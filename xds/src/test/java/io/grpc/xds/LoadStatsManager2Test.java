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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import io.grpc.Status;
import io.grpc.internal.FakeClock;
import io.grpc.xds.LoadStatsManager2.ClusterDropStats;
import io.grpc.xds.LoadStatsManager2.ClusterLocalityStats;
import io.grpc.xds.Stats.ClusterStats;
import io.grpc.xds.Stats.DroppedRequests;
import io.grpc.xds.Stats.UpstreamLocalityStats;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link LoadStatsManager2}.
 */
@RunWith(JUnit4.class)
public class LoadStatsManager2Test {
  private static final double TOLERANCE = 1.0e-10;
  private static final String CLUSTER_NAME1 = "cluster-foo.googleapis.com";
  private static final String CLUSTER_NAME2 = "cluster-bar.googleapis.com";
  private static final String EDS_SERVICE_NAME1 = "backend-service-foo.googleapis.com";
  private static final String EDS_SERVICE_NAME2 = "backend-service-bar.googleapis.com";
  private static final Locality LOCALITY1 =
      Locality.create("test_region1", "test_zone1", "test_subzone1");
  private static final Locality LOCALITY2 =
      Locality.create("test_region2", "test_zone2", "test_subzone2");
  private static final Locality LOCALITY3 =
      Locality.create("test_region3", "test_zone3", "test_subzone3");

  private final FakeClock fakeClock = new FakeClock();
  private final LoadStatsManager2 loadStatsManager =
      new LoadStatsManager2(fakeClock.getStopwatchSupplier());

