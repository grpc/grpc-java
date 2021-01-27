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

import com.google.common.collect.Iterables;
import io.grpc.Status;
import io.grpc.internal.FakeClock;
import io.grpc.xds.EnvoyProtoData.ClusterStats;
import io.grpc.xds.EnvoyProtoData.ClusterStats.DroppedRequests;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.UpstreamLocalityStats;
import io.grpc.xds.LoadStatsManager2.ClusterDropStats;
import io.grpc.xds.LoadStatsManager2.ClusterLocalityStats;
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
  private static final String CLUSTER_NAME1 = "cluster-foo.googleapis.com";
  private static final String CLUSTER_NAME2 = "cluster-bar.googleapis.com";
  private static final String EDS_SERVICE_NAME1 = "backend-service-foo.googleapis.com";
  private static final String EDS_SERVICE_NAME2 = "backend-service-bar.googleapis.com";
  private static final Locality LOCALITY1 =
      new Locality("test_region1", "test_zone1", "test_subzone1");
  private static final Locality LOCALITY2 =
      new Locality("test_region2", "test_zone2", "test_subzone2");
  private static final Locality LOCALITY3 =
      new Locality("test_region3", "test_zone3", "test_subzone3");

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
    fakeClock.forwardTime(5L, TimeUnit.SECONDS);
    dropCounter2.recordDroppedRequest();
    loadCounter1.recordCallFinished(Status.OK);
    for (int i = 0; i < 9; i++) {
      loadCounter2.recordCallStarted();
    }
    loadCounter2.recordCallFinished(Status.UNAVAILABLE);
    fakeClock.forwardTime(10L, TimeUnit.SECONDS);
    loadCounter3.recordCallStarted();
    List<ClusterStats> allStats = loadStatsManager.getAllClusterStatsReports();
    assertThat(allStats).hasSize(3);  // three cluster:edsServiceName

    ClusterStats stats1 = findClusterStats(allStats, CLUSTER_NAME1, EDS_SERVICE_NAME1);
    assertThat(stats1.getLoadReportIntervalNanos()).isEqualTo(TimeUnit.SECONDS.toNanos(5L + 10L));
    assertThat(stats1.getDroppedRequestsList()).hasSize(2);
    assertThat(findDroppedRequestCount(stats1.getDroppedRequestsList(), "lb")).isEqualTo(1L);
    assertThat(findDroppedRequestCount(stats1.getDroppedRequestsList(), "throttle")).isEqualTo(1L);
    assertThat(stats1.getTotalDroppedRequests()).isEqualTo(1L + 1L);
    assertThat(stats1.getUpstreamLocalityStatsList()).hasSize(2);  // two localities
    UpstreamLocalityStats loadStats1 =
        findLocalityStats(stats1.getUpstreamLocalityStatsList(), LOCALITY1);
    assertThat(loadStats1.getTotalIssuedRequests()).isEqualTo(19L);
    assertThat(loadStats1.getTotalSuccessfulRequests()).isEqualTo(1L);
    assertThat(loadStats1.getTotalErrorRequests()).isEqualTo(0L);
    assertThat(loadStats1.getTotalRequestsInProgress()).isEqualTo(19L - 1L);

    UpstreamLocalityStats loadStats2 =
        findLocalityStats(stats1.getUpstreamLocalityStatsList(), LOCALITY2);
    assertThat(loadStats2.getTotalIssuedRequests()).isEqualTo(9L);
    assertThat(loadStats2.getTotalSuccessfulRequests()).isEqualTo(0L);
    assertThat(loadStats2.getTotalErrorRequests()).isEqualTo(1L);
    assertThat(loadStats2.getTotalRequestsInProgress()).isEqualTo(9L - 1L);

    ClusterStats stats2 = findClusterStats(allStats, CLUSTER_NAME1, EDS_SERVICE_NAME2);
    assertThat(stats2.getLoadReportIntervalNanos()).isEqualTo(TimeUnit.SECONDS.toNanos(5L + 10L));
    assertThat(stats2.getDroppedRequestsList()).isEmpty();  // no categorized drops
    assertThat(stats2.getTotalDroppedRequests()).isEqualTo(1L);
    assertThat(stats2.getUpstreamLocalityStatsList()).isEmpty();  // no per-locality stats

    ClusterStats stats3 = findClusterStats(allStats, CLUSTER_NAME2, null);
    assertThat(stats3.getLoadReportIntervalNanos()).isEqualTo(TimeUnit.SECONDS.toNanos(5L + 10L));
    assertThat(stats3.getDroppedRequestsList()).isEmpty();
    assertThat(stats3.getTotalDroppedRequests()).isEqualTo(0L);  // no drops recorded
    assertThat(stats3.getUpstreamLocalityStatsList()).hasSize(1);  // one localities
    UpstreamLocalityStats loadStats3 =
        Iterables.getOnlyElement(stats3.getUpstreamLocalityStatsList());
    assertThat(loadStats3.getTotalIssuedRequests()).isEqualTo(1L);
    assertThat(loadStats3.getTotalSuccessfulRequests()).isEqualTo(0L);
    assertThat(loadStats3.getTotalErrorRequests()).isEqualTo(0L);
    assertThat(loadStats3.getTotalRequestsInProgress()).isEqualTo(1L);

    fakeClock.forwardTime(3L, TimeUnit.SECONDS);
    List<ClusterStats> clusterStatsList = loadStatsManager.getClusterStatsReports(CLUSTER_NAME1);
    assertThat(clusterStatsList).hasSize(2);
    stats1 = findClusterStats(clusterStatsList, CLUSTER_NAME1, EDS_SERVICE_NAME1);
    assertThat(stats1.getLoadReportIntervalNanos()).isEqualTo(TimeUnit.SECONDS.toNanos(3L));
    assertThat(stats1.getDroppedRequestsList()).isEmpty();
    assertThat(stats1.getTotalDroppedRequests()).isEqualTo(0L);  // no new drops recorded
    assertThat(stats1.getUpstreamLocalityStatsList()).hasSize(2);  // two localities
    loadStats1 = findLocalityStats(stats1.getUpstreamLocalityStatsList(), LOCALITY1);
    assertThat(loadStats1.getTotalIssuedRequests()).isEqualTo(0L);
    assertThat(loadStats1.getTotalSuccessfulRequests()).isEqualTo(0L);
    assertThat(loadStats1.getTotalErrorRequests()).isEqualTo(0L);
    assertThat(loadStats1.getTotalRequestsInProgress()).isEqualTo(18L);  // still in-progress
    loadStats2 = findLocalityStats(stats1.getUpstreamLocalityStatsList(), LOCALITY2);
    assertThat(loadStats2.getTotalIssuedRequests()).isEqualTo(0L);
    assertThat(loadStats2.getTotalSuccessfulRequests()).isEqualTo(0L);
    assertThat(loadStats2.getTotalErrorRequests()).isEqualTo(0L);
    assertThat(loadStats2.getTotalRequestsInProgress()).isEqualTo(8L);  // still in-progress

    stats2 = findClusterStats(clusterStatsList, CLUSTER_NAME1, EDS_SERVICE_NAME2);
    assertThat(stats2.getLoadReportIntervalNanos()).isEqualTo(TimeUnit.SECONDS.toNanos(3L));
    assertThat(stats2.getDroppedRequestsList()).isEmpty();
    assertThat(stats2.getTotalDroppedRequests()).isEqualTo(0L);  // no new drops recorded
    assertThat(stats2.getUpstreamLocalityStatsList()).isEmpty();  // no per-locality stats
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
    assertThat(stats.getDroppedRequestsList()).hasSize(2);
    assertThat(findDroppedRequestCount(stats.getDroppedRequestsList(), "lb")).isEqualTo(1L);
    assertThat(findDroppedRequestCount(stats.getDroppedRequestsList(), "throttle")).isEqualTo(1L);
    assertThat(stats.getTotalDroppedRequests()).isEqualTo(4L);  // 2 cagetorized + 2 uncategoized
  }

  @Test
  public void dropCounterDelayedDeletionAfterReported() {
    ClusterDropStats counter = loadStatsManager.getClusterDropStats(
        CLUSTER_NAME1, EDS_SERVICE_NAME1);
    counter.recordDroppedRequest("lb");
    ClusterStats stats = Iterables.getOnlyElement(
        loadStatsManager.getClusterStatsReports(CLUSTER_NAME1));
    assertThat(stats.getDroppedRequestsList()).hasSize(1);
    assertThat(Iterables.getOnlyElement(stats.getDroppedRequestsList()).getDroppedCount())
        .isEqualTo(1L);
    assertThat(stats.getTotalDroppedRequests()).isEqualTo(1L);

    counter.release();
    stats = Iterables.getOnlyElement(loadStatsManager.getClusterStatsReports(CLUSTER_NAME1));
    assertThat(stats.getDroppedRequestsList()).isEmpty();
    assertThat(stats.getTotalDroppedRequests()).isEqualTo(0L);

    assertThat(loadStatsManager.getClusterStatsReports(CLUSTER_NAME1)).isEmpty();
  }

  @Test
  public void sharedLoadCounterStatsAggregation() {
    ClusterLocalityStats ref1 = loadStatsManager.getClusterLocalityStats(
        CLUSTER_NAME1, EDS_SERVICE_NAME1, LOCALITY1);
    ClusterLocalityStats ref2 = loadStatsManager.getClusterLocalityStats(
        CLUSTER_NAME1, EDS_SERVICE_NAME1, LOCALITY1);
    ref1.recordCallStarted();
    ref1.recordCallFinished(Status.OK);
    ref2.recordCallStarted();
    ref2.recordCallStarted();
    ref2.recordCallFinished(Status.UNAVAILABLE);

    ClusterStats stats = Iterables.getOnlyElement(
        loadStatsManager.getClusterStatsReports(CLUSTER_NAME1));
    UpstreamLocalityStats localityStats =
        Iterables.getOnlyElement(stats.getUpstreamLocalityStatsList());
    assertThat(localityStats.getTotalIssuedRequests()).isEqualTo(1L + 2L);
    assertThat(localityStats.getTotalSuccessfulRequests()).isEqualTo(1L);
    assertThat(localityStats.getTotalErrorRequests()).isEqualTo(1L);
    assertThat(localityStats.getTotalRequestsInProgress()).isEqualTo(1L + 2L - 1L - 1L);
  }

  @Test
  public void loadCounterDelayedDeletionAfterAllInProgressRequestsReported() {
    ClusterLocalityStats counter = loadStatsManager.getClusterLocalityStats(
        CLUSTER_NAME1, EDS_SERVICE_NAME1, LOCALITY1);
    counter.recordCallStarted();
    counter.recordCallStarted();

    ClusterStats stats = Iterables.getOnlyElement(
        loadStatsManager.getClusterStatsReports(CLUSTER_NAME1));
    UpstreamLocalityStats localityStats =
        Iterables.getOnlyElement(stats.getUpstreamLocalityStatsList());
    assertThat(localityStats.getTotalIssuedRequests()).isEqualTo(2L);
    assertThat(localityStats.getTotalSuccessfulRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalErrorRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalRequestsInProgress()).isEqualTo(2L);

    // release the counter, but requests still in-flight
    counter.release();
    stats = Iterables.getOnlyElement(loadStatsManager.getClusterStatsReports(CLUSTER_NAME1));
    localityStats = Iterables.getOnlyElement(stats.getUpstreamLocalityStatsList());
    assertThat(localityStats.getTotalIssuedRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalSuccessfulRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalErrorRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalRequestsInProgress())
        .isEqualTo(2L);  // retained by in-flight calls

    counter.recordCallFinished(Status.OK);
    counter.recordCallFinished(Status.UNAVAILABLE);
    stats = Iterables.getOnlyElement(loadStatsManager.getClusterStatsReports(CLUSTER_NAME1));
    localityStats = Iterables.getOnlyElement(stats.getUpstreamLocalityStatsList());
    assertThat(localityStats.getTotalIssuedRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalSuccessfulRequests()).isEqualTo(1L);
    assertThat(localityStats.getTotalErrorRequests()).isEqualTo(1L);
    assertThat(localityStats.getTotalRequestsInProgress()).isEqualTo(0L);

    assertThat(loadStatsManager.getClusterStatsReports(CLUSTER_NAME1)).isEmpty();
  }

  @Nullable
  private static ClusterStats findClusterStats(
      List<ClusterStats> statsList, String cluster, @Nullable String edsServiceName) {
    for (ClusterStats stats : statsList) {
      if (stats.getClusterName().equals(cluster)
          && Objects.equals(stats.getClusterServiceName(), edsServiceName)) {
        return stats;
      }
    }
    return null;
  }

  @Nullable
  private static UpstreamLocalityStats findLocalityStats(
      List<UpstreamLocalityStats> localityStatsList, Locality locality) {
    for (UpstreamLocalityStats stats : localityStatsList) {
      if (stats.getLocality().equals(locality)) {
        return stats;
      }
    }
    return null;
  }

  private static long findDroppedRequestCount(
      List<DroppedRequests> droppedRequestsLists, String category) {
    DroppedRequests drop = null;
    for (DroppedRequests stats : droppedRequestsLists) {
      if (stats.getCategory().equals(category)) {
        drop = stats;
      }
    }
    assertThat(drop).isNotNull();
    return drop.getDroppedCount();
  }
}
