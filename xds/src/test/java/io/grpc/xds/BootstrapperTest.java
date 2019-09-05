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

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.api.v2.core.Locality;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.xds.Bootstrapper.ConfigReader;
import io.grpc.xds.Bootstrapper.FileBasedBootstrapper;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link Bootstrapper}. */
@RunWith(JUnit4.class)
public class BootstrapperTest {

  @Test
  public void validBootstrap() throws IOException {
    String bootstrapData = "{"
        + "\"node\": {"
        + "\"id\": \"ENVOY_NODE_ID\","
        + "\"locality\": {"
        + "\"zone\": \"ENVOY_ZONE\"},"
        + "\"metadata\": {"
        + "\"TRAFFICDIRECTOR_INTERCEPTION_PORT\": \"ENVOY_PORT\", "
        + "\"TRAFFICDIRECTOR_NETWORK_NAME\": \"VPC_NETWORK_NAME\""
        + "}"
        + "},"
        + "\"xds_server\": {"
        + "\"api_type\": \"GRPC\","
        + "\"grpc_services\": "
        + "[ {\"google_grpc\": {\"target_uri\": \"trafficdirector.googleapis.com:443\"} } ]"
        + "} "
        + "}";

    ConfigReader reader = new ConfigReader(bootstrapData);
    Bootstrapper bootstrapper =
        new FileBasedBootstrapper(reader);
    assertThat(bootstrapper.getBalancerName()).isEqualTo("trafficdirector.googleapis.com:443");
    assertThat(bootstrapper.getNode())
        .isEqualTo(
            Node.newBuilder()
                .setId("ENVOY_NODE_ID")
                .setLocality(Locality.newBuilder().setZone("ENVOY_ZONE").build())
                .setMetadata(
                    Struct.newBuilder()
                        .putFields("TRAFFICDIRECTOR_INTERCEPTION_PORT",
                            Value.newBuilder().setStringValue("ENVOY_PORT").build())
                        .putFields("TRAFFICDIRECTOR_NETWORK_NAME",
                            Value.newBuilder().setStringValue("VPC_NETWORK_NAME").build())
                        .build()).build());
  }

  @Test
  public void failToBootstrap() {
    try {
      new FileBasedBootstrapper(null);
      fail("RuntimeException should have been thrown");
    } catch (RuntimeException e) {
      // Expected.
      assertThat(e.getMessage()).isEqualTo("Failed to bootstrap from config file.");
    }
  }

  @Test
  public void configReader_emptyFile() {
    String bootstrapData = "";
    try {
      new ConfigReader(bootstrapData);
      fail("IOException should have been thrown");
    } catch (IOException e) {
      // Expected.
    }
  }

  @Test
  public void configReader_invalidNodeProto() {
    String bootstrapData = "{"
        + "\"node\": {"
        + "\"id\": \"ENVOY_NODE_ID\","
        + "\"bad_field\": \"bad_value\""
        + "\"locality\": {"
        + "\"zone\": \"ENVOY_ZONE\"},"
        + "\"metadata\": {"
        + "\"TRAFFICDIRECTOR_INTERCEPTION_PORT\": \"ENVOY_PORT\", "
        + "\"TRAFFICDIRECTOR_NETWORK_NAME\": \"VPC_NETWORK_NAME\""
        + "}"
        + "},"
        + "\"xds_server\": {"
        + "\"api_type\": \"GRPC\","
        + "\"grpc_services\": "
        + "[ {\"google_grpc\": {\"target_uri\": \"trafficdirector.googleapis.com:443\"} } ]"
        + "} "
        + "}";
    try {
      new ConfigReader(bootstrapData);
      fail("IOException should have been thrown");
    } catch (IOException e) {
      // Expected.
    }
  }

  @Test
  public void configReader_invalidApiConfigSourceProto() {
    String bootstrapData = "{"
        + "\"node\": {"
        + "\"id\": \"ENVOY_NODE_ID\","
        + "\"locality\": {"
        + "\"zone\": \"ENVOY_ZONE\"},"
        + "\"metadata\": {"
        + "\"TRAFFICDIRECTOR_INTERCEPTION_PORT\": \"ENVOY_PORT\", "
        + "\"TRAFFICDIRECTOR_NETWORK_NAME\": \"VPC_NETWORK_NAME\""
        + "}"
        + "},"
        + "\"xds_server\": {"
        + "\"api_type\": \"GRPC\","
        + "\"bad_field\": \"bad_value\""
        + "\"grpc_services\": "
        + "[ {\"google_grpc\": {\"target_uri\": \"trafficdirector.googleapis.com:443\"} } ]"
        + "} "
        + "}";
    try {
      new ConfigReader(bootstrapData);
      fail("IOException should have been thrown");
    } catch (IOException e) {
      // Expected.
    }
  }

  @Test
  public void configReader_tooManyGrpcServices() throws IOException {
    String bootstrapData = "{"
        + "\"node\": {"
        + "\"id\": \"ENVOY_NODE_ID\","
        + "\"locality\": {"
        + "\"zone\": \"ENVOY_ZONE\"},"
        + "\"metadata\": {"
        + "\"TRAFFICDIRECTOR_INTERCEPTION_PORT\": \"ENVOY_PORT\", "
        + "\"TRAFFICDIRECTOR_NETWORK_NAME\": \"VPC_NETWORK_NAME\""
        + "}"
        + "},"
        + "\"xds_server\": {"
        + "\"api_type\": \"GRPC\","
        + "\"grpc_services\": "
        + "[ {\"google_grpc\": {\"target_uri\": \"trafficdirector.googleapis.com:443\"} },"
        + "{\"google_grpc\": {\"target_uri\": \"foobar.googleapis.com:443\"} } ]"
        + "} "
        + "}";
    try {
      new ConfigReader(bootstrapData);
      fail("RuntimeException should have been thrown");
    } catch (RuntimeException e) {
      // Expected.
      assertThat(e.getMessage())
          .isEqualTo("Unexpected number of gRPC services: expected: 1, actual: 2");
    }
  }

  @Test
  public void configReader_invalidApiType() throws IOException {
    String bootstrapData = "{"
        + "\"node\": {"
        + "\"id\": \"ENVOY_NODE_ID\","
        + "\"locality\": {"
        + "\"zone\": \"ENVOY_ZONE\"},"
        + "\"metadata\": {"
        + "\"TRAFFICDIRECTOR_INTERCEPTION_PORT\": \"ENVOY_PORT\", "
        + "\"TRAFFICDIRECTOR_NETWORK_NAME\": \"VPC_NETWORK_NAME\""
        + "}"
        + "},"
        + "\"xds_server\": {"
        + "\"api_type\": \"REST\","
        + "\"grpc_services\": "
        + "[ {\"google_grpc\": {\"target_uri\": \"trafficdirector.googleapis.com:443\"} } ]"
        + "} "
        + "}";
    try {
      new ConfigReader(bootstrapData);
      fail("RuntimeException should have been thrown");
    } catch (RuntimeException e) {
      // Expected.
      assertThat(e.getMessage()).isEqualTo("Unexpected api type: REST");
    }
  }
}