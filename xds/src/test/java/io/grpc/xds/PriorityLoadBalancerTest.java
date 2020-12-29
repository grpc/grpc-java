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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.internal.TestUtils.StandardLoadBalancerProvider;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig.PriorityChildConfig;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link PriorityLoadBalancer}. */
@RunWith(JUnit4.class)
public class PriorityLoadBalancerTest {
  private final List<LoadBalancer> fooBalancers = new ArrayList<>();
  private final List<LoadBalancer> barBalancers = new ArrayList<>();
  private final List<Helper> fooHelpers = new ArrayList<>();
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final FakeClock fakeClock = new FakeClock();

  private final LoadBalancerProvider fooLbProvider =
      new StandardLoadBalancerProvider("foo_policy") {
        @Override
        public LoadBalancer newLoadBalancer(Helper helper) {
          fooHelpers.add(helper);
          LoadBalancer childBalancer = mock(LoadBalancer.class);
          fooBalancers.add(childBalancer);
          return childBalancer;
        }
      };

  private final LoadBalancerProvider barLbProvider =
      new StandardLoadBalancerProvider("bar_policy") {
        @Override
        public LoadBalancer newLoadBalancer(Helper helper) {
          LoadBalancer childBalancer = mock(LoadBalancer.class);
          barBalancers.add(childBalancer);
          return childBalancer;
        }
      };

  @Rule public final MockitoRule mockitoRule = MockitoJUnit.rule();
  @Mock private Helper helper;
  @Captor ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor;
  @Captor ArgumentCaptor<ConnectivityState> connectivityStateCaptor;
  @Captor ArgumentCaptor<SubchannelPicker> pickerCaptor;

  private PriorityLoadBalancer priorityLb;

  @Before
  public void setUp() {
    doReturn(syncContext).when(helper).getSynchronizationContext();
    doReturn(fakeClock.getScheduledExecutorService()).when(helper).getScheduledExecutorService();
    priorityLb = new PriorityLoadBalancer(helper);
  }

  @After
  public void tearDown() {
    priorityLb.shutdown();
    for (LoadBalancer lb : fooBalancers) {
      verify(lb).shutdown();
    }
    for (LoadBalancer lb : barBalancers) {
      verify(lb).shutdown();
    }
    assertThat(fakeClock.getPendingTasks()).isEmpty();
  }

  @Test
  public void handleResolvedAddresses() {
    SocketAddress socketAddress = new InetSocketAddress(8080);
    EquivalentAddressGroup eag = new EquivalentAddressGroup(socketAddress);
    eag = AddressFilter.setPathFilter(eag, ImmutableList.of("p1"));
    List<EquivalentAddressGroup> addresses = ImmutableList.of(eag);
    Attributes attributes =
        Attributes.newBuilder().set(Attributes.Key.create("fakeKey"), "fakeValue").build();
    Object fooConfig0 = new Object();
    PriorityChildConfig priorityChildConfig0 =
        new PriorityChildConfig(new PolicySelection(fooLbProvider, fooConfig0), true);
    Object barConfig0 = new Object();
    PriorityChildConfig priorityChildConfig1 =
        new PriorityChildConfig(new PolicySelection(barLbProvider, barConfig0), true);
    Object fooConfig1 = new Object();
    PriorityChildConfig priorityChildConfig2 =
        new PriorityChildConfig(new PolicySelection(fooLbProvider, fooConfig1), true);
    PriorityLbConfig priorityLbConfig =
        new PriorityLbConfig(
            ImmutableMap.of("p0", priorityChildConfig0, "p1", priorityChildConfig1,
                "p2", priorityChildConfig2),
            ImmutableList.of("p0", "p1", "p2"));
    priorityLb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(addresses)
            .setAttributes(attributes)
            .setLoadBalancingPolicyConfig(priorityLbConfig)
            .build());
    assertThat(fooBalancers).hasSize(1);
    assertThat(barBalancers).isEmpty();
    LoadBalancer fooBalancer0 = Iterables.getOnlyElement(fooBalancers);
    verify(fooBalancer0).handleResolvedAddresses(resolvedAddressesCaptor.capture());
    ResolvedAddresses addressesReceived = resolvedAddressesCaptor.getValue();
    assertThat(addressesReceived.getAddresses()).isEmpty();
    assertThat(addressesReceived.getAttributes()).isEqualTo(attributes);
    assertThat(addressesReceived.getLoadBalancingPolicyConfig()).isEqualTo(fooConfig0);

