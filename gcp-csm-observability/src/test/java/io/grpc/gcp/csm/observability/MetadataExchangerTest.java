/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.gcp.csm.observability;

import static com.google.common.truth.Truth.assertThat;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.Metadata;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link MetadataExchanger}. */
@RunWith(JUnit4.class)
public final class MetadataExchangerTest {

  @Test
  public void enablePluginForChannel_matches() {
    MetadataExchanger exchanger =
        new MetadataExchanger(Attributes.builder().build(), (name) -> null);
    assertThat(exchanger.enablePluginForChannel("xds:///testing")).isTrue();
    assertThat(exchanger.enablePluginForChannel("xds:/testing")).isTrue();
    assertThat(exchanger.enablePluginForChannel(
        "xds://traffic-director-global.xds.googleapis.com/testing:123")).isTrue();
  }

  @Test
  public void enablePluginForChannel_doesNotMatch() {
    MetadataExchanger exchanger =
        new MetadataExchanger(Attributes.builder().build(), (name) -> null);
    assertThat(exchanger.enablePluginForChannel("dns:///localhost")).isFalse();
    assertThat(exchanger.enablePluginForChannel("xds:///[]")).isFalse();
    assertThat(exchanger.enablePluginForChannel("xds://my-xds-server/testing")).isFalse();
  }

  @Test
  public void addLabels_receivedWrongType() {
    MetadataExchanger exchanger =
        new MetadataExchanger(Attributes.builder().build(), (name) -> null);
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("x-envoy-peer-metadata", Metadata.ASCII_STRING_MARSHALLER),
        BaseEncoding.base64().encode(Struct.newBuilder()
          .putFields("type", Value.newBuilder().setNumberValue(1).build())
          .build()
          .toByteArray()));
    AttributesBuilder builder = Attributes.builder();
    exchanger.newServerStreamPlugin(metadata).addLabels(builder);

    assertThat(builder.build()).isEqualTo(Attributes.builder()
        .put(stringKey("csm.mesh_id"), "unknown")
        .put(stringKey("csm.workload_canonical_service"), "unknown")
        .put(stringKey("csm.remote_workload_type"), "unknown")
        .put(stringKey("csm.remote_workload_canonical_service"), "unknown")
        .build());
  }

  @Test
  public void addLabelsFromExchange_unknownGcpType() {
    MetadataExchanger exchanger =
        new MetadataExchanger(Attributes.builder().build(), (name) -> null);
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("x-envoy-peer-metadata", Metadata.ASCII_STRING_MARSHALLER),
        BaseEncoding.base64().encode(Struct.newBuilder()
          .putFields("type", Value.newBuilder().setStringValue("gcp_surprise").build())
          .putFields("canonical_service", Value.newBuilder().setStringValue("myservice1").build())
          .build()
          .toByteArray()));
    AttributesBuilder builder = Attributes.builder();
    exchanger.newServerStreamPlugin(metadata).addLabels(builder);

    assertThat(builder.build()).isEqualTo(Attributes.builder()
        .put(stringKey("csm.mesh_id"), "unknown")
        .put(stringKey("csm.workload_canonical_service"), "unknown")
        .put(stringKey("csm.remote_workload_type"), "gcp_surprise")
        .put(stringKey("csm.remote_workload_canonical_service"), "myservice1")
        .build());
  }

  @Test
  public void addMetadata_k8s() throws Exception {
    MetadataExchanger exchanger = new MetadataExchanger(
        Attributes.builder()
            .put(stringKey("cloud.platform"), "gcp_kubernetes_engine")
            .put(stringKey("k8s.namespace.name"), "mynamespace1")
            .put(stringKey("k8s.cluster.name"), "mycluster1")
            .put(stringKey("cloud.availability_zone"), "myzone1")
            .put(stringKey("cloud.account.id"), "0001")
            .build(),
        ImmutableMap.of(
            "CSM_CANONICAL_SERVICE_NAME", "myservice1",
            "CSM_WORKLOAD_NAME", "myworkload1")::get);
    Metadata metadata = new Metadata();
    exchanger.newClientCallPlugin().addMetadata(metadata);

    Struct peer = Struct.parseFrom(BaseEncoding.base64().decode(metadata.get(
        Metadata.Key.of("x-envoy-peer-metadata", Metadata.ASCII_STRING_MARSHALLER))));
    assertThat(peer).isEqualTo(
        Struct.newBuilder()
          .putFields("type", Value.newBuilder().setStringValue("gcp_kubernetes_engine").build())
          .putFields("canonical_service", Value.newBuilder().setStringValue("myservice1").build())
          .putFields("workload_name", Value.newBuilder().setStringValue("myworkload1").build())
          .putFields("namespace_name", Value.newBuilder().setStringValue("mynamespace1").build())
          .putFields("cluster_name", Value.newBuilder().setStringValue("mycluster1").build())
          .putFields("location", Value.newBuilder().setStringValue("myzone1").build())
          .putFields("project_id", Value.newBuilder().setStringValue("0001").build())
          .build());
  }

  @Test
  public void addMetadata_gce() throws Exception {
    MetadataExchanger exchanger = new MetadataExchanger(
        Attributes.builder()
            .put(stringKey("cloud.platform"), "gcp_compute_engine")
            .put(stringKey("cloud.availability_zone"), "myzone1")
            .put(stringKey("cloud.account.id"), "0001")
            .build(),
        ImmutableMap.of(
            "CSM_CANONICAL_SERVICE_NAME", "myservice1",
            "CSM_WORKLOAD_NAME", "myworkload1")::get);
    Metadata metadata = new Metadata();
    exchanger.newClientCallPlugin().addMetadata(metadata);

    Struct peer = Struct.parseFrom(BaseEncoding.base64().decode(metadata.get(
        Metadata.Key.of("x-envoy-peer-metadata", Metadata.ASCII_STRING_MARSHALLER))));
    assertThat(peer).isEqualTo(
        Struct.newBuilder()
          .putFields("type", Value.newBuilder().setStringValue("gcp_compute_engine").build())
          .putFields("canonical_service", Value.newBuilder().setStringValue("myservice1").build())
          .putFields("workload_name", Value.newBuilder().setStringValue("myworkload1").build())
          .putFields("location", Value.newBuilder().setStringValue("myzone1").build())
          .putFields("project_id", Value.newBuilder().setStringValue("0001").build())
          .build());
  }
}
