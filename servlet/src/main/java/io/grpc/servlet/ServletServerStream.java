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

package io.grpc.servlet;

import static io.grpc.internal.GrpcUtil.CONTENT_TYPE_GRPC;
import static io.grpc.internal.GrpcUtil.CONTENT_TYPE_KEY;
import static io.grpc.servlet.ServletServerStream.ByteArrayWritableBuffer.FLUSH;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINEST;

import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.AbstractServerStream;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.LogId;
import io.grpc.internal.ReadableBuffer;
import io.grpc.internal.SerializingExecutor;
import io.grpc.internal.StatsTraceContext;
import io.grpc.internal.TransportFrameUtil;
import io.grpc.internal.TransportTracer;
import io.grpc.internal.WritableBuffer;
import io.grpc.internal.WritableBufferAllocator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;

final class ServletServerStream extends AbstractServerStream {

  static final Logger logger = Logger.getLogger(ServletServerStream.class.getName());

  private final TransportState transportState =
      new TransportState(Integer.MAX_VALUE, StatsTraceContext.NOOP, new TransportTracer());
  final Sink sink;
  final AsyncContext asyncCtx;
  final AtomicReference<WriteState> writeState;
  final WritableBufferChain writeChain;
  final ScheduledExecutorService scheduler;
  final LogId logId;

  ServletServerStream(
      WritableBufferAllocator bufferAllocator, AsyncContext asyncCtx,
      AtomicReference<WriteState> writeState, WritableBufferChain writeChain,
      ScheduledExecutorService scheduler, LogId logId) {
    super(bufferAllocator, StatsTraceContext.NOOP);
    this.asyncCtx = asyncCtx;
    this.writeState = writeState;
    this.writeChain = writeChain;
    this.scheduler = scheduler;
    this.logId = logId;
    this.sink = new Sink();
  }

  @Override
  protected TransportState transportState() {
    return transportState;
  }

  @Override
  protected Sink abstractServerStreamSink() {
    return sink;
  }

  static final class TransportState extends io.grpc.internal.AbstractServerStream.TransportState {

    final SerializingExecutor transportThreadExecutor =
        new SerializingExecutor(MoreExecutors.directExecutor());

    TransportState(
        int maxMessageSize, StatsTraceContext statsTraceCtx, TransportTracer transportTracer) {
      super(maxMessageSize, statsTraceCtx, transportTracer);
    }

    @Override
    public void runOnTransportThread(Runnable r) {
      transportThreadExecutor.execute(r);
    }

    @Override
    public void bytesRead(int numBytes) {
      // no-op
      // not able to do flow control
    }

    @Override
    public void deframeFailed(Throwable cause) {
      // TODO
      cause.printStackTrace();
    }

    @Override
    public void inboundDataReceived(ReadableBuffer frame, boolean endOfStream) {
      runOnTransportThread(() -> super.inboundDataReceived(frame, endOfStream));
    }
  }

  static final class ByteArrayWritableBuffer implements WritableBuffer {

    static final ByteArrayWritableBuffer FLUSH = new ByteArrayWritableBuffer();

    private final int capacity;
    final byte[] bytes;
    private int index;

    private ByteArrayWritableBuffer() {
      capacity = 0;
      bytes = new byte[0];
    }

    ByteArrayWritableBuffer(int capacityHint) {
      capacity = min(1024 * 1024,  max(4096, capacityHint));
      bytes = new byte[capacity];
    }

    @Override
    public void write(byte[] src, int srcIndex, int length) {
      System.arraycopy(src, srcIndex, bytes, index, length);
      index += length;
    }

    @Override
    public void write(byte b) {
      bytes[index++] = b;
    }

    @Override
    public int writableBytes() {
      return capacity - index;
    }

    @Override
    public int readableBytes() {
      return index;
    }

    @Override
    public void release() {}
  }

  /**
   * A queue of WritableBuffers. Not safe for multiple concurrent polls, or multiple concurrent
   * enqueues, but safe for concurrently calling one poll() and one enqueue().
   */
  static final class WritableBufferChain {

