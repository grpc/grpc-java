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
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.BinaryLog;
import io.grpc.BindableService;
import io.grpc.CompressorRegistry;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.DecompressorRegistry;
import io.grpc.HandlerRegistry;
import io.grpc.InternalChannelz;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.ServerTransportFilter;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Default builder for {@link io.grpc.Server} instances, for usage in Transport implementations.
 */
public final class ServerImplBuilder extends ServerBuilder<ServerImplBuilder> {

  private static final Logger log = Logger.getLogger(ServerImplBuilder.class.getName());

  public static ServerBuilder<?> forPort(int port) {
    throw new UnsupportedOperationException(
        "ClientTransportServersBuilder is required, use a constructor");
  }

  // defaults
  private static final ObjectPool<? extends Executor> DEFAULT_EXECUTOR_POOL =
      SharedResourcePool.forResource(GrpcUtil.SHARED_CHANNEL_EXECUTOR);
  private static final HandlerRegistry DEFAULT_FALLBACK_REGISTRY = new DefaultFallbackRegistry();
  private static final DecompressorRegistry DEFAULT_DECOMPRESSOR_REGISTRY =
      DecompressorRegistry.getDefaultInstance();
  private static final CompressorRegistry DEFAULT_COMPRESSOR_REGISTRY =
      CompressorRegistry.getDefaultInstance();
  private static final long DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(120);

  // mutable state
  final InternalHandlerRegistry.Builder registryBuilder =
      new InternalHandlerRegistry.Builder();
  final List<ServerTransportFilter> transportFilters = new ArrayList<>();
  final List<ServerInterceptor> interceptors = new ArrayList<>();
  private final List<ServerStreamTracer.Factory> streamTracerFactories = new ArrayList<>();
  private final ClientTransportServersBuilder clientTransportServersBuilder;
  HandlerRegistry fallbackRegistry = DEFAULT_FALLBACK_REGISTRY;
  ObjectPool<? extends Executor> executorPool = DEFAULT_EXECUTOR_POOL;
  DecompressorRegistry decompressorRegistry = DEFAULT_DECOMPRESSOR_REGISTRY;
  CompressorRegistry compressorRegistry = DEFAULT_COMPRESSOR_REGISTRY;
  long handshakeTimeoutMillis = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS;
  Deadline.Ticker ticker = Deadline.getSystemTicker();
  private boolean statsEnabled = true;
  private boolean recordStartedRpcs = true;
  private boolean recordFinishedRpcs = true;
  private boolean recordRealTimeMetrics = false;
  private boolean tracingEnabled = true;
  @Nullable BinaryLog binlog;
  InternalChannelz channelz = InternalChannelz.instance();
  CallTracer.Factory callTracerFactory = CallTracer.getDefaultFactory();

  /**
   * An interface to provide to provide transport specific information for the server. This method
   * is meant for Transport implementors and should not be used by normal users.
   */
  public interface ClientTransportServersBuilder {
    InternalServer buildClientTransportServers(
        List<? extends ServerStreamTracer.Factory> streamTracerFactories);
  }

  /**
   * Creates a new server builder with given transport servers provider.
   */
  public ServerImplBuilder(ClientTransportServersBuilder clientTransportServersBuilder) {
    this.clientTransportServersBuilder = checkNotNull(clientTransportServersBuilder,
        "clientTransportServersBuilder");
  }

  @Override
  public ServerImplBuilder directExecutor() {
    return executor(MoreExecutors.directExecutor());
  }

  @Override
  public ServerImplBuilder executor(@Nullable Executor executor) {
    this.executorPool = executor != null ? new FixedObjectPool<>(executor) : DEFAULT_EXECUTOR_POOL;
    return this;
  }

  @Override
  public ServerImplBuilder addService(ServerServiceDefinition service) {
    registryBuilder.addService(checkNotNull(service, "service"));
    return this;
  }

  @Override
  public ServerImplBuilder addService(BindableService bindableService) {
    return addService(checkNotNull(bindableService, "bindableService").bindService());
  }

  @Override
  public ServerImplBuilder addTransportFilter(ServerTransportFilter filter) {
    transportFilters.add(checkNotNull(filter, "filter"));
    return this;
  }

  @Override
  public ServerImplBuilder intercept(ServerInterceptor interceptor) {
    interceptors.add(checkNotNull(interceptor, "interceptor"));
    return this;
  }

