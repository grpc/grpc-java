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
import com.google.common.collect.ImmutableMap;
import io.grpc.Attributes;
import io.grpc.InternalServerInterceptors;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.ObjectPool;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalProtocolNegotiationEvent;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiators;
import io.grpc.netty.ProtocolNegotiationEvent;
import io.grpc.xds.EnvoyServerProtoData.FilterChain;
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
import io.grpc.xds.internal.sds.SslContextProvider;
import io.grpc.xds.internal.sds.SslContextProviderSupplier;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.CertStoreException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

  private static final Attributes.Key<AtomicReference<FilterChainSelector>>
      ATTR_FILTER_CHAIN_SELECTOR_REF = Attributes.Key.create(
          "io.grpc.xds.ServerWrapper.filterChainSelectorRef");
  private static final Attributes.Key<ServerRoutingConfig> ATTR_SERVER_ROUTING_CONFIG =
      Attributes.Key.create("io.grpc.xds.ServerWrapper.serverRoutingConfig");

  private final String listenerAddress;
  private final Server delegate;
  private final FilterRegistry filterRegistry;
  private final ThreadSafeRandom random;
  private final XdsClientPoolFactory xdsClientPoolFactory;
  private final XdsServingStatusListener listener;
  private final AtomicReference<FilterChainSelector> filterChainSelectorRef;
  private final AtomicReference<ServerInterceptor> configApplyingInterceptorRef;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean shutdown = new AtomicBoolean(false);
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private DiscoveryState discoveryState;

  XdsServerWrapper(
      String listenerAddress,
      Server delegate,
      XdsServingStatusListener listener,
      AtomicReference<FilterChainSelector> filterChainSelectorRef,
      AtomicReference<ServerInterceptor> configApplyingInterceptorRef) {
    this.listenerAddress = checkNotNull(listenerAddress, "listenerAddress");
    this.delegate = checkNotNull(delegate, "delegate");
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
    try {
      xdsClientPool = xdsClientPoolFactory.getOrCreate();
    } catch (Exception e) {
      String errorMsg = "Failed to initialize xDS";
      listener.onNotServing(
          Status.UNAVAILABLE.withDescription(errorMsg).withCause(e).asException());
      throw new IOException(errorMsg, e);
    }
    xdsClient = xdsClientPool.getObject();
    // TODO(chengyuanzhang): add an API on XdsClient indicating if it is using v3, don't get
    //  from bootstrap.
    boolean useProtocolV3 = xdsClient.getBootstrapInfo().getServers().get(0).isUseProtocolV3();
    String listenerTemplate = xdsClient.getBootstrapInfo().getServerListenerResourceNameTemplate();
    if (!useProtocolV3 || listenerTemplate == null) {
      String errorMsg = "Can only support xDS v3 with listener resource name template";
      listener.onNotServing(Status.UNAVAILABLE.withDescription(errorMsg).asException());
      xdsClient = xdsClientPool.returnObject(xdsClient);
      throw new IOException(errorMsg);
    }
    discoveryState = new DiscoveryState(listenerTemplate.replaceAll("%s", listenerAddress));
    // TODO(chengyuanzhang): block until all RouteConfigurations are discovered.
    delegate.start();
    // TODO(chengyuanzhang): shall we retry if delegate fails to bind?
    return this;
  }

  @Override
  public Server shutdown() {
    if (!shutdown.compareAndSet(false, true)) {
      return this;
    }
    internalShutdown();
    delegate.shutdown();
    return this;
  }

  @Override
  public Server shutdownNow() {
    if (!shutdown.compareAndSet(false, true)) {
      return this;
    }
    internalShutdown();
    delegate.shutdownNow();
    return this;
  }

  private void internalShutdown() {
    logger.log(Level.FINER, "Shutting down XdsServerWrapper");
    if (discoveryState != null) {
      discoveryState.shutdown();
    }
    if (xdsClient != null) {
      xdsClient = xdsClientPool.returnObject(xdsClient);
    }
  }

  @Override
  public boolean isShutdown() {
    return shutdown.get();
  }

  @Override
  public boolean isTerminated() {
    return delegate.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return delegate.awaitTermination(timeout, unit);
  }

  @Override
  public void awaitTermination() throws InterruptedException {
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

  private final class DiscoveryState implements LdsResourceWatcher {
    private final String resourceName;
    private final Map<String, RouteDiscoveryState> routeDiscoveryStates = new HashMap<>();
    // TODO(chengyuanzhang): key by FilterChain's unique name instead.
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
    public void onResourceDoesNotExist(String resourceName) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped) {
            return;
          }
          cleanUpRouteDiscoveryStates();
          filterChainSelectorRef.set(FilterChainSelector.NO_FILTER_CHAIN);
        }
      });
    }

    @Override
    public void onError(Status error) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped) {
            return;
          }
          // TODO(chengyuanzhang): report transient error through ServingStatusListener
          //  and that's it (keep using existing resources if previously succeeded)?
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
            ImmutableMap.copyOf(routingConfigs), defaultRoutingConfig);
        filterChainSelectorRef.set(selector);
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
      public void onError(Status error) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (!routeDiscoveryStates.containsKey(resourceName)) {
              return;
            }
            // TODO(chengyuanzhang): report transient error through ServingStatusListener
            //  and that's it (keep using existing resources if previously succeeded)?
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
    private static final FilterChainSelector NO_FILTER_CHAIN =
        new FilterChainSelector(Collections.<FilterChain, ServerRoutingConfig>emptyMap(), null);

    private final Map<FilterChain, ServerRoutingConfig> routingConfigs;
    @Nullable
    private final ServerRoutingConfig defaultRoutingConfig;

    private FilterChainSelector(Map<FilterChain, ServerRoutingConfig> routingConfigs,
        ServerRoutingConfig defaultRoutingConfig) {
      this.routingConfigs = checkNotNull(routingConfigs, "routingConfigs");
      this.defaultRoutingConfig = defaultRoutingConfig;
    }

    @Nullable
    private FilteredConfig select(InetSocketAddress localAddr, InetSocketAddress remoteAddr) {
      // TODO(chengyuanzhang): do filter chain matching.
      return null;
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
