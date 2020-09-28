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
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.GrpcUtil.GrpcBuildVersion;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.Bootstrapper.ChannelCreds;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.Node;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link Bootstrapper}. */
@RunWith(JUnit4.class)
public class BootstrapperTest {

  @SuppressWarnings("deprecation") // https://github.com/grpc/grpc-java/issues/7467
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void parseBootstrap_validData_singleXdsServer() throws XdsInitializationException {
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
            .setLocality(new Locality("ENVOY_REGION", "ENVOY_ZONE", "ENVOY_SUBZONE"))
            .setMetadata(
                ImmutableMap.of(
                    "TRAFFICDIRECTOR_INTERCEPTION_PORT",
                    "ENVOY_PORT",
                    "TRAFFICDIRECTOR_NETWORK_NAME",
                    "VPC_NETWORK_NAME"))
            .build());
  }

  @Test
  public void parseBootstrap_validData_multipleXdsServers() throws XdsInitializationException {
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
        + "      ],\n"
        + "      \"server_features\": [\n"
        + "        \"xds_v3\", \"foo\", \"bar\"\n"
        + "      ]\n"
        + "    },\n"
        + "    {\n"
        + "      \"server_uri\": \"trafficdirector-bar.googleapis.com:443\",\n"
        + "      \"channel_creds\": [\n"
        + "        {\"type\": \"insecure\"}"
        + "      ]\n"
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
    assertThat(serverInfoList.get(0).getServerFeatures()).contains("xds_v3");
    assertThat(serverInfoList.get(1).getServerUri())
        .isEqualTo("trafficdirector-bar.googleapis.com:443");
    assertThat(serverInfoList.get(1).getChannelCredentials().get(0).getType())
        .isEqualTo("insecure");
    assertThat(serverInfoList.get(0).getChannelCredentials().get(0).getConfig()).isNull();
    assertThat(info.getNode()).isEqualTo(
        getNodeBuilder()
            .setId("ENVOY_NODE_ID")
            .setCluster("ENVOY_CLUSTER")
            .setLocality(new Locality("ENVOY_REGION", "ENVOY_ZONE", "ENVOY_SUBZONE"))
            .setMetadata(
                ImmutableMap.of(
                    "TRAFFICDIRECTOR_INTERCEPTION_PORT",
                    "ENVOY_PORT",
                    "TRAFFICDIRECTOR_NETWORK_NAME",
                    "VPC_NETWORK_NAME"))
            .build());
  }

  @Test
  public void parseBootstrap_IgnoreIrrelevantFields() throws XdsInitializationException {
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
            .setLocality(new Locality("ENVOY_REGION", "ENVOY_ZONE", "ENVOY_SUBZONE"))
            .setMetadata(
                ImmutableMap.of(
                    "TRAFFICDIRECTOR_INTERCEPTION_PORT",
                    "ENVOY_PORT",
                    "TRAFFICDIRECTOR_NETWORK_NAME",
                    "VPC_NETWORK_NAME"))
            .build());
  }

  @Test
  public void parseBootstrap_emptyData() throws XdsInitializationException {
    String rawData = "";

    thrown.expect(XdsInitializationException.class);
    Bootstrapper.parseConfig(rawData);
  }

  @Test
  public void parseBootstrap_minimumRequiredFields() throws XdsInitializationException {
    String rawData = "{\n"
        + "  \"xds_servers\": []\n"
        + "}";

    BootstrapInfo info = Bootstrapper.parseConfig(rawData);
    assertThat(info.getServers()).isEmpty();
    assertThat(info.getNode()).isEqualTo(getNodeBuilder().build());
  }

  @Test
  public void parseBootstrap_minimalUsableData() throws XdsInitializationException {
    String rawData = "{\n"
        + "  \"xds_servers\": [\n"
        + "    {\n"
        + "      \"server_uri\": \"trafficdirector.googleapis.com:443\",\n"
        + "      \"channel_creds\": [\n"
        + "        {\"type\": \"insecure\"}\n"
        + "      ]\n"
        + "    }\n"
        + "  ]\n"
        + "}";

    BootstrapInfo info = Bootstrapper.parseConfig(rawData);
    assertThat(info.getServers()).hasSize(1);
    ServerInfo serverInfo = Iterables.getOnlyElement(info.getServers());
    assertThat(serverInfo.getServerUri()).isEqualTo("trafficdirector.googleapis.com:443");
    assertThat(serverInfo.getChannelCredentials()).hasSize(1);
    ChannelCreds creds = Iterables.getOnlyElement(serverInfo.getChannelCredentials());
    assertThat(creds.getType()).isEqualTo("insecure");
    assertThat(creds.getConfig()).isNull();
    assertThat(info.getNode()).isEqualTo(getNodeBuilder().build());
  }

  @Test
  public void parseBootstrap_noXdsServers() throws XdsInitializationException {
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

    thrown.expect(XdsInitializationException.class);
    thrown.expectMessage("Invalid bootstrap: 'xds_servers' does not exist.");
    Bootstrapper.parseConfig(rawData);
  }

  @Test
  public void parseBootstrap_serverWithoutServerUri() throws XdsInitializationException {
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

    thrown.expect(XdsInitializationException.class);
    thrown.expectMessage("Invalid bootstrap: missing 'xds_servers'");
    Bootstrapper.parseConfig(rawData);
  }

  @Test
  public void parseBootstrap_validData_certProviderInstances() throws XdsInitializationException {
    String rawData =
        "{\n"
            + "  \"xds_servers\": [],\n"
            + "  \"certificate_providers\": {\n"
            + "    \"gcp_id\": {\n"
            + "      \"plugin_name\": \"meshca\",\n"
            + "      \"config\": {\n"
            + "        \"server\": {\n"
            + "          \"api_type\": \"GRPC\",\n"
            + "          \"grpc_services\": [{\n"
            + "            \"google_grpc\": {\n"
            + "              \"target_uri\": \"meshca.com\",\n"
            + "              \"channel_credentials\": {\"google_default\": {}},\n"
            + "              \"call_credentials\": [{\n"
            + "                \"sts_service\": {\n"
            + "                  \"token_exchange_service\": \"securetoken.googleapis.com\",\n"
            + "                  \"subject_token_path\": \"/etc/secret/sajwt.token\"\n"
            + "                }\n"
            + "              }]\n" // end call_credentials
            + "            },\n" // end google_grpc
            + "            \"time_out\": {\"seconds\": 10}\n"
            + "          }]\n" // end grpc_services
            + "        },\n" // end server
            + "        \"certificate_lifetime\": {\"seconds\": 86400},\n"
            + "        \"renewal_grace_period\": {\"seconds\": 3600},\n"
            + "        \"key_type\": \"RSA\",\n"
            + "        \"key_size\": 2048,\n"
            + "        \"location\": \"https://container.googleapis.com/v1/project/test-project1/locations/test-zone2/clusters/test-cluster3\"\n"
            + "      }\n" // end config
            + "    },\n" // end gcp_id
            + "    \"file_provider\": {\n"
            + "      \"plugin_name\": \"file_watcher\",\n"
            + "      \"config\": {\"path\": \"/etc/secret/certs\"}\n"
            + "    }\n"
            + "  }\n"
            + "}";

    BootstrapInfo info = Bootstrapper.parseConfig(rawData);
    assertThat(info.getServers()).isEmpty();
    assertThat(info.getNode()).isEqualTo(getNodeBuilder().build());
    Map<String, Bootstrapper.CertificateProviderInfo> certProviders = info.getCertProviders();
    assertThat(certProviders).isNotNull();
    Bootstrapper.CertificateProviderInfo gcpId = certProviders.get("gcp_id");
    Bootstrapper.CertificateProviderInfo fileProvider = certProviders.get("file_provider");
    assertThat(gcpId.getPluginName()).isEqualTo("meshca");
    assertThat(gcpId.getConfig()).isInstanceOf(Map.class);
    assertThat(fileProvider.getPluginName()).isEqualTo("file_watcher");
    assertThat(fileProvider.getConfig()).isInstanceOf(Map.class);
    Map<String, ?> meshCaConfig = (Map<String, ?>)gcpId.getConfig();
    assertThat(meshCaConfig.get("key_size")).isEqualTo(2048);
  }

  @Test
  public void parseBootstrap_badPluginName() throws XdsInitializationException {
    String rawData =
        "{\n"
            + "  \"xds_servers\": [],\n"
            + "  \"certificate_providers\": {\n"
            + "    \"gcp_id\": {\n"
            + "      \"plugin_name\": 234,\n"
            + "      \"config\": {\n"
            + "        \"server\": {\n"
            + "          \"api_type\": \"GRPC\",\n"
            + "          \"grpc_services\": [{\n"
            + "            \"google_grpc\": {\n"
            + "              \"target_uri\": \"meshca.com\",\n"
            + "              \"channel_credentials\": {\"google_default\": {}},\n"
            + "              \"call_credentials\": [{\n"
            + "                \"sts_service\": {\n"
            + "                  \"token_exchange_service\": \"securetoken.googleapis.com\",\n"
            + "                  \"subject_token_path\": \"/etc/secret/sajwt.token\"\n"
            + "                }\n"
            + "              }]\n" // end call_credentials
            + "            },\n" // end google_grpc
            + "            \"time_out\": {\"seconds\": 10}\n"
            + "          }]\n" // end grpc_services
            + "        },\n" // end server
            + "        \"certificate_lifetime\": {\"seconds\": 86400},\n"
            + "        \"renewal_grace_period\": {\"seconds\": 3600},\n"
            + "        \"key_type\": \"RSA\",\n"
            + "        \"key_size\": 2048,\n"
            + "        \"location\": \"https://container.googleapis.com/v1/project/test-project1/locations/test-zone2/clusters/test-cluster3\"\n"
            + "      }\n" // end config
            + "    },\n" // end gcp_id
            + "    \"file_provider\": {\n"
            + "      \"plugin_name\": \"file_watcher\",\n"
            + "      \"config\": {\"path\": \"/etc/secret/certs\"}\n"
            + "    }\n"
            + "  }\n"
            + "}";

    try {
      Bootstrapper.parseConfig(rawData);
      fail("exception expected");
    } catch (ClassCastException expected) {
      assertThat(expected).hasMessageThat().contains("value '234.0' for key 'plugin_name' in");
    }
  }

  @Test
  public void parseBootstrap_badConfig() throws XdsInitializationException {
    String rawData =
        "{\n"
            + "  \"xds_servers\": [],\n"
            + "  \"certificate_providers\": {\n"
            + "    \"gcp_id\": {\n"
            + "      \"plugin_name\": \"meshca\",\n"
            + "      \"config\": \"badValue\"\n"
            + "    },\n" // end gcp_id
            + "    \"file_provider\": {\n"
            + "      \"plugin_name\": \"file_watcher\",\n"
            + "      \"config\": {\"path\": \"/etc/secret/certs\"}\n"
            + "    }\n"
            + "  }\n"
            + "}";

    try {
      Bootstrapper.parseConfig(rawData);
      fail("exception expected");
    } catch (ClassCastException expected) {
      assertThat(expected).hasMessageThat().contains("value 'badValue' for key 'config' in");
    }
  }

  @Test
  public void parseBootstrap_missingConfig() {
    String rawData =
        "{\n"
            + "  \"xds_servers\": [],\n"
            + "  \"certificate_providers\": {\n"
            + "    \"gcp_id\": {\n"
            + "      \"plugin_name\": \"meshca\"\n"
            + "    },\n" // end gcp_id
            + "    \"file_provider\": {\n"
            + "      \"plugin_name\": \"file_watcher\",\n"
            + "      \"config\": {\"path\": \"/etc/secret/certs\"}\n"
            + "    }\n"
            + "  }\n"
            + "}";

    try {
      Bootstrapper.parseConfig(rawData);
      fail("exception expected");
    } catch (XdsInitializationException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("Invalid bootstrap: 'config' does not exist.");
    }
  }

  @Test
  public void parseBootstrap_missingPluginName() {
    String rawData =
        "{\n"
            + "  \"xds_servers\": [],\n"
            + "  \"certificate_providers\": {\n"
            + "    \"gcp_id\": {\n"
            + "      \"plugin_name\": \"meshca\",\n"
            + "      \"config\": {\n"
            + "        \"server\": {\n"
            + "          \"api_type\": \"GRPC\",\n"
            + "          \"grpc_services\": [{\n"
            + "            \"google_grpc\": {\n"
            + "              \"target_uri\": \"meshca.com\",\n"
            + "              \"channel_credentials\": {\"google_default\": {}},\n"
            + "              \"call_credentials\": [{\n"
            + "                \"sts_service\": {\n"
            + "                  \"token_exchange_service\": \"securetoken.googleapis.com\",\n"
            + "                  \"subject_token_path\": \"/etc/secret/sajwt.token\"\n"
            + "                }\n"
            + "              }]\n" // end call_credentials
            + "            },\n" // end google_grpc
            + "            \"time_out\": {\"seconds\": 10}\n"
            + "          }]\n" // end grpc_services
            + "        },\n" // end server
            + "        \"certificate_lifetime\": {\"seconds\": 86400},\n"
            + "        \"renewal_grace_period\": {\"seconds\": 3600},\n"
            + "        \"key_type\": \"RSA\",\n"
            + "        \"key_size\": 2048,\n"
            + "        \"location\": \"https://container.googleapis.com/v1/project/test-project1/locations/test-zone2/clusters/test-cluster3\"\n"
            + "      }\n" // end config
            + "    },\n" // end gcp_id
            + "    \"file_provider\": {\n"
            + "      \"config\": {\"path\": \"/etc/secret/certs\"}\n"
            + "    }\n"
            + "  }\n"
            + "}";

    try {
      Bootstrapper.parseConfig(rawData);
      fail("exception expected");
    } catch (XdsInitializationException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("Invalid bootstrap: 'plugin_name' does not exist.");
    }
  }

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
