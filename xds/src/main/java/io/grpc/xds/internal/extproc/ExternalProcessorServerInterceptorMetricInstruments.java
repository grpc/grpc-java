/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.xds.internal.extproc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import io.grpc.DoubleHistogramMetricInstrument;
import io.grpc.MetricInstrumentRegistry;
import io.grpc.internal.GrpcUtil;
import java.util.List;

/**
 * Holds metric instrument definitions for server-side external processing.
 */
public final class ExternalProcessorServerInterceptorMetricInstruments {
  @VisibleForTesting
  public static DoubleHistogramMetricInstrument clientHeadersDuration;
  @VisibleForTesting
  public static DoubleHistogramMetricInstrument clientHalfCloseDuration;
  @VisibleForTesting
  public static DoubleHistogramMetricInstrument serverHeadersDuration;
  @VisibleForTesting
  public static DoubleHistogramMetricInstrument serverTrailersDuration;

  // Copied from io.grpc.opentelemetry.internal.OpenTelemetryConstants.LATENCY_BUCKETS
  private static final List<Double> LATENCY_BUCKETS = ImmutableList.of(
      0d,     0.00001d, 0.00005d, 0.0001d, 0.0003d, 0.0006d, 0.0008d, 0.001d, 0.002d,
      0.003d, 0.004d,   0.005d,   0.006d,  0.008d,  0.01d,   0.013d,  0.016d, 0.02d,
      0.025d, 0.03d,    0.04d,    0.05d,   0.065d,  0.08d,   0.1d,    0.13d,  0.16d,
      0.2d,   0.25d,    0.3d,     0.4d,    0.5d,    0.65d,   0.8d,    1d,     2d,
      5d,     10d,      20d,      50d,     100d);

  static {
    initMetricInstruments();
  }

  public static synchronized void initMetricInstruments() {
    if (GrpcUtil.getFlag("GRPC_EXPERIMENTAL_XDS_EXT_PROC_ON_SERVER", false)) {
      if (clientHeadersDuration == null) {
        MetricInstrumentRegistry registry = MetricInstrumentRegistry.getDefaultRegistry();

        clientHeadersDuration = registry.registerDoubleHistogram(
            "grpc.server_ext_proc.client_headers_duration",
            "Time between when the ext_proc filter sees the client's headers and when "
                + "it allows those headers to continue on to the next filter",
            "s",
            LATENCY_BUCKETS,
            ImmutableList.of(),
            ImmutableList.of(),
            true);

        clientHalfCloseDuration = registry.registerDoubleHistogram(
            "grpc.server_ext_proc.client_half_close_duration",
            "Time between when the ext_proc filter sees the client's half-close and when "
                + "it allows that half-close to continue on to the next filter",
            "s",
            LATENCY_BUCKETS,
            ImmutableList.of(),
            ImmutableList.of(),
            true);

        serverHeadersDuration = registry.registerDoubleHistogram(
            "grpc.server_ext_proc.server_headers_duration",
            "Time between when the ext_proc filter sees the server's headers and when "
                + "it allows those headers to continue on to the next filter",
            "s",
            LATENCY_BUCKETS,
            ImmutableList.of(),
            ImmutableList.of(),
            true);

        serverTrailersDuration = registry.registerDoubleHistogram(
            "grpc.server_ext_proc.server_trailers_duration",
            "Time between when the ext_proc filter sees the server's trailers and when "
                + "it allows those trailers to continue on to the next filter",
            "s",
            LATENCY_BUCKETS,
            ImmutableList.of(),
            ImmutableList.of(),
            true);
      }
    }
  }

  private ExternalProcessorServerInterceptorMetricInstruments() {}
}