  @Override
  public ServerImplBuilder addStreamTracerFactory(ServerStreamTracer.Factory factory) {
    streamTracerFactories.add(checkNotNull(factory, "factory"));
    return this;
  }

  @Override
  public ServerImplBuilder fallbackHandlerRegistry(@Nullable HandlerRegistry registry) {
    this.fallbackRegistry = registry != null ? registry : DEFAULT_FALLBACK_REGISTRY;
    return this;
  }

  @Override
  public ServerImplBuilder decompressorRegistry(@Nullable DecompressorRegistry registry) {
    this.decompressorRegistry = registry != null ? registry : DEFAULT_DECOMPRESSOR_REGISTRY;
    return this;
  }

  @Override
  public ServerImplBuilder compressorRegistry(@Nullable CompressorRegistry registry) {
    this.compressorRegistry = registry != null ? registry : DEFAULT_COMPRESSOR_REGISTRY;
    return this;
  }

  @Override
  public ServerImplBuilder handshakeTimeout(long timeout, TimeUnit unit) {
    checkArgument(timeout > 0, "handshake timeout is %s, but must be positive", timeout);
    this.handshakeTimeoutMillis = checkNotNull(unit, "unit").toMillis(timeout);
    return this;
  }

  @Override
  public ServerImplBuilder setBinaryLog(@Nullable BinaryLog binaryLog) {
    this.binlog = binaryLog;
    return this;
  }

  /**
   * Disable or enable stats features.  Enabled by default.
   */
  public void setStatsEnabled(boolean value) {
    this.statsEnabled = value;
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
   */
  public void setTracingEnabled(boolean value) {
    tracingEnabled = value;
  }

  /**
   * Sets a custom deadline ticker.  This should only be called from InProcessServerBuilder.
   */
  public void setDeadlineTicker(Deadline.Ticker ticker) {
    this.ticker = checkNotNull(ticker, "ticker");
  }

  @Override
  public Server build() {
    return new ServerImpl(this,
        clientTransportServersBuilder.buildClientTransportServers(getTracerFactories()),
        Context.ROOT);
  }

  @VisibleForTesting
  List<? extends ServerStreamTracer.Factory> getTracerFactories() {
    ArrayList<ServerStreamTracer.Factory> tracerFactories = new ArrayList<>();
    if (statsEnabled) {
      ServerStreamTracer.Factory censusStatsTracerFactory = null;
      try {
        Class<?> censusStatsAccessor =
            Class.forName("io.grpc.census.InternalCensusStatsAccessor");
        Method getServerStreamTracerFactoryMethod =
            censusStatsAccessor.getDeclaredMethod(
                "getServerStreamTracerFactory",
                boolean.class,
                boolean.class,
                boolean.class);
        censusStatsTracerFactory =
            (ServerStreamTracer.Factory) getServerStreamTracerFactoryMethod
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
      if (censusStatsTracerFactory != null) {
        tracerFactories.add(censusStatsTracerFactory);
      }
    }
    if (tracingEnabled) {
      ServerStreamTracer.Factory tracingStreamTracerFactory = null;
      try {
        Class<?> censusTracingAccessor =
            Class.forName("io.grpc.census.InternalCensusTracingAccessor");
        Method getServerStreamTracerFactoryMethod =
            censusTracingAccessor.getDeclaredMethod("getServerStreamTracerFactory");
        tracingStreamTracerFactory =
            (ServerStreamTracer.Factory) getServerStreamTracerFactoryMethod.invoke(null);
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
      if (tracingStreamTracerFactory != null) {
        tracerFactories.add(tracingStreamTracerFactory);
      }
    }
    tracerFactories.addAll(streamTracerFactories);
    tracerFactories.trimToSize();
    return Collections.unmodifiableList(tracerFactories);
  }

  public InternalChannelz getChannelz() {
    return channelz;
  }

  private static final class DefaultFallbackRegistry extends HandlerRegistry {
    @Override
    public List<ServerServiceDefinition> getServices() {
      return Collections.emptyList();
    }

    @Nullable
    @Override
    public ServerMethodDefinition<?, ?> lookupMethod(
        String methodName, @Nullable String authority) {
      return null;
    }
  }

  /**
   * Returns the internal ExecutorPool for offloading tasks.
   */
  public ObjectPool<? extends Executor> getExecutorPool() {
    return this.executorPool;
  }

  @Override
  public ServerImplBuilder useTransportSecurity(File certChain, File privateKey) {
    throw new UnsupportedOperationException("TLS not supported in ServerImplBuilder");
  }
}
