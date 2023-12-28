/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.xds.Bootstrapper.XDSTP_SCHEME;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.InternalServerInterceptors;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.xds.EnvoyServerProtoData.FilterChain;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.Filter.NamedFilterConfig;
import io.grpc.xds.Filter.ServerInterceptorBuilder;
import io.grpc.xds.FilterChainMatchingProtocolNegotiators.FilterChainMatchingHandler.FilterChainSelector;
import io.grpc.xds.ThreadSafeRandom.ThreadSafeRandomImpl;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.XdsClient.ResourceWatcher;
import io.grpc.xds.XdsListenerResource.LdsUpdate;
import io.grpc.xds.XdsNameResolverProvider.XdsClientPoolFactory;
import io.grpc.xds.XdsRouteConfigureResource.RdsUpdate;
import io.grpc.xds.XdsServerBuilder.XdsServingStatusListener;
import io.grpc.xds.internal.security.SslContextProviderSupplier;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

final class XdsServerWrapper extends Server {
  private static final Logger logger = Logger.getLogger(XdsServerWrapper.class.getName());

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          logger.log(Level.SEVERE, "Exception!" + e);
          // TODO(chengyuanzhang): implement cleanup.
        }
      });

  public static final Attributes.Key<AtomicReference<ServerRoutingConfig>>
      ATTR_SERVER_ROUTING_CONFIG =
      Attributes.Key.create("io.grpc.xds.ServerWrapper.serverRoutingConfig");

  @VisibleForTesting
  static final long RETRY_DELAY_NANOS = TimeUnit.MINUTES.toNanos(1);
  private final String listenerAddress;
  private final ServerBuilder<?> delegateBuilder;
  private boolean sharedTimeService;
  private final ScheduledExecutorService timeService;
  private final FilterRegistry filterRegistry;
  private final ThreadSafeRandom random = ThreadSafeRandomImpl.instance;
  private final XdsClientPoolFactory xdsClientPoolFactory;
  private final XdsServingStatusListener listener;
  private final FilterChainSelectorManager filterChainSelectorManager;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean shutdown = new AtomicBoolean(false);
  private boolean isServing;
  private final CountDownLatch internalTerminationLatch = new CountDownLatch(1);
  private final SettableFuture<Exception> initialStartFuture = SettableFuture.create();
  private boolean initialStarted;
  private ScheduledHandle restartTimer;
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private DiscoveryState discoveryState;
  private volatile Server delegate;

  XdsServerWrapper(
      String listenerAddress,
      ServerBuilder<?> delegateBuilder,
      XdsServingStatusListener listener,
      FilterChainSelectorManager filterChainSelectorManager,
      XdsClientPoolFactory xdsClientPoolFactory,
      FilterRegistry filterRegistry) {
    this(listenerAddress, delegateBuilder, listener, filterChainSelectorManager,
        xdsClientPoolFactory, filterRegistry, SharedResourceHolder.get(GrpcUtil.TIMER_SERVICE));
    sharedTimeService = true;
  }

  @VisibleForTesting
  XdsServerWrapper(
          String listenerAddress,
          ServerBuilder<?> delegateBuilder,
          XdsServingStatusListener listener,
          FilterChainSelectorManager filterChainSelectorManager,
          XdsClientPoolFactory xdsClientPoolFactory,
          FilterRegistry filterRegistry,
          ScheduledExecutorService timeService) {
    this.listenerAddress = checkNotNull(listenerAddress, "listenerAddress");
    this.delegateBuilder = checkNotNull(delegateBuilder, "delegateBuilder");
    this.delegateBuilder.intercept(new ConfigApplyingInterceptor());
    this.listener = checkNotNull(listener, "listener");
    this.filterChainSelectorManager
        = checkNotNull(filterChainSelectorManager, "filterChainSelectorManager");
    this.xdsClientPoolFactory = checkNotNull(xdsClientPoolFactory, "xdsClientPoolFactory");
    this.timeService = checkNotNull(timeService, "timeService");
    this.filterRegistry = checkNotNull(filterRegistry,"filterRegistry");
    this.delegate = delegateBuilder.build();
  }

  @Override
  public Server start() throws IOException {
    checkState(started.compareAndSet(false, true), "Already started");
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        internalStart();
      }
    });
    Exception exception;
    try {
      exception = initialStartFuture.get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
    if (exception != null) {
      throw (exception instanceof IOException) ? (IOException) exception :
              new IOException(exception);
    }
    return this;
  }

  private void internalStart() {
    try {
      xdsClientPool = xdsClientPoolFactory.getOrCreate();
    } catch (Exception e) {
      StatusException statusException = Status.UNAVAILABLE.withDescription(
              "Failed to initialize xDS").withCause(e).asException();
      listener.onNotServing(statusException);
      initialStartFuture.set(statusException);
      return;
    }
    xdsClient = xdsClientPool.getObject();
    String listenerTemplate = xdsClient.getBootstrapInfo().serverListenerResourceNameTemplate();
    if (listenerTemplate == null) {
      StatusException statusException =
          Status.UNAVAILABLE.withDescription(
              "Can only support xDS v3 with listener resource name template").asException();
      listener.onNotServing(statusException);
      initialStartFuture.set(statusException);
      xdsClient = xdsClientPool.returnObject(xdsClient);
      return;
    }
    String replacement = listenerAddress;
    if (listenerTemplate.startsWith(XDSTP_SCHEME)) {
      replacement = XdsClient.percentEncodePath(replacement);
    }
    discoveryState = new DiscoveryState(listenerTemplate.replaceAll("%s", replacement));
  }

  @Override
  public Server shutdown() {
    if (!shutdown.compareAndSet(false, true)) {
      return this;
    }
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        if (!delegate.isShutdown()) {
          delegate.shutdown();
        }
        internalShutdown();
      }
    });
    return this;
  }

  @Override
  public Server shutdownNow() {
    if (!shutdown.compareAndSet(false, true)) {
      return this;
    }
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        if (!delegate.isShutdown()) {
          delegate.shutdownNow();
        }
        internalShutdown();
        initialStartFuture.set(new IOException("server is forcefully shut down"));
      }
    });
    return this;
  }

  // Must run in SynchronizationContext
  private void internalShutdown() {
    logger.log(Level.FINER, "Shutting down XdsServerWrapper");
    if (discoveryState != null) {
      discoveryState.shutdown();
    }
    if (xdsClient != null) {
      xdsClient = xdsClientPool.returnObject(xdsClient);
    }
    if (restartTimer != null) {
      restartTimer.cancel();
    }
    if (sharedTimeService) {
      SharedResourceHolder.release(GrpcUtil.TIMER_SERVICE, timeService);
    }
    isServing = false;
    internalTerminationLatch.countDown();
  }

  @Override
  public boolean isShutdown() {
    return shutdown.get();
  }

  @Override
  public boolean isTerminated() {
    return internalTerminationLatch.getCount() == 0 && delegate.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    long startTime = System.nanoTime();
    if (!internalTerminationLatch.await(timeout, unit)) {
      return false;
    }
    long remainingTime = unit.toNanos(timeout) - (System.nanoTime() - startTime);
    return delegate.awaitTermination(remainingTime, TimeUnit.NANOSECONDS);
  }

  @Override
  public void awaitTermination() throws InterruptedException {
    internalTerminationLatch.await();
    delegate.awaitTermination();
  }

  @Override
  public int getPort() {
    return delegate.getPort();
  }

  @Override
  public List<? extends SocketAddress> getListenSockets() {
    return delegate.getListenSockets();
  }

  @Override
  public List<ServerServiceDefinition> getServices() {
    return delegate.getServices();
  }

  @Override
  public List<ServerServiceDefinition> getImmutableServices() {
    return delegate.getImmutableServices();
  }

  @Override
  public List<ServerServiceDefinition> getMutableServices() {
    return delegate.getMutableServices();
  }

  // Must run in SynchronizationContext
  private void startDelegateServer() {
    if (restartTimer != null && restartTimer.isPending()) {
      return;
    }
    if (isServing) {
      return;
    }
    if (delegate.isShutdown()) {
      delegate = delegateBuilder.build();
    }
    try {
      delegate.start();
      listener.onServing();
      isServing = true;
      if (!initialStarted) {
        initialStarted = true;
        initialStartFuture.set(null);
      }
      logger.log(Level.FINER, "Delegate server started.");
    } catch (IOException e) {
      logger.log(Level.FINE, "Fail to start delegate server: {0}", e);
      if (!initialStarted) {
        initialStarted = true;
        initialStartFuture.set(e);
      } else {
        listener.onNotServing(e);
      }
      restartTimer = syncContext.schedule(
        new RestartTask(), RETRY_DELAY_NANOS, TimeUnit.NANOSECONDS, timeService);
    }
  }

  private final class RestartTask implements Runnable {
    @Override
    public void run() {
      startDelegateServer();
    }
  }

  private final class DiscoveryState implements ResourceWatcher<LdsUpdate> {
    private final String resourceName;
    // RDS resource name is the key.
    private final Map<String, RouteDiscoveryState> routeDiscoveryStates = new HashMap<>();
    // Track pending RDS resources using rds name.
    private final Set<String> pendingRds = new HashSet<>();
    // Most recently discovered filter chains.
    private List<FilterChain> filterChains = new ArrayList<>();
    // Most recently discovered default filter chain.
    @Nullable
    private FilterChain defaultFilterChain;
    private boolean stopped;
    private final Map<FilterChain, AtomicReference<ServerRoutingConfig>> savedRdsRoutingConfigRef 
        = new HashMap<>();
    private final ServerInterceptor noopInterceptor = new ServerInterceptor() {
      @Override
      public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
          Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        return next.startCall(call, headers);
      }
    };

    private DiscoveryState(String resourceName) {
      this.resourceName = checkNotNull(resourceName, "resourceName");
      xdsClient.watchXdsResource(XdsListenerResource.getInstance(), resourceName, this);
    }

    @Override
    public void onChanged(final LdsUpdate update) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped) {
            return;
          }
          logger.log(Level.FINEST, "Received Lds update {0}", update);
          checkNotNull(update.listener(), "update");
          if (!pendingRds.isEmpty()) {
            // filter chain state has not yet been applied to filterChainSelectorManager and there
            // are two sets of sslContextProviderSuppliers, so we release the old ones.
            releaseSuppliersInFlight();
            pendingRds.clear();
          }
          filterChains = update.listener().filterChains();
          defaultFilterChain = update.listener().defaultFilterChain();
          List<FilterChain> allFilterChains = filterChains;
          if (defaultFilterChain != null) {
            allFilterChains = new ArrayList<>(filterChains);
            allFilterChains.add(defaultFilterChain);
          }
          Set<String> allRds = new HashSet<>();
          for (FilterChain filterChain : allFilterChains) {
            HttpConnectionManager hcm = filterChain.httpConnectionManager();
            if (hcm.virtualHosts() == null) {
              RouteDiscoveryState rdsState = routeDiscoveryStates.get(hcm.rdsName());
              if (rdsState == null) {
                rdsState = new RouteDiscoveryState(hcm.rdsName());
                routeDiscoveryStates.put(hcm.rdsName(), rdsState);
                xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),
                    hcm.rdsName(), rdsState);
              }
              if (rdsState.isPending) {
                pendingRds.add(hcm.rdsName());
              }
              allRds.add(hcm.rdsName());
            }
          }
          for (Map.Entry<String, RouteDiscoveryState> entry: routeDiscoveryStates.entrySet()) {
            if (!allRds.contains(entry.getKey())) {
              xdsClient.cancelXdsResourceWatch(XdsRouteConfigureResource.getInstance(),
                  entry.getKey(), entry.getValue());
            }
          }
          routeDiscoveryStates.keySet().retainAll(allRds);
          if (pendingRds.isEmpty()) {
            updateSelector();
          }
        }
      });
    }

    @Override
    public void onResourceDoesNotExist(final String resourceName) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped) {
            return;
          }
          StatusException statusException = Status.UNAVAILABLE.withDescription(
                  "Listener " + resourceName + " unavailable").asException();
          handleConfigNotFound(statusException);
        }
      });
    }

    @Override
    public void onError(final Status error) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped) {
            return;
          }
          logger.log(Level.FINE, "Error from XdsClient", error);
          if (!isServing) {
            listener.onNotServing(error.asException());
          }
        }
      });
    }

    private void shutdown() {
      stopped = true;
      cleanUpRouteDiscoveryStates();
      logger.log(Level.FINE, "Stop watching LDS resource {0}", resourceName);
      xdsClient.cancelXdsResourceWatch(XdsListenerResource.getInstance(), resourceName, this);
      List<SslContextProviderSupplier> toRelease = getSuppliersInUse();
      filterChainSelectorManager.updateSelector(FilterChainSelector.NO_FILTER_CHAIN);
      for (SslContextProviderSupplier s: toRelease) {
        s.close();
      }
      releaseSuppliersInFlight();
    }

    private void updateSelector() {
      Map<FilterChain, AtomicReference<ServerRoutingConfig>> filterChainRouting = new HashMap<>();
      savedRdsRoutingConfigRef.clear();
      for (FilterChain filterChain: filterChains) {
        filterChainRouting.put(filterChain, generateRoutingConfig(filterChain));
      }
      FilterChainSelector selector = new FilterChainSelector(
          Collections.unmodifiableMap(filterChainRouting),
          defaultFilterChain == null ? null : defaultFilterChain.sslContextProviderSupplier(),
          defaultFilterChain == null ? new AtomicReference<ServerRoutingConfig>() :
              generateRoutingConfig(defaultFilterChain));
      List<SslContextProviderSupplier> toRelease = getSuppliersInUse();
      logger.log(Level.FINEST, "Updating selector {0}", selector);
      filterChainSelectorManager.updateSelector(selector);
      for (SslContextProviderSupplier e: toRelease) {
        e.close();
      }
      startDelegateServer();
    }

    private AtomicReference<ServerRoutingConfig> generateRoutingConfig(FilterChain filterChain) {
      HttpConnectionManager hcm = filterChain.httpConnectionManager();
      if (hcm.virtualHosts() != null) {
        ImmutableMap<Route, ServerInterceptor> interceptors = generatePerRouteInterceptors(
                hcm.httpFilterConfigs(), hcm.virtualHosts());
        return new AtomicReference<>(ServerRoutingConfig.create(hcm.virtualHosts(),interceptors));
      } else {
        RouteDiscoveryState rds = routeDiscoveryStates.get(hcm.rdsName());
        checkNotNull(rds, "rds");
        AtomicReference<ServerRoutingConfig> serverRoutingConfigRef = new AtomicReference<>();
        if (rds.savedVirtualHosts != null) {
          ImmutableMap<Route, ServerInterceptor> interceptors = generatePerRouteInterceptors(
              hcm.httpFilterConfigs(), rds.savedVirtualHosts);
          ServerRoutingConfig serverRoutingConfig =
              ServerRoutingConfig.create(rds.savedVirtualHosts, interceptors);
          serverRoutingConfigRef.set(serverRoutingConfig);
        } else {
          serverRoutingConfigRef.set(ServerRoutingConfig.FAILING_ROUTING_CONFIG);
        }
        savedRdsRoutingConfigRef.put(filterChain, serverRoutingConfigRef);
        return serverRoutingConfigRef;
      }
    }

    private ImmutableMap<Route, ServerInterceptor> generatePerRouteInterceptors(
        List<NamedFilterConfig> namedFilterConfigs, List<VirtualHost> virtualHosts) {
      ImmutableMap.Builder<Route, ServerInterceptor> perRouteInterceptors =
          new ImmutableMap.Builder<>();
      for (VirtualHost virtualHost : virtualHosts) {
        for (Route route : virtualHost.routes()) {
          List<ServerInterceptor> filterInterceptors = new ArrayList<>();
          Map<String, FilterConfig> selectedOverrideConfigs =
              new HashMap<>(virtualHost.filterConfigOverrides());
          selectedOverrideConfigs.putAll(route.filterConfigOverrides());
          if (namedFilterConfigs != null) {
            for (NamedFilterConfig namedFilterConfig : namedFilterConfigs) {
              FilterConfig filterConfig = namedFilterConfig.filterConfig;
              Filter filter = filterRegistry.get(filterConfig.typeUrl());
              if (filter instanceof ServerInterceptorBuilder) {
                ServerInterceptor interceptor =
                    ((ServerInterceptorBuilder) filter).buildServerInterceptor(
                        filterConfig, selectedOverrideConfigs.get(namedFilterConfig.name));
                if (interceptor != null) {
                  filterInterceptors.add(interceptor);
                }
              } else {
                logger.log(Level.WARNING, "HttpFilterConfig(type URL: "
                    + filterConfig.typeUrl() + ") is not supported on server-side. "
                    + "Probably a bug at ClientXdsClient verification.");
              }
            }
          }
          ServerInterceptor interceptor = combineInterceptors(filterInterceptors);
          perRouteInterceptors.put(route, interceptor);
        }
      }
      return perRouteInterceptors.buildOrThrow();
    }

    private ServerInterceptor combineInterceptors(final List<ServerInterceptor> interceptors) {
      if (interceptors.isEmpty()) {
        return noopInterceptor;
      }
      if (interceptors.size() == 1) {
        return interceptors.get(0);
      }
      return new ServerInterceptor() {
        @Override
        public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
          // intercept forward
          for (int i = interceptors.size() - 1; i >= 0; i--) {
            next = InternalServerInterceptors.interceptCallHandlerCreate(
                interceptors.get(i), next);
          }
          return next.startCall(call, headers);
        }
      };
    }

    private void handleConfigNotFound(StatusException exception) {
      cleanUpRouteDiscoveryStates();
      List<SslContextProviderSupplier> toRelease = getSuppliersInUse();
      filterChainSelectorManager.updateSelector(FilterChainSelector.NO_FILTER_CHAIN);
      for (SslContextProviderSupplier s: toRelease) {
        s.close();
      }
      if (restartTimer != null) {
        restartTimer.cancel();
      }
      if (!delegate.isShutdown()) {
        delegate.shutdown();  // let in-progress calls finish
      }
      isServing = false;
      listener.onNotServing(exception);
    }

    private void cleanUpRouteDiscoveryStates() {
      for (RouteDiscoveryState rdsState : routeDiscoveryStates.values()) {
        String rdsName = rdsState.resourceName;
        logger.log(Level.FINE, "Stop watching RDS resource {0}", rdsName);
        xdsClient.cancelXdsResourceWatch(XdsRouteConfigureResource.getInstance(), rdsName,
            rdsState);
      }
      routeDiscoveryStates.clear();
      savedRdsRoutingConfigRef.clear();
    }

    private List<SslContextProviderSupplier> getSuppliersInUse() {
      List<SslContextProviderSupplier> toRelease = new ArrayList<>();
      FilterChainSelector selector = filterChainSelectorManager.getSelectorToUpdateSelector();
      if (selector != null) {
        for (FilterChain f: selector.getRoutingConfigs().keySet()) {
          if (f.sslContextProviderSupplier() != null) {
            toRelease.add(f.sslContextProviderSupplier());
          }
        }
        SslContextProviderSupplier defaultSupplier =
                selector.getDefaultSslContextProviderSupplier();
        if (defaultSupplier != null) {
          toRelease.add(defaultSupplier);
        }
      }
      return toRelease;
    }

    private void releaseSuppliersInFlight() {
      SslContextProviderSupplier supplier;
      for (FilterChain filterChain : filterChains) {
        supplier = filterChain.sslContextProviderSupplier();
        if (supplier != null) {
          supplier.close();
        }
      }
      if (defaultFilterChain != null
              && (supplier = defaultFilterChain.sslContextProviderSupplier()) != null) {
        supplier.close();
      }
    }

    private final class RouteDiscoveryState implements ResourceWatcher<RdsUpdate> {
      private final String resourceName;
      private ImmutableList<VirtualHost> savedVirtualHosts;
      private boolean isPending = true;

      private RouteDiscoveryState(String resourceName) {
        this.resourceName = checkNotNull(resourceName, "resourceName");
      }

      @Override
      public void onChanged(final RdsUpdate update) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (!routeDiscoveryStates.containsKey(resourceName)) {
              return;
            }
            if (savedVirtualHosts == null && !isPending) {
              logger.log(Level.WARNING, "Received valid Rds {0} configuration.", resourceName);
            }
            savedVirtualHosts = ImmutableList.copyOf(update.virtualHosts);
            updateRdsRoutingConfig();
            maybeUpdateSelector();
          }
        });
      }

      @Override
      public void onResourceDoesNotExist(final String resourceName) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (!routeDiscoveryStates.containsKey(resourceName)) {
              return;
            }
            logger.log(Level.WARNING, "Rds {0} unavailable", resourceName);
            savedVirtualHosts = null;
            updateRdsRoutingConfig();
            maybeUpdateSelector();
          }
        });
      }

      @Override
      public void onError(final Status error) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (!routeDiscoveryStates.containsKey(resourceName)) {
              return;
            }
            logger.log(Level.WARNING, "Error loading RDS resource {0} from XdsClient: {1}.",
                    new Object[]{resourceName, error});
            maybeUpdateSelector();
          }
        });
      }

      private void updateRdsRoutingConfig() {
        for (FilterChain filterChain : savedRdsRoutingConfigRef.keySet()) {
          if (resourceName.equals(filterChain.httpConnectionManager().rdsName())) {
            ServerRoutingConfig updatedRoutingConfig;
            if (savedVirtualHosts == null) {
              updatedRoutingConfig = ServerRoutingConfig.FAILING_ROUTING_CONFIG;
            } else {
              ImmutableMap<Route, ServerInterceptor> updatedInterceptors =
                  generatePerRouteInterceptors(
                      filterChain.httpConnectionManager().httpFilterConfigs(),
                      savedVirtualHosts);
              updatedRoutingConfig = ServerRoutingConfig.create(savedVirtualHosts,
                  updatedInterceptors);
            }
            logger.log(Level.FINEST, "Updating filter chain {0} rds routing config: {1}",
                new Object[]{filterChain.name(), updatedRoutingConfig});
            savedRdsRoutingConfigRef.get(filterChain).set(updatedRoutingConfig);
          }
        }
      }

      // Update the selector to use the most recently updated configs only after all rds have been
      // discovered for the first time. Later changes on rds will be applied through virtual host
      // list atomic ref.
      private void maybeUpdateSelector() {
        isPending = false;
        boolean isLastPending = pendingRds.remove(resourceName) && pendingRds.isEmpty();
        if (isLastPending) {
          updateSelector();
        }
      }
    }
  }

  @VisibleForTesting
  final class ConfigApplyingInterceptor implements ServerInterceptor {
    private final ServerInterceptor noopInterceptor = new ServerInterceptor() {
      @Override
      public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
          Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        return next.startCall(call, headers);
      }
    };

    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
        Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      AtomicReference<ServerRoutingConfig> routingConfigRef =
          call.getAttributes().get(ATTR_SERVER_ROUTING_CONFIG);
      ServerRoutingConfig routingConfig = routingConfigRef == null ? null :
          routingConfigRef.get();
      if (routingConfig == null || routingConfig == ServerRoutingConfig.FAILING_ROUTING_CONFIG) {
        String errorMsg = "Missing or broken xDS routing config: RDS config unavailable.";
        call.close(Status.UNAVAILABLE.withDescription(errorMsg), new Metadata());
        return new Listener<ReqT>() {};
      }
      List<VirtualHost> virtualHosts = routingConfig.virtualHosts();
      VirtualHost virtualHost = RoutingUtils.findVirtualHostForHostName(
          virtualHosts, call.getAuthority());
      if (virtualHost == null) {
        call.close(
            Status.UNAVAILABLE.withDescription("Could not find xDS virtual host matching RPC"),
            new Metadata());
        return new Listener<ReqT>() {};
      }
      Route selectedRoute = null;
      MethodDescriptor<ReqT, RespT> method = call.getMethodDescriptor();
      for (Route route : virtualHost.routes()) {
        if (RoutingUtils.matchRoute(
            route.routeMatch(), "/" + method.getFullMethodName(), headers, random)) {
          selectedRoute = route;
          break;
        }
      }
      if (selectedRoute == null) {
        call.close(Status.UNAVAILABLE.withDescription("Could not find xDS route matching RPC"),
            new Metadata());
        return new ServerCall.Listener<ReqT>() {};
      }
      if (selectedRoute.routeAction() != null) {
        call.close(Status.UNAVAILABLE.withDescription("Invalid xDS route action for matching "
            + "route: only Route.non_forwarding_action should be allowed."), new Metadata());
        return new ServerCall.Listener<ReqT>() {};
      }
      ServerInterceptor routeInterceptor = noopInterceptor;
      Map<Route, ServerInterceptor> perRouteInterceptors = routingConfig.interceptors();
      if (perRouteInterceptors != null && perRouteInterceptors.get(selectedRoute) != null) {
        routeInterceptor = perRouteInterceptors.get(selectedRoute);
      }
      return routeInterceptor.interceptCall(call, headers, next);
    }
  }

  /**
   * The HttpConnectionManager level configuration.
   */
  @AutoValue
  abstract static class ServerRoutingConfig {
    @VisibleForTesting
    static final ServerRoutingConfig FAILING_ROUTING_CONFIG = ServerRoutingConfig.create(
        ImmutableList.<VirtualHost>of(), ImmutableMap.<Route, ServerInterceptor>of());

    abstract ImmutableList<VirtualHost> virtualHosts();

    // Prebuilt per route server interceptors from http filter configs.
    abstract ImmutableMap<Route, ServerInterceptor> interceptors();

    /**
     * Server routing configuration.
     * */
    public static ServerRoutingConfig create(
        ImmutableList<VirtualHost> virtualHosts,
        ImmutableMap<Route, ServerInterceptor> interceptors) {
      checkNotNull(virtualHosts, "virtualHosts");
      checkNotNull(interceptors, "interceptors");
      return new AutoValue_XdsServerWrapper_ServerRoutingConfig(virtualHosts, interceptors);
    }
  }
}
