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
import static com.google.common.base.Preconditions.checkState;

import io.grpc.internal.SerializingExecutor;
import io.grpc.okhttp.ExceptionHandlingFrameWriter.TransportExceptionHandler;
import io.grpc.okhttp.internal.framed.ErrorCode;
import io.grpc.okhttp.internal.framed.FrameWriter;
import io.grpc.okhttp.internal.framed.Settings;
import io.perfmark.Link;
import io.perfmark.PerfMark;
import io.perfmark.TaskCloseable;
import java.io.IOException;
import java.net.Socket;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import okio.Buffer;
import okio.Sink;
import okio.Timeout;

/**
 * A sink that asynchronously write / flushes a buffer internally. AsyncSink provides flush
 * coalescing to minimize network packing transmit. Because I/O is handled asynchronously, most I/O
 * exceptions will be delivered via a callback.
 */
final class AsyncSink implements Sink {

  private final Object lock = new Object();
  private final Buffer buffer = new Buffer();
  private final SerializingExecutor serializingExecutor;
  private final TransportExceptionHandler transportExceptionHandler;
  private final int maxQueuedControlFrames;

  @GuardedBy("lock")
  private boolean writeEnqueued = false;
  @GuardedBy("lock")
  private boolean flushEnqueued = false;
  private boolean closed = false;
  @Nullable
  private Sink sink;
  @Nullable
  private Socket socket;
  private boolean controlFramesExceeded;
  private int controlFramesInWrite;
  @GuardedBy("lock")
  private int queuedControlFrames;

  private AsyncSink(SerializingExecutor executor, TransportExceptionHandler exceptionHandler,
      int maxQueuedControlFrames) {
    this.serializingExecutor = checkNotNull(executor, "executor");
    this.transportExceptionHandler = checkNotNull(exceptionHandler, "exceptionHandler");
    this.maxQueuedControlFrames = maxQueuedControlFrames;
  }

  /**
   * {@code maxQueuedControlFrames} is only effective for frames written with
   * {@link #limitControlFramesWriter(FrameWriter)}.
   */
  static AsyncSink sink(
      SerializingExecutor executor, TransportExceptionHandler exceptionHandler,
      int maxQueuedControlFrames) {
    return new AsyncSink(executor, exceptionHandler, maxQueuedControlFrames);
  }

  /**
   * Sets the actual sink. It is allowed to call write / flush operations on the sink iff calling
   * this method is scheduled in the executor. The socket is needed for closing.
   *
   * <p>should only be called once by thread of executor.
   */
  void becomeConnected(Sink sink, Socket socket) {
    checkState(this.sink == null, "AsyncSink's becomeConnected should only be called once.");
    this.sink = checkNotNull(sink, "sink");
    this.socket = checkNotNull(socket, "socket");
  }

  FrameWriter limitControlFramesWriter(FrameWriter delegate) {
    return new LimitControlFramesWriter(delegate);
  }

  @Override
  public void write(Buffer source, long byteCount) throws IOException {
    checkNotNull(source, "source");
    if (closed) {
      throw new IOException("closed");
    }
    try (TaskCloseable ignore = PerfMark.traceTask("AsyncSink.write")) {
      boolean closeSocket = false;
      synchronized (lock) {
        buffer.write(source, byteCount);

        queuedControlFrames += controlFramesInWrite;
        controlFramesInWrite = 0;
        if (!controlFramesExceeded && queuedControlFrames > maxQueuedControlFrames) {
          controlFramesExceeded = true;
          closeSocket = true;
        } else {
          if (writeEnqueued || flushEnqueued || buffer.completeSegmentByteCount() <= 0) {
            return;
          }
          writeEnqueued = true;
        }
      }
      if (closeSocket) {
        try {
          socket.close();
        } catch (IOException e) {
          transportExceptionHandler.onException(e);
        }
        return;
      }
      serializingExecutor.execute(new WriteRunnable() {
        final Link link = PerfMark.linkOut();
        @Override
        public void doRun() throws IOException {
          Buffer buf = new Buffer();
          try (TaskCloseable ignore = PerfMark.traceTask("WriteRunnable.runWrite")) {
            PerfMark.linkIn(link);
            int writingControlFrames;
            synchronized (lock) {
              buf.write(buffer, buffer.completeSegmentByteCount());
              writeEnqueued = false;
              // Imprecise because we only tranfer complete segments, but not by much and error
              // won't accumulate over time
              writingControlFrames = queuedControlFrames;
            }
            sink.write(buf, buf.size());
            synchronized (lock) {
              queuedControlFrames -= writingControlFrames;
            }
          }
        }
      });
    }
  }

  @Override
  public void flush() throws IOException {
    if (closed) {
      throw new IOException("closed");
    }
    try (TaskCloseable ignore = PerfMark.traceTask("AsyncSink.flush")) {
      synchronized (lock) {
        if (flushEnqueued) {
          return;
        }
        flushEnqueued = true;
      }
      serializingExecutor.execute(new WriteRunnable() {
        final Link link = PerfMark.linkOut();
        @Override
        public void doRun() throws IOException {
          Buffer buf = new Buffer();
          try (TaskCloseable ignore = PerfMark.traceTask("WriteRunnable.runFlush")) {
            PerfMark.linkIn(link);
            synchronized (lock) {
              buf.write(buffer, buffer.size());
              flushEnqueued = false;
            }
            sink.write(buf, buf.size());
            sink.flush();
          }
        }
      });
    }
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
        try {
          if (sink != null && buffer.size() > 0) {
            sink.write(buffer, buffer.size());
          }
        } catch (IOException e) {
          transportExceptionHandler.onException(e);
        }
        buffer.close();
        try {
          if (sink != null) {
            sink.close();
          }
        } catch (IOException e) {
          transportExceptionHandler.onException(e);
        }
        try {
          if (socket != null) {
            socket.close();
          }
        } catch (IOException e) {
          transportExceptionHandler.onException(e);
        }
      }
    });
  }

  private abstract class WriteRunnable implements Runnable {
    @Override
    public final void run() {
      try {
        if (sink == null) {
          throw new IOException("Unable to perform write due to unavailable sink.");
        }
        doRun();
      } catch (Exception e) {
        transportExceptionHandler.onException(e);
      }
    }

    public abstract void doRun() throws IOException;
  }

  private class LimitControlFramesWriter extends ForwardingFrameWriter {
    public LimitControlFramesWriter(FrameWriter delegate) {
      super(delegate);
    }

    @Override
    public void ackSettings(Settings peerSettings) throws IOException {
      controlFramesInWrite++;
      super.ackSettings(peerSettings);
    }

    @Override
    public void rstStream(int streamId, ErrorCode errorCode) throws IOException {
      controlFramesInWrite++;
      super.rstStream(streamId, errorCode);
    }

    @Override
    public void ping(boolean ack, int payload1, int payload2) throws IOException {
      if (ack) {
        controlFramesInWrite++;
      }
      super.ping(ack, payload1, payload2);
    }
  }
}
