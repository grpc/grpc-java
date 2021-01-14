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

package io.grpc.xds.sds;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.rpc.Code;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.auth.Secret;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.GrpcService;
import io.envoyproxy.envoy.api.v2.core.GrpcService.GoogleGrpc;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.service.discovery.v2.SecretDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.discovery.v2.SecretDiscoveryServiceGrpc.SecretDiscoveryServiceStub;
import io.grpc.Internal;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.internal.SharedResourceHolder.Resource;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * SDS client used by an {@link SslContextProvider} to get SDS updates from the SDS server. This is
 * most likely a temporary implementation until merged with the XdsClient.
 */
// TODO(sanjaypujare): once XdsClientImpl is ready, merge with it and add retry logic
@Internal
@NotThreadSafe
final class SdsClient {
  private static final Logger logger = Logger.getLogger(SdsClient.class.getName());
  private static final String SECRET_TYPE_URL = "type.googleapis.com/envoy.api.v2.auth.Secret";
  private static final EventLoopGroupResource eventLoopGroupResource =
      Epoll.isAvailable() ? new EventLoopGroupResource("SdsClient") : null;

  private SecretWatcher watcher;
  private final SdsSecretConfig sdsSecretConfig;
  private final Node clientNode;
  private final Executor watcherExecutor;
  private EventLoopGroup eventLoopGroup;
  private ManagedChannel channel;
  private SecretDiscoveryServiceStub secretDiscoveryServiceStub;
  private ResponseObserver responseObserver;
  private StreamObserver<DiscoveryRequest> requestObserver;
  private DiscoveryResponse lastResponse;

  /** Factory for creating SdsClient based on input params and for unit tests. */
  static class Factory {

    /** Creates an SdsClient after figuring out channel to use. */
    static SdsClient createSdsClient(
        SdsSecretConfig sdsSecretConfig,
        Node node,
        Executor watcherExecutor,
        Executor channelExecutor) {
      ChannelInfo channelInfo = extractChannelInfo(sdsSecretConfig.getSdsConfig());
      String targetUri = channelInfo.targetUri;
      String channelType = channelInfo.channelType;
      if (channelType != null && channelType.startsWith("inproc")) {
        ManagedChannel channel =
            InProcessChannelBuilder.forName(targetUri).executor(channelExecutor).build();
        return new SdsClient(
            sdsSecretConfig, node, watcherExecutor, channel, /* eventLoopGroup= */ null);
      }
      NettyChannelBuilder builder;
      EventLoopGroup eventLoopGroup = null;
      if (targetUri.startsWith("unix:")) {
        checkState(Epoll.isAvailable(), "Epoll is not available");
        eventLoopGroup = SharedResourceHolder.get(eventLoopGroupResource);
        builder =
            NettyChannelBuilder.forAddress(new DomainSocketAddress(targetUri.substring(5)))
                .eventLoopGroup(eventLoopGroup)
                .channelType(EpollDomainSocketChannel.class);
      } else {
        builder = NettyChannelBuilder.forTarget(targetUri);
      }
      builder = builder.usePlaintext();
      if (channelExecutor != null) {
        builder = builder.executor(channelExecutor);
      }
      ManagedChannel channel = builder.build();
      return new SdsClient(sdsSecretConfig, node, watcherExecutor, channel, eventLoopGroup);
    }

    @VisibleForTesting
    static ChannelInfo extractChannelInfo(ConfigSource configSource) {
      checkNotNull(configSource, "configSource");
      checkArgument(
          configSource.hasApiConfigSource(), "only configSource with ApiConfigSource supported");
      ApiConfigSource apiConfigSource = configSource.getApiConfigSource();
      checkArgument(
          ApiType.GRPC.equals(apiConfigSource.getApiType()),
          "only GRPC ApiConfigSource type supported");
      checkArgument(
          apiConfigSource.getGrpcServicesCount() == 1,
          "expecting exactly 1 GrpcService in ApiConfigSource");
      GrpcService grpcService = apiConfigSource.getGrpcServices(0);
      checkArgument(
          grpcService.hasGoogleGrpc() && !grpcService.hasEnvoyGrpc(),
          "only GoogleGrpc expected in GrpcService");
      GoogleGrpc googleGrpc = grpcService.getGoogleGrpc();
      // for now don't support any credentials
      checkArgument(
          !googleGrpc.hasChannelCredentials()
              && googleGrpc.getCallCredentialsCount() == 0
              && Strings.isNullOrEmpty(googleGrpc.getCredentialsFactoryName()),
          "No credentials supported in GoogleGrpc");
      String targetUri = googleGrpc.getTargetUri();
      String channelType = null;
      if (googleGrpc.hasConfig()) {
        Struct struct = googleGrpc.getConfig();
        Value value = struct.getFieldsMap().get("channelType");
        channelType = value.getStringValue();
      }
      checkArgument(!Strings.isNullOrEmpty(targetUri), "targetUri in GoogleGrpc is empty!");
      return new ChannelInfo(targetUri, channelType);
    }
  }

