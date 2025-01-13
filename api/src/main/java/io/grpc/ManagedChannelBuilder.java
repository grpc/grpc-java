/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc;

import com.google.common.base.Preconditions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * A builder for {@link ManagedChannel} instances.
 *
 * @param <T> The concrete type of this builder.
 */
public abstract class ManagedChannelBuilder<T extends ManagedChannelBuilder<T>> {
  /**
   * Creates a channel with the target's address and port number.
   *
   * <p>Note that there is an open JDK bug on {@link java.net.URI} class parsing an ipv6 scope ID:
   * bugs.openjdk.org/browse/JDK-8199396. This method is exposed to this bug. If you experience an
   * issue, a work-around is to convert the scope ID to its numeric form (e.g. by using
   * Inet6Address.getScopeId()) before calling this method.
   *
   * @see #forTarget(String)
   * @since 1.0.0
   */
  public static ManagedChannelBuilder<?> forAddress(String name, int port) {
    return ManagedChannelProvider.provider().builderForAddress(name, port);
  }

  /**
   * Creates a channel with a target string, which can be either a valid {@link
   * NameResolver}-compliant URI, or an authority string.
   *
   * <p>A {@code NameResolver}-compliant URI is an absolute hierarchical URI as defined by {@link
   * java.net.URI}. Example URIs:
   * <ul>
   *   <li>{@code "dns:///foo.googleapis.com:8080"}</li>
   *   <li>{@code "dns:///foo.googleapis.com"}</li>
   *   <li>{@code "dns:///%5B2001:db8:85a3:8d3:1319:8a2e:370:7348%5D:443"}</li>
   *   <li>{@code "dns://8.8.8.8/foo.googleapis.com:8080"}</li>
   *   <li>{@code "dns://8.8.8.8/foo.googleapis.com"}</li>
   *   <li>{@code "zookeeper://zk.example.com:9900/example_service"}</li>
   * </ul>
   *
   * <p>An authority string will be converted to a {@code NameResolver}-compliant URI, which has
   * the scheme from the name resolver with the highest priority (e.g. {@code "dns"}),
   * no authority, and the original authority string as its path after properly escaped.
   * We recommend libraries to specify the schema explicitly if it is known, since libraries cannot
   * know which NameResolver will be default during runtime.
   * Example authority strings:
   * <ul>
   *   <li>{@code "localhost"}</li>
   *   <li>{@code "127.0.0.1"}</li>
   *   <li>{@code "localhost:8080"}</li>
   *   <li>{@code "foo.googleapis.com:8080"}</li>
   *   <li>{@code "127.0.0.1:8080"}</li>
   *   <li>{@code "[2001:db8:85a3:8d3:1319:8a2e:370:7348]"}</li>
   *   <li>{@code "[2001:db8:85a3:8d3:1319:8a2e:370:7348]:443"}</li>
   * </ul>
   *
   * <p>Note that there is an open JDK bug on {@link java.net.URI} class parsing an ipv6 scope ID:
   * bugs.openjdk.org/browse/JDK-8199396. This method is exposed to this bug. If you experience an
   * issue, a work-around is to convert the scope ID to its numeric form (e.g. by using
   * Inet6Address.getScopeId()) before calling this method.
   * 
   * @since 1.0.0
   */
  public static ManagedChannelBuilder<?> forTarget(String target) {
    return ManagedChannelProvider.provider().builderForTarget(target);
  }

  /**
   * Execute application code directly in the transport thread.
   *
   * <p>Depending on the underlying transport, using a direct executor may lead to substantial
   * performance improvements. However, it also requires the application to not block under
   * any circumstances.
   *
   * <p>Calling this method is semantically equivalent to calling {@link #executor(Executor)} and
   * passing in a direct executor. However, this is the preferred way as it may allow the transport
   * to perform special optimizations.
   *
   * @return this
   * @since 1.0.0
   */
  public abstract T directExecutor();

  /**
   * Provides a custom executor.
   *
   * <p>It's an optional parameter. If the user has not provided an executor when the channel is
   * built, the builder will use a static cached thread pool.
   *
   * <p>The channel won't take ownership of the given executor. It's caller's responsibility to
   * shut down the executor when it's desired.
   *
   * @return this
   * @since 1.0.0
   */
  public abstract T executor(Executor executor);

