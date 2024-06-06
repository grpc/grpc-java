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

import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.Status;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsInitializationException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public class XdsClientFallbackTest {
  private static final Logger log = Logger.getLogger(XdsClientFallbackTest.class.getName());

  public static final String MAIN_SERVER = "main-server";
  public static final String FALLBACK_SERVER = "fallback-server";
  private static final String DUMMY_TARGET = "TEST_TARGET";
  public static final String RDS_NAME = "route-config.googleapis.com";
  public static final HttpConnectionManager MAIN_HTTP_CONNECTION_MANAGER =
      HttpConnectionManager.forRdsName(0, RDS_NAME, ImmutableList.of(
          new Filter.NamedFilterConfig(MAIN_SERVER, RouterFilter.ROUTER_CONFIG)));
  public static final HttpConnectionManager FALLBACK_HTTP_CONNECTION_MANAGER =
      HttpConnectionManager.forRdsName(0, RDS_NAME, ImmutableList.of(
          new Filter.NamedFilterConfig(FALLBACK_SERVER, RouterFilter.ROUTER_CONFIG)));
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;

  private XdsClient.ResourceWatcher<XdsListenerResource.LdsUpdate> raalLdsWatcher =
      new XdsClient.ResourceWatcher<XdsListenerResource.LdsUpdate>() {

        @Override
        public void onChanged(XdsListenerResource.LdsUpdate update) {
          log.info("LDS update: " + update);
        }

        @Override
        public void onError(Status error) {
          log.info("LDS update error: " + error.getDescription());
        }

        @Override
        public void onResourceDoesNotExist(String resourceName) {
          log.info("LDS resource does not exist: " + resourceName);
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
  private XdsClient.ResourceWatcher<XdsClusterResource.CdsUpdate> cdsWatcher;

  @Rule(order = 0)
  public ControlPlaneRule mainTdServer =
      new ControlPlaneRule(8090).setServerHostName(MAIN_SERVER);

  @Rule(order = 1)
  public ControlPlaneRule fallbackServer =
      new ControlPlaneRule(8095).setServerHostName(MAIN_SERVER);

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Before
  public void setUp() throws XdsInitializationException {
    setAdsConfig(mainTdServer, MAIN_SERVER);
    setAdsConfig(fallbackServer, FALLBACK_SERVER);

    SharedXdsClientPoolProvider clientPoolProvider = new SharedXdsClientPoolProvider();
    clientPoolProvider.setBootstrapOverride(defaultBootstrapOverride());
    //    clientPoolProvider.setBackoffProviderClass(MinimalBackoffPolicyProvider.class);
    xdsClientPool = clientPoolProvider.getOrCreate(DUMMY_TARGET);
  }

  public static void setAdsConfig(ControlPlaneRule controlPlane, String serverName) {

    controlPlane.setLdsConfig(ControlPlaneRule.buildServerListener(),
        ControlPlaneRule.buildClientListener(MAIN_SERVER, serverName));

    controlPlane.setRdsConfig(ControlPlaneRule.buildRouteConfiguration(MAIN_SERVER, serverName));
    controlPlane.setCdsConfig(ControlPlaneRule.buildCluster());
    InetSocketAddress edsInetSocketAddress =
        (InetSocketAddress) controlPlane.getServer().getListenSockets().get(0);
    controlPlane.setEdsConfig(
        ControlPlaneRule.buildClusterLoadAssignment(edsInetSocketAddress.getHostName(),
            edsInetSocketAddress.getPort()));
    log.info(
        String.format("Set ADS config for %s with address %s", serverName, edsInetSocketAddress));
  }

  @After
  public void cleanUp() {
    xdsClientPool.returnObject(xdsClient);
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
    verify(ldsWatcher, timeout(10000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(
            MAIN_HTTP_CONNECTION_MANAGER));

    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(), RDS_NAME, rdsWatcher);
    verify(rdsWatcher, timeout(10000)).onChanged(any());
  }

  @Test
  public void mainServerDown_fallbackServerUp()  throws Exception {
    mainTdServer.getServer().shutdownNow();
    restartServer(TdServerType.FALLBACK);
    xdsClient = xdsClientPool.getObject();
    log.info("Fallback port = " + fallbackServer.getServer().getPort());

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);

    verify(ldsWatcher, timeout(10000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(
            FALLBACK_HTTP_CONNECTION_MANAGER));
  }

  @Test
  public void both_down_restart_main()  throws Exception {
    mainTdServer.getServer().shutdownNow();
    fallbackServer.getServer().shutdownNow();
    xdsClient = xdsClientPool.getObject();

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);

    Thread.sleep(5000);
    verify(ldsWatcher, never()).onChanged(any());

    restartServer(TdServerType.MAIN);

    xdsClient.watchXdsResource(
        XdsRouteConfigureResource.getInstance(), RDS_NAME, rdsWatcher);

    verify(ldsWatcher, timeout(10000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(MAIN_HTTP_CONNECTION_MANAGER));
  }

  @Test
  public void mainDown_fallbackUp_restart_main()  throws Exception {
    mainTdServer.getServer().shutdownNow();
    fallbackServer.restartTdServer();
    xdsClient = xdsClientPool.getObject();
    InOrder inOrder = inOrder(ldsWatcher);

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);
    inOrder.verify(ldsWatcher, timeout(10000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(FALLBACK_HTTP_CONNECTION_MANAGER));
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(), RDS_NAME, rdsWatcher);

    restartServer(TdServerType.MAIN);

    inOrder.verify(ldsWatcher, timeout(10000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(MAIN_HTTP_CONNECTION_MANAGER));
  }

  // Since everything is loaded we shouldn't fallback
  @Test
  public void connect_then_mainServerDown_fallbackServerUp()  throws Exception {
    restartServer(TdServerType.MAIN);
    restartServer(TdServerType.FALLBACK);
    xdsClient = xdsClientPool.getObject();

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);

    verify(ldsWatcher, timeout(10000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(MAIN_HTTP_CONNECTION_MANAGER));

    mainTdServer.getServer().shutdownNow();

    // Shouldn't do fallback since all watchers are loaded
    Thread.sleep(5000);
    verify(ldsWatcher, never()).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(FALLBACK_HTTP_CONNECTION_MANAGER));

    // Should just get from cache
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher2);
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(), RDS_NAME, rdsWatcher);
    verify(ldsWatcher2, timeout(10000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(MAIN_HTTP_CONNECTION_MANAGER));
    verify(ldsWatcher, never()).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(FALLBACK_HTTP_CONNECTION_MANAGER));

    // Asking for something not in cache should force a fallback
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(), "cluster0", cdsWatcher);
    verify(rdsWatcher, timeout(10000)).onChanged(any());
    verify(ldsWatcher, timeout(10000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(FALLBACK_HTTP_CONNECTION_MANAGER));
    verify(ldsWatcher2, timeout(10000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(FALLBACK_HTTP_CONNECTION_MANAGER));
    verify(cdsWatcher, timeout(10000)).onChanged(any());
  }

  private Map<String, ?> defaultBootstrapOverride() {
    return ImmutableMap.of(
        "node", ImmutableMap.of(
            "id", UUID.randomUUID().toString(),
            "cluster", "cluster0"),
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

  static final class MinimalBackoffPolicyProvider implements BackoffPolicy.Provider {
    @Override
    public BackoffPolicy get() {
      return new BackoffPolicy() {
        @Override
        public long nextBackoffNanos() {
          return TimeUnit.MILLISECONDS.toNanos(1000);
        }
      };
    }
  }

  private enum TdServerType {
    MAIN,
    FALLBACK
  }
}
