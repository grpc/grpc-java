/*
 * Copyright 2018 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.okhttp;

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.internal.SerializingExecutor;
import io.grpc.okhttp.ExceptionHandlingFrameWriter.TransportExceptionHandler;
import java.io.IOException;
import javax.annotation.concurrent.GuardedBy;
import okio.Buffer;
import okio.Sink;
import okio.Timeout;

/**
 * A sink that asynchronously write / flushes a buffer internally. AsyncSink provides flush
 * coalescing to minimize network packing transmit.
 */
final class AsyncSink implements Sink {

  private final Object lock = new Object();
  @GuardedBy("lock")
  private final Buffer buffer = new Buffer();
  private final Sink sink;
  private final SerializingExecutor serializingExecutor;
  private final TransportExceptionHandler transportExceptionHandler;

  @GuardedBy("lock")
  private boolean writeEnqueued = false;
  @GuardedBy("lock")
  private boolean flushEnqueued = false;
  private boolean closed = false;

  private AsyncSink(
      Sink sink, SerializingExecutor executor, TransportExceptionHandler exceptionHandler) {
    this.sink = checkNotNull(sink, "sink");
    this.serializingExecutor = checkNotNull(executor, "executor");
    this.transportExceptionHandler = exceptionHandler;
  }

  static AsyncSink sink(
      Sink sink, SerializingExecutor executor, TransportExceptionHandler exceptionHandler) {
    return new AsyncSink(sink, executor, exceptionHandler);
  }

  @Override
  public void write(final Buffer source, final long byteCount) throws IOException {
    checkNotNull(source, "source");
    if (closed) {
      throw new IOException("closed");
    }
    synchronized (lock) {
      buffer.write(source, byteCount);
      if (writeEnqueued || flushEnqueued || buffer.completeSegmentByteCount() <= 0) {
        return;
      }
      writeEnqueued = true;
    }
    serializingExecutor.execute(new Runnable() {
      @Override
      public void run() {
        Buffer buf = new Buffer();
        synchronized (lock) {
          if (buffer.completeSegmentByteCount() > 0) {
            buf.write(buffer, buffer.completeSegmentByteCount());
          }
          writeEnqueued = false;
        }
        if (buf.size() > 0) {
          try {
            sink.write(buf, buf.size());
          } catch (IOException e) {
            transportExceptionHandler.onException(e);
          }
        }
      }
    });
  }

  @Override
  public void flush() throws IOException {
    if (closed) {
      throw new IOException("closed");
    }
    synchronized (lock) {
      if (flushEnqueued) {
        return;
      }
      flushEnqueued = true;
    }
    serializingExecutor.execute(new Runnable() {
      @Override
      public void run() {
        try {
          Buffer buf = new Buffer();
          synchronized (lock) {
            buf.write(buffer, buffer.size());
            flushEnqueued = false;
          }
          if (buf.size() > 0) {
            sink.write(buf, buf.size());
          }
          sink.flush();
        } catch (IOException e) {
          transportExceptionHandler.onException(e);
        }
      }
    });
  }

  @Override
  public Timeout timeout() {
    return Timeout.NONE;
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    serializingExecutor.execute(new Runnable() {
      @Override
      public void run() {
        synchronized (lock) {
          buffer.close();
          try {
            sink.close();
          } catch (IOException e) {
            transportExceptionHandler.onException(e);
          }
        }
      }
    });
  }
}