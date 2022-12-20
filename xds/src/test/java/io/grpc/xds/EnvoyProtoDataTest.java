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
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.xds.EnvoyProtoData.Address;
import io.grpc.xds.EnvoyProtoData.Node;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link EnvoyProtoData}.
 */
@RunWith(JUnit4.class)
public class EnvoyProtoDataTest {

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
        .setLocality(Locality.create("region", "zone", "subzone"))
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
  }

  @Test
  public void nodeToBuilderPropagatesAllAttributes() {
    Node node = Node.newBuilder()
        .setId("id")
        .setCluster("cluster")
        .setMetadata(ImmutableMap.of("key1", "value1", "key2", "value2"))
        .setLocality(Locality.create("region", "zone", "subzone"))
        .setBuildVersion("v1")
        .setUserAgentName("grpc-java")
        .setUserAgentVersion("v1.0.9")
        .addListeningAddresses(new Address("localhost", 8080))
        .addListeningAddresses(new Address("localhost", 8081))
        .addClientFeatures("feature1")
        .addClientFeatures("feature2")
        .build();
    assertThat(node.toBuilder().build()).isEqualTo(node);
  }
}
