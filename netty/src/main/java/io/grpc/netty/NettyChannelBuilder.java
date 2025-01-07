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
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.internal.GrpcUtil.DEFAULT_KEEPALIVE_TIMEOUT_NANOS;
import static io.grpc.internal.GrpcUtil.KEEPALIVE_TIME_NANOS_DISABLED;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.InlineMe;
import io.grpc.Attributes;
import io.grpc.CallCredentials;
import io.grpc.ChannelCredentials;
import io.grpc.ChannelLogger;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ExperimentalApi;
import io.grpc.ForwardingChannelBuilder2;
import io.grpc.HttpConnectProxiedSocketAddress;
import io.grpc.Internal;
import io.grpc.ManagedChannelBuilder;
import io.grpc.internal.AtomicBackoff;
import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.ConnectionClientTransport;
import io.grpc.internal.FixedObjectPool;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.KeepAliveManager;
import io.grpc.internal.ManagedChannelImplBuilder;
import io.grpc.internal.ManagedChannelImplBuilder.ChannelBuilderDefaultPortProvider;
import io.grpc.internal.ManagedChannelImplBuilder.ClientTransportFactoryBuilder;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourcePool;
import io.grpc.internal.TransportTracer;
import io.grpc.netty.ProtocolNegotiators.FromChannelCredentialsResult;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;

