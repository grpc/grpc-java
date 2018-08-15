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

import io.grpc.Metadata;
import io.grpc.internal.LogId;
import io.grpc.internal.ReadableBuffers;
import io.grpc.internal.ServerTransportListener;
import io.grpc.internal.WritableBufferAllocator;
import io.grpc.servlet.ServletServerStream.ByteArrayWritableBuffer;
import io.grpc.servlet.ServletServerStream.WritableBufferChain;
import io.grpc.servlet.ServletServerStream.WriteState;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
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
 * An implementation of {@link ServletAdapter}.
 */
final class ServletAdapterImpl implements ServletAdapter {

  static final Logger logger = Logger.getLogger(ServletServerStream.class.getName());

  private final ServerTransportListener transportListener;
  private final ScheduledExecutorService scheduler;

  ServletAdapterImpl(
      ServerTransportListener transportListener, ScheduledExecutorService scheduler) {
    this.transportListener = transportListener;
    this.scheduler = checkNotNull(scheduler, "scheduler");
  }

  /**
   * Call this method inside {@link javax.servlet.http.HttpServlet#doGet(HttpServletRequest,
   * HttpServletResponse)}.
   *
   * <p>Do not modify {@code req} and {@code resp} before or after calling this method. However,
   * calling {@code resp.setBufferSize()} before invocation is allowed.
   */
  @Override
  public void doGet(HttpServletRequest req, HttpServletResponse resp) {
    // TODO
  }

  /**
   * Call this method inside {@link javax.servlet.http.HttpServlet#doPost(HttpServletRequest,
   * HttpServletResponse)}.
   *
   * <p>Do not modify {@code req} and {@code resp} before or after calling this method. However,
   * calling {@code resp.setBufferSize()} before invocation is allowed.
   */
  @Override
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
    WritableBufferChain writeChain = new WritableBufferChain();

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

  /** Call this method when the adapter is no longer need. */
  @Override
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
}
