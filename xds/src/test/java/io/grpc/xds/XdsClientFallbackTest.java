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

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsInitializationException;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public class XdsClientFallbackTest {
  public static final String MAIN_SERVER = "main-server";
  public static final String FALLBACK_SERVER = "fallback-server";
  private static final String DUMMY_TARGET = "TEST_TARGET";
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  @Mock
  private XdsClient.ResourceWatcher<XdsListenerResource.LdsUpdate> ldsWatcher;

  @Rule
  public ControlPlaneRule mainTdServer = new ControlPlaneRule().setServerHostName(MAIN_SERVER);

  @Rule
  public ControlPlaneRule fallbackServer = new ControlPlaneRule().setServerHostName(FALLBACK_SERVER);

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Before
  public void setUp() throws XdsInitializationException {
    SharedXdsClientPoolProvider clientPoolProvider = new SharedXdsClientPoolProvider();
    clientPoolProvider.setBootstrapOverride(defaultBootstrapOverride());
    xdsClientPool = clientPoolProvider.getOrCreate(DUMMY_TARGET);
    xdsClient = xdsClientPool.getObject();
  }

  @After
  public void cleanUp() {
    xdsClientPool.returnObject(xdsClient);
  }

  private AtomicInteger testInt = new AtomicInteger(0);

  @Test
  public void mainServerDown_fallbackServerUp() throws XdsInitializationException, IOException {
    mainTdServer.getServer().shutdownNow();
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), "test-server", ldsWatcher);
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
}