    // Fail over to p1.
    fakeClock.forwardTime(10, TimeUnit.SECONDS);
    assertThat(fooBalancers).hasSize(1);
    assertThat(barBalancers).hasSize(1);
    LoadBalancer barBalancer0 = Iterables.getOnlyElement(barBalancers);
    verify(barBalancer0).handleResolvedAddresses(resolvedAddressesCaptor.capture());
    addressesReceived = resolvedAddressesCaptor.getValue();
    assertThat(Iterables.getOnlyElement(addressesReceived.getAddresses()).getAddresses())
        .containsExactly(socketAddress);
    assertThat(addressesReceived.getAttributes()).isEqualTo(attributes);
    assertThat(addressesReceived.getLoadBalancingPolicyConfig()).isEqualTo(barConfig0);

    // Fail over to p2.
    fakeClock.forwardTime(10, TimeUnit.SECONDS);
    assertThat(fooBalancers).hasSize(2);
    assertThat(barBalancers).hasSize(1);
    LoadBalancer fooBalancer1 = Iterables.getLast(fooBalancers);
    verify(fooBalancer1).handleResolvedAddresses(resolvedAddressesCaptor.capture());
    addressesReceived = resolvedAddressesCaptor.getValue();
    assertThat(addressesReceived.getAddresses()).isEmpty();
    assertThat(addressesReceived.getAttributes()).isEqualTo(attributes);
    assertThat(addressesReceived.getLoadBalancingPolicyConfig()).isEqualTo(fooConfig1);

