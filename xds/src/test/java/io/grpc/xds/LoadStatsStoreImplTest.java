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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;
import io.grpc.internal.FakeClock;
import io.grpc.xds.ClientLoadCounter.MetricValue;
import io.grpc.xds.EnvoyProtoData.ClusterStats;
import io.grpc.xds.EnvoyProtoData.ClusterStats.DroppedRequests;
import io.grpc.xds.EnvoyProtoData.EndpointLoadMetricStats;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.UpstreamLocalityStats;
import io.grpc.xds.LoadStatsManager.LoadStatsStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LoadStatsStore}. */
@RunWith(JUnit4.class)
public class LoadStatsStoreImplTest {
  private static final String CLUSTER_NAME = "cluster-test.googleapis.com";
  private static final Locality LOCALITY1 =
      new Locality("test_region1", "test_zone", "test_subzone");
  private static final Locality LOCALITY2 =
      new Locality("test_region2", "test_zone", "test_subzone");
  private final FakeClock fakeClock = new FakeClock();
  private LoadStatsStore loadStatsStore;

  @Before
  public void setUp() {
    Stopwatch stopwatch = fakeClock.getStopwatchSupplier().get();
    loadStatsStore = new LoadStatsStoreImpl(CLUSTER_NAME, null, stopwatch);
  }

  private static List<EndpointLoadMetricStats> buildEndpointLoadMetricStatsList(
      Map<String, MetricValue> metrics) {
    List<EndpointLoadMetricStats> res = new ArrayList<>();
    for (Map.Entry<String, MetricValue> entry : metrics.entrySet()) {
      res.add(EndpointLoadMetricStats.newBuilder()
          .setMetricName(entry.getKey())
          .setNumRequestsFinishedWithMetric(entry.getValue().getNumReports())
          .setTotalMetricValue(entry.getValue().getTotalValue())
          .build());
    }
    return res;
  }

  private static UpstreamLocalityStats buildUpstreamLocalityStats(
      Locality locality,
      long callsSucceed,
      long callsInProgress,
      long callsFailed,
      long callsIssued,
      @Nullable List<EndpointLoadMetricStats> metrics) {
    UpstreamLocalityStats.Builder builder =
        UpstreamLocalityStats.newBuilder()
            .setLocality(locality)
            .setTotalSuccessfulRequests(callsSucceed)
            .setTotalErrorRequests(callsFailed)
            .setTotalRequestsInProgress(callsInProgress)
            .setTotalIssuedRequests(callsIssued);
    if (metrics != null) {
      builder.addAllLoadMetricStats(metrics);
    }
    return builder.build();
  }

  private static DroppedRequests buildDroppedRequests(String category, long counts) {
    return new DroppedRequests(category, counts);
  }

  private static ClusterStats buildClusterStats(
      @Nullable List<UpstreamLocalityStats> upstreamLocalityStatsList,
      @Nullable List<DroppedRequests> droppedRequestsList, long totalDroppedRequests,
      long intervalNano) {
    ClusterStats.Builder clusterStatsBuilder = ClusterStats.newBuilder();
    clusterStatsBuilder.setClusterName(CLUSTER_NAME);
    if (upstreamLocalityStatsList != null) {
      clusterStatsBuilder.addAllUpstreamLocalityStats(upstreamLocalityStatsList);
    }
    if (droppedRequestsList != null) {
      for (DroppedRequests drop : droppedRequestsList) {
        totalDroppedRequests += drop.getDroppedCount();
        clusterStatsBuilder.addDroppedRequests(drop);
      }
    }
    clusterStatsBuilder.setTotalDroppedRequests(totalDroppedRequests);
    clusterStatsBuilder.setLoadReportIntervalNanos(intervalNano);
    return clusterStatsBuilder.build();
  }

  private static void assertClusterStatsEqual(ClusterStats expected, ClusterStats actual) {
    assertThat(actual.getClusterName()).isEqualTo(expected.getClusterName());
    assertThat(actual.getLoadReportIntervalNanos())
        .isEqualTo(expected.getLoadReportIntervalNanos());
    assertThat(actual.getTotalDroppedRequests()).isEqualTo(expected.getTotalDroppedRequests());
    assertThat(actual.getDroppedRequestsList()).hasSize(expected.getDroppedRequestsList().size());
    assertThat(new HashSet<>(actual.getDroppedRequestsList()))
        .isEqualTo(new HashSet<>(expected.getDroppedRequestsList()));
    assertUpstreamLocalityStatsListsEqual(actual.getUpstreamLocalityStatsList(),
        expected.getUpstreamLocalityStatsList());
  }

  private static void assertUpstreamLocalityStatsListsEqual(List<UpstreamLocalityStats> expected,
      List<UpstreamLocalityStats> actual) {
    assertThat(actual).hasSize(expected.size());
    Map<Locality, UpstreamLocalityStats> expectedLocalityStats =
        new HashMap<>();
    for (UpstreamLocalityStats stats : expected) {
      expectedLocalityStats.put(stats.getLocality(), stats);
    }
    for (UpstreamLocalityStats stats : actual) {
      UpstreamLocalityStats expectedStats = expectedLocalityStats.get(stats.getLocality());
      assertThat(expectedStats).isNotNull();
      assertUpstreamLocalityStatsEqual(stats, expectedStats);
    }
  }

