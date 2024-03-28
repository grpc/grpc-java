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

package io.grpc.services;

import com.google.common.annotations.VisibleForTesting;
import com.google.errorprone.annotations.InlineMe;
import io.grpc.Context;
import io.grpc.ExperimentalApi;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Utility to record call metrics for load-balancing. One instance per call.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/6012")
@ThreadSafe
public final class CallMetricRecorder {
  private static final CallMetricRecorder NOOP = new CallMetricRecorder().disable();

  static final Context.Key<CallMetricRecorder> CONTEXT_KEY =
      Context.key("io.grpc.services.CallMetricRecorder");

  private final AtomicReference<ConcurrentHashMap<String, Double>> utilizationMetrics =
      new AtomicReference<>();
  private final AtomicReference<ConcurrentHashMap<String, Double>> requestCostMetrics =
      new AtomicReference<>();
  private final AtomicReference<ConcurrentHashMap<String, Double>> namedMetrics =
      new AtomicReference<>();
  private double cpuUtilizationMetric = 0;
  private double applicationUtilizationMetric = 0;
  private double memoryUtilizationMetric = 0;
  private double qps = 0;
  private double eps = 0;
  private volatile boolean disabled;

  /**
   * Returns the call metric recorder attached to the current {@link Context}.  If there is none,
   * returns a no-op recorder.
   *
   * <p><strong>IMPORTANT:</strong>It returns the recorder specifically for the current RPC call.
   * <b>DO NOT</b> save the returned object or share it between different RPC calls.
   *
   * <p><strong>IMPORTANT:</strong>It must be called under the {@link Context} under which the RPC
   * handler was called.  If it is called from a different thread, the Context must be propagated to
   * the same thread, e.g., with {@link Context#wrap(Runnable)}.
   *
   * @since 1.23.0
   */
  public static CallMetricRecorder getCurrent() {
    CallMetricRecorder recorder = CONTEXT_KEY.get();
    return recorder != null ? recorder : NOOP;
  }

  /**
   * Records a call metric measurement for utilization in the range [0, 1]. Values outside the valid
   * range are ignored. If RPC has already finished, this method is no-op.
   *
   * <p>A latter record will overwrite its former name-sakes.
   *
   * @return this recorder object
   * @since 1.23.0
   */
  public CallMetricRecorder recordUtilizationMetric(String name, double value) {
    if (disabled || !MetricRecorderHelper.isUtilizationValid(value)) {
      return this;
    }
    if (utilizationMetrics.get() == null) {
      // The chance of race of creation of the map should be very small, so it should be fine
      // to create these maps that might be discarded.
      utilizationMetrics.compareAndSet(null, new ConcurrentHashMap<String, Double>());
    }
    utilizationMetrics.get().put(name, value);
    return this;
  }

  /**
   * Records a call metric measurement for request cost.
   * If RPC has already finished, this method is no-op.
   *
   * <p>A latter record will overwrite its former name-sakes.
   *
   * @return this recorder object
   * @since 1.47.0
   * @deprecated use {@link #recordRequestCostMetric} instead.
   *     This method will be removed in the future.
   */
  @Deprecated
  @InlineMe(replacement = "this.recordRequestCostMetric(name, value)")
  public CallMetricRecorder recordCallMetric(String name, double value) {
    return recordRequestCostMetric(name, value);
  }

  /**
   * Records a call metric measurement for request cost.
   * If RPC has already finished, this method is no-op.
   *
   * <p>A latter record will overwrite its former name-sakes.
   *
   * @return this recorder object
   * @since 1.48.1
   */
  public CallMetricRecorder recordRequestCostMetric(String name, double value) {
    if (disabled) {
      return this;
    }
    if (requestCostMetrics.get() == null) {
      // The chance of race of creation of the map should be very small, so it should be fine
      // to create these maps that might be discarded.
      requestCostMetrics.compareAndSet(null, new ConcurrentHashMap<String, Double>());
    }
    requestCostMetrics.get().put(name, value);
    return this;
  }

  /**
   * Records an application-specific opaque custom metric measurement. If RPC has already finished,
   * this method is no-op.
   *
   * <p>A latter record will overwrite its former name-sakes.
   *
   * @return this recorder object
   */
  public CallMetricRecorder recordNamedMetric(String name, double value) {
    if (disabled) {
      return this;
    }
    if (namedMetrics.get() == null) {
      // The chance of race of creation of the map should be very small, so it should be fine
      // to create these maps that might be discarded.
      namedMetrics.compareAndSet(null, new ConcurrentHashMap<String, Double>());
    }
    namedMetrics.get().put(name, value);
    return this;
  }

