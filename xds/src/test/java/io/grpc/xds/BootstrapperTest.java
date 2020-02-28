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

import com.google.common.collect.Iterables;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.api.v2.core.Locality;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.GrpcUtil.GrpcBuildVersion;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.Bootstrapper.ServerInfo;
import java.io.IOException;
import java.util.List;
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
  public void parseBootstrap_validData_singleXdsServer() throws IOException {
    String rawData = "{\n"
        + "  \"node\": {\n"
        + "    \"id\": \"ENVOY_NODE_ID\",\n"
        + "    \"cluster\": \"ENVOY_CLUSTER\",\n"
        + "    \"locality\": {\n"
        + "      \"region\": \"ENVOY_REGION\",\n"
        + "      \"zone\": \"ENVOY_ZONE\",\n"
        + "      \"sub_zone\": \"ENVOY_SUBZONE\"\n"
        + "    },\n"
        + "    \"metadata\": {\n"
        + "      \"TRAFFICDIRECTOR_INTERCEPTION_PORT\": \"ENVOY_PORT\",\n"
        + "      \"TRAFFICDIRECTOR_NETWORK_NAME\": \"VPC_NETWORK_NAME\"\n"
        + "    }\n"
        + "  },\n"
        + "  \"xds_servers\": [\n"
        + "    {\n"
        + "      \"server_uri\": \"trafficdirector.googleapis.com:443\",\n"
        + "      \"channel_creds\": [\n"
        + "        {\"type\": \"tls\"}, {\"type\": \"loas\"}, {\"type\": \"google_default\"}\n"
        + "      ]\n"
        + "    }\n"
        + "  ]\n"
        + "}";

    BootstrapInfo info = Bootstrapper.parseConfig(rawData);
    assertThat(info.getServers()).hasSize(1);
    ServerInfo serverInfo = Iterables.getOnlyElement(info.getServers());
    assertThat(serverInfo.getServerUri()).isEqualTo("trafficdirector.googleapis.com:443");
    assertThat(serverInfo.getChannelCredentials()).hasSize(3);
    assertThat(serverInfo.getChannelCredentials().get(0).getType()).isEqualTo("tls");
    assertThat(serverInfo.getChannelCredentials().get(0).getConfig()).isNull();
    assertThat(serverInfo.getChannelCredentials().get(1).getType()).isEqualTo("loas");
    assertThat(serverInfo.getChannelCredentials().get(1).getConfig()).isNull();
    assertThat(serverInfo.getChannelCredentials().get(2).getType()).isEqualTo("google_default");
    assertThat(serverInfo.getChannelCredentials().get(2).getConfig()).isNull();
    assertThat(info.getNode()).isEqualTo(
        getNodeBuilder()
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
            .build());
  }

  @Test
  public void parseBootstrap_validData_multipleXdsServers() throws IOException {
    String rawData = "{\n"
        + "  \"node\": {\n"
        + "    \"id\": \"ENVOY_NODE_ID\",\n"
        + "    \"cluster\": \"ENVOY_CLUSTER\",\n"
        + "    \"locality\": {\n"
        + "      \"region\": \"ENVOY_REGION\",\n"
        + "      \"zone\": \"ENVOY_ZONE\",\n"
        + "      \"sub_zone\": \"ENVOY_SUBZONE\"\n"
        + "    },\n"
        + "    \"metadata\": {\n"
        + "      \"TRAFFICDIRECTOR_INTERCEPTION_PORT\": \"ENVOY_PORT\",\n"
        + "      \"TRAFFICDIRECTOR_NETWORK_NAME\": \"VPC_NETWORK_NAME\"\n"
        + "    }\n"
        + "  },\n"
        + "  \"xds_servers\": [\n"
        + "    {\n"
        + "      \"server_uri\": \"trafficdirector-foo.googleapis.com:443\",\n"
        + "      \"channel_creds\": [\n"
        + "        {\"type\": \"tls\"}, {\"type\": \"loas\"}, {\"type\": \"google_default\"}\n"
        + "      ]\n"
        + "    },\n"
        + "    {\n"
        + "      \"server_uri\": \"trafficdirector-bar.googleapis.com:443\",\n"
        + "      \"channel_creds\": []\n"
        + "    }\n"
        + "  ]\n"
        + "}";

    BootstrapInfo info = Bootstrapper.parseConfig(rawData);
    assertThat(info.getServers()).hasSize(2);
    List<ServerInfo> serverInfoList = info.getServers();
    assertThat(serverInfoList.get(0).getServerUri())
        .isEqualTo("trafficdirector-foo.googleapis.com:443");
    assertThat(serverInfoList.get(0).getChannelCredentials()).hasSize(3);
    assertThat(serverInfoList.get(0).getChannelCredentials().get(0).getType()).isEqualTo("tls");
    assertThat(serverInfoList.get(0).getChannelCredentials().get(0).getConfig()).isNull();
    assertThat(serverInfoList.get(0).getChannelCredentials().get(1).getType()).isEqualTo("loas");
    assertThat(serverInfoList.get(0).getChannelCredentials().get(1).getConfig()).isNull();
    assertThat(serverInfoList.get(0).getChannelCredentials().get(2).getType())
        .isEqualTo("google_default");
    assertThat(serverInfoList.get(0).getChannelCredentials().get(2).getConfig()).isNull();
    assertThat(serverInfoList.get(1).getServerUri())
        .isEqualTo("trafficdirector-bar.googleapis.com:443");
    assertThat(serverInfoList.get(1).getChannelCredentials()).isEmpty();
    assertThat(info.getNode()).isEqualTo(
        getNodeBuilder()
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
            .build());
  }

  @Test
  public void parseBootstrap_IgnoreIrrelevantFields() throws IOException {
    String rawData = "{\n"
        + "  \"node\": {\n"
        + "    \"id\": \"ENVOY_NODE_ID\",\n"
        + "    \"cluster\": \"ENVOY_CLUSTER\",\n"
        + "    \"locality\": {\n"
        + "      \"region\": \"ENVOY_REGION\",\n"
        + "      \"zone\": \"ENVOY_ZONE\",\n"
        + "      \"sub_zone\": \"ENVOY_SUBZONE\"\n"
        + "    },\n"
        + "    \"metadata\": {\n"
        + "      \"TRAFFICDIRECTOR_INTERCEPTION_PORT\": \"ENVOY_PORT\",\n"
        + "      \"TRAFFICDIRECTOR_NETWORK_NAME\": \"VPC_NETWORK_NAME\"\n"
        + "    }\n"
        + "  },\n"
        + "  \"xds_servers\": [\n"
        + "    {\n"
        + "      \"server_uri\": \"trafficdirector.googleapis.com:443\",\n"
        + "      \"ignore\": \"something irrelevant\","
        + "      \"channel_creds\": [\n"
        + "        {\"type\": \"tls\"}, {\"type\": \"loas\"}, {\"type\": \"google_default\"}\n"
        + "      ]\n"
        + "    }\n"
        + "  ],\n"
        + "  \"ignore\": \"something irrelevant\"\n"
        + "}";

    BootstrapInfo info = Bootstrapper.parseConfig(rawData);
    assertThat(info.getServers()).hasSize(1);
    ServerInfo serverInfo = Iterables.getOnlyElement(info.getServers());
    assertThat(serverInfo.getServerUri()).isEqualTo("trafficdirector.googleapis.com:443");
    assertThat(serverInfo.getChannelCredentials()).hasSize(3);
    assertThat(serverInfo.getChannelCredentials().get(0).getType()).isEqualTo("tls");
    assertThat(serverInfo.getChannelCredentials().get(0).getConfig()).isNull();
    assertThat(serverInfo.getChannelCredentials().get(1).getType()).isEqualTo("loas");
    assertThat(serverInfo.getChannelCredentials().get(1).getConfig()).isNull();
    assertThat(serverInfo.getChannelCredentials().get(2).getType()).isEqualTo("google_default");
    assertThat(serverInfo.getChannelCredentials().get(2).getConfig()).isNull();
    assertThat(info.getNode()).isEqualTo(
        getNodeBuilder()
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
    String rawData = "{\n"
        + "  \"xds_servers\": []\n"
        + "}";

    BootstrapInfo info = Bootstrapper.parseConfig(rawData);
    assertThat(info.getServers()).isEmpty();
    assertThat(info.getNode()).isEqualTo(getNodeBuilder().build());
  }

  @Test
  public void parseBootstrap_minimalUsableData() throws IOException {
    String rawData = "{\n"
        + "  \"xds_servers\": [\n"
        + "    {\n"
        + "      \"server_uri\": \"trafficdirector.googleapis.com:443\"\n"
        + "    }\n"
        + "  ]\n"
        + "}";

    BootstrapInfo info = Bootstrapper.parseConfig(rawData);
    assertThat(info.getServers()).hasSize(1);
    ServerInfo serverInfo = Iterables.getOnlyElement(info.getServers());
    assertThat(serverInfo.getServerUri()).isEqualTo("trafficdirector.googleapis.com:443");
    assertThat(serverInfo.getChannelCredentials()).isEmpty();
    assertThat(info.getNode()).isEqualTo(getNodeBuilder().build());
  }

  @Test
  public void parseBootstrap_noXdsServers() throws IOException {
    String rawData = "{\n"
        + "  \"node\": {\n"
        + "    \"id\": \"ENVOY_NODE_ID\",\n"
        + "    \"cluster\": \"ENVOY_CLUSTER\",\n"
        + "    \"locality\": {\n"
        + "      \"region\": \"ENVOY_REGION\",\n"
        + "      \"zone\": \"ENVOY_ZONE\",\n"
        + "      \"sub_zone\": \"ENVOY_SUBZONE\"\n"
        + "    },\n"
        + "    \"metadata\": {\n"
        + "      \"TRAFFICDIRECTOR_INTERCEPTION_PORT\": \"ENVOY_PORT\",\n"
        + "      \"TRAFFICDIRECTOR_NETWORK_NAME\": \"VPC_NETWORK_NAME\"\n"
        + "    }\n"
        + "  }\n"
        + "}";

    thrown.expect(IOException.class);
    thrown.expectMessage("Invalid bootstrap: 'xds_servers' does not exist.");
    Bootstrapper.parseConfig(rawData);
  }

  @Test
  public void parseBootstrap_serverWithoutServerUri() throws IOException {
    String rawData = "{"
        + "  \"node\": {\n"
        + "    \"id\": \"ENVOY_NODE_ID\",\n"
        + "    \"cluster\": \"ENVOY_CLUSTER\",\n"
        + "    \"locality\": {\n"
        + "      \"region\": \"ENVOY_REGION\",\n"
        + "      \"zone\": \"ENVOY_ZONE\",\n"
        + "      \"sub_zone\": \"ENVOY_SUBZONE\"\n"
        + "    },\n"
        + "    \"metadata\": {\n"
        + "      \"TRAFFICDIRECTOR_INTERCEPTION_PORT\": \"ENVOY_PORT\",\n"
        + "      \"TRAFFICDIRECTOR_NETWORK_NAME\": \"VPC_NETWORK_NAME\"\n"
        + "    }\n"
        + "  },\n"
        + "  \"xds_servers\": [\n"
        + "    {\n"
        + "      \"channel_creds\": [\n"
        + "        {\"type\": \"tls\"}, {\"type\": \"loas\"}\n"
        + "      ]\n"
        + "    }\n"
        + "  ]\n "
        + "}";

    thrown.expect(IOException.class);
    thrown.expectMessage("Invalid bootstrap: 'xds_servers' contains unknown server.");
    Bootstrapper.parseConfig(rawData);
  }

  @SuppressWarnings("deprecation")
  private static Node.Builder getNodeBuilder() {
    GrpcBuildVersion buildVersion = GrpcUtil.getGrpcBuildVersion();
    return
        Node.newBuilder()
            .setBuildVersion(buildVersion.toString())
            .setUserAgentName(buildVersion.getUserAgent())
            .setUserAgentVersion(buildVersion.getImplementationVersion())
            .addClientFeatures(Bootstrapper.CLIENT_FEATURE_DISABLE_OVERPROVISIONING);
  }
}
