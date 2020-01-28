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

import static com.google.common.base.Preconditions.checkNotNull;

import com.github.udpa.udpa.data.orca.v1.OrcaLoadReport;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.util.ForwardingClientStreamTracer;
import io.grpc.xds.OrcaOobUtil.OrcaOobReportListener;
import io.grpc.xds.OrcaPerRequestUtil.OrcaPerRequestReportListener;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Client side load stats recorder that provides RPC counting and metrics recording as name-value
 * pairs.
 *
 * <p>All methods except {@link #snapshot()} in this class are thread-safe.
 */
@NotThreadSafe
final class ClientLoadCounter {

  private static final int THREAD_BALANCING_FACTOR = 64;
  private final AtomicLong callsInProgress = new AtomicLong();
  private final AtomicLong callsSucceeded = new AtomicLong();
  private final AtomicLong callsFailed = new AtomicLong();
  private final AtomicLong callsIssued = new AtomicLong();
  private final MetricRecorder[] metricRecorders = new MetricRecorder[THREAD_BALANCING_FACTOR];

  // True if this counter continues to record stats after next snapshot. Otherwise, it will be
  // discarded.
  private boolean active;

  ClientLoadCounter() {
    for (int i = 0; i < THREAD_BALANCING_FACTOR; i++) {
      metricRecorders[i] = new MetricRecorder();
    }
    active = true;
  }

  /**
   * Must only be used for testing.
   */
  @VisibleForTesting
  ClientLoadCounter(long callsSucceeded, long callsInProgress, long callsFailed, long callsIssued) {
    this();
    this.callsSucceeded.set(callsSucceeded);
    this.callsInProgress.set(callsInProgress);
    this.callsFailed.set(callsFailed);
    this.callsIssued.set(callsIssued);
  }

  void recordCallStarted() {
    callsIssued.getAndIncrement();
    callsInProgress.getAndIncrement();
  }

  void recordCallFinished(Status status) {
    callsInProgress.getAndDecrement();
    if (status.isOk()) {
      callsSucceeded.getAndIncrement();
    } else {
      callsFailed.getAndIncrement();
    }
  }

  void recordMetric(String name, double value) {
    MetricRecorder recorder =
        metricRecorders[(int) (Thread.currentThread().getId() % THREAD_BALANCING_FACTOR)];
    recorder.addValue(name, value);
  }

  /**
   * Generates a snapshot for load stats recorded in this counter. Successive snapshots represent
   * load stats recorded for the interval since the previous snapshot. So taking a snapshot clears
   * the counter state except for ongoing RPC recordings.
   *
   * <p>This method is not thread-safe and must be called from {@link
   * io.grpc.LoadBalancer.Helper#getSynchronizationContext()}.
   */
  ClientLoadSnapshot snapshot() {
    Map<String, MetricValue> aggregatedValues = new HashMap<>();
    for (MetricRecorder recorder : metricRecorders) {
      Map<String, MetricValue> map = recorder.takeAll();
      for (Map.Entry<String, MetricValue> entry : map.entrySet()) {
        MetricValue curr = aggregatedValues.get(entry.getKey());
        if (curr == null) {
          curr = new MetricValue();
          aggregatedValues.put(entry.getKey(), curr);
        }
        MetricValue diff = entry.getValue();
        curr.numReports += diff.numReports;
        curr.totalValue += diff.totalValue;
      }
    }
    return new ClientLoadSnapshot(callsSucceeded.getAndSet(0),
        callsInProgress.get(),
        callsFailed.getAndSet(0),
        callsIssued.getAndSet(0),
        aggregatedValues);
  }

  void setActive(boolean value) {
    active = value;
  }

  boolean isActive() {
    return active;
  }

  /**
   * A {@link ClientLoadSnapshot} represents a snapshot of {@link ClientLoadCounter}, which is a
   * read-only copy of load stats recorded for some period of time.
   */
  static final class ClientLoadSnapshot {

    @VisibleForTesting
    @SuppressWarnings("unchecked")
    static final ClientLoadSnapshot EMPTY_SNAPSHOT =
        new ClientLoadSnapshot(0, 0, 0, 0, Collections.EMPTY_MAP);
    private final long callsSucceeded;
    private final long callsInProgress;
    private final long callsFailed;
    private final long callsIssued;
    private final Map<String, MetricValue> metricValues;

    /**
     * External usage must only be for testing.
     */
    @VisibleForTesting
    ClientLoadSnapshot(long callsSucceeded,
        long callsInProgress,
        long callsFailed,
        long callsIssued,
        Map<String, MetricValue> metricValues) {
      this.callsSucceeded = callsSucceeded;
      this.callsInProgress = callsInProgress;
      this.callsFailed = callsFailed;
      this.callsIssued = callsIssued;
      this.metricValues = checkNotNull(metricValues, "metricValues");
    }

    long getCallsSucceeded() {
      return callsSucceeded;
    }

    long getCallsInProgress() {
      return callsInProgress;
    }

    long getCallsFailed() {
      return callsFailed;
    }

    long getCallsIssued() {
      return callsIssued;
    }

    Map<String, MetricValue> getMetricValues() {
      return Collections.unmodifiableMap(metricValues);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("callsSucceeded", callsSucceeded)
          .add("callsInProgress", callsInProgress)
          .add("callsFailed", callsFailed)
          .add("callsIssued", callsIssued)
          .add("metricValues", metricValues)
          .toString();
    }
  }

  /**
   * Atomic unit of recording for metric data.
   */
  static final class MetricValue {

    private int numReports;
    private double totalValue;

    private MetricValue() {
      this(0, 0);
    }

    /**
     * Must only be used for testing.
     */
    @VisibleForTesting
    MetricValue(int numReports, double totalValue) {
      this.numReports = numReports;
      this.totalValue = totalValue;
    }

    long getNumReports() {
      return numReports;
    }

    double getTotalValue() {
      return totalValue;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("numReports", numReports)
          .add("totalValue", totalValue)
          .toString();
    }
  }

  /**
   * Single contention-balanced bucket for recording metric data.
   */
  private static class MetricRecorder {

    private Map<String, MetricValue> metricValues = new HashMap<>();

    synchronized void addValue(String metricName, double value) {
      MetricValue currValue = metricValues.get(metricName);
      if (currValue == null) {
        currValue = new MetricValue();
      }
      currValue.numReports++;
      currValue.totalValue += value;
      metricValues.put(metricName, currValue);
    }

    synchronized Map<String, MetricValue> takeAll() {
      Map<String, MetricValue> ret = metricValues;
      metricValues = new HashMap<>();
      return ret;
    }
  }

  /**
   * An {@link LoadRecordingStreamTracerFactory} instance for creating client stream tracers that
   * records and aggregates client-side load data into an {@link ClientLoadCounter} object.
   */
  @ThreadSafe
  @VisibleForTesting
  static final class LoadRecordingStreamTracerFactory extends ClientStreamTracer.Factory {

    private final ClientStreamTracer.Factory delegate;
    private final ClientLoadCounter counter;

    LoadRecordingStreamTracerFactory(ClientLoadCounter counter,
        ClientStreamTracer.Factory delegate) {
      this.counter = checkNotNull(counter, "counter");
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
      counter.recordCallStarted();
      final ClientStreamTracer delegateTracer = delegate.newClientStreamTracer(info, headers);
      return new ForwardingClientStreamTracer() {
        @Override
        protected ClientStreamTracer delegate() {
          return delegateTracer;
        }

        @Override
        public void streamClosed(Status status) {
          counter.recordCallFinished(status);
          delegate().streamClosed(status);
        }
      };
    }

    @VisibleForTesting
    ClientLoadCounter getCounter() {
      return counter;
    }

    @VisibleForTesting
    ClientStreamTracer.Factory delegate() {
      return delegate;
    }
  }

  /**
   * Listener implementation to receive backend metrics and record metric values in the provided
   * {@link ClientLoadCounter}.
   */
  @ThreadSafe
  static final class MetricsRecordingListener
      implements OrcaPerRequestReportListener, OrcaOobReportListener {

    private final ClientLoadCounter counter;

    MetricsRecordingListener(ClientLoadCounter counter) {
      this.counter = checkNotNull(counter, "counter");
    }

    @Override
    public void onLoadReport(OrcaLoadReport report) {
      counter.recordMetric("cpu_utilization", report.getCpuUtilization());
      counter.recordMetric("mem_utilization", report.getMemUtilization());
      for (Map.Entry<String, Double> entry : report.getRequestCostMap().entrySet()) {
        counter.recordMetric(entry.getKey(), entry.getValue());
      }
      for (Map.Entry<String, Double> entry : report.getUtilizationMap().entrySet()) {
        counter.recordMetric(entry.getKey(), entry.getValue());
      }
    }

    @VisibleForTesting
    ClientLoadCounter getCounter() {
      return counter;
    }
  }

  /**
   * Base class for {@link SubchannelPicker} wrapper classes that intercept "RPC-capable"
   * {@link PickResult}s with applying a custom {@link ClientStreamTracer.Factory} for stream
   * instrumenting purposes.
   */
  @VisibleForTesting
  abstract static class TracerWrappingSubchannelPicker extends SubchannelPicker {

    private static final ClientStreamTracer NOOP_CLIENT_STREAM_TRACER =
        new ClientStreamTracer() {
        };
    private static final ClientStreamTracer.Factory NOOP_CLIENT_STREAM_TRACER_FACTORY =
        new ClientStreamTracer.Factory() {
          @Override
          public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
            return NOOP_CLIENT_STREAM_TRACER;
          }
        };

    protected abstract SubchannelPicker delegate();

    protected abstract ClientStreamTracer.Factory wrapTracerFactory(
        ClientStreamTracer.Factory originFactory);

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      PickResult result = delegate().pickSubchannel(args);
      if (!result.getStatus().isOk()) {
        return result;
      }
      if (result.getSubchannel() == null) {
        return result;
      }
      ClientStreamTracer.Factory originFactory = result.getStreamTracerFactory();
      if (originFactory == null) {
        originFactory = NOOP_CLIENT_STREAM_TRACER_FACTORY;
      }
      return PickResult.withSubchannel(result.getSubchannel(), wrapTracerFactory(originFactory));
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("delegate", delegate()).toString();
    }
  }

  /**
   * A wrapper class that wraps a {@link SubchannelPicker} instance and associate it with a {@link
   * ClientLoadCounter}. All "RPC-capable" {@link PickResult}s picked will be intercepted with
   * client side load recording logic such that RPC activities occurring in the {@link PickResult}'s
   * {@link io.grpc.LoadBalancer.Subchannel} will be recorded in the associated {@link
   * ClientLoadCounter}.
   */
  @ThreadSafe
  static final class LoadRecordingSubchannelPicker extends TracerWrappingSubchannelPicker {

    private final ClientLoadCounter counter;
    private final SubchannelPicker delegate;

    LoadRecordingSubchannelPicker(ClientLoadCounter counter, SubchannelPicker delegate) {
      this.counter = checkNotNull(counter, "counter");
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    protected SubchannelPicker delegate() {
      return delegate;
    }

    @Override
    protected ClientStreamTracer.Factory wrapTracerFactory(
        ClientStreamTracer.Factory originFactory) {
      return new LoadRecordingStreamTracerFactory(counter, originFactory);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(LoadRecordingSubchannelPicker.class)
          .add("delegate", delegate)
          .toString();
    }
  }

  /**
   * A wrapper class that wraps {@link SubchannelPicker} instance and associate it with an {@link
   * OrcaPerRequestReportListener}. All "RPC-capable" {@link PickResult}s picked will be intercepted
   * with the logic of registering the listener for observing backend metrics.
   */
  @ThreadSafe
  static final class MetricsObservingSubchannelPicker extends TracerWrappingSubchannelPicker {

    private final OrcaPerRequestReportListener listener;
    private final SubchannelPicker delegate;
    private final OrcaPerRequestUtil orcaPerRequestUtil;

    MetricsObservingSubchannelPicker(OrcaPerRequestReportListener listener,
        SubchannelPicker delegate,
        OrcaPerRequestUtil orcaPerRequestUtil) {
      this.listener = checkNotNull(listener, "listener");
      this.delegate = checkNotNull(delegate, "delegate");
      this.orcaPerRequestUtil = checkNotNull(orcaPerRequestUtil, "orcaPerRequestUtil");
    }

    @Override
    protected SubchannelPicker delegate() {
      return delegate;
    }

    @Override
    protected ClientStreamTracer.Factory wrapTracerFactory(
        ClientStreamTracer.Factory originFactory) {
      return orcaPerRequestUtil.newOrcaClientStreamTracerFactory(originFactory, listener);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(MetricsObservingSubchannelPicker.class)
          .add("delegate", delegate)
          .toString();
    }
  }
}
