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
import static com.google.common.base.Verify.verifyNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.util.Timestamps;
import io.envoyproxy.envoy.admin.v3.ClientResourceStatus;
import io.envoyproxy.envoy.service.status.v3.ClientConfig;
import io.envoyproxy.envoy.service.status.v3.ClientConfig.GenericXdsConfig;
import io.envoyproxy.envoy.service.status.v3.ClientStatusDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.status.v3.ClientStatusRequest;
import io.envoyproxy.envoy.service.status.v3.ClientStatusResponse;
import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.internal.ObjectPool;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsClient.ResourceMetadata;
import io.grpc.xds.client.XdsClient.ResourceMetadata.ResourceMetadataStatus;
import io.grpc.xds.client.XdsClient.ResourceMetadata.UpdateFailureState;
import io.grpc.xds.client.XdsResourceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
public final class CsdsService implements BindableService {
  private static final Logger logger = Logger.getLogger(CsdsService.class.getName());
  private final XdsClientPoolFactory xdsClientPoolFactory;
  private final CsdsServiceInternal delegate = new CsdsServiceInternal();

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
  public ServerServiceDefinition bindService() {
    return delegate.bindService();
  }

  /** Hide protobuf from being exposed via the API. */
  private final class CsdsServiceInternal
      extends ClientStatusDiscoveryServiceGrpc.ClientStatusDiscoveryServiceImplBase {
    @Override
    public void fetchClientStatus(
        ClientStatusRequest request, StreamObserver<ClientStatusResponse> responseObserver) {
      if (handleRequest(request, responseObserver)) {
        responseObserver.onCompleted();
      }
      // TODO(sergiitk): Add a case covering mutating handleRequest return false to true - to verify
      //   that responseObserver.onCompleted() isn't erroneously called on error.
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
  }

  private boolean handleRequest(
      ClientStatusRequest request, StreamObserver<ClientStatusResponse> responseObserver) {
    StatusException error = null;

    if (request.getNodeMatchersCount() > 0) {
      error = new StatusException(
          Status.INVALID_ARGUMENT.withDescription("node_matchers not supported"));
    } else {
      List<String> targets = xdsClientPoolFactory.getTargets();
      List<ClientConfig> clientConfigs = new ArrayList<>(targets.size());

      for (int i = 0; i < targets.size() && error == null; i++) {
        try {
          ClientConfig clientConfig = getConfigForRequest(targets.get(i));
          if (clientConfig != null) {
            clientConfigs.add(clientConfig);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          logger.log(Level.FINE, "Server interrupted while building CSDS config dump", e);
          error = Status.ABORTED.withDescription("Thread interrupted").withCause(e).asException();
        } catch (RuntimeException e) {
          logger.log(Level.WARNING, "Unexpected error while building CSDS config dump", e);
          error = Status.INTERNAL.withDescription("Unexpected internal error").withCause(e)
              .asException();
        }
      }

      try {
        responseObserver.onNext(getStatusResponse(clientConfigs));
      } catch (RuntimeException e) {
        logger.log(Level.WARNING, "Unexpected error while processing CSDS config dump", e);
        error = Status.INTERNAL.withDescription("Unexpected internal error").withCause(e)
            .asException();
      }
    }

    if (error == null) {
      return true; // All clients reported without error
    }
    responseObserver.onError(error);
    return false;
  }

  private ClientConfig getConfigForRequest(String target) throws InterruptedException {
    ObjectPool<XdsClient> xdsClientPool = xdsClientPoolFactory.get(target);
    if (xdsClientPool == null) {
      return null;
    }

    XdsClient xdsClient = null;
    try {
      xdsClient = xdsClientPool.getObject();
      return getClientConfigForXdsClient(xdsClient, target);
    } finally {
      if (xdsClient != null) {
        xdsClientPool.returnObject(xdsClient);
      }
    }
  }

  private ClientStatusResponse getStatusResponse(List<ClientConfig> clientConfigs) {
    if (clientConfigs.isEmpty()) {
      return ClientStatusResponse.getDefaultInstance();
    }
    return ClientStatusResponse.newBuilder().addAllConfig(clientConfigs).build();
  }

  @VisibleForTesting
  static ClientConfig getClientConfigForXdsClient(XdsClient xdsClient, String target)
      throws InterruptedException {
    ClientConfig.Builder builder = ClientConfig.newBuilder()
        .setClientScope(target)
        .setNode(xdsClient.getBootstrapInfo().node().toEnvoyProtoNode());

    Map<XdsResourceType<?>, Map<String, ResourceMetadata>> metadataByType =
        awaitSubscribedResourcesMetadata(xdsClient.getSubscribedResourcesMetadataSnapshot());

    for (Map.Entry<XdsResourceType<?>, Map<String, ResourceMetadata>> metadataByTypeEntry
        : metadataByType.entrySet()) {
      XdsResourceType<?> type = metadataByTypeEntry.getKey();
      Map<String, ResourceMetadata> metadataByResourceName = metadataByTypeEntry.getValue();
      for (Map.Entry<String, ResourceMetadata> metadataEntry : metadataByResourceName.entrySet()) {
        String resourceName = metadataEntry.getKey();
        ResourceMetadata metadata = metadataEntry.getValue();
        GenericXdsConfig.Builder genericXdsConfigBuilder = GenericXdsConfig.newBuilder()
            .setTypeUrl(type.typeUrl())
            .setName(resourceName)
            .setClientStatus(metadataStatusToClientStatus(metadata.getStatus()));
        if (metadata.getRawResource() != null) {
          genericXdsConfigBuilder
              .setVersionInfo(metadata.getVersion())
              .setLastUpdated(Timestamps.fromNanos(metadata.getUpdateTimeNanos()))
              .setXdsConfig(metadata.getRawResource());
        }
        if (metadata.getStatus() == ResourceMetadataStatus.NACKED) {
          verifyNotNull(metadata.getErrorState(), "resource %s getErrorState", resourceName);
          genericXdsConfigBuilder
              .setErrorState(metadataUpdateFailureStateToProto(metadata.getErrorState()));
        }
        builder.addGenericXdsConfigs(genericXdsConfigBuilder);
      }
    }
    return builder.build();
  }

  private static Map<XdsResourceType<?>, Map<String, ResourceMetadata>>
      awaitSubscribedResourcesMetadata(
      ListenableFuture<Map<XdsResourceType<?>, Map<String, ResourceMetadata>>> future)
      throws InterruptedException {
    try {
      // Normally this shouldn't take long, but add some slack for cases like a cold JVM.
      return future.get(20, TimeUnit.SECONDS);
    } catch (ExecutionException | TimeoutException e) {
      // For CSDS' purposes, the exact reason why metadata not loaded isn't important.
      throw new RuntimeException(e);
    }
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
      case TIMEOUT:
        return ClientResourceStatus.TIMEOUT;
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
