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
  private final ConnectivityState state;
  private final SubchannelPicker picker;
  @Nullable private final Runnable requestConnection;

  /**
   * Constructs a {@link PickerEndpoint}.
   *
   * @param state the current connectivity state of the endpoint
   * @param picker the latest subchannel picker for the endpoint
   * @param requestConnection a callback to trigger a connection attempt on the child balancer
   */
  PickerEndpoint(
      ConnectivityState state,
      SubchannelPicker picker,
      @Nullable Runnable requestConnection) {
    this.state = checkNotNull(state, "state");
    this.picker = checkNotNull(picker, "picker");
    this.requestConnection = requestConnection;
  }

  ConnectivityState getState() {
    return state;
  }

  SubchannelPicker getPicker() {
    return picker;
  }

  void requestConnection() {
    if (requestConnection != null) {
      requestConnection.run();
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
