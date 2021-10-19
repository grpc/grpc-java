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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.util.Timestamps;
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
import io.envoyproxy.envoy.service.status.v3.ClientConfig;
import io.envoyproxy.envoy.service.status.v3.ClientStatusDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.status.v3.ClientStatusRequest;
import io.envoyproxy.envoy.service.status.v3.ClientStatusResponse;
import io.envoyproxy.envoy.service.status.v3.PerXdsConfig;
import io.grpc.ExperimentalApi;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.internal.ObjectPool;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.AbstractXdsClient.ResourceType;
import io.grpc.xds.XdsClient.ResourceMetadata;
import io.grpc.xds.XdsClient.ResourceMetadata.ResourceMetadataStatus;
import io.grpc.xds.XdsClient.ResourceMetadata.UpdateFailureState;
import io.grpc.xds.XdsNameResolverProvider.XdsClientPoolFactory;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The CSDS service provides information about the status of a running xDS client.
 *
 * <p><a href="https://github.com/envoyproxy/envoy/blob/main/api/envoy/service/status/v3/csds.proto">
 * Client Status Discovery Service</a> is a service that exposes xDS config of a given client. See
 * the full design at <a href="https://github.com/grpc/proposal/blob/master/A40-csds-support.md">
 * gRFC A40: xDS Configuration Dump via Client Status Discovery Service in gRPC</a>.
 *
 * @since 1.37.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8016")
