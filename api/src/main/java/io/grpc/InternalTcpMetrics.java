/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * TCP Metrics defined to be shared across transport implementations.
 */
@Internal
public final class InternalTcpMetrics {

  private InternalTcpMetrics() {
  }

  private static final List<String> OPTIONAL_LABELS = Arrays.asList(
      "network.local.address",
      "network.local.port",
      "network.peer.address",
      "network.peer.port");

  public static final DoubleHistogramMetricInstrument MIN_RTT_INSTRUMENT = 
      MetricInstrumentRegistry.getDefaultRegistry()
          .registerDoubleHistogram(
          "grpc.tcp.min_rtt",
          "Minimum round-trip time of a TCP connection",
          "s",
          getMinRttBuckets(),
          Collections.emptyList(),
          OPTIONAL_LABELS,
          false);

  public static final LongCounterMetricInstrument CONNECTIONS_CREATED_INSTRUMENT = 
      MetricInstrumentRegistry
      .getDefaultRegistry()
      .registerLongCounter(
          "grpc.tcp.connections_created",
          "The total number of TCP connections established.",
          "{connection}",
          Collections.emptyList(),
          OPTIONAL_LABELS,
          false);

  public static final LongUpDownCounterMetricInstrument CONNECTION_COUNT_INSTRUMENT = 
      MetricInstrumentRegistry
      .getDefaultRegistry()
      .registerLongUpDownCounter(
          "grpc.tcp.connection_count",
          "The current number of active TCP connections.",
          "{connection}",
          Collections.emptyList(),
          OPTIONAL_LABELS,
          false);

  public static final LongCounterMetricInstrument PACKETS_RETRANSMITTED_INSTRUMENT = 
      MetricInstrumentRegistry
      .getDefaultRegistry()
      .registerLongCounter(
          "grpc.tcp.packets_retransmitted",
          "The total number of packets retransmitted for all TCP connections.",
          "{packet}",
          Collections.emptyList(),
          OPTIONAL_LABELS,
          false);

  public static final LongCounterMetricInstrument RECURRING_RETRANSMITS_INSTRUMENT = 
      MetricInstrumentRegistry
      .getDefaultRegistry()
      .registerLongCounter(
          "grpc.tcp.recurring_retransmits",
          "The total number of times the retransmit timer popped for all TCP"
              + " connections.",
          "{timeout}",
          Collections.emptyList(),
          OPTIONAL_LABELS,
          false);

  private static List<Double> getMinRttBuckets() {
    List<Double> buckets = new ArrayList<>(100);
    for (int i = 1; i <= 100; i++) {
      buckets.add(1e-6 * Math.pow(2.0, i * 0.24));
    }
    return Collections.unmodifiableList(buckets);
  }
}
