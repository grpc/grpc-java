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

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.UInt32Value;
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
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.ObjectPool;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalProtocolNegotiationEvent;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiators;
import io.grpc.netty.ProtocolNegotiationEvent;
import io.grpc.xds.EnvoyServerProtoData.CidrRange;
import io.grpc.xds.EnvoyServerProtoData.ConnectionSourceType;
import io.grpc.xds.EnvoyServerProtoData.FilterChain;
import io.grpc.xds.EnvoyServerProtoData.FilterChainMatch;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.Filter.NamedFilterConfig;
import io.grpc.xds.Filter.ServerInterceptorBuilder;
import io.grpc.xds.ThreadSafeRandom.ThreadSafeRandomImpl;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.XdsClient.LdsResourceWatcher;
import io.grpc.xds.XdsClient.LdsUpdate;
import io.grpc.xds.XdsClient.RdsResourceWatcher;
import io.grpc.xds.XdsClient.RdsUpdate;
import io.grpc.xds.XdsNameResolverProvider.XdsClientPoolFactory;
import io.grpc.xds.XdsServerBuilder.XdsServingStatusListener;
import io.grpc.xds.internal.Matchers.CidrMatcher;
import io.grpc.xds.internal.sds.SslContextProvider;
import io.grpc.xds.internal.sds.SslContextProviderSupplier;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslContext;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.CertStoreException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
          // TODO(chengyuanzhang): implement cleanup.
        }
      });

  // TODO(chengyuanzhang): move this to XdsServerBuilder.
  private static final Attributes.Key<AtomicReference<FilterChainSelector>>
      ATTR_FILTER_CHAIN_SELECTOR_REF = Attributes.Key.create(
          "io.grpc.xds.ServerWrapper.filterChainSelectorRef");
  private static final Attributes.Key<ServerRoutingConfig> ATTR_SERVER_ROUTING_CONFIG =
      Attributes.Key.create("io.grpc.xds.ServerWrapper.serverRoutingConfig");

  private final String listenerAddress;
  private final ServerBuilder<?> delegateBuilder;
  private final ScheduledExecutorService timeService;
  private final long retryDelayNano;
  private final FilterRegistry filterRegistry;
  private final ThreadSafeRandom random;
  private final XdsClientPoolFactory xdsClientPoolFactory;
  private final XdsServingStatusListener listener;
  private final AtomicReference<FilterChainSelector> filterChainSelectorRef;
  private final AtomicReference<ServerInterceptor> configApplyingInterceptorRef;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean shutdown = new AtomicBoolean(false);
  private final SettableFuture<IOException> initialStartFuture = SettableFuture.create();
  private boolean initialStarted;
  private ScheduledHandle restartTimer;
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private DiscoveryState discoveryState;
  private volatile Server delegate;

  XdsServerWrapper(
      String listenerAddress,
      ServerBuilder<?> delegateBuilder,
      ScheduledExecutorService timeService,
      long retryDelayNano,
      XdsServingStatusListener listener,
      AtomicReference<FilterChainSelector> filterChainSelectorRef,
      AtomicReference<ServerInterceptor> configApplyingInterceptorRef) {
    this.listenerAddress = checkNotNull(listenerAddress, "listenerAddress");
    this.delegateBuilder = checkNotNull(delegateBuilder, "delegateBuilder");
    this.timeService = checkNotNull(timeService, "timeService");
    this.retryDelayNano = retryDelayNano;
    this.listener = checkNotNull(listener, "listener");
    this.filterChainSelectorRef = checkNotNull(filterChainSelectorRef, "filterChainSelectorRef");
    this.configApplyingInterceptorRef =
        checkNotNull(configApplyingInterceptorRef, "configApplyingInterceptorRef");
    this.filterRegistry = FilterRegistry.getDefaultRegistry();
    this.random = ThreadSafeRandomImpl.instance;
    this.xdsClientPoolFactory = SharedXdsClientPoolProvider.getDefaultProvider();
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
    syncContext.drain();
    IOException exception;
    try {
      exception = initialStartFuture.get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
    if (exception != null) {
      throw exception;
    }
    return this;
  }

  private void internalStart() {
    try {
      xdsClientPool = xdsClientPoolFactory.getOrCreate();
    } catch (Exception e) {
      listener.onNotServing(
          Status.UNAVAILABLE.withDescription(
              "Failed to initialize xDS").withCause(e).asException());
      initialStartFuture.set(null);
      return;
    }
    xdsClient = xdsClientPool.getObject();
    // TODO(chengyuanzhang): add an API on XdsClient indicating if it is using v3, don't get
    //  from bootstrap.
    boolean useProtocolV3 = xdsClient.getBootstrapInfo().getServers().get(0).isUseProtocolV3();
    String listenerTemplate = xdsClient.getBootstrapInfo().getServerListenerResourceNameTemplate();
    if (!useProtocolV3 || listenerTemplate == null) {
      listener.onNotServing(
          Status.UNAVAILABLE.withDescription(
              "Can only support xDS v3 with listener resource name template").asException());
      initialStartFuture.set(null);
      xdsClient = xdsClientPool.returnObject(xdsClient);
      return;
    }
    discoveryState = new DiscoveryState(listenerTemplate.replaceAll("%s", listenerAddress));
  }

  @Override
  public Server shutdown() {
    if (!shutdown.compareAndSet(false, true)) {
      return this;
    }
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        internalShutdown();
        if (delegate != null) {
          delegate.shutdown();
        }
      }
    });
    syncContext.drain();
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
        internalShutdown();
        if (delegate != null) {
          delegate.shutdownNow();
        }
      }
    });
    syncContext.drain();
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
  }

  @Override
  public boolean isShutdown() {
    return shutdown.get();
  }

  @Override
  public boolean isTerminated() {
    boolean shutdownCalled = shutdown.get();
    if (!shutdownCalled) {
      return false;
    }
    if (delegate != null) {
      delegate.isTerminated();
    }
    return true;
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    // TODO
    return false;
  }

  @Override
  public void awaitTermination() throws InterruptedException {
    // TODO
  }

  @Override
  public int getPort() {
    if (delegate != null) {
      return delegate.getPort();
    }
    return -1;
  }

  @Override
  public List<? extends SocketAddress> getListenSockets() {
    if (delegate != null) {
      return delegate.getListenSockets();
    }
    return Collections.emptyList();
  }

  @Override
  public List<ServerServiceDefinition> getServices() {
    if (delegate != null) {
      return delegate.getServices();
    }
    return Collections.emptyList();
  }

  @Override
  public List<ServerServiceDefinition> getImmutableServices() {
    if (delegate != null) {
      return delegate.getImmutableServices();
    }
    return Collections.emptyList();
  }

  @Override
  public List<ServerServiceDefinition> getMutableServices() {
    if (delegate != null) {
      return delegate.getMutableServices();
    }
    return Collections.emptyList();
  }

  // Must run in SynchronizationContext
  private void startDelegateServer() {
    if (restartTimer != null && restartTimer.isPending()) {
      return;
    }
    delegate = delegateBuilder.build();
    try {
      delegate.start();
      listener.onServing();
    } catch (IOException e) {
      if (!initialStarted) {
        initialStarted = true;
        initialStartFuture.set(e);
      } else {
        restartTimer = syncContext.schedule(
            new RestartTask(), retryDelayNano, TimeUnit.NANOSECONDS, timeService);
      }
    }
  }

  private final class RestartTask implements Runnable {
    @Override
    public void run() {
      startDelegateServer();
    }
  }

  private final class DiscoveryState implements LdsResourceWatcher {
    private final String resourceName;
    private final Map<String, RouteDiscoveryState> routeDiscoveryStates = new HashMap<>();
    // TODO(chengyuanzhang): key by FilterChain's unique name instead.
    //  Prerequisite: https://github.com/grpc/grpc-java/pull/8228
    private final Map<FilterChain, ServerRoutingConfig> routingConfigs = new HashMap<>();
    // Most recently discovered filter chains.
    private List<FilterChain> filterChains = Collections.emptyList();
    // Most recently discovered default filter chain.
    @Nullable
    private FilterChain defaultFilterChain;
    // Config for the most recently discovered default filter chain.
    @Nullable
    private ServerRoutingConfig defaultRoutingConfig;
    private boolean stopped;

    private DiscoveryState(String resourceName) {
      this.resourceName = checkNotNull(resourceName, "resourceName");
      xdsClient.watchLdsResource(resourceName, this);
    }

    @Override
    public void onChanged(final LdsUpdate update) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped) {
            return;
          }
          if (update.listener == null) {
            // Ignore updates not for servers.
            return;
          }
          filterChains = update.listener.getFilterChains();
          if (!Objects.equals(defaultFilterChain, update.listener.getDefaultFilterChain())) {
            defaultFilterChain = update.listener.getDefaultFilterChain();
            // Discard config for the stale default filter chain.
            defaultRoutingConfig = null;
          }
          defaultFilterChain = update.listener.getDefaultFilterChain();
          List<FilterChain> allFilterChains = filterChains;
          if (defaultFilterChain != null) {
            allFilterChains = new ArrayList<>(filterChains);
            allFilterChains.add(defaultFilterChain);
          }
          for (FilterChain filterChain : allFilterChains) {
            // TODO(chengyuanzhang): populate HttpConnectionManager. If has RDS name, create and
            //  start a RouteDiscoveryState; if has virtual host list, create a ServerRoutingConfig
            //  result entry and put into routingConfigs.
            //  Prerequisite: https://github.com/grpc/grpc-java/pull/8228
            // TODO(chengyuanzhang): adjust RouteDiscoveryStates and results based on latest HCMs.
          }
          // Discard configs for revoked filter chains.
          // FIXME(chengyuanzhang): SslContextProviderSupplier in each FilterChain is leaked.
          routingConfigs.keySet().retainAll(filterChains);
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
          cleanUpRouteDiscoveryStates();
          filterChainSelectorRef.set(FilterChainSelector.NO_FILTER_CHAIN);
          if (!initialStarted) {
            initialStarted = true;
            initialStartFuture.set(null);
          }
          if (restartTimer != null) {
            restartTimer.cancel();
          }
          if (delegate != null) {
            delegate.shutdown();  // let in-progress calls finish
          }
          listener.onNotServing(
              Status.UNAVAILABLE.withDescription(
                  "Listener " + resourceName + " unavailable").asException());
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
          logger.log(Level.FINE, "Transient error from XdsClient: {0}", error);
          // TODO(chengyuanzhang): shall we do anything with transient error?
        }
      });
    }

    private void shutdown() {
      stopped = true;
      cleanUpRouteDiscoveryStates();
      logger.log(Level.FINE, "Stop watching LDS resource {0}", resourceName);
      xdsClient.cancelLdsResourceWatch(resourceName, this);
      for (FilterChain filterChain : filterChains) {
        SslContextProviderSupplier sslContextProviderSupplier =
            filterChain.getSslContextProviderSupplier();
        if (sslContextProviderSupplier != null) {
          sslContextProviderSupplier.close();
        }
      }
    }

    private void handleConfigDiscovered(
        FilterChain filterChain, ServerRoutingConfig routingConfig) {
      if (Objects.equals(filterChain, defaultFilterChain)) {
        defaultRoutingConfig = routingConfig;
      } else {
        routingConfigs.put(filterChain, routingConfig);
      }
      // Update the selector to use the most recently updated configs only after all
      // RouteConfigurations (including default) have been discovered.
      if (routingConfigs.keySet().containsAll(filterChains)
          && (defaultFilterChain == null) == (defaultRoutingConfig == null)) {
        FilterChainSelector selector = new FilterChainSelector(
            ImmutableMap.copyOf(routingConfigs),
            defaultFilterChain == null ? null : defaultFilterChain.getSslContextProviderSupplier(),
            defaultRoutingConfig);
        filterChainSelectorRef.set(selector);
        startDelegateServer();
      }
    }

    private void handleConfigNotFound(FilterChain filterChain) {
      if (Objects.equals(filterChain, defaultFilterChain)) {
        defaultRoutingConfig = null;
      } else {
        routingConfigs.remove(filterChain);
      }
      // TODO(chengyuanzhang): figure out if we should update the filter chain selector.
    }

    private void cleanUpRouteDiscoveryStates() {
      for (RouteDiscoveryState rdsState : routeDiscoveryStates.values()) {
        String rdsName = rdsState.resourceName;
        logger.log(Level.FINE, "Stop watching RDS resource {0}", rdsName);
        xdsClient.cancelRdsResourceWatch(rdsName, rdsState);
      }
      routeDiscoveryStates.clear();
    }

    private final class RouteDiscoveryState implements RdsResourceWatcher {
      private final String resourceName;
      private final FilterChain filterChain;
      private final List<NamedFilterConfig> httpFilterConfigs;

      private RouteDiscoveryState(String resourceName, FilterChain filterChain,
          List<NamedFilterConfig> httpFilterConfigs) {
        this.resourceName = checkNotNull(resourceName, "resourceName");
        this.filterChain = checkNotNull(filterChain, "filterChain");
        this.httpFilterConfigs = checkNotNull(httpFilterConfigs, "httpFilterConfigs");
      }

      @Override
      public void onChanged(final RdsUpdate update) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (!routeDiscoveryStates.containsKey(resourceName)) {
              return;
            }
            ServerRoutingConfig routingConfig =
                new ServerRoutingConfig(httpFilterConfigs, update.virtualHosts);
            handleConfigDiscovered(filterChain, routingConfig);
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
            handleConfigNotFound(filterChain);
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
            logger.log(Level.FINE, "Transient error from XdsClient: {0}", error);
            // TODO(chengyuanzhang): shall we do anything with transient error?
          }
        });
      }
    }
  }

  private final class ConfigApplyingInterceptor implements ServerInterceptor {
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
      ServerRoutingConfig routingConfig = call.getAttributes().get(ATTR_SERVER_ROUTING_CONFIG);
      if (routingConfig == null) {
        // TODO(chengyuanzhang): handle routingConfig not found, the root cause should be
        //  FilterChain matching failed
        return new Listener<ReqT>() {};
      }
      VirtualHost virtualHost = RoutingUtils.findVirtualHostForHostName(
          routingConfig.virtualHosts, call.getAuthority());
      if (virtualHost == null) {
        // TODO(chengyuanzhang): handle virtualhost not found. Can this really happen?
        throw new AssertionError();
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
        call.close(
            Status.UNAVAILABLE.withDescription("Could not find xDS route matching RPC"),
            new Metadata());
        return new ServerCall.Listener<ReqT>() {};
      }
      List<ServerInterceptor> filterInterceptors = new ArrayList<>();
      for (NamedFilterConfig namedFilterConfig : routingConfig.httpFilterConfigs) {
        FilterConfig filterConfig = namedFilterConfig.filterConfig;
        Filter filter = filterRegistry.get(filterConfig.typeUrl());
        if (filter instanceof ServerInterceptorBuilder) {
          ServerInterceptor interceptor =
              ((ServerInterceptorBuilder) filter).buildServerInterceptor(
                  filterConfig, selectedRoute.filterConfigOverrides().get(namedFilterConfig.name));
          filterInterceptors.add(interceptor);
        }
      }
      ServerInterceptor interceptor = combineInterceptors(filterInterceptors);
      return interceptor.interceptCall(call, headers, next);
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
  }

  /**
   * The HttpConnectionManager level configuration.
   */
  private static final class ServerRoutingConfig {
    // Top level http filter configs.
    private final List<NamedFilterConfig> httpFilterConfigs;
    private final List<VirtualHost> virtualHosts;

    private ServerRoutingConfig(List<NamedFilterConfig> httpFilterConfigs,
        List<VirtualHost> virtualHosts) {
      this.httpFilterConfigs = checkNotNull(httpFilterConfigs, "httpFilterConfigs");
      this.virtualHosts = checkNotNull(virtualHosts, "virtualHosts");
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("httpFilterConfigs", httpFilterConfigs)
          .add("virtualHost", virtualHosts)
          .toString();
    }
  }

  /**
   * The FilterChain level configuration.
   */
  private static final class FilteredConfig {
    private final ServerRoutingConfig routingConfig;
    @Nullable
    private final SslContextProviderSupplier sslContextProviderSupplier;

    private FilteredConfig(ServerRoutingConfig routingConfig,
        @Nullable SslContextProviderSupplier sslContextProviderSupplier) {
      this.routingConfig = checkNotNull(routingConfig, "routingConfig");
      this.sslContextProviderSupplier = sslContextProviderSupplier;
    }
  }

  private static final class FilterChainSelector {
    private static final FilterChainSelector NO_FILTER_CHAIN = new FilterChainSelector(
        Collections.<FilterChain, ServerRoutingConfig>emptyMap(), null, null);

    private final Map<FilterChain, ServerRoutingConfig> routingConfigs;
    @Nullable
    private final SslContextProviderSupplier defaultSslContextProviderSupplier;
    @Nullable
    private final ServerRoutingConfig defaultRoutingConfig;

    private FilterChainSelector(Map<FilterChain, ServerRoutingConfig> routingConfigs,
        @Nullable SslContextProviderSupplier defaultSslContextProviderSupplier,
        @Nullable ServerRoutingConfig defaultRoutingConfig) {
      this.routingConfigs = checkNotNull(routingConfigs, "routingConfigs");
      this.defaultSslContextProviderSupplier = defaultSslContextProviderSupplier;
      this.defaultRoutingConfig = defaultRoutingConfig;
    }

    @Nullable
    private FilteredConfig select(InetSocketAddress localAddr, InetSocketAddress remoteAddr) {
      Collection<FilterChain> filterChains = routingConfigs.keySet();
      filterChains = filterOnDestinationPort(filterChains);
      filterChains = filterOnIpAddress(filterChains, localAddr.getAddress(), true);
      filterChains = filterOnServerNames(filterChains);
      filterChains = filterOnTransportProtocol(filterChains);
      filterChains = filterOnApplicationProtocols(filterChains);
      filterChains =
          filterOnSourceType(filterChains, remoteAddr.getAddress(), localAddr.getAddress());
      filterChains = filterOnIpAddress(filterChains, remoteAddr.getAddress(), false);
      filterChains = filterOnSourcePort(filterChains, remoteAddr.getPort());

      if (filterChains.size() > 1) {
        // TODO(chengyuanzhang): should we just return any matched one?
        return null;
      }
      if (filterChains.size() == 1) {
        FilterChain selected = Iterables.getOnlyElement(filterChains);
        return new FilteredConfig(
            routingConfigs.get(selected), selected.getSslContextProviderSupplier());
      }
      if (defaultRoutingConfig != null) {
        return new FilteredConfig(defaultRoutingConfig, defaultSslContextProviderSupplier);
      }
      return null;
    }

    // reject if filer-chain-match has non-empty application_protocols
    private static Collection<FilterChain> filterOnApplicationProtocols(
        Collection<FilterChain> filterChains) {
      ArrayList<FilterChain> filtered = new ArrayList<>(filterChains.size());
      for (FilterChain filterChain : filterChains) {
        FilterChainMatch filterChainMatch = filterChain.getFilterChainMatch();

        if (filterChainMatch.getApplicationProtocols().isEmpty()) {
          filtered.add(filterChain);
        }
      }
      return filtered;
    }

    // reject if filer-chain-match has non-empty transport protocol other than "raw_buffer"
    private static Collection<FilterChain> filterOnTransportProtocol(
        Collection<FilterChain> filterChains) {
      ArrayList<FilterChain> filtered = new ArrayList<>(filterChains.size());
      for (FilterChain filterChain : filterChains) {
        FilterChainMatch filterChainMatch = filterChain.getFilterChainMatch();

        String transportProtocol = filterChainMatch.getTransportProtocol();
        if ( Strings.isNullOrEmpty(transportProtocol) || "raw_buffer".equals(transportProtocol)) {
          filtered.add(filterChain);
        }
      }
      return filtered;
    }

    // reject if filer-chain-match has server_name(s)
    private static Collection<FilterChain> filterOnServerNames(
        Collection<FilterChain> filterChains) {
      ArrayList<FilterChain> filtered = new ArrayList<>(filterChains.size());
      for (FilterChain filterChain : filterChains) {
        FilterChainMatch filterChainMatch = filterChain.getFilterChainMatch();

        if (filterChainMatch.getServerNames().isEmpty()) {
          filtered.add(filterChain);
        }
      }
      return filtered;
    }

    // destination_port present => Always fail match
    private static Collection<FilterChain> filterOnDestinationPort(
        Collection<FilterChain> filterChains) {
      ArrayList<FilterChain> filtered = new ArrayList<>(filterChains.size());
      for (FilterChain filterChain : filterChains) {
        FilterChainMatch filterChainMatch = filterChain.getFilterChainMatch();

        if (filterChainMatch.getDestinationPort() == UInt32Value.getDefaultInstance().getValue()) {
          filtered.add(filterChain);
        }
      }
      return filtered;
    }

    private static Collection<FilterChain> filterOnSourcePort(
        Collection<FilterChain> filterChains, int sourcePort) {
      ArrayList<FilterChain> filteredOnMatch = new ArrayList<>(filterChains.size());
      ArrayList<FilterChain> filteredOnEmpty = new ArrayList<>(filterChains.size());
      for (FilterChain filterChain : filterChains) {
        FilterChainMatch filterChainMatch = filterChain.getFilterChainMatch();

        List<Integer> sourcePortsToMatch = filterChainMatch.getSourcePorts();
        if (sourcePortsToMatch.isEmpty()) {
          filteredOnEmpty.add(filterChain);
        } else if (sourcePortsToMatch.contains(sourcePort)) {
          filteredOnMatch.add(filterChain);
        }
      }
      // match against source port is more specific than match against empty list
      return filteredOnMatch.isEmpty() ? filteredOnEmpty : filteredOnMatch;
    }

    private static Collection<FilterChain> filterOnSourceType(
        Collection<FilterChain> filterChains, InetAddress sourceAddress, InetAddress destAddress) {
      ArrayList<FilterChain> filtered = new ArrayList<>(filterChains.size());
      for (FilterChain filterChain : filterChains) {
        FilterChainMatch filterChainMatch = filterChain.getFilterChainMatch();
        ConnectionSourceType sourceType =
            filterChainMatch.getConnectionSourceType();

        boolean matching = false;
        if (sourceType == ConnectionSourceType.SAME_IP_OR_LOOPBACK) {
          matching =
              sourceAddress.isLoopbackAddress()
                  || sourceAddress.isAnyLocalAddress()
                  || sourceAddress.equals(destAddress);
        } else if (sourceType == ConnectionSourceType.EXTERNAL) {
          matching = !sourceAddress.isLoopbackAddress() && !sourceAddress.isAnyLocalAddress();
        } else { // ANY or null
          matching = true;
        }
        if (matching) {
          filtered.add(filterChain);
        }
      }
      return filtered;
    }

    private static int getMatchingPrefixLength(
        FilterChainMatch filterChainMatch, InetAddress address, boolean forDestination) {
      boolean isIPv6 = address instanceof Inet6Address;
      List<CidrRange> cidrRanges =
          forDestination
              ? filterChainMatch.getPrefixRanges()
              : filterChainMatch.getSourcePrefixRanges();
      int matchingPrefixLength;
      if (cidrRanges.isEmpty()) { // if there is no CidrRange assume 0-length match
        matchingPrefixLength = 0;
      } else {
        matchingPrefixLength = -1;
        for (CidrRange cidrRange : cidrRanges) {
          InetAddress cidrAddr = cidrRange.getAddressPrefix();
          boolean cidrIsIpv6 = cidrAddr instanceof Inet6Address;
          if (isIPv6 == cidrIsIpv6) {
            int prefixLen = cidrRange.getPrefixLen();
            CidrMatcher matcher = CidrMatcher.create(cidrAddr, prefixLen);
            if (matcher.matches(address) && prefixLen > matchingPrefixLength) {
              matchingPrefixLength = prefixLen;
            }
          }
        }
      }
      return matchingPrefixLength;
    }

    // use prefix_ranges (CIDR) and get the most specific matches
    private static Collection<FilterChain> filterOnIpAddress(
        Collection<FilterChain> filterChains, InetAddress address, boolean forDestination) {
      // curent list of top ones
      ArrayList<FilterChain> topOnes = new ArrayList<>(filterChains.size());
      int topMatchingPrefixLen = -1;
      for (FilterChain filterChain : filterChains) {
        int currentMatchingPrefixLen =
            getMatchingPrefixLength(filterChain.getFilterChainMatch(), address, forDestination);

        if (currentMatchingPrefixLen >= 0) {
          if (currentMatchingPrefixLen < topMatchingPrefixLen) {
            continue;
          }
          if (currentMatchingPrefixLen > topMatchingPrefixLen) {
            topMatchingPrefixLen = currentMatchingPrefixLen;
            topOnes.clear();
          }
          topOnes.add(filterChain);
        }
      }
      return topOnes;
    }
  }

  private static final class FilterChainMatchingHandler extends ChannelInboundHandlerAdapter {
    private final GrpcHttp2ConnectionHandler grpcHandler;
    private final FilterChainSelector selector;
    @Nullable
    private final ProtocolNegotiator fallbackProtocolNegotiator;

    private FilterChainMatchingHandler(
        GrpcHttp2ConnectionHandler grpcHandler, FilterChainSelector selector,
        @Nullable ProtocolNegotiator fallbackProtocolNegotiator) {
      this.grpcHandler = checkNotNull(grpcHandler, "grpcHandler");
      this.selector = checkNotNull(selector, "selector");
      this.fallbackProtocolNegotiator = fallbackProtocolNegotiator;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (!(evt instanceof ProtocolNegotiationEvent)) {
        super.userEventTriggered(ctx, evt);
        return;
      }
      FilteredConfig config = selector.select(
          (InetSocketAddress) ctx.channel().localAddress(),
          (InetSocketAddress) ctx.channel().remoteAddress());
      ProtocolNegotiationEvent pne = InternalProtocolNegotiationEvent.getDefault();
      SslContextProviderSupplier sslContextProviderSupplier = null;
      ChannelHandler handler;
      if (config != null) {
        Attributes attr = InternalProtocolNegotiationEvent.getAttributes(pne)
            .toBuilder().set(ATTR_SERVER_ROUTING_CONFIG, config.routingConfig).build();
        pne = InternalProtocolNegotiationEvent.withAttributes(pne, attr);
        sslContextProviderSupplier = config.sslContextProviderSupplier;
      }
      if (sslContextProviderSupplier == null) {
        if (fallbackProtocolNegotiator == null) {
          ctx.fireExceptionCaught(new CertStoreException("No certificate source found!"));
          return;
        }
        handler = fallbackProtocolNegotiator.newHandler(grpcHandler);
      } else {
        handler = new ServerHandler(grpcHandler, sslContextProviderSupplier);
      }
      ctx.pipeline().replace(this, null, handler);
      ctx.fireUserEventTriggered(pne);
    }
  }

  private static final class ServerHandler
      extends InternalProtocolNegotiators.ProtocolNegotiationHandler {
    private final GrpcHttp2ConnectionHandler grpcHandler;
    private final SslContextProviderSupplier sslContextProviderSupplier;

    ServerHandler(
        GrpcHttp2ConnectionHandler grpcHandler,
        SslContextProviderSupplier sslContextProviderSupplier) {
      super(
          // superclass (InternalProtocolNegotiators.ProtocolNegotiationHandler) expects 'next'
          // handler but we don't have a next handler _yet_. So we "disable" superclass's
          // behavior here and then manually add 'next' when we call
          // fireProtocolNegotiationEvent()
          new ChannelHandlerAdapter() {
            @Override
            public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
              ctx.pipeline().remove(this);
            }
          }, checkNotNull(grpcHandler, "grpcHandler").getNegotiationLogger());
      this.grpcHandler = grpcHandler;
      this.sslContextProviderSupplier = sslContextProviderSupplier;
    }

    @Override
    protected void handlerAdded0(final ChannelHandlerContext ctx) {
      final BufferReadsHandler bufferReads = new BufferReadsHandler();
      ctx.pipeline().addBefore(ctx.name(), null, bufferReads);

      sslContextProviderSupplier.updateSslContext(
          new SslContextProvider.Callback(ctx.executor()) {

            @Override
            public void updateSecret(SslContext sslContext) {
              ChannelHandler handler =
                  InternalProtocolNegotiators.serverTls(sslContext).newHandler(grpcHandler);

              // Delegate rest of handshake to TLS handler
              if (!ctx.isRemoved()) {
                ctx.pipeline().addAfter(ctx.name(), null, handler);
                fireProtocolNegotiationEvent(ctx);
                ctx.pipeline().remove(bufferReads);
              }
            }

            @Override
            public void onException(Throwable throwable) {
              ctx.fireExceptionCaught(throwable);
            }
          }
      );
    }
  }

  private static class BufferReadsHandler extends ChannelInboundHandlerAdapter {
    private final List<Object> reads = new ArrayList<>();
    private boolean readComplete;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      reads.add(msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
      readComplete = true;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
      for (Object msg : reads) {
        super.channelRead(ctx, msg);
      }
      if (readComplete) {
        super.channelReadComplete(ctx);
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      ctx.fireExceptionCaught(cause);
    }
  }
}
