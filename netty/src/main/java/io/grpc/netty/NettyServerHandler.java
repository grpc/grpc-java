/*
 * Copyright 2014 The gRPC Authors
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

package io.grpc.netty;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.internal.GrpcUtil.SERVER_KEEPALIVE_TIME_NANOS_DISABLED;
import static io.grpc.netty.NettyServerBuilder.MAX_CONNECTION_AGE_GRACE_NANOS_INFINITE;
import static io.grpc.netty.NettyServerBuilder.MAX_CONNECTION_AGE_NANOS_DISABLED;
import static io.grpc.netty.NettyServerBuilder.MAX_CONNECTION_IDLE_NANOS_DISABLED;
import static io.grpc.netty.Utils.CONTENT_TYPE_HEADER;
import static io.grpc.netty.Utils.HTTP_METHOD;
import static io.grpc.netty.Utils.TE_HEADER;
import static io.grpc.netty.Utils.TE_TRAILERS;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static io.netty.handler.codec.http2.DefaultHttp2LocalFlowController.DEFAULT_WINDOW_UPDATE_RATIO;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.AUTHORITY;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.InternalChannelz;
import io.grpc.InternalMetadata;
import io.grpc.InternalStatus;
import io.grpc.Metadata;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.KeepAliveEnforcer;
import io.grpc.internal.KeepAliveManager;
import io.grpc.internal.LogExceptionRunnable;
import io.grpc.internal.MaxConnectionIdleManager;
import io.grpc.internal.ServerTransportListener;
import io.grpc.internal.StatsTraceContext;
import io.grpc.internal.TransportTracer;
import io.grpc.netty.GrpcHttp2HeadersUtils.GrpcHttp2ServerHeadersDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.DecoratingHttp2FrameWriter;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionDecoder;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionEncoder;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2LocalFlowController;
import io.netty.handler.codec.http2.DefaultHttp2RemoteFlowController;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionAdapter;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Exception.StreamException;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2FrameReader;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersDecoder;
import io.netty.handler.codec.http2.Http2InboundFrameLogger;
import io.netty.handler.codec.http2.Http2OutboundFrameLogger;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.handler.codec.http2.Http2StreamVisitor;
import io.netty.handler.codec.http2.WeightedFairQueueByteDistributor;
import io.netty.handler.logging.LogLevel;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import io.perfmark.PerfMark;
import io.perfmark.Tag;
import io.perfmark.TaskCloseable;
import java.text.MessageFormat;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

/**
 * Server-side Netty handler for GRPC processing. All event handlers are executed entirely within
 * the context of the Netty Channel thread.
 */
class NettyServerHandler extends AbstractNettyHandler {
  private static final Logger logger = Logger.getLogger(NettyServerHandler.class.getName());
  private static final long KEEPALIVE_PING = 0xDEADL;
  @VisibleForTesting
  static final long GRACEFUL_SHUTDOWN_PING = 0x97ACEF001L;
  private static final long GRACEFUL_SHUTDOWN_PING_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
  /** Temporary workaround for #8674. Fine to delete after v1.45 release, and maybe earlier. */
  private static final boolean DISABLE_CONNECTION_HEADER_CHECK = Boolean.parseBoolean(
      System.getProperty("io.grpc.netty.disableConnectionHeaderCheck", "false"));

  private final Http2Connection.PropertyKey streamKey;
  private final ServerTransportListener transportListener;
  private final int maxMessageSize;
  private final long keepAliveTimeInNanos;
  private final long keepAliveTimeoutInNanos;
  private final long maxConnectionAgeInNanos;
  private final long maxConnectionAgeGraceInNanos;
  private final int maxRstCount;
  private final long maxRstPeriodNanos;
  private final List<? extends ServerStreamTracer.Factory> streamTracerFactories;
  private final TransportTracer transportTracer;
  private final KeepAliveEnforcer keepAliveEnforcer;
  private final Attributes eagAttributes;
  private final Ticker ticker;
  /** Incomplete attributes produced by negotiator. */
  private Attributes negotiationAttributes;
  private InternalChannelz.Security securityInfo;
  /** Completed attributes produced by transportReady. */
  private Attributes attributes;
  private Throwable connectionError;
  private boolean teWarningLogged;
  private WriteQueue serverWriteQueue;
  private AsciiString lastKnownAuthority;
  @CheckForNull
  private KeepAliveManager keepAliveManager;
  @CheckForNull
  private MaxConnectionIdleManager maxConnectionIdleManager;
  @CheckForNull
  private ScheduledFuture<?> maxConnectionAgeMonitor;
  @CheckForNull
  private GracefulShutdown gracefulShutdown;
  private int rstCount;
  private long lastRstNanoTime;

