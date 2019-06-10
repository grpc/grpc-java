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
import io.envoyproxy.envoy.api.v2.core.Locality;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats.DroppedRequests;
import io.envoyproxy.envoy.api.v2.endpoint.EndpointLoadMetricStats;
import io.envoyproxy.envoy.api.v2.endpoint.UpstreamLocalityStats;
import io.grpc.Status;
import io.grpc.xds.ClientLoadCounter.ClientLoadSnapshot;
import io.grpc.xds.ClientLoadCounter.MetricValue;
import io.grpc.xds.XdsLoadReportClientImpl.StatsStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * An {@link XdsLoadStatsStore} instance holds the client side load stats for a cluster.
 */
@NotThreadSafe
final class XdsLoadStatsStore implements StatsStore {

  private final String clusterName;
  private final ConcurrentMap<Locality, StatsCounter> localityLoadCounters;
  // Cluster level dropped request counts for each category specified in the DropOverload policy.
  private final ConcurrentMap<String, AtomicLong> dropCounters;

  XdsLoadStatsStore(String clusterName) {
    this(clusterName, new ConcurrentHashMap<Locality, StatsCounter>(),
        new ConcurrentHashMap<String, AtomicLong>());
  }

  @VisibleForTesting
  XdsLoadStatsStore(String clusterName,
      ConcurrentMap<Locality, StatsCounter> localityLoadCounters,
      ConcurrentMap<String, AtomicLong> dropCounters) {
    this.clusterName = checkNotNull(clusterName, "clusterName");
    this.localityLoadCounters = checkNotNull(localityLoadCounters, "localityLoadCounters");
    this.dropCounters = checkNotNull(dropCounters, "dropCounters");
  }

  /**
   * Generates a {@link ClusterStats} containing client side load stats and backend metrics
   * (if any) in locality granularity.
   * This method should be called in the same synchronized context that
   * {@link XdsLoadBalancer#helper#getSynchronizationContext} returns.
   */
  @Override
  public ClusterStats generateLoadReport() {
    ClusterStats.Builder statsBuilder = ClusterStats.newBuilder().setClusterName(clusterName);
    for (Map.Entry<Locality, StatsCounter> entry : localityLoadCounters.entrySet()) {
      ClientLoadSnapshot snapshot = entry.getValue().snapshot();
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

  /**
   * Create a {@link ClientLoadCounter} for the provided locality or make it active if already in
   * this {@link XdsLoadStatsStore}. This method needs to be called at locality updates only for
   * newly assigned localities in balancer discovery responses.
   * This method should be called in the same synchronized context that
   * {@link XdsLoadBalancer#helper#getSynchronizationContext} returns.
   */
  @Override
  public void addLocality(final Locality locality) {
    StatsCounter counter = localityLoadCounters.get(locality);
    checkState(counter == null || !counter.isActive(),
        "An active counter for locality %s already exists", locality);
    if (counter == null) {
      localityLoadCounters.put(locality, new ClientLoadCounter());
    } else {
      counter.setActive(true);
    }
  }

  /**
   * Deactivate the {@link StatsCounter} for the provided locality in by this
   * {@link XdsLoadStatsStore}. Inactive {@link StatsCounter}s are for localities
   * no longer exposed by the remote balancer. This method needs to be called at
   * locality updates only for localities newly removed from balancer discovery responses.
   * This method should be called in the same synchronized context that
   * {@link XdsLoadBalancer#helper#getSynchronizationContext} returns.
   */
  @Override
  public void removeLocality(final Locality locality) {
    StatsCounter counter = localityLoadCounters.get(locality);
    checkState(counter != null && counter.isActive(),
        "No active counter for locality %s exists", locality);
    counter.setActive(false);
  }

  /**
   * Returns the {@link StatsCounter} instance that is responsible for aggregating load
   * stats for the provided locality, or {@code null} if the locality is untracked.
   */
  @Override
  public StatsCounter getLocalityCounter(final Locality locality) {
    return localityLoadCounters.get(locality);
  }

  /**
   * Record that a request has been dropped by drop overload policy with the provided category
   * instructed by the remote balancer.
   */
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

  /**
   * Blueprint for counters that can can record number of calls in-progress, succeeded, failed,
   * issued and backend metrics.
   */
  abstract static class StatsCounter {

    private boolean active = true;

    abstract void recordCallStarted();

    abstract void recordCallFinished(Status status);

    abstract void recordMetric(String name, double value);

    abstract ClientLoadSnapshot snapshot();

    boolean isActive() {
      return active;
    }

    void setActive(boolean value) {
      active = value;
    }
  }
}
