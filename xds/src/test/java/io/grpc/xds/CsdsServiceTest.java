/*
 * Copyright 2021 The gRPC Authors
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
import static com.google.common.truth.Truth.assertWithMessage;
import static io.grpc.xds.AbstractXdsClient.ResourceType.CDS;
import static io.grpc.xds.AbstractXdsClient.ResourceType.EDS;
import static io.grpc.xds.AbstractXdsClient.ResourceType.LDS;
import static io.grpc.xds.AbstractXdsClient.ResourceType.RDS;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.Timestamp;
import io.envoyproxy.envoy.admin.v3.ClientResourceStatus;
import io.envoyproxy.envoy.admin.v3.ClustersConfigDump;
import io.envoyproxy.envoy.admin.v3.ClustersConfigDump.DynamicCluster;
import io.envoyproxy.envoy.admin.v3.EndpointsConfigDump;
import io.envoyproxy.envoy.admin.v3.EndpointsConfigDump.DynamicEndpointConfig;
import io.envoyproxy.envoy.admin.v3.ListenersConfigDump;
import io.envoyproxy.envoy.admin.v3.ListenersConfigDump.DynamicListener;
import io.envoyproxy.envoy.admin.v3.ListenersConfigDump.DynamicListenerState;
import io.envoyproxy.envoy.admin.v3.RoutesConfigDump;
import io.envoyproxy.envoy.admin.v3.RoutesConfigDump.DynamicRouteConfig;
import io.envoyproxy.envoy.admin.v3.UpdateFailureState;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.service.status.v3.ClientConfig;
import io.envoyproxy.envoy.service.status.v3.ClientStatusDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.status.v3.ClientStatusRequest;
import io.envoyproxy.envoy.service.status.v3.ClientStatusResponse;
import io.envoyproxy.envoy.service.status.v3.PerXdsConfig;
import io.envoyproxy.envoy.type.matcher.v3.NodeMatcher;
import io.grpc.InsecureChannelCredentials;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.testing.StreamRecorder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcServerRule;
import io.grpc.xds.AbstractXdsClient.ResourceType;
import io.grpc.xds.XdsClient.ResourceMetadata;
import io.grpc.xds.XdsClient.ResourceMetadata.ResourceMetadataStatus;
import io.grpc.xds.XdsNameResolverProvider.XdsClientPoolFactory;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CsdsService}. */
@RunWith(Enclosed.class)
public class CsdsServiceTest {
  private static final String SERVER_URI = "trafficdirector.googleapis.com";
  private static final String NODE_ID =
      "projects/42/networks/default/nodes/5c85b298-6f5b-4722-b74a-f7d1f0ccf5ad";
  private static final EnvoyProtoData.Node BOOTSTRAP_NODE =
      EnvoyProtoData.Node.newBuilder().setId(NODE_ID).build();
  private static final XdsClient XDS_CLIENT_NO_RESOURCES = new XdsClient() {
    @Override
    Bootstrapper.BootstrapInfo getBootstrapInfo() {
      return Bootstrapper.BootstrapInfo.builder()
          .servers(Arrays.asList(
              Bootstrapper.ServerInfo.create(
                  SERVER_URI, InsecureChannelCredentials.create(), false)))
          .node(BOOTSTRAP_NODE)
          .build();
    }

    @Override
    String getCurrentVersion(ResourceType type) {
      return "getCurrentVersion." + type.name();
    }

    @Override
    Map<String, ResourceMetadata> getSubscribedResourcesMetadata(ResourceType type) {
      return ImmutableMap.of();
    }
  };

  @RunWith(JUnit4.class)
  public static class ServiceTests {
    private static final CsdsService CSDS_SERVICE_MINIMAL =
        new CsdsService(new FakeXdsClientPoolFactory(XDS_CLIENT_NO_RESOURCES));
    private static final ClientStatusRequest REQUEST = ClientStatusRequest.getDefaultInstance();
    private static final ClientStatusRequest REQUEST_INVALID =
        ClientStatusRequest.newBuilder().addNodeMatchers(NodeMatcher.getDefaultInstance()).build();

    @Rule public final GrpcServerRule grpcServerRule = new GrpcServerRule().directExecutor();

    private ClientStatusDiscoveryServiceGrpc.ClientStatusDiscoveryServiceBlockingStub csdsStub;
    private ClientStatusDiscoveryServiceGrpc.ClientStatusDiscoveryServiceStub csdsAsyncStub;

    @Before
    public void setUp() {
      csdsStub = ClientStatusDiscoveryServiceGrpc.newBlockingStub(grpcServerRule.getChannel());
      csdsAsyncStub = ClientStatusDiscoveryServiceGrpc.newStub(grpcServerRule.getChannel());
    }

    /** Until XdsClient successfully initialized, expect response to be empty. */
    @Test
    public void fetchClientConfig_xdsClientPoolNotInitialized() {
      grpcServerRule.getServiceRegistry().addService(CsdsService.newInstance());
      ClientStatusResponse response = csdsStub.fetchClientStatus(REQUEST);
      assertThat(response).isEqualTo(ClientStatusResponse.getDefaultInstance());
    }

    /** Status.INVALID_ARGUMENT on unexpected request fields. */
    @Test
    public void fetchClientConfig_invalidArgument() {
      grpcServerRule.getServiceRegistry().addService(CSDS_SERVICE_MINIMAL);
      try {
        ClientStatusResponse response = csdsStub.fetchClientStatus(REQUEST_INVALID);
        fail("Should've failed, got response: " + response);
      } catch (StatusRuntimeException e) {
        verifyRequestInvalidResponseStatus(e.getStatus());
      }
    }