  @Test
  public void recordAndGetReport() {
    ClusterDropStats dropCounter1 = loadStatsManager.getClusterDropStats(
        CLUSTER_NAME1, EDS_SERVICE_NAME1);
    ClusterDropStats dropCounter2 = loadStatsManager.getClusterDropStats(
        CLUSTER_NAME1, EDS_SERVICE_NAME2);
    ClusterLocalityStats loadCounter1 = loadStatsManager.getClusterLocalityStats(
        CLUSTER_NAME1, EDS_SERVICE_NAME1, LOCALITY1);
    ClusterLocalityStats loadCounter2 = loadStatsManager.getClusterLocalityStats(
        CLUSTER_NAME1, EDS_SERVICE_NAME1, LOCALITY2);
    ClusterLocalityStats loadCounter3 = loadStatsManager.getClusterLocalityStats(
        CLUSTER_NAME2, null, LOCALITY3);
    dropCounter1.recordDroppedRequest("lb");
    dropCounter1.recordDroppedRequest("throttle");
    for (int i = 0; i < 19; i++) {
      loadCounter1.recordCallStarted();
    }
    loadCounter1.recordBackendLoadMetricStats(ImmutableMap.of("named1", 3.14159));
    loadCounter1.recordBackendLoadMetricStats(ImmutableMap.of("named1", 1.618));
    loadCounter1.recordBackendLoadMetricStats(ImmutableMap.of("named1", 99.0));
    loadCounter1.recordBackendLoadMetricStats(ImmutableMap.of("named1", -97.23, "named2", -2.718));
    fakeClock.forwardTime(5L, TimeUnit.SECONDS);
    dropCounter2.recordDroppedRequest();
    loadCounter1.recordCallFinished(Status.OK);
    for (int i = 0; i < 9; i++) {
      loadCounter2.recordCallStarted();
    }
    loadCounter2.recordBackendLoadMetricStats(ImmutableMap.of("named3", 0.0009));
    loadCounter2.recordCallFinished(Status.UNAVAILABLE);
    fakeClock.forwardTime(10L, TimeUnit.SECONDS);
    loadCounter3.recordCallStarted();
    List<ClusterStats> allStats = loadStatsManager.getAllClusterStatsReports();
    assertThat(allStats).hasSize(3);  // three cluster:edsServiceName

    ClusterStats stats1 = findClusterStats(allStats, CLUSTER_NAME1, EDS_SERVICE_NAME1);
    assertThat(stats1.loadReportIntervalNano()).isEqualTo(TimeUnit.SECONDS.toNanos(5L + 10L));
    assertThat(stats1.droppedRequestsList()).hasSize(2);
    assertThat(findDroppedRequestCount(stats1.droppedRequestsList(), "lb")).isEqualTo(1L);
    assertThat(findDroppedRequestCount(stats1.droppedRequestsList(), "throttle")).isEqualTo(1L);
    assertThat(stats1.totalDroppedRequests()).isEqualTo(1L + 1L);
    assertThat(stats1.upstreamLocalityStatsList()).hasSize(2);  // two localities
    UpstreamLocalityStats loadStats1 =
        findLocalityStats(stats1.upstreamLocalityStatsList(), LOCALITY1);
    assertThat(loadStats1.totalIssuedRequests()).isEqualTo(19L);
    assertThat(loadStats1.totalSuccessfulRequests()).isEqualTo(1L);
    assertThat(loadStats1.totalErrorRequests()).isEqualTo(0L);
    assertThat(loadStats1.totalRequestsInProgress()).isEqualTo(19L - 1L);
    assertThat(loadStats1.loadMetricStatsMap().containsKey("named1")).isTrue();
    assertThat(loadStats1.loadMetricStatsMap().containsKey("named2")).isTrue();
    assertThat(
        loadStats1.loadMetricStatsMap().get("named1").numRequestsFinishedWithMetric()).isEqualTo(
        4L);
    assertThat(loadStats1.loadMetricStatsMap().get("named1").totalMetricValue()).isWithin(TOLERANCE)
        .of(3.14159 + 1.618 + 99 - 97.23);
    assertThat(
        loadStats1.loadMetricStatsMap().get("named2").numRequestsFinishedWithMetric()).isEqualTo(
        1L);
    assertThat(loadStats1.loadMetricStatsMap().get("named2").totalMetricValue()).isWithin(TOLERANCE)
        .of(-2.718);

    UpstreamLocalityStats loadStats2 =
        findLocalityStats(stats1.upstreamLocalityStatsList(), LOCALITY2);
    assertThat(loadStats2.totalIssuedRequests()).isEqualTo(9L);
    assertThat(loadStats2.totalSuccessfulRequests()).isEqualTo(0L);
    assertThat(loadStats2.totalErrorRequests()).isEqualTo(1L);
    assertThat(loadStats2.totalRequestsInProgress()).isEqualTo(9L - 1L);
    assertThat(loadStats2.loadMetricStatsMap().containsKey("named3")).isTrue();
    assertThat(
        loadStats2.loadMetricStatsMap().get("named3").numRequestsFinishedWithMetric()).isEqualTo(
        1L);
    assertThat(loadStats2.loadMetricStatsMap().get("named3").totalMetricValue()).isWithin(TOLERANCE)
        .of(0.0009);

    ClusterStats stats2 = findClusterStats(allStats, CLUSTER_NAME1, EDS_SERVICE_NAME2);
    assertThat(stats2.loadReportIntervalNano()).isEqualTo(TimeUnit.SECONDS.toNanos(5L + 10L));
    assertThat(stats2.droppedRequestsList()).isEmpty();  // no categorized drops
    assertThat(stats2.totalDroppedRequests()).isEqualTo(1L);
    assertThat(stats2.upstreamLocalityStatsList()).isEmpty();  // no per-locality stats

    ClusterStats stats3 = findClusterStats(allStats, CLUSTER_NAME2, null);
    assertThat(stats3.loadReportIntervalNano()).isEqualTo(TimeUnit.SECONDS.toNanos(5L + 10L));
    assertThat(stats3.droppedRequestsList()).isEmpty();
    assertThat(stats3.totalDroppedRequests()).isEqualTo(0L);  // no drops recorded
    assertThat(stats3.upstreamLocalityStatsList()).hasSize(1);  // one localities
    UpstreamLocalityStats loadStats3 =
        Iterables.getOnlyElement(stats3.upstreamLocalityStatsList());
    assertThat(loadStats3.totalIssuedRequests()).isEqualTo(1L);
    assertThat(loadStats3.totalSuccessfulRequests()).isEqualTo(0L);
    assertThat(loadStats3.totalErrorRequests()).isEqualTo(0L);
    assertThat(loadStats3.totalRequestsInProgress()).isEqualTo(1L);
    assertThat(loadStats3.loadMetricStatsMap()).isEmpty();

    fakeClock.forwardTime(3L, TimeUnit.SECONDS);
    List<ClusterStats> clusterStatsList = loadStatsManager.getClusterStatsReports(CLUSTER_NAME1);
    assertThat(clusterStatsList).hasSize(2);
    stats1 = findClusterStats(clusterStatsList, CLUSTER_NAME1, EDS_SERVICE_NAME1);
    assertThat(stats1.loadReportIntervalNano()).isEqualTo(TimeUnit.SECONDS.toNanos(3L));
    assertThat(stats1.droppedRequestsList()).isEmpty();
    assertThat(stats1.totalDroppedRequests()).isEqualTo(0L);  // no new drops recorded
    assertThat(stats1.upstreamLocalityStatsList()).hasSize(2);  // two localities
    loadStats1 = findLocalityStats(stats1.upstreamLocalityStatsList(), LOCALITY1);
    assertThat(loadStats1.totalIssuedRequests()).isEqualTo(0L);
    assertThat(loadStats1.totalSuccessfulRequests()).isEqualTo(0L);
    assertThat(loadStats1.totalErrorRequests()).isEqualTo(0L);
    assertThat(loadStats1.totalRequestsInProgress()).isEqualTo(18L);  // still in-progress
    assertThat(loadStats1.loadMetricStatsMap()).isEmpty();
    loadStats2 = findLocalityStats(stats1.upstreamLocalityStatsList(), LOCALITY2);
    assertThat(loadStats2.totalIssuedRequests()).isEqualTo(0L);
    assertThat(loadStats2.totalSuccessfulRequests()).isEqualTo(0L);
    assertThat(loadStats2.totalErrorRequests()).isEqualTo(0L);
    assertThat(loadStats2.totalRequestsInProgress()).isEqualTo(8L);  // still in-progress
    assertThat(loadStats2.loadMetricStatsMap()).isEmpty();

    stats2 = findClusterStats(clusterStatsList, CLUSTER_NAME1, EDS_SERVICE_NAME2);
    assertThat(stats2.loadReportIntervalNano()).isEqualTo(TimeUnit.SECONDS.toNanos(3L));
    assertThat(stats2.droppedRequestsList()).isEmpty();
    assertThat(stats2.totalDroppedRequests()).isEqualTo(0L);  // no new drops recorded
    assertThat(stats2.upstreamLocalityStatsList()).isEmpty();  // no per-locality stats
  }

