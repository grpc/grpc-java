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

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.XdsServerWrapper.ATTR_SERVER_ROUTING_CONFIG;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.InsecureChannelCredentials;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.internal.FakeClock;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.xds.EnvoyServerProtoData.FilterChain;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.Filter.NamedFilterConfig;
import io.grpc.xds.Filter.ServerInterceptorBuilder;
import io.grpc.xds.FilterChainMatchingHandler.FilterChainSelector;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.VirtualHost.Route.RouteMatch;
import io.grpc.xds.VirtualHost.Route.RouteMatch.PathMatcher;
import io.grpc.xds.XdsClient.RdsResourceWatcher;
import io.grpc.xds.XdsClient.RdsUpdate;
import io.grpc.xds.XdsServerBuilder.XdsServingStatusListener;
import io.grpc.xds.XdsServerTestHelper.FakeXdsClient;
import io.grpc.xds.XdsServerTestHelper.FakeXdsClientPoolFactory;
import io.grpc.xds.XdsServerWrapper.ConfigApplyingInterceptor;
import io.grpc.xds.XdsServerWrapper.ServerRoutingConfig;
import io.grpc.xds.internal.Matchers.HeaderMatcher;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.sds.SslContextProviderSupplier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class XdsServerWrapperTest {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  @Mock
  private ServerBuilder<?> mockBuilder;
  @Mock
  private Server mockServer;
  @Mock
  private static TlsContextManager tlsContextManager;
  private AtomicReference<FilterChainSelector> selectorRef = new AtomicReference<>();
  private FakeClock executor = new FakeClock();
  private FakeXdsClient xdsClient = new FakeXdsClient();
  private FilterRegistry filterRegistry = FilterRegistry.getDefaultRegistry();

  @Mock
  private XdsServingStatusListener listener;

  private static final String SERVER_URI = "trafficdirector.googleapis.com";
  private static final String NODE_ID =
          "projects/42/networks/default/nodes/5c85b298-6f5b-4722-b74a-f7d1f0ccf5ad";
  private static final EnvoyProtoData.Node BOOTSTRAP_NODE =
          EnvoyProtoData.Node.newBuilder().setId(NODE_ID).build();
  static final Bootstrapper.BootstrapInfo BOOTSTRAP_INFO =
          new Bootstrapper.BootstrapInfo(
                  Arrays.asList(
                          new Bootstrapper.ServerInfo(SERVER_URI,
                                  InsecureChannelCredentials.create(), true)),
                  BOOTSTRAP_NODE,
                  null,
                  "grpc/server?udpa.resource.listening_address=%s");

  private XdsServerWrapper xdsServerWrapper;

  @Before
  public void setup() {
    when(mockBuilder.build()).thenReturn(mockServer);
    xdsServerWrapper = new XdsServerWrapper("0.0.0.0:1", mockBuilder,
            executor.getScheduledExecutorService(), 0, listener,
            selectorRef, new FakeXdsClientPoolFactory(xdsClient), filterRegistry);
  }

  @Test
  public void shutdown() throws Exception {
    final SettableFuture<Server> start = SettableFuture.create();
    Executors.newSingleThreadExecutor().execute(new Runnable() {
      @Override
      public void run() {
        try {
          start.set(xdsServerWrapper.start());
        } catch (Exception ex) {
          start.setException(ex);
        }
      }
    });
    String ldsWatched = xdsClient.ldsResource.get(5, TimeUnit.SECONDS);
    assertThat(ldsWatched).isEqualTo("grpc/server?udpa.resource.listening_address=0.0.0.0:1");
    HttpConnectionManager hcm_virtual = HttpConnectionManager.forVirtualHosts(
            0L, Collections.singletonList(createVirtualHost("virtual-host-0")),
            new ArrayList<NamedFilterConfig>());
    EnvoyServerProtoData.FilterChain f0 = createFilterChain("filter-chain-0", hcm_virtual);
    SslContextProviderSupplier sslSupplier = f0.getSslContextProviderSupplier();
    xdsClient.deliverLdsUpdate(Collections.singletonList(f0), null);
    verify(mockServer).start();
    xdsServerWrapper.shutdown();
    assertThat(xdsServerWrapper.isShutdown()).isTrue();
    assertThat(xdsClient.ldsResource).isNull();
    assertThat(xdsClient.shutdown).isTrue();
    verify(mockServer).shutdown();
    assertThat(sslSupplier.isShutdown()).isTrue();
    when(mockServer.isTerminated()).thenReturn(true);
    assertThat(xdsServerWrapper.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    xdsServerWrapper.awaitTermination();
    assertThat(xdsServerWrapper.isTerminated()).isTrue();
    assertThat(start.get()).isSameInstanceAs(xdsServerWrapper);
  }

  @Test
  public void discoverState_virtualhost() throws Exception {
    final SettableFuture<Server> start = SettableFuture.create();
    Executors.newSingleThreadExecutor().execute(new Runnable() {
      @Override
      public void run() {
        try {
          start.set(xdsServerWrapper.start());
        } catch (Exception ex) {
          start.setException(ex);
        }
      }
    });
    String ldsWatched = xdsClient.ldsResource.get(5, TimeUnit.SECONDS);
    VirtualHost virtualHost =
            VirtualHost.create(
                    "virtual-host", Collections.singletonList("auth"), new ArrayList<Route>(),
                    ImmutableMap.<String, FilterConfig>of());
    HttpConnectionManager httpConnectionManager = HttpConnectionManager.forVirtualHosts(
            0L, Collections.singletonList(virtualHost), new ArrayList<NamedFilterConfig>());
    EnvoyServerProtoData.FilterChain filterChain = new EnvoyServerProtoData.FilterChain(
            "filter-chain-foo", createMatch(), httpConnectionManager, createTls(),
            tlsContextManager);
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain), null);
    FilterChainSelector selector = selectorRef.get();
    assertThat(ldsWatched).isEqualTo("grpc/server?udpa.resource.listening_address=0.0.0.0:1");
    assertThat(selector.getRoutingConfigs()).isEqualTo(ImmutableMap.of(
            filterChain, ServerRoutingConfig.create(httpConnectionManager.httpFilterConfigs(),
                    httpConnectionManager.virtualHosts())
    ));
    verify(listener).onServing();
    verify(mockServer).start();
  }

  @Test
  public void discoverState_rds() throws Exception {
    final SettableFuture<Server> start = SettableFuture.create();
    Executors.newSingleThreadExecutor().execute(new Runnable() {
      @Override
      public void run() {
        try {
          start.set(xdsServerWrapper.start());
        } catch (Exception ex) {
          start.setException(ex);
        }
      }
    });
    String ldsWatched = xdsClient.ldsResource.get(5, TimeUnit.SECONDS);
    assertThat(ldsWatched).isEqualTo("grpc/server?udpa.resource.listening_address=0.0.0.0:1");
    VirtualHost virtualHost = createVirtualHost("virtual-host-0");
    HttpConnectionManager hcm_virtual = HttpConnectionManager.forVirtualHosts(
            0L, Collections.singletonList(virtualHost), new ArrayList<NamedFilterConfig>());
    EnvoyServerProtoData.FilterChain f0 = createFilterChain("filter-chain-0", hcm_virtual);
    EnvoyServerProtoData.FilterChain f1 = createFilterChain("filter-chain-1", createRds("r0"));

    xdsClient.deliverLdsUpdate(Arrays.asList(f0, f1), null);
    assertThat(selectorRef.get()).isNull();
    verify(mockServer, never()).start();
    assertThat(xdsClient.rdsResources.keySet()).isEqualTo(ImmutableSet.of("r0"));

    EnvoyServerProtoData.FilterChain f2 = createFilterChain("filter-chain-2", createRds("r1"));
    EnvoyServerProtoData.FilterChain f3 = createFilterChain("filter-chain-3", createRds("r2"));
    xdsClient.deliverLdsUpdate(Arrays.asList(f0, f2), f3);
    assertThat(xdsClient.rdsResources.keySet()).isEqualTo(ImmutableSet.of("r1", "r2"));
    verify(mockServer, never()).start();
    verify(listener, never()).onServing();

    xdsClient.deliverRdsUpdate("r1",
            Collections.singletonList(createVirtualHost("virtual-host-1")));
    verify(mockServer, never()).start();
    xdsClient.deliverRdsUpdate("r2",
            Collections.singletonList(createVirtualHost("virtual-host-2")));
    verify(mockServer).start();
    assertThat(selectorRef.get().getRoutingConfigs()).isEqualTo(ImmutableMap.of(
        f0, ServerRoutingConfig.create(hcm_virtual.httpFilterConfigs(), hcm_virtual.virtualHosts()),
        f2, ServerRoutingConfig.create(f2.getHttpConnectionManager().httpFilterConfigs(),
                    Collections.singletonList(createVirtualHost("virtual-host-1")))
    ));
    assertThat(selectorRef.get().getDefaultRoutingConfig()).isEqualTo(
            ServerRoutingConfig.create(f3.getHttpConnectionManager().httpFilterConfigs(),
                    Collections.singletonList(createVirtualHost("virtual-host-2"))));
    assertThat(selectorRef.get().getDefaultSslContextProviderSupplier()).isEqualTo(
            f3.getSslContextProviderSupplier());
  }

  @Test
  public void error() throws Exception {
    final SettableFuture<Server> start = SettableFuture.create();
    Executors.newSingleThreadExecutor().execute(new Runnable() {
      @Override
      public void run() {
        try {
          start.set(xdsServerWrapper.start());
        } catch (Exception ex) {
          start.setException(ex);
        }
      }
    });
    String ldsResource = xdsClient.ldsResource.get(5, TimeUnit.SECONDS);
    xdsClient.ldsWatcher.onResourceDoesNotExist(ldsResource);
    verify(listener, times(1)).onNotServing(any(StatusException.class));
    verify(mockBuilder, never()).build();
    FilterChain filterChain = createFilterChain("filter-chain-0", createRds("rds"));
    SslContextProviderSupplier sslSupplier = filterChain.getSslContextProviderSupplier();
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain), null);
    xdsClient.rdsResources.get("rds").onResourceDoesNotExist("rds");
    assertThat(selectorRef.get()).isSameInstanceAs(FilterChainSelector.NO_FILTER_CHAIN);
    assertThat(xdsClient.rdsResources).isEmpty();
    verify(mockBuilder, never()).build();
    verify(listener, times(2)).onNotServing(any(StatusException.class));
    assertThat(sslSupplier.isShutdown()).isTrue();

    when(mockServer.start()).thenThrow(new IOException("error!"))
            .thenReturn(mockServer);
    filterChain = createFilterChain("filter-chain-1", createRds("rds"));
    sslSupplier = filterChain.getSslContextProviderSupplier();
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain), null);
    xdsClient.deliverRdsUpdate("rds",
            Collections.singletonList(createVirtualHost("virtual-host-1")));
    RdsResourceWatcher saveRdsWatcher = xdsClient.rdsResources.get("rds");
    assertThat(executor.runDueTasks()).isEqualTo(1);
    verify(mockBuilder, times(1)).build();
    verify(mockServer, times(2)).start();
    verify(listener, times(1)).onServing();
    verify(listener, times(2)).onNotServing(any(StatusException.class));
    assertThat(selectorRef.get().getRoutingConfigs()).isEqualTo(ImmutableMap.of(
            filterChain, ServerRoutingConfig.create(new ArrayList<NamedFilterConfig>(),
                    Collections.singletonList(createVirtualHost("virtual-host-1")))
    ));
    assertThat(sslSupplier.isShutdown()).isFalse();
    // xds update after start
    xdsClient.deliverRdsUpdate("rds",
            Collections.singletonList(createVirtualHost("virtual-host-2")));
    xdsClient.ldsWatcher.onError(Status.INTERNAL);
    verify(mockBuilder, times(1)).build();
    verify(mockServer, times(2)).start();
    verify(listener, times(1)).onServing();
    verify(listener, times(2)).onNotServing(any(StatusException.class));
    assertThat(selectorRef.get().getRoutingConfigs()).isEqualTo(ImmutableMap.of(
            filterChain, ServerRoutingConfig.create(new ArrayList<NamedFilterConfig>(),
                    Collections.singletonList(createVirtualHost("virtual-host-2")))
    ));
    assertThat(sslSupplier.isShutdown()).isFalse();

    // not serving after serving
    xdsClient.ldsWatcher.onResourceDoesNotExist(ldsResource);
    assertThat(xdsClient.rdsResources).isEmpty();
    verify(mockServer).shutdown();
    when(mockServer.isShutdown()).thenReturn(true);
    assertThat(selectorRef.get()).isSameInstanceAs(FilterChainSelector.NO_FILTER_CHAIN);
    verify(listener, times(3)).onNotServing(any(StatusException.class));
    assertThat(sslSupplier.isShutdown()).isTrue();
    // no op
    saveRdsWatcher.onChanged(
            new RdsUpdate(Collections.singletonList(createVirtualHost("virtual-host-1"))));
    verify(mockBuilder, times(1)).build();
    verify(mockServer, times(2)).start();
    verify(listener, times(1)).onServing();

    // cancel retry
    when(mockServer.start()).thenThrow(new IOException("error1!"))
            .thenThrow(new IOException("error2!"))
            .thenReturn(mockServer);
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain), null);
    xdsClient.deliverRdsUpdate("rds",
            Collections.singletonList(createVirtualHost("virtual-host-1")));
    verify(mockBuilder, times(2)).build();
    when(mockServer.isShutdown()).thenReturn(false);
    verify(mockServer, times(3)).start();
    verify(listener, times(1)).onServing();
    verify(listener, times(3)).onNotServing(any(StatusException.class));
    assertThat(selectorRef.get().getRoutingConfigs()).isEqualTo(ImmutableMap.of(
            filterChain, ServerRoutingConfig.create(new ArrayList<NamedFilterConfig>(),
                    Collections.singletonList(createVirtualHost("virtual-host-1")))
    ));
    assertThat(executor.numPendingTasks()).isEqualTo(1);
    xdsClient.ldsWatcher.onResourceDoesNotExist(ldsResource);
    verify(mockServer, times(2)).shutdown();
    verify(listener, times(4)).onNotServing(any(StatusException.class));
    when(mockServer.isShutdown()).thenReturn(true);
    assertThat(executor.numPendingTasks()).isEqualTo(0);

    // serving after not serving
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain), null);
    xdsClient.deliverRdsUpdate("rds",
            Collections.singletonList(createVirtualHost("virtual-host-1")));
    verify(mockBuilder, times(3)).build();
    verify(mockServer, times(4)).start();
    verify(listener, times(1)).onServing();
    verify(listener, times(4)).onNotServing(any(StatusException.class));
    assertThat(executor.runDueTasks()).isEqualTo(1);
    verify(listener, times(2)).onServing();
    assertThat(selectorRef.get().getRoutingConfigs()).isEqualTo(ImmutableMap.of(
            filterChain, ServerRoutingConfig.create(new ArrayList<NamedFilterConfig>(),
                    Collections.singletonList(createVirtualHost("virtual-host-1")))
    ));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void interceptors() throws Exception {
    ArgumentCaptor<ConfigApplyingInterceptor> interceptorCaptor =
            ArgumentCaptor.forClass(ConfigApplyingInterceptor.class);
    final SettableFuture<Server> start = SettableFuture.create();
    Executors.newSingleThreadExecutor().execute(new Runnable() {
      @Override
      public void run() {
        try {
          start.set(xdsServerWrapper.start());
        } catch (Exception ex) {
          start.setException(ex);
        }
      }
    });
    xdsClient.ldsResource.get(5, TimeUnit.SECONDS);
    verify(mockBuilder).intercept(interceptorCaptor.capture());
    final ConfigApplyingInterceptor interceptor = interceptorCaptor.getValue();
    RouteMatch routeMatch =
            RouteMatch.create(
                    PathMatcher.fromPath("/FooService/barMethod", true),
                    Collections.<HeaderMatcher>emptyList(), null);
    Filter filter = mock(Filter.class, withSettings()
            .extraInterfaces(ServerInterceptorBuilder.class));
    when(filter.typeUrls()).thenReturn(new String[]{"filter-type-url"});
    filterRegistry.register(filter);
    FilterConfig f0 = mock(FilterConfig.class);
    FilterConfig f0Override = mock(FilterConfig.class);
    when(f0.typeUrl()).thenReturn("filter-type-url");
    final List<Integer> interceptorTrace = new ArrayList<>();
    ServerInterceptor interceptor0 = new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
          Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        interceptorTrace.add(0);
        return next.startCall(call, headers);
      }
    };
    ServerInterceptor interceptor1 = new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
          Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        interceptorTrace.add(1);
        return next.startCall(call, headers);
      }
    };

    VirtualHost virtualHost  = VirtualHost.create(
            "v1", Collections.singletonList("foo.google.com"),
            Arrays.asList(Route.forAction(routeMatch, null,
                    ImmutableMap.<String, FilterConfig>of())),
            ImmutableMap.of("filter-config-name-0", f0Override));
    ServerRoutingConfig routingConfig = ServerRoutingConfig.create(
            Arrays.asList(new NamedFilterConfig("filter-config-name-0", f0),
                    new NamedFilterConfig("filter-config-name-1", f0)),
            Collections.singletonList(virtualHost)
    );
    ServerCall<Void, Void> serverCall = mock(ServerCall.class);
    ServerCallHandler<Void, Void> mockNext = mock(ServerCallHandler.class);
    final ServerCall.Listener<Void> listener = new ServerCall.Listener<Void>() {};
    when(mockNext.startCall(any(ServerCall.class), any(Metadata.class))).thenReturn(listener);
    when(serverCall.getAttributes()).thenReturn(
            Attributes.newBuilder().set(ATTR_SERVER_ROUTING_CONFIG, routingConfig).build());
    when(serverCall.getMethodDescriptor()).thenReturn(
            MethodDescriptor.<Void, Void>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNKNOWN)
                    .setFullMethodName("FooService/barMethod")
                    .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
                    .setResponseMarshaller(TestMethodDescriptors.voidMarshaller())
                    .build()
    );
    when(serverCall.getAuthority()).thenReturn("foo.google.com");

    when(((ServerInterceptorBuilder)filter).buildServerInterceptor(f0, f0Override))
            .thenReturn(null);
    when(((ServerInterceptorBuilder)filter).buildServerInterceptor(f0, null))
            .thenReturn(null);
    ServerCall.Listener<Void> configApplyingInterceptorListener =
            interceptor.interceptCall(serverCall, new Metadata(), mockNext);
    assertThat(configApplyingInterceptorListener).isSameInstanceAs(listener);
    verify(mockNext).startCall(eq(serverCall), any(Metadata.class));
    assertThat(interceptorTrace).isEqualTo(Arrays.asList());

    when(((ServerInterceptorBuilder)filter).buildServerInterceptor(f0, f0Override))
            .thenReturn(null);
    when(((ServerInterceptorBuilder)filter).buildServerInterceptor(f0, null))
            .thenReturn(interceptor0);
    configApplyingInterceptorListener = interceptor.interceptCall(
            serverCall, new Metadata(), mockNext);
    assertThat(configApplyingInterceptorListener).isSameInstanceAs(listener);
    verify(mockNext, times(2)).startCall(eq(serverCall), any(Metadata.class));
    assertThat(interceptorTrace).isEqualTo(Arrays.asList(0));

    when(((ServerInterceptorBuilder)filter).buildServerInterceptor(f0, f0Override))
            .thenReturn(interceptor0);
    when(((ServerInterceptorBuilder)filter).buildServerInterceptor(f0, null))
            .thenReturn(interceptor1);
    configApplyingInterceptorListener = interceptor.interceptCall(
            serverCall, new Metadata(), mockNext);
    assertThat(configApplyingInterceptorListener).isSameInstanceAs(listener);
    verify(mockNext, times(3)).startCall(eq(serverCall), any(Metadata.class));
    assertThat(interceptorTrace).isEqualTo(Arrays.asList(0, 0, 1));
  }

  private HttpConnectionManager createRds(String name) {
    return HttpConnectionManager.forRdsName(0L, name,
            new ArrayList<NamedFilterConfig>());
  }

  private FilterChain createFilterChain(String name, HttpConnectionManager hcm) {
    return new EnvoyServerProtoData.FilterChain(name, createMatch(),
            hcm, createTls(), tlsContextManager);
  }

  private VirtualHost createVirtualHost(String name) {
    return VirtualHost.create(
            name, Collections.singletonList("auth"), new ArrayList<Route>(),
            ImmutableMap.<String, FilterConfig>of());

  }

  private EnvoyServerProtoData.FilterChainMatch createMatch() {
    return new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
  }

  private EnvoyServerProtoData.DownstreamTlsContext createTls() {
    return CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
  }
}
