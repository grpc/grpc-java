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
    assertThat(emptySnapshot.callsInProgress).isEqualTo(0);
    assertThat(emptySnapshot.callsSucceed).isEqualTo(0);
    assertThat(emptySnapshot.callsFailed).isEqualTo(0);
    assertThat(emptySnapshot.metricValues).isEmpty();
  }

  @Test
  public void snapshotContainsEverything() {
    long numInProgressCalls = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long numFinishedCalls = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long numFailedCalls = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    counter = new ClientLoadCounter(numInProgressCalls, numFinishedCalls, numFailedCalls);
    counter.recordMetric("test-metric-1", 0.75);
    counter.recordMetric("test-metric-2", 0.342);
    counter.recordMetric("test-metric-3", 0.512);
    counter.recordMetric("test-metric-1", 0.543);
    counter.recordMetric("test-metric-1", 4.412);
    counter.recordMetric("test-metric-1", 100.353);
    ClientLoadSnapshot snapshot = counter.snapshot();
    assertThat(snapshot.callsSucceed).isEqualTo(numFinishedCalls - numFailedCalls);
    assertThat(snapshot.callsInProgress).isEqualTo(numInProgressCalls);
    assertThat(snapshot.callsFailed).isEqualTo(numFailedCalls);
    assertThat(snapshot.metricValues.get("test-metric-1").numReports).isEqualTo(4);
    assertThat(Double.doubleToLongBits(snapshot.metricValues.get("test-metric-1").totalValue))
        .isEqualTo(Double.doubleToLongBits(0.75 + 0.543 + 4.412 + 100.353));
    assertThat(snapshot.metricValues.get("test-metric-2").numReports).isEqualTo(1);
    assertThat(Double.doubleToLongBits(snapshot.metricValues.get("test-metric-2").totalValue))
        .isEqualTo(Double.doubleToLongBits(0.342));
    assertThat(snapshot.metricValues.get("test-metric-3").numReports).isEqualTo(1);
    assertThat(Double.doubleToLongBits(snapshot.metricValues.get("test-metric-3").totalValue))
        .isEqualTo(Double.doubleToLongBits(0.512));

    // Snapshot only accounts for stats happening after previous snapshot.
    snapshot = counter.snapshot();
    assertThat(snapshot.callsSucceed).isEqualTo(0);
    assertThat(snapshot.callsInProgress).isEqualTo(numInProgressCalls);
    assertThat(snapshot.callsFailed).isEqualTo(0);
    assertThat(snapshot.metricValues).isEmpty();
  }

  @Test
  public void normalCountingOperations() {
    ClientLoadSnapshot preSnapshot = counter.snapshot();
    counter.incrementCallsInProgress();
    ClientLoadSnapshot afterSnapshot = counter.snapshot();
    assertThat(afterSnapshot.callsInProgress).isEqualTo(preSnapshot.callsInProgress + 1);
    counter.decrementCallsInProgress();
    afterSnapshot = counter.snapshot();
    assertThat(afterSnapshot.callsInProgress).isEqualTo(preSnapshot.callsInProgress);

    counter.incrementCallsFinished();
    afterSnapshot = counter.snapshot();
    assertThat(afterSnapshot.callsSucceed).isEqualTo(1);

    counter.incrementCallsFinished();
    counter.incrementCallsFailed();
    afterSnapshot = counter.snapshot();
    assertThat(afterSnapshot.callsFailed).isEqualTo(1);
    assertThat(afterSnapshot.callsSucceed).isEqualTo(0);
  }

  @Test
  public void xdsClientLoadRecorder_clientSideQueryCountsAggregation() {
    XdsClientLoadRecorder recorder1 =
        new XdsClientLoadRecorder(counter, NOOP_CLIENT_STREAM_TRACER_FACTORY);
    ClientStreamTracer tracer = recorder1.newClientStreamTracer(STREAM_INFO, new Metadata());
    ClientLoadSnapshot snapshot = counter.snapshot();
    assertThat(snapshot.callsInProgress).isEqualTo(1);
    tracer.streamClosed(Status.OK);
    snapshot = counter.snapshot();
    assertThat(snapshot.callsSucceed).isEqualTo(1);
    assertThat(snapshot.callsInProgress).isEqualTo(0);

    // Create a second XdsClientLoadRecorder with the same counter, stats are aggregated together.
    XdsClientLoadRecorder recorder2 =
        new XdsClientLoadRecorder(counter, NOOP_CLIENT_STREAM_TRACER_FACTORY);
    recorder1.newClientStreamTracer(STREAM_INFO, new Metadata()).streamClosed(Status.ABORTED);
    recorder2.newClientStreamTracer(STREAM_INFO, new Metadata()).streamClosed(Status.CANCELLED);
    snapshot = counter.snapshot();
    assertThat(snapshot.callsFailed).isEqualTo(2);
  }

  @Test
  public void metricListener_backendMetricsAggregation() {
    MetricListener listener = new MetricListener(counter);
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

    // Simulate an empty load report.
    listener.onLoadReport(OrcaLoadReport.getDefaultInstance());

    ClientLoadSnapshot snapshot = counter.snapshot();
    MetricValue cpuMetric = snapshot.metricValues.get("cpu_utilization");
    assertThat(cpuMetric.numReports).isEqualTo(2);
    assertThat(cpuMetric.totalValue).isEqualTo(cpuUtilization);

    MetricValue memMetric = snapshot.metricValues.get("mem_utilization");
    assertThat(memMetric.numReports).isEqualTo(2);
    assertThat(memMetric.totalValue).isEqualTo(memUtilization);

    MetricValue namedMetric1 = snapshot.metricValues.get("named-cost-or-utilization-1");
    assertThat(namedMetric1.numReports).isEqualTo(1);
    assertThat(namedMetric1.totalValue).isEqualTo(namedCostOrUtil1);

    MetricValue namedMetric2 = snapshot.metricValues.get("named-cost-or-utilization-2");
    assertThat(namedMetric2.numReports).isEqualTo(1);
    assertThat(namedMetric2.totalValue).isEqualTo(namedCostOrUtil2);
  }
}
