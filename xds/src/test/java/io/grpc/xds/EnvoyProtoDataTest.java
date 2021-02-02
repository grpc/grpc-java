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

import com.google.common.collect.ImmutableMap;
import com.google.common.testing.EqualsTester;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.Durations;
import io.grpc.xds.EnvoyProtoData.Address;
import io.grpc.xds.EnvoyProtoData.ClusterStats;
import io.grpc.xds.EnvoyProtoData.ClusterStats.DroppedRequests;
import io.grpc.xds.EnvoyProtoData.EndpointLoadMetricStats;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.EnvoyProtoData.UpstreamLocalityStats;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link EnvoyProtoData}.
 */
@RunWith(JUnit4.class)
public class EnvoyProtoDataTest {

  @Test
  public void locality_convertToAndFromLocalityProto() {
    io.envoyproxy.envoy.config.core.v3.Locality locality =
        io.envoyproxy.envoy.config.core.v3.Locality.newBuilder()
            .setRegion("test_region")
            .setZone("test_zone")
            .setSubZone("test_subzone")
            .build();
    Locality xdsLocality = Locality.fromEnvoyProtoLocality(locality);
    assertThat(xdsLocality.getRegion()).isEqualTo("test_region");
    assertThat(xdsLocality.getZone()).isEqualTo("test_zone");
    assertThat(xdsLocality.getSubZone()).isEqualTo("test_subzone");

    io.envoyproxy.envoy.api.v2.core.Locality convertedLocality =
        xdsLocality.toEnvoyProtoLocalityV2();
    assertThat(convertedLocality.getRegion()).isEqualTo("test_region");
    assertThat(convertedLocality.getZone()).isEqualTo("test_zone");
    assertThat(convertedLocality.getSubZone()).isEqualTo("test_subzone");
  }

  @Test
  public void locality_equal() {
    new EqualsTester()
        .addEqualityGroup(
            new Locality("region-a", "zone-a", "subzone-a"),
            new Locality("region-a", "zone-a", "subzone-a"))
        .addEqualityGroup(
            new Locality("region", "zone", "subzone")
        )
        .addEqualityGroup(
            new Locality("", "", ""),
            new Locality("", "", ""))
        .testEquals();
  }

  @SuppressWarnings("deprecation")
  @Test
  public void convertNode() {
    Node node = Node.newBuilder()
        .setId("node-id")
        .setCluster("cluster")
        .setMetadata(
            ImmutableMap.of(
                "TRAFFICDIRECTOR_INTERCEPTION_PORT",
                "ENVOY_PORT",
                "TRAFFICDIRECTOR_NETWORK_NAME",
                "VPC_NETWORK_NAME"))
        .setLocality(new Locality("region", "zone", "subzone"))
        .addListeningAddresses(new Address("www.foo.com", 8080))
        .addListeningAddresses(new Address("www.bar.com", 8088))
        .setBuildVersion("v1")
        .setUserAgentName("agent")
        .setUserAgentVersion("1.1")
        .addClientFeatures("feature-1")
        .addClientFeatures("feature-2")
        .build();
    io.envoyproxy.envoy.config.core.v3.Node nodeProto =
        io.envoyproxy.envoy.config.core.v3.Node.newBuilder()
            .setId("node-id")
            .setCluster("cluster")
            .setMetadata(Struct.newBuilder()
                .putFields("TRAFFICDIRECTOR_INTERCEPTION_PORT",
                    Value.newBuilder().setStringValue("ENVOY_PORT").build())
                .putFields("TRAFFICDIRECTOR_NETWORK_NAME",
                    Value.newBuilder().setStringValue("VPC_NETWORK_NAME").build()))
            .setLocality(
                io.envoyproxy.envoy.config.core.v3.Locality.newBuilder()
                    .setRegion("region")
                    .setZone("zone")
                    .setSubZone("subzone"))
            .addListeningAddresses(
                io.envoyproxy.envoy.config.core.v3.Address.newBuilder()
                    .setSocketAddress(
                        io.envoyproxy.envoy.config.core.v3.SocketAddress.newBuilder()
                            .setAddress("www.foo.com")
                            .setPortValue(8080)))
            .addListeningAddresses(
                io.envoyproxy.envoy.config.core.v3.Address.newBuilder()
                    .setSocketAddress(
                        io.envoyproxy.envoy.config.core.v3.SocketAddress.newBuilder()
                            .setAddress("www.bar.com")
                            .setPortValue(8088)))
            .setUserAgentName("agent")
            .setUserAgentVersion("1.1")
            .addClientFeatures("feature-1")
            .addClientFeatures("feature-2")
            .build();
    assertThat(node.toEnvoyProtoNode()).isEqualTo(nodeProto);

    @SuppressWarnings("deprecation") // Deprecated v2 API setBuildVersion().
    io.envoyproxy.envoy.api.v2.core.Node nodeProtoV2 =
        io.envoyproxy.envoy.api.v2.core.Node.newBuilder()
            .setId("node-id")
            .setCluster("cluster")
            .setMetadata(Struct.newBuilder()
                .putFields("TRAFFICDIRECTOR_INTERCEPTION_PORT",
                    Value.newBuilder().setStringValue("ENVOY_PORT").build())
                .putFields("TRAFFICDIRECTOR_NETWORK_NAME",
                    Value.newBuilder().setStringValue("VPC_NETWORK_NAME").build()))
            .setLocality(
                io.envoyproxy.envoy.api.v2.core.Locality.newBuilder()
                    .setRegion("region")
                    .setZone("zone")
                    .setSubZone("subzone"))
            .addListeningAddresses(
                io.envoyproxy.envoy.api.v2.core.Address.newBuilder()
                    .setSocketAddress(
                        io.envoyproxy.envoy.api.v2.core.SocketAddress.newBuilder()
                            .setAddress("www.foo.com")
                            .setPortValue(8080)))
            .addListeningAddresses(
                io.envoyproxy.envoy.api.v2.core.Address.newBuilder()
                    .setSocketAddress(
                        io.envoyproxy.envoy.api.v2.core.SocketAddress.newBuilder()
                            .setAddress("www.bar.com")
                            .setPortValue(8088)))
            .setBuildVersion("v1")
            .setUserAgentName("agent")
            .setUserAgentVersion("1.1")
            .addClientFeatures("feature-1")
            .addClientFeatures("feature-2")
            .build();
    assertThat(node.toEnvoyProtoNodeV2()).isEqualTo(nodeProtoV2);
  }

