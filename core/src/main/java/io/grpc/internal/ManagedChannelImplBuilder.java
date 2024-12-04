/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.errorprone.annotations.DoNotCall;
import io.grpc.Attributes;
import io.grpc.BinaryLog;
import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientTransportFilter;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalChannelz;
import io.grpc.InternalConfiguratorRegistry;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.MetricSink;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.ProxyDetector;
import io.grpc.StatusOr;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Default managed channel builder, for usage in Transport implementations.
 */
public final class ManagedChannelImplBuilder
    extends ManagedChannelBuilder<ManagedChannelImplBuilder> {
  private static final String DIRECT_ADDRESS_SCHEME = "directaddress";

  private static final Logger log = Logger.getLogger(ManagedChannelImplBuilder.class.getName());

  @DoNotCall("ClientTransportFactoryBuilder is required, use a constructor")
  public static ManagedChannelBuilder<?> forAddress(String name, int port) {
    throw new UnsupportedOperationException(
        "ClientTransportFactoryBuilder is required, use a constructor");
  }

  @DoNotCall("ClientTransportFactoryBuilder is required, use a constructor")
  public static ManagedChannelBuilder<?> forTarget(String target) {
    throw new UnsupportedOperationException(
        "ClientTransportFactoryBuilder is required, use a constructor");
  }

  /**
   * An idle timeout larger than this would disable idle mode.
   */
  @VisibleForTesting
  static final long IDLE_MODE_MAX_TIMEOUT_DAYS = 30;

  /**
   * The default idle timeout.
   */
  @VisibleForTesting
  static final long IDLE_MODE_DEFAULT_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(30);

  /**
   * An idle timeout smaller than this would be capped to it.
   */
  static final long IDLE_MODE_MIN_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(1);

  private static final ObjectPool<? extends Executor> DEFAULT_EXECUTOR_POOL =
      SharedResourcePool.forResource(GrpcUtil.SHARED_CHANNEL_EXECUTOR);

  private static final DecompressorRegistry DEFAULT_DECOMPRESSOR_REGISTRY =
      DecompressorRegistry.getDefaultInstance();

  private static final CompressorRegistry DEFAULT_COMPRESSOR_REGISTRY =
      CompressorRegistry.getDefaultInstance();

  private static final long DEFAULT_RETRY_BUFFER_SIZE_IN_BYTES = 1L << 24;  // 16M
  private static final long DEFAULT_PER_RPC_BUFFER_LIMIT_IN_BYTES = 1L << 20; // 1M

  // Matching this pattern means the target string is a URI target or at least intended to be one.
  // A URI target must be an absolute hierarchical URI.
  // From RFC 2396: scheme = alpha *( alpha | digit | "+" | "-" | "." )
  @VisibleForTesting
  static final Pattern URI_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*:/.*");

  private static final Method GET_CLIENT_INTERCEPTOR_METHOD;

  static {
    Method getClientInterceptorMethod = null;
    try {
      Class<?> censusStatsAccessor =
          Class.forName("io.grpc.census.InternalCensusStatsAccessor");
      getClientInterceptorMethod =
          censusStatsAccessor.getDeclaredMethod(
              "getClientInterceptor",
              boolean.class,
              boolean.class,
              boolean.class,
              boolean.class);
    } catch (ClassNotFoundException e) {
      // Replace these separate catch statements with multicatch when Android min-API >= 19
      log.log(Level.FINE, "Unable to apply census stats", e);
    } catch (NoSuchMethodException e) {
      log.log(Level.FINE, "Unable to apply census stats", e);
    }
    GET_CLIENT_INTERCEPTOR_METHOD = getClientInterceptorMethod;
  }


  ObjectPool<? extends Executor> executorPool = DEFAULT_EXECUTOR_POOL;

  ObjectPool<? extends Executor> offloadExecutorPool = DEFAULT_EXECUTOR_POOL;

  private final List<ClientInterceptor> interceptors = new ArrayList<>();
  NameResolverRegistry nameResolverRegistry = NameResolverRegistry.getDefaultRegistry();

  final List<ClientTransportFilter> transportFilters = new ArrayList<>();

  final String target;
  @Nullable
  final ChannelCredentials channelCredentials;
  @Nullable
  final CallCredentials callCredentials;

  @Nullable
  private final SocketAddress directServerAddress;

  @Nullable
  String userAgent;

  @Nullable
  String authorityOverride;

  String defaultLbPolicy = GrpcUtil.DEFAULT_LB_POLICY;

  boolean fullStreamDecompression;

  DecompressorRegistry decompressorRegistry = DEFAULT_DECOMPRESSOR_REGISTRY;

  CompressorRegistry compressorRegistry = DEFAULT_COMPRESSOR_REGISTRY;

  long idleTimeoutMillis = IDLE_MODE_DEFAULT_TIMEOUT_MILLIS;

  int maxRetryAttempts = 5;
  int maxHedgedAttempts = 5;
  long retryBufferSize = DEFAULT_RETRY_BUFFER_SIZE_IN_BYTES;
  long perRpcBufferLimit = DEFAULT_PER_RPC_BUFFER_LIMIT_IN_BYTES;
  boolean retryEnabled = true;

  InternalChannelz channelz = InternalChannelz.instance();
  int maxTraceEvents;

  @Nullable
  Map<String, ?> defaultServiceConfig;
  boolean lookUpServiceConfig = true;

  @Nullable
  BinaryLog binlog;

  @Nullable
  ProxyDetector proxyDetector;

  private boolean authorityCheckerDisabled;
  private boolean statsEnabled = true;
  private boolean recordStartedRpcs = true;
  private boolean recordFinishedRpcs = true;
  private boolean recordRealTimeMetrics = false;
  private boolean recordRetryMetrics = true;
  private boolean tracingEnabled = true;
  List<MetricSink> metricSinks = new ArrayList<>();

  /**
   * An interface for Transport implementors to provide the {@link ClientTransportFactory}
   * appropriate for the channel.
   */
  public interface ClientTransportFactoryBuilder {
    ClientTransportFactory buildClientTransportFactory();
  }

  /**
   * Convenience ClientTransportFactoryBuilder, throws UnsupportedOperationException().
   */
  public static class UnsupportedClientTransportFactoryBuilder implements
      ClientTransportFactoryBuilder {
    @Override
    public ClientTransportFactory buildClientTransportFactory() {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * An interface for Transport implementors to provide a default port to {@link
   * io.grpc.NameResolver} for use in cases where the target string doesn't include a port. The
   * default implementation returns {@link GrpcUtil#DEFAULT_PORT_SSL}.
   */
  public interface ChannelBuilderDefaultPortProvider {
    int getDefaultPort();
  }

  /**
   * Default implementation of {@link ChannelBuilderDefaultPortProvider} that returns a fixed port.
   */
  public static final class FixedPortProvider implements ChannelBuilderDefaultPortProvider {
    private final int port;

    public FixedPortProvider(int port) {
      this.port = port;
    }

    @Override
    public int getDefaultPort() {
      return port;
    }
  }

  private static final class ManagedChannelDefaultPortProvider implements
      ChannelBuilderDefaultPortProvider {
    @Override
    public int getDefaultPort() {
      return GrpcUtil.DEFAULT_PORT_SSL;
    }
  }

  private final ClientTransportFactoryBuilder clientTransportFactoryBuilder;
  private final ChannelBuilderDefaultPortProvider channelBuilderDefaultPortProvider;

  /**
   * Creates a new managed channel builder with a target string, which can be either a valid {@link
   * io.grpc.NameResolver}-compliant URI, or an authority string. Transport implementors must
   * provide client transport factory builder, and may set custom channel default port provider.
   */
  public ManagedChannelImplBuilder(String target,
      ClientTransportFactoryBuilder clientTransportFactoryBuilder,
      @Nullable ChannelBuilderDefaultPortProvider channelBuilderDefaultPortProvider) {
    this(target, null, null, clientTransportFactoryBuilder, channelBuilderDefaultPortProvider);
  }

  /**
   * Creates a new managed channel builder with a target string, which can be either a valid {@link
   * io.grpc.NameResolver}-compliant URI, or an authority string. Transport implementors must
   * provide client transport factory builder, and may set custom channel default port provider.
   *
   * @param channelCreds The ChannelCredentials provided by the user. These may be used when
   *     creating derivative channels.
   */
  public ManagedChannelImplBuilder(
      String target, @Nullable ChannelCredentials channelCreds, @Nullable CallCredentials callCreds,
      ClientTransportFactoryBuilder clientTransportFactoryBuilder,
      @Nullable ChannelBuilderDefaultPortProvider channelBuilderDefaultPortProvider) {
    this.target = checkNotNull(target, "target");
    this.channelCredentials = channelCreds;
    this.callCredentials = callCreds;
    this.clientTransportFactoryBuilder = checkNotNull(clientTransportFactoryBuilder,
        "clientTransportFactoryBuilder");
    this.directServerAddress = null;

    if (channelBuilderDefaultPortProvider != null) {
      this.channelBuilderDefaultPortProvider = channelBuilderDefaultPortProvider;
    } else {
      this.channelBuilderDefaultPortProvider = new ManagedChannelDefaultPortProvider();
    }
    // TODO(dnvindhya): Move configurator to all the individual builders
    InternalConfiguratorRegistry.configureChannelBuilder(this);
  }

  /**
   * Returns a target string for the SocketAddress. It is only used as a placeholder, because
   * DirectAddressNameResolverProvider will not actually try to use it. However, it must be a valid
   * URI.
   */
  @VisibleForTesting
  static String makeTargetStringForDirectAddress(SocketAddress address) {
    try {
      return new URI(DIRECT_ADDRESS_SCHEME, "", "/" + address, null).toString();
    } catch (URISyntaxException e) {
      // It should not happen.
      throw new RuntimeException(e);
    }
  }

  /**
   * Creates a new managed channel builder with the given server address, authority string of the
   * channel. Transport implementors must provide client transport factory builder, and may set
   * custom channel default port provider.
   */
  public ManagedChannelImplBuilder(SocketAddress directServerAddress, String authority,
      ClientTransportFactoryBuilder clientTransportFactoryBuilder,
      @Nullable ChannelBuilderDefaultPortProvider channelBuilderDefaultPortProvider) {
    this(directServerAddress, authority, null, null, clientTransportFactoryBuilder,
        channelBuilderDefaultPortProvider);
  }

  /**
   * Creates a new managed channel builder with the given server address, authority string of the
   * channel. Transport implementors must provide client transport factory builder, and may set
   * custom channel default port provider.
   * 
   * @param channelCreds The ChannelCredentials provided by the user. These may be used when
   *     creating derivative channels.
   */
  public ManagedChannelImplBuilder(SocketAddress directServerAddress, String authority,
      @Nullable ChannelCredentials channelCreds, @Nullable CallCredentials callCreds,
      ClientTransportFactoryBuilder clientTransportFactoryBuilder,
      @Nullable ChannelBuilderDefaultPortProvider channelBuilderDefaultPortProvider) {
    this.target = makeTargetStringForDirectAddress(directServerAddress);
    this.channelCredentials = channelCreds;
    this.callCredentials = callCreds;
    this.clientTransportFactoryBuilder = checkNotNull(clientTransportFactoryBuilder,
        "clientTransportFactoryBuilder");
    this.directServerAddress = directServerAddress;
    NameResolverRegistry reg = new NameResolverRegistry();
    reg.register(new DirectAddressNameResolverProvider(directServerAddress,
        authority));
    this.nameResolverRegistry = reg;

    if (channelBuilderDefaultPortProvider != null) {
      this.channelBuilderDefaultPortProvider = channelBuilderDefaultPortProvider;
    } else {
      this.channelBuilderDefaultPortProvider = new ManagedChannelDefaultPortProvider();
    }
    // TODO(dnvindhya): Move configurator to all the individual builders
    InternalConfiguratorRegistry.configureChannelBuilder(this);
  }

  @Override
  public ManagedChannelImplBuilder directExecutor() {
    return executor(MoreExecutors.directExecutor());
  }

  @Override
  public ManagedChannelImplBuilder executor(Executor executor) {
    if (executor != null) {
      this.executorPool = new FixedObjectPool<>(executor);
    } else {
      this.executorPool = DEFAULT_EXECUTOR_POOL;
    }
    return this;
  }

  @Override
  public ManagedChannelImplBuilder offloadExecutor(Executor executor) {
    if (executor != null) {
      this.offloadExecutorPool = new FixedObjectPool<>(executor);
    } else {
      this.offloadExecutorPool = DEFAULT_EXECUTOR_POOL;
    }
    return this;
  }

  @Override
  public ManagedChannelImplBuilder intercept(List<ClientInterceptor> interceptors) {
    this.interceptors.addAll(interceptors);
    return this;
  }

  @Override
  public ManagedChannelImplBuilder intercept(ClientInterceptor... interceptors) {
    return intercept(Arrays.asList(interceptors));
  }

  @Override
  protected ManagedChannelImplBuilder interceptWithTarget(InterceptorFactory factory) {
    // Add a placeholder instance to the interceptor list, and replace it with a real instance
    // during build().
    this.interceptors.add(new InterceptorFactoryWrapper(factory));
    return this;
  }

  @Override
  public ManagedChannelImplBuilder addTransportFilter(ClientTransportFilter hook) {
    transportFilters.add(checkNotNull(hook, "transport filter"));
    return this;
  }

  @Deprecated
  @Override
  public ManagedChannelImplBuilder nameResolverFactory(NameResolver.Factory resolverFactory) {
    Preconditions.checkState(directServerAddress == null,
        "directServerAddress is set (%s), which forbids the use of NameResolverFactory",
        directServerAddress);
    if (resolverFactory != null) {
      NameResolverRegistry reg = new NameResolverRegistry();
      if (resolverFactory instanceof NameResolverProvider) {
        reg.register((NameResolverProvider) resolverFactory);
      } else {
        reg.register(new NameResolverFactoryToProviderFacade(resolverFactory));
      }
      this.nameResolverRegistry = reg;
    } else {
      this.nameResolverRegistry = NameResolverRegistry.getDefaultRegistry();
    }
    return this;
  }

  ManagedChannelImplBuilder nameResolverRegistry(NameResolverRegistry resolverRegistry) {
    this.nameResolverRegistry = resolverRegistry;
    return this;
  }

  @Override
  public ManagedChannelImplBuilder defaultLoadBalancingPolicy(String policy) {
    Preconditions.checkState(directServerAddress == null,
        "directServerAddress is set (%s), which forbids the use of load-balancing policy",
        directServerAddress);
    Preconditions.checkArgument(policy != null, "policy cannot be null");
    this.defaultLbPolicy = policy;
    return this;
  }

  @Override
  public ManagedChannelImplBuilder decompressorRegistry(DecompressorRegistry registry) {
    if (registry != null) {
      this.decompressorRegistry = registry;
    } else {
      this.decompressorRegistry = DEFAULT_DECOMPRESSOR_REGISTRY;
    }
    return this;
  }

  @Override
  public ManagedChannelImplBuilder compressorRegistry(CompressorRegistry registry) {
    if (registry != null) {
      this.compressorRegistry = registry;
    } else {
      this.compressorRegistry = DEFAULT_COMPRESSOR_REGISTRY;
    }
    return this;
  }

  @Override
  public ManagedChannelImplBuilder userAgent(@Nullable String userAgent) {
    this.userAgent = userAgent;
    return this;
  }

  @Override
  public ManagedChannelImplBuilder overrideAuthority(String authority) {
    this.authorityOverride = checkAuthority(authority);
    return this;
  }

  @Override
  public ManagedChannelImplBuilder idleTimeout(long value, TimeUnit unit) {
    checkArgument(value > 0, "idle timeout is %s, but must be positive", value);
    // We convert to the largest unit to avoid overflow
    if (unit.toDays(value) >= IDLE_MODE_MAX_TIMEOUT_DAYS) {
      // This disables idle mode
      this.idleTimeoutMillis = ManagedChannelImpl.IDLE_TIMEOUT_MILLIS_DISABLE;
    } else {
      this.idleTimeoutMillis = Math.max(unit.toMillis(value), IDLE_MODE_MIN_TIMEOUT_MILLIS);
    }
    return this;
  }

  @Override
  public ManagedChannelImplBuilder maxRetryAttempts(int maxRetryAttempts) {
    this.maxRetryAttempts = maxRetryAttempts;
    return this;
  }

  @Override
  public ManagedChannelImplBuilder maxHedgedAttempts(int maxHedgedAttempts) {
    this.maxHedgedAttempts = maxHedgedAttempts;
    return this;
  }

  @Override
  public ManagedChannelImplBuilder retryBufferSize(long bytes) {
    checkArgument(bytes > 0L, "retry buffer size must be positive");
    retryBufferSize = bytes;
    return this;
  }

  @Override
  public ManagedChannelImplBuilder perRpcBufferLimit(long bytes) {
    checkArgument(bytes > 0L, "per RPC buffer limit must be positive");
    perRpcBufferLimit = bytes;
    return this;
  }

  @Override
  public ManagedChannelImplBuilder disableRetry() {
    retryEnabled = false;
    return this;
  }

  @Override
  public ManagedChannelImplBuilder enableRetry() {
    retryEnabled = true;
    return this;
  }

  @Override
  public ManagedChannelImplBuilder setBinaryLog(BinaryLog binlog) {
    this.binlog = binlog;
    return this;
  }

  @Override
  public ManagedChannelImplBuilder maxTraceEvents(int maxTraceEvents) {
    checkArgument(maxTraceEvents >= 0, "maxTraceEvents must be non-negative");
    this.maxTraceEvents = maxTraceEvents;
    return this;
  }

  @Override
  public ManagedChannelImplBuilder proxyDetector(@Nullable ProxyDetector proxyDetector) {
    this.proxyDetector = proxyDetector;
    return this;
  }

  @Override
  public ManagedChannelImplBuilder defaultServiceConfig(@Nullable Map<String, ?> serviceConfig) {
    // TODO(notcarl): use real parsing
    defaultServiceConfig = checkMapEntryTypes(serviceConfig);
    return this;
  }

  @Nullable
  private static Map<String, ?> checkMapEntryTypes(@Nullable Map<?, ?> map) {
    if (map == null) {
      return null;
    }
    // Not using ImmutableMap.Builder because of extra guava dependency for Android.
    Map<String, Object> parsedMap = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      checkArgument(
          entry.getKey() instanceof String,
          "The key of the entry '%s' is not of String type", entry);

      String key = (String) entry.getKey();
      Object value = entry.getValue();
      if (value == null) {
        parsedMap.put(key, null);
      } else if (value instanceof Map) {
        parsedMap.put(key, checkMapEntryTypes((Map<?, ?>) value));
      } else if (value instanceof List) {
        parsedMap.put(key, checkListEntryTypes((List<?>) value));
      } else if (value instanceof String) {
        parsedMap.put(key, value);
      } else if (value instanceof Double) {
        parsedMap.put(key, value);
      } else if (value instanceof Boolean) {
        parsedMap.put(key, value);
      } else {
        throw new IllegalArgumentException(
            "The value of the map entry '" + entry + "' is of type '" + value.getClass()
                + "', which is not supported");
      }
    }
    return Collections.unmodifiableMap(parsedMap);
  }

  private static List<?> checkListEntryTypes(List<?> list) {
    List<Object> parsedList = new ArrayList<>(list.size());
    for (Object value : list) {
      if (value == null) {
        parsedList.add(null);
      } else if (value instanceof Map) {
        parsedList.add(checkMapEntryTypes((Map<?, ?>) value));
      } else if (value instanceof List) {
        parsedList.add(checkListEntryTypes((List<?>) value));
      } else if (value instanceof String) {
        parsedList.add(value);
      } else if (value instanceof Double) {
        parsedList.add(value);
      } else if (value instanceof Boolean) {
        parsedList.add(value);
      } else {
        throw new IllegalArgumentException(
            "The entry '" + value + "' is of type '" + value.getClass()
                + "', which is not supported");
      }
    }
    return Collections.unmodifiableList(parsedList);
  }

  @Override
  public ManagedChannelImplBuilder disableServiceConfigLookUp() {
    this.lookUpServiceConfig = false;
    return this;
  }

  /**
   * Disable or enable stats features. Enabled by default.
   *
   * <p>For the current release, calling {@code setStatsEnabled(true)} may have a side effect that
   * disables retry.
   */
  public void setStatsEnabled(boolean value) {
    statsEnabled = value;
  }

  /**
   * Disable or enable stats recording for RPC upstarts.  Effective only if {@link
   * #setStatsEnabled} is set to true.  Enabled by default.
   */
  public void setStatsRecordStartedRpcs(boolean value) {
    recordStartedRpcs = value;
  }

  /**
   * Disable or enable stats recording for RPC completions.  Effective only if {@link
   * #setStatsEnabled} is set to true.  Enabled by default.
   */
  public void setStatsRecordFinishedRpcs(boolean value) {
    recordFinishedRpcs = value;
  }

  /**
   * Disable or enable real-time metrics recording.  Effective only if {@link #setStatsEnabled} is
   * set to true.  Disabled by default.
   */
  public void setStatsRecordRealTimeMetrics(boolean value) {
    recordRealTimeMetrics = value;
  }
  
  public void setStatsRecordRetryMetrics(boolean value) {
    recordRetryMetrics = value;
  }

  /**
   * Disable or enable tracing features.  Enabled by default.
   */
  public void setTracingEnabled(boolean value) {
    tracingEnabled = value;
  }

  /**
   * Verifies the authority is valid.
   */
  @VisibleForTesting
  String checkAuthority(String authority) {
    if (authorityCheckerDisabled) {
      return authority;
    }
    return GrpcUtil.checkAuthority(authority);
  }

  /** Disable the check whether the authority is valid. */
  public ManagedChannelImplBuilder disableCheckAuthority() {
    authorityCheckerDisabled = true;
    return this;
  }

  /** Enable previously disabled authority check. */
  public ManagedChannelImplBuilder enableCheckAuthority() {
    authorityCheckerDisabled = false;
    return this;
  }

  @Override
  protected ManagedChannelImplBuilder addMetricSink(MetricSink metricSink) {
    metricSinks.add(checkNotNull(metricSink, "metric sink"));
    return this;
  }

  @Override
  public ManagedChannel build() {
    ClientTransportFactory clientTransportFactory =
        clientTransportFactoryBuilder.buildClientTransportFactory();
    ResolvedNameResolver resolvedResolver = getNameResolverProvider(
        target, nameResolverRegistry, clientTransportFactory.getSupportedSocketAddressTypes());
    return new ManagedChannelOrphanWrapper(new ManagedChannelImpl(
        this,
        clientTransportFactory,
        resolvedResolver.targetUri,
        resolvedResolver.provider,
        new ExponentialBackoffPolicy.Provider(),
        SharedResourcePool.forResource(GrpcUtil.SHARED_CHANNEL_EXECUTOR),
        GrpcUtil.STOPWATCH_SUPPLIER,
        getEffectiveInterceptors(resolvedResolver.targetUri.toString()),
        TimeProvider.SYSTEM_TIME_PROVIDER));
  }

  // Temporarily disable retry when stats or tracing is enabled to avoid breakage, until we know
  // what should be the desired behavior for retry + stats/tracing.
  // TODO(zdapeng): FIX IT
  @VisibleForTesting
  List<ClientInterceptor> getEffectiveInterceptors(String computedTarget) {
    List<ClientInterceptor> effectiveInterceptors = new ArrayList<>(this.interceptors);
    for (int i = 0; i < effectiveInterceptors.size(); i++) {
      if (!(effectiveInterceptors.get(i) instanceof InterceptorFactoryWrapper)) {
        continue;
      }
      InterceptorFactory factory =
          ((InterceptorFactoryWrapper) effectiveInterceptors.get(i)).factory;
      ClientInterceptor interceptor = factory.newInterceptor(computedTarget);
      if (interceptor == null) {
        throw new NullPointerException("Factory returned null interceptor: " + factory);
      }
      effectiveInterceptors.set(i, interceptor);
    }

    boolean disableImplicitCensus = InternalConfiguratorRegistry.wasSetConfiguratorsCalled();
    if (disableImplicitCensus) {
      return effectiveInterceptors;
    }
    if (statsEnabled) {
      ClientInterceptor statsInterceptor = null;

      if (GET_CLIENT_INTERCEPTOR_METHOD != null) {
        try {
          statsInterceptor =
            (ClientInterceptor) GET_CLIENT_INTERCEPTOR_METHOD
              .invoke(
                null,
                recordStartedRpcs,
                recordFinishedRpcs,
                recordRealTimeMetrics,
                recordRetryMetrics);
        } catch (IllegalAccessException e) {
          log.log(Level.FINE, "Unable to apply census stats", e);
        } catch (InvocationTargetException e) {
          log.log(Level.FINE, "Unable to apply census stats", e);
        }
      }

      if (statsInterceptor != null) {
        // First interceptor runs last (see ClientInterceptors.intercept()), so that no
        // other interceptor can override the tracer factory we set in CallOptions.
        effectiveInterceptors.add(0, statsInterceptor);
      }
    }
    if (tracingEnabled) {
      ClientInterceptor tracingInterceptor = null;
      try {
        Class<?> censusTracingAccessor =
            Class.forName("io.grpc.census.InternalCensusTracingAccessor");
        Method getClientInterceptroMethod =
            censusTracingAccessor.getDeclaredMethod("getClientInterceptor");
        tracingInterceptor = (ClientInterceptor) getClientInterceptroMethod.invoke(null);
      } catch (ClassNotFoundException e) {
        // Replace these separate catch statements with multicatch when Android min-API >= 19
        log.log(Level.FINE, "Unable to apply census stats", e);
      } catch (NoSuchMethodException e) {
        log.log(Level.FINE, "Unable to apply census stats", e);
      } catch (IllegalAccessException e) {
        log.log(Level.FINE, "Unable to apply census stats", e);
      } catch (InvocationTargetException e) {
        log.log(Level.FINE, "Unable to apply census stats", e);
      }
      if (tracingInterceptor != null) {
        effectiveInterceptors.add(0, tracingInterceptor);
      }
    }
    return effectiveInterceptors;
  }

  /**
   * Returns a default port to {@link NameResolver} for use in cases where the target string doesn't
   * include a port. The default implementation returns {@link GrpcUtil#DEFAULT_PORT_SSL}.
   */
  int getDefaultPort() {
    return channelBuilderDefaultPortProvider.getDefaultPort();
  }

  @VisibleForTesting
  static class ResolvedNameResolver {
    public final URI targetUri;
    public final NameResolverProvider provider;

    public ResolvedNameResolver(URI targetUri, NameResolverProvider provider) {
      this.targetUri = checkNotNull(targetUri, "targetUri");
      this.provider = checkNotNull(provider, "provider");
    }
  }

  @VisibleForTesting
  static ResolvedNameResolver getNameResolverProvider(
      String target, NameResolverRegistry nameResolverRegistry,
      Collection<Class<? extends SocketAddress>> channelTransportSocketAddressTypes) {
    // Finding a NameResolver. Try using the target string as the URI. If that fails, try prepending
    // "dns:///".
    NameResolverProvider provider = null;
    URI targetUri = null;
    StringBuilder uriSyntaxErrors = new StringBuilder();
    try {
      targetUri = new URI(target);
    } catch (URISyntaxException e) {
      // Can happen with ip addresses like "[::1]:1234" or 127.0.0.1:1234.
      uriSyntaxErrors.append(e.getMessage());
    }
    if (targetUri != null) {
      // For "localhost:8080" this would likely cause provider to be null, because "localhost" is
      // parsed as the scheme. Will hit the next case and try "dns:///localhost:8080".
      provider = nameResolverRegistry.getProviderForScheme(targetUri.getScheme());
    }

    if (provider == null && !URI_PATTERN.matcher(target).matches()) {
      // It doesn't look like a URI target. Maybe it's an authority string. Try with the default
      // scheme from the registry.
      try {
        targetUri = new URI(nameResolverRegistry.getDefaultScheme(), "", "/" + target, null);
      } catch (URISyntaxException e) {
        // Should not be possible.
        throw new IllegalArgumentException(e);
      }
      provider = nameResolverRegistry.getProviderForScheme(targetUri.getScheme());
    }

    if (provider == null) {
      throw new IllegalArgumentException(String.format(
          "Could not find a NameResolverProvider for %s%s",
          target, uriSyntaxErrors.length() > 0 ? " (" + uriSyntaxErrors + ")" : ""));
    }

    if (channelTransportSocketAddressTypes != null) {
      Collection<Class<? extends SocketAddress>> nameResolverSocketAddressTypes
          = provider.getProducedSocketAddressTypes();
      if (!channelTransportSocketAddressTypes.containsAll(nameResolverSocketAddressTypes)) {
        throw new IllegalArgumentException(String.format(
            "Address types of NameResolver '%s' for '%s' not supported by transport",
            targetUri.getScheme(), target));
      }
    }

    return new ResolvedNameResolver(targetUri, provider);
  }

  private static class DirectAddressNameResolverProvider extends NameResolverProvider {
    final SocketAddress address;
    final String authority;
    final Collection<Class<? extends SocketAddress>> producedSocketAddressTypes;

    DirectAddressNameResolverProvider(SocketAddress address, String authority) {
      this.address = address;
      this.authority = authority;
      this.producedSocketAddressTypes
          = Collections.singleton(address.getClass());
    }

    @Override
    public NameResolver newNameResolver(URI notUsedUri, NameResolver.Args args) {
      return new NameResolver() {
        @Override
        public String getServiceAuthority() {
          return authority;
        }

        @Override
        public void start(Listener2 listener) {
          listener.onResult2(
              ResolutionResult.newBuilder()
                  .setAddressesOrError(
                      StatusOr.fromValue(
                          Collections.singletonList(new EquivalentAddressGroup(address))))
                  .setAttributes(Attributes.EMPTY)
                  .build());
        }

        @Override
        public void shutdown() {}
      };
    }

    @Override
    public String getDefaultScheme() {
      return DIRECT_ADDRESS_SCHEME;
    }

    @Override
    protected boolean isAvailable() {
      return true;
    }

    @Override
    protected int priority() {
      return 5;
    }

    @Override
    public Collection<Class<? extends SocketAddress>> getProducedSocketAddressTypes() {
      return producedSocketAddressTypes;
    }
  }

  private static final class InterceptorFactoryWrapper implements ClientInterceptor {
    final InterceptorFactory factory;

    public InterceptorFactoryWrapper(InterceptorFactory factory) {
      this.factory = checkNotNull(factory, "factory");
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      throw new AssertionError("Should have been replaced with real instance");
    }
  }

  /**
   * Returns the internal offload executor pool for offloading tasks.
   */
  public ObjectPool<? extends Executor> getOffloadExecutorPool() {
    return this.offloadExecutorPool;
  }
}
