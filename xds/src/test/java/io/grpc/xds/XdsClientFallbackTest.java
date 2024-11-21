/*
 * Copyright 2024 The gRPC Authors
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
import static junit.framework.TestCase.assertFalse;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.Status;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.CommonBootstrapperTestUtils;
import io.grpc.xds.client.LoadReportClient;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsClientImpl;
import io.grpc.xds.client.XdsInitializationException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class XdsClientFallbackTest {
  private static final Logger log = Logger.getLogger(XdsClientFallbackTest.class.getName());

  private static final String MAIN_SERVER = "main-server";
  private static final String FALLBACK_SERVER = "fallback-server";
  private static final String DUMMY_TARGET = "TEST_TARGET";
  private static final String RDS_NAME = "route-config.googleapis.com";
  private static final String FALLBACK_RDS_NAME = "fallback-" + RDS_NAME;
  private static final String CLUSTER_NAME = "cluster0";
  private static final String FALLBACK_CLUSTER_NAME = "fallback-" + CLUSTER_NAME;
  private static final String EDS_NAME = "eds-service-0";
  private static final String FALLBACK_EDS_NAME = "fallback-" + EDS_NAME;
  private static final HttpConnectionManager MAIN_HTTP_CONNECTION_MANAGER =
      HttpConnectionManager.forRdsName(0, RDS_NAME, ImmutableList.of(
          new Filter.NamedFilterConfig(MAIN_SERVER, RouterFilter.ROUTER_CONFIG)));
  private static final HttpConnectionManager FALLBACK_HTTP_CONNECTION_MANAGER =
      HttpConnectionManager.forRdsName(0, RDS_NAME, ImmutableList.of(
          new Filter.NamedFilterConfig(FALLBACK_SERVER, RouterFilter.ROUTER_CONFIG)));
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private boolean originalEnableXdsFallback;

  private XdsClient.ResourceWatcher<XdsListenerResource.LdsUpdate> raalLdsWatcher =
      new XdsClient.ResourceWatcher<XdsListenerResource.LdsUpdate>() {

        @Override
        public void onChanged(XdsListenerResource.LdsUpdate update) {
          log.log(Level.FINE, "LDS update: " + update);
        }

        @Override
        public void onError(Status error) {
          log.log(Level.FINE, "LDS update error: " + error.getDescription());
        }

        @Override
        public void onResourceDoesNotExist(String resourceName) {
          log.log(Level.FINE, "LDS resource does not exist: " + resourceName);
        }
      };

  @SuppressWarnings("unchecked")
  private XdsClient.ResourceWatcher<XdsListenerResource.LdsUpdate> ldsWatcher =
      mock(XdsClient.ResourceWatcher.class, delegatesTo(raalLdsWatcher));
  @Mock
  private XdsClient.ResourceWatcher<XdsListenerResource.LdsUpdate> ldsWatcher2;

  @Mock
  private XdsClient.ResourceWatcher<XdsRouteConfigureResource.RdsUpdate> rdsWatcher;
  @Mock
  private XdsClient.ResourceWatcher<XdsRouteConfigureResource.RdsUpdate> rdsWatcher2;
  @Mock
  private XdsClient.ResourceWatcher<XdsRouteConfigureResource.RdsUpdate> rdsWatcher3;

  private XdsClient.ResourceWatcher<XdsClusterResource.CdsUpdate> raalCdsWatcher =
      new XdsClient.ResourceWatcher<XdsClusterResource.CdsUpdate>() {

        @Override
        public void onChanged(XdsClusterResource.CdsUpdate update) {
          log.log(Level.FINE, "CDS update: " + update);
        }

        @Override
        public void onError(Status error) {
          log.log(Level.FINE, "CDS update error: " + error.getDescription());
        }

        @Override
        public void onResourceDoesNotExist(String resourceName) {
          log.log(Level.FINE, "CDS resource does not exist: " + resourceName);
        }
      };

  @SuppressWarnings("unchecked")
  private XdsClient.ResourceWatcher<XdsClusterResource.CdsUpdate> cdsWatcher =
      mock(XdsClient.ResourceWatcher.class, delegatesTo(raalCdsWatcher));
  @Mock
  private XdsClient.ResourceWatcher<XdsClusterResource.CdsUpdate> cdsWatcher2;

  @Rule(order = 0)
  public ControlPlaneRule mainTdServer =
      new ControlPlaneRule(8090).setServerHostName(MAIN_SERVER);

  @Rule(order = 1)
  public ControlPlaneRule fallbackServer =
      new ControlPlaneRule(8095).setServerHostName(MAIN_SERVER);

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Before
  public void setUp() throws XdsInitializationException {
    originalEnableXdsFallback = CommonBootstrapperTestUtils.setEnableXdsFallback(true);
    if (mainTdServer == null) {
      throw new XdsInitializationException("Failed to create ControlPlaneRule for main TD server");
    }
    setAdsConfig(mainTdServer, MAIN_SERVER);
    setAdsConfig(fallbackServer, FALLBACK_SERVER);

    SharedXdsClientPoolProvider clientPoolProvider = new SharedXdsClientPoolProvider();
    clientPoolProvider.setBootstrapOverride(defaultBootstrapOverride());
    xdsClientPool = clientPoolProvider.getOrCreate(DUMMY_TARGET);
  }

  @After
  public void cleanUp() {
    if (xdsClientPool != null) {
      xdsClientPool.returnObject(xdsClient);
    }
    CommonBootstrapperTestUtils.setEnableXdsFallback(originalEnableXdsFallback);
  }

  private static void setAdsConfig(ControlPlaneRule controlPlane, String serverName) {
    InetSocketAddress edsInetSocketAddress =
        (InetSocketAddress) controlPlane.getServer().getListenSockets().get(0);
    boolean isMainServer = serverName.equals(MAIN_SERVER);
    String rdsName = isMainServer
                     ? RDS_NAME
                     : FALLBACK_RDS_NAME;
    String clusterName = isMainServer ? CLUSTER_NAME : FALLBACK_CLUSTER_NAME;
    String edsName = isMainServer ? EDS_NAME : FALLBACK_EDS_NAME;

    controlPlane.setLdsConfig(ControlPlaneRule.buildServerListener(),
        ControlPlaneRule.buildClientListener(MAIN_SERVER, serverName));

    controlPlane.setRdsConfig(rdsName,
        ControlPlaneRule.buildRouteConfiguration(MAIN_SERVER, rdsName, clusterName));
    controlPlane.setCdsConfig(clusterName, ControlPlaneRule.buildCluster(clusterName, edsName));

    controlPlane.setEdsConfig(edsName,
        ControlPlaneRule.buildClusterLoadAssignment(edsInetSocketAddress.getHostName(),
            DataPlaneRule.ENDPOINT_HOST_NAME, edsInetSocketAddress.getPort(), edsName));
    log.log(Level.FINE,
        String.format("Set ADS config for %s with address %s", serverName, edsInetSocketAddress));
  }

  private void restartServer(TdServerType type) {
    switch (type) {
      case MAIN:
        mainTdServer.restartTdServer();
        setAdsConfig(mainTdServer, MAIN_SERVER);
        break;
      case FALLBACK:
        fallbackServer.restartTdServer();
        setAdsConfig(fallbackServer, FALLBACK_SERVER);
        break;
      default:
        throw new IllegalArgumentException("Unknown server type: " + type);
    }
  }

  // This is basically a control test to make sure everything is set up correctly.
  @Test
  public void everything_okay() {
    restartServer(TdServerType.MAIN);
    restartServer(TdServerType.FALLBACK);
    xdsClient = xdsClientPool.getObject();
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);
    verify(ldsWatcher, timeout(5000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(
            MAIN_HTTP_CONNECTION_MANAGER));

    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(), RDS_NAME, rdsWatcher);
    verify(rdsWatcher, timeout(5000)).onChanged(any());
  }

  @Test
  public void mainServerDown_fallbackServerUp() {
    mainTdServer.getServer().shutdownNow();
    restartServer(TdServerType.FALLBACK);
    xdsClient = xdsClientPool.getObject();
    log.log(Level.FINE, "Fallback port = " + fallbackServer.getServer().getPort());

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);

    verify(ldsWatcher, timeout(5000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(
            FALLBACK_HTTP_CONNECTION_MANAGER));
  }

  @Test
  public void both_down_restart_main() {
    mainTdServer.getServer().shutdownNow();
    fallbackServer.getServer().shutdownNow();
    xdsClient = xdsClientPool.getObject();

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);
    verify(ldsWatcher, timeout(5000).times(0)).onChanged(any());

    restartServer(TdServerType.MAIN);

    xdsClient.watchXdsResource(
        XdsRouteConfigureResource.getInstance(), RDS_NAME, rdsWatcher);

    verify(ldsWatcher, timeout(300000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(MAIN_HTTP_CONNECTION_MANAGER));
    mainTdServer.getServer().shutdownNow();
  }

  @Test
  public void mainDown_fallbackUp_restart_main() {
    mainTdServer.getServer().shutdownNow();
    fallbackServer.restartTdServer();
    xdsClient = xdsClientPool.getObject();
    InOrder inOrder = inOrder(ldsWatcher, rdsWatcher, cdsWatcher, cdsWatcher2);

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);
    inOrder.verify(ldsWatcher, timeout(5000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(FALLBACK_HTTP_CONNECTION_MANAGER));
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(), FALLBACK_CLUSTER_NAME, cdsWatcher);
    inOrder.verify(cdsWatcher, timeout(5000)).onChanged(any());
    Object fallbackCpcBlob = ((XdsClientImpl) xdsClient).getActiveCpcForTest(null);

    restartServer(TdServerType.MAIN);

    verify(ldsWatcher, timeout(5000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(MAIN_HTTP_CONNECTION_MANAGER));

    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(), RDS_NAME, rdsWatcher);
    inOrder.verify(rdsWatcher, timeout(5000)).onChanged(any());

    xdsClient.watchXdsResource(XdsClusterResource.getInstance(), CLUSTER_NAME, cdsWatcher2);
    inOrder.verify(cdsWatcher2, timeout(5000)).onChanged(any());

    // verify that connection to fallback server is closed
    assertFalse("Should have disconnected from fallback server",
        ((XdsClientImpl) xdsClient).isCpcBlobConnected(fallbackCpcBlob));

  }

  // This test takes a long time because of the 16 sec timeout for non-existent resource
  @Test
  public void connect_then_mainServerDown_fallbackServerUp() throws InterruptedException {
    restartServer(TdServerType.MAIN);
    restartServer(TdServerType.FALLBACK);
    xdsClient = xdsClientPool.getObject();

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);

    verify(ldsWatcher, timeout(5000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(MAIN_HTTP_CONNECTION_MANAGER));

    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(), RDS_NAME, rdsWatcher);
    verify(rdsWatcher, timeout(5000)).onChanged(any());

    mainTdServer.getServer().shutdownNow();
    TimeUnit.SECONDS.sleep(5); // TODO(lsafran) Use FakeClock so test runs faster

    // Shouldn't do fallback since all watchers are loaded
    verify(ldsWatcher, never()).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(FALLBACK_HTTP_CONNECTION_MANAGER));

    // Should just get from cache
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher2);
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(), RDS_NAME, rdsWatcher2);
    verify(ldsWatcher2, timeout(5000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(MAIN_HTTP_CONNECTION_MANAGER));
    verify(ldsWatcher, never()).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(FALLBACK_HTTP_CONNECTION_MANAGER));
    // Make sure that rdsWatcher wasn't called again
    verify(rdsWatcher, times(1)).onChanged(any());
    verify(rdsWatcher2, timeout(5000)).onChanged(any());

    // Asking for something not in cache should force a fallback
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(), FALLBACK_CLUSTER_NAME, cdsWatcher);
    verify(ldsWatcher, timeout(5000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(FALLBACK_HTTP_CONNECTION_MANAGER));
    verify(ldsWatcher2, timeout(5000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(FALLBACK_HTTP_CONNECTION_MANAGER));
    verify(cdsWatcher, timeout(16000)).onChanged(any());

    xdsClient.watchXdsResource(
        XdsRouteConfigureResource.getInstance(), FALLBACK_RDS_NAME, rdsWatcher3);
    verify(rdsWatcher3, timeout(5000)).onChanged(any());

    // Test that resource defined in main but not fallback is handled correctly
    xdsClient.watchXdsResource(
        XdsClusterResource.getInstance(), CLUSTER_NAME, cdsWatcher2);
    verify(cdsWatcher2, timeout(16000)).onResourceDoesNotExist(eq(CLUSTER_NAME));
  }

  @Test
  public void connect_then_mainServerRestart_fallbackServerdown() {
    restartServer(TdServerType.MAIN);
    xdsClient = xdsClientPool.getObject();

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);

    verify(ldsWatcher, timeout(5000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(MAIN_HTTP_CONNECTION_MANAGER));

    mainTdServer.getServer().shutdownNow();
    fallbackServer.getServer().shutdownNow();

    xdsClient.watchXdsResource(XdsClusterResource.getInstance(), CLUSTER_NAME, cdsWatcher);

    restartServer(TdServerType.MAIN);

    verify(cdsWatcher, timeout(5000)).onChanged(any());
    verify(ldsWatcher, timeout(5000).atLeastOnce()).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(MAIN_HTTP_CONNECTION_MANAGER));
  }

  private Bootstrapper.ServerInfo getLrsServerInfo(String target) {
    for (Map.Entry<Bootstrapper.ServerInfo, LoadReportClient> entry
        : xdsClient.getServerLrsClientMap().entrySet()) {
      if (entry.getKey().target().equals(target)) {
        return entry.getKey();
      }
    }
    return null;
  }

  @Test
  public void used_then_mainServerRestart_fallbackServerUp() throws Exception {
    xdsClient = xdsClientPool.getObject();

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);

    verify(ldsWatcher, timeout(5000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(MAIN_HTTP_CONNECTION_MANAGER));

    restartServer(TdServerType.MAIN);

    assertThat(getLrsServerInfo("localhost:" + fallbackServer.getServer().getPort())).isNull();
    assertThat(getLrsServerInfo("localhost:" + mainTdServer.getServer().getPort())).isNotNull();

    xdsClient.watchXdsResource(XdsClusterResource.getInstance(), CLUSTER_NAME, cdsWatcher);

    verify(cdsWatcher, timeout(5000)).onChanged(any());
    assertThat(getLrsServerInfo("localhost:" + fallbackServer.getServer().getPort())).isNull();
  }

  private Map<String, ?> defaultBootstrapOverride() {
    return ImmutableMap.of(
        "node", ImmutableMap.of(
            "id", UUID.randomUUID().toString(),
            "cluster", CLUSTER_NAME),
         "xds_servers", ImmutableList.of(
            ImmutableMap.of(
                "server_uri", "localhost:" + mainTdServer.getServer().getPort(),
                "channel_creds", Collections.singletonList(
                    ImmutableMap.of("type", "insecure")
                ),
                "server_features", Collections.singletonList("xds_v3")
            ),
            ImmutableMap.of(
                "server_uri", "localhost:" + fallbackServer.getServer().getPort(),
                "channel_creds", Collections.singletonList(
                    ImmutableMap.of("type", "insecure")
                ),
                "server_features", Collections.singletonList("xds_v3")
            )
        ),
        "fallback-policy", "fallback"
      );
  }

  private enum TdServerType {
    MAIN,
    FALLBACK
  }
}