    private static final class Entry {
      @Nullable
      ByteArrayWritableBuffer buffer;
      @Nullable
      volatile Entry next;
      boolean polled;
    }

    Entry head; // not null
    Entry tail; // not null

    WritableBufferChain() {
      head = new Entry();
      head.polled = true;
      tail = head;
    }

    @Nullable
    ByteArrayWritableBuffer poll() {
      if (head.polled) {
        if (head.next != null) {
          Entry oldHead = head;
          head = oldHead.next;
          oldHead.next = null;
          return poll();
        }
        return null;
      }

      head.polled = true;
      ByteArrayWritableBuffer retVal = head.buffer;
      if (head.next != null) {
        Entry oldHead = head;
        head = head.next;
        oldHead.next = null;
      }
      return retVal;
    }

    void enqueue(@Nonnull ByteArrayWritableBuffer buffer) {
      Entry newTail = new Entry();
      newTail.buffer = buffer;
      tail.next = newTail;
      tail = newTail;
    }
  }

  static final class WriteState {

    static final WriteState DEFAULT = new WriteState(false, false);

    /**
     * {@link javax.servlet.WriteListener#onWritePossible()} exits because currently there is no
     * more data to write, but the last check of {@link javax.servlet.ServletOutputStream#isReady()}
     * is true.
     */
    final boolean stillWritePossible;

    final boolean trailersSent;

    private WriteState(boolean stillWritePossible, boolean trailersSent) {
      this.stillWritePossible = stillWritePossible;
      this.trailersSent = trailersSent;
    }

    @CheckReturnValue
    WriteState withTrailersSent(boolean trailersSent) {
      return new WriteState(stillWritePossible, trailersSent);
    }

    /**
     * Only {@link javax.servlet.WriteListener#onWritePossible()} can set it to true, and only
     * {@link ServletServerStream.Sink#writeFrame} can set it to false;
     */
    @CheckReturnValue
    WriteState withStillWritePossible(boolean stillWritePossible) {
      return new WriteState(stillWritePossible, trailersSent);
    }

    @CheckReturnValue
    WriteState newState() {
      return new WriteState(stillWritePossible, trailersSent);
    }
  }

  final class Sink implements AbstractServerStream.Sink {

    final HttpServletResponse resp;

    Sink() {
      resp = (HttpServletResponse) asyncCtx.getResponse();
    }

    final TrailerSupplier trailerSupplier = new TrailerSupplier();

    @Override
    public void writeHeaders(Metadata headers) {
      // Discard any application supplied duplicates of the reserved headers
      headers.discardAll(CONTENT_TYPE_KEY);
      headers.discardAll(GrpcUtil.TE_HEADER);
      headers.discardAll(GrpcUtil.USER_AGENT_KEY);

      if (logger.isLoggable(FINE)) {
        logger.log(FINE, "[{0}] writeHeaders {1}", new Object[] {logId, headers});
      }

      resp.setStatus(HttpServletResponse.SC_OK);
      resp.setContentType(CONTENT_TYPE_GRPC);

      byte[][] serializedHeaders = TransportFrameUtil.toHttp2Headers(headers);
      for (int i = 0; i < serializedHeaders.length; i += 2) {
        resp.setHeader(
            new String(serializedHeaders[i], StandardCharsets.US_ASCII),
            new String(serializedHeaders[i + 1], StandardCharsets.US_ASCII));
      }
      resp.setTrailerFields(trailerSupplier);
    }

    @Override
    public void writeFrame(@Nullable WritableBuffer frame, boolean flush, int numMessages) {
      if (frame == null && !flush) {
        return;
      }

      if (logger.isLoggable(FINE)) {
        logger.log(
            FINE,
            "[{0}] writeFrame: numBytes = {1}, flush = {2}, numMessages = {3}",
            new Object[]{logId, frame == null ? 0 : frame.readableBytes(), flush, numMessages});
      }

      if (frame != null) {
        writeFrame((ByteArrayWritableBuffer) frame);
      }

      if (flush) {
        writeFrame(FLUSH);
      }
    }

