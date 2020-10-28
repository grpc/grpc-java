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
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.TrafficDirection;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.FilterChainMatch;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.grpc.Status;
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
  @Nullable
  private ListenerWatcher listenerWatcher;
  private int listenerPort = -1;
  private final boolean newServerApi;
  @Nullable private final String instanceIp;
  @Nullable
  private ScheduledHandle ldsRespTimer;

  ServerXdsClient(XdsChannel channel, Node node, ScheduledExecutorService timeService,
      BackoffPolicy.Provider backoffPolicyProvider, Supplier<Stopwatch> stopwatchSupplier,
      boolean newServerApi, String instanceIp) {
    super(channel, node, timeService, backoffPolicyProvider, stopwatchSupplier);
    this.newServerApi = channel.isUseProtocolV3() && newServerApi;
    this.instanceIp = (instanceIp != null ? instanceIp : "0.0.0.0");
  }

  @Override
  void watchListenerData(final int port, final ListenerWatcher watcher) {
    checkState(listenerWatcher == null, "ListenerWatcher already registered");
    listenerWatcher = checkNotNull(watcher, "watcher");
    checkArgument(port > 0, "port needs to be > 0");
    listenerPort = port;
    getSyncContext().execute(new Runnable() {
      @Override
      public void run() {
        getLogger().log(XdsLogLevel.INFO, "Started watching listener for port {0}", port);
        if (!newServerApi) {
          updateNodeMetadataForListenerRequest(port);
        }
        adjustResourceSubscription(ResourceType.LDS);
        if (!isInBackoff()) {
          ldsRespTimer =
              getSyncContext()
                  .schedule(
                      new ListenerResourceFetchTimeoutTask(":" + port),
                      INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS, getTimeService());
        }
      }
    });
  }

  @Nullable
  @Override
  Collection<String> getSubscribedResources(ResourceType type) {
    if (newServerApi) {
      String listeningAddress = instanceIp + ":" + listenerPort;
      String resourceName = "grpc/server?udpa.resource.listening_address=" + listeningAddress;
      return ImmutableList.<String>of(resourceName);
    } else {
      return Collections.emptyList();
    }
  }

  /** In case of Listener watcher metadata to be updated to include port. */
  private void updateNodeMetadataForListenerRequest(int port) {
    Map<String, Object> newMetadata = new HashMap<>();
    if (node.getMetadata() != null) {
      newMetadata.putAll(node.getMetadata());
    }
    newMetadata.put("TRAFFICDIRECTOR_INBOUND_INTERCEPTION_PORT", "15001");
    newMetadata.put("TRAFFICDIRECTOR_INBOUND_BACKEND_PORTS", "" + port);
    newMetadata.put("INSTANCE_IP", instanceIp);
    node = node.toBuilder().setMetadata(newMetadata).build();
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
    if (newServerApi) {
      return "TRAFFICDIRECTOR_INBOUND_LISTENER".equals(listener.getName())
              && listener.getTrafficDirection().equals(TrafficDirection.INBOUND)
              && hasMatchingFilter(listener.getFilterChainsList());
    }
    return isAddressMatching(listener.getAddress())
        && hasMatchingFilter(listener.getFilterChainsList());
  }

  private boolean isAddressMatching(Address address) {
    return newServerApi || (address.hasSocketAddress()
            && (address.getSocketAddress().getPortValue() == 15001));
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
          getSyncContext()
              .schedule(
                  new ListenerResourceFetchTimeoutTask(":" + listenerPort),
                  INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS, getTimeService());
    }
  }

  @Override
  protected void handleShutdown() {
    cleanUpResourceTimer();
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
