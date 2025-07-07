/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.internal;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.grpc.LongCounterMetricInstrument;
import io.grpc.LongUpDownCounterMetricInstrument;
import io.grpc.MetricInstrumentRegistry;
import io.grpc.MetricRecorder;

public final class SubchannelMetrics {

  private static final LongCounterMetricInstrument disconnections;
  private static final LongCounterMetricInstrument connectionAttemptsSucceeded;
  private static final LongCounterMetricInstrument connectionAttemptsFailed;
  private static final LongUpDownCounterMetricInstrument openConnections;
  private final MetricRecorder metricRecorder;

  public SubchannelMetrics(MetricRecorder metricRecorder) {
    this.metricRecorder = metricRecorder;
  }

  static {
    MetricInstrumentRegistry metricInstrumentRegistry
        = MetricInstrumentRegistry.getDefaultRegistry();
    disconnections = metricInstrumentRegistry.registerLongCounter(
        "grpc.subchannel.disconnections1",
        "EXPERIMENTAL. Number of times the selected subchannel becomes disconnected",
        "{disconnection}",
        Lists.newArrayList("grpc.target"),
        Lists.newArrayList("grpc.lb.backend_service", "grpc.lb.locality", "grpc.disconnect_error"),
        false
    );

    connectionAttemptsSucceeded = metricInstrumentRegistry.registerLongCounter(
        "grpc.subchannel.connection_attempts_succeeded",
        "EXPERIMENTAL. Number of successful connection attempts",
        "{attempt}",
        Lists.newArrayList("grpc.target"),
        Lists.newArrayList("grpc.lb.backend_service", "grpc.lb.locality"),
        false
    );

    connectionAttemptsFailed = metricInstrumentRegistry.registerLongCounter(
        "grpc.subchannel.connection_attempts_failed",
        "EXPERIMENTAL. Number of failed connection attempts",
        "{attempt}",
        Lists.newArrayList("grpc.target"),
        Lists.newArrayList("grpc.lb.backend_service", "grpc.lb.locality"),
        false
    );

    openConnections = metricInstrumentRegistry.registerLongUpDownCounter(
        "grpc.subchannel.open_connections",
        "EXPERIMENTAL. Number of open connections.",
        "{connection}",
        Lists.newArrayList("grpc.target"),
        Lists.newArrayList("grpc.security_level", "grpc.lb.backend_service", "grpc.lb.locality"),
        false
    );
  }

  public void recordConnectionAttemptSucceeded(OtelMetricsAttributes labelSet) {
    metricRecorder
        .addLongCounter(connectionAttemptsSucceeded, 1,
            ImmutableList.of(labelSet.target),
            ImmutableList.of(labelSet.backendService, labelSet.locality));
    metricRecorder
        .addLongUpDownCounter(openConnections, 1,
            ImmutableList.of(labelSet.target),
            ImmutableList.of(labelSet.securityLevel, labelSet.backendService, labelSet.locality));
  }

  public void recordConnectionAttemptFailed(OtelMetricsAttributes labelSet) {
    metricRecorder
        .addLongCounter(connectionAttemptsFailed, 1,
            ImmutableList.of(labelSet.target),
            ImmutableList.of(labelSet.backendService, labelSet.locality));
  }

  public void recordDisconnection(OtelMetricsAttributes labelSet) {
    metricRecorder
        .addLongCounter(disconnections, 1,
            ImmutableList.of(labelSet.target),
            ImmutableList.of(labelSet.backendService, labelSet.locality, labelSet.disconnectError));
    metricRecorder
        .addLongUpDownCounter(openConnections, -1,
            ImmutableList.of(labelSet.target),
            ImmutableList.of(labelSet.securityLevel, labelSet.backendService, labelSet.locality));
  }
}
