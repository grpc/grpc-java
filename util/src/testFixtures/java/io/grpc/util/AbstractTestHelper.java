/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.util;

import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;

import io.grpc.Attributes;
import io.grpc.Channel;
import io.grpc.ChannelLogger;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A real class that can be used as a delegate of a mock Helper to provide more real representation
 * and track the subchannels as is needed with petiole policies where the subchannels are no
 * longer direct children of the loadbalancer.
 * <br>
 * To use it replace <br>
 * \@mock Helper mockHelper<br>
 * with<br>
 * <p>Helper mockHelper = mock(Helper.class, delegatesTo(new TestHelper()));</p>
 * <br>
 * TestHelper will need to define accessors for the maps that information is store within as
 * those maps need to be defined in the Test class.
 */
public abstract class AbstractTestHelper extends ForwardingLoadBalancerHelper {

  public abstract Map<List<EquivalentAddressGroup>, Subchannel> getSubchannelMap();

  public abstract Map<Subchannel, Subchannel> getMockToRealSubChannelMap();

  public abstract Map<Subchannel, SubchannelStateListener> getSubchannelStateListeners();

  @Override
  public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
    // do nothing, should have been done in the wrapper helpers
  }

  @Override
  protected Helper delegate() {
    throw new UnsupportedOperationException("This helper class is only for use in this test");
  }

  @Override
  public Subchannel createSubchannel(CreateSubchannelArgs args) {
    Subchannel subchannel = getSubchannelMap().get(args.getAddresses());
    if (subchannel == null) {
      TestSubchannel delegate = new TestSubchannel(args);
      subchannel = mock(Subchannel.class, delegatesTo(delegate));
      getSubchannelMap().put(args.getAddresses(), subchannel);
      getMockToRealSubChannelMap().put(subchannel, delegate);
    }

    return subchannel;
  }

  @Override
  public void refreshNameResolution() {
    // no-op
  }

  public void setChannel(Subchannel subchannel, Channel channel) {
    ((TestSubchannel)subchannel).channel = channel;
  }

  @Override
  public String toString() {
    return "Test Helper";
  }

  private class TestSubchannel extends ForwardingSubchannel {
    final CreateSubchannelArgs args;
    Channel channel;

    public TestSubchannel(CreateSubchannelArgs args) {
      this.args = args;
    }

    @Override
    protected Subchannel delegate() {
      throw new UnsupportedOperationException("Only to be used in tests");
    }

    @Override
    public List<EquivalentAddressGroup> getAllAddresses() {
      return args.getAddresses();
    }

    @Override
    public Attributes getAttributes() {
      return args.getAttributes();
    }

    @Override
    public void requestConnection() {
      // Ignore, we will manually update state
    }

    @Override
    public void updateAddresses(List<EquivalentAddressGroup> addrs) {
      // Do nothing, will be handled in wrappers
    }

    @Override
    public void start(SubchannelStateListener listener) {
      getSubchannelStateListeners().put(this, listener);
    }

    @Override
    public void shutdown() {
      getSubchannelStateListeners().remove(this);
      for (EquivalentAddressGroup eag : getAllAddresses()) {
        getSubchannelMap().remove(Collections.singletonList(eag));
      }
    }

    @Override
    public Channel asChannel() {
      return channel;
    }

    @Override
    public ChannelLogger getChannelLogger() {
      return mock(ChannelLogger.class);
    }

    @Override
    public String toString() {
      return "Mock Subchannel" + args.toString();
    }
  }
}

