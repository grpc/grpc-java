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

package io.grpc.autosharding;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer.SubchannelPicker;
import javax.annotation.Nullable;

/**
 * Immutable snapshot of endpoint state used by {@link AutoShardingPicker}.
 */
final class PickerEndpoint {

  /**
   * Callback interface to trigger connection attempts on an IDLE endpoint's child balancer.
   */
  @FunctionalInterface
  interface ExitIdler {
    /**
     * Requests the child load balancer to exit IDLE and initiate a connection.
     *
     * <p>Implementations MUST be thread-safe, non-blocking, idempotent, and dispatch
     * execution to the {@link io.grpc.SynchronizationContext}.
     */
    void exitIdle();
  }

  private final ConnectivityState state;
  private final SubchannelPicker picker;
  @Nullable private final ExitIdler exitIdler;

  /**
   * Constructs a {@link PickerEndpoint}.
   *
   * @param state the current connectivity state of the endpoint
   * @param picker the latest subchannel picker for the endpoint
   * @param exitIdler a callback to trigger an IDLE child balancer to start connecting
   */
  PickerEndpoint(
      ConnectivityState state,
      SubchannelPicker picker,
      @Nullable ExitIdler exitIdler) {
    this.state = checkNotNull(state, "state");
    this.picker = checkNotNull(picker, "picker");
    this.exitIdler = exitIdler;
  }

  ConnectivityState getState() {
    return state;
  }

  SubchannelPicker getPicker() {
    return picker;
  }

  void requestConnection() {
    if (exitIdler != null) {
      exitIdler.exitIdle();
    }
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("state", state)
        .add("picker", picker)
        .toString();
  }
}
