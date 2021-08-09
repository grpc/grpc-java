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
import static io.grpc.xds.XdsServerWrapper.RETRY_DELAY_NANOS;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.InsecureChannelCredentials;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.internal.FakeClock;
import io.grpc.xds.EnvoyServerProtoData.FilterChain;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.Filter.NamedFilterConfig;
import io.grpc.xds.FilterChainMatchingProtocolNegotiators.FilterChainMatchingHandler.FilterChainSelector;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.XdsServerBuilder.XdsServingStatusListener;
import io.grpc.xds.XdsServerTestHelper.FakeXdsClient;
import io.grpc.xds.XdsServerTestHelper.FakeXdsClientPoolFactory;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.sds.SslContextProviderSupplier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
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
  private XdsServerWrapper xdsServerWrapper;

  @Before
  public void setup() {
    when(mockBuilder.build()).thenReturn(mockServer);
    xdsServerWrapper = new XdsServerWrapper("0.0.0.0:1", mockBuilder, listener,
            selectorRef, new FakeXdsClientPoolFactory(xdsClient),
            executor.getScheduledExecutorService());
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
            selectorRef, new FakeXdsClientPoolFactory(xdsClient));
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
    EnvoyServerProtoData.FilterChain f0 = createFilterChain("filter-chain-0", hcm_virtual);
    SslContextProviderSupplier sslSupplier = f0.getSslContextProviderSupplier();
    xdsClient.deliverLdsUpdate(Collections.singletonList(f0), null);
    start.get(5000, TimeUnit.MILLISECONDS);
    verify(mockServer).start();
    xdsServerWrapper.shutdown();
    assertThat(xdsServerWrapper.isShutdown()).isTrue();
    assertThat(xdsClient.ldsResource).isNull();
    assertThat(xdsClient.shutdown).isTrue();
    verify(mockServer).shutdown();
    assertThat(sslSupplier.isShutdown()).isTrue();
    when(mockServer.isTerminated()).thenReturn(true);
    when(mockServer.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
    assertThat(xdsServerWrapper.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    xdsServerWrapper.awaitTermination();
    assertThat(xdsServerWrapper.isTerminated()).isTrue();
    assertThat(start.get()).isSameInstanceAs(xdsServerWrapper);
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
    when(mockServer.isShutdown()).thenReturn(true);
    when(mockServer.isTerminated()).thenReturn(true);
    assertThat(sslSupplier.isShutdown()).isTrue();
    assertThat(executor.getPendingTasks().size()).isEqualTo(0);
    verify(listener, never()).onNotServing(any(Throwable.class));
    verify(listener, never()).onServing();
    xdsServerWrapper.awaitTermination();
    assertThat(xdsServerWrapper.isTerminated()).isTrue();
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
    assertThat(selector.getFilterChains()).isEqualTo(Collections.singletonList(filterChain));
    verify(listener).onServing();
    verify(mockServer).start();
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
    try {
      start.get(5000, TimeUnit.MILLISECONDS);
      fail("Start should throw exception");
    } catch (ExecutionException ex) {
      assertThat(ex.getCause()).isInstanceOf(IOException.class);
      assertThat(ex.getCause().getMessage()).isEqualTo("error!");
    }
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
    verify(mockServer, times(1)).shutdown();
    when(mockServer.isShutdown()).thenReturn(true);
    xdsClient.ldsWatcher.onError(Status.INTERNAL);
    assertThat(selectorRef.get()).isSameInstanceAs(FilterChainSelector.NO_FILTER_CHAIN);
    verify(mockBuilder, times(1)).build();
    verify(listener, times(2)).onNotServing(any(StatusException.class));
    verify(mockServer, times(1)).shutdown();

    when(mockServer.start()).thenThrow(new IOException("error!"))
            .thenReturn(mockServer);
    when(mockServer.isShutdown()).thenReturn(true).thenReturn(false);
    FilterChain filterChain = createFilterChain("filter-chain-1", createRds("rds"));
    SslContextProviderSupplier sslSupplier = filterChain.getSslContextProviderSupplier();
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain), null);
    assertThat(executor.forwardNanos(RETRY_DELAY_NANOS)).isEqualTo(1);
    verify(mockBuilder, times(2)).build();
    verify(mockServer, times(2)).start();
    verify(listener, times(1)).onServing();
    verify(listener, times(2)).onNotServing(any(StatusException.class));
    assertThat(selectorRef.get().getFilterChains()).isEqualTo(
            Collections.singletonList(filterChain));
    assertThat(selectorRef.get().getDefaultSslContextProviderSupplier()).isNull();
    assertThat(sslSupplier.isShutdown()).isFalse();

    // xds update after start
    filterChain = createFilterChain("filter-chain-2", createRds("rds"));
    FilterChain f1 = createFilterChain("filter-chain-2-0", createRds("rds"));
    SslContextProviderSupplier s1 = filterChain.getSslContextProviderSupplier();
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain), f1);

    verify(mockBuilder, times(2)).build();
    verify(mockServer, times(2)).start();
    verify(listener, times(1)).onServing();
    verify(listener, times(2)).onNotServing(any(StatusException.class));
    assertThat(selectorRef.get().getFilterChains())
            .isEqualTo(Collections.singletonList(filterChain));
    assertThat(selectorRef.get().getDefaultSslContextProviderSupplier())
            .isEqualTo(f1.getSslContextProviderSupplier());
    assertThat(sslSupplier.isShutdown()).isTrue();
    assertThat(s1.isShutdown()).isFalse();

    // not serving after serving
    xdsClient.ldsWatcher.onError(Status.INTERNAL);
    verify(mockServer, times(2)).shutdown();
    when(mockServer.isShutdown()).thenReturn(true);
    assertThat(selectorRef.get()).isSameInstanceAs(FilterChainSelector.NO_FILTER_CHAIN);
    verify(listener, times(3)).onNotServing(any(StatusException.class));
    assertThat(s1.isShutdown()).isTrue();

    // cancel retry
    when(mockServer.start()).thenThrow(new IOException("error1!"))
            .thenThrow(new IOException("error2!"))
            .thenReturn(mockServer);
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain), null);
    verify(mockBuilder, times(3)).build();
    when(mockServer.isShutdown()).thenReturn(false);
    verify(mockServer, times(3)).start();
    verify(listener, times(1)).onServing();
    verify(listener, times(3)).onNotServing(any(StatusException.class));
    assertThat(selectorRef.get().getFilterChains()).isEqualTo(Collections.singletonList(
            filterChain)
    );
    assertThat(executor.numPendingTasks()).isEqualTo(1);
    xdsClient.ldsWatcher.onResourceDoesNotExist(ldsResource);
    verify(mockServer, times(3)).shutdown();
    when(mockServer.isShutdown()).thenReturn(true);
    verify(listener, times(4)).onNotServing(any(StatusException.class));
    assertThat(executor.numPendingTasks()).isEqualTo(0);

    // serving after not serving
    xdsClient.deliverLdsUpdate(Collections.singletonList(filterChain), null);
    verify(mockBuilder, times(4)).build();
    verify(mockServer, times(4)).start();
    verify(listener, times(1)).onServing();
    verify(listener, times(4)).onNotServing(any(StatusException.class));
    assertThat(executor.forwardNanos(RETRY_DELAY_NANOS)).isEqualTo(1);
    verify(listener, times(2)).onServing();
    assertThat(selectorRef.get().getFilterChains()).isEqualTo(Collections.singletonList(
            filterChain)
    );
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

  private HttpConnectionManager createRds(String name) {
    return HttpConnectionManager.forRdsName(0L, name,
            new ArrayList<NamedFilterConfig>());
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