  static NettyServerHandler newHandler(
      ServerTransportListener transportListener,
      ChannelPromise channelUnused,
      List<? extends ServerStreamTracer.Factory> streamTracerFactories,
      TransportTracer transportTracer,
      int maxStreams,
      boolean autoFlowControl,
      int flowControlWindow,
      int maxHeaderListSize,
      int softLimitHeaderListSize,
      int maxMessageSize,
      long keepAliveTimeInNanos,
      long keepAliveTimeoutInNanos,
      long maxConnectionIdleInNanos,
      long maxConnectionAgeInNanos,
      long maxConnectionAgeGraceInNanos,
      boolean permitKeepAliveWithoutCalls,
      long permitKeepAliveTimeInNanos,
      int maxRstCount,
      long maxRstPeriodNanos,
      Attributes eagAttributes) {
    Preconditions.checkArgument(maxHeaderListSize > 0, "maxHeaderListSize must be positive: %s",
        maxHeaderListSize);
    Http2FrameLogger frameLogger = new Http2FrameLogger(LogLevel.DEBUG, NettyServerHandler.class);
    Http2HeadersDecoder headersDecoder = new GrpcHttp2ServerHeadersDecoder(maxHeaderListSize);
    Http2FrameReader frameReader = new Http2InboundFrameLogger(
        new DefaultHttp2FrameReader(headersDecoder), frameLogger);
    Http2FrameWriter frameWriter =
        new Http2OutboundFrameLogger(new DefaultHttp2FrameWriter(), frameLogger);
    return newHandler(
        channelUnused,
        frameReader,
        frameWriter,
        transportListener,
        streamTracerFactories,
        transportTracer,
        maxStreams,
        autoFlowControl,
        flowControlWindow,
        maxHeaderListSize,
        softLimitHeaderListSize,
        maxMessageSize,
        keepAliveTimeInNanos,
        keepAliveTimeoutInNanos,
        maxConnectionIdleInNanos,
        maxConnectionAgeInNanos,
        maxConnectionAgeGraceInNanos,
        permitKeepAliveWithoutCalls,
        permitKeepAliveTimeInNanos,
        maxRstCount,
        maxRstPeriodNanos,
        eagAttributes,
        Ticker.systemTicker());
  }

  static NettyServerHandler newHandler(
      ChannelPromise channelUnused,
      Http2FrameReader frameReader,
      Http2FrameWriter frameWriter,
      ServerTransportListener transportListener,
      List<? extends ServerStreamTracer.Factory> streamTracerFactories,
      TransportTracer transportTracer,
      int maxStreams,
      boolean autoFlowControl,
      int flowControlWindow,
      int maxHeaderListSize,
      int softLimitHeaderListSize,
      int maxMessageSize,
      long keepAliveTimeInNanos,
      long keepAliveTimeoutInNanos,
      long maxConnectionIdleInNanos,
      long maxConnectionAgeInNanos,
      long maxConnectionAgeGraceInNanos,
      boolean permitKeepAliveWithoutCalls,
      long permitKeepAliveTimeInNanos,
      int maxRstCount,
      long maxRstPeriodNanos,
      Attributes eagAttributes,
      Ticker ticker) {
    Preconditions.checkArgument(maxStreams > 0, "maxStreams must be positive: %s", maxStreams);
    Preconditions.checkArgument(flowControlWindow > 0, "flowControlWindow must be positive: %s",
        flowControlWindow);
    Preconditions.checkArgument(maxHeaderListSize > 0, "maxHeaderListSize must be positive: %s",
        maxHeaderListSize);
    Preconditions.checkArgument(
        softLimitHeaderListSize > 0, "softLimitHeaderListSize must be positive: %s",
        softLimitHeaderListSize);
    Preconditions.checkArgument(maxMessageSize > 0, "maxMessageSize must be positive: %s",
        maxMessageSize);

    final Http2Connection connection = new DefaultHttp2Connection(true);
    WeightedFairQueueByteDistributor dist = new WeightedFairQueueByteDistributor(connection);
    dist.allocationQuantum(16 * 1024); // Make benchmarks fast again.
    DefaultHttp2RemoteFlowController controller =
        new DefaultHttp2RemoteFlowController(connection, dist);
    connection.remote().flowController(controller);
    final KeepAliveEnforcer keepAliveEnforcer = new KeepAliveEnforcer(
        permitKeepAliveWithoutCalls, permitKeepAliveTimeInNanos, TimeUnit.NANOSECONDS);

    // Create the local flow controller configured to auto-refill the connection window.
    connection.local().flowController(
        new DefaultHttp2LocalFlowController(connection, DEFAULT_WINDOW_UPDATE_RATIO, true));
    frameWriter = new WriteMonitoringFrameWriter(frameWriter, keepAliveEnforcer);
    Http2ConnectionEncoder encoder =
        new DefaultHttp2ConnectionEncoder(connection, frameWriter);
    encoder = new Http2ControlFrameLimitEncoder(encoder, 10000);
    Http2ConnectionDecoder decoder = new DefaultHttp2ConnectionDecoder(connection, encoder,
        frameReader);

    Http2Settings settings = new Http2Settings();
    settings.initialWindowSize(flowControlWindow);
    settings.maxConcurrentStreams(maxStreams);
    settings.maxHeaderListSize(maxHeaderListSize);

    if (ticker == null) {
      ticker = Ticker.systemTicker();
    }

    return new NettyServerHandler(
        channelUnused,
        connection,
        transportListener,
        streamTracerFactories,
        transportTracer,
        decoder, encoder, settings,
        maxMessageSize,
        maxHeaderListSize,
        softLimitHeaderListSize,
        keepAliveTimeInNanos,
        keepAliveTimeoutInNanos,
        maxConnectionIdleInNanos,
        maxConnectionAgeInNanos, maxConnectionAgeGraceInNanos,
        keepAliveEnforcer,
        autoFlowControl,
        maxRstCount,
        maxRstPeriodNanos,
        eagAttributes, ticker);
  }