/**
 * A builder to help simplify construction of channels using the Netty transport.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1784")
@CheckReturnValue
public final class NettyChannelBuilder extends ForwardingChannelBuilder2<NettyChannelBuilder> {

  // 1MiB.
  public static final int DEFAULT_FLOW_CONTROL_WINDOW = 1024 * 1024;
  private static final boolean DEFAULT_AUTO_FLOW_CONTROL;

  private static final long AS_LARGE_AS_INFINITE = TimeUnit.DAYS.toNanos(1000L);

  private static final ChannelFactory<? extends Channel> DEFAULT_CHANNEL_FACTORY =
      new ReflectiveChannelFactory<>(Utils.DEFAULT_CLIENT_CHANNEL_TYPE);
  private static final ObjectPool<? extends EventLoopGroup> DEFAULT_EVENT_LOOP_GROUP_POOL =
      SharedResourcePool.forResource(Utils.DEFAULT_WORKER_EVENT_LOOP_GROUP);

  static {
    String autoFlowControl = System.getenv("GRPC_EXPERIMENTAL_AUTOFLOWCONTROL");
    if (autoFlowControl == null) {
      autoFlowControl = "true";
    }
    DEFAULT_AUTO_FLOW_CONTROL = Boolean.parseBoolean(autoFlowControl);
  }

  private final ManagedChannelImplBuilder managedChannelImplBuilder;
  private TransportTracer.Factory transportTracerFactory = TransportTracer.getDefaultFactory();
  private final Map<ChannelOption<?>, Object> channelOptions = new HashMap<>();
  private ChannelFactory<? extends Channel> channelFactory = DEFAULT_CHANNEL_FACTORY;
  private ObjectPool<? extends EventLoopGroup> eventLoopGroupPool = DEFAULT_EVENT_LOOP_GROUP_POOL;
  private boolean autoFlowControl = DEFAULT_AUTO_FLOW_CONTROL;
  private int flowControlWindow = DEFAULT_FLOW_CONTROL_WINDOW;
  private int maxHeaderListSize = GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE;
  private int softLimitHeaderListSize = GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE;
  private int maxInboundMessageSize = GrpcUtil.DEFAULT_MAX_MESSAGE_SIZE;
  private long keepAliveTimeNanos = KEEPALIVE_TIME_NANOS_DISABLED;
  private long keepAliveTimeoutNanos = DEFAULT_KEEPALIVE_TIMEOUT_NANOS;
  private boolean keepAliveWithoutCalls;
  private ProtocolNegotiator.ClientFactory protocolNegotiatorFactory
      = new DefaultProtocolNegotiator();
  private final boolean freezeProtocolNegotiatorFactory;
  private LocalSocketPicker localSocketPicker;

  /**
   * If true, indicates that the transport may use the GET method for RPCs, and may include the
   * request body in the query params.
   */
  private final boolean useGetForSafeMethods = false;

  private Class<? extends SocketAddress> transportSocketType = InetSocketAddress.class;

  /**
   * Creates a new builder with the given server address. This factory method is primarily intended
   * for using Netty Channel types other than SocketChannel. {@link #forAddress(String, int)} should
   * generally be preferred over this method, since that API permits delaying DNS lookups and
   * noticing changes to DNS. If an unresolved InetSocketAddress is passed in, then it will remain
   * unresolved.
   */
  public static NettyChannelBuilder forAddress(SocketAddress serverAddress) {
    return new NettyChannelBuilder(serverAddress);
  }

  /**
   * Creates a new builder with the given server address. This factory method is primarily intended
   * for using Netty Channel types other than SocketChannel.
   * {@link #forAddress(String, int, ChannelCredentials)} should generally be preferred over this
   * method, since that API permits delaying DNS lookups and noticing changes to DNS. If an
   * unresolved InetSocketAddress is passed in, then it will remain unresolved.
   */
  public static NettyChannelBuilder forAddress(SocketAddress serverAddress,
      ChannelCredentials creds) {
    FromChannelCredentialsResult result = ProtocolNegotiators.from(creds);
    if (result.error != null) {
      throw new IllegalArgumentException(result.error);
    }
    return new NettyChannelBuilder(serverAddress, creds, result.callCredentials, result.negotiator);
  }

  /**
   * Creates a new builder with the given host and port.
   */
  public static NettyChannelBuilder forAddress(String host, int port) {
    return forTarget(GrpcUtil.authorityFromHostAndPort(host, port));
  }

  /**
   * Creates a new builder with the given host and port.
   */
  public static NettyChannelBuilder forAddress(String host, int port, ChannelCredentials creds) {
    return forTarget(GrpcUtil.authorityFromHostAndPort(host, port), creds);
  }

  /**
   * Creates a new builder with the given target string that will be resolved by
   * {@link io.grpc.NameResolver}.
   */
  public static NettyChannelBuilder forTarget(String target) {
    return new NettyChannelBuilder(target);
  }

  /**
   * Creates a new builder with the given target string that will be resolved by
   * {@link io.grpc.NameResolver}.
   */
  public static NettyChannelBuilder forTarget(String target, ChannelCredentials creds) {
    FromChannelCredentialsResult result = ProtocolNegotiators.from(creds);
    if (result.error != null) {
      throw new IllegalArgumentException(result.error);
    }
    return new NettyChannelBuilder(target, creds, result.callCredentials, result.negotiator);
  }

  private final class NettyChannelTransportFactoryBuilder implements ClientTransportFactoryBuilder {
    @Override
    public ClientTransportFactory buildClientTransportFactory() {
      return buildTransportFactory();
    }
  }

  private final class NettyChannelDefaultPortProvider implements ChannelBuilderDefaultPortProvider {
    @Override
    public int getDefaultPort() {
      return protocolNegotiatorFactory.getDefaultPort();
    }
  }

  NettyChannelBuilder(String target) {
    managedChannelImplBuilder = new ManagedChannelImplBuilder(target,
        new NettyChannelTransportFactoryBuilder(),
        new NettyChannelDefaultPortProvider());
    this.freezeProtocolNegotiatorFactory = false;
  }

  NettyChannelBuilder(
      String target, ChannelCredentials channelCreds, CallCredentials callCreds,
      ProtocolNegotiator.ClientFactory negotiator) {
    managedChannelImplBuilder = new ManagedChannelImplBuilder(
        target, channelCreds, callCreds,
        new NettyChannelTransportFactoryBuilder(),
        new NettyChannelDefaultPortProvider());
    this.protocolNegotiatorFactory = checkNotNull(negotiator, "negotiator");
    this.freezeProtocolNegotiatorFactory = true;
  }

  NettyChannelBuilder(SocketAddress address) {
    managedChannelImplBuilder = new ManagedChannelImplBuilder(address,
        getAuthorityFromAddress(address),
        new NettyChannelTransportFactoryBuilder(),
        new NettyChannelDefaultPortProvider());
    this.freezeProtocolNegotiatorFactory = false;
  }

  NettyChannelBuilder(
      SocketAddress address, ChannelCredentials channelCreds, CallCredentials callCreds,
      ProtocolNegotiator.ClientFactory negotiator) {
    managedChannelImplBuilder = new ManagedChannelImplBuilder(address,
        getAuthorityFromAddress(address),
        channelCreds, callCreds,
        new NettyChannelTransportFactoryBuilder(),
        new NettyChannelDefaultPortProvider());
    this.protocolNegotiatorFactory = checkNotNull(negotiator, "negotiator");
    this.freezeProtocolNegotiatorFactory = true;
  }

  @Internal
  @Override
  protected ManagedChannelBuilder<?> delegate() {
    return managedChannelImplBuilder;
  }

  private static String getAuthorityFromAddress(SocketAddress address) {
    if (address instanceof InetSocketAddress) {
      InetSocketAddress inetAddress = (InetSocketAddress) address;
      return GrpcUtil.authorityFromHostAndPort(inetAddress.getHostString(), inetAddress.getPort());
    } else {
      return address.toString();
    }
  }

  /**
   * Specifies the channel type to use, by default we use {@code EpollSocketChannel} if available,
   * otherwise using {@link NioSocketChannel}.
   *
   * <p>You either use this or {@link #channelFactory(io.netty.channel.ChannelFactory)} if your
   * {@link Channel} implementation has no no-args constructor.
   *
   * <p>It's an optional parameter. If the user has not provided an Channel type or ChannelFactory
   * when the channel is built, the builder will use the default one which is static.
   *
   * <p>You must also provide corresponding {@link #eventLoopGroup(EventLoopGroup)}. For example,
   * {@link NioSocketChannel} must use {@link io.netty.channel.nio.NioEventLoopGroup}, otherwise
   * your application won't start.
   */
  @CanIgnoreReturnValue
  public NettyChannelBuilder channelType(Class<? extends Channel> channelType) {
    return channelType(channelType, null);
  }

  /**
   * Similar to {@link #channelType(Class)} above but allows the
   * caller to specify the socket-type associated with the channelType.
   *
   * @param channelType the type of {@link Channel} to use.
   * @param transportSocketType the associated {@link SocketAddress} type. If {@code null}, then
   *     no compatibility check is performed between channel transport and name-resolver addresses.
   */
  @CanIgnoreReturnValue
  public NettyChannelBuilder channelType(Class<? extends Channel> channelType,
      @Nullable Class<? extends SocketAddress> transportSocketType) {
    checkNotNull(channelType, "channelType");
    return channelFactory(new ReflectiveChannelFactory<>(channelType),
        transportSocketType);
  }

  /**
   * Specifies the {@link ChannelFactory} to create {@link Channel} instances. This method is
   * usually only used if the specific {@code Channel} requires complex logic which requires
   * additional information to create the {@code Channel}. Otherwise, recommend to use {@link
   * #channelType(Class)}.
   *
   * <p>It's an optional parameter. If the user has not provided an Channel type or ChannelFactory
   * when the channel is built, the builder will use the default one which is static.
   *
   * <p>You must also provide corresponding {@link #eventLoopGroup(EventLoopGroup)}. For example,
   * {@link NioSocketChannel} based {@link ChannelFactory} must use {@link
   * io.netty.channel.nio.NioEventLoopGroup}, otherwise your application won't start.
   */
  @CanIgnoreReturnValue
  public NettyChannelBuilder channelFactory(ChannelFactory<? extends Channel> channelFactory) {
    return channelFactory(channelFactory, null);
  }

  /**
   * Similar to {@link #channelFactory(ChannelFactory)} above but allows the
   * caller to specify the socket-type associated with the channelFactory.
   *
   * @param channelFactory the {@link ChannelFactory} to use.
   * @param transportSocketType the associated {@link SocketAddress} type. If {@code null}, then
   *     no compatibility check is performed between channel transport and name-resolver addresses.
   */
  @CanIgnoreReturnValue
  public NettyChannelBuilder channelFactory(ChannelFactory<? extends Channel> channelFactory,
      @Nullable Class<? extends SocketAddress> transportSocketType) {
    this.channelFactory = checkNotNull(channelFactory, "channelFactory");
    this.transportSocketType = transportSocketType;
    return this;
  }

  /**
   * Specifies a channel option. As the underlying channel as well as network implementation may
   * ignore this value applications should consider it a hint.
   */
  @CanIgnoreReturnValue
  public <T> NettyChannelBuilder withOption(ChannelOption<T> option, T value) {
    channelOptions.put(option, value);
    return this;
  }

  /**
   * Sets the negotiation type for the HTTP/2 connection.
   *
   * <p>Default: <code>TLS</code>
   */
  @CanIgnoreReturnValue
  public NettyChannelBuilder negotiationType(NegotiationType type) {
    checkState(!freezeProtocolNegotiatorFactory,
               "Cannot change security when using ChannelCredentials");
    if (!(protocolNegotiatorFactory instanceof DefaultProtocolNegotiator)) {
      // Do nothing for compatibility
      return this;
    }
    ((DefaultProtocolNegotiator) protocolNegotiatorFactory).negotiationType = type;
    return this;
  }

  /**
   * Provides an EventGroupLoop to be used by the netty transport.
   *
   * <p>It's an optional parameter. If the user has not provided an EventGroupLoop when the channel
   * is built, the builder will use the default one which is static.
   *
   * <p>You must also provide corresponding {@link #channelType(Class)} or {@link
   * #channelFactory(ChannelFactory)} corresponding to the given {@code EventLoopGroup}. For
   * example, {@link io.netty.channel.nio.NioEventLoopGroup} requires {@link NioSocketChannel}
   *
   * <p>The channel won't take ownership of the given EventLoopGroup. It's caller's responsibility
   * to shut it down when it's desired.
   */
  @CanIgnoreReturnValue
  public NettyChannelBuilder eventLoopGroup(@Nullable EventLoopGroup eventLoopGroup) {
    if (eventLoopGroup != null) {
      return eventLoopGroupPool(new FixedObjectPool<>(eventLoopGroup));
    }
    return eventLoopGroupPool(DEFAULT_EVENT_LOOP_GROUP_POOL);
  }

  @CanIgnoreReturnValue
  NettyChannelBuilder eventLoopGroupPool(ObjectPool<? extends EventLoopGroup> eventLoopGroupPool) {
    this.eventLoopGroupPool = checkNotNull(eventLoopGroupPool, "eventLoopGroupPool");
    return this;
  }

  /**
   * SSL/TLS context to use instead of the system default. It must have been configured with {@link
   * GrpcSslContexts}, but options could have been overridden.
   */
  @CanIgnoreReturnValue
  public NettyChannelBuilder sslContext(SslContext sslContext) {
    checkState(!freezeProtocolNegotiatorFactory,
               "Cannot change security when using ChannelCredentials");
    if (sslContext != null) {
      checkArgument(sslContext.isClient(),
          "Server SSL context can not be used for client channel");
      GrpcSslContexts.ensureAlpnAndH2Enabled(sslContext.applicationProtocolNegotiator());
    }
    if (!(protocolNegotiatorFactory instanceof DefaultProtocolNegotiator)) {
      // Do nothing for compatibility
      return this;
    }
    ((DefaultProtocolNegotiator) protocolNegotiatorFactory).sslContext = sslContext;
    return this;
  }

  /**
   * Sets the initial flow control window in bytes. Setting initial flow control window enables auto
   * flow control tuning using bandwidth-delay product algorithm. To disable auto flow control
   * tuning, use {@link #flowControlWindow(int)}. By default, auto flow control is enabled with
   * initial flow control window size of {@link #DEFAULT_FLOW_CONTROL_WINDOW}.
   */
  @CanIgnoreReturnValue
  public NettyChannelBuilder initialFlowControlWindow(int initialFlowControlWindow) {
    checkArgument(initialFlowControlWindow > 0, "initialFlowControlWindow must be positive");
    this.flowControlWindow = initialFlowControlWindow;
    this.autoFlowControl = true;
    return this;
  }

  /**
   * Sets the flow control window in bytes. Setting flowControlWindow disables auto flow control
   * tuning; use {@link #initialFlowControlWindow(int)} to enable auto flow control tuning. If not
   * called, the default value is {@link #DEFAULT_FLOW_CONTROL_WINDOW}) with auto flow control
   * tuning.
   */
  @CanIgnoreReturnValue
  public NettyChannelBuilder flowControlWindow(int flowControlWindow) {
    checkArgument(flowControlWindow > 0, "flowControlWindow must be positive");
    this.flowControlWindow = flowControlWindow;
    this.autoFlowControl = false;
    return this;
  }

  /**
   * Sets the maximum size of header list allowed to be received. This is cumulative size of the
   * headers with some overhead, as defined for
   * <a href="http://httpwg.org/specs/rfc7540.html#rfc.section.6.5.2">
   * HTTP/2's SETTINGS_MAX_HEADER_LIST_SIZE</a>. The default is 8 KiB.
   *
   * @deprecated Use {@link #maxInboundMetadataSize} instead
   */
  @CanIgnoreReturnValue
  @Deprecated
  @InlineMe(replacement = "this.maxInboundMetadataSize(maxHeaderListSize)")
  public NettyChannelBuilder maxHeaderListSize(int maxHeaderListSize) {
    return maxInboundMetadataSize(maxHeaderListSize);
  }

  /**
   * Sets the maximum size of metadata allowed to be received. This is cumulative size of the
   * entries with some overhead, as defined for
   * <a href="http://httpwg.org/specs/rfc7540.html#rfc.section.6.5.2">
   * HTTP/2's SETTINGS_MAX_HEADER_LIST_SIZE</a>. The default is 8 KiB.
   *
   * @param bytes the maximum size of received metadata
   * @return this
   * @throws IllegalArgumentException if bytes is non-positive
   * @since 1.17.0
   */
  @CanIgnoreReturnValue
  @Override
  public NettyChannelBuilder maxInboundMetadataSize(int bytes) {
    checkArgument(bytes > 0, "maxInboundMetadataSize must be > 0");
    this.maxHeaderListSize = bytes;
    // Clear the soft limit setting, by setting soft limit to maxInboundMetadataSize. The
    // maxInboundMetadataSize will take precedence be applied before soft limit check.
    this.softLimitHeaderListSize = bytes;
    return this;
  }

  /**
   * Sets the size of metadata that clients are advised to not exceed. When a metadata with size
   * larger than the soft limit is encountered there will be a probability the RPC will fail. The
   * chance of failing increases as the metadata size approaches the hard limit.
   * {@code Integer.MAX_VALUE} disables the enforcement. The default is implementation-dependent,
   * but is not generally less than 8 KiB and may be unlimited.
   *
   * <p>This is cumulative size of the metadata. The precise calculation is
   * implementation-dependent, but implementations are encouraged to follow the calculation used
   * for
   * <a href="http://httpwg.org/specs/rfc7540.html#rfc.section.6.5.2">HTTP/2's
   * SETTINGS_MAX_HEADER_LIST_SIZE</a>. It sums the bytes from each entry's key and value, plus 32
   * bytes of overhead per entry.
   *
   * @param soft the soft size limit of received metadata
   * @param max the hard size limit of received metadata
   * @return this
   * @throws IllegalArgumentException if soft and/or max is non-positive, or max smaller than
   *     soft
   * @since 1.68.0
   */
  @CanIgnoreReturnValue
  public NettyChannelBuilder maxInboundMetadataSize(int soft, int max) {
    checkArgument(soft > 0, "softLimitHeaderListSize must be > 0");
    checkArgument(max > soft,
        "maxInboundMetadataSize must be greater than softLimitHeaderListSize");
    this.softLimitHeaderListSize = soft;
    this.maxHeaderListSize = max;
    return this;
  }

  /**
   * Equivalent to using {@link #negotiationType(NegotiationType)} with {@code PLAINTEXT}.
   */
  @CanIgnoreReturnValue
  @Override
  public NettyChannelBuilder usePlaintext() {
    negotiationType(NegotiationType.PLAINTEXT);
    return this;
  }

  /**
   * Equivalent to using {@link #negotiationType(NegotiationType)} with {@code TLS}.
   */
  @CanIgnoreReturnValue
  @Override
  public NettyChannelBuilder useTransportSecurity() {
    negotiationType(NegotiationType.TLS);
    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @since 1.3.0
   */
  @CanIgnoreReturnValue
  @Override
  public NettyChannelBuilder keepAliveTime(long keepAliveTime, TimeUnit timeUnit) {
    checkArgument(keepAliveTime > 0L, "keepalive time must be positive");
    keepAliveTimeNanos = timeUnit.toNanos(keepAliveTime);
    keepAliveTimeNanos = KeepAliveManager.clampKeepAliveTimeInNanos(keepAliveTimeNanos);
    if (keepAliveTimeNanos >= AS_LARGE_AS_INFINITE) {
      // Bump keepalive time to infinite. This disables keepalive.
      keepAliveTimeNanos = KEEPALIVE_TIME_NANOS_DISABLED;
    }
    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @since 1.3.0
   */
  @CanIgnoreReturnValue
  @Override
  public NettyChannelBuilder keepAliveTimeout(long keepAliveTimeout, TimeUnit timeUnit) {
    checkArgument(keepAliveTimeout > 0L, "keepalive timeout must be positive");
    keepAliveTimeoutNanos = timeUnit.toNanos(keepAliveTimeout);
    keepAliveTimeoutNanos = KeepAliveManager.clampKeepAliveTimeoutInNanos(keepAliveTimeoutNanos);
    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @since 1.3.0
   */
  @CanIgnoreReturnValue
  @Override
  public NettyChannelBuilder keepAliveWithoutCalls(boolean enable) {
    keepAliveWithoutCalls = enable;
    return this;
  }


  /**
   * If non-{@code null}, attempts to create connections bound to a local port.
   */
  @CanIgnoreReturnValue
  public NettyChannelBuilder localSocketPicker(@Nullable LocalSocketPicker localSocketPicker) {
    this.localSocketPicker = localSocketPicker;
    return this;
  }

  /**
   * This class is meant to be overriden with a custom implementation of
   * {@link #createSocketAddress}.  The default implementation is a no-op.
   *
   * @since 1.16.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/4917")
  public static class LocalSocketPicker {

    /**
     * Called by gRPC to pick local socket to bind to.  This may be called multiple times.
     * Subclasses are expected to override this method.
     *
     * @param remoteAddress the remote address to connect to.
     * @param attrs the Attributes present on the {@link io.grpc.EquivalentAddressGroup} associated
     *        with the address.
     * @return a {@link SocketAddress} suitable for binding, or else {@code null}.
     * @since 1.16.0
     */
    @Nullable
    public SocketAddress createSocketAddress(
        SocketAddress remoteAddress, @EquivalentAddressGroup.Attr Attributes attrs) {
      return null;
    }
  }

  /**
   * Sets the maximum message size allowed for a single gRPC frame. If an inbound messages larger
   * than this limit is received it will not be processed and the RPC will fail with
   * RESOURCE_EXHAUSTED.
   */
  @CanIgnoreReturnValue
  @Override
  public NettyChannelBuilder maxInboundMessageSize(int max) {
    checkArgument(max >= 0, "negative max");
    maxInboundMessageSize = max;
    return this;
  }

  ClientTransportFactory buildTransportFactory() {
    assertEventLoopAndChannelType();

    ProtocolNegotiator negotiator = protocolNegotiatorFactory.newNegotiator();
    return new NettyTransportFactory(
        negotiator,
        channelFactory,
        channelOptions,
        eventLoopGroupPool,
        autoFlowControl,
        flowControlWindow,
        maxInboundMessageSize,
        maxHeaderListSize,
        softLimitHeaderListSize,
        keepAliveTimeNanos,
        keepAliveTimeoutNanos,
        keepAliveWithoutCalls,
        transportTracerFactory,
        localSocketPicker,
        useGetForSafeMethods,
        transportSocketType);
  }

  @VisibleForTesting
  void assertEventLoopAndChannelType() {
    boolean bothProvided = channelFactory != DEFAULT_CHANNEL_FACTORY
        && eventLoopGroupPool != DEFAULT_EVENT_LOOP_GROUP_POOL;
    boolean nonProvided = channelFactory == DEFAULT_CHANNEL_FACTORY
        && eventLoopGroupPool == DEFAULT_EVENT_LOOP_GROUP_POOL;
    checkState(
        bothProvided || nonProvided,
        "Both EventLoopGroup and ChannelType should be provided or neither should be");
  }

  int getDefaultPort() {
    return protocolNegotiatorFactory.getDefaultPort();
  }

  @VisibleForTesting
  static ProtocolNegotiator createProtocolNegotiatorByType(
      NegotiationType negotiationType,
      SslContext sslContext,
      ObjectPool<? extends Executor> executorPool) {
    switch (negotiationType) {
      case PLAINTEXT:
        return ProtocolNegotiators.plaintext();
      case PLAINTEXT_UPGRADE:
        return ProtocolNegotiators.plaintextUpgrade();
      case TLS:
        return ProtocolNegotiators.tls(sslContext, executorPool, Optional.empty());
      default:
        throw new IllegalArgumentException("Unsupported negotiationType: " + negotiationType);
    }
  }

  @CanIgnoreReturnValue
  NettyChannelBuilder disableCheckAuthority() {
    this.managedChannelImplBuilder.disableCheckAuthority();
    return this;
  }

  @CanIgnoreReturnValue
  NettyChannelBuilder enableCheckAuthority() {
    this.managedChannelImplBuilder.enableCheckAuthority();
    return this;
  }

  void protocolNegotiatorFactory(ProtocolNegotiator.ClientFactory protocolNegotiatorFactory) {
    checkState(!freezeProtocolNegotiatorFactory,
               "Cannot change security when using ChannelCredentials");
    this.protocolNegotiatorFactory
        = checkNotNull(protocolNegotiatorFactory, "protocolNegotiatorFactory");
  }

  void setTracingEnabled(boolean value) {
    this.managedChannelImplBuilder.setTracingEnabled(value);
  }

  void setStatsEnabled(boolean value) {
    this.managedChannelImplBuilder.setStatsEnabled(value);
  }

  void setStatsRecordStartedRpcs(boolean value) {
    this.managedChannelImplBuilder.setStatsRecordStartedRpcs(value);
  }

  void setStatsRecordFinishedRpcs(boolean value) {
    this.managedChannelImplBuilder.setStatsRecordFinishedRpcs(value);
  }

  void setStatsRecordRealTimeMetrics(boolean value) {
    this.managedChannelImplBuilder.setStatsRecordRealTimeMetrics(value);
  }

  void setStatsRecordRetryMetrics(boolean value) {
    this.managedChannelImplBuilder.setStatsRecordRetryMetrics(value);
  }

  @CanIgnoreReturnValue
  @VisibleForTesting
  NettyChannelBuilder setTransportTracerFactory(TransportTracer.Factory transportTracerFactory) {
    this.transportTracerFactory = transportTracerFactory;
    return this;
  }

  static Collection<Class<? extends SocketAddress>> getSupportedSocketAddressTypes() {
    return Collections.singleton(InetSocketAddress.class);
  }

  private final class DefaultProtocolNegotiator implements ProtocolNegotiator.ClientFactory {
    private NegotiationType negotiationType = NegotiationType.TLS;
    private SslContext sslContext;

    @Override
    public ProtocolNegotiator newNegotiator() {
      SslContext localSslContext = sslContext;
      if (negotiationType == NegotiationType.TLS && localSslContext == null) {
        try {
          localSslContext = GrpcSslContexts.forClient().build();
        } catch (SSLException ex) {
          throw new RuntimeException(ex);
        }
      }
      return createProtocolNegotiatorByType(negotiationType, localSslContext,
          managedChannelImplBuilder.getOffloadExecutorPool());
    }

    @Override
    public int getDefaultPort() {
      switch (negotiationType) {
        case PLAINTEXT:
        case PLAINTEXT_UPGRADE:
          return GrpcUtil.DEFAULT_PORT_PLAINTEXT;
        case TLS:
          return GrpcUtil.DEFAULT_PORT_SSL;
        default:
          throw new AssertionError(negotiationType + " not handled");
      }
    }
  }

  /**
   * Creates Netty transports. Exposed for internal use, as it should be private.
   */
  private static final class NettyTransportFactory implements ClientTransportFactory {
    private final ProtocolNegotiator protocolNegotiator;
    private final ChannelFactory<? extends Channel> channelFactory;
    private final Map<ChannelOption<?>, ?> channelOptions;
    private final ObjectPool<? extends EventLoopGroup> groupPool;
    private final EventLoopGroup group;
    private final boolean autoFlowControl;
    private final int flowControlWindow;
    private final int maxMessageSize;
    private final int maxHeaderListSize;
    private final int softLimitHeaderListSize;
    private final long keepAliveTimeNanos;
    private final AtomicBackoff keepAliveBackoff;
    private final long keepAliveTimeoutNanos;
    private final boolean keepAliveWithoutCalls;
    private final TransportTracer.Factory transportTracerFactory;
    private final LocalSocketPicker localSocketPicker;
    private final boolean useGetForSafeMethods;

    private boolean closed;
    private final Class<? extends SocketAddress> transportSocketType;

    NettyTransportFactory(
        ProtocolNegotiator protocolNegotiator,
        ChannelFactory<? extends Channel> channelFactory,
        Map<ChannelOption<?>, ?> channelOptions,
        ObjectPool<? extends EventLoopGroup> groupPool,
        boolean autoFlowControl,
        int flowControlWindow,
        int maxMessageSize,
        int maxHeaderListSize,
        int softLimitHeaderListSize,
        long keepAliveTimeNanos,
        long keepAliveTimeoutNanos,
        boolean keepAliveWithoutCalls,
        TransportTracer.Factory transportTracerFactory,
        LocalSocketPicker localSocketPicker,
        boolean useGetForSafeMethods,
        Class<? extends SocketAddress> transportSocketType) {
      this.protocolNegotiator = checkNotNull(protocolNegotiator, "protocolNegotiator");
      this.channelFactory = channelFactory;
      this.channelOptions = new HashMap<ChannelOption<?>, Object>(channelOptions);
      this.groupPool = groupPool;
      this.group = groupPool.getObject();
      this.autoFlowControl = autoFlowControl;
      this.flowControlWindow = flowControlWindow;
      this.maxMessageSize = maxMessageSize;
      this.maxHeaderListSize = maxHeaderListSize;
      this.softLimitHeaderListSize = softLimitHeaderListSize;
      this.keepAliveTimeNanos = keepAliveTimeNanos;
      this.keepAliveBackoff = new AtomicBackoff("keepalive time nanos", keepAliveTimeNanos);
      this.keepAliveTimeoutNanos = keepAliveTimeoutNanos;
      this.keepAliveWithoutCalls = keepAliveWithoutCalls;
      this.transportTracerFactory = transportTracerFactory;
      this.localSocketPicker =
          localSocketPicker != null ? localSocketPicker : new LocalSocketPicker();
      this.useGetForSafeMethods = useGetForSafeMethods;
      this.transportSocketType = transportSocketType;
    }

    @Override
    public ConnectionClientTransport newClientTransport(
        SocketAddress serverAddress, ClientTransportOptions options, ChannelLogger channelLogger) {
      checkState(!closed, "The transport factory is closed.");

      ProtocolNegotiator localNegotiator = protocolNegotiator;
      HttpConnectProxiedSocketAddress proxiedAddr = options.getHttpConnectProxiedSocketAddress();
      if (proxiedAddr != null) {
        serverAddress = proxiedAddr.getTargetAddress();
        localNegotiator = ProtocolNegotiators.httpProxy(
            proxiedAddr.getProxyAddress(),
            proxiedAddr.getUsername(),
            proxiedAddr.getPassword(),
            protocolNegotiator);
      }

      final AtomicBackoff.State keepAliveTimeNanosState = keepAliveBackoff.getState();
      Runnable tooManyPingsRunnable = new Runnable() {
        @Override
        public void run() {
          keepAliveTimeNanosState.backoff();
        }
      };

      // TODO(carl-mastrangelo): Pass channelLogger in.
      NettyClientTransport transport =
          new NettyClientTransport(
              serverAddress,
              channelFactory,
              channelOptions,
              group,
              localNegotiator,
              autoFlowControl,
              flowControlWindow,
              maxMessageSize,
              maxHeaderListSize,
              softLimitHeaderListSize,
              keepAliveTimeNanosState.get(),
              keepAliveTimeoutNanos,
              keepAliveWithoutCalls,
              options.getAuthority(),
              options.getUserAgent(),
              tooManyPingsRunnable,
              transportTracerFactory.create(),
              options.getEagAttributes(),
              localSocketPicker,
              channelLogger,
              useGetForSafeMethods,
              Ticker.systemTicker());
      return transport;
    }

    @Override
    public ScheduledExecutorService getScheduledExecutorService() {
      return group;
    }

    @Override
    public SwapChannelCredentialsResult swapChannelCredentials(ChannelCredentials channelCreds) {
      checkNotNull(channelCreds, "channelCreds");
      FromChannelCredentialsResult result = ProtocolNegotiators.from(channelCreds);
      if (result.error != null) {
        return null;
      }
      ClientTransportFactory factory =
          new NettyTransportFactory(
              result.negotiator.newNegotiator(),
              channelFactory,
              channelOptions,
              groupPool,
              autoFlowControl,
              flowControlWindow,
              maxMessageSize,
              maxHeaderListSize,
              softLimitHeaderListSize,
              keepAliveTimeNanos,
              keepAliveTimeoutNanos,
              keepAliveWithoutCalls,
              transportTracerFactory,
              localSocketPicker,
              useGetForSafeMethods,
              transportSocketType);
      return new SwapChannelCredentialsResult(factory, result.callCredentials);
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;

      protocolNegotiator.close();
      groupPool.returnObject(group);
    }

    @Override
    public Collection<Class<? extends SocketAddress>> getSupportedSocketAddressTypes() {
      return transportSocketType == null ? null
          : Collections.singleton(transportSocketType);
    }
  }
}