    /** Unexpected exceptions translated to internal error status. */
    @Test
    public void fetchClientConfig_unexpectedException() {
      XdsClient throwingXdsClient = new XdsClient() {
        @Override
        Map<String, ResourceMetadata> getSubscribedResourcesMetadata(ResourceType type) {
          throw new IllegalArgumentException("IllegalArgumentException");
        }
      };
      grpcServerRule.getServiceRegistry()
          .addService(new CsdsService(new FakeXdsClientPoolFactory(throwingXdsClient)));

      try {
        ClientStatusResponse response = csdsStub.fetchClientStatus(REQUEST);
        fail("Should've failed, got response: " + response);
      } catch (StatusRuntimeException e) {
        assertThat(e.getStatus().getCode()).isEqualTo(Code.INTERNAL);
        assertThat(e.getStatus().getDescription()).isEqualTo("Unexpected internal error");
      }
    }

    /** ClientStatusResponse contains valid ClientConfig with the correct shape. */
    @Test
    public void fetchClientConfig_happyPath() {
      grpcServerRule.getServiceRegistry().addService(CSDS_SERVICE_MINIMAL);
      verifyResponse(csdsStub.fetchClientStatus(REQUEST));
    }

    @Test
    public void streamClientStatus_happyPath() {
      CsdsService csdsService =
          new CsdsService(new FakeXdsClientPoolFactory(XDS_CLIENT_NO_RESOURCES) {
            boolean calledOnce;

            @Override
            @Nullable
            public ObjectPool<XdsClient> get() {
              // xDS client not ready on the first call, then becomes ready.
              if (!calledOnce) {
                calledOnce = true;
                return null;
              } else {
                return super.get();
              }
            }
          });

      grpcServerRule.getServiceRegistry().addService(csdsService);

      StreamRecorder<ClientStatusResponse> responseObserver = StreamRecorder.create();
      StreamObserver<ClientStatusRequest> requestObserver =
          csdsAsyncStub.streamClientStatus(responseObserver);

      requestObserver.onNext(REQUEST);
      requestObserver.onNext(REQUEST);
      requestObserver.onNext(REQUEST);
      requestObserver.onCompleted();

      List<ClientStatusResponse> responses = responseObserver.getValues();
      assertThat(responses.size()).isEqualTo(3);
      // Empty response on XdsClient not ready.
      assertThat(responses.get(0)).isEqualTo(ClientStatusResponse.getDefaultInstance());
      // The following calls return ClientConfig's successfully.
      verifyResponse(responses.get(1));
      verifyResponse(responses.get(2));
    }

    @Test
    public void streamClientStatus_requestInvalid() {
      grpcServerRule.getServiceRegistry().addService(CSDS_SERVICE_MINIMAL);

      StreamRecorder<ClientStatusResponse> responseObserver = StreamRecorder.create();
      StreamObserver<ClientStatusRequest> requestObserver =
          csdsAsyncStub.streamClientStatus(responseObserver);

      requestObserver.onNext(REQUEST);
      requestObserver.onNext(REQUEST_INVALID);
      requestObserver.onNext(REQUEST);
      requestObserver.onCompleted();

      List<ClientStatusResponse> responses = responseObserver.getValues();
      assertThat(responses.size()).isEqualTo(1);
      verifyResponse(responses.get(0));
      assertThat(responseObserver.getError()).isNotNull();
      verifyRequestInvalidResponseStatus(Status.fromThrowable(responseObserver.getError()));
    }

    @Test
    public void streamClientStatus_onClientError() {
      grpcServerRule.getServiceRegistry().addService(CSDS_SERVICE_MINIMAL);

      StreamRecorder<ClientStatusResponse> responseObserver = StreamRecorder.create();
      StreamObserver<ClientStatusRequest> requestObserver =
          csdsAsyncStub.streamClientStatus(responseObserver);

      requestObserver.onNext(REQUEST);
      requestObserver.onError(new StatusRuntimeException(Status.DATA_LOSS));

      List<ClientStatusResponse> responses = responseObserver.getValues();
      assertThat(responses.size()).isEqualTo(1);
      verifyResponse(responses.get(0));
      // Server quietly closes its side.
      assertThat(responseObserver.getError()).isNull();
    }

    private void verifyResponse(ClientStatusResponse response) {
      assertThat(response.getConfigCount()).isEqualTo(1);
      ClientConfig clientConfig = response.getConfig(0);
      verifyClientConfigNode(clientConfig);
      verifyClientConfigNoResources(clientConfig);
    }

    private void verifyRequestInvalidResponseStatus(Status status) {
      assertThat(status.getCode()).isEqualTo(Code.INVALID_ARGUMENT);
      assertThat(status.getDescription()).isEqualTo("node_matchers not supported");
    }
  }

  @RunWith(JUnit4.class)
  public static class MetadataToProtoTests {
    private static final String LDS_RESOURCE = "listener.googleapis.com";
    private static final String RDS_RESOURCE = "route-configuration.googleapis.com";
    private static final String CDS_RESOURCE = "cluster.googleapis.com";
    private static final String EDS_RESOURCE = "cluster-load-assignment.googleapis.com";
    private static final String VERSION_ACK = "42";
    private static final String VERSION_NACK = "43";
    private static final String ERROR = "Parse error line 1\n Parse error line 2";

    // Test timestamps.
    private static final Timestamp TIMESTAMP_ZERO = Timestamp.getDefaultInstance();
    private static final long NANOS_LAST_UPDATE = 1577923199_606042047L;
    private static final Timestamp TIMESTAMP_LAST_UPDATE = Timestamp.newBuilder()
        .setSeconds(1577923199L)  // 2020-01-01T23:59:59Z
        .setNanos(606042047)
        .build();
    private static final long NANOS_FAILED_UPDATE = 1609545599_732105843L;
    private static final Timestamp TIMESTAMP_FAILED_UPDATE = Timestamp.newBuilder()
        .setSeconds(1609545599L)  // 2021-01-01T23:59:59Z
        .setNanos(732105843)
        .build();

