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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats.DroppedRequests;
import io.envoyproxy.envoy.api.v2.endpoint.EndpointLoadMetricStats;
import io.envoyproxy.envoy.api.v2.endpoint.UpstreamLocalityStats;
import io.grpc.ClientStreamTracer;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.xds.ClientLoadCounter.ClientLoadSnapshot;
import io.grpc.xds.ClientLoadCounter.MetricValue;
import io.grpc.xds.XdsLoadStatsStore.StatsCounter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link XdsLoadStatsStore}. */
@RunWith(JUnit4.class)
public class XdsLoadStatsStoreTest {
  private static final XdsLocality LOCALITY1 =
      new XdsLocality("test_region1", "test_zone", "test_subzone");
  private static final XdsLocality LOCALITY2 =
      new XdsLocality("test_region2", "test_zone", "test_subzone");
  private static final ClientStreamTracer.StreamInfo STREAM_INFO =
      ClientStreamTracer.StreamInfo.newBuilder().build();
  private Subchannel mockSubchannel = mock(Subchannel.class);
  private ConcurrentMap<XdsLocality, StatsCounter> localityLoadCounters;
  private ConcurrentMap<String, AtomicLong> dropCounters;
  private XdsLoadStatsStore loadStore;

  @Before
  public void setUp() {
    localityLoadCounters = new ConcurrentHashMap<>();
    dropCounters = new ConcurrentHashMap<>();
    loadStore = new XdsLoadStatsStore(localityLoadCounters, dropCounters);
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

  private static UpstreamLocalityStats buildUpstreamLocalityStats(XdsLocality locality,
      long callsSucceed,
      long callsInProgress,
      long callsFailed,
      long callsIssued,
      @Nullable List<EndpointLoadMetricStats> metrics) {
    UpstreamLocalityStats.Builder builder =
        UpstreamLocalityStats.newBuilder()
            .setLocality(locality.toLocalityProto())
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
    return DroppedRequests.newBuilder()
        .setCategory(category)
        .setDroppedCount(counts)
        .build();
  }

  private static ClusterStats buildClusterStats(
      @Nullable List<UpstreamLocalityStats> upstreamLocalityStatsList,
      @Nullable List<DroppedRequests> droppedRequestsList) {
    ClusterStats.Builder clusterStatsBuilder = ClusterStats.newBuilder();
    if (upstreamLocalityStatsList != null) {
      clusterStatsBuilder.addAllUpstreamLocalityStats(upstreamLocalityStatsList);
    }
    if (droppedRequestsList != null) {
      long dropCount = 0;
      for (DroppedRequests drop : droppedRequestsList) {
        dropCount += drop.getDroppedCount();
        clusterStatsBuilder.addDroppedRequests(drop);
      }
      clusterStatsBuilder.setTotalDroppedRequests(dropCount);
    }
    return clusterStatsBuilder.build();
  }

  private static void assertClusterStatsEqual(ClusterStats expected, ClusterStats actual) {
    assertThat(actual.getClusterName()).isEqualTo(expected.getClusterName());
    assertThat(actual.getLoadReportInterval()).isEqualTo(expected.getLoadReportInterval());
    assertThat(actual.getTotalDroppedRequests()).isEqualTo(expected.getTotalDroppedRequests());
    assertThat(actual.getDroppedRequestsCount()).isEqualTo(expected.getDroppedRequestsCount());
    assertThat(new HashSet<>(actual.getDroppedRequestsList()))
        .isEqualTo(new HashSet<>(expected.getDroppedRequestsList()));
    assertUpstreamLocalityStatsListsEqual(actual.getUpstreamLocalityStatsList(),
        expected.getUpstreamLocalityStatsList());
  }

  private static void assertUpstreamLocalityStatsListsEqual(List<UpstreamLocalityStats> expected,
      List<UpstreamLocalityStats> actual) {
    assertThat(actual).hasSize(expected.size());
    Map<io.envoyproxy.envoy.api.v2.core.Locality, UpstreamLocalityStats> expectedLocalityStats =
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
  public void addAndGetAndRemoveLocality() {
    loadStore.addLocality(LOCALITY1);
    assertThat(localityLoadCounters).containsKey(LOCALITY1);

    // Adding the same locality counter again causes an exception.
    try {
      loadStore.addLocality(LOCALITY1);
      Assert.fail();
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessageThat()
          .contains("An active counter for locality " + LOCALITY1 + " already exists");
    }

    assertThat(loadStore.getLocalityCounter(LOCALITY1))
        .isSameInstanceAs(localityLoadCounters.get(LOCALITY1));
    assertThat(loadStore.getLocalityCounter(LOCALITY2)).isNull();

    // Removing an non-existing locality counter causes an exception.
    try {
      loadStore.removeLocality(LOCALITY2);
      Assert.fail();
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessageThat()
          .contains("No active counter for locality " + LOCALITY2 + " exists");
    }

    // Removing the locality counter only mark it as inactive, but not throw it away.
    loadStore.removeLocality(LOCALITY1);
    assertThat(localityLoadCounters.get(LOCALITY1).isActive()).isFalse();

    // Removing an inactive locality counter causes an exception.
    try {
      loadStore.removeLocality(LOCALITY1);
      Assert.fail();
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessageThat()
          .contains("No active counter for locality " + LOCALITY1 + " exists");
    }

    // Adding it back simply mark it as active again.
    loadStore.addLocality(LOCALITY1);
    assertThat(localityLoadCounters.get(LOCALITY1).isActive()).isTrue();
  }

  @Test
  public void removeInactiveCountersAfterGeneratingLoadReport() {
    StatsCounter counter1 = mock(StatsCounter.class);
    when(counter1.isActive()).thenReturn(true);
    when(counter1.snapshot()).thenReturn(ClientLoadSnapshot.EMPTY_SNAPSHOT);
    StatsCounter counter2 = mock(StatsCounter.class);
    when(counter2.isActive()).thenReturn(false);
    when(counter2.snapshot()).thenReturn(ClientLoadSnapshot.EMPTY_SNAPSHOT);
    localityLoadCounters.put(LOCALITY1, counter1);
    localityLoadCounters.put(LOCALITY2, counter2);
    loadStore.generateLoadReport();
    assertThat(localityLoadCounters).containsKey(LOCALITY1);
    assertThat(localityLoadCounters).doesNotContainKey(LOCALITY2);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void loadReportMatchesSnapshots() {
    StatsCounter counter1 = mock(StatsCounter.class);
    Map<String, MetricValue> metrics1 = new HashMap<>();
    metrics1.put("cpu_utilization", new MetricValue(15, 12.5435));
    metrics1.put("mem_utilization", new MetricValue(8, 0.421));
    metrics1.put("named_cost_or_utilization", new MetricValue(3, 2.5435));
    when(counter1.isActive()).thenReturn(true);
    when(counter1.snapshot()).thenReturn(new ClientLoadSnapshot(4315, 3421, 23, 593, metrics1),
        new ClientLoadSnapshot(0, 543, 0, 0, Collections.EMPTY_MAP));
    StatsCounter counter2 = mock(StatsCounter.class);
    Map<String, MetricValue> metrics2 = new HashMap<>();
    metrics2.put("cpu_utilization", new MetricValue(344, 132.74));
    metrics2.put("mem_utilization", new MetricValue(41, 23.453));
    metrics2.put("named_cost_or_utilization", new MetricValue(12, 423));
    when(counter2.snapshot()).thenReturn(new ClientLoadSnapshot(41234, 432, 431, 702, metrics2),
        new ClientLoadSnapshot(0, 432, 0, 0, Collections.EMPTY_MAP));
    when(counter2.isActive()).thenReturn(true);
    localityLoadCounters.put(LOCALITY1, counter1);
    localityLoadCounters.put(LOCALITY2, counter2);

    ClusterStats expectedReport =
        buildClusterStats(
            Arrays.asList(
                buildUpstreamLocalityStats(LOCALITY1, 4315, 3421, 23, 593,
                    buildEndpointLoadMetricStatsList(metrics1)),
                buildUpstreamLocalityStats(LOCALITY2, 41234, 432, 431, 702,
                    buildEndpointLoadMetricStatsList(metrics2))
            ),
            null);

    assertClusterStatsEqual(expectedReport, loadStore.generateLoadReport());
    verify(counter1).snapshot();
    verify(counter2).snapshot();

    expectedReport =
        buildClusterStats(
            Arrays.asList(
                buildUpstreamLocalityStats(LOCALITY1, 0, 543, 0, 0, null),
                buildUpstreamLocalityStats(LOCALITY2, 0, 432, 0, 0, null)
            ),
            null);
    assertClusterStatsEqual(expectedReport, loadStore.generateLoadReport());
    verify(counter1, times(2)).snapshot();
    verify(counter2, times(2)).snapshot();
  }

  @Test
  public void recordingDroppedRequests() {
    int numLbDrop = 123;
    int numThrottleDrop = 456;
    for (int i = 0; i < numLbDrop; i++) {
      loadStore.recordDroppedRequest("lb");
    }
    for (int i = 0; i < numThrottleDrop; i++) {
      loadStore.recordDroppedRequest("throttle");
    }
    assertThat(dropCounters.get("lb").get()).isEqualTo(numLbDrop);
    assertThat(dropCounters.get("throttle").get()).isEqualTo(numThrottleDrop);
    ClusterStats expectedLoadReport =
        buildClusterStats(null,
            Arrays.asList(buildDroppedRequests("lb", numLbDrop),
                buildDroppedRequests("throttle", numThrottleDrop)));
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport());
    assertThat(dropCounters.get("lb").get()).isEqualTo(0);
    assertThat(dropCounters.get("throttle").get()).isEqualTo(0);
  }

  @Test
  public void loadNotRecordedForUntrackedLocality() {
    PickResult pickResult = PickResult.withSubchannel(mockSubchannel);
    // If the per-locality counter does not exist, nothing should happen.
    PickResult interceptedPickResult = loadStore.interceptPickResult(pickResult, LOCALITY1);
    assertThat(interceptedPickResult.getStreamTracerFactory()).isNull();
  }

  @Test
  public void invalidPickResultNotIntercepted() {
    PickResult errorResult = PickResult.withError(Status.UNAVAILABLE.withDescription("Error"));
    PickResult droppedResult = PickResult.withDrop(Status.UNAVAILABLE.withDescription("Dropped"));
    // TODO (chengyuanzhang): for NoResult PickResult, do we still intercept?
    PickResult interceptedErrorResult = loadStore.interceptPickResult(errorResult, LOCALITY1);
    PickResult interceptedDroppedResult =
        loadStore.interceptPickResult(droppedResult, LOCALITY1);
    assertThat(interceptedErrorResult.getStreamTracerFactory()).isNull();
    assertThat(interceptedDroppedResult.getStreamTracerFactory()).isNull();
  }

  @Test
  public void interceptPreservesOriginStreamTracer() {
    ClientStreamTracer.Factory mockFactory = mock(ClientStreamTracer.Factory.class);
    ClientStreamTracer mockTracer = mock(ClientStreamTracer.class);
    when(mockFactory
        .newClientStreamTracer(any(ClientStreamTracer.StreamInfo.class), any(Metadata.class)))
        .thenReturn(mockTracer);
    localityLoadCounters.put(LOCALITY1, new ClientLoadCounter());
    PickResult pickResult = PickResult.withSubchannel(mockSubchannel, mockFactory);
    PickResult interceptedPickResult = loadStore.interceptPickResult(pickResult, LOCALITY1);
    Metadata metadata = new Metadata();
    interceptedPickResult.getStreamTracerFactory().newClientStreamTracer(STREAM_INFO, metadata)
        .streamClosed(Status.OK);
    verify(mockFactory).newClientStreamTracer(same(STREAM_INFO), same(metadata));
    verify(mockTracer).streamClosed(Status.OK);
  }
}
