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

import com.google.protobuf.Duration;
import io.envoyproxy.envoy.api.v2.core.Locality;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats.DroppedRequests;
import io.envoyproxy.envoy.api.v2.endpoint.EndpointLoadMetricStats;
import io.envoyproxy.envoy.api.v2.endpoint.UpstreamLocalityStats;
import io.envoyproxy.udpa.data.orca.v1.OrcaLoadReport;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.xds.XdsLoadReportStore.XdsClientLoadRecorder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link XdsLoadReportStore}. */
@RunWith(JUnit4.class)
public class XdsLoadReportStoreTest {
  private static final String SERVICE_NAME = "api.google.com";
  private static final ClientStreamTracer.StreamInfo STREAM_INFO =
      ClientStreamTracer.StreamInfo.newBuilder().build();
  private static final ClientStreamTracer.Factory NOOP_CLIENT_STREAM_TRACER_FACTORY =
      new ClientStreamTracer.Factory() {
        @Override
        public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
          return new ClientStreamTracer() {
          };
        }
      };
  private static final Locality TEST_LOCALITY = Locality.newBuilder()
      .setRegion("test_region")
      .setZone("test_zone")
      .setSubZone("test_subzone")
      .build();
  private ConcurrentMap<Locality, ClientLoadCounter> localityLoadCounters;
  private ConcurrentMap<String, AtomicLong> dropCounters;
  private XdsLoadReportStore loadStore;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    localityLoadCounters = new ConcurrentHashMap<>();
    dropCounters = new ConcurrentHashMap<>();
    loadStore = new XdsLoadReportStore(SERVICE_NAME, localityLoadCounters, dropCounters);
  }

  @Test
  public void loadStatsRecording() {
    Locality locality1 =
        Locality.newBuilder()
            .setRegion("test_region1")
            .setZone("test_zone")
            .setSubZone("test_subzone")
            .build();
    loadStore.addLocality(locality1);
    assertThat(localityLoadCounters).containsKey(locality1);
    XdsClientLoadRecorder recorder1 = new XdsClientLoadRecorder(
        localityLoadCounters.get(locality1), NOOP_CLIENT_STREAM_TRACER_FACTORY);
    ClientStreamTracer tracer = recorder1.newClientStreamTracer(STREAM_INFO, new Metadata());
    Duration interval = Duration.newBuilder().setNanos(342).build();
    ClusterStats expectedLoadReport = buildClusterStats(interval,
        Collections.singletonList(buildUpstreamLocalityStats(locality1, 0, 0, 1, null)), null);
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));

    // Make another load report should not reset count for calls in progress.
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));

    tracer.streamClosed(Status.OK);
    expectedLoadReport = buildClusterStats(interval,
        Collections.singletonList(buildUpstreamLocalityStats(locality1, 1, 0, 0, null)), null);
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));

    // Make another load report should reset finished calls count for calls finished.
    expectedLoadReport = buildClusterStats(interval,
        Collections.singletonList(buildUpstreamLocalityStats(locality1, 0, 0, 0, null)), null);
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));

    // Recorder with the same locality should aggregate to the same counter.
    assertThat(localityLoadCounters).hasSize(1);
    XdsClientLoadRecorder recorder2 = new XdsClientLoadRecorder(
        localityLoadCounters.get(locality1), NOOP_CLIENT_STREAM_TRACER_FACTORY);
    recorder1.newClientStreamTracer(STREAM_INFO, new Metadata()).streamClosed(Status.ABORTED);
    recorder2.newClientStreamTracer(STREAM_INFO, new Metadata()).streamClosed(Status.CANCELLED);
    expectedLoadReport = buildClusterStats(interval,
        Collections.singletonList(buildUpstreamLocalityStats(locality1, 0, 2, 0, null)), null);
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));

    expectedLoadReport = buildClusterStats(interval,
        Collections.singletonList(buildUpstreamLocalityStats(locality1, 0, 0, 0, null)), null);
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));

    Locality locality2 =
        Locality.newBuilder()
            .setRegion("test_region2")
            .setZone("test_zone")
            .setSubZone("test_subzone")
            .build();
    loadStore.addLocality(locality2);
    assertThat(localityLoadCounters).containsKey(locality2);
    assertThat(localityLoadCounters).hasSize(2);
    XdsClientLoadRecorder recorder3 = new XdsClientLoadRecorder(
        localityLoadCounters.get(locality2), NOOP_CLIENT_STREAM_TRACER_FACTORY);
    recorder3.newClientStreamTracer(STREAM_INFO, new Metadata());
    List<UpstreamLocalityStats> upstreamLocalityStatsList =
        Arrays.asList(buildUpstreamLocalityStats(locality1, 0, 0, 0, null),
            buildUpstreamLocalityStats(locality2, 0, 0, 1, null));
    expectedLoadReport = buildClusterStats(interval, upstreamLocalityStatsList, null);
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));
  }

  @Test
  public void loadRecordingForRemovedLocality() {
    loadStore.addLocality(TEST_LOCALITY);
    assertThat(localityLoadCounters).containsKey(TEST_LOCALITY);
    XdsClientLoadRecorder recorder = new XdsClientLoadRecorder(
        localityLoadCounters.get(TEST_LOCALITY), NOOP_CLIENT_STREAM_TRACER_FACTORY);
    ClientStreamTracer tracer = recorder.newClientStreamTracer(STREAM_INFO, new Metadata());

    Duration interval = Duration.newBuilder().setNanos(342).build();
    ClusterStats expectedLoadReport = buildClusterStats(interval,
        Collections.singletonList(buildUpstreamLocalityStats(TEST_LOCALITY, 0, 0, 1, null)), null);
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));
    // Remote balancer instructs to remove the locality while client has in-progress calls
    // to backends in the locality, the XdsClientLoadStore continues tracking its load stats.
    loadStore.removeLocality(TEST_LOCALITY);
    assertThat(localityLoadCounters).containsKey(TEST_LOCALITY);
    expectedLoadReport = buildClusterStats(interval,
        Collections.singletonList(buildUpstreamLocalityStats(TEST_LOCALITY, 0, 0, 1, null)), null);
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));

    tracer.streamClosed(Status.OK);
    expectedLoadReport = buildClusterStats(interval,
        Collections.singletonList(buildUpstreamLocalityStats(TEST_LOCALITY, 1, 0, 0, null)), null);
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));
    assertThat(localityLoadCounters).doesNotContainKey(TEST_LOCALITY);
  }

  @Test
  public void recordingDroppedRequests() {
    Random rand = new Random();
    int numLbDrop = rand.nextInt(1000);
    int numThrottleDrop = rand.nextInt(1000);
    for (int i = 0; i < numLbDrop; i++) {
      loadStore.recordDroppedRequest("lb");
    }
    for (int i = 0; i < numThrottleDrop; i++) {
      loadStore.recordDroppedRequest("throttle");
    }
    Duration interval = Duration.newBuilder().setNanos(342).build();
    ClusterStats expectedLoadReport = buildClusterStats(interval,
        null,
        Arrays.asList(
            DroppedRequests.newBuilder()
                .setCategory("lb")
                .setDroppedCount(numLbDrop)
                .build(),
            DroppedRequests.newBuilder()
                .setCategory("throttle")
                .setDroppedCount(numThrottleDrop)
                .build()
        ));
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));
    assertThat(dropCounters.get("lb").get()).isEqualTo(0);
    assertThat(dropCounters.get("throttle").get()).isEqualTo(0);
  }

  @Test
  public void recordingMetricAggregation() {
    loadStore.addLocality(TEST_LOCALITY);
    XdsLoadReportStore.MetricListener listener =
        new XdsLoadReportStore.MetricListener(loadStore.getLocalityCounter(TEST_LOCALITY));
    Random rand = new Random();
    double cpuUtilization = rand.nextDouble();
    double memUtilization = rand.nextDouble();
    double namedCostOrUtil1 = rand.nextDouble() * rand.nextInt(Integer.MAX_VALUE);
    double namedCostOrUtil2 = rand.nextDouble() * rand.nextInt(Integer.MAX_VALUE);
    OrcaLoadReport report =
        OrcaLoadReport.newBuilder()
            .setCpuUtilization(cpuUtilization)
            .setMemUtilization(memUtilization)
            .putRequestCostOrUtilization("named-cost-or-utilization-1", namedCostOrUtil1)
            .putRequestCostOrUtilization("named-cost-or-utilization-2", namedCostOrUtil2)
            .build();
    listener.onLoadReport(report);

    listener.onLoadReport(OrcaLoadReport.getDefaultInstance());

    Duration interval = Duration.newBuilder().setNanos(342).build();
    ClusterStats expectedLoadReport = buildClusterStats(interval,
        Arrays.asList(buildUpstreamLocalityStats(TEST_LOCALITY, 0, 0, 0,
            Arrays.asList(
                EndpointLoadMetricStats.newBuilder()
                    .setMetricName("cpu_utilization")
                    .setNumRequestsFinishedWithMetric(2)
                    .setTotalMetricValue(cpuUtilization)
                    .build(),
                EndpointLoadMetricStats.newBuilder()
                    .setMetricName("mem_utilization")
                    .setNumRequestsFinishedWithMetric(2)
                    .setTotalMetricValue(memUtilization)
                    .build(),
                EndpointLoadMetricStats.newBuilder()
                    .setMetricName("named-cost-or-utilization-1")
                    .setNumRequestsFinishedWithMetric(1)
                    .setTotalMetricValue(namedCostOrUtil1)
                    .build(),
                EndpointLoadMetricStats.newBuilder()
                    .setMetricName("named-cost-or-utilization-2")
                    .setNumRequestsFinishedWithMetric(1)
                    .setTotalMetricValue(namedCostOrUtil2)
                    .build()
            ))
        ),
        null);
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));
  }

  private UpstreamLocalityStats buildUpstreamLocalityStats(Locality locality,
      long callsSucceed,
      long callsFailed,
      long callsInProgress,
      @Nullable List<EndpointLoadMetricStats> metrics) {
    UpstreamLocalityStats.Builder builder =
        UpstreamLocalityStats.newBuilder()
            .setLocality(locality)
            .setTotalSuccessfulRequests(callsSucceed)
            .setTotalErrorRequests(callsFailed)
            .setTotalRequestsInProgress(callsInProgress);
    if (metrics != null) {
      builder.addAllLoadMetricStats(metrics);
    }
    return builder.build();
  }

  private ClusterStats buildClusterStats(Duration interval,
      @Nullable List<UpstreamLocalityStats> upstreamLocalityStatsList,
      @Nullable List<DroppedRequests> droppedRequestsList) {
    ClusterStats.Builder clusterStatsBuilder = ClusterStats.newBuilder()
        .setClusterName(SERVICE_NAME)
        .setLoadReportInterval(interval);
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

  private void assertClusterStatsEqual(ClusterStats expected, ClusterStats actual) {
    assertThat(actual.getClusterName()).isEqualTo(expected.getClusterName());
    assertThat(actual.getLoadReportInterval()).isEqualTo(expected.getLoadReportInterval());
    assertThat(actual.getDroppedRequestsCount()).isEqualTo(expected.getDroppedRequestsCount());
    assertThat(new HashSet<>(actual.getDroppedRequestsList()))
        .isEqualTo(new HashSet<>(expected.getDroppedRequestsList()));
    assertUpstreamLocalityStatsListsEqual(actual.getUpstreamLocalityStatsList(),
        expected.getUpstreamLocalityStatsList());
  }

  private void assertUpstreamLocalityStatsListsEqual(List<UpstreamLocalityStats> expected,
      List<UpstreamLocalityStats> actual) {
    assertThat(actual.size()).isEqualTo(expected.size());
    Map<Locality, UpstreamLocalityStats> expectedLocalityStats = new HashMap<>();
    for (UpstreamLocalityStats stats : expected) {
      expectedLocalityStats.put(stats.getLocality(), stats);
    }
    for (UpstreamLocalityStats stats : actual) {
      UpstreamLocalityStats expectedStats = expectedLocalityStats.get(stats.getLocality());
      assertThat(expectedStats).isNotNull();
      assertUpstreamLocalityStatsEqual(stats, expectedStats);
    }
  }

  private void assertUpstreamLocalityStatsEqual(UpstreamLocalityStats expected,
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
}
