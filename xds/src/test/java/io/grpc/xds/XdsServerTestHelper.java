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
import io.grpc.InsecureChannelCredentials;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.EnvoyServerProtoData.ConnectionSourceType;
import io.grpc.xds.EnvoyServerProtoData.FilterChain;
import io.grpc.xds.EnvoyServerProtoData.Listener;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.Filter.NamedFilterConfig;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.XdsListenerResource.LdsUpdate;
import io.grpc.xds.XdsRouteConfigureResource.RdsUpdate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
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
    EnvoyServerProtoData.Listener listener = buildTestListener("listener1", "10.1.2.3",
        ImmutableList.of(), tlsContext, null, tlsContextManager);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    xdsClient.deliverLdsUpdate(listenerUpdate);
  }

  static void generateListenerUpdate(
      FakeXdsClient xdsClient, ImmutableList<Integer> sourcePorts,
      EnvoyServerProtoData.DownstreamTlsContext tlsContext,
      EnvoyServerProtoData.DownstreamTlsContext tlsContextForDefaultFilterChain,
      TlsContextManager tlsContextManager) {
    EnvoyServerProtoData.Listener listener = buildTestListener("listener1", "10.1.2.3", sourcePorts,
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
            name, address, ImmutableList.of(filterChain1), defaultFilterChain);
    return listener;
  }

  static final class FakeXdsClientPoolFactory
        implements XdsNameResolverProvider.XdsClientPoolFactory {

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
    public ObjectPool<XdsClient> get() {
      throw new UnsupportedOperationException("Should not be called");
    }

    @Override
    public ObjectPool<XdsClient> getOrCreate() throws XdsInitializationException {
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
  }

  static final class FakeXdsClient extends XdsClient {
    boolean shutdown;
    SettableFuture<String> ldsResource = SettableFuture.create();
    ResourceWatcher<LdsUpdate> ldsWatcher;
    CountDownLatch rdsCount = new CountDownLatch(1);
    final Map<String, ResourceWatcher<RdsUpdate>> rdsWatchers = new HashMap<>();

    @Override
    public TlsContextManager getTlsContextManager() {
      return null;
    }

    @Override
    public BootstrapInfo getBootstrapInfo() {
      return BOOTSTRAP_INFO;
    }

    @Override
    @SuppressWarnings("unchecked")
    <T extends ResourceUpdate> void watchXdsResource(XdsResourceType<T> resourceType,
                                                     String resourceName,
                                                     ResourceWatcher<T> watcher) {
      switch (resourceType.typeName()) {
        case "LDS":
          assertThat(ldsWatcher).isNull();
          ldsWatcher = (ResourceWatcher<LdsUpdate>) watcher;
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
    <T extends ResourceUpdate> void cancelXdsResourceWatch(XdsResourceType<T> type,
                                                           String resourceName,
                                ResourceWatcher<T> watcher) {
      switch (type.typeName()) {
        case "LDS":
          assertThat(ldsWatcher).isNotNull();
          ldsResource = null;
          ldsWatcher = null;
          break;
        case "RDS":
          rdsWatchers.remove(resourceName);
          break;
        default:
      }
    }

    @Override
    void shutdown() {
      shutdown = true;
    }

    @Override
    boolean isShutDown() {
      return shutdown;
    }

    void deliverLdsUpdate(List<FilterChain> filterChains,
                          FilterChain defaultFilterChain) {
      ldsWatcher.onChanged(LdsUpdate.forTcpListener(Listener.create(
              "listener", "0.0.0.0:1", ImmutableList.copyOf(filterChains), defaultFilterChain)));
    }

    void deliverLdsUpdate(LdsUpdate ldsUpdate) {
      ldsWatcher.onChanged(ldsUpdate);
    }

    void deliverRdsUpdate(String rdsName, List<VirtualHost> virtualHosts) {
      rdsWatchers.get(rdsName).onChanged(new RdsUpdate(virtualHosts));
    }
  }
}
