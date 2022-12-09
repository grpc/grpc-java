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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.DoNotCall;
import io.grpc.ChoiceServerCredentials;
import io.grpc.ExperimentalApi;
import io.grpc.ForwardingServerBuilder;
import io.grpc.InsecureServerCredentials;
import io.grpc.Internal;
import io.grpc.ServerBuilder;
import io.grpc.ServerCredentials;
import io.grpc.ServerStreamTracer;
import io.grpc.TlsServerCredentials;
import io.grpc.internal.FixedObjectPool;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.InternalServer;
import io.grpc.internal.KeepAliveManager;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServerImplBuilder;
import io.grpc.internal.SharedResourcePool;
import io.grpc.internal.TransportTracer;
import io.grpc.okhttp.internal.Platform;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.security.GeneralSecurityException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

/**
 * Build servers with the OkHttp transport.
 *
 * @since 1.49.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1785")
public final class OkHttpServerBuilder extends ForwardingServerBuilder<OkHttpServerBuilder> {
  private static final Logger log = Logger.getLogger(OkHttpServerBuilder.class.getName());
  private static final int DEFAULT_FLOW_CONTROL_WINDOW = 65535;

  static final long MAX_CONNECTION_IDLE_NANOS_DISABLED = Long.MAX_VALUE;
  private static final long MIN_MAX_CONNECTION_IDLE_NANO = TimeUnit.SECONDS.toNanos(1L);
  static final long MAX_CONNECTION_AGE_NANOS_DISABLED = Long.MAX_VALUE;
  static final long MAX_CONNECTION_AGE_GRACE_NANOS_INFINITE = Long.MAX_VALUE;
  private static final long MIN_MAX_CONNECTION_AGE_NANO = TimeUnit.SECONDS.toNanos(1L);

  private static final long AS_LARGE_AS_INFINITE = TimeUnit.DAYS.toNanos(1000L);
  private static final ObjectPool<Executor> DEFAULT_TRANSPORT_EXECUTOR_POOL =
      OkHttpChannelBuilder.DEFAULT_TRANSPORT_EXECUTOR_POOL;

  /**
   * Always throws, to shadow {@code ServerBuilder.forPort()}.
   *
   * @deprecated Use {@link #forPort(int, ServerCredentials)} instead
   */
  @DoNotCall("Always throws. Use forPort(int, ServerCredentials) instead")
  @Deprecated
  public static OkHttpServerBuilder forPort(int port) {
    throw new UnsupportedOperationException();
  }

  /**
   * Creates a builder for a server listening on {@code port}.
   */
  public static OkHttpServerBuilder forPort(int port, ServerCredentials creds) {
    return forPort(new InetSocketAddress(port), creds);
  }

  /**
   * Creates a builder for a server listening on {@code address}.
   */
  public static OkHttpServerBuilder forPort(SocketAddress address, ServerCredentials creds) {
    HandshakerSocketFactoryResult result = handshakerSocketFactoryFrom(creds);
    if (result.error != null) {
      throw new IllegalArgumentException(result.error);
    }
    return new OkHttpServerBuilder(address, result.factory);
  }

  final ServerImplBuilder serverImplBuilder = new ServerImplBuilder(this::buildTransportServers);
  final SocketAddress listenAddress;
  final HandshakerSocketFactory handshakerSocketFactory;
  TransportTracer.Factory transportTracerFactory = TransportTracer.getDefaultFactory();

  ObjectPool<Executor> transportExecutorPool = DEFAULT_TRANSPORT_EXECUTOR_POOL;
  ObjectPool<ScheduledExecutorService> scheduledExecutorServicePool =
      SharedResourcePool.forResource(GrpcUtil.TIMER_SERVICE);

  ServerSocketFactory socketFactory = ServerSocketFactory.getDefault();
  long keepAliveTimeNanos = GrpcUtil.DEFAULT_SERVER_KEEPALIVE_TIME_NANOS;
  long keepAliveTimeoutNanos = GrpcUtil.DEFAULT_SERVER_KEEPALIVE_TIMEOUT_NANOS;
  int flowControlWindow = DEFAULT_FLOW_CONTROL_WINDOW;
  int maxInboundMetadataSize = GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE;
  int maxInboundMessageSize = GrpcUtil.DEFAULT_MAX_MESSAGE_SIZE;
  long maxConnectionIdleInNanos = MAX_CONNECTION_IDLE_NANOS_DISABLED;
  boolean permitKeepAliveWithoutCalls;
  long permitKeepAliveTimeInNanos = TimeUnit.MINUTES.toNanos(5);
  long maxConnectionAgeInNanos = MAX_CONNECTION_AGE_NANOS_DISABLED;
  long maxConnectionAgeGraceInNanos = MAX_CONNECTION_AGE_GRACE_NANOS_INFINITE;

  @VisibleForTesting
  OkHttpServerBuilder(
      SocketAddress address, HandshakerSocketFactory handshakerSocketFactory) {
    this.listenAddress = Preconditions.checkNotNull(address, "address");
    this.handshakerSocketFactory =
        Preconditions.checkNotNull(handshakerSocketFactory, "handshakerSocketFactory");
  }

  @Internal
  @Override
  protected ServerBuilder<?> delegate() {
    return serverImplBuilder;
  }

  // @VisibleForTesting
  OkHttpServerBuilder setTransportTracerFactory(TransportTracer.Factory transportTracerFactory) {
    this.transportTracerFactory = transportTracerFactory;
    return this;
  }

  /**
   * Override the default executor necessary for internal transport use.
   *
   * <p>The channel does not take ownership of the given executor. It is the caller' responsibility
   * to shutdown the executor when appropriate.
   */
  public OkHttpServerBuilder transportExecutor(Executor transportExecutor) {
    if (transportExecutor == null) {
      this.transportExecutorPool = DEFAULT_TRANSPORT_EXECUTOR_POOL;
    } else {
      this.transportExecutorPool = new FixedObjectPool<>(transportExecutor);
    }
    return this;
  }

  /**
   * Override the default {@link ServerSocketFactory} used to listen. If the socket factory is not
   * set or set to null, a default one will be used.
   */
  public OkHttpServerBuilder socketFactory(ServerSocketFactory socketFactory) {
    if (socketFactory == null) {
      this.socketFactory = ServerSocketFactory.getDefault();
    } else {
      this.socketFactory = socketFactory;
    }
    return this;
  }

  /**
   * Sets the time without read activity before sending a keepalive ping. An unreasonably small
   * value might be increased, and {@code Long.MAX_VALUE} nano seconds or an unreasonably large
   * value will disable keepalive. Defaults to two hours.
   *
   * @throws IllegalArgumentException if time is not positive
   */
  @Override
  public OkHttpServerBuilder keepAliveTime(long keepAliveTime, TimeUnit timeUnit) {
    Preconditions.checkArgument(keepAliveTime > 0L, "keepalive time must be positive");
    keepAliveTimeNanos = timeUnit.toNanos(keepAliveTime);
    keepAliveTimeNanos = KeepAliveManager.clampKeepAliveTimeInNanos(keepAliveTimeNanos);
    if (keepAliveTimeNanos >= AS_LARGE_AS_INFINITE) {
      // Bump keepalive time to infinite. This disables keepalive.
      keepAliveTimeNanos = GrpcUtil.KEEPALIVE_TIME_NANOS_DISABLED;
    }
    return this;
  }

  /**
   * Sets a custom max connection idle time, connection being idle for longer than which will be
   * gracefully terminated. Idleness duration is defined since the most recent time the number of
   * outstanding RPCs became zero or the connection establishment. An unreasonably small value might
   * be increased. {@code Long.MAX_VALUE} nano seconds or an unreasonably large value will disable
   * max connection idle.
   */
  @Override
  public OkHttpServerBuilder maxConnectionIdle(long maxConnectionIdle, TimeUnit timeUnit) {
    checkArgument(maxConnectionIdle > 0L, "max connection idle must be positive: %s",
        maxConnectionIdle);
    maxConnectionIdleInNanos = timeUnit.toNanos(maxConnectionIdle);
    if (maxConnectionIdleInNanos >= AS_LARGE_AS_INFINITE) {
      maxConnectionIdleInNanos = MAX_CONNECTION_IDLE_NANOS_DISABLED;
    }
    if (maxConnectionIdleInNanos < MIN_MAX_CONNECTION_IDLE_NANO) {
      maxConnectionIdleInNanos = MIN_MAX_CONNECTION_IDLE_NANO;
    }
    return this;
  }

  /**
   * Sets a custom max connection age, connection lasting longer than which will be gracefully
   * terminated. An unreasonably small value might be increased.  A random jitter of +/-10% will be
   * added to it. {@code Long.MAX_VALUE} nano seconds or an unreasonably large value will disable
   * max connection age.
   */
  @Override
  public OkHttpServerBuilder maxConnectionAge(long maxConnectionAge, TimeUnit timeUnit) {
    checkArgument(maxConnectionAge > 0L, "max connection age must be positive: %s",
        maxConnectionAge);
    maxConnectionAgeInNanos = timeUnit.toNanos(maxConnectionAge);
    if (maxConnectionAgeInNanos >= AS_LARGE_AS_INFINITE) {
      maxConnectionAgeInNanos = MAX_CONNECTION_AGE_NANOS_DISABLED;
    }
    if (maxConnectionAgeInNanos < MIN_MAX_CONNECTION_AGE_NANO) {
      maxConnectionAgeInNanos = MIN_MAX_CONNECTION_AGE_NANO;
    }
    return this;
  }

  /**
   * Sets a custom grace time for the graceful connection termination. Once the max connection age
   * is reached, RPCs have the grace time to complete. RPCs that do not complete in time will be
   * cancelled, allowing the connection to terminate. {@code Long.MAX_VALUE} nano seconds or an
   * unreasonably large value are considered infinite.
   *
   * @see #maxConnectionAge(long, TimeUnit)
   */
  @Override
  public OkHttpServerBuilder maxConnectionAgeGrace(long maxConnectionAgeGrace, TimeUnit timeUnit) {
    checkArgument(maxConnectionAgeGrace >= 0L, "max connection age grace must be non-negative: %s",
        maxConnectionAgeGrace);
    maxConnectionAgeGraceInNanos = timeUnit.toNanos(maxConnectionAgeGrace);
    if (maxConnectionAgeGraceInNanos >= AS_LARGE_AS_INFINITE) {
      maxConnectionAgeGraceInNanos = MAX_CONNECTION_AGE_GRACE_NANOS_INFINITE;
    }
    return this;
  }

  /**
   * Sets a time waiting for read activity after sending a keepalive ping. If the time expires
   * without any read activity on the connection, the connection is considered dead. An unreasonably
   * small value might be increased. Defaults to 20 seconds.
   *
   * <p>This value should be at least multiple times the RTT to allow for lost packets.
   *
   * @throws IllegalArgumentException if timeout is not positive
   */
  @Override
  public OkHttpServerBuilder keepAliveTimeout(long keepAliveTimeout, TimeUnit timeUnit) {
    Preconditions.checkArgument(keepAliveTimeout > 0L, "keepalive timeout must be positive");
    keepAliveTimeoutNanos = timeUnit.toNanos(keepAliveTimeout);
    keepAliveTimeoutNanos = KeepAliveManager.clampKeepAliveTimeoutInNanos(keepAliveTimeoutNanos);
    return this;
  }

  /**
   * Specify the most aggressive keep-alive time clients are permitted to configure. The server will
   * try to detect clients exceeding this rate and when detected will forcefully close the
   * connection. The default is 5 minutes.
   *
   * <p>Even though a default is defined that allows some keep-alives, clients must not use
   * keep-alive without approval from the service owner. Otherwise, they may experience failures in
   * the future if the service becomes more restrictive. When unthrottled, keep-alives can cause a
   * significant amount of traffic and CPU usage, so clients and servers should be conservative in
   * what they use and accept.
   *
   * @see #permitKeepAliveWithoutCalls(boolean)
   */
  @CanIgnoreReturnValue
  @Override
  public OkHttpServerBuilder permitKeepAliveTime(long keepAliveTime, TimeUnit timeUnit) {
    checkArgument(keepAliveTime >= 0, "permit keepalive time must be non-negative: %s",
        keepAliveTime);
    permitKeepAliveTimeInNanos = timeUnit.toNanos(keepAliveTime);
    return this;
  }

  /**
   * Sets whether to allow clients to send keep-alive HTTP/2 PINGs even if there are no outstanding
   * RPCs on the connection. Defaults to {@code false}.
   *
   * @see #permitKeepAliveTime(long, TimeUnit)
   */
  @CanIgnoreReturnValue
  @Override
  public OkHttpServerBuilder permitKeepAliveWithoutCalls(boolean permit) {
    permitKeepAliveWithoutCalls = permit;
    return this;
  }

  /**
   * Sets the flow control window in bytes. If not called, the default value is 64 KiB.
   */
  public OkHttpServerBuilder flowControlWindow(int flowControlWindow) {
    Preconditions.checkState(flowControlWindow > 0, "flowControlWindow must be positive");
    this.flowControlWindow = flowControlWindow;
    return this;
  }

  /**
   * Provides a custom scheduled executor service.
   *
   * <p>It's an optional parameter. If the user has not provided a scheduled executor service when
   * the channel is built, the builder will use a static thread pool.
   *
   * @return this
   */
  public OkHttpServerBuilder scheduledExecutorService(
      ScheduledExecutorService scheduledExecutorService) {
    this.scheduledExecutorServicePool = new FixedObjectPool<>(
        Preconditions.checkNotNull(scheduledExecutorService, "scheduledExecutorService"));
    return this;
  }

  /**
   * Sets the maximum size of metadata allowed to be received. Defaults to 8 KiB.
   *
   * <p>The implementation does not currently limit memory usage; this value is checked only after
   * the metadata is decoded from the wire. It does prevent large metadata from being passed to the
   * application.
   *
   * @param bytes the maximum size of received metadata
   * @return this
   * @throws IllegalArgumentException if bytes is non-positive
   */
  @Override
  public OkHttpServerBuilder maxInboundMetadataSize(int bytes) {
    Preconditions.checkArgument(bytes > 0, "maxInboundMetadataSize must be > 0");
    this.maxInboundMetadataSize = bytes;
    return this;
  }

  /**
   * Sets the maximum message size allowed to be received on the server. If not called, defaults to
   * defaults to 4 MiB. The default provides protection to servers who haven't considered the
   * possibility of receiving large messages while trying to be large enough to not be hit in normal
   * usage.
   *
   * @param bytes the maximum number of bytes a single message can be.
   * @return this
   * @throws IllegalArgumentException if bytes is negative.
   */
  @Override
  public OkHttpServerBuilder maxInboundMessageSize(int bytes) {
    Preconditions.checkArgument(bytes >= 0, "negative max bytes");
    maxInboundMessageSize = bytes;
    return this;
  }

  void setStatsEnabled(boolean value) {
    this.serverImplBuilder.setStatsEnabled(value);
  }

  InternalServer buildTransportServers(
      List<? extends ServerStreamTracer.Factory> streamTracerFactories) {
    return new OkHttpServer(this, streamTracerFactories, serverImplBuilder.getChannelz());
  }

  private static final EnumSet<TlsServerCredentials.Feature> understoodTlsFeatures =
      EnumSet.of(
          TlsServerCredentials.Feature.MTLS, TlsServerCredentials.Feature.CUSTOM_MANAGERS);

  static HandshakerSocketFactoryResult handshakerSocketFactoryFrom(ServerCredentials creds) {
    if (creds instanceof TlsServerCredentials) {
      TlsServerCredentials tlsCreds = (TlsServerCredentials) creds;
      Set<TlsServerCredentials.Feature> incomprehensible =
          tlsCreds.incomprehensible(understoodTlsFeatures);
      if (!incomprehensible.isEmpty()) {
        return HandshakerSocketFactoryResult.error(
            "TLS features not understood: " + incomprehensible);
      }
      KeyManager[] km = null;
      if (tlsCreds.getKeyManagers() != null) {
        km = tlsCreds.getKeyManagers().toArray(new KeyManager[0]);
      } else if (tlsCreds.getPrivateKey() != null) {
        if (tlsCreds.getPrivateKeyPassword() != null) {
          return HandshakerSocketFactoryResult.error("byte[]-based private key with password "
              + "unsupported. Use unencrypted file or KeyManager");
        }
        try {
          km = OkHttpChannelBuilder.createKeyManager(
              tlsCreds.getCertificateChain(), tlsCreds.getPrivateKey());
        } catch (GeneralSecurityException gse) {
          log.log(Level.FINE, "Exception loading private key from credential", gse);
          return HandshakerSocketFactoryResult.error(
              "Unable to load private key: " + gse.getMessage());
        }
      } // else don't have a client cert
      TrustManager[] tm = null;
      if (tlsCreds.getTrustManagers() != null) {
        tm = tlsCreds.getTrustManagers().toArray(new TrustManager[0]);
      } else if (tlsCreds.getRootCertificates() != null) {
        try {
          tm = OkHttpChannelBuilder.createTrustManager(tlsCreds.getRootCertificates());
        } catch (GeneralSecurityException gse) {
          log.log(Level.FINE, "Exception loading root certificates from credential", gse);
          return HandshakerSocketFactoryResult.error(
              "Unable to load root certificates: " + gse.getMessage());
        }
      } // else use system default
      SSLContext sslContext;
      try {
        sslContext = SSLContext.getInstance("TLS", Platform.get().getProvider());
        sslContext.init(km, tm, null);
      } catch (GeneralSecurityException gse) {
        throw new RuntimeException("TLS Provider failure", gse);
      }
      SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
      switch (tlsCreds.getClientAuth()) {
        case OPTIONAL:
          sslSocketFactory = new ClientCertRequestingSocketFactory(sslSocketFactory, false);
          break;

        case REQUIRE:
          sslSocketFactory = new ClientCertRequestingSocketFactory(sslSocketFactory, true);
          break;

        case NONE:
          // NOOP; this is the SSLContext default
          break;

        default:
          return HandshakerSocketFactoryResult.error(
              "Unknown TlsServerCredentials.ClientAuth value: " + tlsCreds.getClientAuth());
      }
      return HandshakerSocketFactoryResult.factory(new TlsServerHandshakerSocketFactory(
          new SslSocketFactoryServerCredentials.ServerCredentials(sslSocketFactory)));

    } else if (creds instanceof InsecureServerCredentials) {
      return HandshakerSocketFactoryResult.factory(new PlaintextHandshakerSocketFactory());

    } else if (creds instanceof SslSocketFactoryServerCredentials.ServerCredentials) {
      SslSocketFactoryServerCredentials.ServerCredentials factoryCreds =
          (SslSocketFactoryServerCredentials.ServerCredentials) creds;
      return HandshakerSocketFactoryResult.factory(
          new TlsServerHandshakerSocketFactory(factoryCreds));

    } else if (creds instanceof ChoiceServerCredentials) {
      ChoiceServerCredentials choiceCreds = (ChoiceServerCredentials) creds;
      StringBuilder error = new StringBuilder();
      for (ServerCredentials innerCreds : choiceCreds.getCredentialsList()) {
        HandshakerSocketFactoryResult result = handshakerSocketFactoryFrom(innerCreds);
        if (result.error == null) {
          return result;
        }
        error.append(", ");
        error.append(result.error);
      }
      return HandshakerSocketFactoryResult.error(error.substring(2));

    } else {
      return HandshakerSocketFactoryResult.error(
          "Unsupported credential type: " + creds.getClass().getName());
    }
  }

  static final class HandshakerSocketFactoryResult {
    public final HandshakerSocketFactory factory;
    public final String error;

    private HandshakerSocketFactoryResult(HandshakerSocketFactory factory, String error) {
      this.factory = factory;
      this.error = error;
    }

    public static HandshakerSocketFactoryResult error(String error) {
      return new HandshakerSocketFactoryResult(
          null, Preconditions.checkNotNull(error, "error"));
    }

    public static HandshakerSocketFactoryResult factory(HandshakerSocketFactory factory) {
      return new HandshakerSocketFactoryResult(
          Preconditions.checkNotNull(factory, "factory"), null);
    }
  }

  static final class ClientCertRequestingSocketFactory extends SSLSocketFactory {
    private final SSLSocketFactory socketFactory;
    private final boolean required;

    public ClientCertRequestingSocketFactory(SSLSocketFactory socketFactory, boolean required) {
      this.socketFactory = Preconditions.checkNotNull(socketFactory, "socketFactory");
      this.required = required;
    }

    private Socket apply(Socket s) throws IOException {
      if (!(s instanceof SSLSocket)) {
        throw new IOException(
            "SocketFactory " + socketFactory + " did not produce an SSLSocket: " + s.getClass());
      }
      SSLSocket sslSocket = (SSLSocket) s;
      if (required) {
        sslSocket.setNeedClientAuth(true);
      } else {
        sslSocket.setWantClientAuth(true);
      }
      return sslSocket;
    }

    @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose)
        throws IOException {
      return apply(socketFactory.createSocket(s, host, port, autoClose));
    }

    @Override public Socket createSocket(String host, int port) throws IOException {
      return apply(socketFactory.createSocket(host, port));
    }

    @Override public Socket createSocket(
        String host, int port, InetAddress localHost, int localPort) throws IOException {
      return apply(socketFactory.createSocket(host, port, localHost, localPort));
    }

    @Override public Socket createSocket(InetAddress host, int port) throws IOException {
      return apply(socketFactory.createSocket(host, port));
    }

    @Override public Socket createSocket(
        InetAddress host, int port, InetAddress localAddress, int localPort) throws IOException {
      return apply(socketFactory.createSocket(host, port, localAddress, localPort));
    }

    @Override public String[] getDefaultCipherSuites() {
      return socketFactory.getDefaultCipherSuites();
    }

    @Override public String[] getSupportedCipherSuites() {
      return socketFactory.getSupportedCipherSuites();
    }
  }
}
