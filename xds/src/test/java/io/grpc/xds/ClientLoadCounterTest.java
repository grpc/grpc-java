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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.udpa.udpa.data.orca.v1.OrcaLoadReport;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.FakeClock;
import io.grpc.xds.ClientLoadCounter.ClientLoadSnapshot;
import io.grpc.xds.ClientLoadCounter.LoadRecordingStreamTracerFactory;
import io.grpc.xds.ClientLoadCounter.LoadRecordingSubchannelPicker;
import io.grpc.xds.ClientLoadCounter.MetricValue;
import io.grpc.xds.ClientLoadCounter.MetricsObservingSubchannelPicker;
import io.grpc.xds.ClientLoadCounter.MetricsRecordingListener;
import io.grpc.xds.ClientLoadCounter.TracerWrappingSubchannelPicker;
import io.grpc.xds.OrcaPerRequestUtil.OrcaPerRequestReportListener;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

  private final FakeClock fakeClock = new FakeClock();
  private ClientLoadCounter counter =
      new ClientLoadCounter(fakeClock.getStopwatchSupplier().get());

  @Test
  public void recordAndSnapshot() {
    for (int i = 0; i < 52; i++) {
      counter.recordCallStarted();
    }
    fakeClock.forwardTime(10L, TimeUnit.SECONDS);
    for (int i = 0; i < 31; i++) {
      counter.recordCallFinished(Status.OK);
    }
    fakeClock.forwardTime(5L, TimeUnit.SECONDS);
    for (int i = 0; i < 7; i++) {
      counter.recordCallFinished(Status.PERMISSION_DENIED);
    }
    fakeClock.forwardTime(1L, TimeUnit.SECONDS);
    counter.recordCallStarted();
    ClientLoadSnapshot snapshot = counter.snapshot();
    assertThat(snapshot.getCallsIssued()).isEqualTo(52L + 1L);
    assertThat(snapshot.getCallsSucceeded()).isEqualTo(31L);
    assertThat(snapshot.getCallsInProgress()).isEqualTo(52L + 1L - 31L - 7L);  // 15
    assertThat(snapshot.getCallsFailed()).isEqualTo(7L);
    assertThat(snapshot.getMetricValues()).isEmpty();
    assertThat(snapshot.getDurationNano()).isEqualTo(TimeUnit.SECONDS.toNanos(10L + 5L + 1L));

    fakeClock.forwardTime(3L, TimeUnit.MILLISECONDS);
    counter.recordCallFinished(Status.OK);
    counter.recordMetric("test-metric-1", 0.75);
    counter.recordMetric("test-metric-2", 0.342);
    counter.recordMetric("test-metric-3", 0.512);
    counter.recordMetric("test-metric-1", 0.543);
    counter.recordMetric("test-metric-1", 4.412);
    counter.recordMetric("test-metric-1", 100.353);
    fakeClock.forwardTime(3L, TimeUnit.MILLISECONDS);
    snapshot = counter.snapshot();
    assertThat(snapshot.getCallsIssued()).isEqualTo(0L);
    assertThat(snapshot.getCallsSucceeded()).isEqualTo(1L);
    assertThat(snapshot.getCallsInProgress()).isEqualTo(15L - 1L);
    assertThat(snapshot.getCallsFailed()).isEqualTo(0L);
    Map<String, MetricValue> metrics = snapshot.getMetricValues();
    assertThat(metrics.get("test-metric-1").getNumReports()).isEqualTo(4L);
    assertThat(metrics.get("test-metric-1").getTotalValue())
        .isEqualTo(0.75 + 0.543 + 4.412 + 100.353);
    assertThat(metrics.get("test-metric-2").getNumReports()).isEqualTo(1L);
    assertThat(metrics.get("test-metric-2").getTotalValue()).isEqualTo(0.342);
    assertThat(metrics.get("test-metric-3").getNumReports()).isEqualTo(1L);
    assertThat(metrics.get("test-metric-3").getTotalValue()).isEqualTo(0.512);
    assertThat(snapshot.getDurationNano()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(3L + 3L));

    fakeClock.forwardTime(5L, TimeUnit.SECONDS);
    snapshot = counter.snapshot();
    assertThat(snapshot.getCallsIssued()).isEqualTo(0L);
    assertThat(snapshot.getCallsSucceeded()).isEqualTo(0L);
    assertThat(snapshot.getCallsInProgress()).isEqualTo(14L);
    assertThat(snapshot.getCallsFailed()).isEqualTo(0L);
    assertThat(snapshot.getMetricValues()).isEmpty();
    assertThat(snapshot.getDurationNano()).isEqualTo(TimeUnit.SECONDS.toNanos(5L));
  }

  @Test
  public void loadRecordingStreamTracerFactory_clientSideQueryCountsAggregation() {
    LoadRecordingStreamTracerFactory factory1 =
        new LoadRecordingStreamTracerFactory(counter, NOOP_CLIENT_STREAM_TRACER_FACTORY);
    ClientStreamTracer tracer = factory1.newClientStreamTracer(STREAM_INFO, new Metadata());
    ClientLoadSnapshot snapshot = counter.snapshot();
    assertThat(snapshot.getCallsIssued()).isEqualTo(1L);
    assertThat(snapshot.getCallsSucceeded()).isEqualTo(0L);
    assertThat(snapshot.getCallsInProgress()).isEqualTo(1L);
    assertThat(snapshot.getCallsFailed()).isEqualTo(0L);

    tracer.streamClosed(Status.OK);
    snapshot = counter.snapshot();
    assertThat(snapshot.getCallsIssued()).isEqualTo(0L);
    assertThat(snapshot.getCallsSucceeded()).isEqualTo(1L);
    assertThat(snapshot.getCallsInProgress()).isEqualTo(0L);
    assertThat(snapshot.getCallsFailed()).isEqualTo(0L);

    // Create a second LoadRecordingStreamTracerFactory with the same counter, stats are aggregated
    // together.
    LoadRecordingStreamTracerFactory factory2 =
        new LoadRecordingStreamTracerFactory(counter, NOOP_CLIENT_STREAM_TRACER_FACTORY);
    factory1.newClientStreamTracer(STREAM_INFO, new Metadata()).streamClosed(Status.ABORTED);
    factory2.newClientStreamTracer(STREAM_INFO, new Metadata()).streamClosed(Status.CANCELLED);
    snapshot = counter.snapshot();
    assertThat(snapshot.getCallsIssued()).isEqualTo(2L);
    assertThat(snapshot.getCallsSucceeded()).isEqualTo(0L);
    assertThat(snapshot.getCallsInProgress()).isEqualTo(0L);
    assertThat(snapshot.getCallsFailed()).isEqualTo(2L);
  }

  @Test
  public void metricsRecordingListener_backendMetricsAggregation() {
    MetricsRecordingListener listener1 = new MetricsRecordingListener(counter);
    OrcaLoadReport report =
        OrcaLoadReport.newBuilder()
            .setCpuUtilization(0.5345)
            .setMemUtilization(0.647)
            .putRequestCost("named-cost-1", 3453.3525)
            .putRequestCost("named-cost-2", 532543.14234)
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

    MetricValue namedMetric1 = snapshot.getMetricValues().get("named-cost-1");
    assertThat(namedMetric1.getNumReports()).isEqualTo(1);
    assertThat(namedMetric1.getTotalValue()).isEqualTo(3453.3525);

    MetricValue namedMetric2 = snapshot.getMetricValues().get("named-cost-2");
    assertThat(namedMetric2.getNumReports()).isEqualTo(1);
    assertThat(namedMetric2.getTotalValue()).isEqualTo(532543.14234);

    snapshot = counter.snapshot();
    assertThat(snapshot.getMetricValues()).isEmpty();

    MetricsRecordingListener listener2 = new MetricsRecordingListener(counter);
    report =
        OrcaLoadReport.newBuilder()
            .setCpuUtilization(0.3423)
            .setMemUtilization(0.654)
            .putUtilization("named-utilization", 0.7563)
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

    MetricValue namedMetric = snapshot.getMetricValues().get("named-utilization");
    assertThat(namedMetric.getNumReports()).isEqualTo(2);
    assertThat(namedMetric.getTotalValue()).isEqualTo(0.7563 + 0.7563);
  }

  @Test
  public void tracerWrappingSubchannelPicker_interceptPickResult_invalidPickResultNotIntercepted() {
    final SubchannelPicker picker = mock(SubchannelPicker.class);
    SubchannelPicker streamInstrSubchannelPicker = new TracerWrappingSubchannelPicker() {
      @Override
      protected SubchannelPicker delegate() {
        return picker;
      }

      @Override
      protected ClientStreamTracer.Factory wrapTracerFactory(
          ClientStreamTracer.Factory originFactory) {
        // NO-OP
        return originFactory;
      }
    };
    PickResult errorResult = PickResult.withError(Status.UNAVAILABLE.withDescription("Error"));
    PickResult droppedResult = PickResult.withDrop(Status.UNAVAILABLE.withDescription("Dropped"));
    PickResult emptyResult = PickResult.withNoResult();
    when(picker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(errorResult, droppedResult, emptyResult);
    PickSubchannelArgs args = mock(PickSubchannelArgs.class);

    PickResult interceptedErrorResult = streamInstrSubchannelPicker.pickSubchannel(args);
    PickResult interceptedDroppedResult = streamInstrSubchannelPicker.pickSubchannel(args);
    PickResult interceptedEmptyResult = streamInstrSubchannelPicker.pickSubchannel(args);
    assertThat(interceptedErrorResult).isSameInstanceAs(errorResult);
    assertThat(interceptedDroppedResult).isSameInstanceAs(droppedResult);
    assertThat(interceptedEmptyResult).isSameInstanceAs(emptyResult);
  }

  @Test
  public void loadRecordingSubchannelPicker_interceptPickResult_applyLoadRecorderToPickResult() {
    ClientStreamTracer.Factory mockFactory = mock(ClientStreamTracer.Factory.class);
    ClientStreamTracer mockTracer = mock(ClientStreamTracer.class);
    when(mockFactory
        .newClientStreamTracer(any(ClientStreamTracer.StreamInfo.class), any(Metadata.class)))
        .thenReturn(mockTracer);

    ClientLoadCounter localityCounter1 =
        new ClientLoadCounter(fakeClock.getStopwatchSupplier().get());
    ClientLoadCounter localityCounter2 =
        new ClientLoadCounter(fakeClock.getStopwatchSupplier().get());
    final PickResult pickResult1 = PickResult.withSubchannel(mock(Subchannel.class), mockFactory);
    final PickResult pickResult2 = PickResult.withSubchannel(mock(Subchannel.class));
    SubchannelPicker picker1 = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return pickResult1;
      }
    };
    SubchannelPicker picker2 = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return pickResult2;
      }
    };
    SubchannelPicker loadRecordingPicker1 =
        new LoadRecordingSubchannelPicker(localityCounter1, picker1);
    SubchannelPicker loadRecordingPicker2 =
        new LoadRecordingSubchannelPicker(localityCounter2, picker2);
    PickSubchannelArgs args = mock(PickSubchannelArgs.class);
    PickResult interceptedPickResult1 = loadRecordingPicker1.pickSubchannel(args);
    PickResult interceptedPickResult2 = loadRecordingPicker2.pickSubchannel(args);

    LoadRecordingStreamTracerFactory recorder1 =
        (LoadRecordingStreamTracerFactory) interceptedPickResult1.getStreamTracerFactory();
    LoadRecordingStreamTracerFactory recorder2 =
        (LoadRecordingStreamTracerFactory) interceptedPickResult2.getStreamTracerFactory();
    assertThat(recorder1.getCounter()).isSameInstanceAs(localityCounter1);
    assertThat(recorder2.getCounter()).isSameInstanceAs(localityCounter2);

    // Stream tracing is propagated to downstream tracers, which preserves PickResult's original
    // tracing functionality.
    Metadata metadata = new Metadata();
    interceptedPickResult1.getStreamTracerFactory().newClientStreamTracer(STREAM_INFO, metadata)
        .streamClosed(Status.OK);
    verify(mockFactory).newClientStreamTracer(same(STREAM_INFO), same(metadata));
    verify(mockTracer).streamClosed(Status.OK);
  }

  @Test
  public void metricsObservingSubchannelPicker_interceptPickResult_applyOrcaListenerToPickResult() {
    ClientStreamTracer.Factory mockFactory = mock(ClientStreamTracer.Factory.class);
    ClientStreamTracer mockTracer = mock(ClientStreamTracer.class);
    when(mockFactory
        .newClientStreamTracer(any(ClientStreamTracer.StreamInfo.class), any(Metadata.class)))
        .thenReturn(mockTracer);

    final PickResult pickResult1 = PickResult.withSubchannel(mock(Subchannel.class), mockFactory);
    final PickResult pickResult2 = PickResult.withSubchannel(mock(Subchannel.class));
    SubchannelPicker picker1 = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return pickResult1;
      }
    };
    SubchannelPicker picker2 = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return pickResult2;
      }
    };
    OrcaPerRequestUtil orcaPerRequestUtil = mock(OrcaPerRequestUtil.class);
    ClientStreamTracer.Factory metricsRecorder1 = mock(ClientStreamTracer.Factory.class);
    ClientStreamTracer.Factory metricsRecorder2 = mock(ClientStreamTracer.Factory.class);
    when(orcaPerRequestUtil.newOrcaClientStreamTracerFactory(any(ClientStreamTracer.Factory.class),
        any(OrcaPerRequestReportListener.class))).thenReturn(metricsRecorder1, metricsRecorder2);
    OrcaPerRequestReportListener listener1 = mock(OrcaPerRequestReportListener.class);
    OrcaPerRequestReportListener listener2 = mock(OrcaPerRequestReportListener.class);
    PickSubchannelArgs args = mock(PickSubchannelArgs.class);

    SubchannelPicker metricsObservingPicker1 =
        new MetricsObservingSubchannelPicker(listener1, picker1, orcaPerRequestUtil);
    SubchannelPicker metricsObservingPicker2 =
        new MetricsObservingSubchannelPicker(listener2, picker2, orcaPerRequestUtil);
    PickResult interceptedPickResult1 = metricsObservingPicker1.pickSubchannel(args);
    PickResult interceptedPickResult2 = metricsObservingPicker2.pickSubchannel(args);

    verify(orcaPerRequestUtil)
        .newOrcaClientStreamTracerFactory(any(ClientStreamTracer.Factory.class), same(listener1));
    verify(orcaPerRequestUtil)
        .newOrcaClientStreamTracerFactory(any(ClientStreamTracer.Factory.class), same(listener2));
    assertThat(interceptedPickResult1.getStreamTracerFactory()).isSameInstanceAs(metricsRecorder1);
    assertThat(interceptedPickResult2.getStreamTracerFactory()).isSameInstanceAs(metricsRecorder2);
  }
}
