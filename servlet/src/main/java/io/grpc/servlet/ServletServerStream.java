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
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINEST;
import static java.util.logging.Level.WARNING;

import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.Attributes;
import io.grpc.InternalLogId;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.internal.AbstractServerStream;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.SerializingExecutor;
import io.grpc.internal.StatsTraceContext;
import io.grpc.internal.TransportFrameUtil;
import io.grpc.internal.TransportTracer;
import io.grpc.internal.WritableBuffer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.servlet.AsyncContext;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;

final class ServletServerStream extends AbstractServerStream {

  private static final Logger logger = Logger.getLogger(ServletServerStream.class.getName());

  private final ServletTransportState transportState;
  private final Sink sink = new Sink();
  private final AsyncContext asyncCtx;
  private final HttpServletResponse resp;
  private final AtomicReference<WriteState> writeState = new AtomicReference<>(WriteState.DEFAULT);
  // SPSC queue would do
  private final Queue<ByteArrayWritableBuffer> writeChain = new ConcurrentLinkedQueue<>();
  private final Attributes attributes;
  private final String authority;
  private final InternalLogId logId;

  ServletServerStream(
      AsyncContext asyncCtx,
      StatsTraceContext statsTraceCtx,
      int maxInboundMessageSize,
      Attributes attributes,
      String authority,
      InternalLogId logId) throws IOException {
    super(ByteArrayWritableBuffer::new, statsTraceCtx);
    transportState =
        new ServletTransportState(maxInboundMessageSize, statsTraceCtx, new TransportTracer());
    this.attributes = attributes;
    this.authority = authority;
    this.logId = logId;
    this.asyncCtx = asyncCtx;
    this.resp = (HttpServletResponse) asyncCtx.getResponse();
    resp.getOutputStream().setWriteListener(new GrpcWriteListener());
  }

  @Override
  protected ServletTransportState transportState() {
    return transportState;
  }

  @Override
  public Attributes getAttributes() {
    return attributes;
  }

  @Override
  public String getAuthority() {
    return authority;
  }

  @Override
  public int streamId() {
    return -1;
  }

  @Override
  protected Sink abstractServerStreamSink() {
    return sink;
  }

  private void writeHeadersToServletResponse(Metadata metadata) {
    // Discard any application supplied duplicates of the reserved headers
    metadata.discardAll(CONTENT_TYPE_KEY);
    metadata.discardAll(GrpcUtil.TE_HEADER);
    metadata.discardAll(GrpcUtil.USER_AGENT_KEY);

    if (logger.isLoggable(FINE)) {
      logger.log(FINE, "[{0}] writeHeaders {1}", new Object[] {logId, metadata});
    }

    resp.setStatus(HttpServletResponse.SC_OK);
    resp.setContentType(CONTENT_TYPE_GRPC);

    byte[][] serializedHeaders = TransportFrameUtil.toHttp2Headers(metadata);
    for (int i = 0; i < serializedHeaders.length; i += 2) {
      resp.addHeader(
          new String(serializedHeaders[i], StandardCharsets.US_ASCII),
          new String(serializedHeaders[i + 1], StandardCharsets.US_ASCII));
    }
  }

  private void writeBufToServletResponse(ByteArrayWritableBuffer buffer)
      throws IOException {
    int numBytes = buffer.readableBytes();
    if (buffer == ByteArrayWritableBuffer.FLUSH) {
      resp.flushBuffer();
    } else {
      resp.getOutputStream().write(buffer.bytes, 0, numBytes);
      transportState.runOnTransportThread(() -> transportState.onSentBytes(numBytes));

      if (logger.isLoggable(Level.FINEST)) {
        logger.log(
            Level.FINEST,
            "[{0}] outbound data: length = {1}, bytes = {2}",
            new Object[] {logId, numBytes, toHexString(buffer.bytes, numBytes)});
      }
    }
  }

  private void callComplete() {
    logger.log(FINE, "[{0}] call is completing", logId);
    transportState.runOnTransportThread(
        () -> {
          transportState.complete();
          asyncCtx.complete();
          logger.log(FINE, "[{0}] call completed", logId);
        });
  }

  final class ServletTransportState extends TransportState {

    private final SerializingExecutor transportThreadExecutor =
        new SerializingExecutor(MoreExecutors.directExecutor());

    private ServletTransportState(
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
      // no flow control yet
    }

    @Override
    public void deframeFailed(Throwable cause) {
      if (logger.isLoggable(FINE)) {
        logger.log(FINE, String.format("[{%s}] Exception processing message", logId), cause);
      }
      cancel(Status.fromThrowable(cause));
    }
  }

  private static final class ByteArrayWritableBuffer implements WritableBuffer {

    static final ByteArrayWritableBuffer FLUSH = new ByteArrayWritableBuffer(new byte[0]);

    private final int capacity;
    final byte[] bytes;
    private int index;

    ByteArrayWritableBuffer(int capacityHint) {
      this(new byte[min(1024 * 1024,  max(4096, capacityHint))]);
    }

