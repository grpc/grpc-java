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

import com.google.common.base.MoreObjects;
import io.grpc.Attributes;
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
import io.grpc.xds.Filter.NamedFilterConfig;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

final class XdsServerWrapper extends Server {
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

  private final Server delegate;
  private final FilterRegistry filterRegistry;
  private final ThreadSafeRandom random;
  private final XdsClientPoolFactory xdsClientPoolFactory;
  private final XdsServingStatusListener listener;
  private final AtomicReference<FilterChainSelector> filterChainSelectorRef;
  private final AtomicReference<ServerInterceptor> configApplyingInterceptorRef;
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;

  XdsServerWrapper(Server delegate, XdsServingStatusListener listener,
      AtomicReference<FilterChainSelector> filterChainSelectorRef,
      AtomicReference<ServerInterceptor> configApplyingInterceptorRef) {
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
    try {
      xdsClientPool = xdsClientPoolFactory.getOrCreate();
    } catch (Exception e) {
      listener.onNotServing(
          Status.UNAVAILABLE.withDescription(
              "Failed to initialize xDS").withCause(e).asException());
      return this;
    }
    xdsClient = xdsClientPool.getObject();
    String listenerTemplate = xdsClient.getBootstrapInfo().getServerListenerResourceNameTemplate();
    // TODO(chengyuanzhang): create and start DiscoveryState and block until all
    //  RouteConfigurations being discovered.
    return this;
  }

  @Override
  public Server shutdown() {
    // TODO
    return null;
  }

  @Override
  public Server shutdownNow() {
    // TODO
    return null;
  }

  @Override
  public boolean isShutdown() {
    // TODO
    return false;
  }

  @Override
  public boolean isTerminated() {
    // TODO
    return false;
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
    private final Map<FilterChain, ServerRoutingConfig> routingConfigs = new HashMap<>();
    private final Map<String, RouteDiscoveryState> routeDiscoveryStates = new HashMap<>();
    private boolean stopped;

    @Override
    public void onChanged(final LdsUpdate update) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped) {
            return;
          }
          List<FilterChain> filterChains = update.listener.getFilterChains();
          for (FilterChain filterChain : filterChains) {
            // TODO(chengyuanzhang): populate HttpConnectionManager. If has RDS name, create and
            //  start a RouteDiscoveryState; if has virtual host list, create a ServerRoutingConfig
            //  result entry and put into routingConfigs.
            //  Prerequisite: https://github.com/grpc/grpc-java/pull/8228
            // TODO(chengyuanzhang): adjust RouteDiscoveryStates and results based on latest HCMs.
          }
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
          // TODO
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

    private void handleConfigDiscovered(
        FilterChain filterChain, ServerRoutingConfig routingConfig) {
      // TODO(chengyuanzhang): add/update the entry in routingConfigs. If RouteConfigurations for
      //  all FilterChains have been discovered, update the filterChainSelector ref.
    }

    private void handleConfigNotFound(FilterChain filterChain) {
      // TODO(chengyuanzhang): remove the entry from routingConfigs. Need to figure out if/how to
      //  propagate partial results.
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
        // TODO(chengyuanzhang): create a ServerRoutingConfig
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
    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
        Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      ServerRoutingConfig routingConfig = call.getAttributes().get(ATTR_SERVER_ROUTING_CONFIG);
      if (routingConfig != null) {
        VirtualHost virtualHost = RouteMatchingUtils.findVirtualHostForHostName(
            routingConfig.virtualHosts, call.getAuthority());
        if (virtualHost == null) {
          // TODO(chengyuanzhang): handle virtualhost not found.
          throw new AssertionError();
        }
        MethodDescriptor<ReqT, RespT> method = call.getMethodDescriptor();
        for (Route route : virtualHost.routes()) {
          if (RouteMatchingUtils.matchRoute(
              route.routeMatch(), "/" + method.getFullMethodName(), headers, random)) {

          }
        }


      } else {
        // TODO(chengyuanzhang): handle routingConfig not found, the root cause should be
        //  FilterChain matching failed
      }
      return null;
    }
  }

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
    private final Map<FilterChain, ServerRoutingConfig> routingConfigs;

    private FilterChainSelector(Map<FilterChain, ServerRoutingConfig> routingConfigs) {
      this.routingConfigs = checkNotNull(routingConfigs, "routingConfigs");
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
      if (evt instanceof ProtocolNegotiationEvent) {
        ProtocolNegotiationEvent pne = InternalProtocolNegotiationEvent.getDefault();
        FilteredConfig config = selector.select(
            (InetSocketAddress) ctx.channel().localAddress(),
            (InetSocketAddress) ctx.channel().remoteAddress());
        if (config != null) {
          Attributes attr = InternalProtocolNegotiationEvent.getAttributes(pne)
              .toBuilder().set(ATTR_SERVER_ROUTING_CONFIG, config.routingConfig).build();
          pne = InternalProtocolNegotiationEvent.withAttributes(pne, attr);
        }
        // TODO(chengyuanzhang): handle all cases
        ctx.fireUserEventTriggered(pne);
      } else {
        super.userEventTriggered(ctx, evt);
      }
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