  /**
   * Provides a custom executor that will be used for operations that block or are expensive, to
   * avoid blocking asynchronous code paths. For example, DNS queries and OAuth token fetching over
   * HTTP could use this executor.
   *
   * <p>It's an optional parameter. If the user has not provided an executor when the channel is
   * built, the builder will use a static cached thread pool.
   *
   * <p>The channel won't take ownership of the given executor. It's caller's responsibility to shut
   * down the executor when it's desired.
   *
   * @return this
   * @throws UnsupportedOperationException if unsupported
   * @since 1.25.0
   */
  public T offloadExecutor(Executor executor) {
    throw new UnsupportedOperationException();
  }

  /**
   * Adds interceptors that will be called before the channel performs its real work. This is
   * functionally equivalent to using {@link ClientInterceptors#intercept(Channel, List)}, but while
   * still having access to the original {@code ManagedChannel}. Interceptors run in the reverse
   * order in which they are added, just as with consecutive calls to {@code
   * ClientInterceptors.intercept()}.
   *
   * @return this
   * @since 1.0.0
   */
  public abstract T intercept(List<ClientInterceptor> interceptors);

  /**
   * Adds interceptors that will be called before the channel performs its real work. This is
   * functionally equivalent to using {@link ClientInterceptors#intercept(Channel,
   * ClientInterceptor...)}, but while still having access to the original {@code ManagedChannel}.
   * Interceptors run in the reverse order in which they are added, just as with consecutive calls
   * to {@code ClientInterceptors.intercept()}.
   *
   * @return this
   * @since 1.0.0
   */
  public abstract T intercept(ClientInterceptor... interceptors);

  /**
   * Internal-only: Adds a factory that will construct an interceptor based on the channel's target.
   * This can be used to work around nameResolverFactory() changing the target string.
   */
  @Internal
  protected T interceptWithTarget(InterceptorFactory factory) {
    throw new UnsupportedOperationException();
  }

  /** Internal-only. */
  @Internal
  protected interface InterceptorFactory {
    ClientInterceptor newInterceptor(String target);
  }

  /**
   * Adds a {@link ClientTransportFilter}. The order of filters being added is the order they will
   * be executed
   *
   * @return this
   * @since 1.60.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/10652")
  public T addTransportFilter(ClientTransportFilter filter) {
    throw new UnsupportedOperationException();
  }

  /**
   * Provides a custom {@code User-Agent} for the application.
   *
   * <p>It's an optional parameter. The library will provide a user agent independent of this
   * option. If provided, the given agent will prepend the library's user agent information.
   *
   * @return this
   * @since 1.0.0
   */
  public abstract T userAgent(String userAgent);

  /**
   * Overrides the authority used with TLS and HTTP virtual hosting. It does not change what host is
   * actually connected to. Is commonly in the form {@code host:port}.
   *
   * <p>If the channel builder overrides authority, any authority override from name resolution
   * result (via {@link EquivalentAddressGroup#ATTR_AUTHORITY_OVERRIDE}) will be discarded.
   *
   * <p>This method is intended for testing, but may safely be used outside of tests as an
   * alternative to DNS overrides.
   *
   * @return this
   * @since 1.0.0
   */
  public abstract T overrideAuthority(String authority);

  /**
   * Use of a plaintext connection to the server. By default a secure connection mechanism
   * such as TLS will be used.
   *
   * <p>Should only be used for testing or for APIs where the use of such API or the data
   * exchanged is not sensitive.
   *
   * <p>This assumes prior knowledge that the target of this channel is using plaintext.  It will
   * not perform HTTP/1.1 upgrades.
   *
   * @return this
   * @throws IllegalStateException if ChannelCredentials were provided when constructing the builder
   * @throws UnsupportedOperationException if plaintext mode is not supported.
   * @since 1.11.0
   */
  public T usePlaintext() {
    throw new UnsupportedOperationException();
  }

  /**
   * Makes the client use TLS. Note: this is enabled by default.
   *
   * <p>It is recommended to use the {@link ChannelCredentials} API
   * instead of this method.
   *
   * @return this
   * @throws IllegalStateException if ChannelCredentials were provided when constructing the builder
   * @throws UnsupportedOperationException if transport security is not supported.
   * @since 1.9.0
   */
  public T useTransportSecurity() {
    throw new UnsupportedOperationException();
  }

  /**
   * Provides a custom {@link NameResolver.Factory} for the channel. If this method is not called,
   * the builder will try the providers registered in the default {@link NameResolverRegistry} for
   * the given target.
   *
   * <p>This method should rarely be used, as name resolvers should provide a {@code
   * NameResolverProvider} and users rely on service loading to find implementations in the class
   * path. That allows application's configuration to easily choose the name resolver via the
   * 'target' string passed to {@link ManagedChannelBuilder#forTarget(String)}.
   *
   * @return this
   * @since 1.0.0
   * @deprecated Most usages should use a globally-registered {@link NameResolverProvider} instead,
   *     with either the SPI mechanism or {@link NameResolverRegistry#register}. Replacements for
   *     all use-cases are not necessarily available yet. See
   *     <a href="https://github.com/grpc/grpc-java/issues/7133">#7133</a>.
   */
  @Deprecated
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
  public abstract T nameResolverFactory(NameResolver.Factory resolverFactory);