  @VisibleForTesting
  static final class ChannelInfo {
    @VisibleForTesting final String targetUri;
    @VisibleForTesting final String channelType;

    private ChannelInfo(String targetUri, String channelType) {
      this.targetUri = targetUri;
      this.channelType = channelType;
    }
  }

  private SdsClient(
      SdsSecretConfig sdsSecretConfig,
      Node node,
      Executor watcherExecutor,
      ManagedChannel channel,
      EventLoopGroup eventLoopGroup) {
    checkNotNull(sdsSecretConfig, "sdsSecretConfig");
    checkNotNull(node, "node");
    this.sdsSecretConfig = sdsSecretConfig;
    this.clientNode = node;
    this.watcherExecutor = watcherExecutor;
    this.eventLoopGroup = eventLoopGroup;
    checkNotNull(channel, "channel");
    this.channel = channel;
  }

  /**
   * Starts resource discovery with SDS protocol. This method should be the first one to be called
   * and should be called only once.
   */
  void start() {
    if (requestObserver == null) {
      secretDiscoveryServiceStub = SecretDiscoveryServiceGrpc.newStub(channel);
      responseObserver = new ResponseObserver();
      requestObserver = secretDiscoveryServiceStub.streamSecrets(responseObserver);
    }
  }

  /** Stops resource discovery. No method in this class should be called after this point. */
  void shutdown() {
    if (requestObserver != null) {
      requestObserver.onCompleted();
      requestObserver = null;
      channel.shutdownNow();
      if (eventLoopGroup != null) {
        eventLoopGroup = SharedResourceHolder.release(eventLoopGroupResource, eventLoopGroup);
      }
    }
  }

  /** Response observer for SdsClient. */
  private final class ResponseObserver implements StreamObserver<DiscoveryResponse> {
    ResponseObserver() {}

    @Override
    public void onNext(DiscoveryResponse discoveryResponse) {
      processDiscoveryResponse(discoveryResponse);
    }

    @Override
    public void onError(Throwable t) {
      sendErrorToWatcher(t);
    }

    @Override
    public void onCompleted() {
      // TODO(sanjaypujare): add retry logic once client implementation is final
    }
  }

  private void processDiscoveryResponse(final DiscoveryResponse response) {
    watcherExecutor.execute(
        new Runnable() {
          @Override
          public void run() {
            if (!processSecretsFromDiscoveryResponse(response)) {
              sendNack(Code.INTERNAL_VALUE, "Secret not updated");
              return;
            }
            lastResponse = response;
            // send discovery request as ACK
            sendDiscoveryRequestOnStream();
          }
        });
  }

  private void sendNack(int errorCode, String errorMessage) {
    String nonce = "";
    String versionInfo = "";

    if (lastResponse != null) {
      nonce = lastResponse.getNonce();
      versionInfo = lastResponse.getVersionInfo();
    }
    DiscoveryRequest.Builder builder =
        DiscoveryRequest.newBuilder()
            .setTypeUrl(SECRET_TYPE_URL)
            .setResponseNonce(nonce)
            .setVersionInfo(versionInfo)
            .addResourceNames(sdsSecretConfig.getName())
            .setErrorDetail(
                com.google.rpc.Status.newBuilder()
                    .setCode(errorCode)
                    .setMessage(errorMessage)
                    .build())
            .setNode(clientNode);

    DiscoveryRequest req = builder.build();
    requestObserver.onNext(req);
  }