    // Raw resources.
    private static final Any RAW_LISTENER =
        Any.pack(Listener.newBuilder().setName(LDS_RESOURCE).build());
    private static final Any RAW_ROUTE_CONFIGURATION =
        Any.pack(RouteConfiguration.newBuilder().setName(RDS_RESOURCE).build());
    private static final Any RAW_CLUSTER =
        Any.pack(Cluster.newBuilder().setName(CDS_RESOURCE).build());
    private static final Any RAW_CLUSTER_LOAD_ASSIGNMENT =
        Any.pack(ClusterLoadAssignment.newBuilder().setClusterName(EDS_RESOURCE).build());

    // Test metadata: no data received states.
    private static final ResourceMetadata METADATA_UNKNOWN =
        ResourceMetadata.newResourceMetadataUnknown();
    private static final ResourceMetadata METADATA_DOES_NOT_EXIST =
        ResourceMetadata.newResourceMetadataDoesNotExist();
    private static final ResourceMetadata METADATA_REQUESTED =
        ResourceMetadata.newResourceMetadataRequested();

    // Test metadata: resource acknowledged state, per resource type.
    private static final ResourceMetadata METADATA_ACKED_LDS = ResourceMetadata
        .newResourceMetadataAcked(RAW_LISTENER, VERSION_ACK, NANOS_LAST_UPDATE);
    private static final ResourceMetadata METADATA_ACKED_RDS = ResourceMetadata
        .newResourceMetadataAcked(RAW_ROUTE_CONFIGURATION, VERSION_ACK, NANOS_LAST_UPDATE);
    private static final ResourceMetadata METADATA_ACKED_CDS = ResourceMetadata
        .newResourceMetadataAcked(RAW_CLUSTER, VERSION_ACK, NANOS_LAST_UPDATE);
    private static final ResourceMetadata METADATA_ACKED_EDS = ResourceMetadata
        .newResourceMetadataAcked(RAW_CLUSTER_LOAD_ASSIGNMENT, VERSION_ACK, NANOS_LAST_UPDATE);

    // Test resources list.
    private static final ImmutableMap<String, ResourceMetadata> RESOURCES_METADATA =
        ImmutableMap.of("A", METADATA_UNKNOWN, "B", METADATA_REQUESTED);

    /* LDS tests */

    @Test
    public void dumpLdsConfig() {
      ListenersConfigDump ldsConfig = CsdsService.dumpLdsConfig(RESOURCES_METADATA, VERSION_ACK);
      assertThat(ldsConfig.getVersionInfo()).isEqualTo(VERSION_ACK);
      assertThat(ldsConfig.getStaticListenersCount()).isEqualTo(0);
      assertThat(ldsConfig.getDynamicListenersCount()).isEqualTo(2);
      // Minimal check to confirm that resources generated from corresponding metadata.
      DynamicListener listenerA = ldsConfig.getDynamicListeners(0);
      assertThat(listenerA.getName()).isEqualTo("A");
      assertThat(listenerA.getClientStatus()).isEqualTo(ClientResourceStatus.UNKNOWN);
      DynamicListener listenerB = ldsConfig.getDynamicListeners(1);
      assertThat(listenerB.getName()).isEqualTo("B");
      assertThat(listenerB.getClientStatus()).isEqualTo(ClientResourceStatus.REQUESTED);
    }

    @Test
    public void buildDynamicListener_metadataUnknown() {
      DynamicListener dynamicListener =
          CsdsService.buildDynamicListener(LDS_RESOURCE, METADATA_UNKNOWN);
      verifyDynamicListener(dynamicListener, ClientResourceStatus.UNKNOWN);
      verifyDynamicListenerStateNoData(dynamicListener.getActiveState());
    }

    @Test
    public void buildDynamicListener_metadataDoesNotExist() {
      DynamicListener dynamicListener =
          CsdsService.buildDynamicListener(LDS_RESOURCE, METADATA_DOES_NOT_EXIST);
      verifyDynamicListener(dynamicListener, ClientResourceStatus.DOES_NOT_EXIST);
      verifyDynamicListenerStateNoData(dynamicListener.getActiveState());
    }

    @Test
    public void buildDynamicListener_metadataRequested() {
      DynamicListener dynamicListener =
          CsdsService.buildDynamicListener(LDS_RESOURCE, METADATA_REQUESTED);
      verifyDynamicListener(dynamicListener, ClientResourceStatus.REQUESTED);
      verifyDynamicListenerStateNoData(dynamicListener.getActiveState());
    }

    @Test
    public void buildDynamicListener_metadataAcked() {
      DynamicListener dynamicListener =
          CsdsService.buildDynamicListener(LDS_RESOURCE, METADATA_ACKED_LDS);
      verifyDynamicListener(dynamicListener, ClientResourceStatus.ACKED);
      verifyDynamicListenerStateAccepted(dynamicListener.getActiveState());
    }

    @Test
    public void buildDynamicListener_metadataNackedFromRequested() {
      ResourceMetadata metadata = ResourceMetadata.newResourceMetadataNacked(
          METADATA_REQUESTED, VERSION_NACK, NANOS_FAILED_UPDATE, ERROR);
      DynamicListener dynamicListener = CsdsService.buildDynamicListener(LDS_RESOURCE, metadata);
      verifyDynamicListener(dynamicListener, ClientResourceStatus.NACKED);
      verifyErrorState(dynamicListener.getErrorState());
      verifyDynamicListenerStateNoData(dynamicListener.getActiveState());
    }

