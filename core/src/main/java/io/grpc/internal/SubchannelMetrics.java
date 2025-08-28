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
import javax.annotation.Nullable;

final class SubchannelMetrics {

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
        "grpc.subchannel.disconnections",
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

  public void recordConnectionAttemptSucceeded(String target, String backendService,
                                               String locality, String securityLevel) {
    metricRecorder
        .addLongCounter(connectionAttemptsSucceeded, 1,
            ImmutableList.of(target),
            ImmutableList.of(backendService, locality));
    metricRecorder
        .addLongUpDownCounter(openConnections, 1,
            ImmutableList.of(target),
            ImmutableList.of(securityLevel, backendService, locality));
  }

  public void recordConnectionAttemptFailed(String target, String backendService, String locality) {
    metricRecorder
        .addLongCounter(connectionAttemptsFailed, 1,
            ImmutableList.of(target),
            ImmutableList.of(backendService, locality));
  }

  public void recordDisconnection(String target, String backendService, String locality,
                                  String disconnectError, String securityLevel) {
    metricRecorder
        .addLongCounter(disconnections, 1,
            ImmutableList.of(target),
            ImmutableList.of(backendService, locality, disconnectError));
    metricRecorder
        .addLongUpDownCounter(openConnections, -1,
            ImmutableList.of(target),
            ImmutableList.of(securityLevel, backendService, locality));
  }

  /**
   * Represents the reason for a subchannel failure.
   */
  public enum DisconnectError {

    /**
     * Represents an HTTP/2 GOAWAY frame. The specific error code
     * (e.g., "NO_ERROR", "PROTOCOL_ERROR") should be handled separately
     * as it is a dynamic part of the error.
     * See RFC 9113 for error codes: https://www.rfc-editor.org/rfc/rfc9113.html#name-error-codes
     */
    GOAWAY("goaway"),

    /**
     * The subchannel was shut down for various reasons like parent channel shutdown,
     * idleness, or load balancing policy changes.
     */
    SUBCHANNEL_SHUTDOWN("subchannel shutdown"),

    /**
     * Connection was reset (e.g., ECONNRESET, WSAECONNERESET).
     */
    CONNECTION_RESET("connection reset"),

    /**
     * Connection timed out (e.g., ETIMEDOUT, WSAETIMEDOUT), including closures
     * from gRPC keepalives.
     */
    CONNECTION_TIMED_OUT("connection timed out"),

    /**
     * Connection was aborted (e.g., ECONNABORTED, WSAECONNABORTED).
     */
    CONNECTION_ABORTED("connection aborted"),

    /**
     * Any socket error not covered by other specific disconnect errors.
     */
    SOCKET_ERROR("socket error"),

    /**
     * A catch-all for any other unclassified reason.
     */
    UNKNOWN("unknown");

    private final String errorTag;

    /**
     * Private constructor to associate a description with each enum constant.
     *
     * @param errorTag The detailed explanation of the error.
     */
    DisconnectError(String errorTag) {
      this.errorTag = errorTag;
    }

    /**
     * Gets the error string suitable for use as a metric tag.
     *
     * <p>If the reason is {@code GOAWAY}, this method requires the specific
     * HTTP/2 error code to create the complete tag (e.g., "goaway PROTOCOL_ERROR").
     * For all other reasons, the parameter is ignored.</p>
     *
     * @param goawayErrorCode The specific HTTP/2 error code. This is only
     *                        used if the reason is GOAWAY and should not be null in that case.
     * @return The formatted error string.
     */
    public String getErrorString(@Nullable String goawayErrorCode) {
      if (this == GOAWAY) {
        if (goawayErrorCode == null || goawayErrorCode.isEmpty()) {
          // Return the base tag if the code is missing, or consider throwing an exception
          // throw new IllegalArgumentException("goawayErrorCode is required for GOAWAY reason.");
          return this.errorTag;
        }
        return this.errorTag + " " + goawayErrorCode;
      }
      return this.errorTag;
    }
  }
}
