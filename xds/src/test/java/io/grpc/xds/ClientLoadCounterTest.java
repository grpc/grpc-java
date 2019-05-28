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

import io.envoyproxy.udpa.data.orca.v1.OrcaLoadReport;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.xds.ClientLoadCounter.ClientLoadSnapshot;
import io.grpc.xds.ClientLoadCounter.LocalityMetricsListener;
import io.grpc.xds.ClientLoadCounter.MetricValue;
import io.grpc.xds.ClientLoadCounter.XdsClientLoadRecorder;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ClientLoadCounter}. */
@RunWith(JUnit4.class)
public class ClientLoadCounterTest {

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
  private ClientLoadCounter counter;

  @Before
  public void setUp() {
    counter = new ClientLoadCounter();
    ClientLoadSnapshot emptySnapshot = counter.snapshot();
    assertQueryCounts(emptySnapshot, 0, 0, 0, 0);
    assertThat(emptySnapshot.getMetricValues()).isEmpty();
  }

  @Test
  public void snapshotContainsDataInCounter() {
    long numSucceededCalls = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long numInProgressCalls = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long numFailedCalls = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long numIssuedCalls = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    counter =
        new ClientLoadCounter(numSucceededCalls, numInProgressCalls, numFailedCalls,
            numIssuedCalls);
    ClientLoadSnapshot snapshot = counter.snapshot();
    assertQueryCounts(snapshot, numSucceededCalls, numInProgressCalls, numFailedCalls,
        numIssuedCalls);
    String snapshotStr = snapshot.toString();
    assertThat(snapshotStr).contains("callsSucceeded=" + numSucceededCalls);
    assertThat(snapshotStr).contains("callsInProgress=" + numInProgressCalls);
    assertThat(snapshotStr).contains("callsFailed=" + numFailedCalls);
    assertThat(snapshotStr).contains("callsIssued=" + numIssuedCalls);
    assertThat(snapshotStr).contains("metricValues={}");

    // Snapshot only accounts for stats happening after previous snapshot.
    snapshot = counter.snapshot();
    assertQueryCounts(snapshot, 0, numInProgressCalls, 0, 0);

    snapshotStr = snapshot.toString();
    assertThat(snapshotStr).contains("callsSucceeded=0");
    assertThat(snapshotStr).contains("callsInProgress=" + numInProgressCalls);
    assertThat(snapshotStr).contains("callsFailed=0");
    assertThat(snapshotStr).contains("callsIssued=0");
    assertThat(snapshotStr).contains("metricValues={}");
  }

  @Test
  public void normalRecordingOperations() {
    counter.recordCallStarted();
    ClientLoadSnapshot snapshot = counter.snapshot();
    assertQueryCounts(snapshot, 0, 1, 0, 1);

    counter.recordCallFinished(Status.OK);
    snapshot = counter.snapshot();
    assertQueryCounts(snapshot, 1, 0, 0, 0);

    counter.recordCallStarted();
    counter.recordCallFinished(Status.CANCELLED);
    snapshot = counter.snapshot();
    assertQueryCounts(snapshot, 0, 0, 1, 1);

    counter.recordMetric("test-metric-1", 0.75);
    counter.recordMetric("test-metric-2", 0.342);
    counter.recordMetric("test-metric-3", 0.512);
    counter.recordMetric("test-metric-1", 0.543);
    counter.recordMetric("test-metric-1", 4.412);
    counter.recordMetric("test-metric-1", 100.353);
    snapshot = counter.snapshot();
    assertThat(snapshot.getMetricValues().get("test-metric-1").getNumReports()).isEqualTo(4);
    assertThat(snapshot.getMetricValues().get("test-metric-1").getTotalValue())
        .isEqualTo(0.75 + 0.543 + 4.412 + 100.353);
    assertThat(snapshot.getMetricValues().get("test-metric-2").getNumReports()).isEqualTo(1);
    assertThat(snapshot.getMetricValues().get("test-metric-2").getTotalValue()).isEqualTo(0.342);
    assertThat(snapshot.getMetricValues().get("test-metric-3").getNumReports()).isEqualTo(1);
    assertThat(snapshot.getMetricValues().get("test-metric-3").getTotalValue()).isEqualTo(0.512);
  }