  /**
   * Sets the default load-balancing policy that will be used if the service config doesn't specify
   * one.  If not set, the default will be the "pick_first" policy.
   *
   * <p>Policy implementations are looked up in the
   * {@link LoadBalancerRegistry#getDefaultRegistry default LoadBalancerRegistry}.
   *
   * <p>This method is implemented by all stock channel builders that are shipped with gRPC, but may
   * not be implemented by custom channel builders, in which case this method will throw.
   *
   * @return this
   * @since 1.18.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public T defaultLoadBalancingPolicy(String policy) {
    throw new UnsupportedOperationException();
  }

  /**
   * Set the decompression registry for use in the channel. This is an advanced API call and
   * shouldn't be used unless you are using custom message encoding. The default supported
   * decompressors are in {@link DecompressorRegistry#getDefaultInstance}.
   *
   * @return this
   * @since 1.0.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1704")
  public abstract T decompressorRegistry(DecompressorRegistry registry);

  /**
   * Set the compression registry for use in the channel.  This is an advanced API call and
   * shouldn't be used unless you are using custom message encoding.   The default supported
   * compressors are in {@link CompressorRegistry#getDefaultInstance}.
   *
   * @return this
   * @since 1.0.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1704")
  public abstract T compressorRegistry(CompressorRegistry registry);

  /**
   * Set the duration without ongoing RPCs before going to idle mode.
   *
   * <p>In idle mode the channel shuts down all connections, the NameResolver and the
   * LoadBalancer. A new RPC would take the channel out of idle mode. A channel starts in idle mode.
   * Defaults to 30 minutes.
   *
   * <p>This is an advisory option. Do not rely on any specific behavior related to this option.
   *
   * @return this
   * @since 1.0.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2022")
  public abstract T idleTimeout(long value, TimeUnit unit);

  /**
   * Sets the maximum message size allowed to be received on the channel. If not called,
   * defaults to 4 MiB. The default provides protection to clients who haven't considered the
   * possibility of receiving large messages while trying to be large enough to not be hit in normal
   * usage.
   *
   * <p>This method is advisory, and implementations may decide to not enforce this.  Currently,
   * the only known transport to not enforce this is {@code InProcessTransport}.
   *
   * @param bytes the maximum number of bytes a single message can be.
   * @return this
   * @throws IllegalArgumentException if bytes is negative.
   * @since 1.1.0
   */
  public T maxInboundMessageSize(int bytes) {
    // intentional noop rather than throw, this method is only advisory.
    Preconditions.checkArgument(bytes >= 0, "bytes must be >= 0");
    return thisT();
  }

  /**
   * Sets the maximum size of metadata allowed to be received. {@code Integer.MAX_VALUE} disables
   * the enforcement. The default is implementation-dependent, but is not generally less than 8 KiB
   * and may be unlimited.
   *
   * <p>This is cumulative size of the metadata. The precise calculation is
   * implementation-dependent, but implementations are encouraged to follow the calculation used for
   * <a href="http://httpwg.org/specs/rfc7540.html#rfc.section.6.5.2">
   * HTTP/2's SETTINGS_MAX_HEADER_LIST_SIZE</a>. It sums the bytes from each entry's key and value,
   * plus 32 bytes of overhead per entry.
   *
   * @param bytes the maximum size of received metadata
   * @return this
   * @throws IllegalArgumentException if bytes is non-positive
   * @since 1.17.0
   */
  public T maxInboundMetadataSize(int bytes) {
    Preconditions.checkArgument(bytes > 0, "maxInboundMetadataSize must be > 0");
    // intentional noop rather than throw, this method is only advisory.
    return thisT();
  }

