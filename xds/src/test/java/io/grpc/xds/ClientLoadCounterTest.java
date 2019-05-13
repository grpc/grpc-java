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
import io.grpc.xds.ClientLoadCounter.MetricListener;
import io.grpc.xds.ClientLoadCounter.MetricValue;
import io.grpc.xds.ClientLoadCounter.XdsClientLoadRecorder;
import java.util.Random;
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
    assertThat(emptySnapshot.getCallsInProgress()).isEqualTo(0);
    assertThat(emptySnapshot.getCallsFinished()).isEqualTo(0);
    assertThat(emptySnapshot.getCallsFailed()).isEqualTo(0);
    assertThat(emptySnapshot.getMetricValues()).isEmpty();
  }

  @Test
  public void snapshotContainsEverything() {
    long numFinishedCalls = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long numInProgressCalls = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long numFailedCalls = ThreadLocalRandom.current().nextLong(numFinishedCalls);
    counter = new ClientLoadCounter(numFinishedCalls, numInProgressCalls, numFailedCalls);
    counter.recordMetric("test-metric-1", 0.75);
    counter.recordMetric("test-metric-2", 0.342);
    counter.recordMetric("test-metric-3", 0.512);
    counter.recordMetric("test-metric-1", 0.543);
    counter.recordMetric("test-metric-1", 4.412);
    counter.recordMetric("test-metric-1", 100.353);
    ClientLoadSnapshot snapshot = counter.snapshot();
    assertThat(snapshot.getCallsFinished()).isEqualTo(numFinishedCalls);
    assertThat(snapshot.getCallsInProgress()).isEqualTo(numInProgressCalls);
    assertThat(snapshot.getCallsFailed()).isEqualTo(numFailedCalls);
    assertThat(snapshot.getMetricValues().get("test-metric-1").getNumReports()).isEqualTo(4);
    assertThat(snapshot.getMetricValues().get("test-metric-1").getTotalValue())
        .isEqualTo(0.75 + 0.543 + 4.412 + 100.353);
    assertThat(snapshot.getMetricValues().get("test-metric-2").getNumReports()).isEqualTo(1);
    assertThat(snapshot.getMetricValues().get("test-metric-2").getTotalValue()).isEqualTo(0.342);
    assertThat(snapshot.getMetricValues().get("test-metric-3").getNumReports()).isEqualTo(1);
    assertThat(snapshot.getMetricValues().get("test-metric-3").getTotalValue()).isEqualTo(0.512);

    String snapshotStr = snapshot.toString();
    assertThat(snapshotStr).contains("callsFinished=" + numFinishedCalls);
    assertThat(snapshotStr).contains("callsInProgress=" + numInProgressCalls);
    assertThat(snapshotStr).contains("callsFailed=" + numFailedCalls);

    // Snapshot only accounts for stats happening after previous snapshot.
    snapshot = counter.snapshot();
    assertThat(snapshot.getCallsFinished()).isEqualTo(0);
    assertThat(snapshot.getCallsInProgress()).isEqualTo(numInProgressCalls);
    assertThat(snapshot.getCallsFailed()).isEqualTo(0);
    assertThat(snapshot.getMetricValues()).isEmpty();

    snapshotStr = snapshot.toString();
    assertThat(snapshotStr).contains("callsFinished=0");
    assertThat(snapshotStr).contains("callsInProgress=" + numInProgressCalls);
    assertThat(snapshotStr).contains("callsFailed=0");
    assertThat(snapshotStr).contains("metricValues={}");
  }

  @Test
  public void normalCountingOperations() {
    ClientLoadSnapshot preSnapshot = counter.snapshot();
    counter.incrementCallsInProgress();
    ClientLoadSnapshot afterSnapshot = counter.snapshot();
    assertThat(afterSnapshot.getCallsInProgress()).isEqualTo(preSnapshot.getCallsInProgress() + 1);
    counter.decrementCallsInProgress();
    afterSnapshot = counter.snapshot();
    assertThat(afterSnapshot.getCallsInProgress()).isEqualTo(preSnapshot.getCallsInProgress());

    counter.incrementCallsFinished();
    afterSnapshot = counter.snapshot();
    assertThat(afterSnapshot.getCallsFinished()).isEqualTo(1);

    counter.incrementCallsFailed();
    afterSnapshot = counter.snapshot();
    assertThat(afterSnapshot.getCallsFailed()).isEqualTo(1);
  }

  @Test
  public void xdsClientLoadRecorder_clientSideQueryCountsAggregation() {
    XdsClientLoadRecorder recorder1 =
        new XdsClientLoadRecorder(counter, NOOP_CLIENT_STREAM_TRACER_FACTORY);
    ClientStreamTracer tracer = recorder1.newClientStreamTracer(STREAM_INFO, new Metadata());
    ClientLoadSnapshot snapshot = counter.snapshot();
    assertThat(snapshot.getCallsFinished()).isEqualTo(0);
    assertThat(snapshot.getCallsInProgress()).isEqualTo(1);
    assertThat(snapshot.getCallsFailed()).isEqualTo(0);
    tracer.streamClosed(Status.OK);
    snapshot = counter.snapshot();
    assertThat(snapshot.getCallsFinished()).isEqualTo(1);
    assertThat(snapshot.getCallsInProgress()).isEqualTo(0);
    assertThat(snapshot.getCallsFailed()).isEqualTo(0);

    // Create a second XdsClientLoadRecorder with the same counter, stats are aggregated together.
    XdsClientLoadRecorder recorder2 =
        new XdsClientLoadRecorder(counter, NOOP_CLIENT_STREAM_TRACER_FACTORY);
    recorder1.newClientStreamTracer(STREAM_INFO, new Metadata()).streamClosed(Status.ABORTED);
    recorder2.newClientStreamTracer(STREAM_INFO, new Metadata()).streamClosed(Status.CANCELLED);
    snapshot = counter.snapshot();
    assertThat(snapshot.getCallsFinished()).isEqualTo(2);
    assertThat(snapshot.getCallsInProgress()).isEqualTo(0);
    assertThat(snapshot.getCallsFailed()).isEqualTo(2);
  }

  @Test
  public void metricListener_backendMetricsAggregation() {
    MetricListener listener1 = new MetricListener(counter);
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
    listener1.onLoadReport(report);

    // Simulate an empty load report.
    listener1.onLoadReport(OrcaLoadReport.getDefaultInstance());

    ClientLoadSnapshot snapshot = counter.snapshot();
    MetricValue cpuMetric = snapshot.getMetricValues().get("cpu_utilization");
    assertThat(cpuMetric.getNumReports()).isEqualTo(2);
    assertThat(cpuMetric.getTotalValue()).isEqualTo(cpuUtilization);

    MetricValue memMetric = snapshot.getMetricValues().get("mem_utilization");
    assertThat(memMetric.getNumReports()).isEqualTo(2);
    assertThat(memMetric.getTotalValue()).isEqualTo(memUtilization);

    MetricValue namedMetric1 = snapshot.getMetricValues().get("named-cost-or-utilization-1");
    assertThat(namedMetric1.getNumReports()).isEqualTo(1);
    assertThat(namedMetric1.getTotalValue()).isEqualTo(namedCostOrUtil1);

    MetricValue namedMetric2 = snapshot.getMetricValues().get("named-cost-or-utilization-2");
    assertThat(namedMetric2.getNumReports()).isEqualTo(1);
    assertThat(namedMetric2.getTotalValue()).isEqualTo(namedCostOrUtil2);

    snapshot = counter.snapshot();
    assertThat(snapshot.getMetricValues()).isEmpty();

    MetricListener listener2 = new MetricListener(counter);
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
}