  @Test
  public void sharedDropCounterStatsAggregation() {
    ClusterDropStats ref1 = loadStatsManager.getClusterDropStats(
        CLUSTER_NAME1, EDS_SERVICE_NAME1);
    ClusterDropStats ref2 = loadStatsManager.getClusterDropStats(
        CLUSTER_NAME1, EDS_SERVICE_NAME1);
    ref1.recordDroppedRequest("lb");
    ref2.recordDroppedRequest("throttle");
    ref1.recordDroppedRequest();
    ref2.recordDroppedRequest();

    ClusterStats stats = Iterables.getOnlyElement(
        loadStatsManager.getClusterStatsReports(CLUSTER_NAME1));
    assertThat(stats.droppedRequestsList()).hasSize(2);
    assertThat(findDroppedRequestCount(stats.droppedRequestsList(), "lb")).isEqualTo(1L);
    assertThat(findDroppedRequestCount(stats.droppedRequestsList(), "throttle")).isEqualTo(1L);
    assertThat(stats.totalDroppedRequests()).isEqualTo(4L);  // 2 cagetorized + 2 uncategoized
  }

  @Test
  public void dropCounterDelayedDeletionAfterReported() {
    ClusterDropStats counter = loadStatsManager.getClusterDropStats(
        CLUSTER_NAME1, EDS_SERVICE_NAME1);
    counter.recordDroppedRequest("lb");
    ClusterStats stats = Iterables.getOnlyElement(
        loadStatsManager.getClusterStatsReports(CLUSTER_NAME1));
    assertThat(stats.droppedRequestsList()).hasSize(1);
    assertThat(Iterables.getOnlyElement(stats.droppedRequestsList()).droppedCount())
        .isEqualTo(1L);
    assertThat(stats.totalDroppedRequests()).isEqualTo(1L);

    counter.release();
    stats = Iterables.getOnlyElement(loadStatsManager.getClusterStatsReports(CLUSTER_NAME1));
    assertThat(stats.droppedRequestsList()).isEmpty();
    assertThat(stats.totalDroppedRequests()).isEqualTo(0L);

    assertThat(loadStatsManager.getClusterStatsReports(CLUSTER_NAME1)).isEmpty();
  }

