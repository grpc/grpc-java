/*
 * Copyright 2022 The gRPC Authors
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

import static io.grpc.okhttp.OkHttpServerBuilder.MAX_CONNECTION_AGE_NANOS_DISABLED;
import static io.grpc.okhttp.OkHttpServerBuilder.MAX_CONNECTION_IDLE_NANOS_DISABLED;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.Attributes;
import io.grpc.InternalChannelz;
import io.grpc.InternalLogId;
import io.grpc.InternalStatus;
import io.grpc.Metadata;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.KeepAliveEnforcer;
import io.grpc.internal.KeepAliveManager;
import io.grpc.internal.LogExceptionRunnable;
import io.grpc.internal.MaxConnectionIdleManager;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SerializingExecutor;
import io.grpc.internal.ServerTransport;
import io.grpc.internal.ServerTransportListener;
import io.grpc.internal.StatsTraceContext;
import io.grpc.internal.TransportTracer;
import io.grpc.okhttp.internal.framed.ErrorCode;
import io.grpc.okhttp.internal.framed.FrameReader;
import io.grpc.okhttp.internal.framed.FrameWriter;
import io.grpc.okhttp.internal.framed.Header;
import io.grpc.okhttp.internal.framed.HeadersMode;
import io.grpc.okhttp.internal.framed.Http2;
import io.grpc.okhttp.internal.framed.Settings;
import io.grpc.okhttp.internal.framed.Variant;
import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;

/**
 * OkHttp-based server transport.
 */