  private NettyServerHandler(
      ChannelPromise channelUnused,
      final Http2Connection connection,
      ServerTransportListener transportListener,
      List<? extends ServerStreamTracer.Factory> streamTracerFactories,
      TransportTracer transportTracer,
      Http2ConnectionDecoder decoder,
      Http2ConnectionEncoder encoder,
      Http2Settings settings,
      int maxMessageSize,
      int maxHeaderListSize,
      int softLimitHeaderListSize,
      long keepAliveTimeInNanos,
      long keepAliveTimeoutInNanos,
      long maxConnectionIdleInNanos,
      long maxConnectionAgeInNanos,
      long maxConnectionAgeGraceInNanos,
      final KeepAliveEnforcer keepAliveEnforcer,
      boolean autoFlowControl,
      int maxRstCount,
      long maxRstPeriodNanos,
      Attributes eagAttributes,
      Ticker ticker) {
    super(
        channelUnused,
        decoder,
        encoder,
        settings,
        new ServerChannelLogger(),
        autoFlowControl,
        null,
        ticker,
        maxHeaderListSize,
        softLimitHeaderListSize);

    final MaxConnectionIdleManager maxConnectionIdleManager;
    if (maxConnectionIdleInNanos == MAX_CONNECTION_IDLE_NANOS_DISABLED) {
      maxConnectionIdleManager = null;
    } else {
      maxConnectionIdleManager = new MaxConnectionIdleManager(maxConnectionIdleInNanos);
    }

    connection.addListener(new Http2ConnectionAdapter() {
      @Override
      public void onStreamActive(Http2Stream stream) {
        if (connection.numActiveStreams() == 1) {
          keepAliveEnforcer.onTransportActive();
          if (maxConnectionIdleManager != null) {
            maxConnectionIdleManager.onTransportActive();
          }
        }
      }

      @Override
      public void onStreamClosed(Http2Stream stream) {
        if (connection.numActiveStreams() == 0) {
          keepAliveEnforcer.onTransportIdle();
          if (maxConnectionIdleManager != null) {
            maxConnectionIdleManager.onTransportIdle();
          }
        }
      }
    });

    checkArgument(maxMessageSize >= 0, "maxMessageSize must be non-negative: %s", maxMessageSize);
    this.maxMessageSize = maxMessageSize;
    this.keepAliveTimeInNanos = keepAliveTimeInNanos;
    this.keepAliveTimeoutInNanos = keepAliveTimeoutInNanos;
    this.maxConnectionIdleManager = maxConnectionIdleManager;
    this.maxConnectionAgeInNanos = maxConnectionAgeInNanos;
    this.maxConnectionAgeGraceInNanos = maxConnectionAgeGraceInNanos;
    this.keepAliveEnforcer = checkNotNull(keepAliveEnforcer, "keepAliveEnforcer");
    this.maxRstCount = maxRstCount;
    this.maxRstPeriodNanos = maxRstPeriodNanos;
    this.eagAttributes = checkNotNull(eagAttributes, "eagAttributes");
    this.ticker = checkNotNull(ticker, "ticker");

    this.lastRstNanoTime = ticker.read();
    streamKey = encoder.connection().newKey();
    this.transportListener = checkNotNull(transportListener, "transportListener");
    this.streamTracerFactories = checkNotNull(streamTracerFactories, "streamTracerFactories");
    this.transportTracer = checkNotNull(transportTracer, "transportTracer");
    // Set the frame listener on the decoder.
    decoder().frameListener(new FrameListener());
  }

  @Nullable
  Throwable connectionError() {
    return connectionError;
  }

  @Override
  public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
    serverWriteQueue = new WriteQueue(ctx.channel());

    // init max connection age monitor
    if (maxConnectionAgeInNanos != MAX_CONNECTION_AGE_NANOS_DISABLED) {
      maxConnectionAgeMonitor = ctx.executor().schedule(
          new LogExceptionRunnable(new Runnable() {
            @Override
            public void run() {
              if (gracefulShutdown == null) {
                gracefulShutdown = new GracefulShutdown("max_age", maxConnectionAgeGraceInNanos);
                gracefulShutdown.start(ctx);
                ctx.flush();
              }
            }
          }),
          maxConnectionAgeInNanos,
          TimeUnit.NANOSECONDS);
    }

    if (maxConnectionIdleManager != null) {
      maxConnectionIdleManager.start(new Runnable() {
        @Override
        public void run() {
          if (gracefulShutdown == null) {
            gracefulShutdown = new GracefulShutdown("max_idle", null);
            gracefulShutdown.start(ctx);
            ctx.flush();
          }
        }
      }, ctx.executor());
    }

    if (keepAliveTimeInNanos != SERVER_KEEPALIVE_TIME_NANOS_DISABLED) {
      keepAliveManager = new KeepAliveManager(new KeepAlivePinger(ctx), ctx.executor(),
          keepAliveTimeInNanos, keepAliveTimeoutInNanos, true /* keepAliveDuringTransportIdle */);
      keepAliveManager.onTransportStarted();
    }

    assert encoder().connection().equals(decoder().connection());
    transportTracer.setFlowControlWindowReader(new Utils.FlowControlReader(encoder().connection()));