  /**
   * Records a call metric measurement for CPU utilization in the range [0, inf). Values outside the
   * valid range are ignored. If RPC has already finished, this method is no-op.
   *
   * <p>A latter record will overwrite its former name-sakes.
   *
   * @return this recorder object
   * @since 1.47.0
   */
  public CallMetricRecorder recordCpuUtilizationMetric(double value) {
    if (disabled || !MetricRecorderHelper.isCpuOrApplicationUtilizationValid(value)) {
      return this;
    }
    cpuUtilizationMetric = value;
    return this;
  }

  /**
   * Records a call metric measurement for application specific utilization in the range [0, inf).
   * Values outside the valid range are ignored. If RPC has already finished, this method is no-op.
   *
   * <p>A latter record will overwrite its former name-sakes.
   *
   * @return this recorder object
   */
  public CallMetricRecorder recordApplicationUtilizationMetric(double value) {
    if (disabled || !MetricRecorderHelper.isCpuOrApplicationUtilizationValid(value)) {
      return this;
    }
    applicationUtilizationMetric = value;
    return this;
  }

  /**
   * Records a call metric measurement for memory utilization in the range [0, 1]. Values outside
   * the valid range are ignored. If RPC has already finished, this method is no-op.
   *
   * <p>A latter record will overwrite its former name-sakes.
   *
   * @return this recorder object
   * @since 1.47.0
   */
  public CallMetricRecorder recordMemoryUtilizationMetric(double value) {
    if (disabled || !MetricRecorderHelper.isUtilizationValid(value)) {
      return this;
    }
    memoryUtilizationMetric = value;
    return this;
  }

  /**
   * Records a call metric measurement for queries per second (qps) in the range [0, inf). Values
   * outside the valid range are ignored. If RPC has already finished, this method is no-op.
   *
   * <p>A latter record will overwrite its former name-sakes.
   *
   * @return this recorder object
   * @since 1.54.0
   */
  public CallMetricRecorder recordQpsMetric(double value) {
    if (disabled || !MetricRecorderHelper.isRateValid(value)) {
      return this;
    }
    qps = value;
    return this;
  }

  /**
   * Records a call metric measurement for errors per second (eps) in the range [0, inf). Values
   * outside the valid range are ignored. If RPC has already finished, this method is no-op.
   *
   * <p>A latter record will overwrite its former name-sakes.
   *
   * @return this recorder object
   */
  public CallMetricRecorder recordEpsMetric(double value) {
    if (disabled || !MetricRecorderHelper.isRateValid(value)) {
      return this;
    }
    eps = value;
    return this;
  }

  /**
   * Returns all request cost metric values. No more metric values will be recorded after this
   * method is called. Calling this method multiple times returns the same collection of metric
   * values.
   *
   * @return a map containing all saved metric name-value pairs.
   */
  Map<String, Double> finalizeAndDump() {
    disabled = true;
    Map<String, Double> savedMetrics = requestCostMetrics.get();
    if (savedMetrics == null) {
      return Collections.emptyMap();
    }
    return Collections.unmodifiableMap(savedMetrics);
  }

  /**
   * Returns all save metric values. No more metric values will be recorded after this method is
   * called. Calling this method multiple times returns the same collection of metric values.
   *
   * @return a per-request ORCA reports containing all saved metrics.
   */
  MetricReport finalizeAndDump2() {
    Map<String, Double> savedRequestCostMetrics = finalizeAndDump();
    Map<String, Double> savedUtilizationMetrics = utilizationMetrics.get();
    Map<String, Double> savedNamedMetrics = namedMetrics.get();
    if (savedUtilizationMetrics == null) {
      savedUtilizationMetrics = Collections.emptyMap();
    }
    if (savedNamedMetrics == null) {
      savedNamedMetrics = Collections.emptyMap();
    }
    return new MetricReport(cpuUtilizationMetric, applicationUtilizationMetric,
        memoryUtilizationMetric, qps, eps, Collections.unmodifiableMap(savedRequestCostMetrics),
        Collections.unmodifiableMap(savedUtilizationMetrics),
        Collections.unmodifiableMap(savedNamedMetrics)
    );
  }

  @VisibleForTesting
  boolean isDisabled() {
    return disabled;
  }

  /**
   * Turn this recorder into a no-op one.
   */
  private CallMetricRecorder disable() {
    disabled = true;
    return this;
  }
}
