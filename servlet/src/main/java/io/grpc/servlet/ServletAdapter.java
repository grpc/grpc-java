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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.internal.GrpcUtil.TIMEOUT_KEY;
import static io.grpc.servlet.ServletServerStream.toHexString;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINEST;

import com.google.common.io.BaseEncoding;
import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.InternalLogId;
import io.grpc.InternalMetadata;
import io.grpc.Metadata;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ReadableBuffers;
import io.grpc.internal.ServerTransportListener;
import io.grpc.internal.StatsTraceContext;
import io.grpc.internal.WritableBufferAllocator;
import io.grpc.servlet.ServletServerStream.ByteArrayWritableBuffer;
import io.grpc.servlet.ServletServerStream.WriteState;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * An adapter that transforms {@link HttpServletRequest} into gRPC request and lets a gRPC server
 * process it, and transforms the gRPC response into {@link HttpServletResponse}. An adapter can be
 * instantiated by {@link Factory#create}.
 *
 * <p>In a servlet, calling {@link #doPost(HttpServletRequest, HttpServletResponse)} inside {@link
 * javax.servlet.http.HttpServlet#doPost(HttpServletRequest, HttpServletResponse)} makes the servlet
 * backed by the gRPC server associated with the adapter. The servlet must support Asynchronous
 * Processing and must be deployed to a container that supports servlet 4.0 and enables HTTP/2.
 *
 * <p>The API is unstable. The authors would like to know more about the real usecases. Users are
 * welcome to provide feedback by commenting on
 * <a href=https://github.com/grpc/grpc-java/issues/5066>the tracking issue</a>.
 */
@io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/5066")
public final class ServletAdapter {

  static final Logger logger = Logger.getLogger(ServletServerStream.class.getName());

  private final ServerTransportListener transportListener;
  private final List<ServerStreamTracer.Factory> streamTracerFactories;
  private final int maxInboundMessageSize;
  private final Attributes attributes;

  ServletAdapter(
      ServerTransportListener transportListener,
      List<ServerStreamTracer.Factory> streamTracerFactories,
      int maxInboundMessageSize) {
    this.transportListener = transportListener;
    this.streamTracerFactories = streamTracerFactories;
    this.maxInboundMessageSize = maxInboundMessageSize;
    attributes = transportListener.transportReady(Attributes.EMPTY);
  }

  /**
   * Call this method inside {@link javax.servlet.http.HttpServlet#doGet(HttpServletRequest,
   * HttpServletResponse)} to serve gRPC GET request.
   *
   * <p>Note that in rare case gRPC client sends GET requests.
   *
   * <p>Do not modify {@code req} and {@code resp} before or after calling this method. However,
   * calling {@code resp.setBufferSize()} before invocation is allowed.
   */
  public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    // TODO(zdapeng)
  }

  /**
   * Call this method inside {@link javax.servlet.http.HttpServlet#doPost(HttpServletRequest,
   * HttpServletResponse)} to serve gRPC POST request.
   *
   * <p>Do not modify {@code req} and {@code resp} before or after calling this method. However,
   * calling {@code resp.setBufferSize()} before invocation is allowed.
   */
  public void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    checkArgument(req.isAsyncSupported(), "servlet does not support asynchronous operation");
    checkArgument(ServletAdapter.isGrpc(req), "the request is not a gRPC request");

    InternalLogId logId = InternalLogId.allocate(getClass().getName());
    logger.log(FINE, "[{0}] RPC started", logId);

    AsyncContext asyncCtx = req.startAsync(req, resp);

    String method = req.getRequestURI().substring(1); // remove the leading "/"
    Metadata headers = getHeaders(req);

    if (logger.isLoggable(FINEST)) {
      logger.log(FINEST, "[{0}] method: {1}", new Object[] {logId, method});
      logger.log(FINEST, "[{0}] headers: {1}", new Object[] {logId, headers});
    }

    Long timeoutNanos = headers.get(TIMEOUT_KEY);
    if (timeoutNanos == null) {
      timeoutNanos = 0L;
    }
    asyncCtx.setTimeout(TimeUnit.NANOSECONDS.toMillis(timeoutNanos));
    StatsTraceContext statsTraceCtx =
        StatsTraceContext.newServerContext(streamTracerFactories, method, headers);
    AtomicReference<WriteState> writeState = new AtomicReference<>(WriteState.DEFAULT);
    WritableBufferAllocator bufferAllocator =
        capacityHint -> new ByteArrayWritableBuffer(capacityHint);

    /*
     * The concurrency for pushing and polling on the writeChain is handled by the WriteState state
     * machine, not by the thread-safety of ConcurrentLinkedDeque. Actually the thread-safety of
     * ConcurrentLinkedDeque alone is neither sufficient nor necessary. A plain singly-linked queue
     * would also work with WriteState, but java library only has ConcurrentLinkedDeque.
     */
    Queue<ByteArrayWritableBuffer> writeChain = new ConcurrentLinkedDeque<>();

    ServletServerStream stream = new ServletServerStream(
        bufferAllocator,
        asyncCtx,
        statsTraceCtx,
        writeState,
        writeChain,
        maxInboundMessageSize,
        Attributes.newBuilder()
            .setAll(attributes)
            .set(
                Grpc.TRANSPORT_ATTR_REMOTE_ADDR,
                new InetSocketAddress(req.getRemoteHost(), req.getRemotePort()))
            .set(
                Grpc.TRANSPORT_ATTR_LOCAL_ADDR,
                new InetSocketAddress(req.getLocalAddr(), req.getLocalPort()))
            .build(),
        getAuthority(req),
        logId);

    asyncCtx.getResponse().getOutputStream().setWriteListener(
        new GrpcWriteListener(stream, asyncCtx, writeState, writeChain, logId));

    transportListener.streamCreated(stream, method, headers);
    stream.transportState().runOnTransportThread(
        () -> stream.transportState().onStreamAllocated());

    asyncCtx.getRequest().getInputStream()
        .setReadListener(new GrpcReadListener(stream, asyncCtx, logId));
    asyncCtx.addListener(new GrpcAsycListener(stream, logId));
  }

  private static Metadata getHeaders(HttpServletRequest req) {
    Enumeration<String> headerNames = req.getHeaderNames();
    checkNotNull(
        headerNames, "Servlet container does not allow HttpServletRequest.getHeaderNames()");
    List<byte[]> byteArrays = new ArrayList<>();
    while (headerNames.hasMoreElements()) {
      String headerName = headerNames.nextElement();
      Enumeration<String> values = req.getHeaders(headerName);
      if (values == null) {
        continue;
      }
      while (values.hasMoreElements()) {
        String value = values.nextElement();
        if (headerName.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
          byteArrays.add(headerName.getBytes(StandardCharsets.US_ASCII));
          byteArrays.add(BaseEncoding.base64().decode(value));
        } else {
          byteArrays.add(headerName.getBytes(StandardCharsets.US_ASCII));
          byteArrays.add(value.getBytes(StandardCharsets.US_ASCII));
        }
      }
    }
    return InternalMetadata.newMetadata(byteArrays.toArray(new byte[][]{}));
  }

  private static String getAuthority(HttpServletRequest req) {
    String authority = req.getRequestURL().toString();
    String uri = req.getRequestURI();
    String scheme = req.getScheme() + "://";
    if (authority.endsWith(uri)) {
      authority = authority.substring(0, authority.length() - uri.length());
    }
    if (authority.startsWith(scheme)) {
      authority = authority.substring(scheme.length());
    }
    return authority;
  }

  /**
   * Call this method when the adapter is no longer needed.
   */
  public void destroy() {
    transportListener.transportTerminated();
  }

  private static final class GrpcAsycListener implements AsyncListener {
    final InternalLogId logId;
    final ServletServerStream stream;

    GrpcAsycListener(ServletServerStream stream, InternalLogId logId) {
      this.stream = stream;
      this.logId = logId;
    }

    @Override
    public void onComplete(AsyncEvent event) {}

    @Override
    public void onTimeout(AsyncEvent event) {
      if (logger.isLoggable(FINE)) {
        logger.log(FINE, String.format("[{%s}] Timeout: ", logId), event.getThrowable());
      }
      // If the resp is not committed, cancel() to avoid being redirected to an error page.
      // Else, the container will send RST_STREAM at the end.
      if (!event.getAsyncContext().getResponse().isCommitted()) {
        stream.cancel(Status.DEADLINE_EXCEEDED);
      } else {
        stream.transportState().runOnTransportThread(
            () -> stream.transportState().transportReportStatus(Status.DEADLINE_EXCEEDED));
      }
    }

    @Override
    public void onError(AsyncEvent event) {
      if (logger.isLoggable(FINE)) {
        logger.log(FINE, String.format("[{%s}] Error: ", logId), event.getThrowable());
      }

      // If the resp is not committed, cancel() to avoid being redirected to an error page.
      // Else, the container will send RST_STREAM at the end.
      if (!event.getAsyncContext().getResponse().isCommitted()) {
        stream.cancel(Status.fromThrowable(event.getThrowable()));
      } else {
        stream.transportState().runOnTransportThread(
            () -> stream.transportState().transportReportStatus(
                Status.fromThrowable(event.getThrowable())));
      }
    }

    @Override
    public void onStartAsync(AsyncEvent event) {}
  }

  private static final class GrpcWriteListener implements WriteListener {
    final ServletServerStream stream;
    final AsyncContext asyncCtx;
    final HttpServletResponse resp;
    final ServletOutputStream output;
    final AtomicReference<WriteState> writeState;
    final Queue<ByteArrayWritableBuffer> writeChain;
    final InternalLogId logId;

    GrpcWriteListener(
        ServletServerStream stream,
        AsyncContext asyncCtx,
        AtomicReference<WriteState> writeState,
        Queue<ByteArrayWritableBuffer> writeChain,
        InternalLogId logId) throws IOException {
      this.stream = stream;
      this.asyncCtx = asyncCtx;
      resp = (HttpServletResponse) asyncCtx.getResponse();
      output = resp.getOutputStream();
      this.writeState = writeState;
      this.writeChain = writeChain;
      this.logId = logId;
    }

    @Override
    public void onWritePossible() throws IOException {
      logger.log(FINEST, "[{0}] onWritePossible: ENTRY", logId);

      WriteState curState = writeState.get();
      // curState.stillWritePossible should have been set to false already or right now/
      // It's very very unlikely stillWritePossible is true due to a race condition
      while (curState.stillWritePossible) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100L));
        curState = writeState.get();
      }

      boolean isReady;
      while ((isReady = output.isReady())) {
        curState = writeState.get();

        ByteArrayWritableBuffer buffer = writeChain.poll();
        if (buffer != null) {
          if (buffer == ByteArrayWritableBuffer.FLUSH) {
            resp.flushBuffer();
          } else {
            output.write(buffer.bytes, 0, buffer.readableBytes());
            stream.transportState().runOnTransportThread(
                () -> stream.transportState().onSentBytes(buffer.readableBytes()));

            if (logger.isLoggable(Level.FINEST)) {
              logger.log(
                  Level.FINEST,
                  "[{0}] outbound data: length = {1}, bytes = {2}",
                  new Object[] {
                      logId,
                      buffer.readableBytes(),
                      toHexString(buffer.bytes, buffer.readableBytes())
                  });
            }
          }
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
        stream.transportState().runOnTransportThread(
            () -> {
              stream.transportState().complete();
              asyncCtx.complete();
            });
        logger.log(FINEST, "[{0}] onWritePossible: call complete", logId);
      }

      logger.log(FINEST, "[{0}] onWritePossible: EXIT", logId);
    }

    @Override
    public void onError(Throwable t) {
      if (logger.isLoggable(FINE)) {
        logger.log(FINE, String.format("[{%s}] Error: ", logId), t);
      }

      // If the resp is not committed, cancel() to avoid being redirected to an error page.
      // Else, the container will send RST_STREAM at the end.
      if (!asyncCtx.getResponse().isCommitted()) {
        stream.cancel(Status.fromThrowable(t));
      } else {
        stream.transportState().runOnTransportThread(
            () -> stream.transportState().transportReportStatus(Status.fromThrowable(t)));
      }
    }
  }

  private static final class GrpcReadListener implements ReadListener {
    final ServletServerStream stream;
    final AsyncContext asyncCtx;
    final ServletInputStream input;
    final InternalLogId logId;

    GrpcReadListener(
        ServletServerStream stream,
        AsyncContext asyncCtx,
        InternalLogId logId) throws IOException {
      this.stream = stream;
      this.asyncCtx = asyncCtx;
      input = asyncCtx.getRequest().getInputStream();
      this.logId = logId;
    }

    final byte[] buffer = new byte[4 * 1024];

    @Override
    public void onDataAvailable() throws IOException {
      logger.log(FINEST, "[{0}] onDataAvailable: ENTRY", logId);

      while (input.isReady()) {
        int length = input.read(buffer);
        if (length == -1) {
          logger.log(FINEST, "[{0}] inbound data: read end of stream", logId);
          return;
        } else {
          if (logger.isLoggable(FINEST)) {
            logger.log(
                FINEST,
                "[{0}] inbound data: length = {1}, bytes = {2}",
                new Object[] {logId, length, toHexString(buffer, length)});
          }

          byte[] copy = Arrays.copyOf(buffer, length);
          stream.transportState().runOnTransportThread(
              () -> stream.transportState().inboundDataReceived(ReadableBuffers.wrap(copy), false));
        }
      }

      logger.log(FINEST, "[{0}] onDataAvailable: EXIT", logId);
    }

    @Override
    public void onAllDataRead() {
      logger.log(FINE, "[{0}] onAllDataRead", logId);
      stream.transportState().runOnTransportThread(() ->
          stream.transportState().inboundDataReceived(ReadableBuffers.wrap(new byte[] {}), true));
    }

    @Override
    public void onError(Throwable t) {
      if (logger.isLoggable(FINE)) {
        logger.log(FINE, String.format("[{%s}] Error: ", logId), t);
      }
      // If the resp is not committed, cancel() to avoid being redirected to an error page.
      // Else, the container will send RST_STREAM at the end.
      if (!asyncCtx.getResponse().isCommitted()) {
        stream.cancel(Status.fromThrowable(t));
      } else {
        stream.transportState().runOnTransportThread(
            () -> stream.transportState()
                .transportReportStatus(Status.fromThrowable(t)));
      }
    }
  }

  /**
   * Checks whether an incoming {@code HttpServletRequest} may come from a gRPC client.
   *
   * @return true if the request comes from a gRPC client
   */
  public static boolean isGrpc(HttpServletRequest request) {
    return request.getContentType() != null
        && request.getContentType().contains(GrpcUtil.CONTENT_TYPE_GRPC);
  }

  /**
   * Factory of ServletAdapter.
   *
   * <p>The API is unstable. The authors would like to know more about the real usecases. Users are
   * welcome to provide feedback by commenting on
   * <a href=https://github.com/grpc/grpc-java/issues/5066>the tracking issue</a>.
   */
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/5066")
  public static final class Factory {

    private Factory() {}

    /**
     * Creates an instance of ServletAdapter. A gRPC server will be built and started with the given
     * {@link ServletServerBuilder}. The servlet using this servletAdapter will power the gRPC
     * server.
     */
    public static ServletAdapter create(ServletServerBuilder serverBuilder) {
      return new ServletAdapter(
          serverBuilder.buildAndStart(),
          serverBuilder.streamTracerFactories,
          serverBuilder.maxInboundMessageSize);
    }
  }
}