public final class CsdsService extends
    ClientStatusDiscoveryServiceGrpc.ClientStatusDiscoveryServiceImplBase {
  private static final Logger logger = Logger.getLogger(CsdsService.class.getName());
  private final XdsClientPoolFactory xdsClientPoolFactory;

  @VisibleForTesting
  CsdsService(XdsClientPoolFactory xdsClientPoolFactory) {
    this.xdsClientPoolFactory = checkNotNull(xdsClientPoolFactory, "xdsClientPoolProvider");
  }

  private CsdsService() {
    this(SharedXdsClientPoolProvider.getDefaultProvider());
  }

  /** Creates an instance. */
  public static CsdsService newInstance() {
    return new CsdsService();
  }

  @Override
  public void fetchClientStatus(
      ClientStatusRequest request, StreamObserver<ClientStatusResponse> responseObserver) {
    if (handleRequest(request, responseObserver)) {
      responseObserver.onCompleted();
    }
  }

  @Override
  public StreamObserver<ClientStatusRequest> streamClientStatus(
      final StreamObserver<ClientStatusResponse> responseObserver) {
    return new StreamObserver<ClientStatusRequest>() {
      @Override
      public void onNext(ClientStatusRequest request) {
        handleRequest(request, responseObserver);
      }

      @Override
      public void onError(Throwable t) {
        onCompleted();
      }

      @Override
      public void onCompleted() {
        responseObserver.onCompleted();
      }
    };
  }

  private boolean handleRequest(
      ClientStatusRequest request, StreamObserver<ClientStatusResponse> responseObserver) {
    try {
      responseObserver.onNext(getConfigDumpForRequest(request));
      return true;
    } catch (StatusException e) {
      responseObserver.onError(e);
    } catch (Exception e) {
      logger.log(Level.WARNING, "Unexpected error while building CSDS config dump", e);
      responseObserver.onError(new StatusException(
          Status.INTERNAL.withDescription("Unexpected internal error").withCause(e)));
    }
    return false;
  }

  private ClientStatusResponse getConfigDumpForRequest(ClientStatusRequest request)
      throws StatusException {
    if (request.getNodeMatchersCount() > 0) {
      throw new StatusException(
          Status.INVALID_ARGUMENT.withDescription("node_matchers not supported"));
    }

    ObjectPool<XdsClient> xdsClientPool = xdsClientPoolFactory.get();
    if (xdsClientPool == null) {
      return ClientStatusResponse.getDefaultInstance();
    }

    XdsClient xdsClient = null;
    try {
      xdsClient = xdsClientPool.getObject();
      return ClientStatusResponse.newBuilder()
          .addConfig(getClientConfigForXdsClient(xdsClient))
          .build();
    } finally {
      if (xdsClient != null) {
        xdsClientPool.returnObject(xdsClient);
      }
    }
  }

  @VisibleForTesting
  static ClientConfig getClientConfigForXdsClient(XdsClient xdsClient) {
    ListenersConfigDump ldsConfig = dumpLdsConfig(
        xdsClient.getSubscribedResourcesMetadata(ResourceType.LDS),
        xdsClient.getCurrentVersion(ResourceType.LDS));
    RoutesConfigDump rdsConfig = dumpRdsConfig(
        xdsClient.getSubscribedResourcesMetadata(ResourceType.RDS));
    ClustersConfigDump cdsConfig = dumpCdsConfig(
        xdsClient.getSubscribedResourcesMetadata(ResourceType.CDS),
        xdsClient.getCurrentVersion(ResourceType.CDS));
    EndpointsConfigDump edsConfig = dumpEdsConfig(
        xdsClient.getSubscribedResourcesMetadata(ResourceType.EDS));

    return ClientConfig.newBuilder()
        .setNode(xdsClient.getBootstrapInfo().node().toEnvoyProtoNode())
        .addXdsConfig(PerXdsConfig.newBuilder().setListenerConfig(ldsConfig))
        .addXdsConfig(PerXdsConfig.newBuilder().setRouteConfig(rdsConfig))
        .addXdsConfig(PerXdsConfig.newBuilder().setClusterConfig(cdsConfig))
        .addXdsConfig(PerXdsConfig.newBuilder().setEndpointConfig(edsConfig))
        .build();
  }

  @VisibleForTesting
  static ListenersConfigDump dumpLdsConfig(
      Map<String, ResourceMetadata> resourcesMetadata, String version) {
    ListenersConfigDump.Builder ldsConfig = ListenersConfigDump.newBuilder();
    for (Map.Entry<String, ResourceMetadata> entry : resourcesMetadata.entrySet()) {
      ldsConfig.addDynamicListeners(buildDynamicListener(entry.getKey(), entry.getValue()));
    }
    return ldsConfig.setVersionInfo(version).build();
  }

  @VisibleForTesting
  static DynamicListener buildDynamicListener(String name, ResourceMetadata metadata) {
    DynamicListener.Builder dynamicListener = DynamicListener.newBuilder()
        .setName(name)
        .setClientStatus(metadataStatusToClientStatus(metadata.getStatus()));
    if (metadata.getErrorState() != null) {
      dynamicListener.setErrorState(metadataUpdateFailureStateToProto(metadata.getErrorState()));
    }
    DynamicListenerState.Builder dynamicListenerState = DynamicListenerState.newBuilder()
        .setVersionInfo(metadata.getVersion())
        .setLastUpdated(Timestamps.fromNanos(metadata.getUpdateTimeNanos()));
    if (metadata.getRawResource() != null) {
      dynamicListenerState.setListener(metadata.getRawResource());
    }
    return dynamicListener.setActiveState(dynamicListenerState).build();
  }

  @VisibleForTesting
  static RoutesConfigDump dumpRdsConfig(Map<String, ResourceMetadata> resourcesMetadata) {
    RoutesConfigDump.Builder rdsConfig = RoutesConfigDump.newBuilder();
    for (ResourceMetadata metadata : resourcesMetadata.values()) {
      rdsConfig.addDynamicRouteConfigs(buildDynamicRouteConfig(metadata));
    }
    return rdsConfig.build();
  }

  @VisibleForTesting
  static DynamicRouteConfig buildDynamicRouteConfig(ResourceMetadata metadata) {
    DynamicRouteConfig.Builder dynamicRouteConfig = DynamicRouteConfig.newBuilder()
        .setVersionInfo(metadata.getVersion())
        .setClientStatus(metadataStatusToClientStatus(metadata.getStatus()))
        .setLastUpdated(Timestamps.fromNanos(metadata.getUpdateTimeNanos()));
    if (metadata.getErrorState() != null) {
      dynamicRouteConfig.setErrorState(metadataUpdateFailureStateToProto(metadata.getErrorState()));
    }
    if (metadata.getRawResource() != null) {
      dynamicRouteConfig.setRouteConfig(metadata.getRawResource());
    }
    return dynamicRouteConfig.build();
  }

  @VisibleForTesting
  static ClustersConfigDump dumpCdsConfig(
      Map<String, ResourceMetadata> resourcesMetadata, String version) {
    ClustersConfigDump.Builder cdsConfig = ClustersConfigDump.newBuilder();
    for (ResourceMetadata metadata : resourcesMetadata.values()) {
      cdsConfig.addDynamicActiveClusters(buildDynamicCluster(metadata));
    }
    return cdsConfig.setVersionInfo(version).build();
  }

  @VisibleForTesting
  static DynamicCluster buildDynamicCluster(ResourceMetadata metadata) {
    DynamicCluster.Builder dynamicCluster = DynamicCluster.newBuilder()
        .setVersionInfo(metadata.getVersion())
        .setClientStatus(metadataStatusToClientStatus(metadata.getStatus()))
        .setLastUpdated(Timestamps.fromNanos(metadata.getUpdateTimeNanos()));
    if (metadata.getErrorState() != null) {
      dynamicCluster.setErrorState(metadataUpdateFailureStateToProto(metadata.getErrorState()));
    }
    if (metadata.getRawResource() != null) {
      dynamicCluster.setCluster(metadata.getRawResource());
    }
    return dynamicCluster.build();
  }

  @VisibleForTesting
  static EndpointsConfigDump dumpEdsConfig(Map<String, ResourceMetadata> resourcesMetadata) {
    EndpointsConfigDump.Builder edsConfig = EndpointsConfigDump.newBuilder();
    for (ResourceMetadata metadata : resourcesMetadata.values()) {
      edsConfig.addDynamicEndpointConfigs(buildDynamicEndpointConfig(metadata));
    }
    return edsConfig.build();
  }

  @VisibleForTesting
  static DynamicEndpointConfig buildDynamicEndpointConfig(ResourceMetadata metadata) {
    DynamicEndpointConfig.Builder dynamicRouteConfig = DynamicEndpointConfig.newBuilder()
        .setVersionInfo(metadata.getVersion())
        .setClientStatus(metadataStatusToClientStatus(metadata.getStatus()))
        .setLastUpdated(Timestamps.fromNanos(metadata.getUpdateTimeNanos()));
    if (metadata.getErrorState() != null) {
      dynamicRouteConfig.setErrorState(metadataUpdateFailureStateToProto(metadata.getErrorState()));
    }
    if (metadata.getRawResource() != null) {
      dynamicRouteConfig.setEndpointConfig(metadata.getRawResource());
    }
    return dynamicRouteConfig.build();
  }

  @VisibleForTesting
  static ClientResourceStatus metadataStatusToClientStatus(ResourceMetadataStatus status) {
    switch (status) {
      case UNKNOWN:
        return ClientResourceStatus.UNKNOWN;
      case DOES_NOT_EXIST:
        return ClientResourceStatus.DOES_NOT_EXIST;
      case REQUESTED:
        return ClientResourceStatus.REQUESTED;
      case ACKED:
        return ClientResourceStatus.ACKED;
      case NACKED:
        return ClientResourceStatus.NACKED;
      default:
        throw new AssertionError("Unexpected ResourceMetadataStatus: " + status);
    }
  }

  private static io.envoyproxy.envoy.admin.v3.UpdateFailureState metadataUpdateFailureStateToProto(
      UpdateFailureState errorState) {
    return io.envoyproxy.envoy.admin.v3.UpdateFailureState.newBuilder()
        .setLastUpdateAttempt(Timestamps.fromNanos(errorState.getFailedUpdateTimeNanos()))
        .setDetails(errorState.getFailedDetails())
        .setVersionInfo(errorState.getFailedVersion())
        .build();
  }
}
