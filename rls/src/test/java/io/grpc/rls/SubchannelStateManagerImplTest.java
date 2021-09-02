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

import static com.google.common.truth.Truth.assertThat;

import io.grpc.ConnectivityState;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SubchannelStateManagerImplTest {

  private SubchannelStateManager subchannelStateManager = new SubchannelStateManagerImpl();

  @Test
  public void getState_unknown() {
    assertThat(subchannelStateManager.getState("unknown")).isNull();
  }

  @Test
  public void getState_known() {
    subchannelStateManager.updateState("known", ConnectivityState.TRANSIENT_FAILURE);

    assertThat(subchannelStateManager.getState("known"))
        .isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
  }

  @Test
  public void getState_shutdown_unregistersSubchannel() {
    subchannelStateManager.updateState("known", ConnectivityState.TRANSIENT_FAILURE);

    assertThat(subchannelStateManager.getState("known"))
        .isEqualTo(ConnectivityState.TRANSIENT_FAILURE);

    subchannelStateManager.updateState("known", ConnectivityState.SHUTDOWN);

    assertThat(subchannelStateManager.getState("known")).isNull();
  }

  @Test
  public void getAggregatedStatus_none() {
    assertThat(subchannelStateManager.getAggregatedState())
        .isEqualTo(ConnectivityState.IDLE);
  }

  @Test
  public void getAggregatedStatus_single() {
    for (ConnectivityState value : ConnectivityState.values()) {
      if (value == ConnectivityState.SHUTDOWN) {
        continue;
      }
      SubchannelStateManager stateManager = new SubchannelStateManagerImpl();

      stateManager.updateState("foo", value);

      assertThat(stateManager.getAggregatedState()).isEqualTo(value);
    }
  }

  @Test
  public void getAggregateState_multipleSubchannels() {
    subchannelStateManager.updateState("channel1", ConnectivityState.TRANSIENT_FAILURE);
    subchannelStateManager.updateState("channel2", ConnectivityState.READY);

    assertThat(subchannelStateManager.getState("channel1"))
        .isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
    assertThat(subchannelStateManager.getState("channel2"))
        .isEqualTo(ConnectivityState.READY);

    assertThat(subchannelStateManager.getAggregatedState())
        .isEqualTo(ConnectivityState.READY);

    subchannelStateManager.updateState("channel2", ConnectivityState.SHUTDOWN);

    assertThat(subchannelStateManager.getAggregatedState())
        .isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
  }
}
