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
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import io.grpc.internal.GrpcUtil;
import io.grpc.xds.ClientLoadCounter.ClientLoadSnapshot;
import io.grpc.xds.ClientLoadCounter.MetricValue;
import io.grpc.xds.EnvoyProtoData.ClusterStats;
import io.grpc.xds.EnvoyProtoData.ClusterStats.DroppedRequests;
import io.grpc.xds.EnvoyProtoData.EndpointLoadMetricStats;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.UpstreamLocalityStats;
import io.grpc.xds.LoadStatsManager.LoadStatsStore;
import io.grpc.xds.LoadStatsManager.LoadStatsStoreFactory;
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
// https://github.com/google/error-prone/issues/1767
@SuppressWarnings("ModifyCollectionInEnhancedForLoop")
@NotThreadSafe
final class LoadStatsStoreImpl implements LoadStatsStore {
  private final String clusterName;
  @Nullable
  private final String clusterServiceName;
  private final ConcurrentMap<Locality, ReferenceCounted<ClientLoadCounter>> localityLoadCounters
      = new ConcurrentHashMap<>();
  // Cluster level dropped request counts for each category decision made by xDS load balancer.
  private final ConcurrentMap<String, AtomicLong> dropCounters;
  private final Stopwatch stopwatch;

  LoadStatsStoreImpl(String clusterName, @Nullable String clusterServiceName) {
    this(clusterName, clusterServiceName, GrpcUtil.STOPWATCH_SUPPLIER.get(),
        new ConcurrentHashMap<String, AtomicLong>());
  }

  @VisibleForTesting
  LoadStatsStoreImpl(
      String clusterName,
      @Nullable String clusterServiceName,
      Stopwatch stopwatch,
      ConcurrentMap<String, AtomicLong> dropCounters) {
    this.clusterName = checkNotNull(clusterName, "clusterName");
    this.clusterServiceName = clusterServiceName;
    this.stopwatch =  checkNotNull(stopwatch, "stopwatch");
    this.dropCounters = checkNotNull(dropCounters, "dropCounters");
    stopwatch.reset().start();
  }

  @Override
  public ClusterStats generateLoadReport() {
    ClusterStats.Builder statsBuilder = ClusterStats.newBuilder();
    statsBuilder.setClusterName(clusterName);
    if (clusterServiceName != null) {
      statsBuilder.setClusterServiceName(clusterServiceName);
    }
    for (Map.Entry<Locality, ReferenceCounted<ClientLoadCounter>> entry
        : localityLoadCounters.entrySet()) {
      ClientLoadSnapshot snapshot = entry.getValue().get().snapshot();
      UpstreamLocalityStats.Builder localityStatsBuilder =
          UpstreamLocalityStats.newBuilder().setLocality(entry.getKey());
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
                .setTotalMetricValue(metric.getValue().getTotalValue())
                .build());
      }
      statsBuilder.addUpstreamLocalityStats(localityStatsBuilder.build());
      // Discard counters for localities that are no longer exposed by the remote balancer and
      // no RPCs ongoing.
      if (entry.getValue().getReferenceCount() == 0 && snapshot.getCallsInProgress() == 0) {
        localityLoadCounters.remove(entry.getKey());
      }
    }
    long totalDrops = 0;
    for (Map.Entry<String, AtomicLong> entry : dropCounters.entrySet()) {
      long drops = entry.getValue().getAndSet(0);
      totalDrops += drops;
      statsBuilder.addDroppedRequests(new DroppedRequests(entry.getKey(),drops));
    }
    statsBuilder.setTotalDroppedRequests(totalDrops);
    statsBuilder.setLoadReportIntervalNanos(stopwatch.elapsed(NANOSECONDS));
    stopwatch.reset().start();
    return statsBuilder.build();
  }

  @Override
  public ClientLoadCounter addLocality(final Locality locality) {
    ReferenceCounted<ClientLoadCounter> counter = localityLoadCounters.get(locality);
    if (counter == null) {
      counter = ReferenceCounted.wrap(new ClientLoadCounter());
      localityLoadCounters.put(locality, counter);
    }
    counter.retain();
    return counter.get();
  }

  @Override
  public void removeLocality(final Locality locality) {
    ReferenceCounted<ClientLoadCounter> counter = localityLoadCounters.get(locality);
    counter.release();
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

  static LoadStatsStoreFactory getDefaultFactory() {
    return new LoadStatsStoreFactory() {
      @Override
      public LoadStatsStore newLoadStatsStore(String cluster, String clusterService) {
        return new LoadStatsStoreImpl(cluster, clusterService);
      }
    };
  }
}
