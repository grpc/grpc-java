/*
 * Copyright 2019 The gRPC Authors
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.ChannelLogger;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.xds.EdsLoadBalancer.EdsConfig;
import io.grpc.xds.XdsClient.EndpointUpdate;
import io.grpc.xds.XdsClient.EndpointWatcher;
import java.util.ArrayList;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link EdsLoadBalancer}.
 */
@RunWith(JUnit4.class)
public class EdsLoadBalancerTest {
  private static final String FAKE_CLUSTER_NAME = "cluster1";

  @Rule
  public final ExpectedException thrown = ExpectedException.none();

  @Mock
  private LocalityStore localityStore;
  @Mock
  private XdsClient xdsClient;
  @Mock
  private ChannelLogger channelLogger;

  private EdsLoadBalancer edsLoadBalancer;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);

    edsLoadBalancer = new EdsLoadBalancer(
        FAKE_CLUSTER_NAME, localityStore, xdsClient, channelLogger);
  }

  @Test
  public void canHandleEmptyAddressListFromNameResolution() {
    assertThat(edsLoadBalancer.canHandleEmptyAddressListFromNameResolution()).isTrue();
  }

  @Test
  public void missingEdsConfig() {
    ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .build();

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Expecting an EDS LB config, but the actual LB config is 'null'");
    edsLoadBalancer.handleResolvedAddresses(resolvedAddresses);
  }

  @Test
  public void invalidConfigType() {
    Object invalidConfig = new Object();
    ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setLoadBalancingPolicyConfig(invalidConfig)
        .build();

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Expecting an EDS LB config, but the actual LB config is '"
        + invalidConfig + "'");
    edsLoadBalancer.handleResolvedAddresses(resolvedAddresses);
  }

  @Test
  public void invalidClusterNameInEdsConfig() {
    ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setLoadBalancingPolicyConfig(new EdsConfig("wrongCluster", new Object()))
        .build();

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage(
        "The cluster name 'wrongCluster' from EDS LB config does not match the name '"
            + FAKE_CLUSTER_NAME + "' provided by CDS");
    edsLoadBalancer.handleResolvedAddresses(resolvedAddresses);
  }

  @Test
  public void validEdsConfig_watcherUpdate_shutdown() {
    // handle valid EdsConfig
    ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setLoadBalancingPolicyConfig(new EdsConfig(FAKE_CLUSTER_NAME, new Object()))
        .build();
    edsLoadBalancer.handleResolvedAddresses(resolvedAddresses);

    ArgumentCaptor<EndpointWatcher> endpointWatcherCaptor = ArgumentCaptor.forClass(null);
    verify(xdsClient).watchEndpointData(eq(FAKE_CLUSTER_NAME), endpointWatcherCaptor.capture());

    // watcher receives EndpointUpdate
    EnvoyProtoData.Locality xdsLocality =
        new EnvoyProtoData.Locality("region1", "zone1", "subzone1");
    EnvoyProtoData.LocalityLbEndpoints localityLbEndpoints =
        new EnvoyProtoData.LocalityLbEndpoints(new ArrayList<EnvoyProtoData.LbEndpoint>(), 3, 0);
    ImmutableMap<EnvoyProtoData.Locality, EnvoyProtoData.LocalityLbEndpoints> localityInfoMap =
        ImmutableMap.of(xdsLocality, localityLbEndpoints);
    ImmutableList<EnvoyProtoData.DropOverload> dropOverloads =
        ImmutableList.of(new EnvoyProtoData.DropOverload("cat1", 10));
    EndpointUpdate endpointUpdate = new EndpointUpdate();
    endpointUpdate.localityInfoMap = localityInfoMap;
    endpointUpdate.dropOverloads = dropOverloads;
    endpointWatcherCaptor.getValue().onEndpointChanged(endpointUpdate);

    verify(localityStore).updateLocalityStore(localityInfoMap);
    verify(localityStore).updateDropPercentage(dropOverloads);

    // shutdown
    edsLoadBalancer.shutdown();
    verify(xdsClient).cancelEndpointDataWatch(endpointWatcherCaptor.getValue());
    verify(localityStore).reset();
  }

  @Test
  public void shutdown() {
    edsLoadBalancer.shutdown();
    verify(localityStore).reset();
    verify(xdsClient, never()).cancelEndpointDataWatch(any(EndpointWatcher.class));
  }
}