    super.handlerAdded(ctx);
  }

  private void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers)
      throws Http2Exception {
    try {
      // Connection-specific header fields makes a request malformed. Ideally this would be handled
      // by Netty. RFC 7540 section 8.1.2.2
      if (!DISABLE_CONNECTION_HEADER_CHECK && headers.contains(CONNECTION)) {
        resetStream(ctx, streamId, Http2Error.PROTOCOL_ERROR.code(), ctx.newPromise());
        return;
      }

      if (headers.authority() == null) {
        List<CharSequence> hosts = headers.getAll(HOST);
        if (hosts.size() > 1) {
          // RFC 7230 section 5.4
          respondWithHttpError(ctx, streamId, 400, Status.Code.INTERNAL,
              "Multiple host headers");
          return;
        }
        if (!hosts.isEmpty()) {
          headers.add(AUTHORITY.value(), hosts.get(0));
        }
      }
      headers.remove(HOST);

      // Remove the leading slash of the path and get the fully qualified method name
      CharSequence path = headers.path();

      if (path == null) {
        respondWithHttpError(ctx, streamId, 404, Status.Code.UNIMPLEMENTED,
            "Expected path but is missing");
        return;
      }

      if (path.charAt(0) != '/') {
        respondWithHttpError(ctx, streamId, 404, Status.Code.UNIMPLEMENTED,
            String.format("Expected path to start with /: %s", path));
        return;
      }

      String method = path.subSequence(1, path.length()).toString();

      // Verify that the Content-Type is correct in the request.
      CharSequence contentType = headers.get(CONTENT_TYPE_HEADER);
      if (contentType == null) {
        respondWithHttpError(
            ctx, streamId, 415, Status.Code.INTERNAL, "Content-Type is missing from the request");
        return;
      }
      String contentTypeString = contentType.toString();
      if (!GrpcUtil.isGrpcContentType(contentTypeString)) {
        respondWithHttpError(ctx, streamId, 415, Status.Code.INTERNAL,
            String.format("Content-Type '%s' is not supported", contentTypeString));
        return;
      }

      if (!HTTP_METHOD.contentEquals(headers.method())) {
        respondWithHttpError(ctx, streamId, 405, Status.Code.INTERNAL,
            String.format("Method '%s' is not supported", headers.method()));
        return;
      }

      int h2HeadersSize = Utils.getH2HeadersSize(headers);
      if (Utils.shouldRejectOnMetadataSizeSoftLimitExceeded(
              h2HeadersSize, softLimitHeaderListSize, maxHeaderListSize)) {
        respondWithHttpError(ctx, streamId, 431, Status.Code.RESOURCE_EXHAUSTED, String.format(
                "Client Headers of size %d exceeded Metadata size soft limit: %d",
                h2HeadersSize,
                softLimitHeaderListSize));
        return;
      }

      if (!teWarningLogged && !TE_TRAILERS.contentEquals(headers.get(TE_HEADER))) {
        logger.warning(String.format("Expected header TE: %s, but %s is received. This means "
                + "some intermediate proxy may not support trailers",
            TE_TRAILERS, headers.get(TE_HEADER)));
        teWarningLogged = true;
      }

      // The Http2Stream object was put by AbstractHttp2ConnectionHandler before calling this
      // method.
      Http2Stream http2Stream = requireHttp2Stream(streamId);

      Metadata metadata = Utils.convertHeaders(headers);
      StatsTraceContext statsTraceCtx =
          StatsTraceContext.newServerContext(streamTracerFactories, method, metadata);

      NettyServerStream.TransportState state = new NettyServerStream.TransportState(
          this,
          ctx.channel().eventLoop(),
          http2Stream,
          maxMessageSize,
          statsTraceCtx,
          transportTracer,
          method);

      try (TaskCloseable ignore = PerfMark.traceTask("NettyServerHandler.onHeadersRead")) {
        PerfMark.attachTag(state.tag());
        String authority = getOrUpdateAuthority((AsciiString) headers.authority());
        NettyServerStream stream = new NettyServerStream(
            ctx.channel(),
            state,
            attributes,
            authority,
            statsTraceCtx);
        transportListener.streamCreated(stream, method, metadata);
        state.onStreamAllocated();
        http2Stream.setProperty(streamKey, state);
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Exception in onHeadersRead()", e);
      // Throw an exception that will get handled by onStreamError.
      throw newStreamException(streamId, e);
    }
  }

  private String getOrUpdateAuthority(AsciiString authority) {
    if (authority == null) {
      return null;
    } else if (!authority.equals(lastKnownAuthority)) {
      lastKnownAuthority = authority;
    }

    // AsciiString.toString() is internally cached, so subsequent calls will not
    // result in recomputing the String representation of lastKnownAuthority.
    return lastKnownAuthority.toString();
  }

  private void onDataRead(int streamId, ByteBuf data, int padding, boolean endOfStream)
      throws Http2Exception {
    flowControlPing().onDataRead(data.readableBytes(), padding);
    try {
      NettyServerStream.TransportState stream = serverStream(requireHttp2Stream(streamId));
      if (stream == null) {
        return;
      }
      try (TaskCloseable ignore = PerfMark.traceTask("NettyServerHandler.onDataRead")) {
        PerfMark.attachTag(stream.tag());
        stream.inboundDataReceived(data, endOfStream);
      }
    } catch (Throwable e) {
      logger.log(Level.WARNING, "Exception in onDataRead()", e);
      // Throw an exception that will get handled by onStreamError.
      throw newStreamException(streamId, e);
    }
  }

  private void onRstStreamRead(int streamId, long errorCode) throws Http2Exception {
    if (maxRstCount > 0) {
      long now = ticker.read();
      if (now - lastRstNanoTime > maxRstPeriodNanos) {
        lastRstNanoTime = now;
        rstCount = 1;
      } else {
        rstCount++;
        if (rstCount > maxRstCount) {
          throw new Http2Exception(Http2Error.ENHANCE_YOUR_CALM, "too_many_rststreams") {
            @SuppressWarnings("UnsynchronizedOverridesSynchronized") // No memory accesses
            @Override
            public Throwable fillInStackTrace() {
              // Avoid the CPU cycles, since the resets may be a CPU consumption attack
              return this;
            }
          };
        }
      }
    }

    try {
      NettyServerStream.TransportState stream = serverStream(connection().stream(streamId));
      if (stream != null) {
        try (TaskCloseable ignore = PerfMark.traceTask("NettyServerHandler.onRstStreamRead")) {
          PerfMark.attachTag(stream.tag());
          stream.transportReportStatus(
              Status.CANCELLED.withDescription("RST_STREAM received for code " + errorCode));
        }
      }
    } catch (Throwable e) {
      logger.log(Level.WARNING, "Exception in onRstStreamRead()", e);
      // Throw an exception that will get handled by onStreamError.
      throw newStreamException(streamId, e);
    }
  }

  @Override
  protected void onConnectionError(ChannelHandlerContext ctx, boolean outbound, Throwable cause,
      Http2Exception http2Ex) {
    logger.log(Level.FINE, "Connection Error", cause);
    connectionError = cause;
    super.onConnectionError(ctx, outbound, cause, http2Ex);
  }

  @Override
  protected void onStreamError(ChannelHandlerContext ctx, boolean outbound, Throwable cause,
      StreamException http2Ex) {
    NettyServerStream.TransportState serverStream = serverStream(
        connection().stream(Http2Exception.streamId(http2Ex)));
    Level level = Level.WARNING;
    if (serverStream == null && http2Ex.error() == Http2Error.STREAM_CLOSED) {
      level = Level.FINE;
    }
    logger.log(level, "Stream Error", cause);
    Tag tag = serverStream != null ? serverStream.tag() : PerfMark.createTag();
    try (TaskCloseable ignore = PerfMark.traceTask("NettyServerHandler.onStreamError")) {
      PerfMark.attachTag(tag);
      if (serverStream != null) {
        serverStream.transportReportStatus(Utils.statusFromThrowable(cause));
      }
      // TODO(ejona): Abort the stream by sending headers to help the client with debugging.
      // Delegate to the base class to send a RST_STREAM.
      super.onStreamError(ctx, outbound, cause, http2Ex);
    }
  }

  @Override
  public void handleProtocolNegotiationCompleted(
      Attributes attrs, InternalChannelz.Security securityInfo) {
    negotiationAttributes = attrs;
    this.securityInfo = securityInfo;
    super.handleProtocolNegotiationCompleted(attrs, securityInfo);
    NettyClientHandler.writeBufferingAndRemove(ctx().channel());
  }

  @Override
  public Attributes getEagAttributes() {
    return eagAttributes;
  }

  InternalChannelz.Security getSecurityInfo() {
    return securityInfo;
  }

  @VisibleForTesting
  KeepAliveManager getKeepAliveManagerForTest() {
    return keepAliveManager;
  }

  @VisibleForTesting
  void setKeepAliveManagerForTest(KeepAliveManager keepAliveManager) {
    this.keepAliveManager = keepAliveManager;
  }

  /**
   * Handler for the Channel shutting down.
   */
  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    try {
      if (keepAliveManager != null) {
        keepAliveManager.onTransportTermination();
      }
      if (maxConnectionIdleManager != null) {
        maxConnectionIdleManager.onTransportTermination();
      }
      if (maxConnectionAgeMonitor != null) {
        maxConnectionAgeMonitor.cancel(false);
      }
      final Status status =
          Status.UNAVAILABLE.withDescription("connection terminated for unknown reason");
      // Any streams that are still active must be closed
      connection().forEachActiveStream(new Http2StreamVisitor() {
        @Override
        public boolean visit(Http2Stream stream) throws Http2Exception {
          NettyServerStream.TransportState serverStream = serverStream(stream);
          if (serverStream != null) {
            serverStream.transportReportStatus(status);
          }
          return true;
        }
      });
    } finally {
      super.channelInactive(ctx);
    }
  }

  WriteQueue getWriteQueue() {
    return serverWriteQueue;
  }

  /** Handler for commands sent from the stream. */
  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    if (msg instanceof SendGrpcFrameCommand) {
      sendGrpcFrame(ctx, (SendGrpcFrameCommand) msg, promise);
    } else if (msg instanceof SendResponseHeadersCommand) {
      sendResponseHeaders(ctx, (SendResponseHeadersCommand) msg, promise);
    } else if (msg instanceof CancelServerStreamCommand) {
      cancelStream(ctx, (CancelServerStreamCommand) msg, promise);
    } else if (msg instanceof GracefulServerCloseCommand) {
      gracefulClose(ctx, (GracefulServerCloseCommand) msg, promise);
    } else if (msg instanceof ForcefulCloseCommand) {
      forcefulClose(ctx, (ForcefulCloseCommand) msg, promise);
    } else {
      AssertionError e =
          new AssertionError("Write called for unexpected type: " + msg.getClass().getName());
      ReferenceCountUtil.release(msg);
      promise.setFailure(e);
      throw e;
    }
  }

  @Override
  public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
    gracefulClose(ctx, new GracefulServerCloseCommand("app_requested"), promise);
    ctx.flush();
  }

  /**
   * Returns the given processed bytes back to inbound flow control.
   */
  void returnProcessedBytes(Http2Stream http2Stream, int bytes) {
    try {
      decoder().flowController().consumeBytes(http2Stream, bytes);
    } catch (Http2Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void closeStreamWhenDone(ChannelPromise promise, Http2Stream stream) {
    promise.addListener(
        new ChannelFutureListener() {
          @Override
          public void operationComplete(ChannelFuture future) {
            serverStream(stream).complete();
          }
        });
  }

  private static void streamGone(int streamId, ChannelPromise promise) {
    promise.setFailure(
        new IllegalStateException(
            "attempting to write to stream " + streamId + " that no longer exists") {
          @Override
          public synchronized Throwable fillInStackTrace() {
            return this;
          }
        });
  }

  /** Sends the given gRPC frame to the client. */
  private void sendGrpcFrame(
      ChannelHandlerContext ctx, SendGrpcFrameCommand cmd, ChannelPromise promise)
      throws Http2Exception {
    try (TaskCloseable ignore = PerfMark.traceTask("NettyServerHandler.sendGrpcFrame")) {
      PerfMark.attachTag(cmd.stream().tag());
      PerfMark.linkIn(cmd.getLink());
      int streamId = cmd.stream().id();
      Http2Stream stream = connection().stream(streamId);
      if (stream == null) {
        cmd.release();
        streamGone(streamId, promise);
        return;
      }
      if (cmd.endStream()) {
        closeStreamWhenDone(promise, stream);
      }
      // Call the base class to write the HTTP/2 DATA frame.
      encoder().writeData(ctx, streamId, cmd.content(), 0, cmd.endStream(), promise);
    }
  }

  /**
   * Sends the response headers to the client.
   */
  private void sendResponseHeaders(ChannelHandlerContext ctx, SendResponseHeadersCommand cmd,
      ChannelPromise promise) throws Http2Exception {
    try (TaskCloseable ignore = PerfMark.traceTask("NettyServerHandler.sendResponseHeaders")) {
      PerfMark.attachTag(cmd.stream().tag());
      PerfMark.linkIn(cmd.getLink());
      int streamId = cmd.stream().id();
      Http2Stream stream = connection().stream(streamId);
      if (stream == null) {
        streamGone(streamId, promise);
        return;
      }
      if (cmd.endOfStream()) {
        closeStreamWhenDone(promise, stream);
      }
      encoder().writeHeaders(ctx, streamId, cmd.headers(), 0, cmd.endOfStream(), promise);
    }
  }

  private void cancelStream(ChannelHandlerContext ctx, CancelServerStreamCommand cmd,
      ChannelPromise promise) {
    try (TaskCloseable ignore = PerfMark.traceTask("NettyServerHandler.cancelStream")) {
      PerfMark.attachTag(cmd.stream().tag());
      PerfMark.linkIn(cmd.getLink());
      // Notify the listener if we haven't already.
      cmd.stream().transportReportStatus(cmd.reason());

      // Now we need to decide how we're going to notify the peer that this stream is closed.
      // If possible, it's nice to inform the peer _why_ this stream was cancelled by sending
      // a structured headers frame.
      if (shouldCloseStreamWithHeaders(cmd, connection())) {
        Metadata md = new Metadata();
        md.put(InternalStatus.CODE_KEY, cmd.reason());
        if (cmd.reason().getDescription() != null) {
          md.put(InternalStatus.MESSAGE_KEY, cmd.reason().getDescription());
        }
        Http2Headers headers = Utils.convertServerHeaders(md);
        encoder().writeHeaders(
            ctx, cmd.stream().id(), headers, /* padding = */ 0, /* endStream = */ true, promise);
      } else {
        // Terminate the stream.
        encoder().writeRstStream(ctx, cmd.stream().id(), Http2Error.CANCEL.code(), promise);
      }
    }
  }

  // Determine whether a CancelServerStreamCommand should try to close the stream with a
  // HEADERS or a RST_STREAM frame. The caller has some influence over this (they can
  // configure cmd.wantsHeaders()). The state of the stream also has an influence: we
  // only try to send HEADERS if the stream exists and hasn't already sent any headers.
  private static boolean shouldCloseStreamWithHeaders(
          CancelServerStreamCommand cmd, Http2Connection conn) {
    if (!cmd.wantsHeaders()) {
      return false;
    }
    Http2Stream stream = conn.stream(cmd.stream().id());
    return stream != null && !stream.isHeadersSent();
  }

  private void gracefulClose(final ChannelHandlerContext ctx, final GracefulServerCloseCommand msg,
      ChannelPromise promise) throws Exception {
    // Ideally we'd adjust a pre-existing graceful shutdown's grace period to at least what is
    // requested here. But that's an edge case and seems bug-prone.
    if (gracefulShutdown == null) {
      Long graceTimeInNanos = null;
      if (msg.getGraceTimeUnit() != null) {
        graceTimeInNanos = msg.getGraceTimeUnit().toNanos(msg.getGraceTime());
      }
      gracefulShutdown = new GracefulShutdown(msg.getGoAwayDebugString(), graceTimeInNanos);
      gracefulShutdown.start(ctx);
    }
    promise.setSuccess();
  }

  private void forcefulClose(final ChannelHandlerContext ctx, final ForcefulCloseCommand msg,
      ChannelPromise promise) throws Exception {
    super.close(ctx, promise);
    connection().forEachActiveStream(new Http2StreamVisitor() {
      @Override
      public boolean visit(Http2Stream stream) throws Http2Exception {
        NettyServerStream.TransportState serverStream = serverStream(stream);
        if (serverStream != null) {
          try (TaskCloseable ignore = PerfMark.traceTask("NettyServerHandler.forcefulClose")) {
            PerfMark.attachTag(serverStream.tag());
            PerfMark.linkIn(msg.getLink());
            serverStream.transportReportStatus(msg.getStatus());
            resetStream(ctx, stream.id(), Http2Error.CANCEL.code(), ctx.newPromise());
          }
        }
        stream.close();
        return true;
      }
    });
  }

  private void respondWithHttpError(
      ChannelHandlerContext ctx, int streamId, int code, Status.Code statusCode, String msg) {
    Metadata metadata = new Metadata();
    metadata.put(InternalStatus.CODE_KEY, statusCode.toStatus());
    metadata.put(InternalStatus.MESSAGE_KEY, msg);
    byte[][] serialized = InternalMetadata.serialize(metadata);

    Http2Headers headers = new DefaultHttp2Headers(true, serialized.length / 2)
        .status("" + code)
        .set(CONTENT_TYPE_HEADER, "text/plain; charset=utf-8");
    for (int i = 0; i < serialized.length; i += 2) {
      headers.add(new AsciiString(serialized[i], false), new AsciiString(serialized[i + 1], false));
    }
    encoder().writeHeaders(ctx, streamId, headers, 0, false, ctx.newPromise());
    ByteBuf msgBuf = ByteBufUtil.writeUtf8(ctx.alloc(), msg);
    encoder().writeData(ctx, streamId, msgBuf, 0, true, ctx.newPromise());
  }

  private Http2Stream requireHttp2Stream(int streamId) {
    Http2Stream stream = connection().stream(streamId);
    if (stream == null) {
      // This should never happen.
      throw new AssertionError("Stream does not exist: " + streamId);
    }
    return stream;
  }

  /**
   * Returns the server stream associated to the given HTTP/2 stream object.
   */
  private NettyServerStream.TransportState serverStream(Http2Stream stream) {
    return stream == null ? null : (NettyServerStream.TransportState) stream.getProperty(streamKey);
  }

  private Http2Exception newStreamException(int streamId, Throwable cause) {
    return Http2Exception.streamError(
        streamId, Http2Error.INTERNAL_ERROR, cause, Strings.nullToEmpty(cause.getMessage()));
  }

  private class FrameListener extends Http2FrameAdapter {
    private boolean firstSettings = true;

    @Override
    public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {
      if (firstSettings) {
        firstSettings = false;
        // Delay transportReady until we see the client's HTTP handshake, for coverage with
        // handshakeTimeout
        attributes = transportListener.transportReady(negotiationAttributes);
      }
    }

    @Override
    public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding,
        boolean endOfStream) throws Http2Exception {
      if (keepAliveManager != null) {
        keepAliveManager.onDataReceived();
      }
      NettyServerHandler.this.onDataRead(streamId, data, padding, endOfStream);
      return padding;
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx,
        int streamId,
        Http2Headers headers,
        int streamDependency,
        short weight,
        boolean exclusive,
        int padding,
        boolean endStream) throws Http2Exception {
      if (keepAliveManager != null) {
        keepAliveManager.onDataReceived();
      }
      NettyServerHandler.this.onHeadersRead(ctx, streamId, headers);
      if (endStream) {
        NettyServerHandler.this.onDataRead(streamId, Unpooled.EMPTY_BUFFER, 0, endStream);
      }
    }

    @Override
    public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode)
        throws Http2Exception {
      if (keepAliveManager != null) {
        keepAliveManager.onDataReceived();
      }
      NettyServerHandler.this.onRstStreamRead(streamId, errorCode);
    }

    @Override
    public void onPingRead(ChannelHandlerContext ctx, long data) throws Http2Exception {
      if (keepAliveManager != null) {
        keepAliveManager.onDataReceived();
      }
      if (!keepAliveEnforcer.pingAcceptable()) {
        ByteBuf debugData = ByteBufUtil.writeAscii(ctx.alloc(), "too_many_pings");
        goAway(ctx, connection().remote().lastStreamCreated(), Http2Error.ENHANCE_YOUR_CALM.code(),
            debugData, ctx.newPromise());
        Status status = Status.RESOURCE_EXHAUSTED.withDescription("Too many pings from client");
        try {
          forcefulClose(ctx, new ForcefulCloseCommand(status), ctx.newPromise());
        } catch (Exception ex) {
          onError(ctx, /* outbound= */ true, ex);
        }
      }
    }

    @Override
    public void onPingAckRead(ChannelHandlerContext ctx, long data) throws Http2Exception {
      if (keepAliveManager != null) {
        keepAliveManager.onDataReceived();
      }
      if (data == flowControlPing().payload()) {
        flowControlPing().updateWindow();
        logger.log(Level.FINE, "Window: {0}",
            decoder().flowController().initialWindowSize(connection().connectionStream()));
      } else if (data == GRACEFUL_SHUTDOWN_PING) {
        if (gracefulShutdown == null) {
          // this should never happen
          logger.warning("Received GRACEFUL_SHUTDOWN_PING Ack but gracefulShutdown is null");
        } else {
          gracefulShutdown.secondGoAwayAndClose(ctx);
        }
      } else if (data != KEEPALIVE_PING) {
        logger.warning("Received unexpected ping ack. No ping outstanding");
      }
    }
  }

  private final class KeepAlivePinger implements KeepAliveManager.KeepAlivePinger {
    final ChannelHandlerContext ctx;

    KeepAlivePinger(ChannelHandlerContext ctx) {
      this.ctx = ctx;
    }

    @Override
    public void ping() {
      ChannelFuture pingFuture = encoder().writePing(
          ctx, false /* isAck */, KEEPALIVE_PING, ctx.newPromise());
      ctx.flush();
      pingFuture.addListener(new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
          if (future.isSuccess()) {
            transportTracer.reportKeepAliveSent();
          }
        }
      });
    }

    @Override
    public void onPingTimeout() {
      try {
        forcefulClose(
            ctx,
            new ForcefulCloseCommand(Status.UNAVAILABLE
                .withDescription("Keepalive failed. The connection is likely gone")),
            ctx.newPromise());
      } catch (Exception ex) {
        try {
          exceptionCaught(ctx, ex);
        } catch (Exception ex2) {
          logger.log(Level.WARNING, "Exception while propagating exception", ex2);
          logger.log(Level.WARNING, "Original failure", ex);
        }
      }
    }
  }

  private final class GracefulShutdown {
    String goAwayMessage;

    /**
     * The grace time between starting graceful shutdown and closing the netty channel,
     * {@code null} is unspecified.
     */
    @CheckForNull
    Long graceTimeInNanos;

    /**
     * True if ping is Acked or ping is timeout.
     */
    boolean pingAckedOrTimeout;

    Future<?> pingFuture;

    GracefulShutdown(String goAwayMessage,
        @Nullable Long graceTimeInNanos) {
      this.goAwayMessage = goAwayMessage;
      this.graceTimeInNanos = graceTimeInNanos;
    }

    /**
     * Sends out first GOAWAY and ping, and schedules second GOAWAY and close.
     */
    void start(final ChannelHandlerContext ctx) {
      goAway(
          ctx,
          Integer.MAX_VALUE,
          Http2Error.NO_ERROR.code(),
          ByteBufUtil.writeAscii(ctx.alloc(), goAwayMessage),
          ctx.newPromise());

      pingFuture = ctx.executor().schedule(
          new Runnable() {
            @Override
            public void run() {
              secondGoAwayAndClose(ctx);
            }
          },
          GRACEFUL_SHUTDOWN_PING_TIMEOUT_NANOS,
          TimeUnit.NANOSECONDS);

      encoder().writePing(ctx, false /* isAck */, GRACEFUL_SHUTDOWN_PING, ctx.newPromise());
    }

    void secondGoAwayAndClose(ChannelHandlerContext ctx) {
      if (pingAckedOrTimeout) {
        return;
      }
      pingAckedOrTimeout = true;

      checkNotNull(pingFuture, "pingFuture");
      pingFuture.cancel(false);

      // send the second GOAWAY with last stream id
      goAway(
          ctx,
          connection().remote().lastStreamCreated(),
          Http2Error.NO_ERROR.code(),
          ByteBufUtil.writeAscii(ctx.alloc(), goAwayMessage),
          ctx.newPromise());

      // gracefully shutdown with specified grace time
      long savedGracefulShutdownTimeMillis = gracefulShutdownTimeoutMillis();
      long overriddenGraceTime = graceTimeOverrideMillis(savedGracefulShutdownTimeMillis);
      try {
        gracefulShutdownTimeoutMillis(overriddenGraceTime);
        NettyServerHandler.super.close(ctx, ctx.newPromise());
      } catch (Exception e) {
        onError(ctx, /* outbound= */ true, e);
      } finally {
        gracefulShutdownTimeoutMillis(savedGracefulShutdownTimeMillis);
      }
    }

    private long graceTimeOverrideMillis(long originalMillis) {
      if (graceTimeInNanos == null) {
        return originalMillis;
      }
      if (graceTimeInNanos == MAX_CONNECTION_AGE_GRACE_NANOS_INFINITE) {
        // netty treats -1 as "no timeout"
        return -1L;
      }
      return TimeUnit.NANOSECONDS.toMillis(graceTimeInNanos);
    }
  }

  // Use a frame writer so that we know when frames are through flow control and actually being
  // written.
  private static class WriteMonitoringFrameWriter extends DecoratingHttp2FrameWriter {
    private final KeepAliveEnforcer keepAliveEnforcer;

    public WriteMonitoringFrameWriter(Http2FrameWriter delegate,
        KeepAliveEnforcer keepAliveEnforcer) {
      super(delegate);
      this.keepAliveEnforcer = keepAliveEnforcer;
    }

    @Override
    public ChannelFuture writeData(ChannelHandlerContext ctx, int streamId, ByteBuf data,
        int padding, boolean endStream, ChannelPromise promise) {
      keepAliveEnforcer.resetCounters();
      return super.writeData(ctx, streamId, data, padding, endStream, promise);
    }

    @Override
    public ChannelFuture writeHeaders(ChannelHandlerContext ctx, int streamId, Http2Headers headers,
        int padding, boolean endStream, ChannelPromise promise) {
      keepAliveEnforcer.resetCounters();
      return super.writeHeaders(ctx, streamId, headers, padding, endStream, promise);
    }

    @Override
    public ChannelFuture writeHeaders(ChannelHandlerContext ctx, int streamId, Http2Headers headers,
        int streamDependency, short weight, boolean exclusive, int padding, boolean endStream,
        ChannelPromise promise) {
      keepAliveEnforcer.resetCounters();
      return super.writeHeaders(ctx, streamId, headers, streamDependency, weight, exclusive,
          padding, endStream, promise);
    }
  }

  private static class ServerChannelLogger extends ChannelLogger {
    private static final Logger log = Logger.getLogger(ChannelLogger.class.getName());

    @Override
    public void log(ChannelLogLevel level, String message) {
      log.log(toJavaLogLevel(level), message);
    }

    @Override
    public void log(ChannelLogLevel level, String messageFormat, Object... args) {
      log(level, MessageFormat.format(messageFormat, args));
    }
  }

  private static Level toJavaLogLevel(ChannelLogLevel level) {
    switch (level) {
      case ERROR:
        return Level.FINE;
      case WARNING:
        return Level.FINER;
      default:
        return Level.FINEST;
    }
  }
}