    @Test
    public void buildDynamicListener_metadataNackedFromAcked() {
      ResourceMetadata metadata = ResourceMetadata.newResourceMetadataNacked(
          METADATA_ACKED_LDS, VERSION_NACK, NANOS_FAILED_UPDATE, ERROR);
      DynamicListener dynamicListener = CsdsService.buildDynamicListener(LDS_RESOURCE, metadata);
      verifyDynamicListener(dynamicListener, ClientResourceStatus.NACKED);
      verifyErrorState(dynamicListener.getErrorState());
      verifyDynamicListenerStateAccepted(dynamicListener.getActiveState());
    }

    private void verifyDynamicListener(
        DynamicListener dynamicListener, ClientResourceStatus status) {
      assertWithMessage("name").that(dynamicListener.getName()).isEqualTo(LDS_RESOURCE);
      assertWithMessage("active_state").that(dynamicListener.hasActiveState()).isTrue();
      assertWithMessage("warming_state").that(dynamicListener.hasWarmingState()).isFalse();
      assertWithMessage("draining_state").that(dynamicListener.hasDrainingState()).isFalse();
      assertWithMessage("error_state").that(dynamicListener.hasErrorState())
          .isEqualTo(status.equals(ClientResourceStatus.NACKED));
      assertWithMessage("client_status").that(dynamicListener.getClientStatus()).isEqualTo(status);
    }

    private void verifyDynamicListenerStateNoData(DynamicListenerState dynamicListenerState) {
      assertWithMessage("version_info").that(dynamicListenerState.getVersionInfo()).isEmpty();
      assertWithMessage("listener").that(dynamicListenerState.hasListener()).isFalse();
      assertWithMessage("last_updated").that(dynamicListenerState.getLastUpdated())
          .isEqualTo(TIMESTAMP_ZERO);
    }

    private void verifyDynamicListenerStateAccepted(DynamicListenerState dynamicListenerState) {
      assertWithMessage("version_info").that(dynamicListenerState.getVersionInfo())
          .isEqualTo(VERSION_ACK);
      assertWithMessage("listener").that(dynamicListenerState.hasListener()).isTrue();
      assertWithMessage("listener").that(dynamicListenerState.getListener())
          .isEqualTo(RAW_LISTENER);
      assertWithMessage("last_updated").that(dynamicListenerState.getLastUpdated())
          .isEqualTo(TIMESTAMP_LAST_UPDATE);
    }

    /* RDS tests */

    @Test
    public void dumpRdsConfig() {
      RoutesConfigDump rdsConfig = CsdsService.dumpRdsConfig(RESOURCES_METADATA);
      assertThat(rdsConfig.getStaticRouteConfigsCount()).isEqualTo(0);
      assertThat(rdsConfig.getDynamicRouteConfigsCount()).isEqualTo(2);
      // Minimal check to confirm that resources generated from corresponding metadata.
      assertThat(rdsConfig.getDynamicRouteConfigs(0).getClientStatus())
          .isEqualTo(ClientResourceStatus.UNKNOWN);
      assertThat(rdsConfig.getDynamicRouteConfigs(1).getClientStatus())
          .isEqualTo(ClientResourceStatus.REQUESTED);
    }

    @Test
    public void buildDynamicRouteConfig_metadataUnknown() {
      verifyDynamicRouteConfigNoData(
          CsdsService.buildDynamicRouteConfig(METADATA_UNKNOWN),
          ClientResourceStatus.UNKNOWN);
    }

    @Test
    public void buildDynamicRouteConfig_metadataDoesNotExist() {
      verifyDynamicRouteConfigNoData(
          CsdsService.buildDynamicRouteConfig(METADATA_DOES_NOT_EXIST),
          ClientResourceStatus.DOES_NOT_EXIST);
    }

    @Test
    public void buildDynamicRouteConfig_metadataRequested() {
      verifyDynamicRouteConfigNoData(
          CsdsService.buildDynamicRouteConfig(METADATA_REQUESTED),
          ClientResourceStatus.REQUESTED);
    }

    @Test
    public void buildDynamicRouteConfig_metadataAcked() {
      verifyDynamicRouteConfigAccepted(
          CsdsService.buildDynamicRouteConfig(METADATA_ACKED_RDS),
          ClientResourceStatus.ACKED);
    }

    @Test
    public void buildDynamicRouteConfig_metadataNackedFromRequested() {
      ResourceMetadata metadata = ResourceMetadata.newResourceMetadataNacked(
          METADATA_REQUESTED, VERSION_NACK, NANOS_FAILED_UPDATE, ERROR);
      DynamicRouteConfig dynamicRouteConfig = CsdsService.buildDynamicRouteConfig(metadata);
      verifyDynamicRouteConfigNoData(dynamicRouteConfig, ClientResourceStatus.NACKED);
      verifyErrorState(dynamicRouteConfig.getErrorState());
    }

    @Test
    public void buildDynamicRouteConfig_metadataNackedFromAcked() {
      ResourceMetadata metadata = ResourceMetadata.newResourceMetadataNacked(
          METADATA_ACKED_RDS, VERSION_NACK, NANOS_FAILED_UPDATE, ERROR);
      DynamicRouteConfig dynamicRouteConfig = CsdsService.buildDynamicRouteConfig(metadata);
      verifyDynamicRouteConfigAccepted(dynamicRouteConfig, ClientResourceStatus.NACKED);
      verifyErrorState(dynamicRouteConfig.getErrorState());
    }

    private void verifyDynamicRouteConfigNoData(
        DynamicRouteConfig dynamicRouteConfig, ClientResourceStatus status) {
      assertWithMessage("version_info").that(dynamicRouteConfig.getVersionInfo()).isEmpty();
      assertWithMessage("route_config").that(dynamicRouteConfig.hasRouteConfig()).isFalse();
      assertWithMessage("last_updated").that(dynamicRouteConfig.getLastUpdated())
          .isEqualTo(TIMESTAMP_ZERO);
      assertWithMessage("error_state").that(dynamicRouteConfig.hasErrorState())
          .isEqualTo(status.equals(ClientResourceStatus.NACKED));
      assertWithMessage("client_status").that(dynamicRouteConfig.getClientStatus())
          .isEqualTo(status);
    }

