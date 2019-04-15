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

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Duration;
import io.envoyproxy.envoy.api.v2.core.Locality;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats.DroppedRequests;
import io.envoyproxy.envoy.api.v2.endpoint.UpstreamLocalityStats;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.Metadata;
import io.grpc.xds.XdsClientLoadRecorder.ClientLoadCounter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.ThreadSafe;

/**
 * An {@link XdsLoadReportStore} instance holds the client side load stats for a cluster.
 */
@ThreadSafe
final class XdsLoadReportStore {

  private static final ClientStreamTracer NOOP_CLIENT_STREAM_TRACER =
      new ClientStreamTracer() {
      };
  private static final ClientStreamTracer.Factory NOOP_CLIENT_STREAM_TRACER_FACTORY =
      new ClientStreamTracer.Factory() {
        @Override
        public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
          return NOOP_CLIENT_STREAM_TRACER;
        }
      };
  private final String clusterName;
  private final ConcurrentMap<Locality, ClientLoadCounter> localityLoadCounters;
  // Cluster level dropped request counts for each category specified in the DropOverload policy.
  private final ConcurrentMap<String, AtomicLong> dropCounters;

  XdsLoadReportStore(String clusterName) {
    this(clusterName, new ConcurrentHashMap<Locality, ClientLoadCounter>(),
        new ConcurrentHashMap<String, AtomicLong>());
  }

  @VisibleForTesting
  XdsLoadReportStore(String clusterName,
      ConcurrentMap<Locality, ClientLoadCounter> localityLoadCounters,
      ConcurrentMap<String, AtomicLong> dropCounters) {
    this.clusterName = checkNotNull(clusterName, "clusterName");
    this.localityLoadCounters = checkNotNull(localityLoadCounters, "localityLoadCounters");
    this.dropCounters = checkNotNull(dropCounters, "dropCounters");
  }

  /**
   * Generates a {@link ClusterStats} containing load stats in locality granularity.
   */
  ClusterStats generateLoadReport(Duration interval) {
    ClusterStats.Builder statsBuilder = ClusterStats.newBuilder().setClusterName(clusterName)
        .setLoadReportInterval(interval);
    for (Map.Entry<Locality, XdsClientLoadRecorder.ClientLoadCounter> entry : localityLoadCounters
        .entrySet()) {
      XdsClientLoadRecorder.ClientLoadSnapshot snapshot = entry.getValue().snapshot();
      statsBuilder
          .addUpstreamLocalityStats(UpstreamLocalityStats.newBuilder()
              .setLocality(entry.getKey())
              .setTotalSuccessfulRequests(snapshot.callsSucceed)
              .setTotalErrorRequests(snapshot.callsFailed)
              .setTotalRequestsInProgress(snapshot.callsInProgress));
    }
    for (Map.Entry<String, AtomicLong> entry : dropCounters.entrySet()) {
      statsBuilder.addDroppedRequests(DroppedRequests.newBuilder()
          .setCategory(entry.getKey())
          .setDroppedCount(entry.getValue().get()));
    }
    return statsBuilder.build();
  }

  /**
   * Create a {@link ClientLoadCounter} for the provided locality if not present in this {@link
   * XdsLoadReportStore}. This method needs to be called at locality updates for newly assigned
   * localities in balancer discovery responses.
   */
  void addLocality(Locality locality) {
    localityLoadCounters.putIfAbsent(locality, new ClientLoadCounter());
  }

  /**
   * Discard the {@link ClientLoadCounter} for the provided locality if owned by this {@link
   * XdsLoadReportStore} to avoid map size growing infinitely. To be called at locality updates when
   * the provided locality is no longer considered in balancer discovery response.s
   */
  void removeLocality(Locality locality) {
    localityLoadCounters.remove(locality);
  }

  /**
   * Intercepts a in-locality PickResult with load recording {@link ClientStreamTracer.Factory}.
   */
  PickResult interceptPickResult(PickResult pickResult, Locality locality) {
    if (!pickResult.getStatus().isOk() || !localityLoadCounters.containsKey(locality)) {
      return pickResult;
    }
    ClientStreamTracer.Factory originFactory = pickResult.getStreamTracerFactory();
    if (originFactory == null) {
      originFactory = NOOP_CLIENT_STREAM_TRACER_FACTORY;
    }

    XdsClientLoadRecorder.ClientLoadCounter counter = localityLoadCounters.get(locality);
    XdsClientLoadRecorder recorder = new XdsClientLoadRecorder(counter, originFactory);
    return PickResult.withSubchannel(pickResult.getSubchannel(), recorder);
  }

  /**
   * Record that a request has been dropped by drop overload policy with the provided category
   * instructed by the remote balancer.
   */
  void recordDroppedRequest(String category) {
    // TODO (chengyuanzhang): do we consider a dropped request to be a call started and finished?
    AtomicLong counter = dropCounters.putIfAbsent(category, new AtomicLong());
    if (counter == null) {
      counter = dropCounters.get(category);
    }
    counter.getAndIncrement();
  }
}
