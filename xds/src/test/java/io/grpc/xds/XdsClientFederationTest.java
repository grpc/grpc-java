/*
 * Copyright 2023 The gRPC Authors
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
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.Filter.NamedFilterConfig;
import io.grpc.xds.XdsClient.ResourceWatcher;
import io.grpc.xds.XdsListenerResource.LdsUpdate;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for xDS control plane federation scenarios.
 */
@RunWith(JUnit4.class)
public class XdsClientFederationTest {

  @Mock
  private ResourceWatcher<LdsUpdate> mockDirectPathWatcher;

  @Mock
  private ResourceWatcher<LdsUpdate> mockWatcher;

  @Rule
  public ControlPlaneRule trafficdirector = new ControlPlaneRule().setServerHostName("test-server");

  @Rule
  public ControlPlaneRule directpathPa = new ControlPlaneRule().setServerHostName(
      "xdstp://server-one/envoy.config.listener.v3.Listener/test-server");

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private boolean originalFederationStatus;

  @Before
  public void setUp() throws XdsInitializationException {
    originalFederationStatus = BootstrapperImpl.enableFederation;
    BootstrapperImpl.enableFederation = true;

    SharedXdsClientPoolProvider clientPoolProvider = new SharedXdsClientPoolProvider();
    clientPoolProvider.setBootstrapOverride(defaultBootstrapOverride());
    xdsClientPool = clientPoolProvider.getOrCreate();
    xdsClient = xdsClientPool.getObject();
  }

  @After
  public void cleanUp() throws InterruptedException {
    BootstrapperImpl.enableFederation = originalFederationStatus;
    xdsClientPool.returnObject(xdsClient);
  }

