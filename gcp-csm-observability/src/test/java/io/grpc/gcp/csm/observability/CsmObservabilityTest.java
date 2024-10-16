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
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.inprocess.InProcessSocketAddress;
import io.grpc.internal.testing.FakeNameResolverProvider;
import io.grpc.stub.ClientCalls;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.xds.ClusterImplLoadBalancerProvider;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link CsmObservability}. */
@RunWith(JUnit4.class)
public final class CsmObservabilityTest {
  @Rule
  public final OpenTelemetryRule openTelemetryTesting = OpenTelemetryRule.create();
  @Rule
  public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();

  private NameResolverProvider fakeNameResolverProvider;
  private InProcessSocketAddress socketAddress = new InProcessSocketAddress("csm-test-server");
  private ServerBuilder<?> serverBuilder = InProcessServerBuilder.forAddress(socketAddress)
      .addService(voidService(Status.OK))
      .directExecutor();

  @After
  public void tearDown() {
    if (fakeNameResolverProvider != null) {
      NameResolverRegistry.getDefaultRegistry().deregister(fakeNameResolverProvider);
    }
  }

  @Test
  public void unknownDataExchange() throws Exception {
    MetadataExchanger clientExchanger = new MetadataExchanger(
        Attributes.builder().build(),
        ImmutableMap.<String, String>of()::get);
    CsmObservability.Builder clientCsmBuilder = new CsmObservability.Builder(clientExchanger)
        .sdk(openTelemetryTesting.getOpenTelemetry());
    MetadataExchanger serverExchanger = new MetadataExchanger(
        Attributes.builder().build(),
        ImmutableMap.<String, String>of()::get);
    CsmObservability.Builder serverCsmBuilder = new CsmObservability.Builder(serverExchanger)
        .sdk(openTelemetryTesting.getOpenTelemetry());

    String target = "xds:///csm-test";
    register(new FakeNameResolverProvider(target, socketAddress));
    serverCsmBuilder.build().configureServerBuilder(serverBuilder);
    grpcCleanupRule.register(serverBuilder.build().start());

    ManagedChannelBuilder<?> channelBuilder = InProcessChannelBuilder.forTarget(target)
        .directExecutor();
    clientCsmBuilder.build().configureChannelBuilder(channelBuilder);
    Channel channel = grpcCleanupRule.register(channelBuilder.build());

    ClientCalls.blockingUnaryCall(
        channel, TestMethodDescriptors.voidMethod(), CallOptions.DEFAULT, null);
    Attributes preexistingClientAttributes = Attributes.builder()
        .put(stringKey("grpc.method"), "other")
        .put(stringKey("grpc.target"), target)
        .build();
    Attributes preexistingClientEndAttributes = preexistingClientAttributes.toBuilder()
        .put(stringKey("grpc.status"), "OK")
        .build();
    Attributes newClientAttributes = preexistingClientEndAttributes.toBuilder()
        .put(stringKey("csm.remote_workload_canonical_service"), "unknown")
        .put(stringKey("csm.remote_workload_type"),              "unknown")
        .put(stringKey("csm.service_name"),                      "unknown")
        .put(stringKey("csm.service_namespace_name"),            "unknown")
        .put(stringKey("csm.workload_canonical_service"),        "unknown")
        .put(stringKey("csm.mesh_id"),                           "unknown")
        .build();
    Attributes preexistingServerAttributes = Attributes.builder()
        .put(stringKey("grpc.method"), "other")
        .build();
    Attributes preexistingServerEndAttributes = preexistingServerAttributes.toBuilder()
        .put(stringKey("grpc.status"), "OK")
        .build();
    Attributes newServerAttributes = preexistingServerEndAttributes.toBuilder()
        .put(stringKey("csm.remote_workload_canonical_service"), "unknown")
        .put(stringKey("csm.remote_workload_type"),              "unknown")
        .put(stringKey("csm.workload_canonical_service"),        "unknown")
        .put(stringKey("csm.mesh_id"),                           "unknown")
        .build();
    assertMetrics(
        preexistingClientAttributes,
        preexistingClientEndAttributes,
        newClientAttributes,
        preexistingServerAttributes,
        newServerAttributes);
  }