  @Test
  public void sharedLoadCounterStatsAggregation() {
    ClusterLocalityStats ref1 = loadStatsManager.getClusterLocalityStats(
        CLUSTER_NAME1, EDS_SERVICE_NAME1, LOCALITY1);
    ClusterLocalityStats ref2 = loadStatsManager.getClusterLocalityStats(
        CLUSTER_NAME1, EDS_SERVICE_NAME1, LOCALITY1);
    ref1.recordCallStarted();
    ref1.recordBackendLoadMetricStats(ImmutableMap.of("named1", 1.618));
    ref1.recordBackendLoadMetricStats(ImmutableMap.of("named1", 3.14159));
    ref1.recordCallFinished(Status.OK);
    ref2.recordCallStarted();
    ref2.recordCallStarted();
    ref2.recordBackendLoadMetricStats(ImmutableMap.of("named1", -1.0, "named2", 2.718));
    ref2.recordCallFinished(Status.UNAVAILABLE);

    ClusterStats stats = Iterables.getOnlyElement(
        loadStatsManager.getClusterStatsReports(CLUSTER_NAME1));
    UpstreamLocalityStats localityStats =
        Iterables.getOnlyElement(stats.upstreamLocalityStatsList());
    assertThat(localityStats.totalIssuedRequests()).isEqualTo(1L + 2L);
    assertThat(localityStats.totalSuccessfulRequests()).isEqualTo(1L);
    assertThat(localityStats.totalErrorRequests()).isEqualTo(1L);
    assertThat(localityStats.totalRequestsInProgress()).isEqualTo(1L + 2L - 1L - 1L);
    assertThat(localityStats.loadMetricStatsMap().containsKey("named1")).isTrue();
    assertThat(localityStats.loadMetricStatsMap().containsKey("named2")).isTrue();
    assertThat(
        localityStats.loadMetricStatsMap().get("named1").numRequestsFinishedWithMetric()).isEqualTo(
        3L);
    assertThat(localityStats.loadMetricStatsMap().get("named1").totalMetricValue()).isWithin(
        TOLERANCE).of(1.618 + 3.14159 - 1);
    assertThat(
        localityStats.loadMetricStatsMap().get("named2").numRequestsFinishedWithMetric()).isEqualTo(
        1L);
    assertThat(localityStats.loadMetricStatsMap().get("named2").totalMetricValue()).isEqualTo(
        2.718);
  }

