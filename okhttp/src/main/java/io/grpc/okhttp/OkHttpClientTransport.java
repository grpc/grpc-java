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

package io.grpc.okhttp;

import static com.google.common.base.Preconditions.checkState;
import static io.grpc.okhttp.Utils.DEFAULT_WINDOW_SIZE;
import static io.grpc.okhttp.Utils.DEFAULT_WINDOW_UPDATE_RATIO;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientStreamTracer;
import io.grpc.Grpc;
import io.grpc.HttpConnectProxiedSocketAddress;
import io.grpc.InternalChannelz;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalLogId;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.SecurityLevel;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusException;
import io.grpc.internal.ClientStreamListener.RpcProgress;
import io.grpc.internal.ConnectionClientTransport;
import io.grpc.internal.GrpcAttributes;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.Http2Ping;
import io.grpc.internal.InUseStateAggregator;
import io.grpc.internal.KeepAliveManager;
import io.grpc.internal.KeepAliveManager.ClientKeepAlivePinger;
import io.grpc.internal.SerializingExecutor;
import io.grpc.internal.StatsTraceContext;
import io.grpc.internal.TransportTracer;
import io.grpc.okhttp.ExceptionHandlingFrameWriter.TransportExceptionHandler;
import io.grpc.okhttp.internal.ConnectionSpec;
import io.grpc.okhttp.internal.Credentials;
import io.grpc.okhttp.internal.StatusLine;
import io.grpc.okhttp.internal.framed.ErrorCode;
import io.grpc.okhttp.internal.framed.FrameReader;
import io.grpc.okhttp.internal.framed.FrameWriter;
import io.grpc.okhttp.internal.framed.Header;
import io.grpc.okhttp.internal.framed.HeadersMode;
import io.grpc.okhttp.internal.framed.Http2;
import io.grpc.okhttp.internal.framed.Settings;
import io.grpc.okhttp.internal.framed.Variant;
import io.grpc.okhttp.internal.proxy.HttpUrl;
import io.grpc.okhttp.internal.proxy.Request;
import io.perfmark.PerfMark;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.net.SocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;
import okio.Source;
import okio.Timeout;

/**
 * A okhttp-based {@link ConnectionClientTransport} implementation.
 */
