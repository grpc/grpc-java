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
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SDS client used by {@link SslContextProvider}s to get SDS updates from the SDS server. This is
 * most likely a temporary implementation until merged with the XdsClient.
 */
// TODO(sanjaypujare): once XdsClientImpl is ready, merge with it and add retry logic
@Internal
final class SdsClient {
  private static final Logger logger = Logger.getLogger(SdsClient.class.getName());
  private static final String SECRET_TYPE_URL = "type.googleapis.com/envoy.api.v2.auth.Secret";

  private final HashMap<String, HashSet<SecretWatcher>> watcherMap = new HashMap<>();

  private static SdsClient instance;
  private final ConfigSource configSource;
  private final Node clientNode;
  @VisibleForTesting String targetUri;
  ManagedChannel channel;
  private SecretDiscoveryServiceStub secretDiscoveryServiceStub;
  private ResponseObserver responseObserver;
  private StreamObserver<DiscoveryRequest> requestObserver;
  private DiscoveryResponse lastResponse;

  /**
   * Starts resource discovery with SDS protocol. This method should be the first one to be called
   * and should be called only once.
   */
  void start() {
    if (channel == null) {
      if (targetUri.startsWith("unix:")) {
        EventLoopGroup elg = new EpollEventLoopGroup();
        channel =
            NettyChannelBuilder.forAddress(new DomainSocketAddress(targetUri.substring(5)))
                .eventLoopGroup(elg)
                .channelType(EpollDomainSocketChannel.class)
                .build();
      } else {
        channel = NettyChannelBuilder.forTarget(targetUri).build();
      }
    }
    secretDiscoveryServiceStub = SecretDiscoveryServiceGrpc.newStub(channel);
    responseObserver = new ResponseObserver();
    requestObserver = secretDiscoveryServiceStub.streamSecrets(responseObserver);
  }

  /** Stops resource discovery. No method in this class should be called after this point. */
  void shutdown() {
    requestObserver.onCompleted();
    channel.shutdownNow();
  }

  /**
   * Gets the singleton instance after constructing it as needed.
   *
   * @param configSource the {@link ConfigSource} pointing to the SDS server.
   * @param node Node id of this client.
   */
  static synchronized SdsClient getInstance(ConfigSource configSource, Node node) {
    checkNotNull(configSource, "configSource");
    checkNotNull(node, "node");
    if (instance == null) {
      instance = new SdsClient(configSource, node);
      return instance;
    }
    // check if apiConfigSource match
    if (instance.configSource.equals(configSource) && instance.clientNode.equals(node)) {
      return instance;
    }
    throw new UnsupportedOperationException(
        "Multiple SdsClient's with different ApiConfigSource and Node's not supported");
  }

  private SdsClient(ConfigSource configSource, Node node) {
    checkNotNull(configSource, "configSource");
    checkNotNull(node, "node");
    this.configSource = configSource;
    this.clientNode = node;
    this.targetUri = extractUdsTarget(configSource);
  }

  /** Create the client with given configSource, node and channel. */
  @VisibleForTesting
  SdsClient(ConfigSource configSource, Node node, ManagedChannel channel) {
    this(configSource, node);
    this.channel = channel;
  }

  @VisibleForTesting
  static String extractUdsTarget(ConfigSource configSource) {
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
    checkArgument(!Strings.isNullOrEmpty(targetUri), "targetUri in GoogleGrpc is empty!");
    return targetUri;
  }

  /** Response observer for SdsClient. */
  private final class ResponseObserver implements StreamObserver<DiscoveryResponse> {

    ResponseObserver() {}

    @Override
    public void onNext(DiscoveryResponse discoveryResponse) {
      try {
        processDiscoveryResponse(discoveryResponse);
      } catch (Exception e) {
        logger.log(Level.SEVERE, "processDiscoveryResponse", e);
      }
    }

