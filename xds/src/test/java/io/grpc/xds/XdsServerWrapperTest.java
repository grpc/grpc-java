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
import static io.grpc.xds.XdsServerWrapper.RETRY_DELAY_NANOS;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
import io.grpc.xds.FilterChainMatchingProtocolNegotiators.FilterChainMatchingHandler.FilterChainSelector;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.VirtualHost.Route.RouteMatch;
import io.grpc.xds.VirtualHost.Route.RouteMatch.PathMatcher;
import io.grpc.xds.XdsClient.ResourceWatcher;
import io.grpc.xds.XdsRouteConfigureResource.RdsUpdate;
import io.grpc.xds.XdsServerBuilder.XdsServingStatusListener;
import io.grpc.xds.XdsServerTestHelper.FakeXdsClient;
import io.grpc.xds.XdsServerTestHelper.FakeXdsClientPoolFactory;
import io.grpc.xds.XdsServerWrapper.ConfigApplyingInterceptor;
import io.grpc.xds.XdsServerWrapper.ServerRoutingConfig;
import io.grpc.xds.internal.Matchers.HeaderMatcher;
import io.grpc.xds.internal.security.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.security.SslContextProviderSupplier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
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
  private static final int START_WAIT_AFTER_LISTENER_MILLIS = 100;

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  private ServerBuilder<?> mockBuilder;
  @Mock
  private Server mockServer;
  @Mock
  private XdsServingStatusListener listener;

  private FilterChainSelectorManager selectorManager = new FilterChainSelectorManager();
  private FakeClock executor = new FakeClock();
  private FakeXdsClient xdsClient = new FakeXdsClient();
  private FilterRegistry filterRegistry = FilterRegistry.getDefaultRegistry();
  private XdsServerWrapper xdsServerWrapper;
  private ServerRoutingConfig noopConfig = ServerRoutingConfig.create(
      ImmutableList.<VirtualHost>of(), ImmutableMap.<Route, ServerInterceptor>of());

  @Before
  public void setup() {
    when(mockBuilder.build()).thenReturn(mockServer);
    xdsServerWrapper = new XdsServerWrapper("0.0.0.0:1", mockBuilder, listener,
            selectorManager, new FakeXdsClientPoolFactory(xdsClient),
            filterRegistry, executor.getScheduledExecutorService());
  }

  @After
  public void tearDown() {
    xdsServerWrapper.shutdownNow();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testBootstrap() throws Exception {
    Bootstrapper.BootstrapInfo b =
        Bootstrapper.BootstrapInfo.builder()
            .servers(Arrays.asList(
                Bootstrapper.ServerInfo.create("uri", InsecureChannelCredentials.create())))
            .node(EnvoyProtoData.Node.newBuilder().setId("id").build())
            .serverListenerResourceNameTemplate("grpc/server?udpa.resource.listening_address=%s")
            .build();
    XdsClient xdsClient = mock(XdsClient.class);
    XdsListenerResource listenerResource = XdsListenerResource.getInstance();
    when(xdsClient.getBootstrapInfo()).thenReturn(b);
    xdsServerWrapper = new XdsServerWrapper("[::FFFF:129.144.52.38]:80", mockBuilder, listener,
        selectorManager, new FakeXdsClientPoolFactory(xdsClient), filterRegistry);
    Executors.newSingleThreadExecutor().execute(new Runnable() {
      @Override
      public void run() {
        try {
          xdsServerWrapper.start();
        } catch (IOException ex) {
          // ignore
        }
      }
    });
    verify(xdsClient, timeout(5000)).watchXdsResource(
        eq(listenerResource),
        eq("grpc/server?udpa.resource.listening_address=[::FFFF:129.144.52.38]:80"),
        any(ResourceWatcher.class));
  }

  @Test
  public void testBootstrap_noTemplate() throws Exception {
    Bootstrapper.BootstrapInfo b =
        Bootstrapper.BootstrapInfo.builder()
            .servers(Arrays.asList(
                Bootstrapper.ServerInfo.create("uri", InsecureChannelCredentials.create())))
            .node(EnvoyProtoData.Node.newBuilder().setId("id").build())
            .build();
    verifyBootstrapFail(b);
  }

  private void verifyBootstrapFail(Bootstrapper.BootstrapInfo b) throws Exception {
    XdsClient xdsClient = mock(XdsClient.class);
    when(xdsClient.getBootstrapInfo()).thenReturn(b);
    xdsServerWrapper = new XdsServerWrapper("0.0.0.0:1", mockBuilder, listener,
            selectorManager, new FakeXdsClientPoolFactory(xdsClient), filterRegistry);
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
    try {
      start.get(5000, TimeUnit.MILLISECONDS);
      fail("Start should throw exception");
    } catch (ExecutionException ex) {
      assertThat(ex.getCause()).isInstanceOf(IOException.class);
      Throwable cause = ex.getCause().getCause();
      assertThat(cause).isInstanceOf(StatusException.class);
      assertThat(((StatusException)cause).getStatus().getCode())
              .isEqualTo(Status.UNAVAILABLE.getCode());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testBootstrap_templateWithXdstp() throws Exception {
    Bootstrapper.BootstrapInfo b = Bootstrapper.BootstrapInfo.builder()
        .servers(Arrays.asList(
            Bootstrapper.ServerInfo.create(
                "uri", InsecureChannelCredentials.create())))
        .node(EnvoyProtoData.Node.newBuilder().setId("id").build())
        .serverListenerResourceNameTemplate(
            "xdstp://xds.authority.com/envoy.config.listener.v3.Listener/grpc/server/%s")
        .build();
    XdsClient xdsClient = mock(XdsClient.class);
    XdsListenerResource listenerResource = XdsListenerResource.getInstance();
    when(xdsClient.getBootstrapInfo()).thenReturn(b);
    xdsServerWrapper = new XdsServerWrapper("[::FFFF:129.144.52.38]:80", mockBuilder, listener,
        selectorManager, new FakeXdsClientPoolFactory(xdsClient), filterRegistry);
    Executors.newSingleThreadExecutor().execute(new Runnable() {
      @Override
      public void run() {
        try {
          xdsServerWrapper.start();
        } catch (IOException ex) {
          // ignore
        }
      }
    });
    verify(xdsClient, timeout(5000)).watchXdsResource(
        eq(listenerResource),
        eq("xdstp://xds.authority.com/envoy.config.listener.v3.Listener/grpc/server/"
            + "%5B::FFFF:129.144.52.38%5D:80"),
        any(ResourceWatcher.class));
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
    FilterChain f0 = createFilterChain("filter-chain-0", hcm_virtual);
    FilterChain f1 = createFilterChain("filter-chain-1", createRds("rds"));
    xdsClient.deliverLdsUpdate(Collections.singletonList(f0), f1);
    xdsClient.rdsCount.await(5, TimeUnit.SECONDS);
    xdsClient.deliverRdsUpdate("rds",
            Collections.singletonList(createVirtualHost("virtual-host-1")));
    verify(listener, timeout(5000)).onServing();
    start.get(START_WAIT_AFTER_LISTENER_MILLIS, TimeUnit.MILLISECONDS);
    verify(mockServer).start();
    xdsServerWrapper.shutdown();
    assertThat(xdsServerWrapper.isShutdown()).isTrue();
    assertThat(xdsClient.ldsResource).isNull();
    assertThat(xdsClient.shutdown).isTrue();
    verify(mockServer).shutdown();
    assertThat(f0.sslContextProviderSupplier().isShutdown()).isTrue();
    assertThat(f1.sslContextProviderSupplier().isShutdown()).isTrue();
    when(mockServer.isTerminated()).thenReturn(true);
    when(mockServer.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
    assertThat(xdsServerWrapper.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    xdsServerWrapper.awaitTermination();
    assertThat(xdsServerWrapper.isTerminated()).isTrue();
    assertThat(start.get()).isSameInstanceAs(xdsServerWrapper);
  }

  @Test
  public void shutdown_inflight() throws Exception {
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
    HttpConnectionManager hcmVirtual = HttpConnectionManager.forVirtualHosts(
            0L, Collections.singletonList(createVirtualHost("virtual-host-0")),
            new ArrayList<NamedFilterConfig>());
    FilterChain f0 = createFilterChain("filter-chain-0", createRds("rds"));
    FilterChain f1 = createFilterChain("filter-chain-1", hcmVirtual);
    xdsClient.deliverLdsUpdate(Collections.singletonList(f0), f1);
    xdsServerWrapper.shutdown();
    when(mockServer.isTerminated()).thenReturn(true);
    when(mockServer.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
    assertThat(xdsServerWrapper.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    xdsServerWrapper.awaitTermination();
    assertThat(xdsServerWrapper.isTerminated()).isTrue();
    verify(mockServer, never()).start();
    assertThat(xdsServerWrapper.isShutdown()).isTrue();
    assertThat(xdsClient.ldsResource).isNull();
    assertThat(xdsClient.shutdown).isTrue();
    verify(mockServer).shutdown();
    assertThat(f0.sslContextProviderSupplier().isShutdown()).isTrue();
    assertThat(f1.sslContextProviderSupplier().isShutdown()).isTrue();
    assertThat(start.isDone()).isFalse(); //shall we set initialStatus when shutdown?
  }

  @Test
  public void shutdown_afterResourceNotExist() throws Exception {
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
    verify(listener, timeout(5000)).onNotServing(any());
    try {
      start.get(START_WAIT_AFTER_LISTENER_MILLIS, TimeUnit.MILLISECONDS);
      fail("server should not start() successfully.");
    } catch (TimeoutException ex) {
      // expect to block here.
      assertThat(start.isDone()).isFalse();
    }
    verify(mockBuilder, times(1)).build();
    verify(mockServer, never()).start();
    verify(mockServer).shutdown();
    when(mockServer.isShutdown()).thenReturn(true);
    when(mockServer.isTerminated()).thenReturn(true);
    verify(listener, times(1)).onNotServing(any(Throwable.class));
    xdsServerWrapper.shutdown();
    assertThat(xdsServerWrapper.isShutdown()).isTrue();
    assertThat(xdsClient.ldsResource).isNull();
    assertThat(xdsClient.shutdown).isTrue();
    verify(mockBuilder, times(1)).build();
    verify(mockServer, times(1)).shutdown();
    xdsServerWrapper.awaitTermination(1, TimeUnit.SECONDS);
    assertThat(xdsServerWrapper.isTerminated()).isTrue();
  }

  @Test
  public void shutdown_pendingRetry() throws Exception {
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
    when(mockServer.start()).thenThrow(new IOException("error!"));
    FilterChain filterChain = createFilterChain("filter-chain-1", createRds("rds"));
    SslContextProviderSupplier sslSupplier = filterChain.sslContextProviderSupplier();
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain), null);
    xdsClient.rdsCount.await(5, TimeUnit.SECONDS);
    xdsClient.deliverRdsUpdate("rds",
            Collections.singletonList(createVirtualHost("virtual-host-1")));
    try {
      start.get(5000, TimeUnit.MILLISECONDS);
      fail("Start should throw exception");
    } catch (ExecutionException ex) {
      assertThat(ex.getCause()).isInstanceOf(IOException.class);
    }
    assertThat(executor.getPendingTasks().size()).isEqualTo(1);
    verify(mockServer).start();
    verify(mockServer, never()).shutdown();
    xdsServerWrapper.shutdown();
    verify(mockServer).shutdown();
    when(mockServer.isTerminated()).thenReturn(true);
    assertThat(sslSupplier.isShutdown()).isTrue();
    assertThat(executor.getPendingTasks().size()).isEqualTo(0);
    verify(listener, never()).onNotServing(any(Throwable.class));
    verify(listener, never()).onServing();
    xdsServerWrapper.awaitTermination();
    assertThat(xdsServerWrapper.isTerminated()).isTrue();
  }

  @Test
  public void shutdownNow_startThreadShouldNotLeak() throws Exception {
    final SettableFuture<Server> start = SettableFuture.create();
    Executors.newSingleThreadExecutor()
        .execute(
            new Runnable() {
              @Override
              public void run() {
                try {
                  start.set(xdsServerWrapper.start());
                } catch (Exception ex) {
                  start.setException(ex);
                }
              }
            });
    assertThat(xdsClient.ldsResource.get(5, TimeUnit.SECONDS))
        .isEqualTo("grpc/server?udpa.resource.listening_address=0.0.0.0:1");
    xdsServerWrapper.shutdownNow();
    try {
      start.get(5, TimeUnit.SECONDS);
      fail("should have thrown but not");
    } catch (ExecutionException ex) {
      assertThat(ex).hasCauseThat().isInstanceOf(IOException.class);
      assertThat(ex).hasCauseThat().hasMessageThat().isEqualTo("server is forcefully shut down");
    }
  }

  @Test
  public void initialStartIoException() throws Exception {
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
    when(mockServer.start()).thenThrow(new IOException("error!"));
    xdsClient.ldsResource.get(5, TimeUnit.SECONDS);
    FilterChain filterChain = createFilterChain("filter-chain-1", createRds("rds"));
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain), null);
    xdsClient.rdsCount.await(5, TimeUnit.SECONDS);
    xdsClient.deliverRdsUpdate("rds",
            Collections.singletonList(createVirtualHost("virtual-host-1")));
    try {
      start.get(5000, TimeUnit.MILLISECONDS);
      fail("Start should throw exception");
    } catch (ExecutionException ex) {
      assertThat(ex.getCause()).isInstanceOf(IOException.class);
      assertThat(ex.getCause().getMessage()).isEqualTo("error!");
    }
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
    assertThat(ldsWatched).isEqualTo("grpc/server?udpa.resource.listening_address=0.0.0.0:1");
    VirtualHost virtualHost =
            VirtualHost.create(
                    "virtual-host", Collections.singletonList("auth"), new ArrayList<Route>(),
                    ImmutableMap.<String, FilterConfig>of());
    HttpConnectionManager httpConnectionManager = HttpConnectionManager.forVirtualHosts(
            0L, Collections.singletonList(virtualHost), new ArrayList<NamedFilterConfig>());
    EnvoyServerProtoData.FilterChain filterChain = EnvoyServerProtoData.FilterChain.create(
            "filter-chain-foo", createMatch(), httpConnectionManager, createTls(),
            mock(TlsContextManager.class));
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain), null);
    start.get(5000, TimeUnit.MILLISECONDS);
    assertThat(ldsWatched).isEqualTo("grpc/server?udpa.resource.listening_address=0.0.0.0:1");
    assertThat(selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().size())
        .isEqualTo(1);
    ServerRoutingConfig realConfig =
        selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().get(filterChain).get();
    assertThat(realConfig.virtualHosts()).isEqualTo(httpConnectionManager.virtualHosts());
    assertThat(realConfig.interceptors()).isEqualTo(ImmutableMap.of());
    verify(listener).onServing();
    verify(mockServer).start();
  }

  @Test
  public void discoverState_restart_afterResourceNotExist() throws Exception {
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
    assertThat(ldsResource).isEqualTo("grpc/server?udpa.resource.listening_address=0.0.0.0:1");
    VirtualHost virtualHost =
            VirtualHost.create(
                    "virtual-host", Collections.singletonList("auth"), new ArrayList<Route>(),
                    ImmutableMap.<String, FilterConfig>of());
    HttpConnectionManager httpConnectionManager = HttpConnectionManager.forVirtualHosts(
            0L, Collections.singletonList(virtualHost), new ArrayList<NamedFilterConfig>());
    EnvoyServerProtoData.FilterChain filterChain = EnvoyServerProtoData.FilterChain.create(
            "filter-chain-foo", createMatch(), httpConnectionManager, createTls(),
            mock(TlsContextManager.class));
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain), null);
    start.get(5000, TimeUnit.MILLISECONDS);
    verify(listener).onServing();
    verify(mockServer).start();

    // server shutdown after resourceDoesNotExist
    xdsClient.ldsWatcher.onResourceDoesNotExist(ldsResource);
    verify(mockServer).shutdown();

    // re-deliver lds resource
    reset(mockServer);
    reset(listener);
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain), null);
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
    HttpConnectionManager hcmVirtual = HttpConnectionManager.forVirtualHosts(
            0L, Collections.singletonList(virtualHost), new ArrayList<NamedFilterConfig>());
    EnvoyServerProtoData.FilterChain f0 = createFilterChain("filter-chain-0", hcmVirtual);
    EnvoyServerProtoData.FilterChain f1 = createFilterChain("filter-chain-1", createRds("r0"));
    xdsClient.rdsCount = new CountDownLatch(3);
    xdsClient.deliverLdsUpdate(Arrays.asList(f0, f1), null);
    assertThat(start.isDone()).isFalse();
    assertThat(selectorManager.getSelectorToUpdateSelector()).isNull();
    verify(mockServer, never()).start();
    verify(listener, never()).onServing();

    EnvoyServerProtoData.FilterChain f2 = createFilterChain("filter-chain-2", createRds("r1"));
    EnvoyServerProtoData.FilterChain f3 = createFilterChain("filter-chain-3", createRds("r2"));
    xdsClient.deliverLdsUpdate(Arrays.asList(f0, f2), f3);
    verify(mockServer, never()).start();
    verify(listener, never()).onServing();
    xdsClient.rdsCount.await(5, TimeUnit.SECONDS);

    xdsClient.deliverRdsUpdate("r1",
            Collections.singletonList(createVirtualHost("virtual-host-1")));
    verify(mockServer, never()).start();
    xdsClient.deliverRdsUpdate("r2",
            Collections.singletonList(createVirtualHost("virtual-host-2")));
    start.get(5000, TimeUnit.MILLISECONDS);
    verify(mockServer).start();
    ServerRoutingConfig realConfig =
        selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().get(f0).get();
    assertThat(realConfig.virtualHosts()).isEqualTo(
        Collections.singletonList(createVirtualHost("virtual-host-0")));
    assertThat(realConfig.interceptors()).isEqualTo(ImmutableMap.of());
    assertThat(selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().size())
        .isEqualTo(2);
    realConfig = selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().get(f2).get();
    assertThat(realConfig.virtualHosts()).isEqualTo(
        Collections.singletonList(createVirtualHost("virtual-host-1")));
    assertThat(realConfig.interceptors()).isEqualTo(ImmutableMap.of());
    realConfig = selectorManager.getSelectorToUpdateSelector().getDefaultRoutingConfig().get();
    assertThat(realConfig.virtualHosts()).isEqualTo(
        Collections.singletonList(createVirtualHost("virtual-host-2")));
    assertThat(selectorManager.getSelectorToUpdateSelector().getDefaultSslContextProviderSupplier())
        .isEqualTo(f3.sslContextProviderSupplier());
  }

  @Test
  public void discoverState_oneRdsToMultipleFilterChain() throws Exception {
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
    EnvoyServerProtoData.FilterChain f0 = createFilterChain("filter-chain-0", createRds("r0"));
    EnvoyServerProtoData.FilterChain f1 = createFilterChain("filter-chain-1", createRds("r0"));
    EnvoyServerProtoData.FilterChain f2 = createFilterChain("filter-chain-2", createRds("r0"));

    xdsClient.rdsCount = new CountDownLatch(1);
    xdsClient.deliverLdsUpdate(Arrays.asList(f0, f1), f2);
    assertThat(start.isDone()).isFalse();
    assertThat(selectorManager.getSelectorToUpdateSelector()).isNull();

    xdsClient.rdsCount.await(5, TimeUnit.SECONDS);
    xdsClient.deliverRdsUpdate("r0",
            Collections.singletonList(createVirtualHost("virtual-host-0")));
    start.get(5000, TimeUnit.MILLISECONDS);
    verify(mockServer, times(1)).start();
    ServerRoutingConfig realConfig =
        selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().get(f0).get();
    assertThat(realConfig.virtualHosts()).isEqualTo(
        Collections.singletonList(createVirtualHost("virtual-host-0")));
    assertThat(realConfig.interceptors()).isEqualTo(ImmutableMap.of());

    realConfig = selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().get(f1).get();
    assertThat(realConfig.virtualHosts()).isEqualTo(
        Collections.singletonList(createVirtualHost("virtual-host-0")));
    assertThat(realConfig.interceptors()).isEqualTo(ImmutableMap.of());

    realConfig = selectorManager.getSelectorToUpdateSelector().getDefaultRoutingConfig().get();
    assertThat(realConfig.virtualHosts()).isEqualTo(
        Collections.singletonList(createVirtualHost("virtual-host-0")));
    assertThat(realConfig.interceptors()).isEqualTo(ImmutableMap.of());
    assertThat(selectorManager.getSelectorToUpdateSelector().getDefaultSslContextProviderSupplier())
        .isSameInstanceAs(f2.sslContextProviderSupplier());

    EnvoyServerProtoData.FilterChain f3 = createFilterChain("filter-chain-3", createRds("r0"));
    EnvoyServerProtoData.FilterChain f4 = createFilterChain("filter-chain-4", createRds("r1"));
    EnvoyServerProtoData.FilterChain f5 = createFilterChain("filter-chain-4", createRds("r1"));
    xdsClient.rdsCount = new CountDownLatch(1);
    xdsClient.deliverLdsUpdate(Arrays.asList(f5, f3), f4);
    xdsClient.rdsCount.await(5, TimeUnit.SECONDS);
    xdsClient.deliverRdsUpdate("r1",
            Collections.singletonList(createVirtualHost("virtual-host-1")));
    xdsClient.deliverRdsUpdate("r0",
            Collections.singletonList(createVirtualHost("virtual-host-0")));

    assertThat(selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().size())
        .isEqualTo(2);
    realConfig = selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().get(f5).get();
    assertThat(realConfig.virtualHosts()).isEqualTo(
        Collections.singletonList(createVirtualHost("virtual-host-1")));
    assertThat(realConfig.interceptors()).isEqualTo(ImmutableMap.of());
    realConfig = selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().get(f3).get();
    assertThat(realConfig.virtualHosts()).isEqualTo(
        Collections.singletonList(createVirtualHost("virtual-host-0")));
    assertThat(realConfig.interceptors()).isEqualTo(ImmutableMap.of());

    realConfig = selectorManager.getSelectorToUpdateSelector().getDefaultRoutingConfig().get();
    assertThat(realConfig.virtualHosts()).isEqualTo(
        Collections.singletonList(createVirtualHost("virtual-host-1")));
    assertThat(realConfig.interceptors()).isEqualTo(ImmutableMap.of());

    assertThat(selectorManager.getSelectorToUpdateSelector().getDefaultSslContextProviderSupplier())
        .isSameInstanceAs(f4.sslContextProviderSupplier());
    verify(mockServer, times(1)).start();
    xdsServerWrapper.shutdown();
    verify(mockServer, times(1)).shutdown();
    when(mockServer.isTerminated()).thenReturn(true);
    xdsServerWrapper.awaitTermination();
    assertThat(xdsServerWrapper.isTerminated()).isTrue();
  }

  @Test
  public void discoverState_rds_onError_and_resourceNotExist() throws Exception {
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
    HttpConnectionManager hcmVirtual = HttpConnectionManager.forVirtualHosts(
            0L, Collections.singletonList(virtualHost), new ArrayList<NamedFilterConfig>());
    EnvoyServerProtoData.FilterChain f0 = createFilterChain("filter-chain-0", hcmVirtual);
    EnvoyServerProtoData.FilterChain f1 = createFilterChain("filter-chain-1", createRds("r0"));
    xdsClient.deliverLdsUpdate(Arrays.asList(f0, f1), null);
    xdsClient.rdsCount.await();
    xdsClient.rdsWatchers.get("r0").onError(Status.CANCELLED);
    start.get(5000, TimeUnit.MILLISECONDS);
    assertThat(selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().size())
        .isEqualTo(2);
    ServerRoutingConfig realConfig =
        selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().get(f1).get();
    assertThat(realConfig.virtualHosts()).isEmpty();
    assertThat(realConfig.interceptors()).isEmpty();

    realConfig = selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().get(f0).get();
    assertThat(realConfig.virtualHosts()).isEqualTo(hcmVirtual.virtualHosts());
    assertThat(realConfig.interceptors()).isEqualTo(ImmutableMap.of());

    xdsClient.deliverRdsUpdate("r0",
            Collections.singletonList(createVirtualHost("virtual-host-1")));
    realConfig = selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().get(f1).get();
    assertThat(realConfig.virtualHosts()).isEqualTo(
        Collections.singletonList(createVirtualHost("virtual-host-1")));
    assertThat(realConfig.interceptors()).isEqualTo(ImmutableMap.of());

    xdsClient.rdsWatchers.get("r0").onError(Status.CANCELLED);
    realConfig = selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().get(f1).get();
    assertThat(realConfig.virtualHosts()).isEqualTo(
        Collections.singletonList(createVirtualHost("virtual-host-1")));
    assertThat(realConfig.interceptors()).isEqualTo(ImmutableMap.of());

    xdsClient.rdsWatchers.get("r0").onResourceDoesNotExist("r0");
    realConfig = selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().get(f1).get();
    assertThat(realConfig.virtualHosts()).isEmpty();
    assertThat(realConfig.interceptors()).isEmpty();
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
    verify(listener, timeout(5000)).onNotServing(any());
    try {
      start.get(START_WAIT_AFTER_LISTENER_MILLIS, TimeUnit.MILLISECONDS);
      fail("server should not start()");
    } catch (TimeoutException ex) {
      // expect to block here.
      assertThat(start.isDone()).isFalse();
    }
    verify(listener, times(1)).onNotServing(any(StatusException.class));
    verify(mockBuilder, times(1)).build();
    FilterChain filterChain0 = createFilterChain("filter-chain-0", createRds("rds"));
    SslContextProviderSupplier sslSupplier0 = filterChain0.sslContextProviderSupplier();
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain0), null);
    xdsClient.ldsWatcher.onError(Status.INTERNAL);
    assertThat(selectorManager.getSelectorToUpdateSelector())
        .isSameInstanceAs(FilterChainSelector.NO_FILTER_CHAIN);
    ResourceWatcher<RdsUpdate> saveRdsWatcher = xdsClient.rdsWatchers.get("rds");
    verify(mockBuilder, times(1)).build();
    verify(listener, times(2)).onNotServing(any(StatusException.class));
    assertThat(sslSupplier0.isShutdown()).isFalse();

    when(mockServer.start()).thenThrow(new IOException("error!"))
            .thenReturn(mockServer);
    FilterChain filterChain1 = createFilterChain("filter-chain-1", createRds("rds"));
    SslContextProviderSupplier sslSupplier1 = filterChain1.sslContextProviderSupplier();
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain1), null);
    assertThat(sslSupplier0.isShutdown()).isTrue();
    xdsClient.deliverRdsUpdate("rds",
            Collections.singletonList(createVirtualHost("virtual-host-1")));
    try {
      start.get(5000, TimeUnit.MILLISECONDS);
      fail("Start should throw exception");
    } catch (ExecutionException ex) {
      assertThat(ex.getCause()).isInstanceOf(IOException.class);
      assertThat(ex.getCause().getMessage()).isEqualTo("error!");
    }
    assertThat(executor.forwardNanos(RETRY_DELAY_NANOS)).isEqualTo(1);
    verify(mockBuilder, times(1)).build();
    verify(mockServer, times(2)).start();
    verify(listener, times(1)).onServing();
    assertThat(selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().size())
        .isEqualTo(1);
    ServerRoutingConfig realConfig =
        selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().get(filterChain1).get();
    assertThat(realConfig.virtualHosts()).isEqualTo(
        Collections.singletonList(createVirtualHost("virtual-host-1")));
    assertThat(realConfig.interceptors()).isEqualTo(ImmutableMap.of());

    // xds update after start
    xdsClient.deliverRdsUpdate("rds",
            Collections.singletonList(createVirtualHost("virtual-host-2")));
    assertThat(sslSupplier1.isShutdown()).isFalse();
    xdsClient.ldsWatcher.onError(Status.DEADLINE_EXCEEDED);
    verify(mockBuilder, times(1)).build();
    verify(mockServer, times(2)).start();
    verify(listener, times(2)).onNotServing(any(StatusException.class));
    assertThat(selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().size())
        .isEqualTo(1);
    realConfig = selectorManager.getSelectorToUpdateSelector().getRoutingConfigs()
        .get(filterChain1).get();
    assertThat(realConfig.virtualHosts()).isEqualTo(
        Collections.singletonList(createVirtualHost("virtual-host-2")));
    assertThat(realConfig.interceptors()).isEqualTo(ImmutableMap.of());

    assertThat(sslSupplier1.isShutdown()).isFalse();

    // not serving after serving
    xdsClient.ldsWatcher.onResourceDoesNotExist(ldsResource);
    assertThat(xdsClient.rdsWatchers).isEmpty();
    verify(mockServer, times(2)).shutdown();
    when(mockServer.isShutdown()).thenReturn(true);
    assertThat(selectorManager.getSelectorToUpdateSelector())
        .isSameInstanceAs(FilterChainSelector.NO_FILTER_CHAIN);
    verify(listener, times(3)).onNotServing(any(StatusException.class));
    assertThat(sslSupplier1.isShutdown()).isTrue();
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
    FilterChain filterChain2 = createFilterChain("filter-chain-2", createRds("rds"));
    SslContextProviderSupplier sslSupplier2 = filterChain2.sslContextProviderSupplier();
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain2), null);
    xdsClient.deliverRdsUpdate("rds",
            Collections.singletonList(createVirtualHost("virtual-host-1")));
    assertThat(sslSupplier1.isShutdown()).isTrue();
    verify(mockBuilder, times(2)).build();
    when(mockServer.isShutdown()).thenReturn(false);
    verify(mockServer, times(3)).start();
    verify(listener, times(1)).onServing();
    verify(listener, times(3)).onNotServing(any(StatusException.class));
    assertThat(selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().size())
        .isEqualTo(1);
    realConfig = selectorManager.getSelectorToUpdateSelector().getRoutingConfigs()
        .get(filterChain2).get();
    assertThat(realConfig.virtualHosts()).isEqualTo(
        Collections.singletonList(createVirtualHost("virtual-host-1")));
    assertThat(realConfig.interceptors()).isEqualTo(ImmutableMap.of());

    assertThat(executor.numPendingTasks()).isEqualTo(1);
    xdsClient.ldsWatcher.onResourceDoesNotExist(ldsResource);
    verify(mockServer, times(3)).shutdown();
    verify(listener, times(4)).onNotServing(any(StatusException.class));
    verify(listener, times(1)).onNotServing(any(IOException.class));
    when(mockServer.isShutdown()).thenReturn(true);
    assertThat(executor.numPendingTasks()).isEqualTo(0);
    assertThat(sslSupplier2.isShutdown()).isTrue();

    // serving after not serving
    FilterChain filterChain3 = createFilterChain("filter-chain-2", createRds("rds"));
    SslContextProviderSupplier sslSupplier3 = filterChain3.sslContextProviderSupplier();
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain3), null);
    xdsClient.deliverRdsUpdate("rds",
            Collections.singletonList(createVirtualHost("virtual-host-1")));
    verify(mockBuilder, times(3)).build();
    verify(mockServer, times(4)).start();
    verify(listener, times(1)).onServing();
    when(mockServer.isShutdown()).thenReturn(false);
    verify(listener, times(4)).onNotServing(any(StatusException.class));

    assertThat(selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().size())
        .isEqualTo(1);
    realConfig = selectorManager.getSelectorToUpdateSelector().getRoutingConfigs()
        .get(filterChain3).get();
    assertThat(realConfig.virtualHosts()).isEqualTo(
        Collections.singletonList(createVirtualHost("virtual-host-1")));
    assertThat(realConfig.interceptors()).isEqualTo(ImmutableMap.of());

    xdsServerWrapper.shutdown();
    verify(mockServer, times(4)).shutdown();
    assertThat(sslSupplier3.isShutdown()).isTrue();
    when(mockServer.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
    assertThat(xdsServerWrapper.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void interceptor_success() throws Exception {
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
    ConfigApplyingInterceptor interceptor = interceptorCaptor.getValue();
    RouteMatch routeMatch =
        RouteMatch.create(
            PathMatcher.fromPath("/FooService/barMethod", true),
            Collections.<HeaderMatcher>emptyList(), null);
    Route route = Route.forAction(routeMatch, null,
        ImmutableMap.<String, FilterConfig>of());
    VirtualHost virtualHost  = VirtualHost.create(
        "v1", Collections.singletonList("foo.google.com"), Arrays.asList(route),
        ImmutableMap.<String, FilterConfig>of());
    final List<Integer> interceptorTrace = new ArrayList<>();
    ServerInterceptor interceptor0 = new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
          Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        interceptorTrace.add(0);
        return next.startCall(call, headers);
      }
    };
    ServerRoutingConfig realConfig = ServerRoutingConfig.create(
        ImmutableList.of(virtualHost), ImmutableMap.of(route, interceptor0));
    ServerCall<Void, Void> serverCall = mock(ServerCall.class);
    when(serverCall.getMethodDescriptor()).thenReturn(createMethod("FooService/barMethod"));
    when(serverCall.getAttributes()).thenReturn(
        Attributes.newBuilder().set(ATTR_SERVER_ROUTING_CONFIG,
            new AtomicReference<>(realConfig)).build());
    when(serverCall.getAuthority()).thenReturn("foo.google.com");
    ServerCallHandler<Void, Void> next = mock(ServerCallHandler.class);
    interceptor.interceptCall(serverCall, new Metadata(), next);
    verify(next).startCall(eq(serverCall), any(Metadata.class));
    assertThat(interceptorTrace).isEqualTo(Arrays.asList(0));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void interceptor_virtualHostNotMatch() throws Exception {
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
    ConfigApplyingInterceptor interceptor = interceptorCaptor.getValue();
    ServerRoutingConfig routingConfig =
        createRoutingConfig("/FooService/barMethod", "foo.google.com");
    ServerCall<Void, Void> serverCall = mock(ServerCall.class);
    when(serverCall.getAttributes()).thenReturn(
        Attributes.newBuilder().set(ATTR_SERVER_ROUTING_CONFIG,
            new AtomicReference<>(routingConfig)).build());
    when(serverCall.getAuthority()).thenReturn("not-match.google.com");

    Filter filter = mock(Filter.class);
    when(filter.typeUrls()).thenReturn(new String[]{"filter-type-url"});
    filterRegistry.register(filter);
    ServerCallHandler<Void, Void> next = mock(ServerCallHandler.class);
    interceptor.interceptCall(serverCall, new Metadata(), next);
    verify(next, never()).startCall(any(ServerCall.class), any(Metadata.class));
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(serverCall).close(statusCaptor.capture(), any(Metadata.class));
    Status status = statusCaptor.getValue();
    assertThat(status.getCode()).isEqualTo(Status.UNAVAILABLE.getCode());
    assertThat(status.getDescription()).isEqualTo("Could not find xDS virtual host matching RPC");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void interceptor_routeNotMatch() throws Exception {
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
    ConfigApplyingInterceptor interceptor = interceptorCaptor.getValue();
    ServerRoutingConfig routingConfig =
        createRoutingConfig("/FooService/barMethod", "foo.google.com");
    ServerCall<Void, Void> serverCall = mock(ServerCall.class);
    when(serverCall.getAttributes()).thenReturn(
            Attributes.newBuilder()
                .set(ATTR_SERVER_ROUTING_CONFIG, new AtomicReference<>(routingConfig)).build());
    when(serverCall.getMethodDescriptor()).thenReturn(createMethod("NotMatchMethod"));
    when(serverCall.getAuthority()).thenReturn("foo.google.com");

    Filter filter = mock(Filter.class);
    when(filter.typeUrls()).thenReturn(new String[]{"filter-type-url"});
    filterRegistry.register(filter);
    ServerCallHandler<Void, Void> next = mock(ServerCallHandler.class);
    interceptor.interceptCall(serverCall, new Metadata(), next);
    verify(next, never()).startCall(any(ServerCall.class), any(Metadata.class));
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(serverCall).close(statusCaptor.capture(), any(Metadata.class));
    Status status = statusCaptor.getValue();
    assertThat(status.getCode()).isEqualTo(Status.UNAVAILABLE.getCode());
    assertThat(status.getDescription()).isEqualTo("Could not find xDS route matching RPC");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void interceptor_invalidRouteAction() throws Exception {
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
    ConfigApplyingInterceptor interceptor = interceptorCaptor.getValue();
    ServerRoutingConfig routingConfig =
        createRoutingConfig(
            "/FooService/barMethod",
            "foo.google.com",
            Route.RouteAction.forCluster(
                "cluster", Collections.<Route.RouteAction.HashPolicy>emptyList(), null, null));
    ServerCall<Void, Void> serverCall = mock(ServerCall.class);
    when(serverCall.getAttributes()).thenReturn(
        Attributes.newBuilder()
            .set(ATTR_SERVER_ROUTING_CONFIG, new AtomicReference<>(routingConfig)).build());
    when(serverCall.getMethodDescriptor()).thenReturn(createMethod("FooService/barMethod"));
    when(serverCall.getAuthority()).thenReturn("foo.google.com");

    Filter filter = mock(Filter.class);
    when(filter.typeUrls()).thenReturn(new String[]{"filter-type-url"});
    filterRegistry.register(filter);
    ServerCallHandler<Void, Void> next = mock(ServerCallHandler.class);
    interceptor.interceptCall(serverCall, new Metadata(), next);
    verify(next, never()).startCall(any(ServerCall.class), any(Metadata.class));
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(serverCall).close(statusCaptor.capture(), any(Metadata.class));
    Status status = statusCaptor.getValue();
    assertThat(status.getCode()).isEqualTo(Status.UNAVAILABLE.getCode());
    assertThat(status.getDescription()).isEqualTo("Invalid xDS route action for matching "
        + "route: only Route.non_forwarding_action should be allowed.");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void interceptor_failingRouterConfig() throws Exception {
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
    ConfigApplyingInterceptor interceptor = interceptorCaptor.getValue();
    ServerCall<Void, Void> serverCall = mock(ServerCall.class);

    when(serverCall.getAttributes()).thenReturn(
        Attributes.newBuilder().set(ATTR_SERVER_ROUTING_CONFIG,
            new AtomicReference<>(ServerRoutingConfig.FAILING_ROUTING_CONFIG)).build());

    ServerCallHandler<Void, Void> next = mock(ServerCallHandler.class);
    interceptor.interceptCall(serverCall, new Metadata(), next);
    verify(next, never()).startCall(any(ServerCall.class), any(Metadata.class));
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(serverCall).close(statusCaptor.capture(), any(Metadata.class));
    Status status = statusCaptor.getValue();
    assertThat(status.getCode()).isEqualTo(Status.UNAVAILABLE.getCode());
    assertThat(status.getDescription()).isEqualTo(
        "Missing or broken xDS routing config: RDS config unavailable.");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void buildInterceptor_inline() throws Exception {
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
    when(((ServerInterceptorBuilder)filter).buildServerInterceptor(f0, null))
        .thenReturn(interceptor0);
    when(((ServerInterceptorBuilder)filter).buildServerInterceptor(f0, f0Override))
        .thenReturn(interceptor1);
    Route route = Route.forAction(routeMatch, null,
        ImmutableMap.<String, FilterConfig>of());
    VirtualHost virtualHost  = VirtualHost.create(
        "v1", Collections.singletonList("foo.google.com"), Arrays.asList(route),
        ImmutableMap.of("filter-config-name-0", f0Override));
    HttpConnectionManager hcmVirtual = HttpConnectionManager.forVirtualHosts(
        0L, Collections.singletonList(virtualHost),
        Arrays.asList(new NamedFilterConfig("filter-config-name-0", f0),
            new NamedFilterConfig("filter-config-name-1", f0)));
    EnvoyServerProtoData.FilterChain filterChain = createFilterChain("filter-chain-0", hcmVirtual);
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain), null);
    start.get(5000, TimeUnit.MILLISECONDS);
    verify(mockServer).start();
    assertThat(selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().size())
        .isEqualTo(1);
    ServerInterceptor realInterceptor = selectorManager.getSelectorToUpdateSelector()
        .getRoutingConfigs().get(filterChain).get().interceptors().get(route);
    assertThat(realInterceptor).isNotNull();

    ServerCall<Void, Void> serverCall = mock(ServerCall.class);
    ServerCallHandler<Void, Void> mockNext = mock(ServerCallHandler.class);
    final ServerCall.Listener<Void> listener = new ServerCall.Listener<Void>() {};
    when(mockNext.startCall(any(ServerCall.class), any(Metadata.class))).thenReturn(listener);
    realInterceptor.interceptCall(serverCall, new Metadata(), mockNext);
    assertThat(interceptorTrace).isEqualTo(Arrays.asList(1, 0));
    verify(mockNext).startCall(eq(serverCall), any(Metadata.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void buildInterceptor_rds() throws Exception {
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
    when(((ServerInterceptorBuilder)filter).buildServerInterceptor(f0, null))
        .thenReturn(interceptor0);
    when(((ServerInterceptorBuilder)filter).buildServerInterceptor(f0, f0Override))
        .thenReturn(interceptor1);
    RouteMatch routeMatch =
        RouteMatch.create(
            PathMatcher.fromPath("/FooService/barMethod", true),
            Collections.<HeaderMatcher>emptyList(), null);

    HttpConnectionManager rdsHcm = HttpConnectionManager.forRdsName(0L, "r0",
        Arrays.asList(new NamedFilterConfig("filter-config-name-0", f0),
            new NamedFilterConfig("filter-config-name-1", f0)));
    EnvoyServerProtoData.FilterChain filterChain = createFilterChain("filter-chain-0", rdsHcm);
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain), null);
    Route route = Route.forAction(routeMatch, null,
        ImmutableMap.<String, FilterConfig>of());
    VirtualHost virtualHost  = VirtualHost.create(
        "v1", Collections.singletonList("foo.google.com"), Arrays.asList(route),
        ImmutableMap.of("filter-config-name-0", f0Override));
    xdsClient.rdsCount.await(5, TimeUnit.SECONDS);
    xdsClient.deliverRdsUpdate("r0", Collections.singletonList(virtualHost));
    start.get(5000, TimeUnit.MILLISECONDS);
    verify(mockServer).start();
    assertThat(selectorManager.getSelectorToUpdateSelector().getRoutingConfigs().size())
        .isEqualTo(1);
    ServerInterceptor realInterceptor = selectorManager.getSelectorToUpdateSelector()
        .getRoutingConfigs().get(filterChain).get().interceptors().get(route);
    assertThat(realInterceptor).isNotNull();

    ServerCall<Void, Void> serverCall = mock(ServerCall.class);
    ServerCallHandler<Void, Void> mockNext = mock(ServerCallHandler.class);
    final ServerCall.Listener<Void> listener = new ServerCall.Listener<Void>() {};
    when(mockNext.startCall(any(ServerCall.class), any(Metadata.class))).thenReturn(listener);
    realInterceptor.interceptCall(serverCall, new Metadata(), mockNext);
    assertThat(interceptorTrace).isEqualTo(Arrays.asList(1, 0));
    verify(mockNext).startCall(eq(serverCall), any(Metadata.class));

    virtualHost  = VirtualHost.create(
        "v1", Collections.singletonList("foo.google.com"), Arrays.asList(route),
         ImmutableMap.<String, FilterConfig>of());
    xdsClient.deliverRdsUpdate("r0", Collections.singletonList(virtualHost));
    realInterceptor = selectorManager.getSelectorToUpdateSelector().getRoutingConfigs()
        .get(filterChain).get().interceptors().get(route);
    assertThat(realInterceptor).isNotNull();
    interceptorTrace.clear();
    realInterceptor.interceptCall(serverCall, new Metadata(), mockNext);
    assertThat(interceptorTrace).isEqualTo(Arrays.asList(0, 0));
    verify(mockNext, times(2)).startCall(eq(serverCall), any(Metadata.class));

    xdsClient.rdsWatchers.get("r0").onResourceDoesNotExist("r0");
    assertThat(selectorManager.getSelectorToUpdateSelector().getRoutingConfigs()
        .get(filterChain).get()).isEqualTo(noopConfig);
  }

  private static FilterChain createFilterChain(String name, HttpConnectionManager hcm) {
    return EnvoyServerProtoData.FilterChain.create(name, createMatch(),
            hcm, createTls(), mock(TlsContextManager.class));
  }

  private static VirtualHost createVirtualHost(String name) {
    return VirtualHost.create(
            name, Collections.singletonList("auth"), new ArrayList<Route>(),
            ImmutableMap.<String, FilterConfig>of());
  }

  private static HttpConnectionManager createRds(String name) {
    return createRds(name, null);
  }

  private static HttpConnectionManager createRds(String name, FilterConfig filterConfig) {
    return HttpConnectionManager.forRdsName(0L, name,
        Arrays.asList(new NamedFilterConfig("named-config-" + name, filterConfig)));
  }

  private static EnvoyServerProtoData.FilterChainMatch createMatch() {
    return EnvoyServerProtoData.FilterChainMatch.create(
        0,
        ImmutableList.of(),
        ImmutableList.of(),
        ImmutableList.of(),
        EnvoyServerProtoData.ConnectionSourceType.ANY,
        ImmutableList.of(),
        ImmutableList.of(),
        "");
  }

  private static ServerRoutingConfig createRoutingConfig(String path, String domain) {
    return createRoutingConfig(path, domain, null);
  }

  private static ServerRoutingConfig createRoutingConfig(
      String path, String domain, Route.RouteAction action) {
    RouteMatch routeMatch =
        RouteMatch.create(
            PathMatcher.fromPath(path, true),
            Collections.<HeaderMatcher>emptyList(), null);
    VirtualHost virtualHost  = VirtualHost.create(
        "v1", Collections.singletonList(domain),
        Arrays.asList(Route.forAction(routeMatch, action,
            ImmutableMap.<String, FilterConfig>of())),
        Collections.<String, FilterConfig>emptyMap());
    return ServerRoutingConfig.create(ImmutableList.<VirtualHost>of(virtualHost),
        ImmutableMap.<Route, ServerInterceptor>of()
    );
  }

  private static MethodDescriptor<Void, Void> createMethod(String path) {
    return MethodDescriptor.<Void, Void>newBuilder()
            .setType(MethodDescriptor.MethodType.UNKNOWN)
            .setFullMethodName(path)
            .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
            .setResponseMarshaller(TestMethodDescriptors.voidMarshaller())
            .build();
  }

  private static EnvoyServerProtoData.DownstreamTlsContext createTls() {
    return CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
  }
}
