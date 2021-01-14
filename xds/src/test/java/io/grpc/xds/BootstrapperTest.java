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

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.api.v2.core.Locality;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.internal.GrpcUtil;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link Bootstrapper}. */
@RunWith(JUnit4.class)
public class BootstrapperTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void parseBootstrap_validData() throws IOException {
    String rawData = "{"
        + "\"node\": {"
        + "\"id\": \"ENVOY_NODE_ID\","
        + "\"cluster\": \"ENVOY_CLUSTER\","
        + "\"locality\": {"
        + "\"region\": \"ENVOY_REGION\", \"zone\": \"ENVOY_ZONE\", \"sub_zone\": \"ENVOY_SUBZONE\""
        + "},"
        + "\"metadata\": {"
        + "\"TRAFFICDIRECTOR_INTERCEPTION_PORT\": \"ENVOY_PORT\", "
        + "\"TRAFFICDIRECTOR_NETWORK_NAME\": \"VPC_NETWORK_NAME\""
        + "}"
        + "},"
        + "\"xds_server\": {"
        + "\"server_uri\": \"trafficdirector.googleapis.com:443\","
        + "\"channel_creds\": "
        + "[ {\"type\": \"tls\"}, {\"type\": \"loas\"}, {\"type\": \"google_default\"} ]"
        + "} "
        + "}";

    BootstrapInfo info = Bootstrapper.parseConfig(rawData);
    assertThat(info.getServerUri()).isEqualTo("trafficdirector.googleapis.com:443");
    assertThat(info.getChannelCredentials()).hasSize(3);
    assertThat(info.getChannelCredentials().get(0).getType()).isEqualTo("tls");
    assertThat(info.getChannelCredentials().get(0).getConfig()).isNull();
    assertThat(info.getChannelCredentials().get(1).getType()).isEqualTo("loas");
    assertThat(info.getChannelCredentials().get(1).getConfig()).isNull();
    assertThat(info.getChannelCredentials().get(2).getType()).isEqualTo("google_default");
    assertThat(info.getChannelCredentials().get(2).getConfig()).isNull();
    assertThat(info.getNode()).isEqualTo(
        Node.newBuilder()
            .setId("ENVOY_NODE_ID")
            .setCluster("ENVOY_CLUSTER")
            .setLocality(
                Locality.newBuilder()
                    .setRegion("ENVOY_REGION").setZone("ENVOY_ZONE").setSubZone("ENVOY_SUBZONE"))
            .setMetadata(
                Struct.newBuilder()
                    .putFields("TRAFFICDIRECTOR_INTERCEPTION_PORT",
                        Value.newBuilder().setStringValue("ENVOY_PORT").build())
                    .putFields("TRAFFICDIRECTOR_NETWORK_NAME",
                        Value.newBuilder().setStringValue("VPC_NETWORK_NAME").build())
                    .build())
            .setBuildVersion(GrpcUtil.getGrpcBuildVersion())
            .build());
  }

  @Test
  public void parseBootstrap_emptyData() throws IOException {
    String rawData = "";

    thrown.expect(IOException.class);
    Bootstrapper.parseConfig(rawData);
  }

  @Test
  public void parseBootstrap_minimumRequiredFields() throws IOException {
    String rawData = "{"
        + "\"xds_server\": {"
        + "\"server_uri\": \"trafficdirector.googleapis.com:443\""
        + "}"
        + "}";

    BootstrapInfo info = Bootstrapper.parseConfig(rawData);
    assertThat(info.getServerUri()).isEqualTo("trafficdirector.googleapis.com:443");
    assertThat(info.getNode())
        .isEqualTo(
            Node.newBuilder()
                .setBuildVersion(
                    GrpcUtil.getGrpcBuildVersion())
                .build());
  }

  @Test
  public void parseBootstrap_noXdsServer() throws IOException {
    String rawData = "{"
        + "\"node\": {"
        + "\"id\": \"ENVOY_NODE_ID\","
        + "\"cluster\": \"ENVOY_CLUSTER\","
        + "\"locality\": {"
        + "\"region\": \"ENVOY_REGION\", \"zone\": \"ENVOY_ZONE\", \"sub_zone\": \"ENVOY_SUBZONE\""
        + "},"
        + "\"metadata\": {"
        + "\"TRAFFICDIRECTOR_INTERCEPTION_PORT\": \"ENVOY_PORT\", "
        + "\"TRAFFICDIRECTOR_NETWORK_NAME\": \"VPC_NETWORK_NAME\""
        + "}"
        + "}"
        + "}";

    thrown.expect(IOException.class);
    thrown.expectMessage("Invalid bootstrap: 'xds_server' does not exist.");
    Bootstrapper.parseConfig(rawData);
  }

  @Test
  public void parseBootstrap_noServerUri() throws IOException {
    String rawData = "{"
        + "\"node\": {"
        + "\"id\": \"ENVOY_NODE_ID\","
        + "\"cluster\": \"ENVOY_CLUSTER\","
        + "\"locality\": {"
        + "\"region\": \"ENVOY_REGION\", \"zone\": \"ENVOY_ZONE\", \"sub_zone\": \"ENVOY_SUBZONE\""
        + "},"
        + "\"metadata\": {"
        + "\"TRAFFICDIRECTOR_INTERCEPTION_PORT\": \"ENVOY_PORT\", "
        + "\"TRAFFICDIRECTOR_NETWORK_NAME\": \"VPC_NETWORK_NAME\""
        + "}"
        + "},"
        + "\"xds_server\": {"
        + "\"channel_creds\": "
        + "[ {\"type\": \"tls\"}, {\"type\": \"loas\"} ]"
        + "} "
        + "}";

    thrown.expect(IOException.class);
    thrown.expectMessage("Invalid bootstrap: 'xds_server : server_uri' does not exist.");
    Bootstrapper.parseConfig(rawData);
  }
}