    private void writeFrame(ByteArrayWritableBuffer byteBuffer) {
      int numBytes = byteBuffer.readableBytes();
      if (numBytes > 0) {
        onSendingBytes(numBytes);
      }

      WriteState curState = writeState.get();
      if (curState.stillWritePossible) {
        try {
          ServletOutputStream outputStream = resp.getOutputStream();
          if (byteBuffer == FLUSH) {
            resp.flushBuffer();
          } else {
            outputStream.write(byteBuffer.bytes, 0, byteBuffer.readableBytes());
            transportState().onSentBytes(numBytes);
            if (logger.isLoggable(FINEST)) {
              logger.log(
                  FINEST,
                  "[{0}] outbound data: length = {1}, bytes = {2}",
                  new Object[]{
                      logId, numBytes, toHexString(byteBuffer.bytes, byteBuffer.readableBytes())});
            }
          }
          if (!outputStream.isReady()) {
            while (true) {
              if (writeState.compareAndSet(curState, curState.withStillWritePossible(false))) {
                return;
              }
              curState = writeState.get();
            }
          }
        } catch (IOException ioe) {
          ioe.printStackTrace(); // TODO
        }
      } else {
        writeChain.enqueue(byteBuffer);
        if (!writeState.compareAndSet(curState, curState.newState())) {
          // state changed by another thread, need to check if stillWritePossible again
          if (writeState.get().stillWritePossible && writeChain.poll() != null) {
            writeFrame(byteBuffer);
          }
        }
      }
    }

    @Override
    public void writeTrailers(Metadata trailers, boolean headersSent, Status status) {
      if (logger.isLoggable(FINE)) {
        logger.log(
            FINE,
            "[{0}] writeTrailers: {1}, headersSent = {2}, status = {3}",
            new Object[] {logId, trailers, headersSent, status});
      }
      if (!headersSent) {
        // Discard any application supplied duplicates of the reserved headers
        trailers.discardAll(CONTENT_TYPE_KEY);
        trailers.discardAll(GrpcUtil.TE_HEADER);
        trailers.discardAll(GrpcUtil.USER_AGENT_KEY);

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType(CONTENT_TYPE_GRPC);

        byte[][] serializedHeaders = TransportFrameUtil.toHttp2Headers(trailers);
        for (int i = 0; i < serializedHeaders.length; i += 2) {
          resp.setHeader(
              new String(serializedHeaders[i], StandardCharsets.US_ASCII),
              new String(serializedHeaders[i + 1], StandardCharsets.US_ASCII));
        }
      } else {
        byte[][] serializedHeaders = TransportFrameUtil.toHttp2Headers(trailers);
        IntStream.range(0, serializedHeaders.length)
            .filter(i -> i % 2 == 0)
            .forEach(i ->
                trailerSupplier.get().put(
                    new String(serializedHeaders[i], StandardCharsets.US_ASCII),
                    new String(serializedHeaders[i + 1], StandardCharsets.US_ASCII)));
      }

      while (true) {
        WriteState curState = writeState.get();
        if (curState.stillWritePossible) {
          ServletAdapterImpl.asyncContextComplete(asyncCtx, scheduler);
          logger.log(FINE, "[{0}] writeTrailers: call complete", logId);
          return;
        }
        if (writeState.compareAndSet(curState, curState.withTrailersSent(true))) {
          return;
        }
      }
    }

    @Override
    public void request(int numMessages) {
      transportState().runOnTransportThread(
          () -> transportState().requestMessagesFromDeframer(numMessages));
    }

    @Override
    public void cancel(Status status) {
      // TODO:
      // run in transport thread
    }
  }

  private static final class TrailerSupplier implements Supplier<Map<String, String>> {
    final Map<String, String> trailers = new ConcurrentHashMap<>();

    TrailerSupplier() {}

    @Override
    public Map<String, String> get() {
      return trailers;
    }
  }

  static String toHexString(byte[] bytes, int length) {
    String hex = BaseEncoding.base16().encode(bytes, 0, min(length, 64));
    if (length > 80) {
      hex += "...";
    }
    if (length > 64) {
      int offset = max(64, length - 16);
      hex += BaseEncoding.base16().encode(bytes, offset, length - offset);
    }
    return hex;
  }
}