  @Test
  public void xdsClientLoadRecorder_clientSideQueryCountsAggregation() {
    XdsClientLoadRecorder recorder1 =
        new XdsClientLoadRecorder(counter, NOOP_CLIENT_STREAM_TRACER_FACTORY);
    ClientStreamTracer tracer = recorder1.newClientStreamTracer(STREAM_INFO, new Metadata());
    ClientLoadSnapshot snapshot = counter.snapshot();
    assertQueryCounts(snapshot, 0, 1, 0, 1);
    tracer.streamClosed(Status.OK);
    snapshot = counter.snapshot();
    assertQueryCounts(snapshot, 1, 0, 0, 0);

    // Create a second XdsClientLoadRecorder with the same counter, stats are aggregated together.
    XdsClientLoadRecorder recorder2 =
        new XdsClientLoadRecorder(counter, NOOP_CLIENT_STREAM_TRACER_FACTORY);
    recorder1.newClientStreamTracer(STREAM_INFO, new Metadata()).streamClosed(Status.ABORTED);
    recorder2.newClientStreamTracer(STREAM_INFO, new Metadata()).streamClosed(Status.CANCELLED);
    snapshot = counter.snapshot();
    assertQueryCounts(snapshot, 0, 0, 2, 2);
  }

  @Test
  public void metricListener_backendMetricsAggregation() {
    LocalityMetricsListener listener1 = new LocalityMetricsListener(counter);
    OrcaLoadReport report =
        OrcaLoadReport.newBuilder()
            .setCpuUtilization(0.5345)
            .setMemUtilization(0.647)
            .putRequestCostOrUtilization("named-cost-or-utilization-1", 3453.3525)
            .putRequestCostOrUtilization("named-cost-or-utilization-2", 532543.14234)
            .build();
    listener1.onLoadReport(report);

    // Simulate an empty load report.
    listener1.onLoadReport(OrcaLoadReport.getDefaultInstance());

    ClientLoadSnapshot snapshot = counter.snapshot();
    MetricValue cpuMetric = snapshot.getMetricValues().get("cpu_utilization");
    assertThat(cpuMetric.getNumReports()).isEqualTo(2);
    assertThat(cpuMetric.getTotalValue()).isEqualTo(0.5345);

    MetricValue memMetric = snapshot.getMetricValues().get("mem_utilization");
    assertThat(memMetric.getNumReports()).isEqualTo(2);
    assertThat(memMetric.getTotalValue()).isEqualTo(0.647);

    MetricValue namedMetric1 = snapshot.getMetricValues().get("named-cost-or-utilization-1");
    assertThat(namedMetric1.getNumReports()).isEqualTo(1);
    assertThat(namedMetric1.getTotalValue()).isEqualTo(3453.3525);

    MetricValue namedMetric2 = snapshot.getMetricValues().get("named-cost-or-utilization-2");
    assertThat(namedMetric2.getNumReports()).isEqualTo(1);
    assertThat(namedMetric2.getTotalValue()).isEqualTo(532543.14234);

    snapshot = counter.snapshot();
    assertThat(snapshot.getMetricValues()).isEmpty();

    LocalityMetricsListener listener2 = new LocalityMetricsListener(counter);
    report =
        OrcaLoadReport.newBuilder()
            .setCpuUtilization(0.3423)
            .setMemUtilization(0.654)
            .putRequestCostOrUtilization("named-cost-or-utilization", 3534.0)
            .build();
    // Two listeners with the same counter aggregate metrics together.
    listener1.onLoadReport(report);
    listener2.onLoadReport(report);

    snapshot = counter.snapshot();
    cpuMetric = snapshot.getMetricValues().get("cpu_utilization");
    assertThat(cpuMetric.getNumReports()).isEqualTo(2);
    assertThat(cpuMetric.getTotalValue()).isEqualTo(0.3423 + 0.3423);

    memMetric = snapshot.getMetricValues().get("mem_utilization");
    assertThat(memMetric.getNumReports()).isEqualTo(2);
    assertThat(memMetric.getTotalValue()).isEqualTo(0.654 + 0.654);

    MetricValue namedMetric = snapshot.getMetricValues().get("named-cost-or-utilization");
    assertThat(namedMetric.getNumReports()).isEqualTo(2);
    assertThat(namedMetric.getTotalValue()).isEqualTo(3534.0 + 3534.0);
  }

  private void assertQueryCounts(ClientLoadSnapshot snapshot,
      long callsSucceeded,
      long callsInProgress,
      long callsFailed,
      long callsIssued) {
    assertThat(snapshot.getCallsSucceeded()).isEqualTo(callsSucceeded);
    assertThat(snapshot.getCallsInProgress()).isEqualTo(callsInProgress);
    assertThat(snapshot.getCallsFailed()).isEqualTo(callsFailed);
    assertThat(snapshot.getCallsIssued()).isEqualTo(callsIssued);
  }
}
