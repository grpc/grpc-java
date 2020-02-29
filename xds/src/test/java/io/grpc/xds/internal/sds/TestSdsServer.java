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

package io.grpc.xds.internal.sds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.ProtocolStringList;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.auth.Secret;
import io.envoyproxy.envoy.service.discovery.v2.SecretDiscoveryServiceGrpc;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerDomainSocketChannel;
import io.netty.channel.unix.DomainSocketAddress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/** Server used only in testing {@link SdsClient} so does not contain actual server logic. */
final class TestSdsServer {
  private static final Logger logger = Logger.getLogger(TestSdsServer.class.getName());

  // SecretTypeURL defines the type URL for Envoy secret proto.
  private static final String SECRET_TYPE_URL = "type.googleapis.com/envoy.api.v2.auth.Secret";

  private String currentVersion;
  private String lastRespondedNonce;
  private List<String> lastResourceNames;
  @VisibleForTesting SecretDiscoveryServiceImpl discoveryService;

  /** Used for signalling test clients that request received and processed. */
  @VisibleForTesting final Semaphore requestsCounter = new Semaphore(0);

  @VisibleForTesting String lastK8sJwtTokenValue;

  private EventLoopGroup elg;
  private EventLoopGroup boss;
  private Server server;
  private final ServerMock serverMock;

  /** last "good" discovery request we processed and sent a response to. */
  @VisibleForTesting DiscoveryRequest lastGoodRequest;

  /** last discovery request that was only used as Ack since it contained no new resources. */
  @VisibleForTesting DiscoveryRequest lastRequestOnlyForAck;

  /** last Nack. */
  @VisibleForTesting DiscoveryRequest lastNack;

  /** last response we sent. */
  @VisibleForTesting DiscoveryResponse lastResponse;

  TestSdsServer(ServerMock serverMock) {
    checkNotNull(serverMock, "serverMock");
    this.serverMock = serverMock;
  }

