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
import static io.grpc.xds.XdsClientTestHelper.buildCluster;
import static io.grpc.xds.XdsClientTestHelper.buildClusterLoadAssignment;
import static io.grpc.xds.XdsClientTestHelper.buildDiscoveryResponse;
import static io.grpc.xds.XdsClientTestHelper.buildDropOverload;
import static io.grpc.xds.XdsClientTestHelper.buildLbEndpoint;
import static io.grpc.xds.XdsClientTestHelper.buildListener;
import static io.grpc.xds.XdsClientTestHelper.buildLocalityLbEndpoints;
import static io.grpc.xds.XdsClientTestHelper.buildRouteConfiguration;
import static io.grpc.xds.XdsClientTestHelper.buildVirtualHost;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.xds.AbstractXdsClient.MessagePrinter;
import io.grpc.xds.AbstractXdsClient.ResourceType;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link AbstractXdsClient}.
 */
@RunWith(JUnit4.class)
public class AbstractXdsClientTest {

  @Test
  public void messagePrinter_printLdsResponse() {
    MessagePrinter printer = new MessagePrinter();
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener("foo.googleapis.com:8080",
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRouteConfig(
                        buildRouteConfiguration("route-foo.googleapis.com",
                            ImmutableList.of(
                                buildVirtualHost(
                                    ImmutableList.of("foo.googleapis.com", "bar.googleapis.com"),
                                    "cluster.googleapis.com"))))
                    .build()))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, ResourceType.LDS.typeUrl(), "0000");

    String expectedString = "{\n"
        + "  \"versionInfo\": \"0\",\n"
        + "  \"resources\": [{\n"
        + "    \"@type\": \"type.googleapis.com/envoy.config.listener.v3.Listener\",\n"
        + "    \"name\": \"foo.googleapis.com:8080\",\n"
        + "    \"address\": {\n"
        + "    },\n"
        + "    \"filterChains\": [{\n"
        + "    }],\n"
        + "    \"apiListener\": {\n"
        + "      \"apiListener\": {\n"
        + "        \"@type\": \"type.googleapis.com/envoy.extensions.filters.network"
        + ".http_connection_manager.v3.HttpConnectionManager\",\n"
        + "        \"routeConfig\": {\n"
        + "          \"name\": \"route-foo.googleapis.com\",\n"
        + "          \"virtualHosts\": [{\n"
        + "            \"name\": \"virtualhost00.googleapis.com\",\n"
        + "            \"domains\": [\"foo.googleapis.com\", \"bar.googleapis.com\"],\n"
        + "            \"routes\": [{\n"
        + "              \"match\": {\n"
        + "                \"prefix\": \"\"\n"
        + "              },\n"
        + "              \"route\": {\n"
        + "                \"cluster\": \"cluster.googleapis.com\"\n"
        + "              }\n"
        + "            }]\n"
        + "          }]\n"
        + "        }\n"
        + "      }\n"
        + "    }\n"
        + "  }],\n"
        + "  \"typeUrl\": \"type.googleapis.com/envoy.config.listener.v3.Listener\",\n"
        + "  \"nonce\": \"0000\"\n"
        + "}";
    String res = printer.print(response);
    assertThat(res).isEqualTo(expectedString);
  }

  @Test
  public void messagePrinter_printRdsResponse() {
    MessagePrinter printer = new MessagePrinter();
    List<Any> routeConfigs =
        ImmutableList.of(
            Any.pack(
                buildRouteConfiguration(
                    "route-foo.googleapis.com",
                    ImmutableList.of(
                        buildVirtualHost(
                            ImmutableList.of("foo.googleapis.com", "bar.googleapis.com"),
                            "cluster.googleapis.com")))));
    DiscoveryResponse response =
        buildDiscoveryResponse("213", routeConfigs, ResourceType.RDS.typeUrl(), "0052");

    String expectedString = "{\n"
        + "  \"versionInfo\": \"213\",\n"
        + "  \"resources\": [{\n"
        + "    \"@type\": \"type.googleapis.com/envoy.config.route.v3.RouteConfiguration\",\n"
        + "    \"name\": \"route-foo.googleapis.com\",\n"
        + "    \"virtualHosts\": [{\n"
        + "      \"name\": \"virtualhost00.googleapis.com\",\n"
        + "      \"domains\": [\"foo.googleapis.com\", \"bar.googleapis.com\"],\n"
        + "      \"routes\": [{\n"
        + "        \"match\": {\n"
        + "          \"prefix\": \"\"\n"
        + "        },\n"
        + "        \"route\": {\n"
        + "          \"cluster\": \"cluster.googleapis.com\"\n"
        + "        }\n"
        + "      }]\n"
        + "    }]\n"
        + "  }],\n"
        + "  \"typeUrl\": \"type.googleapis.com/envoy.config.route.v3.RouteConfiguration\",\n"
        + "  \"nonce\": \"0052\"\n"
        + "}";
    String res = printer.print(response);
    assertThat(res).isEqualTo(expectedString);
  }

  @Test
  public void messagePrinter_printCdsResponse() {
    MessagePrinter printer = new MessagePrinter();
    List<Any> clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-bar.googleapis.com", "service-blaze:cluster-bar", true)),
        Any.pack(buildCluster("cluster-foo.googleapis.com", null, false)));
    DiscoveryResponse response =
        buildDiscoveryResponse("14", clusters, ResourceType.CDS.typeUrl(), "8");

    String expectedString = "{\n"
        + "  \"versionInfo\": \"14\",\n"
        + "  \"resources\": [{\n"
        + "    \"@type\": \"type.googleapis.com/envoy.config.cluster.v3.Cluster\",\n"
        + "    \"name\": \"cluster-bar.googleapis.com\",\n"
        + "    \"type\": \"EDS\",\n"
        + "    \"edsClusterConfig\": {\n"
        + "      \"edsConfig\": {\n"
        + "        \"ads\": {\n"
        + "        }\n"
        + "      },\n"
        + "      \"serviceName\": \"service-blaze:cluster-bar\"\n"
        + "    },\n"
        + "    \"lrsServer\": {\n"
        + "      \"self\": {\n"
        + "      }\n"
        + "    }\n"
        + "  }, {\n"
        + "    \"@type\": \"type.googleapis.com/envoy.config.cluster.v3.Cluster\",\n"
        + "    \"name\": \"cluster-foo.googleapis.com\",\n"
        + "    \"type\": \"EDS\",\n"
        + "    \"edsClusterConfig\": {\n"
        + "      \"edsConfig\": {\n"
        + "        \"ads\": {\n"
        + "        }\n"
        + "      }\n"
        + "    }\n"
        + "  }],\n"
        + "  \"typeUrl\": \"type.googleapis.com/envoy.config.cluster.v3.Cluster\",\n"
        + "  \"nonce\": \"8\"\n"
        + "}";
    String res = printer.print(response);
    assertThat(res).isEqualTo(expectedString);
  }

  @Test
  public void messagePrinter_printEdsResponse() {
    MessagePrinter printer = new MessagePrinter();
    List<Any> clusterLoadAssignments = ImmutableList.of(
        Any.pack(buildClusterLoadAssignment("cluster-foo.googleapis.com",
            ImmutableList.of(
                buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2)),
                    1, 0),
                buildLocalityLbEndpoints("region3", "zone3", "subzone3",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.142.5", 80, HealthStatus.UNHEALTHY, 5)),
                    2, 1)),
            ImmutableList.of(
                buildDropOverload("lb", 200),
                buildDropOverload("throttle", 1000)))));

    DiscoveryResponse response =
        buildDiscoveryResponse("5", clusterLoadAssignments,
            ResourceType.EDS.typeUrl(), "004");

    String expectedString = "{\n"
        + "  \"versionInfo\": \"5\",\n"
        + "  \"resources\": [{\n"
        + "    \"@type\": \"type.googleapis.com/envoy.config.endpoint.v3.ClusterLoadAssignment\",\n"
        + "    \"clusterName\": \"cluster-foo.googleapis.com\",\n"
        + "    \"endpoints\": [{\n"
        + "      \"locality\": {\n"
        + "        \"region\": \"region1\",\n"
        + "        \"zone\": \"zone1\",\n"
        + "        \"subZone\": \"subzone1\"\n"
        + "      },\n"
        + "      \"lbEndpoints\": [{\n"
        + "        \"endpoint\": {\n"
        + "          \"address\": {\n"
        + "            \"socketAddress\": {\n"
        + "              \"address\": \"192.168.0.1\",\n"
        + "              \"portValue\": 8080\n"
        + "            }\n"
        + "          }\n"
        + "        },\n"
        + "        \"healthStatus\": \"HEALTHY\",\n"
        + "        \"loadBalancingWeight\": 2\n"
        + "      }],\n"
        + "      \"loadBalancingWeight\": 1\n"
        + "    }, {\n"
        + "      \"locality\": {\n"
        + "        \"region\": \"region3\",\n"
        + "        \"zone\": \"zone3\",\n"
        + "        \"subZone\": \"subzone3\"\n"
        + "      },\n"
        + "      \"lbEndpoints\": [{\n"
        + "        \"endpoint\": {\n"
        + "          \"address\": {\n"
        + "            \"socketAddress\": {\n"
        + "              \"address\": \"192.168.142.5\",\n"
        + "              \"portValue\": 80\n"
        + "            }\n"
        + "          }\n"
        + "        },\n"
        + "        \"healthStatus\": \"UNHEALTHY\",\n"
        + "        \"loadBalancingWeight\": 5\n"
        + "      }],\n"
        + "      \"loadBalancingWeight\": 2,\n"
        + "      \"priority\": 1\n"
        + "    }],\n"
        + "    \"policy\": {\n"
        + "      \"dropOverloads\": [{\n"
        + "        \"category\": \"lb\",\n"
        + "        \"dropPercentage\": {\n"
        + "          \"numerator\": 200,\n"
        + "          \"denominator\": \"MILLION\"\n"
        + "        }\n"
        + "      }, {\n"
        + "        \"category\": \"throttle\",\n"
        + "        \"dropPercentage\": {\n"
        + "          \"numerator\": 1000,\n"
        + "          \"denominator\": \"MILLION\"\n"
        + "        }\n"
        + "      }]\n"
        + "    }\n"
        + "  }],\n"
        + "  \"typeUrl\": \"type.googleapis.com/envoy.config.endpoint.v3.ClusterLoadAssignment\",\n"
        + "  \"nonce\": \"004\"\n"
        + "}";
    String res = printer.print(response);
    assertThat(res).isEqualTo(expectedString);
  }
}
