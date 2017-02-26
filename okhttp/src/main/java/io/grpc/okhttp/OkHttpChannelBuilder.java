/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.okhttp;

import static io.grpc.internal.GrpcUtil.DEFAULT_KEEPALIVE_DELAY_NANOS;
import static io.grpc.internal.GrpcUtil.DEFAULT_KEEPALIVE_TIMEOUT_NANOS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.squareup.okhttp.CipherSuite;
import com.squareup.okhttp.ConnectionSpec;
import com.squareup.okhttp.TlsVersion;
import io.grpc.Attributes;
import io.grpc.ExperimentalApi;
import io.grpc.Internal;
import io.grpc.NameResolver;
import io.grpc.internal.AbstractManagedChannelImplBuilder;
import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.ConnectionClientTransport;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.internal.SharedResourceHolder.Resource;
import io.grpc.okhttp.internal.Platform;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.GeneralSecurityException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/** Convenience class for building channels with the OkHttp transport. */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1785")
public class OkHttpChannelBuilder extends
        AbstractManagedChannelImplBuilder<OkHttpChannelBuilder> {

  public static final ConnectionSpec DEFAULT_CONNECTION_SPEC =
      new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
          .cipherSuites(
              // The following items should be sync with Netty's Http2SecurityUtil.CIPHERS.
              CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
              CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
              CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
              CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
              CipherSuite.TLS_DHE_RSA_WITH_AES_128_GCM_SHA256,
              CipherSuite.TLS_DHE_DSS_WITH_AES_128_GCM_SHA256,
              CipherSuite.TLS_DHE_RSA_WITH_AES_256_GCM_SHA384,
              CipherSuite.TLS_DHE_DSS_WITH_AES_256_GCM_SHA384)
          .tlsVersions(TlsVersion.TLS_1_2)
          .supportsTlsExtensions(true)
          .build();

  private static final Resource<ExecutorService> SHARED_EXECUTOR =
      new Resource<ExecutorService>() {
        @Override
        public ExecutorService create() {
          return Executors.newCachedThreadPool(GrpcUtil.getThreadFactory("grpc-okhttp-%d", true));
        }

        @Override
        public void close(ExecutorService executor) {
          executor.shutdown();
        }
      };

  /** Creates a new builder for the given server host and port. */
  public static OkHttpChannelBuilder forAddress(String host, int port) {
    return new OkHttpChannelBuilder(host, port);
  }

  /**
   * Creates a new builder for the given target that will be resolved by
   * {@link io.grpc.NameResolver}.
   */
  public static OkHttpChannelBuilder forTarget(String target) {
    return new OkHttpChannelBuilder(target);
  }

  private Executor transportExecutor;

  private SSLSocketFactory sslSocketFactory;
  private ConnectionSpec connectionSpec = DEFAULT_CONNECTION_SPEC;
  private NegotiationType negotiationType = NegotiationType.TLS;
  private boolean enableKeepAlive;
  private long keepAliveDelayNanos;
  private long keepAliveTimeoutNanos;

  protected OkHttpChannelBuilder(String host, int port) {
    this(GrpcUtil.authorityFromHostAndPort(host, port));
  }

  private OkHttpChannelBuilder(String target) {
    super(target);
  }

  /**
   * Override the default executor necessary for internal transport use.
   *
   * <p>The channel does not take ownership of the given executor. It is the caller' responsibility
   * to shutdown the executor when appropriate.
   */
  public final OkHttpChannelBuilder transportExecutor(@Nullable Executor transportExecutor) {
    this.transportExecutor = transportExecutor;
    return this;
  }

  /**
   * Sets the negotiation type for the HTTP/2 connection.
   *
   * <p>If TLS is enabled a default {@link SSLSocketFactory} is created using the best
   * {@link java.security.Provider} available and is NOT based on
   * {@link SSLSocketFactory#getDefault}. To more precisely control the TLS configuration call
   * {@link #sslSocketFactory} to override the socket factory used.
   *
   * <p>Default: <code>TLS</code>
   */
  public final OkHttpChannelBuilder negotiationType(NegotiationType type) {
    negotiationType = Preconditions.checkNotNull(type, "type");
    return this;
  }

  /**
   * Enable keepalive with default delay and timeout.
   */
  public final OkHttpChannelBuilder enableKeepAlive(boolean enable) {
    enableKeepAlive = enable;
    if (enable) {
      keepAliveDelayNanos = DEFAULT_KEEPALIVE_DELAY_NANOS;
      keepAliveTimeoutNanos = DEFAULT_KEEPALIVE_TIMEOUT_NANOS;
    }
    return this;
  }

  /**
   * Enable keepalive with custom delay and timeout.
   */
  public final OkHttpChannelBuilder enableKeepAlive(boolean enable, long keepAliveDelay,
      TimeUnit delayUnit, long keepAliveTimeout, TimeUnit timeoutUnit) {
    enableKeepAlive = enable;
    if (enable) {
      keepAliveDelayNanos = delayUnit.toNanos(keepAliveDelay);
      keepAliveTimeoutNanos = timeoutUnit.toNanos(keepAliveTimeout);
    }
    return this;
  }

  /**
   * Override the default {@link SSLSocketFactory} and enable {@link NegotiationType#TLS}
   * negotiation.
   *
   * <p>By default, when TLS is enabled, <code>SSLSocketFactory.getDefault()</code> will be used.
   *
   * <p>{@link NegotiationType#TLS} will be applied by calling this method.
   */
  public final OkHttpChannelBuilder sslSocketFactory(SSLSocketFactory factory) {
    this.sslSocketFactory = factory;
    negotiationType(NegotiationType.TLS);
    return this;
  }

  /**
   * For secure connection, provides a ConnectionSpec to specify Cipher suite and
   * TLS versions.
   *
   * <p>By default {@link #DEFAULT_CONNECTION_SPEC} will be used.
   *
   * <p>This method is only used when building a secure connection. For plaintext
   * connection, use {@link #usePlaintext} instead.
   *
   * @throws IllegalArgumentException
   *         If {@code connectionSpec} is not with TLS
   */
  public final OkHttpChannelBuilder connectionSpec(ConnectionSpec connectionSpec) {
    Preconditions.checkArgument(connectionSpec.isTls(), "plaintext ConnectionSpec is not accepted");
    this.connectionSpec = connectionSpec;
    return this;
  }

  /**
   * Equivalent to using {@link #negotiationType(NegotiationType)} with {@code PLAINTEXT}.
   */
  @Override
  public final OkHttpChannelBuilder usePlaintext(boolean skipNegotiation) {
    if (skipNegotiation) {
      negotiationType(NegotiationType.PLAINTEXT);
    } else {
      throw new IllegalArgumentException("Plaintext negotiation not currently supported");
    }
    return this;
  }

  @Override
  protected final ClientTransportFactory buildTransportFactory() {
    return new OkHttpTransportFactory(transportExecutor,
        createSocketFactory(), connectionSpec, maxInboundMessageSize(), enableKeepAlive,
        keepAliveDelayNanos, keepAliveTimeoutNanos);
  }

  @Override
  protected Attributes getNameResolverParams() {
    int defaultPort;
    switch (negotiationType) {
      case PLAINTEXT:
        defaultPort = GrpcUtil.DEFAULT_PORT_PLAINTEXT;
        break;
      case TLS:
        defaultPort = GrpcUtil.DEFAULT_PORT_SSL;
        break;
      default:
        throw new AssertionError(negotiationType + " not handled");
    }
    return Attributes.newBuilder()
        .set(NameResolver.Factory.PARAMS_DEFAULT_PORT, defaultPort).build();
  }

  @VisibleForTesting
  @Nullable
  SSLSocketFactory createSocketFactory() {
    switch (negotiationType) {
      case TLS:
        try {
          if (sslSocketFactory == null) {
            SSLContext sslContext = SSLContext.getInstance("Default", Platform.get().getProvider());
            sslSocketFactory = sslContext.getSocketFactory();
          }
          return sslSocketFactory;
        } catch (GeneralSecurityException gse) {
          throw new RuntimeException("TLS Provider failure", gse);
        }
      case PLAINTEXT:
        return null;
      default:
        throw new RuntimeException("Unknown negotiation type: " + negotiationType);
    }
  }

  /**
   * Creates OkHttp transports. Exposed for internal use, as it should be private.
   */
  @Internal
  static final class OkHttpTransportFactory implements ClientTransportFactory {
    private final Executor executor;
    private final boolean usingSharedExecutor;
    @Nullable
    private final SSLSocketFactory socketFactory;
    private final ConnectionSpec connectionSpec;
    private final int maxMessageSize;
    private boolean enableKeepAlive;
    private long keepAliveDelayNanos;
    private long keepAliveTimeoutNanos;
    private boolean closed;

    private OkHttpTransportFactory(Executor executor,
        @Nullable SSLSocketFactory socketFactory,
        ConnectionSpec connectionSpec,
        int maxMessageSize,
        boolean enableKeepAlive,
        long keepAliveDelayNanos,
        long keepAliveTimeoutNanos) {
      this.socketFactory = socketFactory;
      this.connectionSpec = connectionSpec;
      this.maxMessageSize = maxMessageSize;
      this.enableKeepAlive = enableKeepAlive;
      this.keepAliveDelayNanos = keepAliveDelayNanos;
      this.keepAliveTimeoutNanos = keepAliveTimeoutNanos;

      usingSharedExecutor = executor == null;
      if (usingSharedExecutor) {
        // The executor was unspecified, using the shared executor.
        this.executor = SharedResourceHolder.get(SHARED_EXECUTOR);
      } else {
        this.executor = executor;
      }
    }

    @Override
    public ConnectionClientTransport newClientTransport(
        SocketAddress addr, String authority, @Nullable String userAgent) {
      if (closed) {
        throw new IllegalStateException("The transport factory is closed.");
      }
      InetSocketAddress proxyAddress = null;
      String proxy = System.getenv("GRPC_PROXY_EXP");
      if (proxy != null) {
        String[] parts = proxy.split(":", 2);
        int port = 80;
        if (parts.length > 1) {
          port = Integer.parseInt(parts[1]);
        }
        proxyAddress = new InetSocketAddress(parts[0], port);
      }
      InetSocketAddress inetSocketAddr = (InetSocketAddress) addr;
      OkHttpClientTransport transport = new OkHttpClientTransport(inetSocketAddr, authority,
          userAgent, executor, socketFactory, Utils.convertSpec(connectionSpec), maxMessageSize,
          proxyAddress, null, null);
      if (enableKeepAlive) {
        transport.enableKeepAlive(true, keepAliveDelayNanos, keepAliveTimeoutNanos);
      }
      return transport;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;

      if (usingSharedExecutor) {
        SharedResourceHolder.release(SHARED_EXECUTOR, (ExecutorService) executor);
      }
    }
  }
}
