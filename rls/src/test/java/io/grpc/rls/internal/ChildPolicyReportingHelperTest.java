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

package io.grpc.rls.internal;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.rls.internal.ChildLoadBalancerHelper.ChildLoadBalancerHelperProvider;
import io.grpc.rls.internal.ChildPolicyReportingHelper.ChildLbStatusListener;
import io.grpc.rls.internal.LbPolicyConfiguration.ChildPolicyWrapper;
import io.grpc.rls.internal.LbPolicyConfiguration.RefCountedChildPolicyWrapperFactory;
import java.net.SocketAddress;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ChildPolicyReportingHelperTest {

  private final Helper helper = mock(Helper.class);
  private final SubchannelStateManager subchannelStateManager = new SubchannelStateManagerImpl();
  private final SubchannelPicker picker = mock(SubchannelPicker.class);
  private final ChildLoadBalancerHelperProvider helperProvider =
      new ChildLoadBalancerHelperProvider(helper, subchannelStateManager, picker);
  private final ChildLbStatusListener childLbStatusListener = mock(ChildLbStatusListener.class);
  private final RefCountedChildPolicyWrapperFactory childPolicyWrapperFactory =
      new RefCountedChildPolicyWrapperFactory(
          new ChildLoadBalancerHelperProvider(helper, subchannelStateManager, picker),
          childLbStatusListener);
  private final ChildPolicyWrapper childPolicyWrapper =
      childPolicyWrapperFactory.createOrGet("foo.google.com");
  private final ChildPolicyReportingHelper childPolicyReportingHelper =
      new ChildPolicyReportingHelper(helperProvider, childPolicyWrapper, childLbStatusListener);

  @After
  public void tearDown() {
    childPolicyWrapperFactory.release(childPolicyWrapper);
  }

  @Test
  public void subchannelStateChange_updateChildPolicyWrapper() {
    FakeSubchannel fakeSubchannel = new FakeSubchannel();
    when(helper.createSubchannel(any(CreateSubchannelArgs.class))).thenReturn(fakeSubchannel);
    Subchannel subchannel =
        childPolicyReportingHelper
            .createSubchannel(
                CreateSubchannelArgs.newBuilder()
                    .setAddresses(new EquivalentAddressGroup(mock(SocketAddress.class)))
                    .build());
    subchannel.start(new SubchannelStateListener() {
      @Override
      public void onSubchannelState(ConnectivityStateInfo newState) {
        // no-op
      }
    });

    fakeSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.CONNECTING));

    assertThat(childPolicyWrapper.getConnectivityStateInfo())
        .isEqualTo(ConnectivityStateInfo.forNonError(ConnectivityState.CONNECTING));
  }

  @Test
  public void updateBalancingState_triggersListener() {
    SubchannelPicker childPicker = mock(SubchannelPicker.class);

    childPolicyReportingHelper.updateBalancingState(ConnectivityState.READY, childPicker);

    verify(childLbStatusListener).onStatusChanged(ConnectivityState.READY);
    assertThat(childPolicyWrapper.getPicker()).isEqualTo(childPicker);
    // picker governs childPickers will be reported to parent LB
    verify(helper).updateBalancingState(ConnectivityState.READY, picker);
  }

  private static class FakeSubchannel extends Subchannel {

    private SubchannelStateListener listener;

    @Override
    public void start(SubchannelStateListener listener) {
      this.listener = listener;
    }

    void updateState(ConnectivityStateInfo newState) {
      checkState(listener != null, "channel is not started yet");
      listener.onSubchannelState(newState);
    }

    @Override
    public void shutdown() {}

    @Override
    public void requestConnection() {}

    @Override
    public Attributes getAttributes() {
      return null;
    }
  }
}