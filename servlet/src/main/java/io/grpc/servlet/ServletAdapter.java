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
import static io.grpc.servlet.ServletServerStream.toHexString;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINEST;

import io.grpc.BindableService;
import io.grpc.Metadata;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.LogId;
import io.grpc.internal.ReadableBuffers;
import io.grpc.internal.ServerTransportListener;
import io.grpc.internal.WritableBufferAllocator;
import io.grpc.servlet.ServletServerStream.ByteArrayWritableBuffer;
import io.grpc.servlet.ServletServerStream.WriteState;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletContext;
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
 */
public final class ServletAdapter {

  static final Logger logger = Logger.getLogger(ServletServerStream.class.getName());

  private final ServerTransportListener transportListener;
  private final ScheduledExecutorService scheduler;

  ServletAdapter(
      ServerTransportListener transportListener, ScheduledExecutorService scheduler) {
    this.transportListener = transportListener;
    this.scheduler = checkNotNull(scheduler, "scheduler");
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
    // TODO
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
    checkArgument(ServletAdapter.isGrpc(req), "req is not a gRPC request");

    LogId logId = LogId.allocate(getClass().getName());
    logger.log(FINE, "[{0}] RPC started", logId);

    String method = req.getRequestURI().substring(1); // remove the leading "/"
    Metadata headers = new Metadata();

    AtomicReference<WriteState> writeState = new AtomicReference<>(WriteState.DEFAULT);
    AsyncContext asyncCtx = req.startAsync();

    ServletOutputStream output = asyncCtx.getResponse().getOutputStream();

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
        bufferAllocator, asyncCtx, writeState, writeChain, scheduler, logId);
    transportListener.streamCreated(stream, method, headers);
    stream.transportState().onStreamAllocated();

    output.setWriteListener(
        new WriteListener() {
          @Override
          public void onWritePossible() throws IOException {
            logger.log(FINE, "[{0}] onWritePossible", logId);

            WriteState curState = writeState.get();
            // curState.stillWritePossible should have been set to false already or right now
            while (curState.stillWritePossible) {
              // it's very unlikely this happens due to a race condition
              Thread.yield();
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
                  stream.transportState().onSentBytes(buffer.readableBytes());

                  if (logger.isLoggable(Level.FINEST)) {
                    logger.log(
                        Level.FINEST,
                        "[{0}] outbound data: length = {1}, bytes = {2}",
                        new Object[]{
                            logId, buffer.readableBytes(),
                            toHexString(buffer.bytes, buffer.readableBytes())});
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
              asyncContextComplete(asyncCtx, scheduler);

              logger.log(FINE, "[{0}] onWritePossible: call complete", logId);
            }
          }

          @Override
          public void onError(Throwable t) {
            // TODO
            t.printStackTrace();
          }
        });

    ServletInputStream input = asyncCtx.getRequest().getInputStream();
    input.setReadListener(
        new ReadListener() {
          volatile boolean allDataRead;
          final byte[] buffer = new byte[4 * 1024];

          @Override
          public void onDataAvailable() throws IOException {
            logger.log(FINE, "[{0}] onDataAvailable", logId);
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
                      new Object[]{logId, length, toHexString(buffer, length)});
                }

                stream
                    .transportState()
                    .inboundDataReceived(
                        ReadableBuffers.wrap(Arrays.copyOf(buffer, length)), false);
              }
            }
          }

          @SuppressWarnings("FutureReturnValueIgnored")
          @Override
          public void onAllDataRead() {
            logger.log(FINE, "[{0}] onAllDataRead", logId);
            if (input.isFinished() && !allDataRead) {
              allDataRead = true;
              ServletContext servletContext = asyncCtx.getRequest().getServletContext();
              if (servletContext != null
                  && servletContext.getServerInfo().contains("GlassFish Server")
                  && servletContext.getServerInfo().contains("5.0")) {
                // Glassfish workaround only:
                // otherwise client may flakily fail with "INTERNAL: Half-closed without a request"
                // for server streaming
                scheduler.schedule(
                    () ->
                        stream
                            .transportState()
                            .inboundDataReceived(ReadableBuffers.wrap(new byte[] {}), true),
                    1,
                    TimeUnit.MILLISECONDS);
              } else {
                stream
                    .transportState()
                    .inboundDataReceived(ReadableBuffers.wrap(new byte[] {}), true);
              }
            }
          }

          @Override
          public void onError(Throwable t) {
            // TODO
            t.printStackTrace();
          }
        });
  }

  /**
   * Call this method before the adapter is in use.
   */
  @PostConstruct
  public void init() {}

  /**
   * Call this method when the adapter is no longer need.
   */
  @PreDestroy
  public void destroy() {
    transportListener.transportTerminated();
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  static void asyncContextComplete(AsyncContext asyncContext, ScheduledExecutorService scheduler) {
    ServletContext servletContext = asyncContext.getRequest().getServletContext();
    if (servletContext != null
        && servletContext.getServerInfo().contains("GlassFish Server Open Source Edition  5.0")) {
      // Glassfish workaround only:
      // otherwise client may receive Encountered end-of-stream mid-frame for
      // server/bidi streaming
      scheduler.schedule(() -> asyncContext.complete(), 100, TimeUnit.MILLISECONDS);
      return;
    }

    asyncContext.complete();
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

  /** Factory of ServletAdapter. */
  public static final class Factory {

    private Factory() {}

    /**
     * Creates an instance of ServletAdapter. A gRPC server will be built and started with the given
     * {@link ServletServerBuilder}. The servlet using this servletAdapter will be backed by the
     * gRPC server.
     */
    public static ServletAdapter create(ServletServerBuilder serverBuilder) {
      ServerTransportListener listener = serverBuilder.buildAndStart();
      return new ServletAdapter(listener, serverBuilder.getScheduledExecutorService());
    }

    /**
     * Creates an instance of ServletAdapter. A gRPC server with the given services and default
     * settings will be built and started. The servlet using this servletAdapter will be backed by
     * the gRPC server.
     */
    public static ServletAdapter create(List<? extends BindableService> services) {
      ServletServerBuilder serverBuilder = new ServletServerBuilder();
      services.forEach(service -> serverBuilder.addService(service));
      return create(serverBuilder);
    }
  }
}
