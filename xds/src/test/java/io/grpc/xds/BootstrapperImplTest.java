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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import io.grpc.InsecureChannelCredentials;
import io.grpc.TlsChannelCredentials;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.GrpcUtil.GrpcBuildVersion;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.Node;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link Bootstrapper}. */
@RunWith(JUnit4.class)
public class BootstrapperImplTest {

  private static final String BOOTSTRAP_FILE_PATH = "/fake/fs/path/bootstrap.json";
  private static final String SERVER_URI = "trafficdirector.googleapis.com:443";
  @SuppressWarnings("deprecation") // https://github.com/grpc/grpc-java/issues/7467
  @Rule
  public final ExpectedException thrown = ExpectedException.none();

  private final BootstrapperImpl bootstrapper = new BootstrapperImpl();
  private String originalBootstrapPathFromEnvVar;
  private String originalBootstrapPathFromSysProp;
  private String originalBootstrapConfigFromEnvVar;
  private String originalBootstrapConfigFromSysProp;
  private boolean originalEnableV3Protocol;

  @Before
  public void setUp() {
    saveEnvironment();
    BootstrapperImpl.bootstrapPathFromEnvVar = BOOTSTRAP_FILE_PATH;
  }

  private void saveEnvironment() {
    originalBootstrapPathFromEnvVar = BootstrapperImpl.bootstrapPathFromEnvVar;
    originalBootstrapPathFromSysProp = BootstrapperImpl.bootstrapPathFromSysProp;
    originalBootstrapConfigFromEnvVar = BootstrapperImpl.bootstrapConfigFromEnvVar;
    originalBootstrapConfigFromSysProp = BootstrapperImpl.bootstrapConfigFromSysProp;
    originalEnableV3Protocol = BootstrapperImpl.enableV3Protocol;
  }

  @After
  public void restoreEnvironment() {
    BootstrapperImpl.bootstrapPathFromEnvVar = originalBootstrapPathFromEnvVar;
    BootstrapperImpl.bootstrapPathFromSysProp = originalBootstrapPathFromSysProp;
    BootstrapperImpl.bootstrapConfigFromEnvVar = originalBootstrapConfigFromEnvVar;
    BootstrapperImpl.bootstrapConfigFromSysProp = originalBootstrapConfigFromSysProp;
    BootstrapperImpl.enableV3Protocol = originalEnableV3Protocol;
  }

