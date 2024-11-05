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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.collect.Maps;
import io.grpc.Attributes;
import io.grpc.Channel;
import io.grpc.ChannelLogger;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.internal.PickFirstLoadBalancerProvider;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * A real class that can be used as a delegate of a mock Helper to provide more real representation
 * and track the subchannels as is needed with petiole policies where the subchannels are no
 * longer direct children of the loadbalancer.
 * <br>
 * To use it replace <br>
 * \@mock Helper mockHelper<br>
 * with<br>
 *
 * <p>Helper mockHelper = mock(Helper.class, delegatesTo(new TestHelper()));</p>
 * <br>
 * TestHelper will need to define accessors for the maps that information is store within as
 * those maps need to be defined in the Test class.
 */
public abstract class AbstractTestHelper extends ForwardingLoadBalancerHelper {

  private final Map<Subchannel, Subchannel> mockToRealSubChannelMap = new HashMap<>();
  protected final Map<Subchannel, Subchannel> realToMockSubChannelMap = new HashMap<>();
  private final Map<Subchannel, SubchannelStateListener> subchannelStateListeners =
      Maps.newLinkedHashMap();
  private final FakeClock fakeClock;
  private final SynchronizationContext syncContext;

  public abstract Map<List<EquivalentAddressGroup>, Subchannel> getSubchannelMap();

  public AbstractTestHelper() {
    this(new FakeClock(), new SynchronizationContext(new Thread.UncaughtExceptionHandler() {
      @Override
      public void uncaughtException(Thread t, Throwable e) {
        throw new RuntimeException(e);
      }
    }));
  }

  public AbstractTestHelper(FakeClock fakeClock, SynchronizationContext syncContext) {
    super();
    this.fakeClock = fakeClock;
    this.syncContext = syncContext;
  }

  public Map<Subchannel, Subchannel> getMockToRealSubChannelMap() {
    return mockToRealSubChannelMap;
  }

  public Subchannel getRealForMockSubChannel(Subchannel mock) {
    Subchannel realSc = getMockToRealSubChannelMap().get(mock);
    if (realSc == null) {
      realSc = mock;
    }
    return realSc;
  }

  public Map<Subchannel, SubchannelStateListener> getSubchannelStateListeners() {
    return subchannelStateListeners;
  }

  public static final FakeClock.TaskFilter NOT_START_NEXT_CONNECTION =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return !command.toString().contains("StartNextConnection");
        }
      };

  public static int getNumFilteredPendingTasks(FakeClock fakeClock) {
    return fakeClock.getPendingTasks(NOT_START_NEXT_CONNECTION).size();
  }

  public void deliverSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
    Subchannel realSc = getMockToRealSubChannelMap().get(subchannel);
    if (realSc == null) {
      realSc = subchannel;
    }
    SubchannelStateListener listener = getSubchannelStateListeners().get(realSc);
    if (listener == null) {
      throw new IllegalArgumentException("subchannel does not have a matching listener");
    }
    listener.onSubchannelState(newState);
  }

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
      TestSubchannel delegate = createRealSubchannel(args);
      subchannel = mock(Subchannel.class, delegatesTo(delegate));
      getSubchannelMap().put(args.getAddresses(), subchannel);
      getMockToRealSubChannelMap().put(subchannel, delegate);
      realToMockSubChannelMap.put(delegate, subchannel);
    }

    return subchannel;
  }

  protected TestSubchannel createRealSubchannel(CreateSubchannelArgs args) {
    return new TestSubchannel(args);
  }

  @Override
  public void refreshNameResolution() {
    // no-op
  }

  public void setChannel(Subchannel subchannel, Channel channel) {
    ((TestSubchannel)subchannel).channel = channel;
  }

  @Override
  public SynchronizationContext getSynchronizationContext() {
    return syncContext;
  }

  @Override
  public ScheduledExecutorService getScheduledExecutorService() {
    return fakeClock.getScheduledExecutorService();
  }

  @Override
  public String toString() {
    return "Test Helper";
  }

  public static void refreshInvokedAndUpdateBS(InOrder inOrder, ConnectivityState state,
                                                Helper helper,
                                               ArgumentCaptor<SubchannelPicker> pickerCaptor) {
    // Old PF and new PF reverse calling order of updateBlaancingState and refreshNameResolution
    if (PickFirstLoadBalancerProvider.isEnabledNewPickFirst()) {
      inOrder.verify(helper).updateBalancingState(eq(state), pickerCaptor.capture());
    }

    inOrder.verify(helper).refreshNameResolution();

    if (!PickFirstLoadBalancerProvider.isEnabledNewPickFirst()) {
      inOrder.verify(helper).updateBalancingState(eq(state), pickerCaptor.capture());
    }
  }

  public static void verifyNoMoreMeaningfulInteractions(Helper helper) {
    verify(helper, atLeast(0)).getSynchronizationContext();
    verify(helper, atLeast(0)).getScheduledExecutorService();
    verifyNoMoreInteractions(helper);
  }

  public static void verifyNoMoreMeaningfulInteractions(Helper helper, InOrder inOrder) {
    inOrder.verify(helper, atLeast(0)).getSynchronizationContext();
    inOrder.verify(helper, atLeast(0)).getScheduledExecutorService();
    inOrder.verifyNoMoreInteractions();
  }

  protected class TestSubchannel extends ForwardingSubchannel {
    CreateSubchannelArgs args;
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
      if (args.getAddresses().equals(addrs)) {
        return; // no changes so it's a no-op
      }

      List<EquivalentAddressGroup> oldAddrs = args.getAddresses();
      Subchannel oldTarget = getSubchannelMap().get(oldAddrs);

      this.args = args.toBuilder().setAddresses(addrs).build();
      getSubchannelMap().put(addrs, oldTarget);
      getSubchannelMap().remove(oldAddrs);
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

  public static final class FakeSocketAddress extends SocketAddress {
    private static final long serialVersionUID = 0L;
    final String name;

    FakeSocketAddress(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return "FakeSocketAddress-" + name;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof FakeSocketAddress)) {
        return false;
      }
      FakeSocketAddress that = (FakeSocketAddress) o;
      return this.name.equals(that.name);
    }

    @Override
    public int hashCode() {
      return name.hashCode();
    }
  }
}