  private void sendErrorToWatcher(final Throwable t) {
    final SecretWatcher localCopy = watcher;
    if (localCopy != null) {
      watcherExecutor.execute(
          new Runnable() {
            @Override
            public void run() {
              try {
                localCopy.onError(Status.fromThrowable(t));
              } catch (Throwable throwable) {
                logger.log(Level.SEVERE, "exception from onError", throwable);
              }
            }
          });
    }
  }

  private boolean processSecretsFromDiscoveryResponse(DiscoveryResponse response) {
    List<Any> resources = response.getResourcesList();
    checkState(resources.size() == 1, "exactly one resource expected");
    boolean noException = true;
    for (Any any : resources) {
      final String typeUrl = any.getTypeUrl();
      checkState(SECRET_TYPE_URL.equals(typeUrl), "wrong value for typeUrl %s", typeUrl);
      Secret secret = null;
      try {
        secret = Secret.parseFrom(any.getValue());
        if (!processSecret(secret)) {
          noException = false;
        }
      } catch (InvalidProtocolBufferException e) {
        logger.log(Level.SEVERE, "exception from parseFrom", e);
      }
    }
    return noException;
  }

  private boolean processSecret(Secret secret) {
    checkState(
        sdsSecretConfig.getName().equals(secret.getName()),
        "expected secret name %s",
        sdsSecretConfig.getName());
    boolean noException = true;
    final SecretWatcher localCopy = watcher;
    if (localCopy != null) {
      try {
        localCopy.onSecretChanged(secret);
      } catch (Throwable throwable) {
        noException = false;
        logger.log(Level.SEVERE, "exception from onSecretChanged", throwable);
      }
    }
    return noException;
  }

  /** Registers a secret watcher for this client's SdsSecretConfig. */
  void watchSecret(SecretWatcher secretWatcher) {
    checkNotNull(secretWatcher, "secretWatcher");
    checkState(watcher == null, "watcher already set");
    watcher = secretWatcher;
    if (lastResponse == null) {
      sendDiscoveryRequestOnStream();
    } else {
      watcherExecutor.execute(
          new Runnable() {
            @Override
            public void run() {
              processSecretsFromDiscoveryResponse(lastResponse);
            }
          });
    }
  }

  /** Unregisters the given endpoints watcher. */
  void cancelSecretWatch(SecretWatcher secretWatcher) {
    checkNotNull(secretWatcher, "secretWatcher");
    checkArgument(secretWatcher == watcher, "Incorrect secretWatcher to cancel");
    watcher = null;
  }

  /** Secret watcher interface. */
  interface SecretWatcher {
    void onSecretChanged(Secret secretUpdate);

    void onError(Status error);
  }

  private static final class EventLoopGroupResource implements Resource<EventLoopGroup> {
    private final String name;

    EventLoopGroupResource(String name) {
      this.name = name;
    }

    @Override
    public EventLoopGroup create() {
      // Use Netty's DefaultThreadFactory in order to get the benefit of FastThreadLocal.
      ThreadFactory threadFactory = new DefaultThreadFactory(name, /* daemon= */ true);
      return new EpollEventLoopGroup(1, threadFactory);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    @Override
    public void close(EventLoopGroup instance) {
      try {
        instance.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
      } catch (InterruptedException e) {
        logger.log(Level.SEVERE, "from EventLoopGroup.shutdownGracefully", e);
        Thread.currentThread().interrupt(); // to not "swallow" the exception...
      }
    }
  }

  private void sendDiscoveryRequestOnStream() {
    String nonce = "";
    String versionInfo = "";

    if (lastResponse != null) {
      nonce = lastResponse.getNonce();
      versionInfo = lastResponse.getVersionInfo();
    }
    DiscoveryRequest.Builder builder =
        DiscoveryRequest.newBuilder()
            .setTypeUrl(SECRET_TYPE_URL)
            .setResponseNonce(nonce)
            .setVersionInfo(versionInfo)
            .addResourceNames(sdsSecretConfig.getName())
            .setNode(clientNode);

    DiscoveryRequest req = builder.build();
    requestObserver.onNext(req);
  }
}
