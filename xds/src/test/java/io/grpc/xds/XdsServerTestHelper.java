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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.SettableFuture;
import io.envoyproxy.envoy.config.core.v3.SocketAddress.Protocol;
import io.grpc.InsecureChannelCredentials;
import io.grpc.MetricRecorder;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.EnvoyServerProtoData.ConnectionSourceType;
import io.grpc.xds.EnvoyServerProtoData.FilterChain;
import io.grpc.xds.EnvoyServerProtoData.Listener;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.Filter.NamedFilterConfig;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.XdsListenerResource.LdsUpdate;
import io.grpc.xds.XdsRouteConfigureResource.RdsUpdate;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.Bootstrapper.BootstrapInfo;
import io.grpc.xds.client.EnvoyProtoData;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsInitializationException;
import io.grpc.xds.client.XdsResourceType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

/**
 * Helper methods related to {@link XdsServerBuilder} and related classes.
 */
public class XdsServerTestHelper {

  private static final String SERVER_URI = "trafficdirector.googleapis.com";
  private static final String NODE_ID =
      "projects/42/networks/default/nodes/5c85b298-6f5b-4722-b74a-f7d1f0ccf5ad";
  private static final EnvoyProtoData.Node BOOTSTRAP_NODE =
      EnvoyProtoData.Node.newBuilder().setId(NODE_ID).build();
  static final Bootstrapper.BootstrapInfo BOOTSTRAP_INFO =
      Bootstrapper.BootstrapInfo.builder()
          .servers(Arrays.asList(
              Bootstrapper.ServerInfo.create(
                  SERVER_URI, InsecureChannelCredentials.create())))
          .node(BOOTSTRAP_NODE)
          .serverListenerResourceNameTemplate("grpc/server?udpa.resource.listening_address=%s")
          .build();

  static void generateListenerUpdate(FakeXdsClient xdsClient,
                                     EnvoyServerProtoData.DownstreamTlsContext tlsContext,
                                     TlsContextManager tlsContextManager) {
    EnvoyServerProtoData.Listener listener = buildTestListener("listener1", "0.0.0.0:0",
        ImmutableList.of(), tlsContext, null, tlsContextManager);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    xdsClient.deliverLdsUpdate(listenerUpdate);
  }

  static void generateListenerUpdate(
      FakeXdsClient xdsClient, ImmutableList<Integer> sourcePorts,
      EnvoyServerProtoData.DownstreamTlsContext tlsContext,
      EnvoyServerProtoData.DownstreamTlsContext tlsContextForDefaultFilterChain,
      TlsContextManager tlsContextManager) {
    EnvoyServerProtoData.Listener listener = buildTestListener(
        "listener1", "0.0.0.0:7000", sourcePorts,
        tlsContext, tlsContextForDefaultFilterChain, tlsContextManager);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    xdsClient.deliverLdsUpdate(listenerUpdate);
  }