    ByteArrayWritableBuffer(byte[] bytes) {
      this.bytes =  bytes;
      this.capacity = bytes.length;
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

  private static final class WriteState {

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

  private final class GrpcWriteListener implements WriteListener {

    @Override
    public void onError(Throwable t) {
      if (logger.isLoggable(FINE)) {
        logger.log(FINE, String.format("[{%s}] Error: ", logId), t);
      }

      // If the resp is not committed, cancel() to avoid being redirected to an error page.
      // Else, the container will send RST_STREAM at the end.
      if (!resp.isCommitted()) {
        cancel(Status.fromThrowable(t));
      } else {
        transportState.runOnTransportThread(
            () -> transportState.transportReportStatus(Status.fromThrowable(t)));
      }
    }

    @Override
    public void onWritePossible() throws IOException {
      logger.log(FINEST, "[{0}] onWritePossible: ENTRY", logId);

      // stillWritePossible should have been set to false already or right now
      // It's very very unlikely stillWritePossible is true due to a race condition
      while (writeState.get().stillWritePossible) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100L));
      }

      boolean isReady;
      while ((isReady = resp.getOutputStream().isReady())) {
        WriteState curState = writeState.get();

        ByteArrayWritableBuffer buffer = writeChain.poll();
        if (buffer != null) {
          writeBufToServletResponse(buffer);
          continue;
        }

        if (writeState.compareAndSet(curState, curState.withStillWritePossible(true))) {
          logger.log(FINEST, "[{0}] set stillWritePossible to true", logId);
          // state has not changed since. It's possible a new entry is just enqueued into the
          // writeChain, but this case is handled right after the enqueuing
          break;
        } // else state changed by another thread, need to drain the writeChain again
      }

      if (isReady && writeState.get().trailersSent) {
        callComplete();
      }

      logger.log(FINEST, "[{0}] onWritePossible: EXIT", logId);
    }
  }

  private void writeBuf(ByteArrayWritableBuffer byteBuffer) {
    WriteState curState = writeState.get();
    if (curState.stillWritePossible) { // write to the outputStream directly
      try {
        writeBufToServletResponse(byteBuffer);
        if (!resp.getOutputStream().isReady()) {
          while (true) {
            if (writeState.compareAndSet(curState, curState.withStillWritePossible(false))) {
              logger.log(FINEST, "[{0}] set stillWritePossible to false", logId);
              return;
            }
            curState = writeState.get();
            assert curState.stillWritePossible;
          }
        }
      } catch (IOException ioe) {
        logger.log(WARNING, String.format("[{%s}] Exception writing message", logId), ioe);
        cancel(Status.fromThrowable(ioe));
      }
    } else { // buffer to the writeChain
      writeChain.offer(byteBuffer);
      if (!writeState.compareAndSet(curState, curState.newState())) {
        // state changed by another thread, need to check if stillWritePossible again
        if (writeState.get().stillWritePossible) {
          ByteArrayWritableBuffer bf = writeChain.poll();
          if (bf != null) {
            assert bf == byteBuffer;
            writeBuf(bf);
          }
        }
      }
    }
  }

  private final class Sink implements AbstractServerStream.Sink {
    final TrailerSupplier trailerSupplier = new TrailerSupplier();

    @Override
    public void writeHeaders(Metadata headers) {
      writeHeadersToServletResponse(headers);
      resp.setTrailerFields(trailerSupplier);
      writeBuf(ByteArrayWritableBuffer.FLUSH);
    }

    @Override
    public void writeFrame(@Nullable WritableBuffer frame, boolean flush, int numMessages) {
      if (frame == null && !flush) {
        return;
      }

      if (logger.isLoggable(FINEST)) {
        logger.log(
            FINEST,
            "[{0}] writeFrame: numBytes = {1}, flush = {2}, numMessages = {3}",
            new Object[]{logId, frame == null ? 0 : frame.readableBytes(), flush, numMessages});
      }

      if (frame != null) {
        int numBytes = frame.readableBytes();
        if (numBytes > 0) {
          onSendingBytes(numBytes);
        }
        writeBuf((ByteArrayWritableBuffer) frame);
      }

      if (flush) {
        writeBuf(ByteArrayWritableBuffer.FLUSH);
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
        writeHeadersToServletResponse(trailers);
      } else {
        byte[][] serializedHeaders = TransportFrameUtil.toHttp2Headers(trailers);
        for (int i = 0; i < serializedHeaders.length; i += 2) {
          String key = new String(serializedHeaders[i], StandardCharsets.US_ASCII);
          String newValue = new String(serializedHeaders[i + 1], StandardCharsets.US_ASCII);
          trailerSupplier.get().computeIfPresent(key, (k, v) -> v + "," + newValue);
          trailerSupplier.get().putIfAbsent(key, newValue);
        }
      }

      while (true) {
        WriteState curState = writeState.get();
        if (curState.stillWritePossible) {
          // in non-error case, this condition means all messages are sent out
          callComplete();
          break;
        } // else, some messages are still in write queue
        if (writeState.compareAndSet(curState, curState.withTrailersSent(true))) {
          logger.log(FINEST, "[{0}] set withTrailersSent to true", logId);
          break;
        }
      }
    }

    @Override
    public void request(int numMessages) {
      transportState.runOnTransportThread(
          () -> transportState.requestMessagesFromDeframer(numMessages));
    }

    @Override
    public void cancel(Status status) {
      if (resp.isCommitted() && Code.DEADLINE_EXCEEDED == status.getCode()) {
        return; // let the servlet timeout, the container will sent RST_STREAM automatically
      }
      transportState.runOnTransportThread(() -> transportState.transportReportStatus(status));
      // There is no way to RST_STREAM with CANCEL code, so write trailers instead
      close(Status.CANCELLED.withCause(status.asRuntimeException()), new Metadata());
      CountDownLatch countDownLatch = new CountDownLatch(1);
      transportState.runOnTransportThread(() -> {
        asyncCtx.complete();
        countDownLatch.countDown();
      });
      try {
        countDownLatch.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static final class TrailerSupplier implements Supplier<Map<String, String>> {
    final Map<String, String> trailers = Collections.synchronizedMap(new HashMap<>());

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