  @Test
  public void parseBootstrap_singleXdsServer() throws XdsInitializationException {
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
        + "      \"server_uri\": \"" + SERVER_URI + "\",\n"
        + "      \"channel_creds\": [\n"
        + "        {\"type\": \"insecure\"}\n"
        + "      ]\n"
        + "    }\n"
        + "  ]\n"
        + "}";

    bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
    BootstrapInfo info = bootstrapper.bootstrap();
    assertThat(info.getServers()).hasSize(1);
    ServerInfo serverInfo = Iterables.getOnlyElement(info.getServers());
    assertThat(serverInfo.getTarget()).isEqualTo(SERVER_URI);
    assertThat(serverInfo.getChannelCredentials()).isInstanceOf(InsecureChannelCredentials.class);
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
  public void parseBootstrap_multipleXdsServers() throws XdsInitializationException {
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
        + "        {\"type\": \"tls\"}\n"
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

    bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
    BootstrapInfo info = bootstrapper.bootstrap();
    assertThat(info.getServers()).hasSize(2);
    List<ServerInfo> serverInfoList = info.getServers();
    assertThat(serverInfoList.get(0).getTarget())
        .isEqualTo("trafficdirector-foo.googleapis.com:443");
    assertThat(serverInfoList.get(0).getChannelCredentials())
        .isInstanceOf(TlsChannelCredentials.class);
    assertThat(serverInfoList.get(1).getTarget())
        .isEqualTo("trafficdirector-bar.googleapis.com:443");
    assertThat(serverInfoList.get(1).getChannelCredentials())
        .isInstanceOf(InsecureChannelCredentials.class);
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
        + "      \"server_uri\": \"" + SERVER_URI + "\",\n"
        + "      \"ignore\": \"something irrelevant\","
        + "      \"channel_creds\": [\n"
        + "        {\"type\": \"insecure\"}\n"
        + "      ]\n"
        + "    }\n"
        + "  ],\n"
        + "  \"ignore\": \"something irrelevant\"\n"
        + "}";

    bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
    BootstrapInfo info = bootstrapper.bootstrap();
    assertThat(info.getServers()).hasSize(1);
    ServerInfo serverInfo = Iterables.getOnlyElement(info.getServers());
    assertThat(serverInfo.getTarget()).isEqualTo(SERVER_URI);
    assertThat(serverInfo.getChannelCredentials()).isInstanceOf(InsecureChannelCredentials.class);
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
  public void parseBootstrap_missingServerChannelCreds() throws XdsInitializationException {
    String rawData = "{\n"
        + "  \"xds_servers\": [\n"
        + "    {\n"
        + "      \"server_uri\": \"" + SERVER_URI + "\"\n"
        + "    }\n"
        + "  ]\n"
        + "}";

    bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
    thrown.expect(XdsInitializationException.class);
    thrown.expectMessage("Invalid bootstrap: server " + SERVER_URI + " 'channel_creds' required");
    bootstrapper.bootstrap();
  }

  @Test
  public void parseBootstrap_unsupportedServerChannelCreds() throws XdsInitializationException {
    String rawData = "{\n"
        + "  \"xds_servers\": [\n"
        + "    {\n"
        + "      \"server_uri\": \"" + SERVER_URI + "\",\n"
        + "      \"channel_creds\": [\n"
        + "        {\"type\": \"unsupported\"}\n"
        + "      ]\n"
        + "    }\n"
        + "  ]\n"
        + "}";

    bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
    thrown.expect(XdsInitializationException.class);
    thrown.expectMessage("Server " + SERVER_URI + ": no supported channel credentials found");
    bootstrapper.bootstrap();
  }

  @Test
  public void parseBootstrap_useFirstSupportedChannelCredentials()
      throws XdsInitializationException {
    String rawData = "{\n"
        + "  \"xds_servers\": [\n"
        + "    {\n"
        + "      \"server_uri\": \"" + SERVER_URI + "\",\n"
        + "      \"channel_creds\": [\n"
        + "        {\"type\": \"unsupported\"}, {\"type\": \"insecure\"}, {\"type\": \"tls\"}\n"
        + "      ]\n"
        + "    }\n"
        + "  ]\n"
        + "}";

    bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
    BootstrapInfo info = bootstrapper.bootstrap();
    assertThat(info.getServers()).hasSize(1);
    ServerInfo serverInfo = Iterables.getOnlyElement(info.getServers());
    assertThat(serverInfo.getTarget()).isEqualTo(SERVER_URI);
    assertThat(serverInfo.getChannelCredentials()).isInstanceOf(InsecureChannelCredentials.class);
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

    bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
    thrown.expect(XdsInitializationException.class);
    thrown.expectMessage("Invalid bootstrap: 'xds_servers' does not exist.");
    bootstrapper.bootstrap();
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

    bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
    thrown.expectMessage("Invalid bootstrap: missing 'server_uri'");
    bootstrapper.bootstrap();
  }

  @Test
  public void parseBootstrap_certProviderInstances() throws XdsInitializationException {
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

    bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
    BootstrapInfo info = bootstrapper.bootstrap();
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

    bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
    try {
      bootstrapper.bootstrap();
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

    bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
    try {
      bootstrapper.bootstrap();
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

    bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
    try {
      bootstrapper.bootstrap();
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

    bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
    try {
      bootstrapper.bootstrap();
      fail("exception expected");
    } catch (XdsInitializationException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("Invalid bootstrap: 'plugin_name' does not exist.");
    }
  }

  @Test
  public void parseBootstrap_grpcServerResourceId() throws XdsInitializationException {
    String rawData = "{\n"
            + "  \"xds_servers\": [],\n"
            + "  \"grpc_server_resource_name_id\": \"grpc/serverx\"\n"
            + "}";

    bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
    BootstrapInfo info = bootstrapper.bootstrap();
    assertThat(info.getGrpcServerResourceId()).isEqualTo("grpc/serverx");
  }

  @Test
  public void useV2ProtocolByDefault() throws XdsInitializationException {
    String rawData = "{\n"
        + "  \"xds_servers\": [\n"
        + "    {\n"
        + "      \"server_uri\": \"" + SERVER_URI + "\",\n"
        + "      \"channel_creds\": [\n"
        + "        {\"type\": \"insecure\"}\n"
        + "      ],\n"
        + "      \"server_features\": [\"xds_v3\"]\n"
        + "    }\n"
        + "  ]\n"
        + "}";

    bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
    BootstrapInfo info = bootstrapper.bootstrap();
    ServerInfo serverInfo = Iterables.getOnlyElement(info.getServers());
    assertThat(serverInfo.getTarget()).isEqualTo(SERVER_URI);
    assertThat(serverInfo.getChannelCredentials()).isInstanceOf(InsecureChannelCredentials.class);
    assertThat(serverInfo.isUseProtocolV3()).isFalse();
  }

  @Test
  public void supportV3Protocol_disabledByDefault() throws XdsInitializationException {
    subtestSupportV3Protocol(false);
  }

  @Test
  public void supportV3Protocol_enabled() throws XdsInitializationException {
    BootstrapperImpl.enableV3Protocol = true;
    subtestSupportV3Protocol(true);
  }

  private void subtestSupportV3Protocol(boolean enabled) throws XdsInitializationException {
    String rawData = "{\n"
        + "  \"xds_servers\": [\n"
        + "    {\n"
        + "      \"server_uri\": \"" + SERVER_URI + "\",\n"
        + "      \"channel_creds\": [\n"
        + "        {\"type\": \"insecure\"}\n"
        + "      ],\n"
        + "      \"server_features\": [\"xds_v3\"]\n"
        + "    }\n"
        + "  ]\n"
        + "}";

    bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
    BootstrapInfo info = bootstrapper.bootstrap();
    ServerInfo serverInfo = Iterables.getOnlyElement(info.getServers());
    assertThat(serverInfo.getTarget()).isEqualTo(SERVER_URI);
    assertThat(serverInfo.getChannelCredentials()).isInstanceOf(InsecureChannelCredentials.class);
    if (enabled) {
      assertThat(serverInfo.isUseProtocolV3()).isTrue();
    } else {
      assertThat(serverInfo.isUseProtocolV3()).isFalse();
    }
  }

  @Test
  public void notFound() {
    BootstrapperImpl.bootstrapPathFromEnvVar = null;
    BootstrapperImpl.bootstrapPathFromSysProp = null;
    BootstrapperImpl.bootstrapConfigFromEnvVar = null;
    BootstrapperImpl.bootstrapConfigFromSysProp = null;
    BootstrapperImpl.FileReader reader = mock(BootstrapperImpl.FileReader.class);
    bootstrapper.setFileReader(reader);
    try {
      bootstrapper.bootstrap();
      fail("should fail");
    } catch (XdsInitializationException expected) {
      assertThat(expected).hasMessageThat().startsWith("Cannot find bootstrap configuration");
    }
    verifyNoInteractions(reader);
  }

  @Test
  public void fallbackToFilePathFromSystemProperty() throws XdsInitializationException {
    final String customPath = "/home/bootstrap.json";
    BootstrapperImpl.bootstrapPathFromEnvVar = null;
    BootstrapperImpl.bootstrapPathFromSysProp = customPath;
    String rawData = "{\n"
        + "  \"xds_servers\": [\n"
        + "    {\n"
        + "      \"server_uri\": \"" + SERVER_URI + "\",\n"
        + "      \"channel_creds\": [\n"
        + "        {\"type\": \"insecure\"}\n"
        + "      ]\n"
        + "    }\n"
        + "  ]\n"
        + "}";

    bootstrapper.setFileReader(createFileReader(customPath, rawData));
    bootstrapper.bootstrap();
  }

  @Test
  public void fallbackToConfigFromEnvVar() throws XdsInitializationException {
    String rawData = "{\n"
        + "  \"xds_servers\": [\n"
        + "    {\n"
        + "      \"server_uri\": \"" + SERVER_URI + "\",\n"
        + "      \"channel_creds\": [\n"
        + "        {\"type\": \"insecure\"}\n"
        + "      ]\n"
        + "    }\n"
        + "  ]\n"
        + "}";

    BootstrapperImpl.bootstrapPathFromEnvVar = null;
    BootstrapperImpl.bootstrapPathFromSysProp = null;
    BootstrapperImpl.bootstrapConfigFromEnvVar = rawData;
    bootstrapper.setFileReader(mock(BootstrapperImpl.FileReader.class));
    bootstrapper.bootstrap();
  }

  @Test
  public void fallbackToConfigFromSysProp() throws XdsInitializationException {
    String rawData = "{\n"
        + "  \"xds_servers\": [\n"
        + "    {\n"
        + "      \"server_uri\": \"" + SERVER_URI + "\",\n"
        + "      \"channel_creds\": [\n"
        + "        {\"type\": \"insecure\"}\n"
        + "      ]\n"
        + "    }\n"
        + "  ]\n"
        + "}";
    
    BootstrapperImpl.bootstrapPathFromEnvVar = null;
    BootstrapperImpl.bootstrapPathFromSysProp = null;
    BootstrapperImpl.bootstrapConfigFromEnvVar = null;
    BootstrapperImpl.bootstrapConfigFromSysProp = rawData;
    bootstrapper.setFileReader(mock(BootstrapperImpl.FileReader.class));
    bootstrapper.bootstrap();
  }

  private static BootstrapperImpl.FileReader createFileReader(
      final String expectedPath, final String rawData) {
    return new BootstrapperImpl.FileReader() {
      @Override
      public String readFile(String path) throws IOException {
        assertThat(path).isEqualTo(expectedPath);
        return rawData;
      }
    };
  }

  private static Node.Builder getNodeBuilder() {
    GrpcBuildVersion buildVersion = GrpcUtil.getGrpcBuildVersion();
    return
        Node.newBuilder()
            .setBuildVersion(buildVersion.toString())
            .setUserAgentName(buildVersion.getUserAgent())
            .setUserAgentVersion(buildVersion.getImplementationVersion())
            .addClientFeatures(BootstrapperImpl.CLIENT_FEATURE_DISABLE_OVERPROVISIONING);
  }
}