  /**
   * Sets the time without read activity before sending a keepalive ping. An unreasonably small
   * value might be increased, and {@code Long.MAX_VALUE} nano seconds or an unreasonably large
   * value will disable keepalive. Defaults to infinite.
   *
   * <p>Clients must receive permission from the service owner before enabling this option.
   * Keepalives can increase the load on services and are commonly "invisible" making it hard to
   * notice when they are causing excessive load. Clients are strongly encouraged to use only as
   * small of a value as necessary.
   *
   * @throws UnsupportedOperationException if unsupported
   * @see <a href="https://github.com/grpc/proposal/blob/master/A8-client-side-keepalive.md">gRFC A8
   *     Client-side Keepalive</a>
   * @since 1.7.0
   */
  public T keepAliveTime(long keepAliveTime, TimeUnit timeUnit) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the time waiting for read activity after sending a keepalive ping. If the time expires
   * without any read activity on the connection, the connection is considered dead. An unreasonably
   * small value might be increased. Defaults to 20 seconds.
   *
   * <p>This value should be at least multiple times the RTT to allow for lost packets.
   *
   * @throws UnsupportedOperationException if unsupported
   * @see <a href="https://github.com/grpc/proposal/blob/master/A8-client-side-keepalive.md">gRFC A8
   *     Client-side Keepalive</a>
   * @since 1.7.0
   */
  public T keepAliveTimeout(long keepAliveTimeout, TimeUnit timeUnit) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets whether keepalive will be performed when there are no outstanding RPC on a connection.
   * Defaults to {@code false}.
   *
   * <p>Clients must receive permission from the service owner before enabling this option.
   * Keepalives on unused connections can easilly accidentally consume a considerable amount of
   * bandwidth and CPU. {@link ManagedChannelBuilder#idleTimeout idleTimeout()} should generally be
   * used instead of this option.
   *
   * @throws UnsupportedOperationException if unsupported
   * @see #keepAliveTime(long, TimeUnit)
   * @see <a href="https://github.com/grpc/proposal/blob/master/A8-client-side-keepalive.md">gRFC A8
   *     Client-side Keepalive</a>
   * @since 1.7.0
   */
  public T keepAliveWithoutCalls(boolean enable) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the maximum number of retry attempts that may be configured by the service config. If the
   * service config specifies a larger value it will be reduced to this value.  Setting this number
   * to zero is not effectively the same as {@code disableRetry()} because the former does not
   * disable
   * <a
   * href="https://github.com/grpc/proposal/blob/master/A6-client-retries.md#transparent-retries">
   * transparent retry</a>.
   *
   * <p>This method may not work as expected for the current release because retry is not fully
   * implemented yet.
   *
   * @return this
   * @since 1.11.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/3982")
  public T maxRetryAttempts(int maxRetryAttempts) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the maximum number of hedged attempts that may be configured by the service config. If the
   * service config specifies a larger value it will be reduced to this value.
   *
   * <p>This method may not work as expected for the current release because retry is not fully
   * implemented yet.
   *
   * @return this
   * @since 1.11.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/3982")
  public T maxHedgedAttempts(int maxHedgedAttempts) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the retry buffer size in bytes. If the buffer limit is exceeded, no RPC
   * could retry at the moment, and in hedging case all hedges but one of the same RPC will cancel.
   * The implementation may only estimate the buffer size being used rather than count the
   * exact physical memory allocated. The method does not have any effect if retry is disabled by
   * the client.
   *
   * <p>This method may not work as expected for the current release because retry is not fully
   * implemented yet.
   *
   * @return this
   * @since 1.10.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/3982")
  public T retryBufferSize(long bytes) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the per RPC buffer limit in bytes used for retry. The RPC is not retriable if its buffer
   * limit is exceeded. The implementation may only estimate the buffer size being used rather than
   * count the exact physical memory allocated. It does not have any effect if retry is disabled by
   * the client.
   *
   * <p>This method may not work as expected for the current release because retry is not fully
   * implemented yet.
   *
   * @return this
   * @since 1.10.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/3982")
  public T perRpcBufferLimit(long bytes) {
    throw new UnsupportedOperationException();
  }


  /**
   * Disables the retry and hedging subsystem provided by the gRPC library. This is designed for the
   * case when users have their own retry implementation and want to avoid their own retry taking
   * place simultaneously with the gRPC library layer retry.
   *
   * @return this
   * @since 1.11.0
   */
  public T disableRetry() {
    throw new UnsupportedOperationException();
  }

  /**
   * Enables the retry and hedging subsystem which will use
   * <a href="https://github.com/grpc/proposal/blob/master/A6-client-retries.md#integration-with-service-config">
   * per-method configuration</a>. If a method is unconfigured, it will be limited to
   * transparent retries, which are safe for non-idempotent RPCs. Service config is ideally provided
   * by the name resolver, but may also be specified via {@link #defaultServiceConfig}.
   *
   * @return this
   * @since 1.11.0
   */
  public T enableRetry() {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the BinaryLog object that this channel should log to. The channel does not take
   * ownership of the object, and users are responsible for calling {@link BinaryLog#close()}.
   *
   * @param binaryLog the object to provide logging.
   * @return this
   * @since 1.13.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/4017")
  public T setBinaryLog(BinaryLog binaryLog) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the maximum number of channel trace events to keep in the tracer for each channel or
   * subchannel. If set to 0, channel tracing is effectively disabled.
   *
   * @return this
   * @since 1.13.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/4471")
  public T maxTraceEvents(int maxTraceEvents) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the proxy detector to be used in addresses name resolution. If <code>null</code> is passed
   * the default proxy detector will be used.  For how proxies work in gRPC, please refer to the
   * documentation on {@link ProxyDetector}.
   *
   * @return this
   * @since 1.19.0
   */
  public T proxyDetector(ProxyDetector proxyDetector) {
    throw new UnsupportedOperationException();
  }

  /**
   * Provides a service config to the channel. The channel will use the default service config when
   * the name resolver provides no service config or if the channel disables lookup service config
   * from name resolver (see {@link #disableServiceConfigLookUp()}). The argument
   * {@code serviceConfig} is a nested map representing a Json object in the most natural way:
   *
   *        <table border="1">
   *          <tr>
   *            <td>Json entry</td><td>Java Type</td>
   *          </tr>
   *          <tr>
   *            <td>object</td><td>{@link Map}</td>
   *          </tr>
   *          <tr>
   *            <td>array</td><td>{@link List}</td>
   *          </tr>
   *          <tr>
   *            <td>string</td><td>{@link String}</td>
   *          </tr>
   *          <tr>
   *            <td>number</td><td>{@link Double}</td>
   *          </tr>
   *          <tr>
   *            <td>boolean</td><td>{@link Boolean}</td>
   *          </tr>
   *          <tr>
   *            <td>null</td><td>{@code null}</td>
   *          </tr>
   *        </table>
   *
   * <p>If null is passed, then there will be no default service config.
   *
   * <p>Your preferred JSON parser may not produce results in the format expected. For such cases,
   * you can convert its output. For example, if your parser produces Integers and other Numbers
   * in addition to Double:
   *
   * <pre>{@code @SuppressWarnings("unchecked")
   * private static Object convertNumbers(Object o) {
   *   if (o instanceof Map) {
   *     ((Map) o).replaceAll((k,v) -> convertNumbers(v));
   *   } else if (o instanceof List) {
   *     ((List) o).replaceAll(YourClass::convertNumbers);
   *   } else if (o instanceof Number && !(o instanceof Double)) {
   *     o = ((Number) o).doubleValue();
   *   }
   *   return o;
   * }}</pre>
   *
   * @return this
   * @throws IllegalArgumentException When the given serviceConfig is invalid or the current version
   *         of grpc library can not parse it gracefully. The state of the builder is unchanged if
   *         an exception is thrown.
   * @since 1.20.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/5189")
  public T defaultServiceConfig(@Nullable Map<String, ?> serviceConfig) {
    throw new UnsupportedOperationException();
  }

  /**
   * Disables service config look-up from the naming system, which is enabled by default.
   *
   * @return this
   * @since 1.20.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/5189")
  public T disableServiceConfigLookUp() {
    throw new UnsupportedOperationException();
  }

  /**
   * Adds a {@link MetricSink} for channel to use for configuring and recording metrics.
   *
   * @return this
   * @since 1.64.0
   */
  @Internal
  protected T addMetricSink(MetricSink metricSink) {
    throw new UnsupportedOperationException();
  }

  /**
   * Provides a "custom" argument for the {@link NameResolver}, if applicable, replacing any 'value'
   * previously provided for 'key'.
   *
   * <p>NB: If the selected {@link NameResolver} does not understand 'key', or target URI resolution
   * isn't needed at all, your custom argument will be silently ignored.
   *
   * <p>See {@link NameResolver.Args#getArg(NameResolver.Args.Key)} for more.
   *
   * @param key identifies the argument in a type-safe manner
   * @param value the argument itself
   * @return this
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
  public <X> T setNameResolverArg(NameResolver.Args.Key<X> key, X value) {
    throw new UnsupportedOperationException();
  }

  /**
   * Builds a channel using the given parameters.
   *
   * @since 1.0.0
   */
  public abstract ManagedChannel build();

  /**
   * Returns the correctly typed version of the builder.
   */
  private T thisT() {
    @SuppressWarnings("unchecked")
    T thisT = (T) this;
    return thisT;
  }
}