  @Test
  public void loadCounterDelayedDeletionAfterAllInProgressRequestsReported() {
    ClusterLocalityStats counter = loadStatsManager.getClusterLocalityStats(
        CLUSTER_NAME1, EDS_SERVICE_NAME1, LOCALITY1);
    counter.recordCallStarted();
    counter.recordCallStarted();
    counter.recordBackendLoadMetricStats(ImmutableMap.of("named1", 2.718));
    counter.recordBackendLoadMetricStats(ImmutableMap.of("named1", 1.414));

    ClusterStats stats = Iterables.getOnlyElement(
        loadStatsManager.getClusterStatsReports(CLUSTER_NAME1));
    UpstreamLocalityStats localityStats =
        Iterables.getOnlyElement(stats.upstreamLocalityStatsList());
    assertThat(localityStats.totalIssuedRequests()).isEqualTo(2L);
    assertThat(localityStats.totalSuccessfulRequests()).isEqualTo(0L);
    assertThat(localityStats.totalErrorRequests()).isEqualTo(0L);
    assertThat(localityStats.totalRequestsInProgress()).isEqualTo(2L);
    assertThat(localityStats.loadMetricStatsMap().containsKey("named1")).isTrue();
    assertThat(
        localityStats.loadMetricStatsMap().get("named1").numRequestsFinishedWithMetric()).isEqualTo(
        2L);
    assertThat(localityStats.loadMetricStatsMap().get("named1").totalMetricValue()).isEqualTo(
        2.718 + 1.414);

    // release the counter, but requests still in-flight
    counter.release();
    stats = Iterables.getOnlyElement(loadStatsManager.getClusterStatsReports(CLUSTER_NAME1));
    localityStats = Iterables.getOnlyElement(stats.upstreamLocalityStatsList());
    assertThat(localityStats.totalIssuedRequests()).isEqualTo(0L);
    assertThat(localityStats.totalSuccessfulRequests()).isEqualTo(0L);
    assertThat(localityStats.totalErrorRequests()).isEqualTo(0L);
    assertThat(localityStats.totalRequestsInProgress())
        .isEqualTo(2L);  // retained by in-flight calls
    assertThat(localityStats.loadMetricStatsMap().isEmpty()).isTrue();

    counter.recordCallFinished(Status.OK);
    counter.recordCallFinished(Status.UNAVAILABLE);
    stats = Iterables.getOnlyElement(loadStatsManager.getClusterStatsReports(CLUSTER_NAME1));
    localityStats = Iterables.getOnlyElement(stats.upstreamLocalityStatsList());
    assertThat(localityStats.totalIssuedRequests()).isEqualTo(0L);
    assertThat(localityStats.totalSuccessfulRequests()).isEqualTo(1L);
    assertThat(localityStats.totalErrorRequests()).isEqualTo(1L);
    assertThat(localityStats.totalRequestsInProgress()).isEqualTo(0L);
    assertThat(localityStats.loadMetricStatsMap().isEmpty()).isTrue();

    assertThat(loadStatsManager.getClusterStatsReports(CLUSTER_NAME1)).isEmpty();
  }

  @Nullable
  private static ClusterStats findClusterStats(
      List<ClusterStats> statsList, String cluster, @Nullable String edsServiceName) {
    for (ClusterStats stats : statsList) {
      if (stats.clusterName().equals(cluster)
          && Objects.equals(stats.clusterServiceName(), edsServiceName)) {
        return stats;
      }
    }
    return null;
  }

  @Nullable
  private static UpstreamLocalityStats findLocalityStats(
      List<UpstreamLocalityStats> localityStatsList, Locality locality) {
    for (UpstreamLocalityStats stats : localityStatsList) {
      if (stats.locality().equals(locality)) {
        return stats;
      }
    }
    return null;
  }

  private static long findDroppedRequestCount(
      List<DroppedRequests> droppedRequestsLists, String category) {
    DroppedRequests drop = null;
    for (DroppedRequests stats : droppedRequestsLists) {
      if (stats.category().equals(category)) {
        drop = stats;
      }
    }
    assertThat(drop).isNotNull();
    return drop.droppedCount();
  }
}
