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

import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.xds.XdsClient.ResourceWatcher;
import io.grpc.xds.XdsListenerResource.LdsUpdate;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for xDS control plane federation scenarios.
 */
@RunWith(JUnit4.class)
public class XdsClientFederationTest {

  @Mock
  private ResourceWatcher<LdsUpdate> mockDirectPathWatcher;

  @Mock
  private ResourceWatcher<LdsUpdate> mockWatcher;

  private static final String SERVER_LISTENER_TEMPLATE_NO_REPLACEMENT =
      "grpc/server?udpa.resource.listening_address=";

  @Rule
  public ControlPlaneRule trafficdirector = new ControlPlaneRule("test-server");

  @Rule
  public ControlPlaneRule directpathPa = new ControlPlaneRule(
      "xdstp://server-one/envoy.config.listener.v3.Listener/test-server");

  private XdsClient xdsClient;
  private boolean originalFederationStatus;

  @Before
  public void setUp() throws XdsInitializationException {
    MockitoAnnotations.initMocks(this);

    originalFederationStatus = BootstrapperImpl.enableFederation;
    BootstrapperImpl.enableFederation = true;

    SharedXdsClientPoolProvider clientPoolProvider = new SharedXdsClientPoolProvider();
    clientPoolProvider.setBootstrapOverride(defaultBootstrapOverride());
    xdsClient = clientPoolProvider.getOrCreate().getObject();
  }

  @After
  public void cleanUp() throws InterruptedException {
    BootstrapperImpl.enableFederation = originalFederationStatus;
  }

  // Assures that resource deletions happening in one control plane do not trigger deletion events
  /// in watchers of resources on other control planes.
  @Test
  public void isolatedResourceDeletions() throws InterruptedException {
    // Add the mock watcher for the normal server resource. The test control plane will send
    // a deletion event.
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), "test-server", mockWatcher);
    verify(mockWatcher, timeout(20000)).onResourceDoesNotExist("test-server");

    // Add the watcher for the DirectPath server.
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),
        "xdstp://server-one/envoy.config.listener.v3.Listener/test-server", mockDirectPathWatcher);
    verify(mockDirectPathWatcher, timeout(20000)).onResourceDoesNotExist(
        "xdstp://server-one/envoy.config.listener.v3.Listener/test-server");
    verify(mockWatcher, timeout(20000)).onResourceDoesNotExist("test-server");

    // Add a normal server resource and observe a changed event on the normal watcher and
    // a onResourceDoesNotExist() on the DirectPath watcher as it's resource is not yet created.
    trafficdirector.setLdsConfig(ControlPlaneRule.buildServerListener(),
        ControlPlaneRule.buildClientListener("test-server"));
    verify(mockWatcher, timeout(20000)).onChanged(isA(LdsUpdate.class));
    verify(mockDirectPathWatcher, timeout(20000)).onResourceDoesNotExist(
        "xdstp://server-one/envoy.config.listener.v3.Listener/test-server");

    // Modifying the DirectPath server resource triggers a changed event on the DirectPath watcher.
    directpathPa.setLdsConfig(ControlPlaneRule.buildServerListener(),
        ControlPlaneRule.buildClientListener(
            "xdstp://server-one/envoy.config.listener.v3.Listener/test-server"));
    verify(mockDirectPathWatcher, timeout(20000)).onChanged(isA(LdsUpdate.class));

    // And the crux of the test: deleting a resource (here by renaming it) in one control plane
    // (here the "normal TrafficDirector" one) should not trigger an onResourceDoesNotExist() call
    // on a watcher of another control plane (here the DirectPath one).
    trafficdirector.setLdsConfig(ControlPlaneRule.buildServerListener(),
        ControlPlaneRule.buildClientListener("new-server"));
    verify(mockWatcher, timeout(20000)).onResourceDoesNotExist("test-server");
    verifyNoMoreInteractions(mockDirectPathWatcher);
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
        ),
        "server_listener_resource_name_template", SERVER_LISTENER_TEMPLATE_NO_REPLACEMENT
    );
  }
}
