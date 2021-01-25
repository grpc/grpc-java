/*
 * Copyright 2021 The gRPC Authors
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

import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.collect.Sets;
import io.grpc.xds.ClientLoadCounter.ClientLoadSnapshot;
import io.grpc.xds.ClientLoadCounter.MetricValue;
import io.grpc.xds.DropStatsCounter.DropStatsSnapshot;
import io.grpc.xds.EnvoyProtoData.ClusterStats;
import io.grpc.xds.EnvoyProtoData.ClusterStats.DroppedRequests;
import io.grpc.xds.EnvoyProtoData.EndpointLoadMetricStats;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.UpstreamLocalityStats;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Manages client side traffic stats. Drop stats are maintained in cluster (with edsServiceName)
 * granularity and load stats (request counts and backend metrics) are maintained in locality
 * granularity.
 */
@ThreadSafe
final class LoadStatsManager2 {
  // Recorders for drops of each cluster:edsServiceName.
  private final Map<String, Map<String, ReferenceCounted<DropStatsCounter>>> dropStats =
      new HashMap<>();
  // Recorders for loads of each cluster:edsServiceName:locality.
  private final Map<String, Map<String,
      Map<Locality, ReferenceCounted<ClientLoadCounter>>>> loadStats = new HashMap<>();
  private final Supplier<Stopwatch> stopwatchSupplier;

  LoadStatsManager2(Supplier<Stopwatch> stopwatchSupplier) {
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
  }

  /**
   * Gets the counter for recording drops for the specified cluster with edsServiceName.
   * The returned counter is reference counted and the caller should use {@link
   * #releaseDropCounter} to release its <i>hard</i> reference when it is safe to discard the
   * counter in the future.
   */
  synchronized DropStatsCounter getDropCounter(String cluster, @Nullable String edsServiceName) {
    if (!dropStats.containsKey(cluster)) {
      dropStats.put(cluster, new HashMap<String, ReferenceCounted<DropStatsCounter>>());
    }
    Map<String, ReferenceCounted<DropStatsCounter>> clusterDropStats = dropStats.get(cluster);
    if (!clusterDropStats.containsKey(edsServiceName)) {
      clusterDropStats.put(
          edsServiceName, ReferenceCounted.wrap(new DropStatsCounter(stopwatchSupplier)));
    }
    ReferenceCounted<DropStatsCounter> ref = clusterDropStats.get(edsServiceName);
    ref.retain();
    return ref.get();
  }

  /**
   * Release the <i>hard</i> reference for the counter previously obtained from {@link
   * #getDropCounter} for the specified cluster with edsServiceName. The counter released may
   * still be recording drops after this method, but there is no guarantee drops recorded after
   * this point will be included in load reports.
   */
  synchronized void releaseDropCounter(String cluster, @Nullable String edsServiceName) {
    checkState(dropStats.containsKey(cluster)
            && dropStats.get(cluster).containsKey(edsServiceName),
        "stats for cluster %s, edsServiceName %s not exits", cluster, edsServiceName);
    ReferenceCounted<DropStatsCounter> ref = dropStats.get(cluster).get(edsServiceName);
    ref.release();
  }

  /**
   * Gets the counter for recording loads for the specified locality (in the specified cluster
   * with edsServiceName). The returned counter is reference counted and the caller should use
   * {@link #releaseLoadCounter} to release its <i>hard</i> reference when it is safe to discard
   * the counter in the future.
   */
  synchronized ClientLoadCounter getLoadCounter(
      String cluster, @Nullable String edsServiceName, Locality locality) {
    if (!loadStats.containsKey(cluster)) {
      loadStats.put(
          cluster, new HashMap<String, Map<Locality, ReferenceCounted<ClientLoadCounter>>>());
    }
    Map<String, Map<Locality, ReferenceCounted<ClientLoadCounter>>> clusterLoadStats =
        loadStats.get(cluster);
    if (!clusterLoadStats.containsKey(edsServiceName)) {
      clusterLoadStats.put(
          edsServiceName, new HashMap<Locality, ReferenceCounted<ClientLoadCounter>>());
    }
    Map<Locality, ReferenceCounted<ClientLoadCounter>> localityLoadStats =
        clusterLoadStats.get(edsServiceName);
    if (!localityLoadStats.containsKey(locality)) {
      localityLoadStats.put(
          locality, ReferenceCounted.wrap(new ClientLoadCounter(stopwatchSupplier)));
    }
    ReferenceCounted<ClientLoadCounter> ref = localityLoadStats.get(locality);
    ref.retain();
    return ref.get();
  }

  /**
   * Release the <i>hard</i> reference for the counter previously obtained from {@link
   * #getLoadCounter} for the specified locality. The counter released may still be recording
   * loads after this method, but there is no guarantee loads recorded after this point will be
   * included in load reports.
   */
  synchronized void releaseLoadCounter(
      String cluster, @Nullable String edsServiceName, Locality locality) {
    checkState(loadStats.containsKey(cluster)
            && loadStats.get(cluster).containsKey(edsServiceName)
            && loadStats.get(cluster).get(edsServiceName).containsKey(locality),
        "stats for cluster %s, edsServiceName %s, locality %s not exits",
        cluster, edsServiceName, locality);
    ReferenceCounted<ClientLoadCounter> ref =
        loadStats.get(cluster).get(edsServiceName).get(locality);
    ref.release();
  }

