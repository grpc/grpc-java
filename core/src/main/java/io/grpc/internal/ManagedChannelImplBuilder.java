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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.Attributes;
import io.grpc.BinaryLog;
import io.grpc.CallCredentials;
import io.grpc.ClientInterceptor;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalChannelz;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.NameResolver;
import io.grpc.NameResolverRegistry;
import io.grpc.ProxyDetector;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Default managed channel builder, for usage in Transport implementations.
 */
public final class ManagedChannelImplBuilder
    extends ManagedChannelBuilder<ManagedChannelImplBuilder> {
  private static final String DIRECT_ADDRESS_SCHEME = "directaddress";

  private static final Logger log = Logger.getLogger(ManagedChannelImplBuilder.class.getName());

  public static ManagedChannelBuilder<?> forAddress(String name, int port) {
    throw new UnsupportedOperationException(
        "ClientTransportFactoryBuilder is required, use a constructor");
  }

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

  ObjectPool<? extends Executor> executorPool = DEFAULT_EXECUTOR_POOL;

  ObjectPool<? extends Executor> offloadExecutorPool = DEFAULT_EXECUTOR_POOL;

  private final List<ClientInterceptor> interceptors = new ArrayList<>();
  final NameResolverRegistry nameResolverRegistry = NameResolverRegistry.getDefaultRegistry();

  // Access via getter, which may perform authority override as needed
  private NameResolver.Factory nameResolverFactory = nameResolverRegistry.asFactory();

  final String target;
  @Nullable
  final CallCredentials callCredentials;

  @Nullable
  private final SocketAddress directServerAddress;

  @Nullable
  String userAgent;

  @Nullable
  private String authorityOverride;

  String defaultLbPolicy = GrpcUtil.DEFAULT_LB_POLICY;

  boolean fullStreamDecompression;

  DecompressorRegistry decompressorRegistry = DEFAULT_DECOMPRESSOR_REGISTRY;

  CompressorRegistry compressorRegistry = DEFAULT_COMPRESSOR_REGISTRY;

  long idleTimeoutMillis = IDLE_MODE_DEFAULT_TIMEOUT_MILLIS;

  int maxRetryAttempts = 5;
  int maxHedgedAttempts = 5;
  long retryBufferSize = DEFAULT_RETRY_BUFFER_SIZE_IN_BYTES;
  long perRpcBufferLimit = DEFAULT_PER_RPC_BUFFER_LIMIT_IN_BYTES;
  boolean retryEnabled = false; // TODO(zdapeng): default to true
  // Temporarily disable retry when stats or tracing is enabled to avoid breakage, until we know
  // what should be the desired behavior for retry + stats/tracing.
  // TODO(zdapeng): delete me
  boolean temporarilyDisableRetry;

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
  private boolean tracingEnabled = true;

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
    this(target, null, clientTransportFactoryBuilder, channelBuilderDefaultPortProvider);
  }

  /**
   * Creates a new managed channel builder with a target string, which can be either a valid {@link
   * io.grpc.NameResolver}-compliant URI, or an authority string. Transport implementors must
   * provide client transport factory builder, and may set custom channel default port provider.
   */
  public ManagedChannelImplBuilder(String target, @Nullable CallCredentials callCreds,
      ClientTransportFactoryBuilder clientTransportFactoryBuilder,
      @Nullable ChannelBuilderDefaultPortProvider channelBuilderDefaultPortProvider) {
    this.target = Preconditions.checkNotNull(target, "target");
    this.callCredentials = callCreds;
    this.clientTransportFactoryBuilder = Preconditions
        .checkNotNull(clientTransportFactoryBuilder, "clientTransportFactoryBuilder");
    this.directServerAddress = null;

    if (channelBuilderDefaultPortProvider != null) {
      this.channelBuilderDefaultPortProvider = channelBuilderDefaultPortProvider;
    } else {
      this.channelBuilderDefaultPortProvider = new ManagedChannelDefaultPortProvider();
    }
  }

  /**
   * Returns a target string for the SocketAddress. It is only used as a placeholder, because
   * DirectAddressNameResolverFactory will not actually try to use it. However, it must be a valid
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
    this.target = makeTargetStringForDirectAddress(directServerAddress);
    this.callCredentials = null;
    this.clientTransportFactoryBuilder = Preconditions
        .checkNotNull(clientTransportFactoryBuilder, "clientTransportFactoryBuilder");
    this.directServerAddress = directServerAddress;
    this.nameResolverFactory = new DirectAddressNameResolverFactory(directServerAddress, authority);

    if (channelBuilderDefaultPortProvider != null) {
      this.channelBuilderDefaultPortProvider = channelBuilderDefaultPortProvider;
    } else {
      this.channelBuilderDefaultPortProvider = new ManagedChannelDefaultPortProvider();
    }
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

  @Deprecated
  @Override
  public ManagedChannelImplBuilder nameResolverFactory(NameResolver.Factory resolverFactory) {
    Preconditions.checkState(directServerAddress == null,
        "directServerAddress is set (%s), which forbids the use of NameResolverFactory",
        directServerAddress);
    if (resolverFactory != null) {
      this.nameResolverFactory = resolverFactory;
    } else {
      this.nameResolverFactory = nameResolverRegistry.asFactory();
    }
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
  public ManagedChannelImplBuilder enableFullStreamDecompression() {
    this.fullStreamDecompression = true;
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

  @Nullable
  @VisibleForTesting
  String getOverrideAuthority() {
    return authorityOverride;
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
    statsEnabled = false;
    tracingEnabled = false;
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

  /**
   * Disable or enable tracing features.  Enabled by default.
   *
   * <p>For the current release, calling {@code setTracingEnabled(true)} may have a side effect that
   * disables retry.
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
  public ManagedChannel build() {
    return new ManagedChannelOrphanWrapper(new ManagedChannelImpl(
        this,
        clientTransportFactoryBuilder.buildClientTransportFactory(),
        new ExponentialBackoffPolicy.Provider(),
        SharedResourcePool.forResource(GrpcUtil.SHARED_CHANNEL_EXECUTOR),
        GrpcUtil.STOPWATCH_SUPPLIER,
        getEffectiveInterceptors(),
        TimeProvider.SYSTEM_TIME_PROVIDER));
  }

  // Temporarily disable retry when stats or tracing is enabled to avoid breakage, until we know
  // what should be the desired behavior for retry + stats/tracing.
  // TODO(zdapeng): FIX IT
  @VisibleForTesting
  List<ClientInterceptor> getEffectiveInterceptors() {
    List<ClientInterceptor> effectiveInterceptors =
        new ArrayList<>(this.interceptors);
    temporarilyDisableRetry = false;
    if (statsEnabled) {
      temporarilyDisableRetry = true;
      ClientInterceptor statsInterceptor = null;
      try {
        Class<?> censusStatsAccessor =
            Class.forName("io.grpc.census.InternalCensusStatsAccessor");
        Method getClientInterceptorMethod =
            censusStatsAccessor.getDeclaredMethod(
                "getClientInterceptor",
                boolean.class,
                boolean.class,
                boolean.class);
        statsInterceptor =
            (ClientInterceptor) getClientInterceptorMethod
                .invoke(
                    null,
                    recordStartedRpcs,
                    recordFinishedRpcs,
                    recordRealTimeMetrics);
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
      if (statsInterceptor != null) {
        // First interceptor runs last (see ClientInterceptors.intercept()), so that no
        // other interceptor can override the tracer factory we set in CallOptions.
        effectiveInterceptors.add(0, statsInterceptor);
      }
    }
    if (tracingEnabled) {
      temporarilyDisableRetry = true;
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

  /**
   * Returns a {@link NameResolver.Factory} for the channel.
   */
  NameResolver.Factory getNameResolverFactory() {
    if (authorityOverride == null) {
      return nameResolverFactory;
    } else {
      return new OverrideAuthorityNameResolverFactory(nameResolverFactory, authorityOverride);
    }
  }

  private static class DirectAddressNameResolverFactory extends NameResolver.Factory {
    final SocketAddress address;
    final String authority;

    DirectAddressNameResolverFactory(SocketAddress address, String authority) {
      this.address = address;
      this.authority = authority;
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
          listener.onResult(
              ResolutionResult.newBuilder()
                  .setAddresses(Collections.singletonList(new EquivalentAddressGroup(address)))
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
  }

  /**
   * Returns the internal offload executor pool for offloading tasks.
   */
  public ObjectPool<? extends Executor> getOffloadExecutorPool() {
    return this.offloadExecutorPool;
  }
}
