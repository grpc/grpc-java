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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.BackoffPolicy;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;

final class XdsClientImpl extends XdsClient {
  static final Logger logger = Logger.getLogger(XdsClientImpl.class.getName());
  static final String EDS_TYPE_URL =
      "type.googleapis.com/envoy.api.v2.ClusterLoadAssignment";

  private final ManagedChannel channel;
  private final Node xdsNode;
  private final String authority;
  private final EndpointWatchers endpointWatchers;
  private final AdsLifecycleManager adsLifecycleManager;

  XdsClientImpl(
      ManagedChannel channel,
      SynchronizationContext syncCtx,
      ScheduledExecutorService timerService,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier,
      Node xdsNode,
      final String authority) {
    this.channel = checkNotNull(channel, "channel");
    this.xdsNode = checkNotNull(xdsNode, "xdsNode");
    this.authority = checkNotNull(authority, "authority");
    this.endpointWatchers = new EndpointWatchers(xdsNode);
    this.adsLifecycleManager = new AdsLifecycleManager(
        AggregatedDiscoveryServiceGrpc.newStub(channel).withWaitForReady(),
        endpointWatchers,
        syncCtx,
        checkNotNull(timerService, "timerService"),
        checkNotNull(stopwatchSupplier, "stopwatchSupplier"),
        checkNotNull(backoffPolicyProvider, "backoffPolicyProvider"));
  }

  @Override
  void start() {
    logger.log(Level.INFO, "Starting XdsClient {0}", XdsClientImpl.this);
    adsLifecycleManager.start();
  }

  @Override
  void shutdown() {
    channel.shutdown();
    adsLifecycleManager.shutdown();
  }

  @Override
  void watchEndpointData(String cluster, EndpointWatcher endpointWatcher) {
    endpointWatchers.addWatcher(cluster, endpointWatcher);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("xdsNode", xdsNode).add("authority", authority).toString();
  }

  /**
   * Keeps track of all the endpoinWaters that have been added.
   */
  static final class EndpointWatchers {
    private final Map<String, Collection<EndpointWatcher>> endpointWatchers = new LinkedHashMap<>();
    private final Node xdsNode;

    @CheckForNull // It is null during backoff period.
    private StreamObserver<DiscoveryRequest> xdsRequestWriter;

    EndpointWatchers(Node xdsNode) {
      this.xdsNode = xdsNode;
    }

    void addWatcher(String cluster, EndpointWatcher endpointWatcher) {
      if (!endpointWatchers.containsKey(cluster)) {
        endpointWatchers.put(cluster, new ArrayList<EndpointWatcher>());
      }
      endpointWatchers.get(cluster).add(endpointWatcher);

      if (xdsRequestWriter != null) {
        requestEndpoints(xdsRequestWriter);
      }
    }

    void onError(Status error) {
      for (Collection<EndpointWatcher> watcherCollection : endpointWatchers.values()) {
        for (EndpointWatcher endpointWatcher : watcherCollection) {
          endpointWatcher.onError(error);
        }
      }
    }

    void onEndpointUpdate(ClusterLoadAssignment clusterLoadAssignment) {
      String cluster = clusterLoadAssignment.getClusterName();
      if (endpointWatchers.containsKey(cluster)) {
        for (EndpointWatcher endpointWatcher : endpointWatchers.get(cluster)) {
          endpointWatcher.onEndpointChanged(
              EndpointUpdate.newBuilder().setClusterLoadAssignment(clusterLoadAssignment).build());
        }
      }
    }

    /**
     * Sends an EDS request if there is any EndpointWatcher added, otherwise will delay EDS request
     * until a new EndpointWatcher is added.
     */
    void requestEndpoints(StreamObserver<DiscoveryRequest> xdsRequestWriter) {
      this.xdsRequestWriter = checkNotNull(xdsRequestWriter, "xdsRequestWriter");
      if (!endpointWatchers.isEmpty()) {
        DiscoveryRequest edsRequest =
            DiscoveryRequest.newBuilder()
                .setNode(xdsNode)
                .setTypeUrl(EDS_TYPE_URL)
                .addAllResourceNames(endpointWatchers.keySet())
                .build();
        logger.log(Level.FINE, "Sending EDS request {0}", edsRequest);
        xdsRequestWriter.onNext(edsRequest);
      }
    }

    /**
     * Will not send EDS request until the next requestEndpoints(xdsRequestWriter) is called.
     */
    void seizeRequestEndpoints() {
      xdsRequestWriter = null;
    }
  }
}
