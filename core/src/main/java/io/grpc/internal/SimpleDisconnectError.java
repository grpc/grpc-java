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

import javax.annotation.concurrent.Immutable;

/**
 * Represents a fixed, static reason for disconnection.
 */
@Immutable
public enum SimpleDisconnectError implements DisconnectError {
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

  SimpleDisconnectError(String errorTag) {
    this.errorTag = errorTag;
  }

  @Override
  public String toErrorString() {
    return this.errorTag;
  }
}