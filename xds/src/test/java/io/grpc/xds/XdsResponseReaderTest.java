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
import static io.grpc.xds.XdsClientImpl.EDS_TYPE_URL;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.protobuf.Any;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.core.Address;
import io.envoyproxy.envoy.api.v2.core.Locality;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.core.SocketAddress;
import io.envoyproxy.envoy.api.v2.endpoint.Endpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.xds.XdsClient.EndpointUpdate;
import io.grpc.xds.XdsClient.EndpointWatcher;
import io.grpc.xds.XdsClientImpl.EndpointWatchers;
import io.grpc.xds.XdsResponseReader.StreamActivityWatcher;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link XdsResponseReader}.
 */
@RunWith(JUnit4.class)
public class XdsResponseReaderTest {
  private XdsResponseReader xdsResponseReader;
  @Mock
  private StreamActivityWatcher streamActivityWatcher;
  @Mock
  private EndpointWatcher endpointWatcher1;
  @Mock
  private EndpointWatcher endpointWatcher2;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);

    EndpointWatchers endpointWatchers = new EndpointWatchers(Node.getDefaultInstance());
    endpointWatchers.addWatcher("cluster1", endpointWatcher1);
    endpointWatchers.addWatcher("cluster2", endpointWatcher2);

    xdsResponseReader = new XdsResponseReader(
        streamActivityWatcher,
        endpointWatchers,
        new SynchronizationContext(
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread t, Throwable e) {
                throw new AssertionError(e);
              }
            }));
  }

  @Test
  public void onResponse() {
    xdsResponseReader.onNext(DiscoveryResponse.getDefaultInstance());

    verify(streamActivityWatcher).responseReceived();
    verify(endpointWatcher1, never()).onEndpointChanged(any(EndpointUpdate.class));
    verify(endpointWatcher2, never()).onEndpointChanged(any(EndpointUpdate.class));
  }

  @Test
  public void onEdsResponse() {
    Locality localityProto1 = Locality.newBuilder()
        .setRegion("region1").setZone("zone1").setSubZone("subzone1").build();
    LbEndpoint endpoint11 = LbEndpoint.newBuilder()
        .setEndpoint(Endpoint.newBuilder()
            .setAddress(Address.newBuilder()
                .setSocketAddress(SocketAddress.newBuilder()
                    .setAddress("addr11").setPortValue(11))))
        .setLoadBalancingWeight(UInt32Value.of(11))
        .build();
    ClusterLoadAssignment clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        // only cluster2
        .setClusterName("cluster2")
        .addEndpoints(LocalityLbEndpoints.newBuilder()
            .setLocality(localityProto1)
            .addLbEndpoints(endpoint11)
            .setLoadBalancingWeight(UInt32Value.of(1)))
        .build();
    DiscoveryResponse edsResponse = DiscoveryResponse.newBuilder()
        .addResources(Any.pack(clusterLoadAssignment))
        .setTypeUrl(EDS_TYPE_URL)
        .build();
    xdsResponseReader.onNext(edsResponse);

    verify(streamActivityWatcher).responseReceived();
    verify(endpointWatcher2).onEndpointChanged(
        eq(EndpointUpdate.newBuilder().setClusterLoadAssignment(clusterLoadAssignment).build()));
    verify(endpointWatcher1, never()).onEndpointChanged(any(EndpointUpdate.class));
  }

  @Test
  public void onError() {
    Exception fakeException = new Exception("fake exception");
    xdsResponseReader.onError(fakeException);

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(null);
    verify(endpointWatcher1).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCause()).isEqualTo(fakeException);
    verify(endpointWatcher2).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCause()).isEqualTo(fakeException);

    verify(streamActivityWatcher).streamClosed();
  }
}