  private static void assertUpstreamLocalityStatsEqual(UpstreamLocalityStats expected,
      UpstreamLocalityStats actual) {
    assertThat(actual.getLocality()).isEqualTo(expected.getLocality());
    assertThat(actual.getTotalSuccessfulRequests())
        .isEqualTo(expected.getTotalSuccessfulRequests());
    assertThat(actual.getTotalRequestsInProgress())
        .isEqualTo(expected.getTotalRequestsInProgress());
    assertThat(actual.getTotalErrorRequests()).isEqualTo(expected.getTotalErrorRequests());
    assertThat(new HashSet<>(actual.getLoadMetricStatsList()))
        .isEqualTo(new HashSet<>(expected.getLoadMetricStatsList()));
  }

  @Test
  public void removeInactiveCountersAfterGeneratingLoadReport() {
    loadStatsStore.addLocality(LOCALITY1);
    assertThat(loadStatsStore.generateLoadReport().getUpstreamLocalityStatsList()).hasSize(1);
    loadStatsStore.removeLocality(LOCALITY1);  // becomes inactive
    assertThat(loadStatsStore.generateLoadReport().getUpstreamLocalityStatsList()).hasSize(1);
    assertThat(loadStatsStore.generateLoadReport().getUpstreamLocalityStatsList()).isEmpty();
  }

  @Test
  public void localityCountersReferenceCounted() {
    loadStatsStore.addLocality(LOCALITY1);
    loadStatsStore.addLocality(LOCALITY1);
    loadStatsStore.removeLocality(LOCALITY1);
    assertThat(loadStatsStore.generateLoadReport().getUpstreamLocalityStatsList()).hasSize(1);
    assertThat(loadStatsStore.generateLoadReport().getUpstreamLocalityStatsList())
        .hasSize(1);  // still active
    loadStatsStore.removeLocality(LOCALITY1);  // becomes inactive
    assertThat(loadStatsStore.generateLoadReport().getUpstreamLocalityStatsList()).hasSize(1);
    assertThat(loadStatsStore.generateLoadReport().getUpstreamLocalityStatsList()).isEmpty();
  }

  @Test
  public void recordCallAndMetricStats() {
    ClientLoadCounter counter1 = loadStatsStore.addLocality(LOCALITY1);
    counter1.setCallsSucceeded(4315);
    counter1.setCallsInProgress(3421);
    counter1.setCallsFailed(23);
    counter1.setCallsIssued(593);
    counter1.recordMetric("cpu_utilization", 0.3244);
    counter1.recordMetric("mem_utilization", 0.01233);
    counter1.recordMetric("named_cost_or_utilization", 3221.6543);
    ClientLoadCounter counter2 = loadStatsStore.addLocality(LOCALITY2);
    counter2.setCallsSucceeded(41234);
    counter2.setCallsInProgress(432);
    counter2.setCallsFailed(431);
    counter2.setCallsIssued(702);
    counter2.recordMetric("cpu_utilization", 0.6526);
    counter2.recordMetric("mem_utilization", 0.3473);
    counter2.recordMetric("named_cost_or_utilization", 87653.4234);

    fakeClock.forwardNanos(1000L);
    Map<String, MetricValue> metrics1 =
        ImmutableMap.of(
            "cpu_utilization", new MetricValue(1, 0.3244),
            "mem_utilization", new MetricValue(1, 0.01233),
            "named_cost_or_utilization", new MetricValue(1, 3221.6543));
    Map<String, MetricValue> metrics2 =
        ImmutableMap.of(
            "cpu_utilization", new MetricValue(1, 0.6526),
            "mem_utilization", new MetricValue(1, 0.3473),
            "named_cost_or_utilization", new MetricValue(1, 87653.4234));
    ClusterStats expectedReport =
        buildClusterStats(
            Arrays.asList(
                buildUpstreamLocalityStats(LOCALITY1, 4315, 3421, 23, 593,
                    buildEndpointLoadMetricStatsList(metrics1)),
                buildUpstreamLocalityStats(LOCALITY2, 41234, 432, 431, 702,
                    buildEndpointLoadMetricStatsList(metrics2))
            ),
            null, 0L, 1000L);
    assertClusterStatsEqual(expectedReport, loadStatsStore.generateLoadReport());

    fakeClock.forwardNanos(2000L);
    expectedReport =
        buildClusterStats(
            Arrays.asList(
                buildUpstreamLocalityStats(LOCALITY1, 0, 3421, 0, 0, null),
                buildUpstreamLocalityStats(LOCALITY2, 0, 432, 0, 0, null)
            ),
            null, 0L, 2000L);
    assertClusterStatsEqual(expectedReport, loadStatsStore.generateLoadReport());
  }

  @Test
  public void recordDroppedRequests() {
    int numLbDrop = 123;
    int numThrottleDrop = 456;
    int uncategorizedDrop = 789;
    for (int i = 0; i < numLbDrop; i++) {
      loadStatsStore.recordDroppedRequest("lb");
    }
    for (int i = 0; i < numThrottleDrop; i++) {
      loadStatsStore.recordDroppedRequest("throttle");
    }
    for (int i = 0; i < uncategorizedDrop; i++) {
      loadStatsStore.recordDroppedRequest();
    }

    fakeClock.forwardNanos(1000L);
    ClusterStats expectedLoadReport =
        buildClusterStats(null,
            Arrays.asList(buildDroppedRequests("lb", numLbDrop),
                buildDroppedRequests("throttle", numThrottleDrop)),
            789L, 1000L);
    assertClusterStatsEqual(expectedLoadReport, loadStatsStore.generateLoadReport());

    fakeClock.forwardNanos(1000L);
    expectedLoadReport =
        buildClusterStats(null,
            Arrays.asList(buildDroppedRequests("lb", 0L),
                buildDroppedRequests("throttle", 0L)),
            0L, 1000L);
    assertClusterStatsEqual(expectedLoadReport, loadStatsStore.generateLoadReport());
  }
}