    private void verifyDynamicRouteConfigAccepted(
        DynamicRouteConfig dynamicRouteConfig, ClientResourceStatus status) {
      assertWithMessage("version_info").that(dynamicRouteConfig.getVersionInfo())
          .isEqualTo(VERSION_ACK);
      assertWithMessage("route_config").that(dynamicRouteConfig.hasRouteConfig()).isTrue();
      assertWithMessage("route_config").that(dynamicRouteConfig.getRouteConfig())
          .isEqualTo(RAW_ROUTE_CONFIGURATION);
      assertWithMessage("last_updated").that(dynamicRouteConfig.getLastUpdated())
          .isEqualTo(TIMESTAMP_LAST_UPDATE);
      assertWithMessage("error_state").that(dynamicRouteConfig.hasErrorState())
          .isEqualTo(status.equals(ClientResourceStatus.NACKED));
      assertWithMessage("client_status").that(dynamicRouteConfig.getClientStatus())
          .isEqualTo(status);
    }

    /* CDS tests */

    @Test
    public void dumpCdsConfig() {
      ClustersConfigDump cdsConfig = CsdsService.dumpCdsConfig(RESOURCES_METADATA, VERSION_ACK);
      assertThat(cdsConfig.getVersionInfo()).isEqualTo(VERSION_ACK);
      assertThat(cdsConfig.getStaticClustersCount()).isEqualTo(0);
      assertThat(cdsConfig.getDynamicWarmingClustersCount()).isEqualTo(0);
      assertThat(cdsConfig.getDynamicActiveClustersCount()).isEqualTo(2);
      // Minimal check to confirm that resources generated from corresponding metadata.
      assertThat(cdsConfig.getDynamicActiveClusters(0).getClientStatus())
          .isEqualTo(ClientResourceStatus.UNKNOWN);
      assertThat(cdsConfig.getDynamicActiveClusters(1).getClientStatus())
          .isEqualTo(ClientResourceStatus.REQUESTED);
    }

    @Test
    public void buildDynamicCluster_metadataUnknown() {
      verifyDynamicClusterNoData(
          CsdsService.buildDynamicCluster(METADATA_UNKNOWN),
          ClientResourceStatus.UNKNOWN);
    }

    @Test
    public void buildDynamicCluster_metadataDoesNotExist() {
      verifyDynamicClusterNoData(
          CsdsService.buildDynamicCluster(METADATA_DOES_NOT_EXIST),
          ClientResourceStatus.DOES_NOT_EXIST);
    }

    @Test
    public void buildDynamicCluster_metadataRequested() {
      verifyDynamicClusterNoData(
          CsdsService.buildDynamicCluster(METADATA_REQUESTED),
          ClientResourceStatus.REQUESTED);
    }

    @Test
    public void buildDynamicCluster_metadataAcked() {
      verifyDynamicClusterAccepted(
          CsdsService.buildDynamicCluster(METADATA_ACKED_CDS),
          ClientResourceStatus.ACKED);
    }

    @Test
    public void buildDynamicCluster_metadataNackedFromRequested() {
      ResourceMetadata metadata = ResourceMetadata.newResourceMetadataNacked(
          METADATA_REQUESTED, VERSION_NACK, NANOS_FAILED_UPDATE, ERROR);
      DynamicCluster dynamicCluster = CsdsService.buildDynamicCluster(metadata);
      verifyDynamicClusterNoData(dynamicCluster, ClientResourceStatus.NACKED);
      verifyErrorState(dynamicCluster.getErrorState());
    }

    @Test
    public void buildDynamicCluster_metadataNackedFromAcked() {
      ResourceMetadata metadata = ResourceMetadata.newResourceMetadataNacked(
          METADATA_ACKED_CDS, VERSION_NACK, NANOS_FAILED_UPDATE, ERROR);
      DynamicCluster dynamicCluster = CsdsService.buildDynamicCluster(metadata);
      verifyDynamicClusterAccepted(dynamicCluster, ClientResourceStatus.NACKED);
      verifyErrorState(dynamicCluster.getErrorState());
    }

    private void verifyDynamicClusterNoData(
        DynamicCluster dynamicCluster, ClientResourceStatus status) {
      assertWithMessage("version_info").that(dynamicCluster.getVersionInfo()).isEmpty();
      assertWithMessage("route_config").that(dynamicCluster.hasCluster()).isFalse();
      assertWithMessage("last_updated").that(dynamicCluster.getLastUpdated())
          .isEqualTo(TIMESTAMP_ZERO);
      assertWithMessage("error_state").that(dynamicCluster.hasErrorState())
          .isEqualTo(status.equals(ClientResourceStatus.NACKED));
      assertWithMessage("client_status").that(dynamicCluster.getClientStatus()).isEqualTo(status);
    }

    private void verifyDynamicClusterAccepted(
        DynamicCluster dynamicCluster, ClientResourceStatus status) {
      assertWithMessage("version_info").that(dynamicCluster.getVersionInfo())
          .isEqualTo(VERSION_ACK);
      assertWithMessage("route_config").that(dynamicCluster.hasCluster()).isTrue();
      assertWithMessage("route_config").that(dynamicCluster.getCluster()).isEqualTo(RAW_CLUSTER);
      assertWithMessage("last_updated").that(dynamicCluster.getLastUpdated())
          .isEqualTo(TIMESTAMP_LAST_UPDATE);
      assertWithMessage("error_state").that(dynamicCluster.hasErrorState())
          .isEqualTo(status.equals(ClientResourceStatus.NACKED));
      assertWithMessage("client_status").that(dynamicCluster.getClientStatus()).isEqualTo(status);
    }