  @Test
  public void nonCsmServer() throws Exception {
    MetadataExchanger clientExchanger = new MetadataExchanger(
        Attributes.builder().build(),
        ImmutableMap.<String, String>of()::get);
    CsmObservability.Builder clientCsmBuilder = new CsmObservability.Builder(clientExchanger)
        .sdk(openTelemetryTesting.getOpenTelemetry());

    String target = "xds:///csm-test";
    register(new FakeNameResolverProvider(target, socketAddress));
    grpcCleanupRule.register(serverBuilder.build().start());

    ManagedChannelBuilder<?> channelBuilder = InProcessChannelBuilder.forTarget(target)
        .directExecutor();
    clientCsmBuilder.build().configureChannelBuilder(channelBuilder);
    Channel channel = grpcCleanupRule.register(channelBuilder.build());

    ClientCalls.blockingUnaryCall(
        channel, TestMethodDescriptors.voidMethod(), CallOptions.DEFAULT, null);
    Attributes preexistingClientAttributes = Attributes.builder()
        .put(stringKey("grpc.method"), "other")
        .put(stringKey("grpc.target"), target)
        .build();
    Attributes preexistingClientEndAttributes = preexistingClientAttributes.toBuilder()
        .put(stringKey("grpc.status"), "OK")
        .build();
    Attributes newClientAttributes = preexistingClientEndAttributes.toBuilder()
        .put(stringKey("csm.remote_workload_canonical_service"), "unknown")
        .put(stringKey("csm.remote_workload_type"),              "unknown")
        .put(stringKey("csm.service_name"),                      "unknown")
        .put(stringKey("csm.service_namespace_name"),            "unknown")
        .put(stringKey("csm.workload_canonical_service"),        "unknown")
        .put(stringKey("csm.mesh_id"),                           "unknown")
        .build();
    OpenTelemetryAssertions.assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder(
             metric -> OpenTelemetryAssertions.assertThat(metric)
                .hasName("grpc.client.attempt.started")
                .hasLongSumSatisfying(
                    longSum -> longSum.hasPointsSatisfying(
                        point -> point.hasAttributes(preexistingClientAttributes))),
             metric -> OpenTelemetryAssertions.assertThat(metric)
                .hasName("grpc.client.attempt.duration")
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> point.hasAttributes(newClientAttributes))),
             metric -> OpenTelemetryAssertions.assertThat(metric)
                .hasName("grpc.client.attempt.sent_total_compressed_message_size")
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> point.hasAttributes(newClientAttributes))),
             metric -> OpenTelemetryAssertions.assertThat(metric)
                .hasName("grpc.client.attempt.rcvd_total_compressed_message_size")
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> point.hasAttributes(newClientAttributes))),
             metric -> OpenTelemetryAssertions.assertThat(metric)
                .hasName("grpc.client.call.duration")
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> point.hasAttributes(preexistingClientEndAttributes))));
  }

  @Test
  public void nonCsmClient() throws Exception {
    MetadataExchanger clientExchanger = new MetadataExchanger(
        Attributes.builder()
            .put(stringKey("cloud.platform"), "gcp_kubernetes_engine")
            .build(),
        ImmutableMap.<String, String>of()::get);
    CsmObservability.Builder clientCsmBuilder = new CsmObservability.Builder(clientExchanger)
        .sdk(openTelemetryTesting.getOpenTelemetry());
    MetadataExchanger serverExchanger = new MetadataExchanger(
        Attributes.builder().build(),
        ImmutableMap.<String, String>of()::get);
    CsmObservability.Builder serverCsmBuilder = new CsmObservability.Builder(serverExchanger)
        .sdk(openTelemetryTesting.getOpenTelemetry());

    String target = "xds://not-a-csm-authority/csm-test";
    register(new FakeNameResolverProvider(target, socketAddress));
    serverCsmBuilder.build().configureServerBuilder(serverBuilder);
    grpcCleanupRule.register(serverBuilder.build().start());

    ManagedChannelBuilder<?> channelBuilder = InProcessChannelBuilder.forTarget(target)
        .directExecutor();
    clientCsmBuilder.build().configureChannelBuilder(channelBuilder);
    Channel channel = grpcCleanupRule.register(channelBuilder.build());

    ClientCalls.blockingUnaryCall(
        channel, TestMethodDescriptors.voidMethod(), CallOptions.DEFAULT, null);
    Attributes preexistingClientAttributes = Attributes.builder()
        .put(stringKey("grpc.method"), "other")
        .put(stringKey("grpc.target"), target)
        .build();
    Attributes preexistingClientEndAttributes = preexistingClientAttributes.toBuilder()
        .put(stringKey("grpc.status"), "OK")
        .build();
    Attributes preexistingServerAttributes = Attributes.builder()
        .put(stringKey("grpc.method"), "other")
        .build();
    Attributes preexistingServerEndAttributes = preexistingServerAttributes.toBuilder()
        .put(stringKey("grpc.status"), "OK")
        .build();
    Attributes newServerAttributes = preexistingServerEndAttributes.toBuilder()
        .put(stringKey("csm.remote_workload_canonical_service"), "unknown")
        .put(stringKey("csm.remote_workload_type"),              "unknown")
        .put(stringKey("csm.workload_canonical_service"),        "unknown")
        .put(stringKey("csm.mesh_id"),                           "unknown")
        .build();
    assertMetrics(
        preexistingClientAttributes,
        preexistingClientEndAttributes,
        preexistingClientEndAttributes,
        preexistingServerAttributes,
        newServerAttributes);
  }

  @Test
  public void k8sExchange() throws Exception {
    MetadataExchanger clientExchanger = new MetadataExchanger(
        Attributes.builder()
            .put(stringKey("cloud.platform"), "gcp_kubernetes_engine")
            .put(stringKey("k8s.namespace.name"), "namespace-aeiou")
            .put(stringKey("k8s.cluster.name"), "mycluster1")
            .put(stringKey("cloud.region"), "us-central1")
            .put(stringKey("cloud.account.id"), "31415926")
            .build(),
        ImmutableMap.of(
            "CSM_CANONICAL_SERVICE_NAME", "canon-service-is-a-client",
            "CSM_WORKLOAD_NAME", "best-client",
            "CSM_MESH_ID", "mymesh")::get);
    CsmObservability.Builder clientCsmBuilder = new CsmObservability.Builder(clientExchanger)
        .sdk(openTelemetryTesting.getOpenTelemetry());
    MetadataExchanger serverExchanger = new MetadataExchanger(
        Attributes.builder()
            .put(stringKey("cloud.platform"), "gcp_kubernetes_engine")
            .put(stringKey("k8s.namespace.name"), "namespace-1e43c")
            .put(stringKey("k8s.cluster.name"), "mycluster2")
            .put(stringKey("cloud.availability_zone"), "us-east2-c")
            .put(stringKey("cloud.region"), "us-east2")
            .put(stringKey("cloud.account.id"), "11235813")
            .build(),
        ImmutableMap.of(
            "CSM_CANONICAL_SERVICE_NAME", "server-has-a-single-name",
            "CSM_WORKLOAD_NAME", "fast-server",
            "CSM_MESH_ID", "meshhh")::get);
    CsmObservability.Builder serverCsmBuilder = new CsmObservability.Builder(serverExchanger)
        .sdk(openTelemetryTesting.getOpenTelemetry());

    String target = "xds:///csm-test-k8s";
    register(new FakeNameResolverProvider(target, socketAddress));
    serverCsmBuilder.build().configureServerBuilder(serverBuilder);
    grpcCleanupRule.register(serverBuilder.build().start());

    ManagedChannelBuilder<?> channelBuilder = InProcessChannelBuilder.forTarget(target)
        .directExecutor()
        .intercept(new ProvideFilterMetadataInterceptor(
            ImmutableMap.of("com.google.csm.telemetry_labels", Struct.newBuilder()
                .putFields("service_name",
                    Value.newBuilder().setStringValue("second-server-name").build())
                .putFields("service_namespace",
                    Value.newBuilder().setStringValue("namespace-0001").build())
                .build())));
    clientCsmBuilder.build().configureChannelBuilder(channelBuilder);
    Channel channel = grpcCleanupRule.register(channelBuilder.build());

    ClientCalls.blockingUnaryCall(
        channel, TestMethodDescriptors.voidMethod(), CallOptions.DEFAULT, null);
    Attributes preexistingClientAttributes = Attributes.builder()
        .put(stringKey("grpc.method"), "other")
        .put(stringKey("grpc.target"), target)
        .build();
    Attributes preexistingClientEndAttributes = preexistingClientAttributes.toBuilder()
        .put(stringKey("grpc.status"), "OK")
        .build();
    Attributes newClientAttributes = preexistingClientEndAttributes.toBuilder()
        .put(stringKey("csm.remote_workload_canonical_service"), "server-has-a-single-name")
        .put(stringKey("csm.remote_workload_type"),              "gcp_kubernetes_engine")
        .put(stringKey("csm.remote_workload_project_id"),        "11235813")
        .put(stringKey("csm.remote_workload_location"),          "us-east2-c")
        .put(stringKey("csm.remote_workload_cluster_name"),      "mycluster2")
        .put(stringKey("csm.remote_workload_namespace_name"),    "namespace-1e43c")
        .put(stringKey("csm.remote_workload_name"),              "fast-server")
        .put(stringKey("csm.service_name"),                      "second-server-name")
        .put(stringKey("csm.service_namespace_name"),            "namespace-0001")
        .put(stringKey("csm.workload_canonical_service"),        "canon-service-is-a-client")
        .put(stringKey("csm.mesh_id"),                           "mymesh")
        .build();
    Attributes preexistingServerAttributes = Attributes.builder()
        .put(stringKey("grpc.method"), "other")
        .build();
    Attributes preexistingServerEndAttributes = preexistingServerAttributes.toBuilder()
        .put(stringKey("grpc.status"), "OK")
        .build();
    Attributes newServerAttributes = preexistingServerEndAttributes.toBuilder()
        .put(stringKey("csm.remote_workload_canonical_service"), "canon-service-is-a-client")
        .put(stringKey("csm.remote_workload_type"),              "gcp_kubernetes_engine")
        .put(stringKey("csm.remote_workload_project_id"),        "31415926")
        .put(stringKey("csm.remote_workload_location"),          "us-central1")
        .put(stringKey("csm.remote_workload_cluster_name"),      "mycluster1")
        .put(stringKey("csm.remote_workload_namespace_name"),    "namespace-aeiou")
        .put(stringKey("csm.remote_workload_name"),              "best-client")
        .put(stringKey("csm.workload_canonical_service"),        "server-has-a-single-name")
        .put(stringKey("csm.mesh_id"),                           "meshhh")
        .build();
    assertMetrics(
        preexistingClientAttributes,
        preexistingClientEndAttributes,
        newClientAttributes,
        preexistingServerAttributes,
        newServerAttributes);
  }

  @Test
  public void gceExchange() throws Exception {
    MetadataExchanger clientExchanger = new MetadataExchanger(
        Attributes.builder()
            .put(stringKey("cloud.platform"), "gcp_compute_engine")
            .put(stringKey("cloud.region"), "us-central1")
            .put(stringKey("cloud.account.id"), "31415926")
            .build(),
        ImmutableMap.of(
            "CSM_CANONICAL_SERVICE_NAME", "canon-service-is-a-client",
            "CSM_WORKLOAD_NAME", "best-client",
            "CSM_MESH_ID", "mymesh")::get);
    CsmObservability.Builder clientCsmBuilder = new CsmObservability.Builder(clientExchanger)
        .sdk(openTelemetryTesting.getOpenTelemetry());
    MetadataExchanger serverExchanger = new MetadataExchanger(
        Attributes.builder()
            .put(stringKey("cloud.platform"), "gcp_compute_engine")
            .put(stringKey("cloud.availability_zone"), "us-east2-c")
            .put(stringKey("cloud.region"), "us-east2")
            .put(stringKey("cloud.account.id"), "11235813")
            .build(),
        ImmutableMap.of(
            "CSM_CANONICAL_SERVICE_NAME", "server-has-a-single-name",
            "CSM_WORKLOAD_NAME", "fast-server",
            "CSM_MESH_ID", "meshhh")::get);
    CsmObservability.Builder serverCsmBuilder = new CsmObservability.Builder(serverExchanger)
        .sdk(openTelemetryTesting.getOpenTelemetry());

    String target = "xds:///csm-test";
    register(new FakeNameResolverProvider(target, socketAddress));
    serverCsmBuilder.build().configureServerBuilder(serverBuilder);
    grpcCleanupRule.register(serverBuilder.build().start());

    ManagedChannelBuilder<?> channelBuilder = InProcessChannelBuilder.forTarget(target)
        .directExecutor()
        .intercept(new ProvideFilterMetadataInterceptor(ImmutableMap.<String, Struct>of()));
    clientCsmBuilder.build().configureChannelBuilder(channelBuilder);
    Channel channel = grpcCleanupRule.register(channelBuilder.build());

    ClientCalls.blockingUnaryCall(
        channel, TestMethodDescriptors.voidMethod(), CallOptions.DEFAULT, null);
    Attributes preexistingClientAttributes = Attributes.builder()
        .put(stringKey("grpc.method"), "other")
        .put(stringKey("grpc.target"), target)
        .build();
    Attributes preexistingClientEndAttributes = preexistingClientAttributes.toBuilder()
        .put(stringKey("grpc.status"), "OK")
        .build();
    Attributes newClientAttributes = preexistingClientEndAttributes.toBuilder()
        .put(stringKey("csm.remote_workload_canonical_service"), "server-has-a-single-name")
        .put(stringKey("csm.remote_workload_type"),              "gcp_compute_engine")
        .put(stringKey("csm.remote_workload_project_id"),        "11235813")
        .put(stringKey("csm.remote_workload_location"),          "us-east2-c")
        .put(stringKey("csm.remote_workload_name"),              "fast-server")
        .put(stringKey("csm.service_name"),                      "unknown")
        .put(stringKey("csm.service_namespace_name"),            "unknown")
        .put(stringKey("csm.workload_canonical_service"),        "canon-service-is-a-client")
        .put(stringKey("csm.mesh_id"),                           "mymesh")
        .build();
    Attributes preexistingServerAttributes = Attributes.builder()
        .put(stringKey("grpc.method"), "other")
        .build();
    Attributes preexistingServerEndAttributes = preexistingServerAttributes.toBuilder()
        .put(stringKey("grpc.status"), "OK")
        .build();
    Attributes newServerAttributes = preexistingServerEndAttributes.toBuilder()
        .put(stringKey("csm.remote_workload_canonical_service"), "canon-service-is-a-client")
        .put(stringKey("csm.remote_workload_type"),              "gcp_compute_engine")
        .put(stringKey("csm.remote_workload_project_id"),        "31415926")
        .put(stringKey("csm.remote_workload_location"),          "us-central1")
        .put(stringKey("csm.remote_workload_name"),              "best-client")
        .put(stringKey("csm.workload_canonical_service"),        "server-has-a-single-name")
        .put(stringKey("csm.mesh_id"),                           "meshhh")
        .build();
    assertMetrics(
        preexistingClientAttributes,
        preexistingClientEndAttributes,
        newClientAttributes,
        preexistingServerAttributes,
        newServerAttributes);
  }

  @Test
  public void trailersOnly() throws Exception {
    MetadataExchanger clientExchanger = new MetadataExchanger(
        Attributes.builder()
            .put(stringKey("cloud.platform"), "gcp_compute_engine")
            .put(stringKey("cloud.region"), "us-central1")
            .put(stringKey("cloud.account.id"), "31415926")
            .build(),
        ImmutableMap.of(
            "CSM_CANONICAL_SERVICE_NAME", "canon-service-is-a-client",
            "CSM_WORKLOAD_NAME", "best-client",
            "CSM_MESH_ID", "mymesh")::get);
    CsmObservability.Builder clientCsmBuilder = new CsmObservability.Builder(clientExchanger)
        .sdk(openTelemetryTesting.getOpenTelemetry());

    MetadataExchanger serverExchanger = new MetadataExchanger(
        Attributes.builder()
            .put(stringKey("cloud.platform"), "gcp_compute_engine")
            .put(stringKey("cloud.availability_zone"), "us-east2-c")
            .put(stringKey("cloud.region"), "us-east2")
            .put(stringKey("cloud.account.id"), "11235813")
            .build(),
        ImmutableMap.of(
            "CSM_CANONICAL_SERVICE_NAME", "server-has-a-single-name",
            "CSM_WORKLOAD_NAME", "fast-server",
            "CSM_MESH_ID", "meshhh")::get);
    CsmObservability.Builder serverCsmBuilder = new CsmObservability.Builder(serverExchanger)
        .sdk(openTelemetryTesting.getOpenTelemetry());

    String target = "xds:///csm-test";
    register(new FakeNameResolverProvider(target, socketAddress));
    // Trailers-only
    serverBuilder.addService(voidService(Status.INVALID_ARGUMENT));
    serverCsmBuilder.build().configureServerBuilder(serverBuilder);
    grpcCleanupRule.register(serverBuilder.build().start());

    ManagedChannelBuilder<?> channelBuilder = InProcessChannelBuilder.forTarget(target)
        .directExecutor();
    clientCsmBuilder.build().configureChannelBuilder(channelBuilder);
    Channel channel = grpcCleanupRule.register(channelBuilder.build());

    assertThrows(StatusRuntimeException.class, () ->
        ClientCalls.blockingUnaryCall(
            channel, TestMethodDescriptors.voidMethod(), CallOptions.DEFAULT, null));
    Attributes preexistingClientAttributes = Attributes.builder()
        .put(stringKey("grpc.method"), "other")
        .put(stringKey("grpc.target"), target)
        .build();
    Attributes preexistingClientEndAttributes = preexistingClientAttributes.toBuilder()
        .put(stringKey("grpc.status"), "INVALID_ARGUMENT")
        .build();
    Attributes newClientAttributes = preexistingClientEndAttributes.toBuilder()
        .put(stringKey("csm.remote_workload_canonical_service"), "server-has-a-single-name")
        .put(stringKey("csm.remote_workload_type"),              "gcp_compute_engine")
        .put(stringKey("csm.remote_workload_project_id"),        "11235813")
        .put(stringKey("csm.remote_workload_location"),          "us-east2-c")
        .put(stringKey("csm.remote_workload_name"),              "fast-server")
        .put(stringKey("csm.service_name"),                      "unknown")
        .put(stringKey("csm.service_namespace_name"),            "unknown")
        .put(stringKey("csm.workload_canonical_service"),        "canon-service-is-a-client")
        .put(stringKey("csm.mesh_id"),                           "mymesh")
        .build();
    Attributes preexistingServerAttributes = Attributes.builder()
        .put(stringKey("grpc.method"), "other")
        .build();
    Attributes preexistingServerEndAttributes = preexistingServerAttributes.toBuilder()
        .put(stringKey("grpc.status"), "INVALID_ARGUMENT")
        .build();
    Attributes newServerAttributes = preexistingServerEndAttributes.toBuilder()
        .put(stringKey("csm.remote_workload_canonical_service"), "canon-service-is-a-client")
        .put(stringKey("csm.remote_workload_type"),              "gcp_compute_engine")
        .put(stringKey("csm.remote_workload_project_id"),        "31415926")
        .put(stringKey("csm.remote_workload_location"),          "us-central1")
        .put(stringKey("csm.remote_workload_name"),              "best-client")
        .put(stringKey("csm.workload_canonical_service"),        "server-has-a-single-name")
        .put(stringKey("csm.mesh_id"),                           "meshhh")
        .build();
    assertMetrics(
        preexistingClientAttributes,
        preexistingClientEndAttributes,
        newClientAttributes,
        preexistingServerAttributes,
        newServerAttributes);
  }

  private void register(NameResolverProvider provider) {
    assertThat(fakeNameResolverProvider).isNull();
    fakeNameResolverProvider = provider;
    NameResolverRegistry.getDefaultRegistry().register(provider);
  }

  private static ServerServiceDefinition voidService(Status status) {
    return ServerServiceDefinition.builder(TestMethodDescriptors.voidMethod().getServiceName())
        .addMethod(TestMethodDescriptors.voidMethod(), (call, headers) -> {
          if (status.isOk()) {
            call.sendHeaders(new Metadata());
            call.sendMessage(null);
          }
          call.close(status, new Metadata());
          return new ServerCall.Listener<Void>() {};
        })
        .build();
  }

  private void assertMetrics(
      Attributes preexistingClientAttributes,
      Attributes preexistingClientEndAttributes,
      Attributes newClientAttributes,
      Attributes preexistingServerAttributes,
      Attributes newServerAttributes) {
    OpenTelemetryAssertions.assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder(
             metric -> OpenTelemetryAssertions.assertThat(metric)
                .hasName("grpc.client.attempt.started")
                .hasLongSumSatisfying(
                    longSum -> longSum.hasPointsSatisfying(
                        point -> point.hasAttributes(preexistingClientAttributes))),
             metric -> OpenTelemetryAssertions.assertThat(metric)
                .hasName("grpc.client.attempt.duration")
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> point.hasAttributes(newClientAttributes))),
             metric -> OpenTelemetryAssertions.assertThat(metric)
                .hasName("grpc.client.attempt.sent_total_compressed_message_size")
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> point.hasAttributes(newClientAttributes))),
             metric -> OpenTelemetryAssertions.assertThat(metric)
                .hasName("grpc.client.attempt.rcvd_total_compressed_message_size")
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> point.hasAttributes(newClientAttributes))),
             metric -> OpenTelemetryAssertions.assertThat(metric)
                .hasName("grpc.client.call.duration")
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> point.hasAttributes(preexistingClientEndAttributes))),
             metric -> OpenTelemetryAssertions.assertThat(metric)
                .hasName("grpc.server.call.started")
                .hasLongSumSatisfying(
                    longSum -> longSum.hasPointsSatisfying(
                        point -> point.hasAttributes(preexistingServerAttributes))),
             metric -> OpenTelemetryAssertions.assertThat(metric)
                .hasName("grpc.server.call.duration")
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> point.hasAttributes(newServerAttributes))),
             metric -> OpenTelemetryAssertions.assertThat(metric)
                .hasName("grpc.server.call.sent_total_compressed_message_size")
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> point.hasAttributes(newServerAttributes))),
             metric -> OpenTelemetryAssertions.assertThat(metric)
                .hasName("grpc.server.call.rcvd_total_compressed_message_size")
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> point.hasAttributes(newServerAttributes))));
  }

  private static class ProvideFilterMetadataInterceptor implements ClientInterceptor {
    private final ImmutableMap<String, Struct> filterMetadata;

    public ProvideFilterMetadataInterceptor(ImmutableMap<String, Struct> filterMetadata) {
      this.filterMetadata = filterMetadata;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      callOptions.getOption(ClusterImplLoadBalancerProvider.FILTER_METADATA_CONSUMER)
          .accept(filterMetadata);
      return next.newCall(method, callOptions);
    }
  }
}