  static EnvoyServerProtoData.Listener buildTestListener(
      String name, String address, ImmutableList<Integer> sourcePorts,
      EnvoyServerProtoData.DownstreamTlsContext tlsContext,
      EnvoyServerProtoData.DownstreamTlsContext tlsContextForDefaultFilterChain,
      TlsContextManager tlsContextManager) {
    EnvoyServerProtoData.FilterChainMatch filterChainMatch1 =
        EnvoyServerProtoData.FilterChainMatch.create(
            0,
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            ConnectionSourceType.ANY,
            sourcePorts,
            ImmutableList.of(),
            "");
    EnvoyServerProtoData.FilterChainMatch defaultFilterChainMatch =
        EnvoyServerProtoData.FilterChainMatch.create(
            0,
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            ConnectionSourceType.ANY,
            ImmutableList.of(),
            ImmutableList.of(),
            "");
    VirtualHost virtualHost =
            VirtualHost.create(
                    "virtual-host", Collections.singletonList("auth"), new ArrayList<Route>(),
                    ImmutableMap.<String, FilterConfig>of());
    HttpConnectionManager httpConnectionManager = HttpConnectionManager.forVirtualHosts(
            0L, Collections.singletonList(virtualHost), new ArrayList<NamedFilterConfig>());
    EnvoyServerProtoData.FilterChain filterChain1 = EnvoyServerProtoData.FilterChain.create(
        "filter-chain-foo", filterChainMatch1, httpConnectionManager, tlsContext,
        tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain = EnvoyServerProtoData.FilterChain.create(
        "filter-chain-bar", defaultFilterChainMatch, httpConnectionManager,
        tlsContextForDefaultFilterChain, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        EnvoyServerProtoData.Listener.create(
            name, address, ImmutableList.of(filterChain1), defaultFilterChain, Protocol.TCP);
    return listener;
  }

  static final class FakeXdsClientPoolFactory
        implements XdsClientPoolFactory {

    private XdsClient xdsClient;
    Map<String, ?> savedBootstrap;

    FakeXdsClientPoolFactory(XdsClient xdsClient) {
      this.xdsClient = xdsClient;
    }

    @Override
    public void setBootstrapOverride(Map<String, ?> bootstrap) {
      this.savedBootstrap = bootstrap;
    }

    @Override
    @Nullable
    public ObjectPool<XdsClient> get(String target) {
      throw new UnsupportedOperationException("Should not be called");
    }

    @Override
    public ObjectPool<XdsClient> getOrCreate(String target, MetricRecorder metricRecorder)
        throws XdsInitializationException {
      return new ObjectPool<XdsClient>() {
        @Override
        public XdsClient getObject() {
          return xdsClient;
        }

        @Override
        public XdsClient returnObject(Object object) {
          xdsClient.shutdown();
          return null;
        }
      };
    }

    @Override
    public List<String> getTargets() {
      return Collections.singletonList("fake-target");
    }
  }

  // Implementation details:
  // 1. Use `synchronized` in methods where XdsClientImpl uses its own `syncContext`.
  // 2. Use `serverExecutor` via `execute()` in methods where XdsClientImpl uses watcher's executor.
  static final class FakeXdsClient extends XdsClient {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private boolean shutdown;
    @Nullable SettableFuture<String> ldsResource = SettableFuture.create();
    @Nullable ResourceWatcher<LdsUpdate> ldsWatcher;
    private CountDownLatch rdsCount = new CountDownLatch(1);
    final Map<String, ResourceWatcher<RdsUpdate>> rdsWatchers = new HashMap<>();
    @Nullable private volatile Executor serverExecutor;

    @Override
    public TlsContextManager getSecurityConfig() {
      return null;
    }

    @Override
    public BootstrapInfo getBootstrapInfo() {
      return BOOTSTRAP_INFO;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T extends ResourceUpdate> void watchXdsResource(
        XdsResourceType<T> resourceType,
        String resourceName,
        ResourceWatcher<T> watcher,
        Executor executor) {
      if (serverExecutor != null) {
        assertThat(executor).isEqualTo(serverExecutor);
      }

      switch (resourceType.typeName()) {
        case "LDS":
          assertThat(ldsWatcher).isNull();
          ldsWatcher = (ResourceWatcher<LdsUpdate>) watcher;
          serverExecutor = executor;
          ldsResource.set(resourceName);
          break;
        case "RDS":
          //re-register is not allowed.
          assertThat(rdsWatchers.put(resourceName, (ResourceWatcher<RdsUpdate>)watcher)).isNull();
          rdsCount.countDown();
          break;
        default:
      }
    }

    @Override
    public synchronized <T extends ResourceUpdate>  void cancelXdsResourceWatch(
        XdsResourceType<T> type, String resourceName, ResourceWatcher<T> watcher) {
      switch (type.typeName()) {
        case "LDS":
          assertThat(ldsWatcher).isNotNull();
          ldsResource = null;
          ldsWatcher = null;
          serverExecutor = null;
          break;
        case "RDS":
          rdsWatchers.remove(resourceName);
          break;
        default:
      }
    }

    @Override
    public synchronized void shutdown() {
      shutdown = true;
    }

    @Override
    public synchronized boolean isShutDown() {
      return shutdown;
    }

    public void awaitRds(Duration timeout) throws InterruptedException, TimeoutException {
      if (!rdsCount.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new TimeoutException("Timeout " + timeout + " waiting for RDSs");
      }
    }

    public void setExpectedRdsCount(int count) {
      rdsCount = new CountDownLatch(count);
    }

    private void execute(Runnable action) {
      // This method ensures that all watcher updates:
      // - Happen after the server started watching LDS.
      // - Are executed within the sync context of the server.
      //
      // Note that this doesn't guarantee that any of the RDS watchers are created.
      // Tests should use setExpectedRdsCount(int) and awaitRds() for that.
      awaitLdsResource(DEFAULT_TIMEOUT);
      serverExecutor.execute(action);
    }

    private String awaitLdsResource(Duration timeout) {
      if (ldsResource == null) {
        throw new IllegalStateException("xDS resource update after watcher cancel");
      }
      try {
        return ldsResource.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (ExecutionException | TimeoutException e) {
        throw new RuntimeException("Can't resolve LDS resource name in " + timeout, e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }

    void deliverLdsUpdateWithApiListener(long httpMaxStreamDurationNano,
        List<VirtualHost> virtualHosts) {
      execute(() -> {
        ldsWatcher.onChanged(LdsUpdate.forApiListener(HttpConnectionManager.forVirtualHosts(
            httpMaxStreamDurationNano, virtualHosts, null)));
      });
    }

    void deliverLdsUpdate(LdsUpdate ldsUpdate) {
      execute(() -> ldsWatcher.onChanged(ldsUpdate));
    }

    void deliverLdsUpdate(
        List<FilterChain> filterChains,
        @Nullable FilterChain defaultFilterChain) {
      deliverLdsUpdate(LdsUpdate.forTcpListener(Listener.create("listener", "0.0.0.0:1",
          ImmutableList.copyOf(filterChains), defaultFilterChain, Protocol.TCP)));
    }

    void deliverLdsUpdate(FilterChain filterChain, @Nullable FilterChain defaultFilterChain) {
      deliverLdsUpdate(ImmutableList.of(filterChain), defaultFilterChain);
    }

    void deliverLdsResourceNotFound() {
      execute(() -> ldsWatcher.onResourceDoesNotExist(awaitLdsResource(DEFAULT_TIMEOUT)));
    }

    void deliverRdsUpdate(String resourceName, List<VirtualHost> virtualHosts) {
      execute(() -> rdsWatchers.get(resourceName).onChanged(new RdsUpdate(virtualHosts)));
    }

    void deliverRdsUpdate(String resourceName, VirtualHost virtualHost) {
      deliverRdsUpdate(resourceName, ImmutableList.of(virtualHost));
    }

    void deliverRdsResourceNotFound(String resourceName) {
      execute(() -> rdsWatchers.get(resourceName).onResourceDoesNotExist(resourceName));
    }
  }
}