    /* EDS tests */

    @Test
    public void dumpEdsConfig() {
      EndpointsConfigDump edsConfig = CsdsService.dumpEdsConfig(RESOURCES_METADATA);
      assertThat(edsConfig.getStaticEndpointConfigsCount()).isEqualTo(0);
      assertThat(edsConfig.getDynamicEndpointConfigsCount()).isEqualTo(2);
      // Minimal check to confirm that resources generated from corresponding metadata.
      assertThat(edsConfig.getDynamicEndpointConfigs(0).getClientStatus())
          .isEqualTo(ClientResourceStatus.UNKNOWN);
      assertThat(edsConfig.getDynamicEndpointConfigs(1).getClientStatus())
          .isEqualTo(ClientResourceStatus.REQUESTED);
    }

    @Test
    public void buildDynamicEndpointConfig_metadataUnknown() {
      buildDynamicEndpointConfigNoData(
          CsdsService.buildDynamicEndpointConfig(METADATA_UNKNOWN),
          ClientResourceStatus.UNKNOWN);
    }

    @Test
    public void buildDynamicEndpointConfig_metadataDoesNotExist() {
      buildDynamicEndpointConfigNoData(
          CsdsService.buildDynamicEndpointConfig(METADATA_DOES_NOT_EXIST),
          ClientResourceStatus.DOES_NOT_EXIST);
    }

    @Test
    public void buildDynamicEndpointConfig_metadataRequested() {
      buildDynamicEndpointConfigNoData(
          CsdsService.buildDynamicEndpointConfig(METADATA_REQUESTED),
          ClientResourceStatus.REQUESTED);
    }

    @Test
    public void buildDynamicEndpointConfig_metadataAcked() {
      verifyDynamicEndpointConfigAccepted(
          CsdsService.buildDynamicEndpointConfig(METADATA_ACKED_EDS),
          ClientResourceStatus.ACKED);
    }

    @Test
    public void buildDynamicEndpointConfig_metadataNackedFromRequested() {
      ResourceMetadata metadata = ResourceMetadata.newResourceMetadataNacked(
          METADATA_REQUESTED, VERSION_NACK, NANOS_FAILED_UPDATE, ERROR);
      DynamicEndpointConfig dynamicEndpointConfig =
          CsdsService.buildDynamicEndpointConfig(metadata);
      buildDynamicEndpointConfigNoData(dynamicEndpointConfig, ClientResourceStatus.NACKED);
      verifyErrorState(dynamicEndpointConfig.getErrorState());
    }

    @Test
    public void buildDynamicEndpointConfig_metadataNackedFromAcked() {
      ResourceMetadata metadata = ResourceMetadata.newResourceMetadataNacked(
          METADATA_ACKED_EDS, VERSION_NACK, NANOS_FAILED_UPDATE, ERROR);
      DynamicEndpointConfig dynamicEndpointConfig =
          CsdsService.buildDynamicEndpointConfig(metadata);
      verifyDynamicEndpointConfigAccepted(dynamicEndpointConfig, ClientResourceStatus.NACKED);
      verifyErrorState(dynamicEndpointConfig.getErrorState());
    }

    private void buildDynamicEndpointConfigNoData(
        DynamicEndpointConfig dynamicEndpointConfig, ClientResourceStatus status) {
      assertWithMessage("version_info").that(dynamicEndpointConfig.getVersionInfo()).isEmpty();
      assertWithMessage("route_config").that(dynamicEndpointConfig.hasEndpointConfig()).isFalse();
      assertWithMessage("last_updated").that(dynamicEndpointConfig.getLastUpdated())
          .isEqualTo(TIMESTAMP_ZERO);
      assertWithMessage("error_state").that(dynamicEndpointConfig.hasErrorState())
          .isEqualTo(status.equals(ClientResourceStatus.NACKED));
      assertWithMessage("client_status").that(dynamicEndpointConfig.getClientStatus())
          .isEqualTo(status);
    }

    private void verifyDynamicEndpointConfigAccepted(
        DynamicEndpointConfig dynamicEndpointConfig, ClientResourceStatus status) {
      assertWithMessage("version_info").that(dynamicEndpointConfig.getVersionInfo())
          .isEqualTo(VERSION_ACK);
      assertWithMessage("route_config").that(dynamicEndpointConfig.hasEndpointConfig()).isTrue();
      assertWithMessage("route_config").that(dynamicEndpointConfig.getEndpointConfig())
          .isEqualTo(RAW_CLUSTER_LOAD_ASSIGNMENT);
      assertWithMessage("last_updated").that(dynamicEndpointConfig.getLastUpdated())
          .isEqualTo(TIMESTAMP_LAST_UPDATE);
      assertWithMessage("error_state").that(dynamicEndpointConfig.hasErrorState())
          .isEqualTo(status.equals(ClientResourceStatus.NACKED));
      assertWithMessage("client_status").that(dynamicEndpointConfig.getClientStatus())
          .isEqualTo(status);
    }

    /* Common methods. */

    @Test
    public void metadataStatusToClientStatus() {
      assertThat(CsdsService.metadataStatusToClientStatus(ResourceMetadataStatus.UNKNOWN))
          .isEqualTo(ClientResourceStatus.UNKNOWN);
      assertThat(CsdsService.metadataStatusToClientStatus(ResourceMetadataStatus.DOES_NOT_EXIST))
          .isEqualTo(ClientResourceStatus.DOES_NOT_EXIST);
      assertThat(CsdsService.metadataStatusToClientStatus(ResourceMetadataStatus.REQUESTED))
          .isEqualTo(ClientResourceStatus.REQUESTED);
      assertThat(CsdsService.metadataStatusToClientStatus(ResourceMetadataStatus.ACKED))
          .isEqualTo(ClientResourceStatus.ACKED);
      assertThat(CsdsService.metadataStatusToClientStatus(ResourceMetadataStatus.NACKED))
          .isEqualTo(ClientResourceStatus.NACKED);
    }

