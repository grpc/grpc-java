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
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats.DroppedRequests;
import io.envoyproxy.envoy.api.v2.endpoint.EndpointLoadMetricStats;
import io.envoyproxy.envoy.api.v2.endpoint.UpstreamLocalityStats;
import io.grpc.xds.ClientLoadCounter.ClientLoadSnapshot;
import io.grpc.xds.ClientLoadCounter.MetricValue;
import io.grpc.xds.EnvoyProtoData.Locality;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * An {@link LoadStatsStoreImpl} instance holds the load stats for a cluster from an gRPC
 * client's perspective by maintaining a set of locality counters for each locality it is tracking
 * loads for.
 */
@NotThreadSafe
final class LoadStatsStoreImpl implements LoadStatsStore {
  private final String clusterName;
  @Nullable
  @SuppressWarnings("unused")
  private final String clusterServiceName;
  private final ConcurrentMap<Locality, ClientLoadCounter> localityLoadCounters;
  // Cluster level dropped request counts for each category decision made by xDS load balancer.
  private final ConcurrentMap<String, AtomicLong> dropCounters;

  LoadStatsStoreImpl(String clusterName, @Nullable String clusterServiceName) {
    this(clusterName, clusterServiceName, new ConcurrentHashMap<Locality, ClientLoadCounter>(),
        new ConcurrentHashMap<String, AtomicLong>());
  }

  @VisibleForTesting
  LoadStatsStoreImpl(
      String clusterName,
      @Nullable String clusterServiceName,
      ConcurrentMap<Locality, ClientLoadCounter> localityLoadCounters,
      ConcurrentMap<String, AtomicLong> dropCounters) {
    this.clusterName = checkNotNull(clusterName, "clusterName");
    this.clusterServiceName = clusterServiceName;
    this.localityLoadCounters = checkNotNull(localityLoadCounters, "localityLoadCounters");
    this.dropCounters = checkNotNull(dropCounters, "dropCounters");
  }

  @Override
  public ClusterStats generateLoadReport() {
    ClusterStats.Builder statsBuilder = ClusterStats.newBuilder();
    statsBuilder.setClusterName(clusterName);
    // TODO(chengyuangzhang): also set cluster_service_name if provided.
    for (Map.Entry<Locality, ClientLoadCounter> entry : localityLoadCounters.entrySet()) {
      ClientLoadSnapshot snapshot = entry.getValue().snapshot();
      UpstreamLocalityStats.Builder localityStatsBuilder =
          UpstreamLocalityStats.newBuilder().setLocality(entry.getKey().toEnvoyProtoLocality());
      localityStatsBuilder
          .setTotalSuccessfulRequests(snapshot.getCallsSucceeded())
          .setTotalErrorRequests(snapshot.getCallsFailed())
          .setTotalRequestsInProgress(snapshot.getCallsInProgress())
          .setTotalIssuedRequests(snapshot.getCallsIssued());
      for (Map.Entry<String, MetricValue> metric : snapshot.getMetricValues().entrySet()) {
        localityStatsBuilder.addLoadMetricStats(
            EndpointLoadMetricStats.newBuilder()
                .setMetricName(metric.getKey())
                .setNumRequestsFinishedWithMetric(metric.getValue().getNumReports())
                .setTotalMetricValue(metric.getValue().getTotalValue()));
      }
      statsBuilder.addUpstreamLocalityStats(localityStatsBuilder);
      // Discard counters for localities that are no longer exposed by the remote balancer and
      // no RPCs ongoing.
      if (!entry.getValue().isActive() && snapshot.getCallsInProgress() == 0) {
        localityLoadCounters.remove(entry.getKey());
      }
    }
    long totalDrops = 0;
    for (Map.Entry<String, AtomicLong> entry : dropCounters.entrySet()) {
      long drops = entry.getValue().getAndSet(0);
      totalDrops += drops;
      statsBuilder.addDroppedRequests(DroppedRequests.newBuilder()
          .setCategory(entry.getKey())
          .setDroppedCount(drops));
    }
    statsBuilder.setTotalDroppedRequests(totalDrops);
    return statsBuilder.build();
  }

  @Override
  public void addLocality(final Locality locality) {
    ClientLoadCounter counter = localityLoadCounters.get(locality);
    checkState(counter == null || !counter.isActive(),
        "An active counter for locality %s already exists", locality);
    if (counter == null) {
      localityLoadCounters.put(locality, new ClientLoadCounter());
    } else {
      counter.setActive(true);
    }
  }

  @Override
  public void removeLocality(final Locality locality) {
    ClientLoadCounter counter = localityLoadCounters.get(locality);
    checkState(counter != null && counter.isActive(),
        "No active counter for locality %s exists", locality);
    counter.setActive(false);
  }

  @Override
  public ClientLoadCounter getLocalityCounter(final Locality locality) {
    return localityLoadCounters.get(locality);
  }

  @Override
  public void recordDroppedRequest(String category) {
    AtomicLong counter = dropCounters.get(category);
    if (counter == null) {
      counter = dropCounters.putIfAbsent(category, new AtomicLong());
      if (counter == null) {
        counter = dropCounters.get(category);
      }
    }
    counter.getAndIncrement();
  }
}
