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

import com.google.protobuf.Any;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbPolicy;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.core.v3.SelfConfigSource;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.core.v3.TransportSocket;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Policy;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Policy.DropOverload;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.ApiListener;
import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.envoyproxy.envoy.type.v3.FractionalPercent.DenominatorType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link MessagePrinter}.
 */
@RunWith(JUnit4.class)
public class MessagePrinterTest {
  private final MessagePrinter printer = new MessagePrinter();

  @Test
  public void printLdsResponse_v3() {
    Listener listener =
        Listener.newBuilder().setName("foo.googleapis.com:8080")
            .setAddress(
                Address.newBuilder()
                    .setSocketAddress(
                        SocketAddress.newBuilder().setAddress("10.0.0.1").setPortValue(8080)))
            .addFilterChains(
                FilterChain.newBuilder()
                    .addFilters(Filter.getDefaultInstance())
                    .setTransportSocket(
                        TransportSocket.newBuilder()
                            .setName("envoy.transport_sockets.tls")
                            .setTypedConfig(
                                Any.pack(
                                    DownstreamTlsContext.newBuilder()
                                        .setCommonTlsContext(
                                            CommonTlsContext.newBuilder()
                                                .addTlsCertificateSdsSecretConfigs(
                                                    SdsSecretConfig.getDefaultInstance()))
                                        .build()))))
            .setApiListener(
                ApiListener.newBuilder()
                    .setApiListener(
                        Any.pack(HttpConnectionManager.newBuilder()
                            .setRds(
                                Rds.newBuilder()
                                    .setRouteConfigName("route-foo.googleapis.com")
                                    .setConfigSource(
                                        ConfigSource.newBuilder().setAds(
                                            AggregatedConfigSource.getDefaultInstance())))
                            .build())))
            .build();
    DiscoveryResponse response =
        DiscoveryResponse.newBuilder()
            .setTypeUrl("type.googleapis.com/envoy.config.listener.v3.Listener")
            .setVersionInfo("0")
            .addResources(Any.pack(listener))
            .setNonce("0000")
            .build();

    String expectedString = "{\n"
        + "  \"versionInfo\": \"0\",\n"
        + "  \"resources\": [{\n"
        + "    \"@type\": \"type.googleapis.com/envoy.config.listener.v3.Listener\",\n"
        + "    \"name\": \"foo.googleapis.com:8080\",\n"
        + "    \"address\": {\n"
        + "      \"socketAddress\": {\n"
        + "        \"address\": \"10.0.0.1\",\n"
        + "        \"portValue\": 8080\n"
        + "      }\n"
        + "    },\n"
        + "    \"filterChains\": [{\n"
        + "      \"filters\": [{\n"
        + "      }],\n"
        + "      \"transportSocket\": {\n"
        + "        \"name\": \"envoy.transport_sockets.tls\",\n"
        + "        \"typedConfig\": {\n"
        + "          \"@type\": \"type.googleapis.com/envoy.extensions.transport_sockets"
        + ".tls.v3.DownstreamTlsContext\",\n"
        + "          \"commonTlsContext\": {\n"
        + "            \"tlsCertificateSdsSecretConfigs\": [{\n"
        + "            }]\n"
        + "          }\n"
        + "        }\n"
        + "      }\n"
        + "    }],\n"
        + "    \"apiListener\": {\n"
        + "      \"apiListener\": {\n"
        + "        \"@type\": \"type.googleapis.com/envoy.extensions.filters.network"
        + ".http_connection_manager.v3.HttpConnectionManager\",\n"
        + "        \"rds\": {\n"
        + "          \"configSource\": {\n"
        + "            \"ads\": {\n"
        + "            }\n"
        + "          },\n"
        + "          \"routeConfigName\": \"route-foo.googleapis.com\"\n"
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
  public void printRdsResponse_v3() {
    RouteConfiguration routeConfiguration =
        RouteConfiguration.newBuilder()
            .setName("route-foo.googleapis.com")
            .addVirtualHosts(
                VirtualHost.newBuilder()
                    .setName("virtualhost.googleapis.com")
                    .addDomains("foo.googleapis.com")
                    .addDomains("bar.googleapis.com")
                    .addRoutes(
                        Route.newBuilder()
                            .setMatch(
                                RouteMatch.newBuilder().setPath("foo.googleapis.com"))
                            .setRoute(
                                RouteAction.newBuilder()
                                    .setCluster("cluster.googleapis.com"))))
            .build();
    DiscoveryResponse response =
        DiscoveryResponse.newBuilder()
            .setTypeUrl("type.googleapis.com/envoy.config.route.v3.RouteConfiguration")
            .setVersionInfo("0")
            .addResources(Any.pack(routeConfiguration))
            .setNonce("0000")
            .build();

    String expectedString = "{\n"
        + "  \"versionInfo\": \"0\",\n"
        + "  \"resources\": [{\n"
        + "    \"@type\": \"type.googleapis.com/envoy.config.route.v3.RouteConfiguration\",\n"
        + "    \"name\": \"route-foo.googleapis.com\",\n"
        + "    \"virtualHosts\": [{\n"
        + "      \"name\": \"virtualhost.googleapis.com\",\n"
        + "      \"domains\": [\"foo.googleapis.com\", \"bar.googleapis.com\"],\n"
        + "      \"routes\": [{\n"
        + "        \"match\": {\n"
        + "          \"path\": \"foo.googleapis.com\"\n"
        + "        },\n"
        + "        \"route\": {\n"
        + "          \"cluster\": \"cluster.googleapis.com\"\n"
        + "        }\n"
        + "      }]\n"
        + "    }]\n"
        + "  }],\n"
        + "  \"typeUrl\": \"type.googleapis.com/envoy.config.route.v3.RouteConfiguration\",\n"
        + "  \"nonce\": \"0000\"\n"
        + "}";
    String res = printer.print(response);
    assertThat(res).isEqualTo(expectedString);
  }

  @Test
  public void printCdsResponse_v3() {
    Cluster cluster =
        Cluster.newBuilder()
            .setName("cluster-foo.googleapis.com")
            .setEdsClusterConfig(
                EdsClusterConfig.newBuilder()
                    .setServiceName("backend-service-foo.googleapis.com")
                    .setEdsConfig(
                        ConfigSource.newBuilder()
                            .setAds(AggregatedConfigSource.getDefaultInstance())))
            .setLrsServer(ConfigSource.newBuilder().setSelf(SelfConfigSource.getDefaultInstance()))
            .setLbPolicy(LbPolicy.ROUND_ROBIN)
            .setTransportSocket(
                TransportSocket.newBuilder()
                    .setTypedConfig(
                        Any.pack(
                            UpstreamTlsContext.newBuilder()
                                .setCommonTlsContext(
                                    CommonTlsContext.newBuilder()
                                        .addTlsCertificateSdsSecretConfigs(
                                            SdsSecretConfig.getDefaultInstance())).build())))
            .build();
    DiscoveryResponse response =
        DiscoveryResponse.newBuilder()
            .setTypeUrl("type.googleapis.com/envoy.config.cluster.v3.Cluster")
            .setVersionInfo("0")
            .addResources(Any.pack(cluster))
            .setNonce("0000")
            .build();

    String expectedString = "{\n"
        + "  \"versionInfo\": \"0\",\n"
        + "  \"resources\": [{\n"
        + "    \"@type\": \"type.googleapis.com/envoy.config.cluster.v3.Cluster\",\n"
        + "    \"name\": \"cluster-foo.googleapis.com\",\n"
        + "    \"edsClusterConfig\": {\n"
        + "      \"edsConfig\": {\n"
        + "        \"ads\": {\n"
        + "        }\n"
        + "      },\n"
        + "      \"serviceName\": \"backend-service-foo.googleapis.com\"\n"
        + "    },\n"
        + "    \"transportSocket\": {\n"
        + "      \"typedConfig\": {\n"
        + "        \"@type\": \"type.googleapis.com/envoy.extensions.transport_sockets.tls.v3"
        + ".UpstreamTlsContext\",\n"
        + "        \"commonTlsContext\": {\n"
        + "          \"tlsCertificateSdsSecretConfigs\": [{\n"
        + "          }]\n"
        + "        }\n"
        + "      }\n"
        + "    },\n"
        + "    \"lrsServer\": {\n"
        + "      \"self\": {\n"
        + "      }\n"
        + "    }\n"
        + "  }],\n"
        + "  \"typeUrl\": \"type.googleapis.com/envoy.config.cluster.v3.Cluster\",\n"
        + "  \"nonce\": \"0000\"\n"
        + "}";
    String res = printer.print(response);
    assertThat(res).isEqualTo(expectedString);
  }

  @Test
  public void printEdsResponse_v3() {
    ClusterLoadAssignment clusterLoadAssignment =
        ClusterLoadAssignment.newBuilder()
            .setClusterName("cluster-foo.googleapis.com")
            .setPolicy(
                Policy.newBuilder()
                    .addDropOverloads(
                        DropOverload.newBuilder()
                            .setCategory("throttle")
                            .setDropPercentage(
                                FractionalPercent.newBuilder()
                                    .setNumerator(80)
                                    .setDenominator(DenominatorType.HUNDRED))))
            .addEndpoints(
                LocalityLbEndpoints.newBuilder()
                    .setLocality(
                        Locality.newBuilder()
                            .setRegion("region")
                            .setZone("zone")
                            .setSubZone("subzone")).setPriority(1)
                    .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(20))
                    .addLbEndpoints(
                        LbEndpoint.newBuilder()
                            .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(100))
                            .setHealthStatus(HealthStatus.UNHEALTHY)
                            .setEndpoint(
                                Endpoint.newBuilder()
                                    .setAddress(
                                        Address.newBuilder()
                                            .setSocketAddress(
                                                SocketAddress.newBuilder()
                                                    .setAddress("10.0.0.1")
                                                    .setPortValue(8001))))))
            .build();


