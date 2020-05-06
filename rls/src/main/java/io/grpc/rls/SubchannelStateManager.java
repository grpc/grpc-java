/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.rls;

import io.grpc.ConnectivityState;
import javax.annotation.Nullable;

/**
 * SubchannelStateManager manages {@link ConnectivityState} of child subchannels.
 */
interface SubchannelStateManager {

  /**
   * Registers and updates state for given subchannel. {@link ConnectivityState#SHUTDOWN}
   * unregisters the subchannel.
   */
  void updateState(String name, ConnectivityState newState);

  /**
   * Returns current subchannel state for given subchannel name if exists, otherwise returns
   * {@code null}.
   */
  @Nullable
  ConnectivityState getState(String name);

  /** Returns representative subchannel status from all registered subchannels. */
  ConnectivityState getAggregatedState();
}