    // New update: p0 and p2 deleted; p1 config changed.
    SocketAddress newSocketAddress = new InetSocketAddress(8081);
    EquivalentAddressGroup newEag = new EquivalentAddressGroup(newSocketAddress);
    newEag = AddressFilter.setPathFilter(newEag, ImmutableList.of("p1"));
    List<EquivalentAddressGroup> newAddresses = ImmutableList.of(newEag);
    Object newBarConfig = new Object();
    PriorityLbConfig newPriorityLbConfig =
        new PriorityLbConfig(
            ImmutableMap.of("p1",
                new PriorityChildConfig(new PolicySelection(barLbProvider, newBarConfig), true)),
            ImmutableList.of("p1"));
    priorityLb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(newAddresses)
            .setLoadBalancingPolicyConfig(newPriorityLbConfig)
            .build());
    assertThat(fooBalancers).hasSize(2);
    assertThat(barBalancers).hasSize(1);
    verify(barBalancer0, times(2)).handleResolvedAddresses(resolvedAddressesCaptor.capture());
    addressesReceived = resolvedAddressesCaptor.getValue();
    assertThat(Iterables.getOnlyElement(addressesReceived.getAddresses()).getAddresses())
        .containsExactly(newSocketAddress);
    assertThat(addressesReceived.getAttributes()).isEqualTo(Attributes.EMPTY);
    assertThat(addressesReceived.getLoadBalancingPolicyConfig()).isEqualTo(newBarConfig);
    verify(fooBalancer0, never()).shutdown();
    verify(fooBalancer1, never()).shutdown();
    fakeClock.forwardTime(15, TimeUnit.MINUTES);
    verify(fooBalancer0).shutdown();
    verify(fooBalancer1).shutdown();
    verify(barBalancer0, never()).shutdown();
  }

  @Test
  public void handleNameResolutionError() {
    Object fooConfig0 = new Object();
    PriorityChildConfig priorityChildConfig0 =
        new PriorityChildConfig(new PolicySelection(fooLbProvider, fooConfig0), true);
    Object fooConfig1 = new Object();
    PriorityChildConfig priorityChildConfig1 =
        new PriorityChildConfig(new PolicySelection(fooLbProvider, fooConfig1), true);

    PriorityLbConfig priorityLbConfig =
        new PriorityLbConfig(ImmutableMap.of("p0", priorityChildConfig0), ImmutableList.of("p0"));
    priorityLb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
            .setLoadBalancingPolicyConfig(priorityLbConfig)
            .build());
    LoadBalancer fooLb0 = Iterables.getOnlyElement(fooBalancers);
    Status status = Status.DATA_LOSS.withDescription("fake error");
    priorityLb.handleNameResolutionError(status);
    verify(fooLb0).handleNameResolutionError(status);

    priorityLbConfig =
        new PriorityLbConfig(ImmutableMap.of("p1", priorityChildConfig1), ImmutableList.of("p1"));
    priorityLb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
            .setLoadBalancingPolicyConfig(priorityLbConfig)
            .build());
    assertThat(fooBalancers).hasSize(2);
    LoadBalancer fooLb1 = Iterables.getLast(fooBalancers);
    status = Status.UNAVAILABLE.withDescription("fake error");
    priorityLb.handleNameResolutionError(status);
    // fooLb0 is deactivated but not yet deleted. However, because it is delisted by the latest
    // address update, name resolution error will not be propagated to it.
    verify(fooLb0, never()).shutdown();
    verify(fooLb0, never()).handleNameResolutionError(status);
    verify(fooLb1).handleNameResolutionError(status);
  }

  @Test
  public void typicalPriorityFailOverFlow() {
    PriorityChildConfig priorityChildConfig0 =
        new PriorityChildConfig(new PolicySelection(fooLbProvider, new Object()), true);
    PriorityChildConfig priorityChildConfig1 =
        new PriorityChildConfig(new PolicySelection(fooLbProvider, new Object()), true);
    PriorityChildConfig priorityChildConfig2 =
        new PriorityChildConfig(new PolicySelection(fooLbProvider, new Object()), true);
    PriorityChildConfig priorityChildConfig3 =
        new PriorityChildConfig(new PolicySelection(fooLbProvider, new Object()), true);
    PriorityLbConfig priorityLbConfig =
        new PriorityLbConfig(
            ImmutableMap.of("p0", priorityChildConfig0, "p1", priorityChildConfig1,
                "p2", priorityChildConfig2, "p3", priorityChildConfig3),
            ImmutableList.of("p0", "p1", "p2", "p3"));
    priorityLb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
            .setLoadBalancingPolicyConfig(priorityLbConfig)
            .build());
    assertThat(fooBalancers).hasSize(1);
    assertThat(fooHelpers).hasSize(1);
    LoadBalancer balancer0 = Iterables.getLast(fooBalancers);
    Helper helper0 = Iterables.getOnlyElement(fooHelpers);

    // p0 gets READY.
    final Subchannel subchannel0 = mock(Subchannel.class);
    helper0.updateBalancingState(
        READY,
        new SubchannelPicker() {
          @Override
          public PickResult pickSubchannel(PickSubchannelArgs args) {
            return PickResult.withSubchannel(subchannel0);
          }
        });
    assertCurrentPickerPicksSubchannel(subchannel0);

    // p0 fails over to p1 immediately.
    helper0.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(Status.ABORTED));
    assertLatestConnectivityState(CONNECTING);
    assertThat(fooBalancers).hasSize(2);
    assertThat(fooHelpers).hasSize(2);
    LoadBalancer balancer1 = Iterables.getLast(fooBalancers);

    // p1 timeout, and fails over to p2
    fakeClock.forwardTime(10, TimeUnit.SECONDS);
    assertLatestConnectivityState(CONNECTING);
    assertThat(fooBalancers).hasSize(3);
    assertThat(fooHelpers).hasSize(3);
    LoadBalancer balancer2 = Iterables.getLast(fooBalancers);
    Helper helper2 = Iterables.getLast(fooHelpers);

    // p2 gets READY
    final Subchannel subchannel1 = mock(Subchannel.class);
    helper2.updateBalancingState(
        READY,
        new SubchannelPicker() {
          @Override
          public PickResult pickSubchannel(PickSubchannelArgs args) {
            return PickResult.withSubchannel(subchannel1);
          }
        });
    assertCurrentPickerPicksSubchannel(subchannel1);

    // p0 gets back to READY
    final Subchannel subchannel2 = mock(Subchannel.class);
    helper0.updateBalancingState(
        READY,
        new SubchannelPicker() {
          @Override
          public PickResult pickSubchannel(PickSubchannelArgs args) {
            return PickResult.withSubchannel(subchannel2);
          }
        });
    assertCurrentPickerPicksSubchannel(subchannel2);

    // p2 fails but does not affect overall picker
    helper2.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(Status.UNAVAILABLE));
    assertCurrentPickerPicksSubchannel(subchannel2);

    // p0 fails over to p3 immediately since p1 already timeout and p2 already in TRANSIENT_FAILURE.
    helper0.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(Status.UNAVAILABLE));
    assertLatestConnectivityState(CONNECTING);
    assertThat(fooBalancers).hasSize(4);
    assertThat(fooHelpers).hasSize(4);
    LoadBalancer balancer3 = Iterables.getLast(fooBalancers);
    Helper helper3 = Iterables.getLast(fooHelpers);

    // p3 timeout then the channel should go to TRANSIENT_FAILURE
    fakeClock.forwardTime(10, TimeUnit.SECONDS);
    assertCurrentPickerReturnsError(Status.Code.UNAVAILABLE, "timeout");

    // p3 fails then the picker should have error status updated
    helper3.updateBalancingState(
        TRANSIENT_FAILURE, new ErrorPicker(Status.DATA_LOSS.withDescription("foo")));
    assertCurrentPickerReturnsError(Status.Code.DATA_LOSS, "foo");

    // p2 gets back to READY
    final Subchannel subchannel3 = mock(Subchannel.class);
    helper2.updateBalancingState(
        READY,
        new SubchannelPicker() {
          @Override
          public PickResult pickSubchannel(PickSubchannelArgs args) {
            return PickResult.withSubchannel(subchannel3);
          }
        });
    assertCurrentPickerPicksSubchannel(subchannel3);

    // p0 gets back to READY
    final Subchannel subchannel4 = mock(Subchannel.class);
    helper0.updateBalancingState(
        READY,
        new SubchannelPicker() {
          @Override
          public PickResult pickSubchannel(PickSubchannelArgs args) {
            return PickResult.withSubchannel(subchannel4);
          }
        });
    assertCurrentPickerPicksSubchannel(subchannel4);

    // p0 fails over to p2 and picker is updated to p2's existing picker.
    helper0.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(Status.UNAVAILABLE));
    assertCurrentPickerPicksSubchannel(subchannel3);

    // Deactivate child balancer get deleted.
    fakeClock.forwardTime(15, TimeUnit.MINUTES);
    verify(balancer0, never()).shutdown();
    verify(balancer1, never()).shutdown();
    verify(balancer2, never()).shutdown();
    verify(balancer3).shutdown();
  }

  @Test
  public void bypassReresolutionRequestsIfConfiged() {
    PriorityChildConfig priorityChildConfig0 =
        new PriorityChildConfig(new PolicySelection(fooLbProvider, new Object()), true);
    PriorityChildConfig priorityChildConfig1 =
        new PriorityChildConfig(new PolicySelection(fooLbProvider, new Object()), false);
    PriorityLbConfig priorityLbConfig =
        new PriorityLbConfig(
            ImmutableMap.of("p0", priorityChildConfig0, "p1", priorityChildConfig1),
            ImmutableList.of("p0", "p1"));
    priorityLb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
            .setLoadBalancingPolicyConfig(priorityLbConfig)
            .build());
    Helper priorityHelper0 = Iterables.getOnlyElement(fooHelpers);  // priority p0
    priorityHelper0.refreshNameResolution();
    verify(helper, never()).refreshNameResolution();

    // Simulate fallback to priority p1.
    priorityHelper0.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(Status.UNAVAILABLE));
    assertThat(fooHelpers).hasSize(2);
    Helper priorityHelper1 = Iterables.getLast(fooHelpers);
    priorityHelper1.refreshNameResolution();
    verify(helper).refreshNameResolution();
  }

  private void assertLatestConnectivityState(ConnectivityState expectedState) {
    verify(helper, atLeastOnce())
        .updateBalancingState(connectivityStateCaptor.capture(), pickerCaptor.capture());
    assertThat(connectivityStateCaptor.getValue()).isEqualTo(expectedState);
  }

  private void assertCurrentPickerReturnsError(
      Status.Code expectedCode, String expectedDescription) {
    assertLatestConnectivityState(TRANSIENT_FAILURE);
    Status error =
        pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class)).getStatus();
    assertThat(error.getCode()).isEqualTo(expectedCode);
    if (expectedDescription != null) {
      assertThat(error.getDescription()).contains(expectedDescription);
    }
  }

  private void assertCurrentPickerPicksSubchannel(Subchannel expectedSubchannelToPick) {
    assertLatestConnectivityState(READY);
    PickResult pickResult = pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(pickResult.getSubchannel()).isEqualTo(expectedSubchannelToPick);
  }
}