    DiscoveryResponse response =
        DiscoveryResponse.newBuilder()
            .setTypeUrl("type.googleapis.com/envoy.config.endpoint.v3.ClusterLoadAssignment")
            .setVersionInfo("0")
            .addResources(Any.pack(clusterLoadAssignment))
            .setNonce("0000")
            .build();

    String expectedString = "{\n"
        + "  \"versionInfo\": \"0\",\n"
        + "  \"resources\": [{\n"
        + "    \"@type\": \"type.googleapis.com/envoy.config.endpoint.v3"
        + ".ClusterLoadAssignment\",\n"
        + "    \"clusterName\": \"cluster-foo.googleapis.com\",\n"
        + "    \"endpoints\": [{\n"
        + "      \"locality\": {\n"
        + "        \"region\": \"region\",\n"
        + "        \"zone\": \"zone\",\n"
        + "        \"subZone\": \"subzone\"\n"
        + "      },\n"
        + "      \"lbEndpoints\": [{\n"
        + "        \"endpoint\": {\n"
        + "          \"address\": {\n"
        + "            \"socketAddress\": {\n"
        + "              \"address\": \"10.0.0.1\",\n"
        + "              \"portValue\": 8001\n"
        + "            }\n"
        + "          }\n"
        + "        },\n"
        + "        \"healthStatus\": \"UNHEALTHY\",\n"
        + "        \"loadBalancingWeight\": 100\n"
        + "      }],\n"
        + "      \"loadBalancingWeight\": 20,\n"
        + "      \"priority\": 1\n"
        + "    }],\n"
        + "    \"policy\": {\n"
        + "      \"dropOverloads\": [{\n"
        + "        \"category\": \"throttle\",\n"
        + "        \"dropPercentage\": {\n"
        + "          \"numerator\": 80\n"
        + "        }\n"
        + "      }]\n"
        + "    }\n"
        + "  }],\n"
        + "  \"typeUrl\": \"type.googleapis.com/envoy.config.endpoint.v3"
        + ".ClusterLoadAssignment\",\n"
        + "  \"nonce\": \"0000\"\n"
        + "}";
    String res = printer.print(response);
    assertThat(res).isEqualTo(expectedString);
  }
}
