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
class AsyncSink implements Sink {

  private final Object lock = new Object();
  @GuardedBy("lock")
  private final Buffer buffer = new Buffer();
  private final Sink sink;
  private final SerializingExecutor serializingExecutor;
  private final AtomicLong flushVersion = new AtomicLong();
  private boolean closed = false;
  private IOException exception;

  private AsyncSink(Sink sink, SerializingExecutor executor) {
    this.sink = checkNotNull(sink, "sink");
    this.serializingExecutor = checkNotNull(executor, "executor");
  }

  static AsyncSink sink(Sink sink, SerializingExecutor executor) {
    return new AsyncSink(sink, executor);
  }

  @Override
  public void write(final Buffer source, final long byteCount) throws IOException {
    checkNotNull(source, "source");
    if (closed) {
      throw new IOException("closed");
    }
    if (exception != null) {
      closed = true;
      throw exception;
    }
    synchronized (lock) {
      buffer.write(source, byteCount);
    }
    serializingExecutor.execute(new Runnable() {
      @Override
      public void run() {
        try {
          synchronized (lock) {
            if (buffer.size() > 0) {
              sink.write(buffer, buffer.size());
            }
          }
        } catch (IOException e) {
          exception = e;
        }
      }
    });
  }

  @Override
  public void flush() throws IOException {
    if (closed) {
      throw new IOException("closed");
    }
    if (exception != null) {
      closed = true;
      throw exception;
    }
    final long version = flushVersion.incrementAndGet();
    serializingExecutor.execute(new Runnable() {
      @Override
      public void run() {
        try {
          if (version == flushVersion.get()) {
            sink.flush();
          }
        } catch (IOException e) {
          exception = e;
        }
      }
    });
  }

  @Override
  public Timeout timeout() {
    return sink.timeout();
  }

  @Override
  public void close() {
    if (closed || exception == null) {
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
            exception = e;
          }
        }
      }
    });
  }
}