    @Test
    public void getClientConfigForXdsClient_subscribedResourcesToPerXdsConfig() {
      ClientConfig clientConfig = CsdsService.getClientConfigForXdsClient(new XdsClient() {
        @Override
        Bootstrapper.BootstrapInfo getBootstrapInfo() {
          return Bootstrapper.BootstrapInfo.builder()
              .servers(Arrays.asList(
                  Bootstrapper.ServerInfo.create(
                          SERVER_URI, InsecureChannelCredentials.create(), false)))
              .node(BOOTSTRAP_NODE)
              .build();
        }

        @Override
        String getCurrentVersion(ResourceType type) {
          return "getCurrentVersion." + type.name();
        }

        @Override
        Map<String, ResourceMetadata> getSubscribedResourcesMetadata(ResourceType type) {
          switch (type) {
            case LDS:
              return ImmutableMap.of("subscribedResourceName." + type.name(), METADATA_ACKED_LDS);
            case RDS:
              return ImmutableMap.of("subscribedResourceName." + type.name(), METADATA_ACKED_RDS);
            case CDS:
              return ImmutableMap.of("subscribedResourceName." + type.name(), METADATA_ACKED_CDS);
            case EDS:
              return ImmutableMap.of("subscribedResourceName." + type.name(), METADATA_ACKED_EDS);
            case UNKNOWN:
            default:
              throw new AssertionError("Unexpected resource name");
          }
        }
      });

      verifyClientConfigNode(clientConfig);

      // Minimal verification to confirm that the data/metadata XdsClient provides,
      // is propagated to the correct resource types.
      @SuppressWarnings("deprecation")
      int xdsConfigCount = clientConfig.getXdsConfigCount();
      assertThat(xdsConfigCount).isEqualTo(4);
      EnumMap<ResourceType, PerXdsConfig> configDumps = mapConfigDumps(clientConfig);
      assertThat(configDumps.keySet()).containsExactly(LDS, RDS, CDS, EDS);

      // LDS.
      // Both the version provided by XdsClient.getCurrentVersion(),
      // and the resource name provided by XdsClient.getSubscribedResourcesMetadata() are used.
      PerXdsConfig perXdsConfigLds = configDumps.get(LDS);
      verifyPerXdsConfigEmptyFields(perXdsConfigLds);
      ListenersConfigDump listenerConfig = perXdsConfigLds.getListenerConfig();
      assertThat(listenerConfig.getVersionInfo()).isEqualTo("getCurrentVersion.LDS");
      assertThat(listenerConfig.getDynamicListenersCount()).isEqualTo(1);
      DynamicListener dynamicListener = listenerConfig.getDynamicListeners(0);
      assertThat(dynamicListener.getName()).isEqualTo("subscribedResourceName.LDS");
      assertThat(dynamicListener.getClientStatus()).isEqualTo(ClientResourceStatus.ACKED);
      assertThat(dynamicListener.getActiveState().getVersionInfo()).isEqualTo(VERSION_ACK);

      // RDS.
      // Neither the version provided by XdsClient.getCurrentVersion(),
      // nor the resource name provided by XdsClient.getSubscribedResourcesMetadata() are used.
      PerXdsConfig perXdsConfigRds = configDumps.get(RDS);
      verifyPerXdsConfigEmptyFields(perXdsConfigRds);
      RoutesConfigDump routeConfig = perXdsConfigRds.getRouteConfig();
      assertThat(routeConfig.getDynamicRouteConfigsCount()).isEqualTo(1);
      DynamicRouteConfig dynamicRouteConfig = routeConfig.getDynamicRouteConfigs(0);
      assertThat(dynamicRouteConfig.getClientStatus()).isEqualTo(ClientResourceStatus.ACKED);
      assertThat(dynamicRouteConfig.getVersionInfo()).isEqualTo(VERSION_ACK);

      // CDS.
      // Only the version provided by XdsClient.getCurrentVersion() is used,
      // the resource name provided by XdsClient.getSubscribedResourcesMetadata() is ignored.
      PerXdsConfig perXdsConfigCds = configDumps.get(CDS);
      verifyPerXdsConfigEmptyFields(perXdsConfigRds);
      ClustersConfigDump clusterConfig = perXdsConfigCds.getClusterConfig();
      assertThat(clusterConfig.getVersionInfo()).isEqualTo("getCurrentVersion.CDS");
      assertThat(clusterConfig.getDynamicActiveClustersCount()).isEqualTo(1);
      DynamicCluster dynamicCluster = clusterConfig.getDynamicActiveClusters(0);
      assertThat(dynamicCluster.getClientStatus()).isEqualTo(ClientResourceStatus.ACKED);
      assertThat(dynamicCluster.getVersionInfo()).isEqualTo(VERSION_ACK);

      // RDS.
      // Neither the version provided by XdsClient.getCurrentVersion(),
      // nor the resource name provided by XdsClient.getSubscribedResourcesMetadata() are used.
      PerXdsConfig perXdsConfigEds = configDumps.get(EDS);
      verifyPerXdsConfigEmptyFields(perXdsConfigEds);
      EndpointsConfigDump endpointConfig = perXdsConfigEds.getEndpointConfig();
      assertThat(endpointConfig.getDynamicEndpointConfigsCount()).isEqualTo(1);
      DynamicEndpointConfig dynamicEndpointConfig = endpointConfig.getDynamicEndpointConfigs(0);
      assertThat(dynamicEndpointConfig.getClientStatus()).isEqualTo(ClientResourceStatus.ACKED);
      assertThat(dynamicEndpointConfig.getVersionInfo()).isEqualTo(VERSION_ACK);
    }