  /**
   * Gets the traffic stats (drops and loads) as a list of {@link ClusterStats} recorded for the
   * specified cluster since the previous call of this method or {@link
   * #getAllClusterStatsReports}. A {@link ClusterStats} includes stats for a specific cluster with
   * edsServiceName.
   */
  synchronized List<ClusterStats> getClusterStatsReports(String cluster) {
    if (!dropStats.containsKey(cluster) && !loadStats.containsKey(cluster)) {
      return Collections.emptyList();
    }
    Map<String, ReferenceCounted<DropStatsCounter>> clusterDropStats = dropStats.get(cluster);
    Map<String, Map<Locality, ReferenceCounted<ClientLoadCounter>>> clusterLoadStats =
        loadStats.get(cluster);
    Map<String, ClusterStats.Builder> statsReportBuilders = new HashMap<>();
    if (clusterDropStats != null) {
      Set<String> toDiscard = new HashSet<>();
      for (String edsServiceName : clusterDropStats.keySet()) {
        ClusterStats.Builder builder = ClusterStats.newBuilder().setClusterName(cluster);
        if (edsServiceName != null) {
          builder.setClusterServiceName(edsServiceName);
        }
        ReferenceCounted<DropStatsCounter> ref = clusterDropStats.get(edsServiceName);
        if (ref.getReferenceCount() == 0) {  // counter no longer needed after snapshot
          toDiscard.add(edsServiceName);
        }
        DropStatsSnapshot dropStatsSnapshot = ref.get().snapshot();
        long totalCategorizedDrops = 0L;
        for (Map.Entry<String, Long> entry : dropStatsSnapshot.getCategorizedDrops().entrySet()) {
          builder.addDroppedRequests(new DroppedRequests(entry.getKey(), entry.getValue()));
          totalCategorizedDrops += entry.getValue();
        }
        builder.setTotalDroppedRequests(
            totalCategorizedDrops + dropStatsSnapshot.getUncategorizedDrops());
        builder.setLoadReportIntervalNanos(dropStatsSnapshot.getDurationNano());
        statsReportBuilders.put(edsServiceName, builder);
      }
      clusterDropStats.keySet().removeAll(toDiscard);
    }
    if (clusterLoadStats != null) {
      Set<String> toDiscard = new HashSet<>();
      for (String edsServiceName : clusterLoadStats.keySet()) {
        ClusterStats.Builder builder = statsReportBuilders.get(edsServiceName);
        if (builder == null) {
          builder = ClusterStats.newBuilder().setClusterName(cluster);
          if (edsServiceName != null) {
            builder.setClusterServiceName(edsServiceName);
          }
          statsReportBuilders.put(edsServiceName, builder);
        }
        Map<Locality, ReferenceCounted<ClientLoadCounter>> localityStats =
            clusterLoadStats.get(edsServiceName);
        Set<Locality> localitiesToDiscard = new HashSet<>();
        for (Locality locality : localityStats.keySet()) {
          ReferenceCounted<ClientLoadCounter> ref = localityStats.get(locality);
          ClientLoadSnapshot snapshot = ref.get().snapshot();
          // may still want to report all in-flight requests
          if (ref.getReferenceCount() == 0 && snapshot.getCallsInProgress() == 0) {
            localitiesToDiscard.add(locality);
          }
          builder.addUpstreamLocalityStats(buildUpstreamLocalityStats(locality, snapshot));
          // Use the max (drops/loads) recording interval as the overall interval for the
          // cluster's stats.
          builder.setLoadReportIntervalNanos(
              Math.max(builder.getLoadReportIntervalNanos(), snapshot.getDurationNano()));
        }
        localityStats.keySet().removeAll(localitiesToDiscard);
        if (localityStats.isEmpty()) {
          toDiscard.add(edsServiceName);
        }
      }
      clusterLoadStats.keySet().removeAll(toDiscard);
    }
    List<ClusterStats> res = new ArrayList<>();
    for (ClusterStats.Builder builder : statsReportBuilders.values()) {
      res.add(builder.build());
    }
    return Collections.unmodifiableList(res);
  }

  /**
   * Gets the traffic stats (drops and loads) as a list of {@link ClusterStats} recorded for all
   * clusters since the previous call of this method or {@link #getClusterStatsReports} for each
   * specific cluster. A {@link ClusterStats} includes stats for a specific cluster with
   * edsServiceName.
   */
  synchronized List<ClusterStats> getAllClusterStatsReports() {
    Set<String> allClusters = Sets.union(dropStats.keySet(), loadStats.keySet());
    List<ClusterStats> res = new ArrayList<>();
    for (String cluster : allClusters) {
      res.addAll(getClusterStatsReports(cluster));
    }
    return Collections.unmodifiableList(res);
  }

  private static UpstreamLocalityStats buildUpstreamLocalityStats(
      Locality locality, ClientLoadSnapshot snapshot) {
    UpstreamLocalityStats.Builder builder = UpstreamLocalityStats.newBuilder();
    builder.setLocality(locality);
    builder.setTotalIssuedRequests(snapshot.getCallsIssued());
    builder.setTotalSuccessfulRequests(snapshot.getCallsSucceeded());
    builder.setTotalErrorRequests(snapshot.getCallsFailed());
    builder.setTotalRequestsInProgress(snapshot.getCallsInProgress());
    for (Map.Entry<String, MetricValue> metric : snapshot.getMetricValues().entrySet()) {
      EndpointLoadMetricStats metrics =
          EndpointLoadMetricStats.newBuilder()
              .setMetricName(metric.getKey())
              .setNumRequestsFinishedWithMetric(metric.getValue().getNumReports())
              .setTotalMetricValue(metric.getValue().getTotalValue())
              .build();
      builder.addLoadMetricStats(metrics);
    }
    return builder.build();
  }
}
