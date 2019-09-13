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

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.api.v2.core.GrpcService;
import io.envoyproxy.envoy.api.v2.core.GrpcService.GoogleGrpc;
import io.envoyproxy.envoy.api.v2.core.Locality;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.xds.Bootstrapper.FileBasedBootstrapper;
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
  public void validBootstrap() throws IOException {
    Bootstrap config =
        Bootstrap.newBuilder()
            .setNode(
                Node.newBuilder()
                    .setId("ENVOY_NODE_ID")
                    .setLocality(
                        Locality.newBuilder().setZone("ENVOY_ZONE").setRegion("ENVOY_REGION"))
                    .setMetadata(
                        Struct.newBuilder()
                            .putFields("TRAFFICDIRECTOR_INTERCEPTION_PORT",
                                Value.newBuilder().setStringValue("ENVOY_PORT").build())
                            .putFields("TRAFFICDIRECTOR_NETWORK_NAME",
                                Value.newBuilder().setStringValue("VPC_NETWORK_NAME").build())))
            .setXdsServer(ApiConfigSource.newBuilder()
                .setApiType(ApiType.GRPC)
                .addGrpcServices(
                    GrpcService.newBuilder()
                        .setGoogleGrpc(
                            GoogleGrpc.newBuilder()
                                .setTargetUri("trafficdirector.googleapis.com:443").build())))
            .build();

    Bootstrapper bootstrapper = new FileBasedBootstrapper(config);
    assertThat(bootstrapper.getBalancerName()).isEqualTo("trafficdirector.googleapis.com:443");
    assertThat(bootstrapper.getNode())
        .isEqualTo(
            Node.newBuilder()
                .setId("ENVOY_NODE_ID")
                .setLocality(Locality.newBuilder().setZone("ENVOY_ZONE").setRegion("ENVOY_REGION"))
                .setMetadata(
                    Struct.newBuilder()
                        .putFields("TRAFFICDIRECTOR_INTERCEPTION_PORT",
                            Value.newBuilder().setStringValue("ENVOY_PORT").build())
                        .putFields("TRAFFICDIRECTOR_NETWORK_NAME",
                            Value.newBuilder().setStringValue("VPC_NETWORK_NAME").build())
                        .build()).build());
  }

  @Test
  public void unsupportedApiType() throws IOException {
    Bootstrap config =
        Bootstrap.newBuilder()
            .setNode(
                Node.newBuilder()
                    .setId("ENVOY_NODE_ID")
                    .setLocality(
                        Locality.newBuilder().setZone("ENVOY_ZONE").setRegion("ENVOY_REGION"))
                    .setMetadata(
                        Struct.newBuilder()
                            .putFields("TRAFFICDIRECTOR_INTERCEPTION_PORT",
                                Value.newBuilder().setStringValue("ENVOY_PORT").build())
                            .putFields("TRAFFICDIRECTOR_NETWORK_NAME",
                                Value.newBuilder().setStringValue("VPC_NETWORK_NAME").build())))
            .setXdsServer(ApiConfigSource.newBuilder()
                .setApiType(ApiType.REST)
                .addGrpcServices(
                    GrpcService.newBuilder()
                        .setGoogleGrpc(
                            GoogleGrpc.newBuilder()
                                .setTargetUri("trafficdirector.googleapis.com:443").build())))
            .build();

    thrown.expect(IOException.class);
    thrown.expectMessage("Unexpected api type: REST");
    new FileBasedBootstrapper(config);
  }

  @Test
  public void tooManyGrpcServices() throws IOException {
    Bootstrap config =
        Bootstrap.newBuilder()
            .setNode(
                Node.newBuilder()
                    .setId("ENVOY_NODE_ID")
                    .setLocality(
                        Locality.newBuilder().setZone("ENVOY_ZONE").setRegion("ENVOY_REGION"))
                    .setMetadata(
                        Struct.newBuilder()
                            .putFields("TRAFFICDIRECTOR_INTERCEPTION_PORT",
                                Value.newBuilder().setStringValue("ENVOY_PORT").build())
                            .putFields("TRAFFICDIRECTOR_NETWORK_NAME",
                                Value.newBuilder().setStringValue("VPC_NETWORK_NAME").build())))
            .setXdsServer(ApiConfigSource.newBuilder()
                .setApiType(ApiType.GRPC)
                .addGrpcServices(
                    GrpcService.newBuilder()
                        .setGoogleGrpc(
                            GoogleGrpc.newBuilder()
                                .setTargetUri("trafficdirector.googleapis.com:443").build()))
                .addGrpcServices(
                    GrpcService.newBuilder()
                        .setGoogleGrpc(
                            GoogleGrpc.newBuilder()
                                .setTargetUri("foobar.googleapis.com:443").build()))
                )
            .build();

    thrown.expect(IOException.class);
    thrown.expectMessage("Unexpected number of gRPC services: expected: 1, actual: 2");
    new FileBasedBootstrapper(config);
  }

  @Test
  public void parseBootstrap_emptyData() throws InvalidProtocolBufferException {
    String rawData = "";

    thrown.expect(InvalidProtocolBufferException.class);
    Bootstrapper.parseConfig(rawData);
  }

  @Test
  public void parseBootstrap_invalidNodeProto() throws InvalidProtocolBufferException {
    String rawData = "{"
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

    thrown.expect(InvalidProtocolBufferException.class);
    Bootstrapper.parseConfig(rawData);
  }

  @Test
  public void parseBootstrap_invalidApiConfigSourceProto() throws InvalidProtocolBufferException {
    String rawData = "{"
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

    thrown.expect(InvalidProtocolBufferException.class);
    Bootstrapper.parseConfig(rawData);
  }
}
