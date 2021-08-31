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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
  @Mock
  private XdsServingStatusListener listener;

  private AtomicReference<FilterChainSelector> selectorRef = new AtomicReference<>();
  private FakeClock executor = new FakeClock();
  private FakeXdsClient xdsClient = new FakeXdsClient();
  private FilterRegistry filterRegistry = FilterRegistry.getDefaultRegistry();
  private XdsServerWrapper xdsServerWrapper;

  @Before
  public void setup() {
    when(mockBuilder.build()).thenReturn(mockServer);
    xdsServerWrapper = new XdsServerWrapper("0.0.0.0:1", mockBuilder, listener,
            selectorRef, new FakeXdsClientPoolFactory(xdsClient),
            filterRegistry, executor.getScheduledExecutorService());
  }

  @Test
  public void testBootstrap_notV3() throws Exception {
    Bootstrapper.BootstrapInfo b =
        new Bootstrapper.BootstrapInfo(
          Arrays.asList(
                  new Bootstrapper.ServerInfo("uri", InsecureChannelCredentials.create(), false)),
          EnvoyProtoData.Node.newBuilder().setId("id").build(),
          null,
                "grpc/server?udpa.resource.listening_address=%s");
    verifyBootstrapFail(b);
  }

  @Test
  public void testBootstrap_noTemplate() throws Exception {
    Bootstrapper.BootstrapInfo b =
        new Bootstrapper.BootstrapInfo(
            Arrays.asList(
                    new Bootstrapper.ServerInfo("uri", InsecureChannelCredentials.create(), true)),
            EnvoyProtoData.Node.newBuilder().setId("id").build(),
            null,
            null);
    verifyBootstrapFail(b);
  }

  private void verifyBootstrapFail(Bootstrapper.BootstrapInfo b) throws Exception {
    XdsClient xdsClient = mock(XdsClient.class);
    when(xdsClient.getBootstrapInfo()).thenReturn(b);
    xdsServerWrapper = new XdsServerWrapper("0.0.0.0:1", mockBuilder, listener,
            selectorRef, new FakeXdsClientPoolFactory(xdsClient), filterRegistry);
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
    start.get(5000, TimeUnit.MILLISECONDS);
    verify(mockServer).start();
    xdsServerWrapper.shutdown();
    assertThat(xdsServerWrapper.isShutdown()).isTrue();
    assertThat(xdsClient.ldsResource).isNull();
    assertThat(xdsClient.shutdown).isTrue();
    verify(mockServer).shutdown();
    assertThat(f0.getSslContextProviderSupplier().isShutdown()).isTrue();
    assertThat(f1.getSslContextProviderSupplier().isShutdown()).isTrue();
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
    assertThat(f0.getSslContextProviderSupplier().isShutdown()).isTrue();
    assertThat(f1.getSslContextProviderSupplier().isShutdown()).isTrue();
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
    try {
      start.get(5000, TimeUnit.MILLISECONDS);
      fail("Start should throw exception");
    } catch (ExecutionException ex) {
      assertThat(ex.getCause()).isInstanceOf(IOException.class);
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
    SslContextProviderSupplier sslSupplier = filterChain.getSslContextProviderSupplier();
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
    EnvoyServerProtoData.FilterChain filterChain = new EnvoyServerProtoData.FilterChain(
            "filter-chain-foo", createMatch(), httpConnectionManager, createTls(),
            tlsContextManager);
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain), null);
    start.get(5000, TimeUnit.MILLISECONDS);
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
    HttpConnectionManager hcmVirtual = HttpConnectionManager.forVirtualHosts(
            0L, Collections.singletonList(virtualHost), new ArrayList<NamedFilterConfig>());
    EnvoyServerProtoData.FilterChain f0 = createFilterChain("filter-chain-0", hcmVirtual);
    EnvoyServerProtoData.FilterChain f1 = createFilterChain("filter-chain-1", createRds("r0"));
    xdsClient.rdsCount = new CountDownLatch(3);
    xdsClient.deliverLdsUpdate(Arrays.asList(f0, f1), null);
    assertThat(start.isDone()).isFalse();
    assertThat(selectorRef.get()).isNull();
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
    assertThat(selectorRef.get().getRoutingConfigs()).isEqualTo(ImmutableMap.of(
            f0, ServerRoutingConfig.create(
                    hcmVirtual.httpFilterConfigs(), hcmVirtual.virtualHosts()),
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
    assertThat(selectorRef.get()).isNull();

    xdsClient.rdsCount.await(5, TimeUnit.SECONDS);
    xdsClient.deliverRdsUpdate("r0",
            Collections.singletonList(createVirtualHost("virtual-host-0")));
    start.get(5000, TimeUnit.MILLISECONDS);
    verify(mockServer, times(1)).start();
    assertThat(selectorRef.get().getRoutingConfigs()).isEqualTo(ImmutableMap.of(
            f0, ServerRoutingConfig.create(
                    f0.getHttpConnectionManager().httpFilterConfigs(),
                    Collections.singletonList(createVirtualHost("virtual-host-0"))),
            f1, ServerRoutingConfig.create(f1.getHttpConnectionManager().httpFilterConfigs(),
                    Collections.singletonList(createVirtualHost("virtual-host-0")))
    ));
    assertThat(selectorRef.get().getDefaultRoutingConfig()).isEqualTo(
            ServerRoutingConfig.create(f2.getHttpConnectionManager().httpFilterConfigs(),
                    Collections.singletonList(createVirtualHost("virtual-host-0"))));
    assertThat(selectorRef.get().getDefaultSslContextProviderSupplier()).isSameInstanceAs(
            f2.getSslContextProviderSupplier());

    EnvoyServerProtoData.FilterChain f3 = createFilterChain("filter-chain-3", createRds("r0"));
    EnvoyServerProtoData.FilterChain f4 = createFilterChain("filter-chain-4", createRds("r1"));
    xdsClient.rdsCount = new CountDownLatch(1);
    xdsClient.deliverLdsUpdate(Arrays.asList(f1, f3), f4);
    xdsClient.rdsCount.await(5, TimeUnit.SECONDS);
    xdsClient.deliverRdsUpdate("r1",
            Collections.singletonList(createVirtualHost("virtual-host-1")));
    xdsClient.deliverRdsUpdate("r0",
            Collections.singletonList(createVirtualHost("virtual-host-0")));
    assertThat(selectorRef.get().getRoutingConfigs()).isEqualTo(ImmutableMap.of(
            f1, ServerRoutingConfig.create(
                    f1.getHttpConnectionManager().httpFilterConfigs(),
                    Collections.singletonList(createVirtualHost("virtual-host-0"))),
            f3, ServerRoutingConfig.create(f3.getHttpConnectionManager().httpFilterConfigs(),
                    Collections.singletonList(createVirtualHost("virtual-host-0")))
    ));
    assertThat(selectorRef.get().getDefaultRoutingConfig()).isEqualTo(
            ServerRoutingConfig.create(f4.getHttpConnectionManager().httpFilterConfigs(),
                    Collections.singletonList(createVirtualHost("virtual-host-1"))));
    assertThat(selectorRef.get().getDefaultSslContextProviderSupplier()).isSameInstanceAs(
            f4.getSslContextProviderSupplier());
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
    assertThat(selectorRef.get().getRoutingConfigs().get(f1)).isEqualTo(ServerRoutingConfig.create(
            ImmutableList.<NamedFilterConfig>of(), ImmutableList.<VirtualHost>of())
    );
    xdsClient.deliverRdsUpdate("r0",
            Collections.singletonList(createVirtualHost("virtual-host-1")));
    assertThat(selectorRef.get().getRoutingConfigs().get(f1)).isEqualTo(
            ServerRoutingConfig.create(f1.getHttpConnectionManager().httpFilterConfigs(),
            Collections.singletonList(createVirtualHost("virtual-host-1"))));

    xdsClient.rdsWatchers.get("r0").onError(Status.CANCELLED);
    assertThat(selectorRef.get().getRoutingConfigs().get(f1)).isEqualTo(
            ServerRoutingConfig.create(f1.getHttpConnectionManager().httpFilterConfigs(),
                    Collections.singletonList(createVirtualHost("virtual-host-1"))));

    xdsClient.rdsWatchers.get("r0").onResourceDoesNotExist("r0");
    assertThat(selectorRef.get().getRoutingConfigs().get(f1)).isEqualTo(ServerRoutingConfig.create(
            ImmutableList.<NamedFilterConfig>of(), ImmutableList.<VirtualHost>of())
    );
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
    try {
      start.get(5000, TimeUnit.MILLISECONDS);
      fail("Start should throw exception");
    } catch (ExecutionException ex) {
      assertThat(ex.getCause()).isInstanceOf(IOException.class);
    }
    verify(listener, times(1)).onNotServing(any(StatusException.class));
    verify(mockBuilder, times(1)).build();
    FilterChain filterChain0 = createFilterChain("filter-chain-0", createRds("rds"));
    SslContextProviderSupplier sslSupplier0 = filterChain0.getSslContextProviderSupplier();
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain0), null);
    xdsClient.ldsWatcher.onError(Status.INTERNAL);
    assertThat(selectorRef.get()).isSameInstanceAs(FilterChainSelector.NO_FILTER_CHAIN);
    assertThat(xdsClient.rdsWatchers).isEmpty();
    verify(mockBuilder, times(1)).build();
    verify(listener, times(2)).onNotServing(any(StatusException.class));
    assertThat(sslSupplier0.isShutdown()).isFalse();

    when(mockServer.start()).thenThrow(new IOException("error!"))
            .thenReturn(mockServer);
    FilterChain filterChain1 = createFilterChain("filter-chain-1", createRds("rds"));
    SslContextProviderSupplier sslSupplier1 = filterChain1.getSslContextProviderSupplier();
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain1), null);
    assertThat(sslSupplier0.isShutdown()).isTrue();
    xdsClient.deliverRdsUpdate("rds",
            Collections.singletonList(createVirtualHost("virtual-host-1")));
    RdsResourceWatcher saveRdsWatcher = xdsClient.rdsWatchers.get("rds");
    assertThat(executor.forwardNanos(RETRY_DELAY_NANOS)).isEqualTo(1);
    verify(mockBuilder, times(1)).build();
    verify(mockServer, times(2)).start();
    verify(listener, times(1)).onServing();
    assertThat(selectorRef.get().getRoutingConfigs()).isEqualTo(ImmutableMap.of(
            filterChain1, ServerRoutingConfig.create(
                    filterChain1.getHttpConnectionManager().httpFilterConfigs(),
                    Collections.singletonList(createVirtualHost("virtual-host-1")))
    ));
    // xds update after start
    xdsClient.deliverRdsUpdate("rds",
            Collections.singletonList(createVirtualHost("virtual-host-2")));
    assertThat(sslSupplier1.isShutdown()).isFalse();
    xdsClient.ldsWatcher.onError(Status.DEADLINE_EXCEEDED);
    verify(mockBuilder, times(1)).build();
    verify(mockServer, times(2)).start();
    verify(listener, times(2)).onNotServing(any(StatusException.class));
    assertThat(selectorRef.get().getRoutingConfigs()).isEqualTo(ImmutableMap.of(
            filterChain1, ServerRoutingConfig.create(
                    filterChain1.getHttpConnectionManager().httpFilterConfigs(),
                    Collections.singletonList(createVirtualHost("virtual-host-2")))
    ));
    assertThat(sslSupplier1.isShutdown()).isFalse();

    // not serving after serving
    xdsClient.ldsWatcher.onResourceDoesNotExist(ldsResource);
    assertThat(xdsClient.rdsWatchers).isEmpty();
    verify(mockServer, times(3)).shutdown();
    when(mockServer.isShutdown()).thenReturn(true);
    assertThat(selectorRef.get()).isSameInstanceAs(FilterChainSelector.NO_FILTER_CHAIN);
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
    SslContextProviderSupplier sslSupplier2 = filterChain2.getSslContextProviderSupplier();
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain2), null);
    xdsClient.deliverRdsUpdate("rds",
            Collections.singletonList(createVirtualHost("virtual-host-1")));
    assertThat(sslSupplier1.isShutdown()).isTrue();
    verify(mockBuilder, times(2)).build();
    when(mockServer.isShutdown()).thenReturn(false);
    verify(mockServer, times(3)).start();
    verify(listener, times(1)).onServing();
    verify(listener, times(3)).onNotServing(any(StatusException.class));
    assertThat(selectorRef.get().getRoutingConfigs()).isEqualTo(ImmutableMap.of(
            filterChain2, ServerRoutingConfig.create(
                    filterChain2.getHttpConnectionManager().httpFilterConfigs(),
                    Collections.singletonList(createVirtualHost("virtual-host-1")))
    ));
    assertThat(executor.numPendingTasks()).isEqualTo(1);
    xdsClient.ldsWatcher.onResourceDoesNotExist(ldsResource);
    verify(mockServer, times(4)).shutdown();
    verify(listener, times(4)).onNotServing(any(StatusException.class));
    when(mockServer.isShutdown()).thenReturn(true);
    assertThat(executor.numPendingTasks()).isEqualTo(0);
    assertThat(sslSupplier2.isShutdown()).isTrue();

    // serving after not serving
    FilterChain filterChain3 = createFilterChain("filter-chain-2", createRds("rds"));
    SslContextProviderSupplier sslSupplier3 = filterChain3.getSslContextProviderSupplier();
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain3), null);
    xdsClient.deliverRdsUpdate("rds",
            Collections.singletonList(createVirtualHost("virtual-host-1")));
    verify(mockBuilder, times(3)).build();
    verify(mockServer, times(4)).start();
    verify(listener, times(1)).onServing();
    when(mockServer.isShutdown()).thenReturn(false);
    verify(listener, times(4)).onNotServing(any(StatusException.class));
    assertThat(selectorRef.get().getRoutingConfigs()).isEqualTo(ImmutableMap.of(
            filterChain3, ServerRoutingConfig.create(
                    filterChain3.getHttpConnectionManager().httpFilterConfigs(),
                    Collections.singletonList(createVirtualHost("virtual-host-1")))
    ));
    xdsServerWrapper.shutdown();
    verify(mockServer, times(5)).shutdown();
    assertThat(sslSupplier3.isShutdown()).isTrue();
    when(mockServer.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
    assertThat(xdsServerWrapper.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void interceptor_notServerInterceptor() throws Exception {
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
    ServerRoutingConfig routingConfig = createRoutingConfig("/FooService/barMethod",
            "foo.google.com", "filter-type-url");
    ServerCall<Void, Void> serverCall = mock(ServerCall.class);
    when(serverCall.getAttributes()).thenReturn(
            Attributes.newBuilder().set(ATTR_SERVER_ROUTING_CONFIG, routingConfig).build());
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
    assertThat(status.getDescription()).isEqualTo(
            "HttpFilterConfig(type URL: filter-type-url) is not supported on server-side.");
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
    ServerRoutingConfig routingConfig = createRoutingConfig("/FooService/barMethod",
            "foo.google.com", "filter-type-url");
    ServerCall<Void, Void> serverCall = mock(ServerCall.class);
    when(serverCall.getAttributes()).thenReturn(
            Attributes.newBuilder().set(ATTR_SERVER_ROUTING_CONFIG, routingConfig).build());
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
    ServerRoutingConfig routingConfig = createRoutingConfig("/FooService/barMethod",
            "foo.google.com", "filter-type-url");
    ServerCall<Void, Void> serverCall = mock(ServerCall.class);
    when(serverCall.getAttributes()).thenReturn(
            Attributes.newBuilder().set(ATTR_SERVER_ROUTING_CONFIG, routingConfig).build());
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
    ServerRoutingConfig failingConfig = ServerRoutingConfig.create(
            ImmutableList.<NamedFilterConfig>of(), ImmutableList.<VirtualHost>of());
    ServerCall<Void, Void> serverCall = mock(ServerCall.class);
    when(serverCall.getAttributes()).thenReturn(
            Attributes.newBuilder().set(ATTR_SERVER_ROUTING_CONFIG, failingConfig).build());

    ServerCallHandler<Void, Void> next = mock(ServerCallHandler.class);
    interceptor.interceptCall(serverCall, new Metadata(), next);
    verify(next, never()).startCall(any(ServerCall.class), any(Metadata.class));
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(serverCall).close(statusCaptor.capture(), any(Metadata.class));
    Status status = statusCaptor.getValue();
    assertThat(status.getCode()).isEqualTo(Status.UNAVAILABLE.getCode());
    assertThat(status.getDescription()).isEqualTo(
            "Missing xDS routing config. RDS config unavailable.");
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
    when(serverCall.getMethodDescriptor()).thenReturn(createMethod("FooService/barMethod"));
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

  private static FilterChain createFilterChain(String name, HttpConnectionManager hcm) {
    return new EnvoyServerProtoData.FilterChain(name, createMatch(),
            hcm, createTls(), tlsContextManager);
  }

  private static VirtualHost createVirtualHost(String name) {
    return VirtualHost.create(
            name, Collections.singletonList("auth"), new ArrayList<Route>(),
            ImmutableMap.<String, FilterConfig>of());
  }

  private static HttpConnectionManager createRds(String name) {
    return HttpConnectionManager.forRdsName(0L, name,
            Arrays.asList(new NamedFilterConfig("named-config-" + name, null)));
  }

  private static EnvoyServerProtoData.FilterChainMatch createMatch() {
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

  private static ServerRoutingConfig createRoutingConfig(String path, String domain,
                                                         String filterType) {
    RouteMatch routeMatch =
            RouteMatch.create(
                    PathMatcher.fromPath(path, true),
                    Collections.<HeaderMatcher>emptyList(), null);
    VirtualHost virtualHost  = VirtualHost.create(
            "v1", Collections.singletonList(domain),
            Arrays.asList(Route.forAction(routeMatch, null,
                    ImmutableMap.<String, FilterConfig>of())),
            Collections.<String, FilterConfig>emptyMap());
    FilterConfig f0 = mock(FilterConfig.class);
    when(f0.typeUrl()).thenReturn(filterType);
    return ServerRoutingConfig.create(
            Arrays.asList(new NamedFilterConfig("filter-config-name-0", f0)),
            Collections.singletonList(virtualHost)
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
