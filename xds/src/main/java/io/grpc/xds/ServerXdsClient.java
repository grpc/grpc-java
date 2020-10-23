/*
 * Copyright 2020 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.FilterChainMatch;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * XdsClient implementation for server side usages.
 */
final class ServerXdsClient extends AbstractXdsClient {

  // Longest time to wait, since the subscription to some resource, for concluding its absence.
  @VisibleForTesting
  static final int INITIAL_RESOURCE_FETCH_TIMEOUT_SEC = 15;
  private final SynchronizationContext syncContext;
  @Nullable
  private ListenerWatcher listenerWatcher;
  private int listenerPort = -1;
  @Nullable
  private ScheduledHandle ldsRespTimer;

  ServerXdsClient(XdsChannel channel, Node node, SynchronizationContext syncContext,
      ScheduledExecutorService timeService, BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier) {
    super(channel, node, timeService, backoffPolicyProvider, stopwatchSupplier);
    this.syncContext = checkNotNull(syncContext, "syncContext");
  }

  @Override
  void watchListenerData(int port, ListenerWatcher watcher) {
    checkState(listenerWatcher == null, "ListenerWatcher already registered");
    listenerWatcher = checkNotNull(watcher, "watcher");
    checkArgument(port > 0, "port needs to be > 0");
    this.listenerPort = port;
    getLogger().log(XdsLogLevel.INFO, "Started watching listener for port {0}", port);
    updateNodeMetadataForListenerRequest(port);
    adjustResourceSubscription(ResourceType.LDS);
    if (!isInBackoff()) {
      ldsRespTimer =
          syncContext
              .schedule(
                  new ListenerResourceFetchTimeoutTask(":" + port),
                  INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS, getTimeService());
    }
  }

  @Nullable
  @Override
  Collection<String> getSubscribedResources(ResourceType type) {
    if (type != ResourceType.LDS || listenerWatcher == null) {
      return null;
    }
    return Collections.emptyList();
  }

  /** In case of Listener watcher metadata to be updated to include port. */
  private void updateNodeMetadataForListenerRequest(int port) {
    Map<String, Object> newMetadata = new HashMap<>();
    if (node.getMetadata() != null) {
      newMetadata.putAll(node.getMetadata());
    }
    newMetadata.put("TRAFFICDIRECTOR_PROXYLESS", "1");
    // TODO(sanjaypujare): eliminate usage of listening_addresses.
    EnvoyProtoData.Address listeningAddress =
        new EnvoyProtoData.Address("0.0.0.0", port);
    node =
        node.toBuilder().setMetadata(newMetadata).addListeningAddresses(listeningAddress).build();
  }

  @Override
  protected void handleLdsResponse(String versionInfo, List<Any> resources, String nonce) {
    // Unpack Listener messages.
    Listener requestedListener = null;
    getLogger().log(XdsLogLevel.DEBUG, "Listener count: {0}", resources.size());
    try {
      for (com.google.protobuf.Any res : resources) {
        if (res.getTypeUrl().equals(ResourceType.LDS.typeUrlV2())) {
          res = res.toBuilder().setTypeUrl(ResourceType.LDS.typeUrl()).build();
        }
        Listener listener = res.unpack(Listener.class);
        getLogger().log(XdsLogLevel.DEBUG, "Found listener {0}", listener.toString());
        if (isRequestedListener(listener)) {
          requestedListener = listener;
          getLogger().log(XdsLogLevel.DEBUG, "Requested listener found: {0}", listener.getName());
        }
      }
    } catch (InvalidProtocolBufferException e) {
      getLogger().log(XdsLogLevel.WARNING, "Failed to unpack Listeners in LDS response {0}", e);
      nackResponse(ResourceType.LDS, nonce, "Malformed LDS response: " + e);
      return;
    }
    ListenerUpdate listenerUpdate = null;
    if (requestedListener != null) {
      if (ldsRespTimer != null) {
        ldsRespTimer.cancel();
        ldsRespTimer = null;
      }
      try {
        listenerUpdate = ListenerUpdate.newBuilder()
            .setListener(EnvoyServerProtoData.Listener.fromEnvoyProtoListener(requestedListener))
            .build();
      } catch (InvalidProtocolBufferException e) {
        getLogger().log(XdsLogLevel.WARNING, "Failed to unpack Listener in LDS response {0}", e);
        nackResponse(ResourceType.LDS, nonce, "Malformed LDS response: " + e);
        return;
      }
    } else {
      if (ldsRespTimer == null) {
        listenerWatcher.onResourceDoesNotExist(":" + listenerPort);
      }
    }
    ackResponse(ResourceType.LDS, versionInfo, nonce);
    if (listenerUpdate != null) {
      listenerWatcher.onListenerChanged(listenerUpdate);
    }
  }

  private boolean isRequestedListener(Listener listener) {
    // TODO(sanjaypujare): check listener.getName() once we know what xDS server returns
    return isAddressMatching(listener.getAddress())
        && hasMatchingFilter(listener.getFilterChainsList());
  }

  private boolean isAddressMatching(Address address) {
    // TODO(sanjaypujare): check IP address once we know xDS server will include it
    return address.hasSocketAddress()
        && (address.getSocketAddress().getPortValue() == listenerPort);
  }

  private boolean hasMatchingFilter(List<FilterChain> filterChainsList) {
    // TODO(sanjaypujare): if myIp to be checked against filterChainMatch.getPrefixRangesList()
    for (FilterChain filterChain : filterChainsList) {
      FilterChainMatch filterChainMatch = filterChain.getFilterChainMatch();

      if (listenerPort == filterChainMatch.getDestinationPort().getValue()) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected void handleStreamClosed(Status error) {
    cleanUpResourceTimer();
    if (listenerWatcher != null) {
      listenerWatcher.onError(error);
    }
  }

  @Override
  protected void handleStreamRestarted() {
    if (listenerWatcher != null) {
      ldsRespTimer =
          syncContext
              .schedule(
                  new ListenerResourceFetchTimeoutTask(":" + listenerPort),
                  INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS, getTimeService());
    }
  }

  @Override
  protected void handleShutdown() {
    cleanUpResourceTimer();
  }

  @Override
  protected void runWithSynchronized(Runnable runnable) {
    syncContext.execute(runnable);
  }

  private void cleanUpResourceTimer() {
    if (ldsRespTimer != null) {
      ldsRespTimer.cancel();
      ldsRespTimer = null;
    }
  }

  @VisibleForTesting
  final class ListenerResourceFetchTimeoutTask implements Runnable {
    private String resourceName;

    ListenerResourceFetchTimeoutTask(String resourceName) {
      this.resourceName = resourceName;
    }

    @Override
    public void run() {
      getLogger().log(
          XdsLogLevel.WARNING,
          "Did not receive resource info {0} after {1} seconds, conclude it absent",
          resourceName, INITIAL_RESOURCE_FETCH_TIMEOUT_SEC);
      ldsRespTimer = null;
      listenerWatcher.onResourceDoesNotExist(resourceName);
    }
  }
}
