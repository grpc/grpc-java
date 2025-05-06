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
import static io.grpc.xds.client.Bootstrapper.XDSTP_SCHEME;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;
import com.google.common.net.InetAddresses;
import com.google.common.util.concurrent.SettableFuture;
import io.envoyproxy.envoy.config.core.v3.SocketAddress.Protocol;
import io.grpc.Attributes;
import io.grpc.InternalServerInterceptors;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MetricRecorder;
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
import io.grpc.xds.FilterChainMatchingProtocolNegotiators.FilterChainMatchingHandler.FilterChainSelector;
import io.grpc.xds.ThreadSafeRandom.ThreadSafeRandomImpl;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.XdsListenerResource.LdsUpdate;
import io.grpc.xds.XdsRouteConfigureResource.RdsUpdate;
import io.grpc.xds.XdsServerBuilder.XdsServingStatusListener;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsClient.ResourceWatcher;
import io.grpc.xds.internal.security.SslContextProviderSupplier;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
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

  // Must be accessed in syncContext.
  // Filter instances are unique per Server, per FilterChain, and per filter's name+typeUrl.
  // FilterChain.name -> <NamedFilterConfig.filterStateKey -> filter_instance>.
  private final HashMap<String, HashMap<String, Filter>> activeFilters = new HashMap<>();
  // Default filter chain Filter instances are unique per Server, and per filter's name+typeUrl.
  // NamedFilterConfig.filterStateKey -> filter_instance.
  private final HashMap<String, Filter> activeFiltersDefaultChain = new HashMap<>();

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
      xdsClientPool = xdsClientPoolFactory.getOrCreate("#server", new MetricRecorder() {});
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
      xdsClient.watchXdsResource(
          XdsListenerResource.getInstance(), resourceName, this, syncContext);
    }

    @Override
    public void onChanged(final LdsUpdate update) {
      if (stopped) {
        return;
      }
      logger.log(Level.FINEST, "Received Lds update {0}", update);
      if (update.listener() == null) {
        onResourceDoesNotExist("Non-API");
        return;
      }

      String ldsAddress = update.listener().address();
      if (ldsAddress == null || update.listener().protocol() != Protocol.TCP
          || !ipAddressesMatch(ldsAddress)) {
        handleConfigNotFoundOrMismatch(
            Status.UNKNOWN.withDescription(
                String.format(
                    "Listener address mismatch: expected %s, but got %s.",
                    listenerAddress, ldsAddress)).asException());
        return;
      }
      if (!pendingRds.isEmpty()) {
        // filter chain state has not yet been applied to filterChainSelectorManager and there
        // are two sets of sslContextProviderSuppliers, so we release the old ones.
        releaseSuppliersInFlight();
        pendingRds.clear();
      }

      filterChains = update.listener().filterChains();
      defaultFilterChain = update.listener().defaultFilterChain();
      // Filters are loaded even if the server isn't serving yet.
      updateActiveFilters();

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
                hcm.rdsName(), rdsState, syncContext);
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

    private boolean ipAddressesMatch(String ldsAddress) {
      HostAndPort ldsAddressHnP = HostAndPort.fromString(ldsAddress);
      HostAndPort listenerAddressHnP = HostAndPort.fromString(listenerAddress);
      if (!ldsAddressHnP.hasPort() || !listenerAddressHnP.hasPort()
          || ldsAddressHnP.getPort() != listenerAddressHnP.getPort()) {
        return false;
      }
      InetAddress listenerIp = InetAddresses.forString(listenerAddressHnP.getHost());
      InetAddress ldsIp = InetAddresses.forString(ldsAddressHnP.getHost());
      return listenerIp.equals(ldsIp);
    }

    @Override
    public void onResourceDoesNotExist(final String resourceName) {
      if (stopped) {
        return;
      }
      StatusException statusException = Status.UNAVAILABLE.withDescription(
          String.format("Listener %s unavailable, xDS node ID: %s", resourceName,
              xdsClient.getBootstrapInfo().node().getId())).asException();
      handleConfigNotFoundOrMismatch(statusException);
    }

    @Override
    public void onError(final Status error) {
      if (stopped) {
        return;
      }
      String description = error.getDescription() == null ? "" : error.getDescription() + " ";
      Status errorWithNodeId = error.withDescription(
          description + "xDS node ID: " + xdsClient.getBootstrapInfo().node().getId());
      logger.log(Level.FINE, "Error from XdsClient", errorWithNodeId);
      if (!isServing) {
        listener.onNotServing(errorWithNodeId.asException());
      }
    }

    private void shutdown() {
      stopped = true;
      cleanUpRouteDiscoveryStates();
      logger.log(Level.FINE, "Stop watching LDS resource {0}", resourceName);
      xdsClient.cancelXdsResourceWatch(XdsListenerResource.getInstance(), resourceName, this);
      shutdownActiveFilters();
      List<SslContextProviderSupplier> toRelease = getSuppliersInUse();
      filterChainSelectorManager.updateSelector(FilterChainSelector.NO_FILTER_CHAIN);
      for (SslContextProviderSupplier s: toRelease) {
        s.close();
      }
      releaseSuppliersInFlight();
    }

    private void updateSelector() {
      // This is regenerated in generateRoutingConfig() calls below.
      savedRdsRoutingConfigRef.clear();

      // Prepare server routing config map.
      ImmutableMap.Builder<FilterChain, AtomicReference<ServerRoutingConfig>> routingConfigs =
          ImmutableMap.builder();
      for (FilterChain filterChain: filterChains) {
        HashMap<String, Filter> chainFilters = activeFilters.get(filterChain.name());
        routingConfigs.put(filterChain, generateRoutingConfig(filterChain, chainFilters));
      }

      // Prepare the new selector.
      FilterChainSelector selector;
      if (defaultFilterChain != null) {
        selector = new FilterChainSelector(
            routingConfigs.build(),
            defaultFilterChain.sslContextProviderSupplier(),
            generateRoutingConfig(defaultFilterChain, activeFiltersDefaultChain));
      } else {
        selector = new FilterChainSelector(routingConfigs.build());
      }

      // Prepare the list of current selector's resources to close later.
      List<SslContextProviderSupplier> oldSslSuppliers = getSuppliersInUse();

      // Swap the selectors, initiate a graceful shutdown of the old one.
      logger.log(Level.FINEST, "Updating selector {0}", selector);
      filterChainSelectorManager.updateSelector(selector);

      // Release old resources.
      for (SslContextProviderSupplier supplier: oldSslSuppliers) {
        supplier.close();
      }

      // Now that we have valid Transport Socket config, we can start/restart listening on a port.
      startDelegateServer();
    }

    // called in syncContext
    private void updateActiveFilters() {
      Set<String> removedChains = new HashSet<>(activeFilters.keySet());
      for (FilterChain filterChain: filterChains) {
        removedChains.remove(filterChain.name());
        updateActiveFiltersForChain(
            activeFilters.computeIfAbsent(filterChain.name(), k -> new HashMap<>()),
            filterChain.httpConnectionManager().httpFilterConfigs());
      }

      // Shutdown all filters of chains missing from the LDS.
      for (String chainToShutdown : removedChains) {
        HashMap<String, Filter> filtersToShutdown = activeFilters.get(chainToShutdown);
        checkNotNull(filtersToShutdown, "filtersToShutdown of chain %s", chainToShutdown);
        updateActiveFiltersForChain(filtersToShutdown, null);
        activeFilters.remove(chainToShutdown);
      }

      // Default chain.
      ImmutableList<NamedFilterConfig> defaultChainConfigs = null;
      if (defaultFilterChain != null) {
        defaultChainConfigs = defaultFilterChain.httpConnectionManager().httpFilterConfigs();
      }
      updateActiveFiltersForChain(activeFiltersDefaultChain, defaultChainConfigs);
    }

    // called in syncContext
    private void shutdownActiveFilters() {
      for (HashMap<String, Filter> chainFilters : activeFilters.values()) {
        checkNotNull(chainFilters, "chainFilters");
        updateActiveFiltersForChain(chainFilters, null);
      }
      activeFilters.clear();
      updateActiveFiltersForChain(activeFiltersDefaultChain, null);
    }

    // called in syncContext
    private void updateActiveFiltersForChain(
        Map<String, Filter> chainFilters, @Nullable List<NamedFilterConfig> filterConfigs) {
      if (filterConfigs == null) {
        filterConfigs = ImmutableList.of();
      }

      Set<String> filtersToShutdown = new HashSet<>(chainFilters.keySet());
      for (NamedFilterConfig namedFilter : filterConfigs) {
        String typeUrl = namedFilter.filterConfig.typeUrl();
        String filterKey = namedFilter.filterStateKey();

        Filter.Provider provider = filterRegistry.get(typeUrl);
        checkNotNull(provider, "provider %s", typeUrl);
        Filter filter = chainFilters.computeIfAbsent(
            filterKey, k -> provider.newInstance(namedFilter.name));
        checkNotNull(filter, "filter %s", filterKey);
        filtersToShutdown.remove(filterKey);
      }

      // Shutdown filters not present in current HCM.
      for (String filterKey : filtersToShutdown) {
        Filter filterToShutdown = chainFilters.remove(filterKey);
        checkNotNull(filterToShutdown, "filterToShutdown %s", filterKey);
        filterToShutdown.close();
      }
    }

    private AtomicReference<ServerRoutingConfig> generateRoutingConfig(
        FilterChain filterChain, Map<String, Filter> chainFilters) {
      HttpConnectionManager hcm = filterChain.httpConnectionManager();
      ServerRoutingConfig routingConfig;

      // Inlined routes.
      ImmutableList<VirtualHost> vhosts = hcm.virtualHosts();
      if (vhosts != null) {
        routingConfig = ServerRoutingConfig.create(vhosts,
            generatePerRouteInterceptors(hcm.httpFilterConfigs(), vhosts, chainFilters));
        return new AtomicReference<>(routingConfig);
      }

      // Routes from RDS.
      RouteDiscoveryState rds = routeDiscoveryStates.get(hcm.rdsName());
      checkNotNull(rds, "rds");

      ImmutableList<VirtualHost> savedVhosts = rds.savedVirtualHosts;
      if (savedVhosts != null) {
        routingConfig = ServerRoutingConfig.create(savedVhosts,
            generatePerRouteInterceptors(hcm.httpFilterConfigs(), savedVhosts, chainFilters));
      } else {
        routingConfig = ServerRoutingConfig.FAILING_ROUTING_CONFIG;
      }
      AtomicReference<ServerRoutingConfig> routingConfigRef = new AtomicReference<>(routingConfig);
      savedRdsRoutingConfigRef.put(filterChain, routingConfigRef);
      return routingConfigRef;
    }

    private ImmutableMap<Route, ServerInterceptor> generatePerRouteInterceptors(
        @Nullable List<NamedFilterConfig> filterConfigs,
        List<VirtualHost> virtualHosts,
        Map<String, Filter> chainFilters) {
      syncContext.throwIfNotInThisSynchronizationContext();

      checkNotNull(chainFilters, "chainFilters");
      ImmutableMap.Builder<Route, ServerInterceptor> perRouteInterceptors =
          new ImmutableMap.Builder<>();

      for (VirtualHost virtualHost : virtualHosts) {
        for (Route route : virtualHost.routes()) {
          // Short circuit.
          if (filterConfigs == null) {
            perRouteInterceptors.put(route, noopInterceptor);
            continue;
          }

          // Override vhost filter configs with more specific per-route configs.
          Map<String, FilterConfig> perRouteOverrides = ImmutableMap.<String, FilterConfig>builder()
              .putAll(virtualHost.filterConfigOverrides())
              .putAll(route.filterConfigOverrides())
              .buildKeepingLast();

          // Interceptors for this vhost/route combo.
          List<ServerInterceptor> interceptors = new ArrayList<>(filterConfigs.size());
          for (NamedFilterConfig namedFilter : filterConfigs) {
            String name = namedFilter.name;
            FilterConfig config = namedFilter.filterConfig;
            FilterConfig overrideConfig = perRouteOverrides.get(name);
            String filterKey = namedFilter.filterStateKey();

            Filter filter = chainFilters.get(filterKey);
            checkNotNull(filter, "chainFilters.get(%s)", filterKey);
            ServerInterceptor interceptor = filter.buildServerInterceptor(config, overrideConfig);

            if (interceptor != null) {
              interceptors.add(interceptor);
            }
          }

          // Combine interceptors produced by different filters into a single one that executes
          // them sequentially. The order is preserved.
          perRouteInterceptors.put(route, combineInterceptors(interceptors));
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

    private void handleConfigNotFoundOrMismatch(StatusException exception) {
      cleanUpRouteDiscoveryStates();
      shutdownActiveFilters();
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
            String description = error.getDescription() == null ? "" : error.getDescription() + " ";
            Status errorWithNodeId = error.withDescription(
                    description + "xDS node ID: " + xdsClient.getBootstrapInfo().node().getId());
            logger.log(Level.WARNING, "Error loading RDS resource {0} from XdsClient: {1}.",
                    new Object[]{resourceName, errorWithNodeId});
            maybeUpdateSelector();
          }
        });
      }

      private void updateRdsRoutingConfig() {
        for (FilterChain filterChain : savedRdsRoutingConfigRef.keySet()) {
          HttpConnectionManager hcm = filterChain.httpConnectionManager();
          if (!resourceName.equals(hcm.rdsName())) {
            continue;
          }

          ServerRoutingConfig updatedRoutingConfig;
          if (savedVirtualHosts == null) {
            updatedRoutingConfig = ServerRoutingConfig.FAILING_ROUTING_CONFIG;
          } else {
            HashMap<String, Filter> chainFilters = activeFilters.get(filterChain.name());
            ImmutableMap<Route, ServerInterceptor> interceptors = generatePerRouteInterceptors(
                hcm.httpFilterConfigs(), savedVirtualHosts, chainFilters);
            updatedRoutingConfig = ServerRoutingConfig.create(savedVirtualHosts, interceptors);
          }

          logger.log(Level.FINEST, "Updating filter chain {0} rds routing config: {1}",
              new Object[]{filterChain.name(), updatedRoutingConfig});
          savedRdsRoutingConfigRef.get(filterChain).set(updatedRoutingConfig);
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
