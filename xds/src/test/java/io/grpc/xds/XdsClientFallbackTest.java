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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.Status;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.LoadReportClient;
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
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public class XdsClientFallbackTest {
  private static final Logger log = Logger.getLogger(XdsClientFallbackTest.class.getName());

  public static final String MAIN_SERVER = "main-server";
  public static final String FALLBACK_SERVER = "fallback-server";
  private static final String DUMMY_TARGET = "TEST_TARGET";
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
      log.fine("LDS update error: " + error);
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      log.fine("LDS resource does not exist: " + resourceName);
    }
  };

//  @Mock
//  private XdsClient.ResourceWatcher<XdsListenerResource.LdsUpdate> ldsWatcher;
    private XdsClient.ResourceWatcher<XdsListenerResource.LdsUpdate> ldsWatcher =
      mock(XdsClient.ResourceWatcher.class, delegatesTo(raalLdsWatcher));

  @Rule(order = 0)
  public ControlPlaneRule mainTdServer = new ControlPlaneRule().setServerHostName(MAIN_SERVER);

  @Rule(order = 1)
  public ControlPlaneRule fallbackServer = new ControlPlaneRule().setServerHostName(FALLBACK_SERVER);

//  @Rule(order = 2)
//  public DataPlaneRule mainDataPlane = new DataPlaneRule(mainTdServer);
//  @Rule(order = 3)
//  public DataPlaneRule fallbackDataPlane = new DataPlaneRule(fallbackServer);


  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Before
  public void setUp() throws XdsInitializationException {
    setAdsConfig(mainTdServer, MAIN_SERVER);
    setAdsConfig(fallbackServer, FALLBACK_SERVER);

    SharedXdsClientPoolProvider clientPoolProvider = new SharedXdsClientPoolProvider();
//    clientPoolProvider.setBackoffProviderClass(MinimalBackoffPolicyProvider.class);
    clientPoolProvider.setBootstrapOverride(defaultBootstrapOverride());
    xdsClientPool = clientPoolProvider.getOrCreate(DUMMY_TARGET);
    xdsClient = xdsClientPool.getObject();
  }

  public void setAdsConfig(ControlPlaneRule controlPlane, String serverName) {
    controlPlane.setLdsConfig(ControlPlaneRule.buildServerListener(),
        ControlPlaneRule.buildClientListener(serverName));
    controlPlane.setRdsConfig(ControlPlaneRule.buildRouteConfiguration(serverName));
    controlPlane.setCdsConfig(ControlPlaneRule.buildCluster());
    InetSocketAddress edsInetSocketAddress =
        (InetSocketAddress) controlPlane.getServer().getListenSockets().get(0);
    controlPlane.setEdsConfig(
        ControlPlaneRule.buildClusterLoadAssignment(edsInetSocketAddress.getHostName(),
            edsInetSocketAddress.getPort()));
  }

  @After
  public void cleanUp() {
    xdsClientPool.returnObject(xdsClient);
  }

  @Test
  public void mainServerDown_fallbackServerUp()  throws Exception {
    mainTdServer.getServer().shutdownNow();
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), FALLBACK_SERVER, ldsWatcher);
    Map<Bootstrapper.ServerInfo, LoadReportClient> serverLrsClientMap =
        xdsClient.getServerLrsClientMap();

    verify(ldsWatcher, timeout(10000)).onChanged(
        XdsListenerResource.LdsUpdate.forApiListener(
            HttpConnectionManager.forRdsName(0, "route-config.googleapis.com", ImmutableList.of(
                new Filter.NamedFilterConfig("terminal-filter", RouterFilter.ROUTER_CONFIG)))));
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
          return TimeUnit.MILLISECONDS.toNanos(100);
        }
      };
    }
  }


}