  /**
   * Starts the server with given transport params.
   *
   * @param name UDS pathname or server name for {@link InProcessServerBuilder}
   * @param useUds creates a UDS based server if true.
   * @param useInterceptor if true, uses {@link SdsServerInterceptor} to grab & save Jwt Token.
   */
  void startServer(String name, boolean useUds, boolean useInterceptor) throws IOException {
    checkNotNull(name, "name");
    discoveryService = new SecretDiscoveryServiceImpl();
    ServerServiceDefinition serviceDefinition = discoveryService.bindService();

    if (useInterceptor) {
      serviceDefinition =
          ServerInterceptors.intercept(serviceDefinition, new SdsServerInterceptor());
    }
    if (useUds) {
      elg = new EpollEventLoopGroup();
      boss = new EpollEventLoopGroup(1);
      server =
          NettyServerBuilder.forAddress(new DomainSocketAddress(name))
              .bossEventLoopGroup(boss)
              .workerEventLoopGroup(elg)
              .channelType(EpollServerDomainSocketChannel.class)
              .addService(serviceDefinition)
              .directExecutor()
              .build()
              .start();
    } else {
      server =
          InProcessServerBuilder.forName(name)
              .addService(serviceDefinition)
              .directExecutor()
              .build()
              .start();
    }
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  void shutdown() throws InterruptedException {
    server.shutdown();
    if (boss != null) {
      boss.shutdownGracefully().sync();
    }
    if (elg != null) {
      elg.shutdownGracefully().sync();
    }
  }

  /** Interface that allows mocking server behavior. */
  interface ServerMock {
    Secret getSecretFor(String name);

    boolean onNext(DiscoveryRequest discoveryRequest);
  }

  /** Callers can call this to return an "async" response for given resource names. */
  void generateAsyncResponse(String... names) {
    discoveryService.inboundStreamObserver.generateAsyncResponse(Arrays.asList(names));
  }

  /** Main streaming service implementation. */
  final class SecretDiscoveryServiceImpl
      extends SecretDiscoveryServiceGrpc.SecretDiscoveryServiceImplBase {

    // we use startTime for generating version string.
    final long startTime = System.nanoTime();
    SdsInboundStreamObserver inboundStreamObserver;

    @Override
    public StreamObserver<DiscoveryRequest> streamSecrets(
        StreamObserver<DiscoveryResponse> responseObserver) {
      checkNotNull(responseObserver, "responseObserver");
      inboundStreamObserver = new SdsInboundStreamObserver(responseObserver);
      return inboundStreamObserver;
    }

    @Override
    public void fetchSecrets(
        DiscoveryRequest discoveryRequest, StreamObserver<DiscoveryResponse> responseObserver) {
      throw new UnsupportedOperationException("unary fetchSecrets not implemented!");
    }

    private DiscoveryResponse buildResponse(DiscoveryRequest discoveryRequest) {
      checkNotNull(discoveryRequest, "discoveryRequest");
      String requestVersion = discoveryRequest.getVersionInfo();
      String requestNonce = discoveryRequest.getResponseNonce();
      ProtocolStringList resourceNames = discoveryRequest.getResourceNamesList();
      return buildResponse(requestVersion, requestNonce, resourceNames, false, discoveryRequest);
    }

    private DiscoveryResponse buildResponse(
        String requestVersion,
        String requestNonce,
        List<String> resourceNames,
        boolean forcedAsync,
        DiscoveryRequest discoveryRequest) {
      checkNotNull(resourceNames, "resourceNames");
      if (discoveryRequest != null && discoveryRequest.hasErrorDetail()) {
        lastNack = discoveryRequest;
        return null;
      }
      // for stale version or nonce don't send a response
      if (!Strings.isNullOrEmpty(requestVersion) && !requestVersion.equals(currentVersion)) {
        return null;
      }
      if (!Strings.isNullOrEmpty(requestNonce) && !requestNonce.equals(lastRespondedNonce)) {
        return null;
      }
      // check if any new resources are being requested...
      if (!forcedAsync && isSubset(resourceNames, lastResourceNames)) {
        if (discoveryRequest != null) {
          lastRequestOnlyForAck = discoveryRequest;
        }
        return null;
      }

      final String version = generateVersionFromCurrentTime();
      DiscoveryResponse.Builder responseBuilder =
          DiscoveryResponse.newBuilder()
              .setVersionInfo(version)
              .setNonce(generateAndSaveNonce())
              .setTypeUrl(SECRET_TYPE_URL);

      for (String resourceName : resourceNames) {
        buildAndAddResource(responseBuilder, resourceName);
      }
      DiscoveryResponse response = responseBuilder.build();
      currentVersion = version;
      lastResponse = response;
      lastResourceNames = resourceNames;
      return response;
    }

    private String generateVersionFromCurrentTime() {
      return "" + ((System.nanoTime() - startTime) / 1000000L);
    }

    private void buildAndAddResource(
        DiscoveryResponse.Builder responseBuilder, String resourceName) {
      Secret secret = serverMock.getSecretFor(resourceName);
      ByteString data = secret.toByteString();
      Any anyValue = Any.newBuilder().setTypeUrl(SECRET_TYPE_URL).setValue(data).build();
      responseBuilder.addResources(anyValue);
    }

    /** Inbound {@link StreamObserver} to process incoming requests. */
    final class SdsInboundStreamObserver implements StreamObserver<DiscoveryRequest> {
      final StreamObserver<DiscoveryResponse> responseObserver;
      ScheduledExecutorService periodicScheduler;
      ScheduledFuture<?> future;

      SdsInboundStreamObserver(StreamObserver<DiscoveryResponse> responseObserver) {
        this.responseObserver = responseObserver;
      }

      private void generateAsyncResponse(List<String> nameList) {
        checkNotNull(nameList, "nameList");
        if (!nameList.isEmpty()) {
          responseObserver.onNext(
              buildResponse(
                  currentVersion,
                  lastRespondedNonce,
                  nameList,
                  /* forcedAsync= */ true,
                  /* discoveryRequest= */ null));
        }
      }

      @Override
      public void onNext(DiscoveryRequest discoveryRequest) {
        if (!serverMock.onNext(discoveryRequest)) {
          DiscoveryResponse discoveryResponse = buildResponse(discoveryRequest);
          if (discoveryResponse != null) {
            lastGoodRequest = discoveryRequest;
            responseObserver.onNext(discoveryResponse);
          }
        }
        requestsCounter.release();
      }

      @Override
      public void onError(Throwable t) {
        logger.log(Level.SEVERE, "onError", t);
      }

      @Override
      public void onCompleted() {
        responseObserver.onCompleted();
      }
    }
  }

  /** Checks if resourceNames is a "subset" of lastResourceNames. */
  private static boolean isSubset(
      @Nullable List<String> resourceNames, @Nullable List<String> lastResourceNames) {
    if (lastResourceNames == null) {
      return resourceNames == null || resourceNames.isEmpty();
    }
    if (resourceNames == null) {
      return true; // since lastResourceNames is NOT null
    }
    return lastResourceNames.containsAll(resourceNames);
  }

  private String generateAndSaveNonce() {
    lastRespondedNonce = Long.toHexString(System.currentTimeMillis());
    return lastRespondedNonce;
  }

  private class SdsServerInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call,
        Metadata requestHeaders,
        ServerCallHandler<ReqT, RespT> next) {
      lastK8sJwtTokenValue = null;
      byte[] value =
          requestHeaders.get(SdsClientFileBasedMetadataTest.K8S_SA_JWT_TOKEN_HEADER_METADATA_KEY);
      if (value != null) {
        lastK8sJwtTokenValue = new String(value, StandardCharsets.UTF_8);
      }
      return next.startCall(new SimpleForwardingServerCall<ReqT, RespT>(call) {}, requestHeaders);
    }
  }
}
