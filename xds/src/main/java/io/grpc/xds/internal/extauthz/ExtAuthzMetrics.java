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

package io.grpc.xds.internal.extauthz;

import com.google.common.collect.ImmutableList;
import io.grpc.LongCounterMetricInstrument;
import io.grpc.MetricInstrumentRegistry;
import io.grpc.MetricRecorder;

/** Ext_authz counters defined by gRFC A92. */
public final class ExtAuthzMetrics {
  private static final LongCounterMetricInstrument CLIENT_ALLOWED = registerClient(
      "allowed_rpcs", "RPCs allowed by ext_authz or failure_mode_allow.");
  private static final LongCounterMetricInstrument CLIENT_DENIED = registerClient(
      "denied_rpcs", "RPCs denied by ext_authz.");
  private static final LongCounterMetricInstrument CLIENT_FILTER_DISABLED = registerClient(
      "filter_disabled_rpcs", "RPCs for which the ext_authz filter was disabled.");
  private static final LongCounterMetricInstrument CLIENT_FAILED = registerClient(
      "failed_rpcs", "RPCs for which the ext_authz call-out failed or returned invalid data.");

  private ExtAuthzMetrics() {}

  public static void recordClientAllowed(MetricRecorder recorder, String target) {
    recordClient(recorder, target, CLIENT_ALLOWED);
  }

  public static void recordClientDenied(MetricRecorder recorder, String target) {
    recordClient(recorder, target, CLIENT_DENIED);
  }

  public static void recordClientFilterDisabled(MetricRecorder recorder, String target) {
    recordClient(recorder, target, CLIENT_FILTER_DISABLED);
  }

  public static void recordClientFailed(MetricRecorder recorder, String target) {
    recordClient(recorder, target, CLIENT_FAILED);
  }

  private static void recordClient(MetricRecorder recorder, String target,
      LongCounterMetricInstrument instrument) {
    recorder.addLongCounter(instrument, 1, ImmutableList.of(target), ImmutableList.of());
  }

  private static LongCounterMetricInstrument registerClient(String suffix, String description) {
    return MetricInstrumentRegistry.getDefaultRegistry().registerLongCounter(
        "grpc.client_ext_authz." + suffix, "EXPERIMENTAL. " + description, "{RPCs}",
        ImmutableList.of("grpc.target"), ImmutableList.of(), true);
  }
}
