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
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Any;
import io.envoyproxy.envoy.admin.v3.ClientResourceStatus;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.service.status.v3.ClientConfig;
import io.envoyproxy.envoy.service.status.v3.ClientConfig.GenericXdsConfig;
import io.envoyproxy.envoy.service.status.v3.ClientStatusDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.status.v3.ClientStatusRequest;
import io.envoyproxy.envoy.service.status.v3.ClientStatusResponse;
import io.envoyproxy.envoy.type.matcher.v3.NodeMatcher;
import io.grpc.InsecureChannelCredentials;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.testing.StreamRecorder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcServerRule;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.XdsClient.ResourceMetadata;
import io.grpc.xds.XdsClient.ResourceMetadata.ResourceMetadataStatus;
import io.grpc.xds.XdsNameResolverProvider.XdsClientPoolFactory;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
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
  private static final BootstrapInfo BOOTSTRAP_INFO = BootstrapInfo.builder()
      .servers(ImmutableList.of(
          ServerInfo.create(SERVER_URI, InsecureChannelCredentials.create())))
      .node(BOOTSTRAP_NODE)
      .build();
  private static final FakeXdsClient XDS_CLIENT_NO_RESOURCES = new FakeXdsClient();
  private static final XdsResourceType<?> LDS = XdsListenerResource.getInstance();
  private static final XdsResourceType<?> CDS = XdsClusterResource.getInstance();
  private static final XdsResourceType<?> RDS = XdsRouteConfigureResource.getInstance();
  private static final XdsResourceType<?> EDS = XdsEndpointResource.getInstance();

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

    /** Unexpected exceptions translated to Status.Code.INTERNAL. */
    @Test
    public void fetchClientConfig_unexpectedException() {
      XdsClient throwingXdsClient = new FakeXdsClient() {
        @Override
        ListenableFuture<Map<XdsResourceType<?>, Map<String, ResourceMetadata>>>
              getSubscribedResourcesMetadataSnapshot() {
          return Futures.immediateFailedFuture(
              new IllegalArgumentException("IllegalArgumentException"));
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
        // Cause is not propagated.
      }
    }

    /** Interrupted exceptions translated to Status.Code.ABORTED. */
    @Test
    public void fetchClientConfig_interruptedException() {
      XdsClient throwingXdsClient = new FakeXdsClient() {
        @Override
        ListenableFuture<Map<XdsResourceType<?>, Map<String, ResourceMetadata>>>
            getSubscribedResourcesMetadataSnapshot() {
          return Futures.submit(
              new Callable<Map<XdsResourceType<?>, Map<String, ResourceMetadata>>>() {
                @Override
                public Map<XdsResourceType<?>, Map<String, ResourceMetadata>> call() {
                  Thread.currentThread().interrupt();
                  return null;
                }
              }, MoreExecutors.directExecutor());
        }
      };
      grpcServerRule.getServiceRegistry()
          .addService(new CsdsService(new FakeXdsClientPoolFactory(throwingXdsClient)));

      try {
        ClientStatusResponse response = csdsStub.fetchClientStatus(REQUEST);
        fail("Should've failed, got response: " + response);
      } catch (StatusRuntimeException e) {
        assertThat(e.getStatus().getCode()).isEqualTo(Code.ABORTED);
        assertThat(e.getStatus().getDescription()).isEqualTo("Thread interrupted");
        // Clean the test thread interrupt.
        assertThat(Thread.interrupted()).isEqualTo(true);
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
      verifyClientConfigNoResources(XDS_CLIENT_NO_RESOURCES, clientConfig);
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
    private static final String VERSION_ACK_LDS = "42";
    private static final String VERSION_ACK_RDS = "38";
    private static final String VERSION_ACK_CDS = "51";
    private static final String VERSION_ACK_EDS = "29";
    private static final long NANOS_LAST_UPDATE = 1577923199_606042047L;
    // Raw resources.
    private static final Any RAW_LISTENER =
        Any.pack(Listener.newBuilder().setName(LDS_RESOURCE).build());
    private static final Any RAW_ROUTE_CONFIGURATION =
        Any.pack(RouteConfiguration.newBuilder().setName(RDS_RESOURCE).build());
    private static final Any RAW_CLUSTER =
        Any.pack(Cluster.newBuilder().setName(CDS_RESOURCE).build());
    private static final Any RAW_CLUSTER_LOAD_ASSIGNMENT =
        Any.pack(ClusterLoadAssignment.newBuilder().setClusterName(EDS_RESOURCE).build());

    // Test metadata: resource acknowledged state, per resource type.
    private static final ResourceMetadata METADATA_ACKED_LDS = ResourceMetadata
        .newResourceMetadataAcked(RAW_LISTENER, VERSION_ACK_LDS, NANOS_LAST_UPDATE);
    private static final ResourceMetadata METADATA_ACKED_RDS = ResourceMetadata
        .newResourceMetadataAcked(RAW_ROUTE_CONFIGURATION, VERSION_ACK_RDS, NANOS_LAST_UPDATE);
    private static final ResourceMetadata METADATA_ACKED_CDS = ResourceMetadata
        .newResourceMetadataAcked(RAW_CLUSTER, VERSION_ACK_CDS, NANOS_LAST_UPDATE);
    private static final ResourceMetadata METADATA_ACKED_EDS = ResourceMetadata
        .newResourceMetadataAcked(RAW_CLUSTER_LOAD_ASSIGNMENT, VERSION_ACK_EDS, NANOS_LAST_UPDATE);

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
    public void getClientConfigForXdsClient_subscribedResourcesToGenericXdsConfig()
        throws InterruptedException {
      FakeXdsClient fakeXdsClient = new FakeXdsClient() {
        @Override
        protected Map<XdsResourceType<?>, Map<String, ResourceMetadata>>
            getSubscribedResourcesMetadata() {
          return new ImmutableMap.Builder<XdsResourceType<?>, Map<String, ResourceMetadata>>()
              .put(LDS, ImmutableMap.of("subscribedResourceName.LDS", METADATA_ACKED_LDS))
              .put(RDS, ImmutableMap.of("subscribedResourceName.RDS", METADATA_ACKED_RDS))
              .put(CDS, ImmutableMap.of("subscribedResourceName.CDS", METADATA_ACKED_CDS))
              .put(EDS, ImmutableMap.of("subscribedResourceName.EDS", METADATA_ACKED_EDS))
              .buildOrThrow();
        }

        @Override
        public Map<String, XdsResourceType<?>> getSubscribedResourceTypesWithTypeUrl() {
          return ImmutableMap.of(
              LDS.typeUrl(), LDS,
              RDS.typeUrl(), RDS,
              CDS.typeUrl(), CDS,
              EDS.typeUrl(), EDS
          );
        }
      };
      ClientConfig clientConfig = CsdsService.getClientConfigForXdsClient(fakeXdsClient);

      verifyClientConfigNode(clientConfig);

      // Minimal verification to confirm that the data/metadata XdsClient provides,
      // is propagated to the correct resource types.
      int xdsConfigCount = clientConfig.getGenericXdsConfigsCount();
      assertThat(xdsConfigCount).isEqualTo(4);
      Map<XdsResourceType<?>, GenericXdsConfig> configDumps = mapConfigDumps(fakeXdsClient,
          clientConfig);
      assertThat(configDumps.keySet()).containsExactly(LDS, RDS, CDS, EDS);

      // LDS.
      GenericXdsConfig genericXdsConfigLds = configDumps.get(LDS);
      assertThat(genericXdsConfigLds.getName()).isEqualTo("subscribedResourceName.LDS");
      assertThat(genericXdsConfigLds.getClientStatus()).isEqualTo(ClientResourceStatus.ACKED);
      assertThat(genericXdsConfigLds.getVersionInfo()).isEqualTo(VERSION_ACK_LDS);
      assertThat(genericXdsConfigLds.getXdsConfig()).isEqualTo(RAW_LISTENER);

      // RDS.
      GenericXdsConfig genericXdsConfigRds = configDumps.get(RDS);
      assertThat(genericXdsConfigRds.getClientStatus()).isEqualTo(ClientResourceStatus.ACKED);
      assertThat(genericXdsConfigRds.getVersionInfo()).isEqualTo(VERSION_ACK_RDS);
      assertThat(genericXdsConfigRds.getXdsConfig()).isEqualTo(RAW_ROUTE_CONFIGURATION);

      // CDS.
      GenericXdsConfig genericXdsConfigCds = configDumps.get(CDS);
      assertThat(genericXdsConfigCds.getClientStatus()).isEqualTo(ClientResourceStatus.ACKED);
      assertThat(genericXdsConfigCds.getVersionInfo()).isEqualTo(VERSION_ACK_CDS);
      assertThat(genericXdsConfigCds.getXdsConfig()).isEqualTo(RAW_CLUSTER);

      // RDS.
      GenericXdsConfig genericXdsConfigEds = configDumps.get(EDS);
      assertThat(genericXdsConfigEds.getClientStatus()).isEqualTo(ClientResourceStatus.ACKED);
      assertThat(genericXdsConfigEds.getVersionInfo()).isEqualTo(VERSION_ACK_EDS);
      assertThat(genericXdsConfigEds.getXdsConfig()).isEqualTo(RAW_CLUSTER_LOAD_ASSIGNMENT);
    }

    @Test
    public void getClientConfigForXdsClient_noSubscribedResources() throws InterruptedException {
      ClientConfig clientConfig = CsdsService.getClientConfigForXdsClient(XDS_CLIENT_NO_RESOURCES);
      verifyClientConfigNode(clientConfig);
      verifyClientConfigNoResources(XDS_CLIENT_NO_RESOURCES, clientConfig);
    }
  }

  /**
   * Assuming {@link MetadataToProtoTests} passes, and metadata converted to corresponding
   * config dumps correctly, perform a minimal verification of the general shape of ClientConfig.
   */
  private static void verifyClientConfigNoResources(FakeXdsClient xdsClient,
                                                    ClientConfig clientConfig) {
    int xdsConfigCount = clientConfig.getGenericXdsConfigsCount();
    assertThat(xdsConfigCount).isEqualTo(0);
    Map<XdsResourceType<?>, GenericXdsConfig> configDumps = mapConfigDumps(xdsClient, clientConfig);
    assertThat(configDumps).isEmpty();
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

  private static Map<XdsResourceType<?>, GenericXdsConfig> mapConfigDumps(FakeXdsClient client,
                                                                          ClientConfig config) {
    Map<XdsResourceType<?>, GenericXdsConfig> xdsConfigMap = new HashMap<>();
    List<GenericXdsConfig> xdsConfigList = config.getGenericXdsConfigsList();
    for (GenericXdsConfig genericXdsConfig : xdsConfigList) {
      XdsResourceType<?> type = client.getSubscribedResourceTypesWithTypeUrl()
          .get(genericXdsConfig.getTypeUrl());
      assertThat(type).isNotNull();
      assertThat(xdsConfigMap).doesNotContainKey(type);
      xdsConfigMap.put(type, genericXdsConfig);
    }
    return xdsConfigMap;
  }

  private static class FakeXdsClient extends XdsClient implements XdsClient.ResourceStore {
    protected Map<XdsResourceType<?>, Map<String, ResourceMetadata>>
        getSubscribedResourcesMetadata() {
      return ImmutableMap.of();
    }

    @Override
    ListenableFuture<Map<XdsResourceType<?>, Map<String, ResourceMetadata>>>
        getSubscribedResourcesMetadataSnapshot() {
      return Futures.immediateFuture(getSubscribedResourcesMetadata());
    }

    @Override
    BootstrapInfo getBootstrapInfo() {
      return BOOTSTRAP_INFO;
    }

    @Nullable
    @Override
    public Collection<String> getSubscribedResources(ServerInfo serverInfo,
                  XdsResourceType<? extends ResourceUpdate> type) {
      return null;
    }

    @Override
    public Map<String, XdsResourceType<?>> getSubscribedResourceTypesWithTypeUrl() {
      return ImmutableMap.of();
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
    public ObjectPool<XdsClient> getOrCreate() {
      throw new UnsupportedOperationException("Should not be called");
    }
  }
}