    @Test
    public void getClientConfigForXdsClient_noSubscribedResources() {
      ClientConfig clientConfig = CsdsService.getClientConfigForXdsClient(XDS_CLIENT_NO_RESOURCES);
      verifyClientConfigNode(clientConfig);
      verifyClientConfigNoResources(clientConfig);
    }

    private void verifyErrorState(UpdateFailureState errorState) {
      // failed_configuration currently not supported.
      assertWithMessage("failed_configuration").that(errorState.hasFailedConfiguration()).isFalse();
      assertWithMessage("last_update_attempt").that(errorState.getLastUpdateAttempt())
          .isEqualTo(TIMESTAMP_FAILED_UPDATE);
      assertWithMessage("details").that(errorState.getDetails()).isEqualTo(ERROR);
      assertWithMessage("version_info").that(errorState.getVersionInfo()).isEqualTo(VERSION_NACK);
    }
  }

  /**
   * Assuming {@link MetadataToProtoTests} passes, and metadata converted to corresponding
   * config dumps correctly, perform a minimal verification of the general shape of ClientConfig.
   */
  private static void verifyClientConfigNoResources(ClientConfig clientConfig) {
    // Expect PerXdsConfig for all resource types to be present, but empty.
    @SuppressWarnings("deprecation")
    int xdsConfigCount = clientConfig.getXdsConfigCount();
    assertThat(xdsConfigCount).isEqualTo(4);
    EnumMap<ResourceType, PerXdsConfig> configDumps = mapConfigDumps(clientConfig);
    assertThat(configDumps.keySet()).containsExactly(LDS, RDS, CDS, EDS);

    ListenersConfigDump listenerConfig = configDumps.get(LDS).getListenerConfig();
    assertThat(listenerConfig.getVersionInfo()).isEqualTo("getCurrentVersion.LDS");
    assertThat(listenerConfig.getDynamicListenersCount()).isEqualTo(0);

    RoutesConfigDump routeConfig = configDumps.get(RDS).getRouteConfig();
    assertThat(routeConfig.getDynamicRouteConfigsCount()).isEqualTo(0);

    ClustersConfigDump clusterConfig = configDumps.get(CDS).getClusterConfig();
    assertThat(clusterConfig.getVersionInfo()).isEqualTo("getCurrentVersion.CDS");
    assertThat(clusterConfig.getDynamicActiveClustersCount()).isEqualTo(0);

    EndpointsConfigDump endpointConfig = configDumps.get(EDS).getEndpointConfig();
    assertThat(endpointConfig.getDynamicEndpointConfigsCount()).isEqualTo(0);
  }

  /**
   * Assuming {@link io.grpc.xds.EnvoyProtoDataTest#convertNode} passes, perform a minimal check,
   * just verify the node itself is the one we expect.
   */
  private static void verifyClientConfigNode(ClientConfig clientConfig) {
    Node node = clientConfig.getNode();
    assertThat(node.getId()).isEqualTo(NODE_ID);
    assertThat(node).isEqualTo(BOOTSTRAP_NODE.toEnvoyProtoNode());
  }

  /** Verify PerXdsConfig fields that are expected to be omitted. */
  private static void verifyPerXdsConfigEmptyFields(PerXdsConfig perXdsConfig) {
    assertThat(perXdsConfig.getStatusValue()).isEqualTo(0);
    @SuppressWarnings("deprecation")
    int clientStatusValue = perXdsConfig.getClientStatusValue();
    assertThat(clientStatusValue).isEqualTo(0);
  }

  private static EnumMap<ResourceType, PerXdsConfig> mapConfigDumps(ClientConfig config) {
    EnumMap<ResourceType, PerXdsConfig> xdsConfigMap = new EnumMap<>(ResourceType.class);
    @SuppressWarnings("deprecation")
    List<PerXdsConfig> xdsConfigList = config.getXdsConfigList();
    for (PerXdsConfig perXdsConfig : xdsConfigList) {
      ResourceType type = perXdsConfigToResourceType(perXdsConfig);
      assertThat(type).isNotEqualTo(ResourceType.UNKNOWN);
      assertThat(xdsConfigMap).doesNotContainKey(type);
      xdsConfigMap.put(type, perXdsConfig);
    }
    return xdsConfigMap;
  }

  private static ResourceType perXdsConfigToResourceType(PerXdsConfig perXdsConfig) {
    switch (perXdsConfig.getPerXdsConfigCase()) {
      case LISTENER_CONFIG:
        return LDS;
      case CLUSTER_CONFIG:
        return CDS;
      case ROUTE_CONFIG:
        return RDS;
      case ENDPOINT_CONFIG:
        return EDS;
      default:
        return ResourceType.UNKNOWN;
    }
  }

  private static class FakeXdsClientPoolFactory implements XdsClientPoolFactory {
    @Nullable private final XdsClient xdsClient;

    private FakeXdsClientPoolFactory(@Nullable XdsClient xdsClient) {
      this.xdsClient = xdsClient;
    }

    @Override
    @Nullable
    public ObjectPool<XdsClient> get() {
      return new ObjectPool<XdsClient>() {
        @Override
        public XdsClient getObject() {
          return xdsClient;
        }

        @Override
        public XdsClient returnObject(Object object) {
          return null;
        }
      };
    }

    @Override
    public void setBootstrapOverride(Map<String, ?> bootstrap) {
      throw new UnsupportedOperationException("Should not be called");
    }

    @Override
    public ObjectPool<XdsClient> getOrCreate() throws XdsInitializationException {
      throw new UnsupportedOperationException("Should not be called");
    }
  }
}
