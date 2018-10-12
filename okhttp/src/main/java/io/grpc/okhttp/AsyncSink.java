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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.internal.SerializingExecutor;
import io.grpc.okhttp.ExceptionHandlingFrameWriter.TransportExceptionHandler;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.GuardedBy;
import okio.Buffer;
import okio.Sink;
import okio.Timeout;

/**
 * A sink that asynchronously write / flushes a buffer internally. AsyncSink provides flush
 * coalescing to minimize network packing transmit.
 */
final class AsyncSink implements Sink {

  /**
   * When internal buffer exceeds the size of COMPLETE_SEGMENT_SIZE, AsyncSink asynchronously
   * writes to the sink.
   */
  @VisibleForTesting
  static final long COMPLETE_SEGMENT_SIZE = 8192L;

  private final Object lock = new Object();
  @GuardedBy("lock")
  private final Buffer buffer = new Buffer();
  private final Sink sink;
  private final SerializingExecutor serializingExecutor;
  private final AtomicLong flushVersion = new AtomicLong();
  private final TransportExceptionHandler transportExceptionHandler;
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
    }
    serializingExecutor.execute(new Runnable() {
      @Override
      public void run() {
        Buffer buf = new Buffer();
        synchronized (lock) {
          while (buffer.size() >= COMPLETE_SEGMENT_SIZE) {
            buf.write(buffer, COMPLETE_SEGMENT_SIZE);
          }
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
    final long version = flushVersion.incrementAndGet();
    serializingExecutor.execute(new Runnable() {
      @Override
      public void run() {
        try {
          if (version == flushVersion.get()) {
            Buffer buf = new Buffer();
            synchronized (lock) {
              buf.write(buffer, buffer.size());
            }
            if (buf.size() > 0) {
              sink.write(buf, buf.size());
            }
            sink.flush();
          }
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