final class OkHttpServerTransport implements ServerTransport,
      ExceptionHandlingFrameWriter.TransportExceptionHandler, OutboundFlowController.Transport {
  private static final Logger log = Logger.getLogger(OkHttpServerTransport.class.getName());
  private static final int GRACEFUL_SHUTDOWN_PING = 0x1111;

  private static final long GRACEFUL_SHUTDOWN_PING_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(1);

  private static final int KEEPALIVE_PING = 0xDEAD;
  private static final ByteString HTTP_METHOD = ByteString.encodeUtf8(":method");
  private static final ByteString CONNECT_METHOD = ByteString.encodeUtf8("CONNECT");
  private static final ByteString POST_METHOD = ByteString.encodeUtf8("POST");
  private static final ByteString SCHEME = ByteString.encodeUtf8(":scheme");
  private static final ByteString PATH = ByteString.encodeUtf8(":path");
  private static final ByteString AUTHORITY = ByteString.encodeUtf8(":authority");
  private static final ByteString CONNECTION = ByteString.encodeUtf8("connection");
  private static final ByteString HOST = ByteString.encodeUtf8("host");
  private static final ByteString TE = ByteString.encodeUtf8("te");
  private static final ByteString TE_TRAILERS = ByteString.encodeUtf8("trailers");
  private static final ByteString CONTENT_TYPE = ByteString.encodeUtf8("content-type");
  private static final ByteString CONTENT_LENGTH = ByteString.encodeUtf8("content-length");

  private final Config config;
  private final Variant variant = new Http2();
  private final TransportTracer tracer;
  private final InternalLogId logId;
  private Socket socket;
  private ServerTransportListener listener;
  private Executor transportExecutor;
  private ScheduledExecutorService scheduledExecutorService;
  private Attributes attributes;
  private KeepAliveManager keepAliveManager;
  private MaxConnectionIdleManager maxConnectionIdleManager;
  private ScheduledFuture<?> maxConnectionAgeMonitor;
  private final KeepAliveEnforcer keepAliveEnforcer;

  private final Object lock = new Object();
  @GuardedBy("lock")
  private boolean abruptShutdown;
  @GuardedBy("lock")
  private boolean gracefulShutdown;
  @GuardedBy("lock")
  private boolean handshakeShutdown;
  @GuardedBy("lock")
  private InternalChannelz.Security securityInfo;
  @GuardedBy("lock")
  private ExceptionHandlingFrameWriter frameWriter;
  @GuardedBy("lock")
  private OutboundFlowController outboundFlow;
  @GuardedBy("lock")
  private final Map<Integer, StreamState> streams = new TreeMap<>();
  @GuardedBy("lock")
  private int lastStreamId;
  @GuardedBy("lock")
  private int goAwayStreamId = Integer.MAX_VALUE;
  /**
   * Indicates the transport is in go-away state: no new streams will be processed, but existing
   * streams may continue.
   */
  @GuardedBy("lock")
  private Status goAwayStatus;
  /** Non-{@code null} when gracefully shutting down and have not yet sent second GOAWAY. */
  @GuardedBy("lock")
  private ScheduledFuture<?> secondGoawayTimer;
  /** Non-{@code null} when waiting for forceful close GOAWAY to be sent. */
  @GuardedBy("lock")
  private ScheduledFuture<?> forcefulCloseTimer;
  @GuardedBy("lock")
  private Long gracefulShutdownPeriod = null;

  public OkHttpServerTransport(Config config, Socket bareSocket) {
    this.config = Preconditions.checkNotNull(config, "config");
    this.socket = Preconditions.checkNotNull(bareSocket, "bareSocket");

    tracer = config.transportTracerFactory.create();
    tracer.setFlowControlWindowReader(this::readFlowControlWindow);
    logId = InternalLogId.allocate(getClass(), socket.getRemoteSocketAddress().toString());
    transportExecutor = config.transportExecutorPool.getObject();
    scheduledExecutorService = config.scheduledExecutorServicePool.getObject();
    keepAliveEnforcer = new KeepAliveEnforcer(config.permitKeepAliveWithoutCalls,
        config.permitKeepAliveTimeInNanos, TimeUnit.NANOSECONDS);
  }

  public void start(ServerTransportListener listener) {
    this.listener = Preconditions.checkNotNull(listener, "listener");

    SerializingExecutor serializingExecutor = new SerializingExecutor(transportExecutor);
    serializingExecutor.execute(() -> startIo(serializingExecutor));
  }

  private void startIo(SerializingExecutor serializingExecutor) {
    try {
      // The socket implementation is lazily initialized, but had broken thread-safety 
      // for that laziness https://bugs.openjdk.org/browse/JDK-8278326. 
      // As a workaround, we lock to synchronize initialization with shutdown().
      synchronized (lock) {
        socket.setTcpNoDelay(true);
      }
      HandshakerSocketFactory.HandshakeResult result =
          config.handshakerSocketFactory.handshake(socket, Attributes.EMPTY);
      synchronized (lock) {
        this.socket = result.socket;
      }
      this.attributes = result.attributes;

      int maxQueuedControlFrames = 10000;
      AsyncSink asyncSink = AsyncSink.sink(serializingExecutor, this, maxQueuedControlFrames);
      asyncSink.becomeConnected(Okio.sink(socket), socket);
      FrameWriter rawFrameWriter = asyncSink.limitControlFramesWriter(
          variant.newWriter(Okio.buffer(asyncSink), false));
      FrameWriter writeMonitoringFrameWriter = new ForwardingFrameWriter(rawFrameWriter) {
        @Override
        public void synReply(boolean outFinished, int streamId, List<Header> headerBlock)
            throws IOException {
          keepAliveEnforcer.resetCounters();
          super.synReply(outFinished, streamId, headerBlock);
        }

        @Override
        public void headers(int streamId, List<Header> headerBlock) throws IOException {
          keepAliveEnforcer.resetCounters();
          super.headers(streamId, headerBlock);
        }

        @Override
        public void data(boolean outFinished, int streamId, Buffer source, int byteCount)
            throws IOException {
          keepAliveEnforcer.resetCounters();
          super.data(outFinished, streamId, source, byteCount);
        }
      };
      synchronized (lock) {
        this.securityInfo = result.securityInfo;

        // Handle FrameWriter exceptions centrally, since there are many callers. Note that
        // errors coming from rawFrameWriter are generally broken invariants/bugs, as AsyncSink
        // does not propagate syscall errors through the FrameWriter. But we handle the
        // AsyncSink failures with the same TransportExceptionHandler instance so it is all
        // mixed back together.
        frameWriter = new ExceptionHandlingFrameWriter(this, writeMonitoringFrameWriter);
        outboundFlow = new OutboundFlowController(this, frameWriter);

        // These writes will be queued in the serializingExecutor waiting for this function to
        // return.
        frameWriter.connectionPreface();
        Settings settings = new Settings();
        OkHttpSettingsUtil.set(settings,
            OkHttpSettingsUtil.INITIAL_WINDOW_SIZE, config.flowControlWindow);
        OkHttpSettingsUtil.set(settings,
            OkHttpSettingsUtil.MAX_HEADER_LIST_SIZE, config.maxInboundMetadataSize);
        frameWriter.settings(settings);
        if (config.flowControlWindow > Utils.DEFAULT_WINDOW_SIZE) {
          frameWriter.windowUpdate(
              Utils.CONNECTION_STREAM_ID, config.flowControlWindow - Utils.DEFAULT_WINDOW_SIZE);
        }
        frameWriter.flush();
      }

      if (config.keepAliveTimeNanos != GrpcUtil.KEEPALIVE_TIME_NANOS_DISABLED) {
        keepAliveManager = new KeepAliveManager(
            new KeepAlivePinger(), scheduledExecutorService, config.keepAliveTimeNanos,
            config.keepAliveTimeoutNanos, true);
        keepAliveManager.onTransportStarted();
      }

      if (config.maxConnectionIdleNanos != MAX_CONNECTION_IDLE_NANOS_DISABLED) {
        maxConnectionIdleManager = new MaxConnectionIdleManager(config.maxConnectionIdleNanos);
        maxConnectionIdleManager.start(this::shutdown, scheduledExecutorService);
      }

      if (config.maxConnectionAgeInNanos != MAX_CONNECTION_AGE_NANOS_DISABLED) {
        long maxConnectionAgeInNanos =
            (long) ((.9D + Math.random() * .2D) * config.maxConnectionAgeInNanos);
        maxConnectionAgeMonitor = scheduledExecutorService.schedule(
            new LogExceptionRunnable(() -> shutdown(config.maxConnectionAgeGraceInNanos)),
            maxConnectionAgeInNanos,
            TimeUnit.NANOSECONDS);
      }

      transportExecutor.execute(
          new FrameHandler(variant.newReader(Okio.buffer(Okio.source(socket)), false)));
    } catch (Error | IOException | RuntimeException ex) {
      synchronized (lock) {
        if (!handshakeShutdown) {
          log.log(Level.INFO, "Socket failed to handshake", ex);
        }
      }
      GrpcUtil.closeQuietly(socket);
      terminated();
    }
  }

  @Override
  public void shutdown() {
    shutdown(null);
  }

  private void shutdown(@Nullable Long gracefulShutdownPeriod) {
    synchronized (lock) {
      if (gracefulShutdown || abruptShutdown) {
        return;
      }
      gracefulShutdown = true;
      this.gracefulShutdownPeriod = gracefulShutdownPeriod;
      if (frameWriter == null) {
        handshakeShutdown = true;
        GrpcUtil.closeQuietly(socket);
      } else {
        // RFC7540 §6.8. Begin double-GOAWAY graceful shutdown. To wait one RTT we use a PING, but
        // we also set a timer to limit the upper bound in case the PING is excessively stalled or
        // the client is malicious.
        secondGoawayTimer = scheduledExecutorService.schedule(
            this::triggerGracefulSecondGoaway,
            GRACEFUL_SHUTDOWN_PING_TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
        frameWriter.goAway(Integer.MAX_VALUE, ErrorCode.NO_ERROR, new byte[0]);
        frameWriter.ping(false, 0, GRACEFUL_SHUTDOWN_PING);
        frameWriter.flush();
      }
    }
  }

  private void triggerGracefulSecondGoaway() {
    synchronized (lock) {
      if (secondGoawayTimer == null) {
        return;
      }
      secondGoawayTimer.cancel(false);
      secondGoawayTimer = null;
      frameWriter.goAway(lastStreamId, ErrorCode.NO_ERROR, new byte[0]);
      goAwayStreamId = lastStreamId;
      if (streams.isEmpty()) {
        frameWriter.close();
      } else {
        frameWriter.flush();
      }
      if (gracefulShutdownPeriod != null) {
        forcefulCloseTimer = scheduledExecutorService.schedule(
            this::triggerForcefulClose, gracefulShutdownPeriod, TimeUnit.NANOSECONDS);
      }
    }
  }

  @Override
  public void shutdownNow(Status reason) {
    synchronized (lock) {
      if (frameWriter == null) {
        handshakeShutdown = true;
        GrpcUtil.closeQuietly(socket);
        return;
      }
    }
    abruptShutdown(ErrorCode.NO_ERROR, "", reason, true);
  }

  /**
   * Finish all active streams due to an IOException, then close the transport.
   */
  @Override
  public void onException(Throwable failureCause) {
    Preconditions.checkNotNull(failureCause, "failureCause");
    Status status = Status.UNAVAILABLE.withCause(failureCause);
    abruptShutdown(ErrorCode.INTERNAL_ERROR, "I/O failure", status, false);
  }

  private void abruptShutdown(
      ErrorCode errorCode, String moreDetail, Status reason, boolean rstStreams) {
    synchronized (lock) {
      if (abruptShutdown) {
        return;
      }
      abruptShutdown = true;
      goAwayStatus = reason;

      if (secondGoawayTimer != null) {
        secondGoawayTimer.cancel(false);
        secondGoawayTimer = null;
      }
      for (Map.Entry<Integer, StreamState> entry : streams.entrySet()) {
        if (rstStreams) {
          frameWriter.rstStream(entry.getKey(), ErrorCode.CANCEL);
        }
        entry.getValue().transportReportStatus(reason);
      }
      streams.clear();

      // RFC7540 §5.4.1. Attempt to inform the client what went wrong. We try to write the GOAWAY
      // _and then_ close our side of the connection. But place an upper-bound for how long we wait
      // for I/O with a timer, which forcefully closes the socket.
      frameWriter.goAway(lastStreamId, errorCode, moreDetail.getBytes(GrpcUtil.US_ASCII));
      goAwayStreamId = lastStreamId;
      frameWriter.close();
      forcefulCloseTimer = scheduledExecutorService.schedule(
          this::triggerForcefulClose, 1, TimeUnit.SECONDS);
    }
  }

  private void triggerForcefulClose() {
    // Safe to do unconditionally; no need to check if timer cancellation raced
    GrpcUtil.closeQuietly(socket);
  }

  private void terminated() {
    synchronized (lock) {
      if (forcefulCloseTimer != null) {
        forcefulCloseTimer.cancel(false);
        forcefulCloseTimer = null;
      }
    }
    if (keepAliveManager != null) {
      keepAliveManager.onTransportTermination();
    }
    if (maxConnectionIdleManager != null) {
      maxConnectionIdleManager.onTransportTermination();
    }

    if (maxConnectionAgeMonitor != null) {
      maxConnectionAgeMonitor.cancel(false);
    }
    transportExecutor = config.transportExecutorPool.returnObject(transportExecutor);
    scheduledExecutorService =
        config.scheduledExecutorServicePool.returnObject(scheduledExecutorService);
    listener.transportTerminated();
  }

  @Override
  public ScheduledExecutorService getScheduledExecutorService() {
    return scheduledExecutorService;
  }

  @Override
  public ListenableFuture<InternalChannelz.SocketStats> getStats() {
    synchronized (lock) {
      return Futures.immediateFuture(new InternalChannelz.SocketStats(
          tracer.getStats(),
          socket.getLocalSocketAddress(),
          socket.getRemoteSocketAddress(),
          Utils.getSocketOptions(socket),
          securityInfo));
    }
  }

  private TransportTracer.FlowControlWindows readFlowControlWindow() {
    synchronized (lock) {
      long local = outboundFlow == null ? -1 : outboundFlow.windowUpdate(null, 0);
      // connectionUnacknowledgedBytesRead is only readable by FrameHandler, so we provide a lower
      // bound.
      long remote = (long) (config.flowControlWindow * Utils.DEFAULT_WINDOW_UPDATE_RATIO);
      return new TransportTracer.FlowControlWindows(local, remote);
    }
  }

  @Override
  public InternalLogId getLogId() {
    return logId;
  }

  @Override
  public OutboundFlowController.StreamState[] getActiveStreams() {
    synchronized (lock) {
      OutboundFlowController.StreamState[] flowStreams =
          new OutboundFlowController.StreamState[streams.size()];
      int i = 0;
      for (StreamState stream : streams.values()) {
        flowStreams[i++] = stream.getOutboundFlowState();
      }
      return flowStreams;
    }
  }

  /**
   * Notify the transport that the stream was closed. Any frames for the stream must be enqueued
   * before calling.
   */
  void streamClosed(int streamId, boolean flush) {
    synchronized (lock) {
      streams.remove(streamId);
      if (streams.isEmpty()) {
        keepAliveEnforcer.onTransportIdle();
        if (maxConnectionIdleManager != null) {
          maxConnectionIdleManager.onTransportIdle();
        }
      }
      if (gracefulShutdown && streams.isEmpty()) {
        frameWriter.close();
      } else {
        if (flush) {
          frameWriter.flush();
        }
      }
    }
  }

  private static String asciiString(ByteString value) {
    // utf8() string is cached in ByteString, so we prefer it when the contents are ASCII. This
    // provides benefit if the header was reused via HPACK.
    for (int i = 0; i < value.size(); i++) {
      if (value.getByte(i) < 0) {
        return value.string(GrpcUtil.US_ASCII);
      }
    }
    return value.utf8();
  }

  private static int headerFind(List<Header> header, ByteString key, int startIndex) {
    for (int i = startIndex; i < header.size(); i++) {
      if (header.get(i).name.equals(key)) {
        return i;
      }
    }
    return -1;
  }

  private static boolean headerContains(List<Header> header, ByteString key) {
    return headerFind(header, key, 0) != -1;
  }

  private static void headerRemove(List<Header> header, ByteString key) {
    int i = 0;
    while ((i = headerFind(header, key, i)) != -1) {
      header.remove(i);
    }
  }

  /** Assumes that caller requires this field, so duplicates are treated as missing. */
  private static ByteString headerGetRequiredSingle(List<Header> header, ByteString key) {
    int i = headerFind(header, key, 0);
    if (i == -1) {
      return null;
    }
    if (headerFind(header, key, i + 1) != -1) {
      return null;
    }
    return header.get(i).value;
  }

  static final class Config {
    final List<? extends ServerStreamTracer.Factory> streamTracerFactories;
    final ObjectPool<Executor> transportExecutorPool;
    final ObjectPool<ScheduledExecutorService> scheduledExecutorServicePool;
    final TransportTracer.Factory transportTracerFactory;
    final HandshakerSocketFactory handshakerSocketFactory;
    final long keepAliveTimeNanos;
    final long keepAliveTimeoutNanos;
    final int flowControlWindow;
    final int maxInboundMessageSize;
    final int maxInboundMetadataSize;
    final long maxConnectionIdleNanos;
    final boolean permitKeepAliveWithoutCalls;
    final long permitKeepAliveTimeInNanos;
    final long maxConnectionAgeInNanos;
    final long maxConnectionAgeGraceInNanos;

    public Config(
        OkHttpServerBuilder builder,
        List<? extends ServerStreamTracer.Factory> streamTracerFactories) {
      this.streamTracerFactories = Preconditions.checkNotNull(
          streamTracerFactories, "streamTracerFactories");
      transportExecutorPool = Preconditions.checkNotNull(
          builder.transportExecutorPool, "transportExecutorPool");
      scheduledExecutorServicePool = Preconditions.checkNotNull(
          builder.scheduledExecutorServicePool, "scheduledExecutorServicePool");
      transportTracerFactory = Preconditions.checkNotNull(
          builder.transportTracerFactory, "transportTracerFactory");
      handshakerSocketFactory = Preconditions.checkNotNull(
          builder.handshakerSocketFactory, "handshakerSocketFactory");
      keepAliveTimeNanos = builder.keepAliveTimeNanos;
      keepAliveTimeoutNanos = builder.keepAliveTimeoutNanos;
      flowControlWindow = builder.flowControlWindow;
      maxInboundMessageSize = builder.maxInboundMessageSize;
      maxInboundMetadataSize = builder.maxInboundMetadataSize;
      maxConnectionIdleNanos = builder.maxConnectionIdleInNanos;
      permitKeepAliveWithoutCalls = builder.permitKeepAliveWithoutCalls;
      permitKeepAliveTimeInNanos = builder.permitKeepAliveTimeInNanos;
      maxConnectionAgeInNanos = builder.maxConnectionAgeInNanos;
      maxConnectionAgeGraceInNanos = builder.maxConnectionAgeGraceInNanos;
    }
  }

  /**
   * Runnable which reads frames and dispatches them to in flight calls.
   */
  class FrameHandler implements FrameReader.Handler, Runnable {
    private final OkHttpFrameLogger frameLogger =
        new OkHttpFrameLogger(Level.FINE, OkHttpServerTransport.class);
    private final FrameReader frameReader;
    private boolean receivedSettings;
    private int connectionUnacknowledgedBytesRead;

    public FrameHandler(FrameReader frameReader) {
      this.frameReader = frameReader;
    }

    @Override
    public void run() {
      String threadName = Thread.currentThread().getName();
      Thread.currentThread().setName("OkHttpServerTransport");
      try {
        frameReader.readConnectionPreface();
        if (!frameReader.nextFrame(this)) {
          connectionError(ErrorCode.INTERNAL_ERROR, "Failed to read initial SETTINGS");
          return;
        }
        if (!receivedSettings) {
          connectionError(ErrorCode.PROTOCOL_ERROR,
              "First HTTP/2 frame must be SETTINGS. RFC7540 section 3.5");
          return;
        }
        // Read until the underlying socket closes.
        while (frameReader.nextFrame(this)) {
          if (keepAliveManager != null) {
            keepAliveManager.onDataReceived();
          }
        }
        // frameReader.nextFrame() returns false when the underlying read encounters an IOException,
        // it may be triggered by the socket closing, in such case, the startGoAway() will do
        // nothing, otherwise, we finish all streams since it's a real IO issue.
        Status status;
        synchronized (lock) {
          status = goAwayStatus;
        }
        if (status == null) {
          status = Status.UNAVAILABLE.withDescription("TCP connection closed or IOException");
        }
        abruptShutdown(ErrorCode.INTERNAL_ERROR, "I/O failure", status, false);
      } catch (Throwable t) {
        log.log(Level.WARNING, "Error decoding HTTP/2 frames", t);
        abruptShutdown(ErrorCode.INTERNAL_ERROR, "Error in frame decoder",
            Status.INTERNAL.withDescription("Error decoding HTTP/2 frames").withCause(t), false);
      } finally {
        // Wait for the abrupt shutdown to be processed by AsyncSink and close the socket
        try {
          GrpcUtil.exhaust(socket.getInputStream());
        } catch (IOException ex) {
          // Unable to wait, so just proceed to tear-down. The socket is probably already closed so
          // the GOAWAY can't be sent anyway.
        }
        GrpcUtil.closeQuietly(socket);
        terminated();
        Thread.currentThread().setName(threadName);
      }
    }

    /**
     * Handle HTTP2 HEADER and CONTINUATION frames.
     */
    @Override
    public void headers(boolean outFinished,
        boolean inFinished,
        int streamId,
        int associatedStreamId,
        List<Header> headerBlock,
        HeadersMode headersMode) {
      frameLogger.logHeaders(
          OkHttpFrameLogger.Direction.INBOUND, streamId, headerBlock, inFinished);
      // streamId == 0 checking is in HTTP/2 decoder
      if ((streamId & 1) == 0) {
        // The server doesn't use PUSH_PROMISE, so all even streams are IDLE
        connectionError(ErrorCode.PROTOCOL_ERROR,
            "Clients cannot open even numbered streams. RFC7540 section 5.1.1");
        return;
      }
      boolean newStream;
      synchronized (lock) {
        if (streamId > goAwayStreamId) {
          return;
        }
        newStream = streamId > lastStreamId;
        if (newStream) {
          lastStreamId = streamId;
        }
      }

      int metadataSize = headerBlockSize(headerBlock);
      if (metadataSize > config.maxInboundMetadataSize) {
        respondWithHttpError(streamId, inFinished, 431, Status.Code.RESOURCE_EXHAUSTED,
            String.format(
                Locale.US,
                "Request metadata larger than %d: %d",
                config.maxInboundMetadataSize,
                metadataSize));
        return;
      }

      headerRemove(headerBlock, ByteString.EMPTY);

      ByteString httpMethod = null;
      ByteString scheme = null;
      ByteString path = null;
      ByteString authority = null;
      while (headerBlock.size() > 0 && headerBlock.get(0).name.getByte(0) == ':') {
        Header header = headerBlock.remove(0);
        if (HTTP_METHOD.equals(header.name) && httpMethod == null) {
          httpMethod = header.value;
        } else if (SCHEME.equals(header.name) && scheme == null) {
          scheme = header.value;
        } else if (PATH.equals(header.name) && path == null) {
          path = header.value;
        } else if (AUTHORITY.equals(header.name) && authority == null) {
          authority = header.value;
        } else {
          streamError(streamId, ErrorCode.PROTOCOL_ERROR,
              "Unexpected pseudo header. RFC7540 section 8.1.2.1");
          return;
        }
      }
      for (int i = 0; i < headerBlock.size(); i++) {
        if (headerBlock.get(i).name.getByte(0) == ':') {
          streamError(streamId, ErrorCode.PROTOCOL_ERROR,
              "Pseudo header not before regular headers. RFC7540 section 8.1.2.1");
          return;
        }
      }
      if (!CONNECT_METHOD.equals(httpMethod)
          && newStream
          && (httpMethod == null || scheme == null || path == null)) {
        streamError(streamId, ErrorCode.PROTOCOL_ERROR,
            "Missing required pseudo header. RFC7540 section 8.1.2.3");
        return;
      }
      if (headerContains(headerBlock, CONNECTION)) {
        streamError(streamId, ErrorCode.PROTOCOL_ERROR,
            "Connection-specific headers not permitted. RFC7540 section 8.1.2.2");
        return;
      }

      if (!newStream) {
        if (inFinished) {
          synchronized (lock) {
            StreamState stream = streams.get(streamId);
            if (stream == null) {
              streamError(streamId, ErrorCode.STREAM_CLOSED, "Received headers for closed stream");
              return;
            }
            if (stream.hasReceivedEndOfStream()) {
              streamError(streamId, ErrorCode.STREAM_CLOSED,
                  "Received HEADERS for half-closed (remote) stream. RFC7540 section 5.1");
              return;
            }
            // Ignore the trailers, but still half-close the stream
            stream.inboundDataReceived(new Buffer(), 0, true);
            return;
          }
        } else {
          streamError(streamId, ErrorCode.PROTOCOL_ERROR,
              "Headers disallowed in the middle of the stream. RFC7540 section 8.1");
          return;
        }
      }

      if (authority == null) {
        int i = headerFind(headerBlock, HOST, 0);
        if (i != -1) {
          if (headerFind(headerBlock, HOST, i + 1) != -1) {
            respondWithHttpError(streamId, inFinished, 400, Status.Code.INTERNAL,
                "Multiple host headers disallowed. RFC7230 section 5.4");
            return;
          }
          authority = headerBlock.get(i).value;
        }
      }
      headerRemove(headerBlock, HOST);

      // Remove the leading slash of the path and get the fully qualified method name
      if (path.size() == 0 || path.getByte(0) != '/') {
        respondWithHttpError(streamId, inFinished, 404, Status.Code.UNIMPLEMENTED,
            "Expected path to start with /: " + asciiString(path));
        return;
      }
      String method = asciiString(path).substring(1);

      ByteString contentType = headerGetRequiredSingle(headerBlock, CONTENT_TYPE);
      if (contentType == null) {
        respondWithHttpError(streamId, inFinished, 415, Status.Code.INTERNAL,
            "Content-Type is missing or duplicated");
        return;
      }
      String contentTypeString = asciiString(contentType);
      if (!GrpcUtil.isGrpcContentType(contentTypeString)) {
        respondWithHttpError(streamId, inFinished, 415, Status.Code.INTERNAL,
            "Content-Type is not supported: " + contentTypeString);
        return;
      }

      if (!POST_METHOD.equals(httpMethod)) {
        respondWithHttpError(streamId, inFinished, 405, Status.Code.INTERNAL,
            "HTTP Method is not supported: " + asciiString(httpMethod));
        return;
      }

      ByteString te = headerGetRequiredSingle(headerBlock, TE);
      if (!TE_TRAILERS.equals(te)) {
        respondWithGrpcError(streamId, inFinished, Status.Code.INTERNAL,
            String.format("Expected header TE: %s, but %s is received. "
              + "Some intermediate proxy may not support trailers",
              asciiString(TE_TRAILERS), te == null ? "<missing>" : asciiString(te)));
        return;
      }
      headerRemove(headerBlock, CONTENT_LENGTH);

      Metadata metadata = Utils.convertHeaders(headerBlock);
      StatsTraceContext statsTraceCtx =
          StatsTraceContext.newServerContext(config.streamTracerFactories, method, metadata);
      synchronized (lock) {
        OkHttpServerStream.TransportState stream = new OkHttpServerStream.TransportState(
            OkHttpServerTransport.this,
            streamId,
            config.maxInboundMessageSize,
            statsTraceCtx,
            lock,
            frameWriter,
            outboundFlow,
            config.flowControlWindow,
            tracer,
            method);
        OkHttpServerStream streamForApp = new OkHttpServerStream(
            stream,
            attributes,
            authority == null ? null : asciiString(authority),
            statsTraceCtx,
            tracer);
        if (streams.isEmpty()) {
          keepAliveEnforcer.onTransportActive();
          if (maxConnectionIdleManager != null) {
            maxConnectionIdleManager.onTransportActive();
          }
        }
        streams.put(streamId, stream);
        listener.streamCreated(streamForApp, method, metadata);
        stream.onStreamAllocated();
        if (inFinished) {
          stream.inboundDataReceived(new Buffer(), 0, inFinished);
        }
      }
    }

    private int headerBlockSize(List<Header> headerBlock) {
      // Calculate as defined for SETTINGS_MAX_HEADER_LIST_SIZE in RFC 7540 §6.5.2.
      long size = 0;
      for (int i = 0; i < headerBlock.size(); i++) {
        Header header = headerBlock.get(i);
        size += 32 + header.name.size() + header.value.size();
      }
      size = Math.min(size, Integer.MAX_VALUE);
      return (int) size;
    }

    /**
     * Handle an HTTP2 DATA frame.
     */
    @Override
    public void data(boolean inFinished, int streamId, BufferedSource in, int length)
        throws IOException {
      frameLogger.logData(
          OkHttpFrameLogger.Direction.INBOUND, streamId, in.getBuffer(), length, inFinished);
      if (streamId == 0) {
        connectionError(ErrorCode.PROTOCOL_ERROR,
            "Stream 0 is reserved for control messages. RFC7540 section 5.1.1");
        return;
      }
      if ((streamId & 1) == 0) {
        // The server doesn't use PUSH_PROMISE, so all even streams are IDLE
        connectionError(ErrorCode.PROTOCOL_ERROR,
            "Clients cannot open even numbered streams. RFC7540 section 5.1.1");
        return;
      }

      // Wait until the frame is complete. We only support 16 KiB frames, and the max permitted in
      // HTTP/2 is 16 MiB. This is verified in OkHttp's Http2 deframer, so we don't need to be
      // concerned with the window being exceeded at this point.
      in.require(length);

      synchronized (lock) {
        StreamState stream = streams.get(streamId);
        if (stream == null) {
          in.skip(length);
          streamError(streamId, ErrorCode.STREAM_CLOSED, "Received data for closed stream");
          return;
        }
        if (stream.hasReceivedEndOfStream()) {
          in.skip(length);
          streamError(streamId, ErrorCode.STREAM_CLOSED,
              "Received DATA for half-closed (remote) stream. RFC7540 section 5.1");
          return;
        }
        if (stream.inboundWindowAvailable() < length) {
          in.skip(length);
          streamError(streamId, ErrorCode.FLOW_CONTROL_ERROR,
              "Received DATA size exceeded window size. RFC7540 section 6.9");
          return;
        }
        Buffer buf = new Buffer();
        buf.write(in.getBuffer(), length);
        stream.inboundDataReceived(buf, length, inFinished);
      }

      // connection window update
      connectionUnacknowledgedBytesRead += length;
      if (connectionUnacknowledgedBytesRead
          >= config.flowControlWindow * Utils.DEFAULT_WINDOW_UPDATE_RATIO) {
        synchronized (lock) {
          frameWriter.windowUpdate(0, connectionUnacknowledgedBytesRead);
          frameWriter.flush();
        }
        connectionUnacknowledgedBytesRead = 0;
      }
    }

    @Override
    public void rstStream(int streamId, ErrorCode errorCode) {
      frameLogger.logRstStream(OkHttpFrameLogger.Direction.INBOUND, streamId, errorCode);
      // streamId == 0 checking is in HTTP/2 decoder

      if (!(ErrorCode.NO_ERROR.equals(errorCode)
            || ErrorCode.CANCEL.equals(errorCode)
            || ErrorCode.STREAM_CLOSED.equals(errorCode))) {
        log.log(Level.INFO, "Received RST_STREAM: " + errorCode);
      }
      Status status = GrpcUtil.Http2Error.statusForCode(errorCode.httpCode)
          .withDescription("RST_STREAM");
      synchronized (lock) {
        StreamState stream = streams.get(streamId);
        if (stream != null) {
          stream.inboundRstReceived(status);
          streamClosed(streamId, /*flush=*/ false);
        }
      }
    }

    @Override
    public void settings(boolean clearPrevious, Settings settings) {
      frameLogger.logSettings(OkHttpFrameLogger.Direction.INBOUND, settings);
      synchronized (lock) {
        boolean outboundWindowSizeIncreased = false;
        if (OkHttpSettingsUtil.isSet(settings, OkHttpSettingsUtil.INITIAL_WINDOW_SIZE)) {
          int initialWindowSize = OkHttpSettingsUtil.get(
              settings, OkHttpSettingsUtil.INITIAL_WINDOW_SIZE);
          outboundWindowSizeIncreased = outboundFlow.initialOutboundWindowSize(initialWindowSize);
        }

        // The changed settings are not finalized until SETTINGS acknowledgment frame is sent. Any
        // writes due to update in settings must be sent after SETTINGS acknowledgment frame,
        // otherwise it will cause a stream error (RST_STREAM).
        frameWriter.ackSettings(settings);
        frameWriter.flush();
        if (!receivedSettings) {
          receivedSettings = true;
          attributes = listener.transportReady(attributes);
        }

        // send any pending bytes / streams
        if (outboundWindowSizeIncreased) {
          outboundFlow.writeStreams();
        }
      }
    }

    @Override
    public void ping(boolean ack, int payload1, int payload2) {
      if (!keepAliveEnforcer.pingAcceptable()) {
        abruptShutdown(ErrorCode.ENHANCE_YOUR_CALM, "too_many_pings",
            Status.RESOURCE_EXHAUSTED.withDescription("Too many pings from client"), false);
        return;
      }
      long payload = (((long) payload1) << 32) | (payload2 & 0xffffffffL);
      if (!ack) {
        frameLogger.logPing(OkHttpFrameLogger.Direction.INBOUND, payload);
        synchronized (lock) {
          frameWriter.ping(true, payload1, payload2);
          frameWriter.flush();
        }
      } else {
        frameLogger.logPingAck(OkHttpFrameLogger.Direction.INBOUND, payload);
        if (KEEPALIVE_PING == payload) {
          return;
        }
        if (GRACEFUL_SHUTDOWN_PING == payload) {
          triggerGracefulSecondGoaway();
          return;
        }
        log.log(Level.INFO, "Received unexpected ping ack: " + payload);
      }
    }

    @Override
    public void ackSettings() {}

    @Override
    public void goAway(int lastGoodStreamId, ErrorCode errorCode, ByteString debugData) {
      frameLogger.logGoAway(
          OkHttpFrameLogger.Direction.INBOUND, lastGoodStreamId, errorCode, debugData);
      String description = String.format("Received GOAWAY: %s '%s'", errorCode, debugData.utf8());
      Status status = GrpcUtil.Http2Error.statusForCode(errorCode.httpCode)
          .withDescription(description);
      if (!ErrorCode.NO_ERROR.equals(errorCode)) {
        log.log(
            Level.WARNING, "Received GOAWAY: {0} {1}", new Object[] {errorCode, debugData.utf8()});
      }
      synchronized (lock) {
        goAwayStatus = status;
      }
    }

    @Override
    public void pushPromise(int streamId, int promisedStreamId, List<Header> requestHeaders)
        throws IOException {
      frameLogger.logPushPromise(OkHttpFrameLogger.Direction.INBOUND,
          streamId, promisedStreamId, requestHeaders);
      // streamId == 0 checking is in HTTP/2 decoder.
      // The server doesn't use PUSH_PROMISE, so all even streams are IDLE, and odd streams are not
      // peer-initiated.
      connectionError(ErrorCode.PROTOCOL_ERROR,
          "PUSH_PROMISE only allowed on peer-initiated streams. RFC7540 section 6.6");
    }

    @Override
    public void windowUpdate(int streamId, long delta) {
      frameLogger.logWindowsUpdate(OkHttpFrameLogger.Direction.INBOUND, streamId, delta);
      // delta == 0 checking is in HTTP/2 decoder. And it isn't quite right, as it will always cause
      // a GOAWAY. RFC7540 section 6.9 says to use RST_STREAM if the stream id isn't 0. Doesn't
      // matter much though.
      synchronized (lock) {
        if (streamId == Utils.CONNECTION_STREAM_ID) {
          outboundFlow.windowUpdate(null, (int) delta);
        } else {
          StreamState stream = streams.get(streamId);
          if (stream != null) {
            outboundFlow.windowUpdate(stream.getOutboundFlowState(), (int) delta);
          }
        }
      }
    }

    @Override
    public void priority(int streamId, int streamDependency, int weight, boolean exclusive) {
      frameLogger.logPriority(
          OkHttpFrameLogger.Direction.INBOUND, streamId, streamDependency, weight, exclusive);
      // streamId == 0 checking is in HTTP/2 decoder.
      // Ignore priority change.
    }

    @Override
    public void alternateService(int streamId, String origin, ByteString protocol, String host,
        int port, long maxAge) {}

    /**
     * Send GOAWAY to the server, then finish all streams and close the transport. RFC7540 §5.4.1.
     */
    private void connectionError(ErrorCode errorCode, String moreDetail) {
      Status status = GrpcUtil.Http2Error.statusForCode(errorCode.httpCode)
          .withDescription(String.format("HTTP2 connection error: %s '%s'", errorCode, moreDetail));
      abruptShutdown(errorCode, moreDetail, status, false);
    }

    /**
     * Respond with RST_STREAM, making sure to kill the associated stream if it exists. Reason will
     * rarely be seen. RFC7540 §5.4.2.
     */
    private void streamError(int streamId, ErrorCode errorCode, String reason) {
      if (errorCode == ErrorCode.PROTOCOL_ERROR) {
        log.log(
            Level.FINE, "Responding with RST_STREAM {0}: {1}", new Object[] {errorCode, reason});
      }
      synchronized (lock) {
        frameWriter.rstStream(streamId, errorCode);
        frameWriter.flush();
        StreamState stream = streams.get(streamId);
        if (stream != null) {
          stream.transportReportStatus(
              Status.INTERNAL.withDescription(
                  String.format("Responded with RST_STREAM %s: %s", errorCode, reason)));
          streamClosed(streamId, /*flush=*/ false);
        }
      }
    }

    private void respondWithHttpError(
        int streamId, boolean inFinished, int httpCode, Status.Code statusCode, String msg) {
      Metadata metadata = new Metadata();
      metadata.put(InternalStatus.CODE_KEY, statusCode.toStatus());
      metadata.put(InternalStatus.MESSAGE_KEY, msg);
      List<Header> headers =
          Headers.createHttpResponseHeaders(httpCode, "text/plain; charset=utf-8", metadata);
      Buffer data = new Buffer().writeUtf8(msg);

      synchronized (lock) {
        Http2ErrorStreamState stream =
            new Http2ErrorStreamState(streamId, lock, outboundFlow, config.flowControlWindow);
        if (streams.isEmpty()) {
          keepAliveEnforcer.onTransportActive();
          if (maxConnectionIdleManager != null) {
            maxConnectionIdleManager.onTransportActive();
          }
        }
        streams.put(streamId, stream);
        if (inFinished) {
          stream.inboundDataReceived(new Buffer(), 0, true);
        }
        frameWriter.headers(streamId, headers);
        outboundFlow.data(
            /*outFinished=*/true, stream.getOutboundFlowState(), data, /*flush=*/true);
        outboundFlow.notifyWhenNoPendingData(
            stream.getOutboundFlowState(), () -> rstOkAtEndOfHttpError(stream));
      }
    }

    private void rstOkAtEndOfHttpError(Http2ErrorStreamState stream) {
      synchronized (lock) {
        if (!stream.hasReceivedEndOfStream()) {
          frameWriter.rstStream(stream.streamId, ErrorCode.NO_ERROR);
        }
        streamClosed(stream.streamId, /*flush=*/ true);
      }
    }

    private void respondWithGrpcError(
        int streamId, boolean inFinished, Status.Code statusCode, String msg) {
      Metadata metadata = new Metadata();
      metadata.put(InternalStatus.CODE_KEY, statusCode.toStatus());
      metadata.put(InternalStatus.MESSAGE_KEY, msg);
      List<Header> headers = Headers.createResponseTrailers(metadata, false);

      synchronized (lock) {
        frameWriter.synReply(true, streamId, headers);
        if (!inFinished) {
          frameWriter.rstStream(streamId, ErrorCode.NO_ERROR);
        }
        frameWriter.flush();
      }
    }
  }

  private final class KeepAlivePinger implements KeepAliveManager.KeepAlivePinger {
    @Override
    public void ping() {
      synchronized (lock) {
        frameWriter.ping(false, 0, KEEPALIVE_PING);
        frameWriter.flush();
      }
      tracer.reportKeepAliveSent();
    }

    @Override
    public void onPingTimeout() {
      synchronized (lock) {
        goAwayStatus = Status.UNAVAILABLE
            .withDescription("Keepalive failed. Considering connection dead");
        GrpcUtil.closeQuietly(socket);
      }
    }
  }

  interface StreamState {
    /** Must be holding 'lock' when calling. */
    void inboundDataReceived(Buffer frame, int windowConsumed, boolean endOfStream);

    /** Must be holding 'lock' when calling. */
    boolean hasReceivedEndOfStream();

    /** Must be holding 'lock' when calling. */
    int inboundWindowAvailable();

    /** Must be holding 'lock' when calling. */
    void transportReportStatus(Status status);

    /** Must be holding 'lock' when calling. */
    void inboundRstReceived(Status status);

    OutboundFlowController.StreamState getOutboundFlowState();
  }

  static class Http2ErrorStreamState implements StreamState, OutboundFlowController.Stream {
    private final int streamId;
    private final Object lock;
    private final OutboundFlowController.StreamState outboundFlowState;
    @GuardedBy("lock")
    private int window;
    @GuardedBy("lock")
    private boolean receivedEndOfStream;

    Http2ErrorStreamState(
        int streamId, Object lock, OutboundFlowController outboundFlow, int initialWindowSize) {
      this.streamId = streamId;
      this.lock = lock;
      this.outboundFlowState = outboundFlow.createState(this, streamId);
      this.window = initialWindowSize;
    }

    @Override public void onSentBytes(int frameBytes) {}

    @Override public void inboundDataReceived(
        Buffer frame, int windowConsumed, boolean endOfStream) {
      synchronized (lock) {
        if (endOfStream) {
          receivedEndOfStream = true;
        }
        window -= windowConsumed;
        try {
          frame.skip(frame.size()); // Recycle segments
        } catch (IOException ex) {
          throw new AssertionError(ex);
        }
      }
    }

    @Override public boolean hasReceivedEndOfStream() {
      synchronized (lock) {
        return receivedEndOfStream;
      }
    }

    @Override public int inboundWindowAvailable() {
      synchronized (lock) {
        return window;
      }
    }

    @Override public void transportReportStatus(Status status) {}

    @Override public void inboundRstReceived(Status status) {}

    @Override public OutboundFlowController.StreamState getOutboundFlowState() {
      synchronized (lock) {
        return outboundFlowState;
      }
    }
  }
}
