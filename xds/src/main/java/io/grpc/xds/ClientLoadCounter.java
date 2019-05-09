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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Client side aggregator for load stats.
 *
 * <p>All methods except {@link #snapshot()} in this class are thread-safe.
 */
@NotThreadSafe
class ClientLoadCounter {
  private static final int THREAD_BALANCING_FACTOR = 64;
  private final AtomicLong callsInProgress = new AtomicLong();
  private final AtomicLong callsFinished = new AtomicLong();
  private final AtomicLong callsFailed = new AtomicLong();
  private final MetricRecorder[] metricRecorders = new MetricRecorder[THREAD_BALANCING_FACTOR];
  private boolean active = true;

  ClientLoadCounter() {
    for (int i = 0; i < THREAD_BALANCING_FACTOR; i++) {
      metricRecorders[i] = new MetricRecorder();
    }
  }

  @VisibleForTesting
  ClientLoadCounter(long callsInProgress, long callsFinished, long callsFailed) {
    this();
    this.callsInProgress.set(callsInProgress);
    this.callsFinished.set(callsFinished);
    this.callsFailed.set(callsFailed);
  }

  void incrementCallsInProgress() {
    callsInProgress.getAndIncrement();
  }

  void decrementCallsInProgress() {
    callsInProgress.getAndDecrement();
  }

  void incrementCallsFinished() {
    callsFinished.getAndIncrement();
  }

  void incrementCallsFailed() {
    callsFailed.getAndIncrement();
  }

  void recordMetric(String name, double value) {
    MetricRecorder recorder =
        metricRecorders[(int) (Thread.currentThread().getId() % THREAD_BALANCING_FACTOR)];
    recorder.addValue(name, value);
  }

  boolean isActive() {
    return active;
  }

  void setActive(boolean value) {
    active = value;
  }

  /**
   * Generate snapshot for recorded query counts and metrics since previous snapshot.
   *
   * <p>This method is not thread-safe and must be called from {@link
   * io.grpc.LoadBalancer.Helper#getSynchronizationContext()}.
   */
  ClientLoadSnapshot snapshot() {
    ClientLoadSnapshot res = new ClientLoadSnapshot();
    long numFailed = callsFailed.getAndSet(0);
    res.callsSucceed = callsFinished.getAndSet(0) - numFailed;
    res.callsInProgress = callsInProgress.get();
    res.callsFailed = numFailed;
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
    for (Map.Entry<String, MetricValue> entry : aggregatedValues.entrySet()) {
      res.metricValues.put(entry.getKey(), entry.getValue());
    }
    return res;
  }

  /**
   * A {@link ClientLoadSnapshot} represents a snapshot of {@link ClientLoadCounter} to be sent as
   * part of {@link io.envoyproxy.envoy.api.v2.endpoint.ClusterStats} to the balancer.
   */
  static final class ClientLoadSnapshot {

    long callsSucceed;
    long callsInProgress;
    long callsFailed;
    Map<String, MetricValue> metricValues = new HashMap<>();

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("callsSucceed", callsSucceed)
          .add("callsInProgress", callsInProgress)
          .add("callsFailed", callsFailed)
          .add("metricValues", metricValues)
          .toString();
    }
  }

  /** Atomic unit of recording for metric data. */
  static final class MetricValue {
    int numReports;
    double totalValue;

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("numReports", numReports)
          .add("totalValue", totalValue)
          .toString();
    }
  }

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
}
