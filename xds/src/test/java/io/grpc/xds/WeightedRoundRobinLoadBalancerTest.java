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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.services.InternalCallMetricRecorder;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.WrrSubchannel;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.WeightedRoundRobinPicker;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.WeightedRoundRobinLoadBalancerConfig;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public class WeightedRoundRobinLoadBalancerTest {
  @Rule
  public final MockitoRule mockito = MockitoJUnit.rule();
  @Mock
  Helper helper;
  private final List<EquivalentAddressGroup> servers = Lists.newArrayList();
  private final Map<List<EquivalentAddressGroup>, Subchannel> subchannels = Maps.newLinkedHashMap();
  private final Map<Subchannel, SubchannelStateListener> subchannelStateListeners =
        Maps.newLinkedHashMap();
  @Captor
  private ArgumentCaptor<WeightedRoundRobinPicker> pickerCaptor;
  private WeightedRoundRobinLoadBalancer wrr;
  private final FakeClock fakeClock = new FakeClock();

  private final WeightedRoundRobinLoadBalancerConfig weightedConfig =
            new WeightedRoundRobinLoadBalancerConfig.Builder().build();

  private static final Attributes.Key<String> MAJOR_KEY = Attributes.Key.create("major-key");

  private final Attributes affinity =
            Attributes.newBuilder().set(MAJOR_KEY, "I got the keys").build();

  private final SynchronizationContext syncContext = new SynchronizationContext(
            new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    throw new AssertionError(e);
                }
            });
  @Before
  public void setup() {
    MockitoAnnotations.initMocks(this);

    for (int i = 0; i < 3; i++) {
      SocketAddress addr = new FakeSocketAddress("server" + i);
      EquivalentAddressGroup eag = new EquivalentAddressGroup(addr);
      servers.add(eag);
      Subchannel sc = mock(Subchannel.class);
      subchannels.put(Arrays.asList(eag), sc);
    }
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    when(helper.getScheduledExecutorService()).thenReturn(
            fakeClock.getScheduledExecutorService());
    when(helper.createSubchannel(any(CreateSubchannelArgs.class)))
          .then(new Answer<Subchannel>() {
            @Override
            public Subchannel answer(InvocationOnMock invocation) throws Throwable {
              CreateSubchannelArgs args = (CreateSubchannelArgs) invocation.getArguments()[0];
              final Subchannel subchannel = subchannels.get(args.getAddresses());
              when(subchannel.getAllAddresses()).thenReturn(args.getAddresses());
              when(subchannel.getAttributes()).thenReturn(args.getAttributes());
              when(subchannel.getChannelLogger()).thenReturn(mock(ChannelLogger.class));
              doAnswer(
                new Answer<Void>() {
                    @Override
                    public Void answer(InvocationOnMock invocation) throws Throwable {
                        subchannelStateListeners.put(
                                subchannel, (SubchannelStateListener) invocation.getArguments()[0]);
                        return null;
                    }
                }).when(subchannel).start(any(SubchannelStateListener.class));
              return subchannel;
            }
            });
    wrr = new WeightedRoundRobinLoadBalancer(helper, fakeClock.getTimeProvider());
  }

  @Test
  public void pickByWeight() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(LoadBalancer.ResolvedAddresses.newBuilder()
                .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(3)).createSubchannel(
                any(CreateSubchannelArgs.class));
    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    subchannelStateListeners.get(readySubchannel1).onSubchannelState(ConnectivityStateInfo.
            forNonError(ConnectivityState.READY));

    Subchannel readySubchannel2  = it.next();
    subchannelStateListeners.get(readySubchannel2).onSubchannelState(ConnectivityStateInfo.
              forNonError(ConnectivityState.READY));
    verify(helper, times(2)).updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());
    WeightedRoundRobinPicker weightedPicker = pickerCaptor.getAllValues().get(1);
    WrrSubchannel weightedSubchannel1 = (WrrSubchannel) weightedPicker.getList().get(0);
    WrrSubchannel weightedSubchannel2 = (WrrSubchannel) weightedPicker.getList().get(1);
    weightedSubchannel1.onLoadReport(InternalCallMetricRecorder.createMetricReport(
      0.1, 0.1, 1, new HashMap<>(), new HashMap<>()));
    weightedSubchannel2.onLoadReport(InternalCallMetricRecorder.createMetricReport(
      0.2, 0.1, 1, new HashMap<>(), new HashMap<>()));
    fakeClock.forwardTime(11, TimeUnit.SECONDS);
    assertThat(weightedPicker.pickSubchannel(mock(LoadBalancer.PickSubchannelArgs.class))
            .getSubchannel()).isEqualTo(weightedSubchannel1);
    }

    private static class FakeSocketAddress extends SocketAddress {
        final String name;
        FakeSocketAddress(String name) {
            this.name = name;
        }
        @Override
        public String toString() {
            return "FakeSocketAddress-" + name;
        }
    }
}
