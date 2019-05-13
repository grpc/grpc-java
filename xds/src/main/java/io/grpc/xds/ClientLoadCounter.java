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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.util.ForwardingClientStreamTracer;
import io.grpc.xds.XdsLoadStatsStore.StatsCounter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Client side aggregator for load stats.
 *
 * <p>All methods except {@link #snapshot()} in this class are thread-safe.
 */
@NotThreadSafe
final class ClientLoadCounter extends XdsLoadStatsStore.StatsCounter {

  private static final int THREAD_BALANCING_FACTOR = 64;
  private final AtomicLong callsInProgress = new AtomicLong();
  private final AtomicLong callsFinished = new AtomicLong();
  private final AtomicLong callsFailed = new AtomicLong();
  private final MetricRecorder[] metricRecorders = new MetricRecorder[THREAD_BALANCING_FACTOR];

  ClientLoadCounter() {
    for (int i = 0; i < THREAD_BALANCING_FACTOR; i++) {
      metricRecorders[i] = new MetricRecorder();
    }
  }

  /**
   * Must only be used for testing.
   */
  @VisibleForTesting
  ClientLoadCounter(long callsFinished, long callsInProgress, long callsFailed) {
    this();
    this.callsFinished.set(callsFinished);
    this.callsInProgress.set(callsInProgress);
    this.callsFailed.set(callsFailed);
  }

  @Override
  void incrementCallsInProgress() {
    callsInProgress.getAndIncrement();
  }

  @Override
  void decrementCallsInProgress() {
    callsInProgress.getAndDecrement();
  }

  @Override
  void incrementCallsFinished() {
    callsFinished.getAndIncrement();
  }

  @Override
  void incrementCallsFailed() {
    callsFailed.getAndIncrement();
  }

  @Override
  void recordMetric(String name, double value) {
    MetricRecorder recorder =
        metricRecorders[(int) (Thread.currentThread().getId() % THREAD_BALANCING_FACTOR)];
    recorder.addValue(name, value);
  }

  /**
   * Generate snapshot for recorded query counts and metrics since previous snapshot.
   *
   * <p>This method is not thread-safe and must be called from {@link
   * io.grpc.LoadBalancer.Helper#getSynchronizationContext()}.
   */
  @Override
  public ClientLoadSnapshot snapshot() {
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
    return new ClientLoadSnapshot(callsFinished.getAndSet(0),
        callsInProgress.get(),
        callsFailed.getAndSet(0),
        aggregatedValues);
  }

  /**
   * A {@link ClientLoadSnapshot} represents a snapshot of {@link ClientLoadCounter} to be sent as
   * part of {@link io.envoyproxy.envoy.api.v2.endpoint.ClusterStats} to the balancer.
   */
  static final class ClientLoadSnapshot {

    @VisibleForTesting
    static final ClientLoadSnapshot EMPTY_SNAPSHOT =
        new ClientLoadSnapshot(0, 0, 0, new HashMap<String, MetricValue>());
    private final long callsFinished;
    private final long callsInProgress;
    private final long callsFailed;
    private final Map<String, MetricValue> metricValues;

    /**
     * External usage must only be for testing.
     */
    @VisibleForTesting
    ClientLoadSnapshot(long callsFinished,
        long callsInProgress,
        long callsFailed,
        Map<String, MetricValue> metricValues) {
      this.callsFinished = callsFinished;
      this.callsInProgress = callsInProgress;
      this.callsFailed = callsFailed;
      this.metricValues = checkNotNull(metricValues, "metricValues");
    }

    long getCallsFinished() {
      return callsFinished;
    }

    long getCallsInProgress() {
      return callsInProgress;
    }

    long getCallsFailed() {
      return callsFailed;
    }

    Map<String, MetricValue> getMetricValues() {
      return Collections.unmodifiableMap(metricValues);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("callsFinished", callsFinished)
          .add("callsInProgress", callsInProgress)
          .add("callsFailed", callsFailed)
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

    Map<String, MetricValue> metricValues = new HashMap<>();

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
   * An {@link XdsClientLoadRecorder} instance records and aggregates client-side load data into an
   * {@link ClientLoadCounter} object.
   */
  @ThreadSafe
  static final class XdsClientLoadRecorder extends ClientStreamTracer.Factory {

    private final ClientStreamTracer.Factory delegate;
    private final StatsCounter counter;

    XdsClientLoadRecorder(StatsCounter counter, ClientStreamTracer.Factory delegate) {
      this.counter = checkNotNull(counter, "counter");
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
      counter.incrementCallsInProgress();
      final ClientStreamTracer delegateTracer = delegate.newClientStreamTracer(info, headers);
      return new ForwardingClientStreamTracer() {
        @Override
        protected ClientStreamTracer delegate() {
          return delegateTracer;
        }

        @Override
        public void streamClosed(Status status) {
          counter.incrementCallsFinished();
          counter.decrementCallsInProgress();
          if (!status.isOk()) {
            counter.incrementCallsFailed();
          }
          delegate().streamClosed(status);
        }
      };
    }
  }
}