    private void processDiscoveryResponse(DiscoveryResponse response)
        throws InvalidProtocolBufferException {
      List<Any> resources = response.getResourcesList();
      ArrayList<String> resourceNames = new ArrayList<>();
      for (Any any : resources) {
        final String typeUrl = any.getTypeUrl();
        checkState(SECRET_TYPE_URL.equals(typeUrl), "wrong value for typeUrl %s", typeUrl);
        Secret secret = Secret.parseFrom(any.getValue());
        resourceNames.add(secret.getName());
        processSecret(secret);
      }
      lastResponse = response;
      // send discovery request as ACK
      sendDiscoveryRequestOnStream(resourceNames);
    }

    private void processSecret(Secret secret) {
      synchronized (watcherMap) {
        final HashSet<SecretWatcher> secretWatchers = watcherMap.get(secret.getName());
        if (secretWatchers != null) {
          for (SecretWatcher secretWatcher : secretWatchers) {
            secretWatcher.onSecretChanged(secret);
          }
        }
      }
    }

    @Override
    public void onError(Throwable t) {
      logger.log(Level.SEVERE, "ResponseObserver.onError", t);
    }

    @Override
    public void onCompleted() {
      // TODO(sanjaypujare): add retry logic once client implementation is final
    }
  }

  private void sendDiscoveryRequestOnStream(String... names) {
    sendDiscoveryRequestOnStream(Arrays.asList(names));
  }

  @SuppressWarnings("SynchronizeOnNonFinalField")
  private void sendDiscoveryRequestOnStream(List<String> names) {
    checkNotNull(names, "names");
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
            .setNode(clientNode);

    for (String name : names) {
      builder.addResourceNames(name);
    }
    DiscoveryRequest req = builder.build();
    synchronized (requestObserver) {
      requestObserver.onNext(req);
    }
  }

  /**
   * Registers a secret watcher for the given SdsSecretConfig.
   *
   * @return A {@link SecretWatcherHandle} to pass to {@link
   *     #cancelSecretWatch(SecretWatcherHandle)}.
   */
  SecretWatcherHandle watchSecret(SdsSecretConfig sdsSecretConfig, SecretWatcher secretWatcher) {
    checkNotNull(sdsSecretConfig, "sdsSecretConfig");
    checkNotNull(secretWatcher, "secretWatcher");
    checkArgument(
        sdsSecretConfig.getSdsConfig().equals(this.configSource),
        "expected configSource" + this.configSource);
    String name = sdsSecretConfig.getName();
    checkState(!Strings.isNullOrEmpty(name), "name required");
    synchronized (watcherMap) {
      HashSet<SecretWatcher> set = watcherMap.get(name);
      if (set == null) {
        set = new HashSet<>();
        watcherMap.put(name, set);
      }
      set.add(secretWatcher);
    }
    sendDiscoveryRequestOnStream(name);
    return new SecretWatcherHandle(name, secretWatcher);
  }

  /** Unregisters the given endpoints watcher. */
  void cancelSecretWatch(SecretWatcherHandle secretWatcherHandle) {
    checkNotNull(secretWatcherHandle, "secretWatcherHandle");
    synchronized (watcherMap) {
      HashSet<SecretWatcher> set = watcherMap.get(secretWatcherHandle.name);
      checkState(set != null, "watcher not found");
      checkState(set.remove(secretWatcherHandle.secretWatcher), "watcher not found");
    }
  }

  /** Secret watcher interface. */
  interface SecretWatcher {

    void onSecretChanged(Secret secretUpdate);

    void onError(Status error);
  }

  /**
   * Class representing opaque handle to refer to registered {@link SecretWatcher}'s to use in
   * {@link #cancelSecretWatch(SecretWatcherHandle)}.
   */
  static final class SecretWatcherHandle {
    private final String name;
    private final SecretWatcher secretWatcher;

    private SecretWatcherHandle(String name, SecretWatcher secretWatcher) {
      this.name = name;
      this.secretWatcher = secretWatcher;
    }
  }
}