  /**
   * Assures that resource deletions happening in one control plane do not trigger deletion events
   * in watchers of resources on other control planes.
   */
  @Test
  public void isolatedResourceDeletions() throws InterruptedException {
    trafficdirector.setLdsConfig(ControlPlaneRule.buildServerListener(),
        ControlPlaneRule.buildClientListener("test-server"));
    directpathPa.setLdsConfig(ControlPlaneRule.buildServerListener(),
        ControlPlaneRule.buildClientListener(
            "xdstp://server-one/envoy.config.listener.v3.Listener/test-server"));

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), "test-server", mockWatcher);
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),
        "xdstp://server-one/envoy.config.listener.v3.Listener/test-server", mockDirectPathWatcher);

    verify(mockWatcher, timeout(2000)).onChanged(
        LdsUpdate.forApiListener(
            HttpConnectionManager.forRdsName(0, "route-config.googleapis.com", ImmutableList.of(
                new NamedFilterConfig("terminal-filter", RouterFilter.ROUTER_CONFIG)))));
    verify(mockDirectPathWatcher, timeout(2000)).onChanged(
        LdsUpdate.forApiListener(
            HttpConnectionManager.forRdsName(0, "route-config.googleapis.com", ImmutableList.of(
                new NamedFilterConfig("terminal-filter", RouterFilter.ROUTER_CONFIG)))));

    // By setting the LDS config with a new server name we effectively make the old server to go
    // away as it is not in the configuration anymore. This change in one control plane (here the
    // "normal TrafficDirector" one) should not trigger an onResourceDoesNotExist() call on a
    // watcher of another control plane (here the DirectPath one).
    trafficdirector.setLdsConfig(ControlPlaneRule.buildServerListener(),
        ControlPlaneRule.buildClientListener("new-server"));
    verify(mockWatcher, timeout(20000)).onResourceDoesNotExist("test-server");
    verify(mockDirectPathWatcher, times(0)).onResourceDoesNotExist(
            "xdstp://server-one/envoy.config.listener.v3.Listener/test-server");
  }

  /**
   * Assures that when an {@link XdsClient} is asked to add cluster locality stats it appropriately
   * starts {@link LoadReportClient}s to do that.
   */
  @Test
  public void lrsClientsStartedForLocalityStats() throws InterruptedException, ExecutionException {
    trafficdirector.setLdsConfig(ControlPlaneRule.buildServerListener(),
        ControlPlaneRule.buildClientListener("test-server"));
    directpathPa.setLdsConfig(ControlPlaneRule.buildServerListener(),
        ControlPlaneRule.buildClientListener(
            "xdstp://server-one/envoy.config.listener.v3.Listener/test-server"));

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), "test-server", mockWatcher);
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),
        "xdstp://server-one/envoy.config.listener.v3.Listener/test-server", mockDirectPathWatcher);

    // With two control planes and a watcher for each, there should be two LRS clients.
    assertThat(xdsClient.getServerLrsClientMap().size()).isEqualTo(2);

    // When the XdsClient is asked to report locality stats for a control plane server, the
    // corresponding LRS client should be started
    for (Entry<ServerInfo, LoadReportClient> entry : xdsClient.getServerLrsClientMap().entrySet()) {
      xdsClient.addClusterLocalityStats(entry.getKey(), "clusterName", "edsServiceName",
          Locality.create("", "", ""));
      waitForSyncContext(xdsClient);
      assertThat(entry.getValue().lrsStream).isNotNull();
    }
  }


  /**
   * Assures that when an {@link XdsClient} is asked to add cluster locality stats it appropriately
   * starts {@link LoadReportClient}s to do that.
   */
  @Test
  public void lrsClientsStartedForDropStats() throws InterruptedException, ExecutionException {
    trafficdirector.setLdsConfig(ControlPlaneRule.buildServerListener(),
        ControlPlaneRule.buildClientListener("test-server"));
    directpathPa.setLdsConfig(ControlPlaneRule.buildServerListener(),
        ControlPlaneRule.buildClientListener(
            "xdstp://server-one/envoy.config.listener.v3.Listener/test-server"));

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), "test-server", mockWatcher);
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),
        "xdstp://server-one/envoy.config.listener.v3.Listener/test-server", mockDirectPathWatcher);

    // With two control planes and a watcher for each, there should be two LRS clients.
    assertThat(xdsClient.getServerLrsClientMap().size()).isEqualTo(2);

    // When the XdsClient is asked to report drop stats for a control plane server, the
    // corresponding LRS client should be started
    for (Entry<ServerInfo, LoadReportClient> entry : xdsClient.getServerLrsClientMap().entrySet()) {
      xdsClient.addClusterDropStats(entry.getKey(), "clusterName", "edsServiceName");
      waitForSyncContext(xdsClient);
      assertThat(entry.getValue().lrsStream).isNotNull();
    }
  }

  /**
   * Assures that {@link LoadReportClient}s have distinct {@link LoadStatsManager2}s so that they
   * only report on the traffic for their own control plane.
   */
  @Test
  public void lrsClientsHaveDistinctLoadStatsManagers() throws InterruptedException {
    trafficdirector.setLdsConfig(ControlPlaneRule.buildServerListener(),
        ControlPlaneRule.buildClientListener("test-server"));
    directpathPa.setLdsConfig(ControlPlaneRule.buildServerListener(),
        ControlPlaneRule.buildClientListener(
            "xdstp://server-one/envoy.config.listener.v3.Listener/test-server"));

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), "test-server", mockWatcher);
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),
        "xdstp://server-one/envoy.config.listener.v3.Listener/test-server", mockDirectPathWatcher);

    // With two control planes and a watcher for each, there should be two LRS clients.
    assertThat(xdsClient.getServerLrsClientMap().size()).isEqualTo(2);

    // Collect the LoadStatManagers and make sure they are distinct for each control plane.
    HashSet<LoadStatsManager2> loadStatManagers = new HashSet<>();
    for (Entry<ServerInfo, LoadReportClient> entry : xdsClient.getServerLrsClientMap().entrySet()) {
      xdsClient.addClusterDropStats(entry.getKey(), "clusterName", "edsServiceName");
      loadStatManagers.add(entry.getValue().loadStatsManager);
    }
    assertThat(loadStatManagers).containsNoDuplicates();
  }

  // Waits for all SynchronizationContext tasks to finish, assuming no new ones get added.
  private static void waitForSyncContext(XdsClient xdsClient)
      throws InterruptedException, ExecutionException {
    // We use this method just to have a task added to the sync context queue and wait for it to
    // finish.
    xdsClient.getSubscribedResourcesMetadataSnapshot().get();
  }

  private Map<String, ?> defaultBootstrapOverride() {
    return ImmutableMap.of(
        "node", ImmutableMap.of(
            "id", UUID.randomUUID().toString(),
            "cluster", "cluster0"),
        "xds_servers", ImmutableList.of(
            ImmutableMap.of(
                "server_uri", "localhost:" + trafficdirector.getServer().getPort(),
                "channel_creds", Collections.singletonList(
                    ImmutableMap.of("type", "insecure")
                ),
                "server_features", Collections.singletonList("xds_v3")
            )
        ),
        "authorities", ImmutableMap.of(
            "", ImmutableMap.of(),
            "server-one", ImmutableMap.of(
                "xds_servers", ImmutableList.of(
                    ImmutableMap.of(
                        "server_uri", "localhost:" + directpathPa.getServer().getPort(),
                        "channel_creds", Collections.singletonList(
                            ImmutableMap.of("type", "insecure")
                        ),
                        "server_features", Collections.singletonList("xds_v3")
                    )
                )
            ),
            "server-two", ImmutableMap.of()
        )
    );
  }
}