class OkHttpClientTransport implements ConnectionClientTransport, TransportExceptionHandler,
      OutboundFlowController.Transport {
  private static final Map<ErrorCode, Status> ERROR_CODE_TO_STATUS = buildErrorCodeToStatusMap();
  private static final Logger log = Logger.getLogger(OkHttpClientTransport.class.getName());

  private static Map<ErrorCode, Status> buildErrorCodeToStatusMap() {
    Map<ErrorCode, Status> errorToStatus = new EnumMap<>(ErrorCode.class);
    errorToStatus.put(ErrorCode.NO_ERROR,
        Status.INTERNAL.withDescription("No error: A GRPC status of OK should have been sent"));
    errorToStatus.put(ErrorCode.PROTOCOL_ERROR,
        Status.INTERNAL.withDescription("Protocol error"));
    errorToStatus.put(ErrorCode.INTERNAL_ERROR,
        Status.INTERNAL.withDescription("Internal error"));
    errorToStatus.put(ErrorCode.FLOW_CONTROL_ERROR,
        Status.INTERNAL.withDescription("Flow control error"));
    errorToStatus.put(ErrorCode.STREAM_CLOSED,
        Status.INTERNAL.withDescription("Stream closed"));
    errorToStatus.put(ErrorCode.FRAME_TOO_LARGE,
        Status.INTERNAL.withDescription("Frame too large"));
    errorToStatus.put(ErrorCode.REFUSED_STREAM,
        Status.UNAVAILABLE.withDescription("Refused stream"));
    errorToStatus.put(ErrorCode.CANCEL,
        Status.CANCELLED.withDescription("Cancelled"));
    errorToStatus.put(ErrorCode.COMPRESSION_ERROR,
        Status.INTERNAL.withDescription("Compression error"));
    errorToStatus.put(ErrorCode.CONNECT_ERROR,
        Status.INTERNAL.withDescription("Connect error"));
    errorToStatus.put(ErrorCode.ENHANCE_YOUR_CALM,
        Status.RESOURCE_EXHAUSTED.withDescription("Enhance your calm"));
    errorToStatus.put(ErrorCode.INADEQUATE_SECURITY,
        Status.PERMISSION_DENIED.withDescription("Inadequate security"));
    return Collections.unmodifiableMap(errorToStatus);
  }

  private final InetSocketAddress address;
  private final String defaultAuthority;
  private final String userAgent;
  private final Random random = new Random();
  // Returns new unstarted stopwatches
  private final Supplier<Stopwatch> stopwatchFactory;
  private final int initialWindowSize;
  private final Variant variant;
  private Listener listener;
  @GuardedBy("lock")
  private ExceptionHandlingFrameWriter frameWriter;
  private OutboundFlowController outboundFlow;
  private final Object lock = new Object();
  private final InternalLogId logId;
  @GuardedBy("lock")
  private int nextStreamId;
  @GuardedBy("lock")
  private final Map<Integer, OkHttpClientStream> streams = new HashMap<>();
  private final Executor executor;
  // Wrap on executor, to guarantee some operations be executed serially.
  private final SerializingExecutor serializingExecutor;
  private final ScheduledExecutorService scheduler;
  private final int maxMessageSize;
  private int connectionUnacknowledgedBytesRead;
  private ClientFrameHandler clientFrameHandler;
  // Caution: Not synchronized, new value can only be safely read after the connection is complete.
  private Attributes attributes;
  /**
   * Indicates the transport is in go-away state: no new streams will be processed, but existing
   * streams may continue.
   */
  @GuardedBy("lock")
  private Status goAwayStatus;
  @GuardedBy("lock")
  private boolean goAwaySent;
  @GuardedBy("lock")
  private Http2Ping ping;
  @GuardedBy("lock")
  private boolean stopped;
  @GuardedBy("lock")
  private boolean hasStream;
  private final SocketFactory socketFactory;
  private SSLSocketFactory sslSocketFactory;
  private HostnameVerifier hostnameVerifier;
  private Socket socket;
  @GuardedBy("lock")
  private int maxConcurrentStreams = 0;
  @SuppressWarnings("JdkObsolete") // Usage is bursty; want low memory usage when empty
  @GuardedBy("lock")
  private final Deque<OkHttpClientStream> pendingStreams = new LinkedList<>();
  private final ConnectionSpec connectionSpec;
  private KeepAliveManager keepAliveManager;
  private boolean enableKeepAlive;
  private long keepAliveTimeNanos;
  private long keepAliveTimeoutNanos;
  private boolean keepAliveWithoutCalls;
  private final Runnable tooManyPingsRunnable;
  private final int maxInboundMetadataSize;
  private final boolean useGetForSafeMethods;
  @GuardedBy("lock")
  private final TransportTracer transportTracer;
  @GuardedBy("lock")
  private final InUseStateAggregator<OkHttpClientStream> inUseState =
      new InUseStateAggregator<OkHttpClientStream>() {
        @Override
        protected void handleInUse() {
          listener.transportInUse(true);
        }

        @Override
        protected void handleNotInUse() {
          listener.transportInUse(false);
        }
      };
  @GuardedBy("lock")
  private InternalChannelz.Security securityInfo;

  @VisibleForTesting
  @Nullable
  final HttpConnectProxiedSocketAddress proxiedAddr;

  @VisibleForTesting
  int proxySocketTimeout = 30000;

  // The following fields should only be used for test.
  Runnable connectingCallback;
  SettableFuture<Void> connectedFuture;

  public OkHttpClientTransport(
      OkHttpChannelBuilder.OkHttpTransportFactory transportFactory,
      InetSocketAddress address,
      String authority,
      @Nullable String userAgent,
      Attributes eagAttrs,
      @Nullable HttpConnectProxiedSocketAddress proxiedAddr,
      Runnable tooManyPingsRunnable) {
    this(
        transportFactory,
        address,
        authority,
        userAgent,
        eagAttrs,
        GrpcUtil.STOPWATCH_SUPPLIER,
        new Http2(),
        proxiedAddr,
        tooManyPingsRunnable);
  }

  private OkHttpClientTransport(
      OkHttpChannelBuilder.OkHttpTransportFactory transportFactory,
      InetSocketAddress address,
      String authority,
      @Nullable String userAgent,
      Attributes eagAttrs,
      Supplier<Stopwatch> stopwatchFactory,
      Variant variant,
      @Nullable HttpConnectProxiedSocketAddress proxiedAddr,
      Runnable tooManyPingsRunnable) {
    this.address = Preconditions.checkNotNull(address, "address");
    this.defaultAuthority = authority;
    this.maxMessageSize = transportFactory.maxMessageSize;
    this.initialWindowSize = transportFactory.flowControlWindow;
    this.executor = Preconditions.checkNotNull(transportFactory.executor, "executor");
    serializingExecutor = new SerializingExecutor(transportFactory.executor);
    this.scheduler = Preconditions.checkNotNull(
        transportFactory.scheduledExecutorService, "scheduledExecutorService");
    // Client initiated streams are odd, server initiated ones are even. Server should not need to
    // use it. We start clients at 3 to avoid conflicting with HTTP negotiation.
    nextStreamId = 3;
    this.socketFactory = transportFactory.socketFactory == null
        ? SocketFactory.getDefault() : transportFactory.socketFactory;
    this.sslSocketFactory = transportFactory.sslSocketFactory;
    this.hostnameVerifier = transportFactory.hostnameVerifier;
    this.connectionSpec = Preconditions.checkNotNull(
        transportFactory.connectionSpec, "connectionSpec");
    this.stopwatchFactory = Preconditions.checkNotNull(stopwatchFactory, "stopwatchFactory");
    this.variant = Preconditions.checkNotNull(variant, "variant");
    this.userAgent = GrpcUtil.getGrpcUserAgent("okhttp", userAgent);
    this.proxiedAddr = proxiedAddr;
    this.tooManyPingsRunnable =
        Preconditions.checkNotNull(tooManyPingsRunnable, "tooManyPingsRunnable");
    this.maxInboundMetadataSize = transportFactory.maxInboundMetadataSize;
    this.transportTracer = transportFactory.transportTracerFactory.create();
    this.logId = InternalLogId.allocate(getClass(), address.toString());
    this.attributes = Attributes.newBuilder()
        .set(GrpcAttributes.ATTR_CLIENT_EAG_ATTRS, eagAttrs).build();
    this.useGetForSafeMethods = transportFactory.useGetForSafeMethods;
    initTransportTracer();
  }

  /**
   * Create a transport connected to a fake peer for test.
   */
  @SuppressWarnings("AddressSelection") // An IP address always returns one address
  @VisibleForTesting
  OkHttpClientTransport(
      OkHttpChannelBuilder.OkHttpTransportFactory transportFactory,
      String userAgent,
      Supplier<Stopwatch> stopwatchFactory,
      Variant variant,
      @Nullable Runnable connectingCallback,
      SettableFuture<Void> connectedFuture,
      Runnable tooManyPingsRunnable) {
    this(
        transportFactory,
        new InetSocketAddress("127.0.0.1", 80),
        "notarealauthority:80",
        userAgent,
        Attributes.EMPTY,
        stopwatchFactory,
        variant,
        null,
        tooManyPingsRunnable);
    this.connectingCallback = connectingCallback;
    this.connectedFuture = Preconditions.checkNotNull(connectedFuture, "connectedFuture");
  }

  // sslSocketFactory is set to null when use plaintext.
  boolean isUsingPlaintext() {
    return sslSocketFactory == null;
  }

  private void initTransportTracer() {
    synchronized (lock) { // to make @GuardedBy linter happy
      transportTracer.setFlowControlWindowReader(new TransportTracer.FlowControlReader() {
        @Override
        public TransportTracer.FlowControlWindows read() {
          synchronized (lock) {
            long local = outboundFlow == null ? -1 : outboundFlow.windowUpdate(null, 0);
            // connectionUnacknowledgedBytesRead is only readable by ClientFrameHandler, so we
            // provide a lower bound.
            long remote = (long) (initialWindowSize * DEFAULT_WINDOW_UPDATE_RATIO);
            return new TransportTracer.FlowControlWindows(local, remote);
          }
        }
      });
    }
  }

  /**
   * Enable keepalive with custom delay and timeout.
   */
  void enableKeepAlive(boolean enable, long keepAliveTimeNanos,
      long keepAliveTimeoutNanos, boolean keepAliveWithoutCalls) {
    enableKeepAlive = enable;
    this.keepAliveTimeNanos = keepAliveTimeNanos;
    this.keepAliveTimeoutNanos = keepAliveTimeoutNanos;
    this.keepAliveWithoutCalls = keepAliveWithoutCalls;
  }

  @Override
  public void ping(final PingCallback callback, Executor executor) {
    long data = 0;
    Http2Ping p;
    boolean writePing;
    synchronized (lock) {
      checkState(frameWriter != null);
      if (stopped) {
        Http2Ping.notifyFailed(callback, executor, getPingFailure());
        return;
      }
      if (ping != null) {
        // we only allow one outstanding ping at a time, so just add the callback to
        // any outstanding operation
        p = ping;
        writePing = false;
      } else {
        // set outstanding operation and then write the ping after releasing lock
        data = random.nextLong();
        Stopwatch stopwatch = stopwatchFactory.get();
        stopwatch.start();
        p = ping = new Http2Ping(data, stopwatch);
        writePing = true;
        transportTracer.reportKeepAliveSent();
      }
      if (writePing) {
        frameWriter.ping(false, (int) (data >>> 32), (int) data);
      }
    }
    // If transport concurrently failed/stopped since we released the lock above, this could
    // immediately invoke callback (which we shouldn't do while holding a lock)
    p.addCallback(callback, executor);
  }

  @Override
  public OkHttpClientStream newStream(
      MethodDescriptor<?, ?> method, Metadata headers, CallOptions callOptions,
      ClientStreamTracer[] tracers) {
    Preconditions.checkNotNull(method, "method");
    Preconditions.checkNotNull(headers, "headers");
    StatsTraceContext statsTraceContext =
        StatsTraceContext.newClientContext(tracers, getAttributes(), headers);
    // FIXME: it is likely wrong to pass the transportTracer here as it'll exit the lock's scope
    synchronized (lock) { // to make @GuardedBy linter happy
      return new OkHttpClientStream(
          method,
          headers,
          frameWriter,
          OkHttpClientTransport.this,
          outboundFlow,
          lock,
          maxMessageSize,
          initialWindowSize,
          defaultAuthority,
          userAgent,
          statsTraceContext,
          transportTracer,
          callOptions,
          useGetForSafeMethods);
    }
  }

  @GuardedBy("lock")
  void streamReadyToStart(OkHttpClientStream clientStream) {
    if (goAwayStatus != null) {
      clientStream.transportState().transportReportStatus(
          goAwayStatus, RpcProgress.MISCARRIED, true, new Metadata());
    } else if (streams.size() >= maxConcurrentStreams) {
      pendingStreams.add(clientStream);
      setInUse(clientStream);
    } else {
      startStream(clientStream);
    }
  }

  @SuppressWarnings("GuardedBy")
  @GuardedBy("lock")
  private void startStream(OkHttpClientStream stream) {
    Preconditions.checkState(
        stream.transportState().id() == OkHttpClientStream.ABSENT_ID, "StreamId already assigned");
    streams.put(nextStreamId, stream);
    setInUse(stream);
    // TODO(b/145386688): This access should be guarded by 'stream.transportState().lock'; instead
    // found: 'this.lock'
    stream.transportState().start(nextStreamId);
    // For unary and server streaming, there will be a data frame soon, no need to flush the header.
    if ((stream.getType() != MethodType.UNARY && stream.getType() != MethodType.SERVER_STREAMING)
        || stream.useGet()) {
      frameWriter.flush();
    }
    if (nextStreamId >= Integer.MAX_VALUE - 2) {
      // Make sure nextStreamId greater than all used id, so that mayHaveCreatedStream() performs
      // correctly.
      nextStreamId = Integer.MAX_VALUE;
      startGoAway(Integer.MAX_VALUE, ErrorCode.NO_ERROR,
          Status.UNAVAILABLE.withDescription("Stream ids exhausted"));
    } else {
      nextStreamId += 2;
    }
  }

  /**
   * Starts pending streams, returns true if at least one pending stream is started.
   */
  @GuardedBy("lock")
  private boolean startPendingStreams() {
    boolean hasStreamStarted = false;
    while (!pendingStreams.isEmpty() && streams.size() < maxConcurrentStreams) {
      OkHttpClientStream stream = pendingStreams.poll();
      startStream(stream);
      hasStreamStarted = true;
    }
    return hasStreamStarted;
  }

  /**
   * Removes given pending stream, used when a pending stream is cancelled.
   */
  @GuardedBy("lock")
  void removePendingStream(OkHttpClientStream pendingStream) {
    pendingStreams.remove(pendingStream);
    maybeClearInUse(pendingStream);
  }

  @Override
  public Runnable start(Listener listener) {
    this.listener = Preconditions.checkNotNull(listener, "listener");

    if (enableKeepAlive) {
      keepAliveManager = new KeepAliveManager(
          new ClientKeepAlivePinger(this), scheduler, keepAliveTimeNanos, keepAliveTimeoutNanos,
          keepAliveWithoutCalls);
      keepAliveManager.onTransportStarted();
    }

    int maxQueuedControlFrames = 10000;
    final AsyncSink asyncSink = AsyncSink.sink(serializingExecutor, this, maxQueuedControlFrames);
    FrameWriter rawFrameWriter = asyncSink.limitControlFramesWriter(
        variant.newWriter(Okio.buffer(asyncSink), true));

    synchronized (lock) {
      // Handle FrameWriter exceptions centrally, since there are many callers. Note that errors
      // coming from rawFrameWriter are generally broken invariants/bugs, as AsyncSink does not
      // propagate syscall errors through the FrameWriter. But we handle the AsyncSink failures with
      // the same TransportExceptionHandler instance so it is all mixed back together.
      frameWriter = new ExceptionHandlingFrameWriter(this, rawFrameWriter);
      outboundFlow = new OutboundFlowController(this, frameWriter);
    }
    final CountDownLatch latch = new CountDownLatch(1);
    final CountDownLatch latchForExtraThread = new CountDownLatch(1);
    // The transport needs up to two threads to function once started,
    // but only needs one during handshaking. Start another thread during handshaking
    // to make sure there's still a free thread available. If the number of threads is exhausted,
    // it is better to kill the transport than for all the transports to hang unable to send.
    CyclicBarrier barrier = new CyclicBarrier(2);
    // Connecting in the serializingExecutor, so that some stream operations like synStream
    // will be executed after connected.

    serializingExecutor.execute(new Runnable() {
      @Override
      public void run() {
        // Use closed source on failure so that the reader immediately shuts down.
        BufferedSource source = Okio.buffer(new Source() {
          @Override
          public long read(Buffer sink, long byteCount) {
            return -1;
          }

          @Override
          public Timeout timeout() {
            return Timeout.NONE;
          }

          @Override
          public void close() {
          }
        });
        Socket sock;
        SSLSession sslSession = null;
        try {
          // This is a hack to make sure the connection preface and initial settings to be sent out
          // without blocking the start. By doing this essentially prevents potential deadlock when
          // network is not available during startup while another thread holding lock to send the
          // initial preface.
          try {
            latch.await();
            barrier.await(1000, TimeUnit.MILLISECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (TimeoutException | BrokenBarrierException e) {
            startGoAway(0, ErrorCode.INTERNAL_ERROR, Status.UNAVAILABLE
                .withDescription("Timed out waiting for second handshake thread. "
                    + "The transport executor pool may have run out of threads"));
            return;
          }

          if (proxiedAddr == null) {
            sock = socketFactory.createSocket(address.getAddress(), address.getPort());
          } else {
            if (proxiedAddr.getProxyAddress() instanceof InetSocketAddress) {
              sock = createHttpProxySocket(
                  proxiedAddr.getTargetAddress(),
                  (InetSocketAddress) proxiedAddr.getProxyAddress(),
                  proxiedAddr.getUsername(),
                  proxiedAddr.getPassword()
              );
            } else {
              throw Status.INTERNAL.withDescription(
                  "Unsupported SocketAddress implementation "
                  + proxiedAddr.getProxyAddress().getClass()).asException();
            }
          }
          if (sslSocketFactory != null) {
            SSLSocket sslSocket = OkHttpTlsUpgrader.upgrade(
                sslSocketFactory, hostnameVerifier, sock, getOverridenHost(), getOverridenPort(),
                connectionSpec);
            sslSession = sslSocket.getSession();
            sock = sslSocket;
          }
          sock.setTcpNoDelay(true);
          source = Okio.buffer(Okio.source(sock));
          asyncSink.becomeConnected(Okio.sink(sock), sock);

          // The return value of OkHttpTlsUpgrader.upgrade is an SSLSocket that has this info
          attributes = attributes.toBuilder()
              .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, sock.getRemoteSocketAddress())
              .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, sock.getLocalSocketAddress())
              .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession)
              .set(GrpcAttributes.ATTR_SECURITY_LEVEL,
                  sslSession == null ? SecurityLevel.NONE : SecurityLevel.PRIVACY_AND_INTEGRITY)
              .build();
        } catch (StatusException e) {
          startGoAway(0, ErrorCode.INTERNAL_ERROR, e.getStatus());
          return;
        } catch (Exception e) {
          onException(e);
          return;
        } finally {
          clientFrameHandler = new ClientFrameHandler(variant.newReader(source, true));
          latchForExtraThread.countDown();
        }
        synchronized (lock) {
          socket = Preconditions.checkNotNull(sock, "socket");
          if (sslSession != null) {
            securityInfo = new InternalChannelz.Security(new InternalChannelz.Tls(sslSession));
          }
        }
      }
    });

    executor.execute(new Runnable() {
      @Override
      public void run() {
        try {
          barrier.await(1000, TimeUnit.MILLISECONDS);
          latchForExtraThread.await();
        } catch (BrokenBarrierException | TimeoutException e) {
          // Something bad happened, maybe too few threads available!
          // This will be handled in the handshake thread.
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    });
    // Schedule to send connection preface & settings before any other write.
    try {
      sendConnectionPrefaceAndSettings();
    } finally {
      latch.countDown();
    }

    serializingExecutor.execute(new Runnable() {
      @Override
      public void run() {
        if (connectingCallback != null) {
          connectingCallback.run();
        }
        // ClientFrameHandler need to be started after connectionPreface / settings, otherwise it
        // may send goAway immediately.
        executor.execute(clientFrameHandler);
        synchronized (lock) {
          maxConcurrentStreams = Integer.MAX_VALUE;
          startPendingStreams();
        }
        if (connectedFuture != null) {
          connectedFuture.set(null);
        }
      }
    });
    return null;
  }

  /**
   * Should only be called once when the transport is first established.
   */
  private void sendConnectionPrefaceAndSettings() {
    synchronized (lock) {
      frameWriter.connectionPreface();
      Settings settings = new Settings();
      OkHttpSettingsUtil.set(settings, OkHttpSettingsUtil.INITIAL_WINDOW_SIZE, initialWindowSize);
      frameWriter.settings(settings);
      if (initialWindowSize > DEFAULT_WINDOW_SIZE) {
        frameWriter.windowUpdate(
                Utils.CONNECTION_STREAM_ID, initialWindowSize - DEFAULT_WINDOW_SIZE);
      }
    }
  }

  private Socket createHttpProxySocket(InetSocketAddress address, InetSocketAddress proxyAddress,
      String proxyUsername, String proxyPassword) throws StatusException {
    Socket sock = null;
    try {
      // The proxy address may not be resolved
      if (proxyAddress.getAddress() != null) {
        sock = socketFactory.createSocket(proxyAddress.getAddress(), proxyAddress.getPort());
      } else {
        sock =
            socketFactory.createSocket(proxyAddress.getHostName(), proxyAddress.getPort());
      }
      sock.setTcpNoDelay(true);
      // A socket timeout is needed because lost network connectivity while reading from the proxy,
      // can cause reading from the socket to hang.
      sock.setSoTimeout(proxySocketTimeout);

      Source source = Okio.source(sock);
      BufferedSink sink = Okio.buffer(Okio.sink(sock));

      // Prepare headers and request method line
      Request proxyRequest = createHttpProxyRequest(address, proxyUsername, proxyPassword);
      HttpUrl url = proxyRequest.httpUrl();
      String requestLine =
          String.format(Locale.US, "CONNECT %s:%d HTTP/1.1", url.host(), url.port());

      // Write request to socket
      sink.writeUtf8(requestLine).writeUtf8("\r\n");
      for (int i = 0, size = proxyRequest.headers().size(); i < size; i++) {
        sink.writeUtf8(proxyRequest.headers().name(i))
            .writeUtf8(": ")
            .writeUtf8(proxyRequest.headers().value(i))
            .writeUtf8("\r\n");
      }
      sink.writeUtf8("\r\n");
      // Flush buffer (flushes socket and sends request)
      sink.flush();

      // Read status line, check if 2xx was returned
      StatusLine statusLine = StatusLine.parse(readUtf8LineStrictUnbuffered(source));
      // Drain rest of headers
      while (!readUtf8LineStrictUnbuffered(source).equals("")) {}
      if (statusLine.code < 200 || statusLine.code >= 300) {
        Buffer body = new Buffer();
        try {
          sock.shutdownOutput();
          source.read(body, 1024);
        } catch (IOException ex) {
          body.writeUtf8("Unable to read body: " + ex.toString());
        }
        try {
          sock.close();
        } catch (IOException ignored) {
          // ignored
        }
        String message = String.format(
            Locale.US,
            "Response returned from proxy was not successful (expected 2xx, got %d %s). "
              + "Response body:\n%s",
            statusLine.code, statusLine.message, body.readUtf8());
        throw Status.UNAVAILABLE.withDescription(message).asException();
      }
      // As the socket will be used for RPCs from here on, we want the socket timeout back to zero.
      sock.setSoTimeout(0);
      return sock;
    } catch (IOException e) {
      if (sock != null) {
        GrpcUtil.closeQuietly(sock);
      }
      throw Status.UNAVAILABLE.withDescription("Failed trying to connect with proxy").withCause(e)
          .asException();
    }
  }

  private Request createHttpProxyRequest(InetSocketAddress address, String proxyUsername,
                                         String proxyPassword) {
    HttpUrl tunnelUrl = new HttpUrl.Builder()
        .scheme("https")
        .host(address.getHostName())
        .port(address.getPort())
        .build();

    Request.Builder request = new Request.Builder()
        .url(tunnelUrl)
        .header("Host", tunnelUrl.host() + ":" + tunnelUrl.port())
        .header("User-Agent", userAgent);

    // If we have proxy credentials, set them right away
    if (proxyUsername != null && proxyPassword != null) {
      request.header("Proxy-Authorization", Credentials.basic(proxyUsername, proxyPassword));
    }
    return request.build();
  }

  private static String readUtf8LineStrictUnbuffered(Source source) throws IOException {
    Buffer buffer = new Buffer();
    while (true) {
      if (source.read(buffer, 1) == -1) {
        throw new EOFException("\\n not found: " + buffer.readByteString().hex());
      }
      if (buffer.getByte(buffer.size() - 1) == '\n') {
        return buffer.readUtf8LineStrict();
      }
    }
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("logId", logId.getId())
        .add("address", address)
        .toString();
  }

  @Override
  public InternalLogId getLogId() {
    return logId;
  }

  /**
   * Gets the overridden authority hostname.  If the authority is overridden to be an invalid
   * authority, uri.getHost() will (rightly) return null, since the authority is no longer
   * an actual service.  This method overrides the behavior for practical reasons.  For example,
   * if an authority is in the form "invalid_authority" (note the "_"), rather than return null,
   * we return the input.  This is because the return value, in conjunction with getOverridenPort,
   * are used by the SSL library to reconstruct the actual authority.  It /already/ has a
   * connection to the port, independent of this function.
   *
   * <p>Note: if the defaultAuthority has a port number in it and is also bad, this code will do
   * the wrong thing.  An example wrong behavior would be "invalid_host:443".   Registry based
   * authorities do not have ports, so this is even more wrong than before.  Sorry.
   */
  @VisibleForTesting
  String getOverridenHost() {
    URI uri = GrpcUtil.authorityToUri(defaultAuthority);
    if (uri.getHost() != null) {
      return uri.getHost();
    }

    return defaultAuthority;
  }

  @VisibleForTesting
  int getOverridenPort() {
    URI uri = GrpcUtil.authorityToUri(defaultAuthority);
    if (uri.getPort() != -1) {
      return uri.getPort();
    }

    return address.getPort();
  }

  @Override
  public void shutdown(Status reason) {
    synchronized (lock) {
      if (goAwayStatus != null) {
        return;
      }

      goAwayStatus = reason;
      listener.transportShutdown(goAwayStatus);
      stopIfNecessary();
    }
  }

  @Override
  public void shutdownNow(Status reason) {
    shutdown(reason);
    synchronized (lock) {
      Iterator<Map.Entry<Integer, OkHttpClientStream>> it = streams.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<Integer, OkHttpClientStream> entry = it.next();
        it.remove();
        entry.getValue().transportState().transportReportStatus(reason, false, new Metadata());
        maybeClearInUse(entry.getValue());
      }

      for (OkHttpClientStream stream : pendingStreams) {
        // in cases such as the connection fails to ACK keep-alive, pending streams should have a
        // chance to retry and be routed to another connection.
        stream.transportState().transportReportStatus(
            reason, RpcProgress.MISCARRIED, true, new Metadata());
        maybeClearInUse(stream);
      }
      pendingStreams.clear();

      stopIfNecessary();
    }
  }

  @Override
  public Attributes getAttributes() {
    return attributes;
  }

  /**
   * Gets all active streams as an array.
   */
  @Override
  public OutboundFlowController.StreamState[] getActiveStreams() {
    synchronized (lock) {
      OutboundFlowController.StreamState[] flowStreams =
          new OutboundFlowController.StreamState[streams.size()];
      int i = 0;
      for (OkHttpClientStream stream : streams.values()) {
        flowStreams[i++] = stream.transportState().getOutboundFlowState();
      }
      return flowStreams;
    }
  }

  @VisibleForTesting
  ClientFrameHandler getHandler() {
    return clientFrameHandler;
  }

  @VisibleForTesting
  SocketFactory getSocketFactory() {
    return socketFactory;
  }

  @VisibleForTesting
  int getPendingStreamSize() {
    synchronized (lock) {
      return pendingStreams.size();
    }
  }

  @VisibleForTesting
  void setNextStreamId(int nextStreamId) {
    synchronized (lock) {
      this.nextStreamId = nextStreamId;
    }
  }

  /**
   * Finish all active streams due to an IOException, then close the transport.
   */
  @Override
  public void onException(Throwable failureCause) {
    Preconditions.checkNotNull(failureCause, "failureCause");
    Status status = Status.UNAVAILABLE.withCause(failureCause);
    startGoAway(0, ErrorCode.INTERNAL_ERROR, status);
  }

  /**
   * Send GOAWAY to the server, then finish all active streams and close the transport.
   */
  private void onError(ErrorCode errorCode, String moreDetail) {
    startGoAway(0, errorCode, toGrpcStatus(errorCode).augmentDescription(moreDetail));
  }

  private void startGoAway(int lastKnownStreamId, ErrorCode errorCode, Status status) {
    synchronized (lock) {
      if (goAwayStatus == null) {
        goAwayStatus = status;
        listener.transportShutdown(status);
      }
      if (errorCode != null && !goAwaySent) {
        // Send GOAWAY with lastGoodStreamId of 0, since we don't expect any server-initiated
        // streams. The GOAWAY is part of graceful shutdown.
        goAwaySent = true;
        frameWriter.goAway(0, errorCode, new byte[0]);
      }

      Iterator<Map.Entry<Integer, OkHttpClientStream>> it = streams.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<Integer, OkHttpClientStream> entry = it.next();
        if (entry.getKey() > lastKnownStreamId) {
          it.remove();
          entry.getValue().transportState().transportReportStatus(
              status, RpcProgress.REFUSED, false, new Metadata());
          maybeClearInUse(entry.getValue());
        }
      }

      for (OkHttpClientStream stream : pendingStreams) {
        stream.transportState().transportReportStatus(
            status, RpcProgress.MISCARRIED, true, new Metadata());
        maybeClearInUse(stream);
      }
      pendingStreams.clear();

      stopIfNecessary();
    }
  }

  /**
   * Called when a stream is closed. We do things like:
   * <ul>
   * <li>Removing the stream from the map.
   * <li>Optionally reporting the status.
   * <li>Starting pending streams if we can.
   * <li>Stopping the transport if this is the last live stream under a go-away status.
   * </ul>
   *
   * @param streamId the Id of the stream.
   * @param status the final status of this stream, null means no need to report.
   * @param stopDelivery interrupt queued messages in the deframer
   * @param errorCode reset the stream with this ErrorCode if not null.
   * @param trailers the trailers received if not null
   */
  void finishStream(
      int streamId,
      @Nullable Status status,
      RpcProgress rpcProgress,
      boolean stopDelivery,
      @Nullable ErrorCode errorCode,
      @Nullable Metadata trailers) {
    synchronized (lock) {
      OkHttpClientStream stream = streams.remove(streamId);
      if (stream != null) {
        if (errorCode != null) {
          frameWriter.rstStream(streamId, ErrorCode.CANCEL);
        }
        if (status != null) {
          stream
              .transportState()
              .transportReportStatus(
                  status,
                  rpcProgress,
                  stopDelivery,
                  trailers != null ? trailers : new Metadata());
        }
        if (!startPendingStreams()) {
          stopIfNecessary();
        }
        maybeClearInUse(stream);
      }
    }
  }

  /**
   * When the transport is in goAway state, we should stop it once all active streams finish.
   */
  @GuardedBy("lock")
  private void stopIfNecessary() {
    if (!(goAwayStatus != null && streams.isEmpty() && pendingStreams.isEmpty())) {
      return;
    }
    if (stopped) {
      return;
    }
    stopped = true;

    if (keepAliveManager != null) {
      keepAliveManager.onTransportTermination();
    }

    if (ping != null) {
      ping.failed(getPingFailure());
      ping = null;
    }

    if (!goAwaySent) {
      // Send GOAWAY with lastGoodStreamId of 0, since we don't expect any server-initiated
      // streams. The GOAWAY is part of graceful shutdown.
      goAwaySent = true;
      frameWriter.goAway(0, ErrorCode.NO_ERROR, new byte[0]);
    }

    // We will close the underlying socket in the writing thread to break out the reader
    // thread, which will close the frameReader and notify the listener.
    frameWriter.close();
  }

  @GuardedBy("lock")
  private void maybeClearInUse(OkHttpClientStream stream) {
    if (hasStream) {
      if (pendingStreams.isEmpty() && streams.isEmpty()) {
        hasStream = false;
        if (keepAliveManager != null) {
          // We don't have any active streams. No need to do keepalives any more.
          // Again, we have to call this inside the lock to avoid the race between onTransportIdle
          // and onTransportActive.
          keepAliveManager.onTransportIdle();
        }
      }
    }
    if (stream.shouldBeCountedForInUse()) {
      inUseState.updateObjectInUse(stream, false);
    }
  }

  @GuardedBy("lock")
  private void setInUse(OkHttpClientStream stream) {
    if (!hasStream) {
      hasStream = true;
      if (keepAliveManager != null) {
        // We have a new stream. We might need to do keepalives now.
        // Note that we have to do this inside the lock to avoid calling
        // KeepAliveManager.onTransportActive and KeepAliveManager.onTransportIdle in the wrong
        // order.
        keepAliveManager.onTransportActive();
      }
    }
    if (stream.shouldBeCountedForInUse()) {
      inUseState.updateObjectInUse(stream, true);
    }
  }

  private Throwable getPingFailure() {
    synchronized (lock) {
      if (goAwayStatus != null) {
        return goAwayStatus.asException();
      } else {
        return Status.UNAVAILABLE.withDescription("Connection closed").asException();
      }
    }
  }

  boolean mayHaveCreatedStream(int streamId) {
    synchronized (lock) {
      return streamId < nextStreamId && (streamId & 1) == 1;
    }
  }

  OkHttpClientStream getStream(int streamId) {
    synchronized (lock) {
      return streams.get(streamId);
    }
  }

  /**
   * Returns a Grpc status corresponding to the given ErrorCode.
   */
  @VisibleForTesting
  static Status toGrpcStatus(ErrorCode code) {
    Status status = ERROR_CODE_TO_STATUS.get(code);
    return status != null ? status : Status.UNKNOWN.withDescription(
        "Unknown http2 error code: " + code.httpCode);
  }

  @Override
  public ListenableFuture<SocketStats> getStats() {
    SettableFuture<SocketStats> ret = SettableFuture.create();
    synchronized (lock) {
      if (socket == null) {
        ret.set(new SocketStats(
            transportTracer.getStats(),
            /*local=*/ null,
            /*remote=*/ null,
            new InternalChannelz.SocketOptions.Builder().build(),
            /*security=*/ null));
      } else {
        ret.set(new SocketStats(
            transportTracer.getStats(),
            socket.getLocalSocketAddress(),
            socket.getRemoteSocketAddress(),
            Utils.getSocketOptions(socket),
            securityInfo));
      }
      return ret;
    }
  }

  /**
   * Runnable which reads frames and dispatches them to in flight calls.
   */
  class ClientFrameHandler implements FrameReader.Handler, Runnable {

    private final OkHttpFrameLogger logger =
        new OkHttpFrameLogger(Level.FINE, OkHttpClientTransport.class);
    FrameReader frameReader;
    boolean firstSettings = true;

    ClientFrameHandler(FrameReader frameReader) {
      this.frameReader = frameReader;
    }

    @Override
    @SuppressWarnings("Finally")
    public void run() {
      String threadName = Thread.currentThread().getName();
      Thread.currentThread().setName("OkHttpClientTransport");
      try {
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
          status = Status.UNAVAILABLE.withDescription("End of stream or IOException");
        }
        startGoAway(0, ErrorCode.INTERNAL_ERROR, status);
      } catch (Throwable t) {
        // TODO(madongfly): Send the exception message to the server.
        startGoAway(
            0,
            ErrorCode.PROTOCOL_ERROR,
            Status.INTERNAL.withDescription("error in frame handler").withCause(t));
      } finally {
        try {
          frameReader.close();
        } catch (IOException ex) {
          log.log(Level.INFO, "Exception closing frame reader", ex);
        } catch (RuntimeException e) {
          // This same check is done in okhttp proper:
          // https://github.com/square/okhttp/blob/3cc0f4917cbda03cb31617f8ead1e0aeb19de2fb/okhttp/src/main/kotlin/okhttp3/internal/-UtilJvm.kt#L270

          // Conscrypt in Android 10 and 11 may throw closing an SSLSocket. This is safe to ignore.
          // https://issuetracker.google.com/issues/177450597
          if (!"bio == null".equals(e.getMessage())) {
            throw e;
          }
        }
        listener.transportTerminated();
        Thread.currentThread().setName(threadName);
      }
    }

    /**
     * Handle an HTTP2 DATA frame.
     */
    @SuppressWarnings("GuardedBy")
    @Override
    public void data(boolean inFinished, int streamId, BufferedSource in, int length,
                     int paddedLength)
        throws IOException {
      logger.logData(OkHttpFrameLogger.Direction.INBOUND,
          streamId, in.getBuffer(), length, inFinished);
      OkHttpClientStream stream = getStream(streamId);
      if (stream == null) {
        if (mayHaveCreatedStream(streamId)) {
          synchronized (lock) {
            frameWriter.rstStream(streamId, ErrorCode.STREAM_CLOSED);
          }
          in.skip(length);
        } else {
          onError(ErrorCode.PROTOCOL_ERROR, "Received data for unknown stream: " + streamId);
          return;
        }
      } else {
        // Wait until the frame is complete.
        in.require(length);

        Buffer buf = new Buffer();
        buf.write(in.getBuffer(), length);
        PerfMark.event("OkHttpClientTransport$ClientFrameHandler.data",
            stream.transportState().tag());
        synchronized (lock) {
          // TODO(b/145386688): This access should be guarded by 'stream.transportState().lock';
          // instead found: 'OkHttpClientTransport.this.lock'
          stream.transportState().transportDataReceived(buf, inFinished, paddedLength - length);
        }
      }

      // connection window update
      connectionUnacknowledgedBytesRead += paddedLength;
      if (connectionUnacknowledgedBytesRead >= initialWindowSize * DEFAULT_WINDOW_UPDATE_RATIO) {
        synchronized (lock) {
          frameWriter.windowUpdate(0, connectionUnacknowledgedBytesRead);
        }
        connectionUnacknowledgedBytesRead = 0;
      }
    }

    /**
     * Handle HTTP2 HEADER and CONTINUATION frames.
     */
    @SuppressWarnings("GuardedBy")
    @Override
    public void headers(boolean outFinished,
        boolean inFinished,
        int streamId,
        int associatedStreamId,
        List<Header> headerBlock,
        HeadersMode headersMode) {
      logger.logHeaders(OkHttpFrameLogger.Direction.INBOUND, streamId, headerBlock, inFinished);
      boolean unknownStream = false;
      Status failedStatus = null;
      if (maxInboundMetadataSize != Integer.MAX_VALUE) {
        int metadataSize = headerBlockSize(headerBlock);
        if (metadataSize > maxInboundMetadataSize) {
          failedStatus = Status.RESOURCE_EXHAUSTED.withDescription(
              String.format(
                  Locale.US,
                  "Response %s metadata larger than %d: %d",
                  inFinished ? "trailer" : "header",
                  maxInboundMetadataSize,
                  metadataSize));
        }
      }
      synchronized (lock) {
        OkHttpClientStream stream = streams.get(streamId);
        if (stream == null) {
          if (mayHaveCreatedStream(streamId)) {
            frameWriter.rstStream(streamId, ErrorCode.STREAM_CLOSED);
          } else {
            unknownStream = true;
          }
        } else {
          if (failedStatus == null) {
            PerfMark.event("OkHttpClientTransport$ClientFrameHandler.headers",
                stream.transportState().tag());
            // TODO(b/145386688): This access should be guarded by 'stream.transportState().lock';
            // instead found: 'OkHttpClientTransport.this.lock'
            stream.transportState().transportHeadersReceived(headerBlock, inFinished);
          } else {
            if (!inFinished) {
              frameWriter.rstStream(streamId, ErrorCode.CANCEL);
            }
            stream.transportState().transportReportStatus(failedStatus, false, new Metadata());
          }
        }
      }
      if (unknownStream) {
        // We don't expect any server-initiated streams.
        onError(ErrorCode.PROTOCOL_ERROR, "Received header for unknown stream: " + streamId);
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

    @Override
    public void rstStream(int streamId, ErrorCode errorCode) {
      logger.logRstStream(OkHttpFrameLogger.Direction.INBOUND, streamId, errorCode);
      Status status = toGrpcStatus(errorCode).augmentDescription("Rst Stream");
      boolean stopDelivery =
          (status.getCode() == Code.CANCELLED || status.getCode() == Code.DEADLINE_EXCEEDED);
      synchronized (lock) {
        OkHttpClientStream stream = streams.get(streamId);
        if (stream != null) {
          PerfMark.event("OkHttpClientTransport$ClientFrameHandler.rstStream",
              stream.transportState().tag());
          finishStream(
              streamId, status,
              errorCode == ErrorCode.REFUSED_STREAM ? RpcProgress.REFUSED : RpcProgress.PROCESSED,
              stopDelivery, null, null);
        }
      }
    }

    @Override
    public void settings(boolean clearPrevious, Settings settings) {
      logger.logSettings(OkHttpFrameLogger.Direction.INBOUND, settings);
      boolean outboundWindowSizeIncreased = false;
      synchronized (lock) {
        if (OkHttpSettingsUtil.isSet(settings, OkHttpSettingsUtil.MAX_CONCURRENT_STREAMS)) {
          int receivedMaxConcurrentStreams = OkHttpSettingsUtil.get(
              settings, OkHttpSettingsUtil.MAX_CONCURRENT_STREAMS);
          maxConcurrentStreams = receivedMaxConcurrentStreams;
        }

        if (OkHttpSettingsUtil.isSet(settings, OkHttpSettingsUtil.INITIAL_WINDOW_SIZE)) {
          int initialWindowSize = OkHttpSettingsUtil.get(
              settings, OkHttpSettingsUtil.INITIAL_WINDOW_SIZE);
          outboundWindowSizeIncreased = outboundFlow.initialOutboundWindowSize(initialWindowSize);
        }
        if (firstSettings) {
          attributes = listener.filterTransport(attributes);
          listener.transportReady();
          firstSettings = false;
        }

        // The changed settings are not finalized until SETTINGS acknowledgment frame is sent. Any
        // writes due to update in settings must be sent after SETTINGS acknowledgment frame,
        // otherwise it will cause a stream error (RST_STREAM).
        frameWriter.ackSettings(settings);

        // send any pending bytes / streams
        if (outboundWindowSizeIncreased) {
          outboundFlow.writeStreams();
        }
        startPendingStreams();
      }
    }

    @Override
    public void ping(boolean ack, int payload1, int payload2) {
      long ackPayload = (((long) payload1) << 32) | (payload2 & 0xffffffffL);
      logger.logPing(OkHttpFrameLogger.Direction.INBOUND, ackPayload);
      if (!ack) {
        synchronized (lock) {
          frameWriter.ping(true, payload1, payload2);
        }
      } else {
        Http2Ping p = null;
        synchronized (lock) {
          if (ping != null) {
            if (ping.payload() == ackPayload) {
              p = ping;
              ping = null;
            } else {
              log.log(Level.WARNING, String.format(
                  Locale.US, "Received unexpected ping ack. Expecting %d, got %d",
                  ping.payload(), ackPayload));
            }
          } else {
            log.warning("Received unexpected ping ack. No ping outstanding");
          }
        }
        // don't complete it while holding lock since callbacks could run immediately
        if (p != null) {
          p.complete();
        }
      }
    }

    @Override
    public void ackSettings() {
      // Do nothing currently.
    }

    @Override
    public void goAway(int lastGoodStreamId, ErrorCode errorCode, ByteString debugData) {
      logger.logGoAway(OkHttpFrameLogger.Direction.INBOUND, lastGoodStreamId, errorCode, debugData);
      if (errorCode == ErrorCode.ENHANCE_YOUR_CALM) {
        String data = debugData.utf8();
        log.log(Level.WARNING, String.format(
            "%s: Received GOAWAY with ENHANCE_YOUR_CALM. Debug data: %s", this, data));
        if ("too_many_pings".equals(data)) {
          tooManyPingsRunnable.run();
        }
      }
      Status status = GrpcUtil.Http2Error.statusForCode(errorCode.httpCode)
          .augmentDescription("Received Goaway");
      if (debugData.size() > 0) {
        // If a debug message was provided, use it.
        status = status.augmentDescription(debugData.utf8());
      }
      startGoAway(lastGoodStreamId, null, status);
    }

    @Override
    public void pushPromise(int streamId, int promisedStreamId, List<Header> requestHeaders)
        throws IOException {
      logger.logPushPromise(OkHttpFrameLogger.Direction.INBOUND,
          streamId, promisedStreamId, requestHeaders);
      // We don't accept server initiated stream.
      synchronized (lock) {
        frameWriter.rstStream(streamId, ErrorCode.PROTOCOL_ERROR);
      }
    }

    @Override
    public void windowUpdate(int streamId, long delta) {
      logger.logWindowsUpdate(OkHttpFrameLogger.Direction.INBOUND, streamId, delta);
      if (delta == 0) {
        String errorMsg = "Received 0 flow control window increment.";
        if (streamId == 0) {
          onError(ErrorCode.PROTOCOL_ERROR, errorMsg);
        } else {
          finishStream(
              streamId, Status.INTERNAL.withDescription(errorMsg), RpcProgress.PROCESSED, false,
              ErrorCode.PROTOCOL_ERROR, null);
        }
        return;
      }

      boolean unknownStream = false;
      synchronized (lock) {
        if (streamId == Utils.CONNECTION_STREAM_ID) {
          outboundFlow.windowUpdate(null, (int) delta);
          return;
        }

        OkHttpClientStream stream = streams.get(streamId);
        if (stream != null) {
          outboundFlow.windowUpdate(stream.transportState().getOutboundFlowState(), (int) delta);
        } else if (!mayHaveCreatedStream(streamId)) {
          unknownStream = true;
        }
      }
      if (unknownStream) {
        onError(ErrorCode.PROTOCOL_ERROR,
            "Received window_update for unknown stream: " + streamId);
      }
    }

    @Override
    public void priority(int streamId, int streamDependency, int weight, boolean exclusive) {
      // Ignore priority change.
      // TODO(madongfly): log
    }

    @Override
    public void alternateService(int streamId, String origin, ByteString protocol, String host,
        int port, long maxAge) {
      // TODO(madongfly): Deal with alternateService propagation
    }
  }
}