  @Test
  public void locality_hash() {
    assertThat(new Locality("region", "zone", "subzone").hashCode())
        .isEqualTo(new Locality("region", "zone","subzone").hashCode());
  }

  // TODO(chengyuanzhang): add test for other data types.

  @Test
  public void clusterStats_convertToEnvoyProto() {
    ClusterStats clusterStats =
        ClusterStats.newBuilder()
            .setClusterName("cluster1")
            .setClusterServiceName("backend-service1")
            .setLoadReportIntervalNanos(1234)
            .setTotalDroppedRequests(123)
            .addUpstreamLocalityStats(UpstreamLocalityStats.newBuilder()
                .setLocality(new Locality("region1", "zone1", "subzone1"))
                .setTotalErrorRequests(1)
                .setTotalRequestsInProgress(2)
                .setTotalSuccessfulRequests(100)
                .setTotalIssuedRequests(103)
                .addLoadMetricStats(EndpointLoadMetricStats.newBuilder()
                    .setMetricName("metric1")
                    .setNumRequestsFinishedWithMetric(1000)
                    .setTotalMetricValue(0.5D)
                    .build())
                .build())
            .addDroppedRequests(new DroppedRequests("category1", 100))
            .build();

    io.envoyproxy.envoy.config.endpoint.v3.ClusterStats clusterStatsProto =
        clusterStats.toEnvoyProtoClusterStats();
    assertThat(clusterStatsProto).isEqualTo(
        io.envoyproxy.envoy.config.endpoint.v3.ClusterStats.newBuilder()
            .setClusterName("cluster1")
            .setClusterServiceName("backend-service1")
            .setLoadReportInterval(Durations.fromNanos(1234))
            .setTotalDroppedRequests(123)
            .addUpstreamLocalityStats(
                io.envoyproxy.envoy.config.endpoint.v3.UpstreamLocalityStats.newBuilder()
                    .setLocality(
                        new Locality("region1", "zone1", "subzone1").toEnvoyProtoLocality())
                    .setTotalErrorRequests(1)
                    .setTotalRequestsInProgress(2)
                    .setTotalSuccessfulRequests(100)
                    .setTotalIssuedRequests(103)
                    .addLoadMetricStats(
                        io.envoyproxy.envoy.config.endpoint.v3.EndpointLoadMetricStats.newBuilder()
                            .setMetricName("metric1")
                            .setNumRequestsFinishedWithMetric(1000)
                            .setTotalMetricValue(0.5D)))
            .addDroppedRequests(
                io.envoyproxy.envoy.config.endpoint.v3.ClusterStats.DroppedRequests.newBuilder()
                    .setCategory("category1")
                    .setDroppedCount(100))
            .build());

    io.envoyproxy.envoy.api.v2.endpoint.ClusterStats clusterStatsProtoV2 =
        clusterStats.toEnvoyProtoClusterStatsV2();
    assertThat(clusterStatsProtoV2).isEqualTo(
        io.envoyproxy.envoy.api.v2.endpoint.ClusterStats.newBuilder()
            .setClusterName("cluster1")
            .setClusterServiceName("backend-service1")
            .setLoadReportInterval(Durations.fromNanos(1234))
            .setTotalDroppedRequests(123)
            .addUpstreamLocalityStats(
                io.envoyproxy.envoy.api.v2.endpoint.UpstreamLocalityStats.newBuilder()
                    .setLocality(
                        new Locality("region1", "zone1", "subzone1").toEnvoyProtoLocalityV2())
                    .setTotalErrorRequests(1)
                    .setTotalRequestsInProgress(2)
                    .setTotalSuccessfulRequests(100)
                    .setTotalIssuedRequests(103)
                    .addLoadMetricStats(
                        io.envoyproxy.envoy.api.v2.endpoint.EndpointLoadMetricStats.newBuilder()
                            .setMetricName("metric1")
                            .setNumRequestsFinishedWithMetric(1000)
                            .setTotalMetricValue(0.5D)))
            .addDroppedRequests(
                io.envoyproxy.envoy.api.v2.endpoint.ClusterStats.DroppedRequests.newBuilder()
                    .setCategory("category1")
                    .setDroppedCount(100))
            .build());
  }
}
