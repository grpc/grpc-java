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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.LoadBalancer.HAS_HEALTH_PRODUCER_LISTENER_KEY;
import static io.grpc.LoadBalancer.HEALTH_CONSUMER_LISTENER_ARG_KEY;
import static io.grpc.LoadBalancer.IS_PETIOLE_POLICY;
import static io.grpc.internal.PickFirstLeafLoadBalancer.CONNECTION_DELAY_INTERVAL_MS;
import static io.grpc.internal.PickFirstLeafLoadBalancer.isSerializingRetries;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.errorprone.annotations.Keep;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.PickFirstLeafLoadBalancer.PickFirstLeafLoadBalancerConfig;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;


/** Unit test for {@link PickFirstLeafLoadBalancer}. */
@RunWith(Parameterized.class)
public class PickFirstLeafLoadBalancerTest {
  public static final Status CONNECTION_ERROR =
      Status.UNAVAILABLE.withDescription("Simulated connection error");
  public static final String GRPC_SERIALIZE_RETRIES = "GRPC_SERIALIZE_RETRIES";

  @Parameterized.Parameters(name = "{0}-{1}")
  public static List<Object[]> data() {
    return Arrays.asList(new Object[][] {
        {false, false},
        {false, true},
        {true, false}});
  }

  @Parameterized.Parameter(value = 0)
  public boolean serializeRetries;

  @Parameterized.Parameter(value = 1)
  public boolean enableHappyEyeballs;

  private PickFirstLeafLoadBalancer loadBalancer;
  private final List<EquivalentAddressGroup> servers = Lists.newArrayList();
  private static final Attributes.Key<String> FOO = Attributes.Key.create("foo");
  // For scheduled executor
  private final FakeClock fakeClock = new FakeClock();
  // For syncContext
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final Attributes affinity = Attributes.newBuilder().set(FOO, "bar").build();
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor;
  @Captor
  private ArgumentCaptor<ConnectivityState> connectivityStateCaptor;
  @Captor
  private ArgumentCaptor<CreateSubchannelArgs> createArgsCaptor;
  @Captor
  private ArgumentCaptor<SubchannelStateListener> stateListenerCaptor;
  private Helper mockHelper;
  private FakeSubchannel mockSubchannel1;
  private FakeSubchannel mockSubchannel1n2;
  private FakeSubchannel mockSubchannel2;
  private FakeSubchannel mockSubchannel2n2;
  private FakeSubchannel mockSubchannel3;
  private FakeSubchannel mockSubchannel3n2;
  private FakeSubchannel mockSubchannel4;
  private FakeSubchannel mockSubchannel5;
  @Mock // This LoadBalancer doesn't use any of the arg fields, as verified in tearDown().
  private PickSubchannelArgs mockArgs;

  private String originalHappyEyeballsEnabledValue;
  private String originalSerializeRetriesValue;

  private long backoffMillis;

  @Before
  public void setUp() {
    assumeTrue(!serializeRetries || !enableHappyEyeballs); // they are not compatible

    backoffMillis = TimeUnit.SECONDS.toMillis(1);
    originalSerializeRetriesValue = System.getProperty(GRPC_SERIALIZE_RETRIES);
    System.setProperty(GRPC_SERIALIZE_RETRIES, Boolean.toString(serializeRetries));

    originalHappyEyeballsEnabledValue =
        System.getProperty(PickFirstLoadBalancerProvider.GRPC_PF_USE_HAPPY_EYEBALLS);
    System.setProperty(PickFirstLoadBalancerProvider.GRPC_PF_USE_HAPPY_EYEBALLS,
        Boolean.toString(enableHappyEyeballs));

    for (int i = 1; i <= 5; i++) {
      SocketAddress addr = new FakeSocketAddress("server" + i);
      servers.add(new EquivalentAddressGroup(addr));
    }
    mockSubchannel1 = mock(FakeSubchannel.class, delegatesTo(
        new FakeSubchannel(Arrays.asList(servers.get(0)), Attributes.EMPTY)));
    mockSubchannel1n2 = mock(FakeSubchannel.class, delegatesTo(
        new FakeSubchannel(Arrays.asList(servers.get(0)), Attributes.EMPTY)));
    mockSubchannel2 = mock(FakeSubchannel.class, delegatesTo(
        new FakeSubchannel(Arrays.asList(servers.get(1)), Attributes.EMPTY)));
    mockSubchannel2n2 = mock(FakeSubchannel.class, delegatesTo(
        new FakeSubchannel(Arrays.asList(servers.get(1)), Attributes.EMPTY)));
    mockSubchannel3 = mock(FakeSubchannel.class, delegatesTo(
        new FakeSubchannel(Arrays.asList(servers.get(2)), Attributes.EMPTY)));
    mockSubchannel3n2 = mock(FakeSubchannel.class, delegatesTo(
        new FakeSubchannel(Arrays.asList(servers.get(2)), Attributes.EMPTY)));
    mockSubchannel4 = mock(FakeSubchannel.class, delegatesTo(
        new FakeSubchannel(Arrays.asList(servers.get(3)), Attributes.EMPTY)));
    mockSubchannel5 = mock(FakeSubchannel.class, delegatesTo(
        new FakeSubchannel(Arrays.asList(servers.get(4)), Attributes.EMPTY)));

    mockHelper = mock(Helper.class, delegatesTo(new MockHelperImpl(Arrays.asList(
        mockSubchannel1, mockSubchannel1n2,
        mockSubchannel2, mockSubchannel2n2,
        mockSubchannel3, mockSubchannel3n2,
        mockSubchannel4, mockSubchannel5))));
    loadBalancer = new PickFirstLeafLoadBalancer(mockHelper);
  }

  @After
  public void tearDown() {
    if (originalSerializeRetriesValue == null) {
      System.clearProperty(GRPC_SERIALIZE_RETRIES);
    } else {
      System.setProperty(GRPC_SERIALIZE_RETRIES, originalSerializeRetriesValue);
    }
    if (originalHappyEyeballsEnabledValue == null) {
      System.clearProperty(PickFirstLoadBalancerProvider.GRPC_PF_USE_HAPPY_EYEBALLS);
    } else {
      System.setProperty(PickFirstLoadBalancerProvider.GRPC_PF_USE_HAPPY_EYEBALLS,
          originalHappyEyeballsEnabledValue);
    }

    loadBalancer.shutdown();
    verifyNoMoreInteractions(mockArgs);
  }

  @Test
  public void pickAfterResolved() {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    forwardTimeByConnectionDelay(3);
    int expectedCreates = enableHappyEyeballs ? 4 : 1;
    verify(mockHelper, times(expectedCreates)).createSubchannel(createArgsCaptor.capture());
    List<CreateSubchannelArgs> argsList = createArgsCaptor.getAllValues();
    assertThat(argsList.get(0).getAddresses().get(0)).isEqualTo(servers.get(0));
    assertThat(argsList.get(0).getAddresses().size()).isEqualTo(1);
    if (enableHappyEyeballs) {
      assertThat(argsList.get(1).getAddresses().get(0)).isEqualTo(servers.get(1));
      assertThat(argsList.get(2).getAddresses().get(0)).isEqualTo(servers.get(2));
      assertThat(argsList.get(3).getAddresses().get(0)).isEqualTo(servers.get(3));
      assertThat(argsList.get(1).getAddresses().size()).isEqualTo(1);
      assertThat(argsList.get(2).getAddresses().size()).isEqualTo(1);
      assertThat(argsList.get(3).getAddresses().size()).isEqualTo(1);
    }
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockSubchannel1).requestConnection();
    verify(mockHelper, atLeast(0)).getScheduledExecutorService();
    verify(mockHelper, atLeast(0)).getSynchronizationContext();

    // Calling pickSubchannel() twice gave the same result and doesn't interact with mockHelper
    assertEquals(pickerCaptor.getValue().pickSubchannel(mockArgs),
        pickerCaptor.getValue().pickSubchannel(mockArgs));

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterResolved_shuffle() {
    servers.remove(4);
    servers.remove(3);
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity)
            .setLoadBalancingPolicyConfig(new PickFirstLeafLoadBalancerConfig(true, 123L)).build());

    verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();


    forwardTimeByConnectionDelay(servers.size() - 1);
    int expectedScs = enableHappyEyeballs ? servers.size() : 1;
    verify(mockHelper, times(expectedScs)).createSubchannel(createArgsCaptor.capture());

    List<CreateSubchannelArgs> argsList = createArgsCaptor.getAllValues();
    // We should still see the same set of addresses.
    // Because we use a fixed seed, the addresses should always be shuffled in this order.
    assertThat(argsList.get(0).getAddresses().get(0)).isEqualTo(servers.get(1));
    assertThat(argsList.get(0).getAddresses().size()).isEqualTo(1);
    if (enableHappyEyeballs) {
      assertThat(argsList.get(1).getAddresses().get(0)).isEqualTo(servers.get(0));
      assertThat(argsList.get(2).getAddresses().get(0)).isEqualTo(servers.get(2));
      assertThat(argsList.get(1).getAddresses().size()).isEqualTo(1);
      assertThat(argsList.get(2).getAddresses().size()).isEqualTo(1);
      verify(mockSubchannel1).requestConnection();
    }

    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockHelper, atLeast(0)).getScheduledExecutorService();
    verify(mockHelper, atLeast(0)).getSynchronizationContext();

    // Calling pickSubchannel() twice gives the same result and doesn't interact with mockHelper
    PickResult pick1 = pickerCaptor.getValue().pickSubchannel(mockArgs);
    PickResult pick2 = pickerCaptor.getValue().pickSubchannel(mockArgs);
    assertEquals(pick1, pick2);
    verifyNoMoreInteractions(mockHelper);
    assertThat(pick1.getSubchannel()).isNull();

    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    PickResult pick3 = pickerCaptor.getValue().pickSubchannel(mockArgs);
    PickResult pick4 = pickerCaptor.getValue().pickSubchannel(mockArgs);
    assertEquals(pick3, pick4);
    assertThat(pick3.getSubchannel()).isEqualTo(mockSubchannel2);
  }

  @Test
  public void pickAfterResolved_noShuffle() {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity)
            .setLoadBalancingPolicyConfig(new PickFirstLeafLoadBalancerConfig(false)).build());

    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockSubchannel1).requestConnection();

    // Calling pickSubchannel() twice gave the same result
    assertEquals(pickerCaptor.getValue().pickSubchannel(mockArgs),
        pickerCaptor.getValue().pickSubchannel(mockArgs));
    assertNotNull(pickerCaptor.getValue().pickSubchannel(mockArgs));
  }

  @Test
  public void requestConnectionPicker() {
    // Set up
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(0), servers.get(1),
        servers.get(2));

    // Accepting resolved addresses
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());

    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3);

    // We initialize and start first subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockHelper).createSubchannel(any());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();

    // We start connection attempt to the first address in the list
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // If we send the first subchannel into idle ...
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();

    // Calling pickSubchannel() requests a connection, gives the same result when called twice.
    assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));

    // But the picker calls requestConnection() only once for a total of two connection requests.
    inOrder.verify(mockSubchannel1).requestConnection();
    verify(mockSubchannel1, times(2)).requestConnection();
  }

  @Test
  public void refreshNameResolutionAfterSubchannelConnectionBroken() {
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(0));
    when(mockSubchannel1.getAllAddresses()).thenReturn(Lists.newArrayList(servers.get(0)));

    // accept resolved addresses
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1);
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    assertNull(pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());
    inOrder.verify(mockSubchannel1).requestConnection();

    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    inOrder.verify(mockHelper).refreshNameResolution();
    assertEquals(CONNECTION_ERROR, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertEquals(mockSubchannel1, pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());

    // Simulate receiving go-away so the subchannel transit to IDLE.
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));
  }

  @Test
  public void pickAfterResolvedAndUnchanged() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1);
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    inOrder.verify(mockSubchannel1, times(1)).requestConnection();

    // Second acceptResolvedAddresses shouldn't do anything
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    inOrder.verify(mockSubchannel1, never()).requestConnection();
    inOrder.verify(mockHelper, never()).updateBalancingState(any(), any());
  }

  @Test
  public void pickAfterResolvedAndChanged() {
    SocketAddress socketAddr1 = new FakeSocketAddress("server1");
    List<EquivalentAddressGroup> oldServers =
        Lists.newArrayList(new EquivalentAddressGroup(socketAddr1));

    SocketAddress socketAddr2 = new FakeSocketAddress("server2");
    List<EquivalentAddressGroup> newServers =
        Lists.newArrayList(new EquivalentAddressGroup(socketAddr2));

    // accept resolved addresses which starts connection attempt to first address
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    assertNull(pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());

    // updating the subchannel addresses is unnecessary, but doesn't hurt anything
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockSubchannel1).shutdown();
    verify(mockSubchannel2).requestConnection();
  }

  @Test
  public void healthCheck_nonPetiolePolicy() {
    when(mockSubchannel1.getAttributes()).thenReturn(
        Attributes.newBuilder().set(HAS_HEALTH_PRODUCER_LISTENER_KEY, true).build());

    // Initialize with one server loadbalancer and both health and state listeners
    List<EquivalentAddressGroup> oneServer = Lists.newArrayList(servers.get(0));
    loadBalancer.acceptResolvedAddresses(ResolvedAddresses.newBuilder().setAddresses(oneServer)
        .setAttributes(Attributes.EMPTY).build());
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1);
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    SubchannelStateListener healthListener = createArgsCaptor.getValue()
        .getOption(HEALTH_CONSUMER_LISTENER_ARG_KEY);
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();

    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    healthListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    inOrder.verify(mockHelper, never()).updateBalancingState(any(), any());

    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), any()); // health listener ignored

    healthListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(Status.INTERNAL));
    inOrder.verify(mockHelper, never()).updateBalancingState(any(), any(SubchannelPicker.class));
  }

  @Test
  public void healthCheckFlow() {
    when(mockSubchannel1.getAttributes()).thenReturn(
        Attributes.newBuilder().set(HAS_HEALTH_PRODUCER_LISTENER_KEY, true).build());
    when(mockSubchannel2.getAttributes()).thenReturn(
        Attributes.newBuilder().set(HAS_HEALTH_PRODUCER_LISTENER_KEY, true).build());

    List<EquivalentAddressGroup> oneServer = Lists.newArrayList(servers.get(0), servers.get(1));
    loadBalancer.acceptResolvedAddresses(ResolvedAddresses.newBuilder().setAddresses(oneServer)
        .setAttributes(Attributes.newBuilder().set(IS_PETIOLE_POLICY, true).build()).build());

    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2);
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    SubchannelStateListener healthListener = createArgsCaptor.getValue()
        .getOption(HEALTH_CONSUMER_LISTENER_ARG_KEY);
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel1).requestConnection();

    // subchannel  |  state    |   health
    // subchannel1 | CONNECTING| CONNECTING
    // subchannel2 | IDLE      | IDLE
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    healthListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    inOrder.verify(mockHelper, never()).updateBalancingState(any(), any());

    // subchannel  |  state    |   health
    // subchannel1 | READY     | CONNECTING
    // subchannel2 | IDLE      | IDLE
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper, never()).updateBalancingState(any(), any());

    // subchannel  |  state    |   health
    // subchannel1 | READY     | READY
    healthListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), any(SubchannelPicker.class));
    healthListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(Status.CANCELLED));
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue().pickSubchannel(mockArgs)
        .getStatus()).isEqualTo(Status.CANCELLED);
    //sticky tf
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockSubchannel1).requestConnection();
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    inOrder.verify(mockHelper, times(0)).updateBalancingState(any(), any());
    healthListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper, times(0)).updateBalancingState(any(), any());

    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(Status.INTERNAL));

    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    SubchannelStateListener healthListener2 = createArgsCaptor.getValue()
        .getOption(HEALTH_CONSUMER_LISTENER_ARG_KEY);
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel2).requestConnection();
    //ignore health update on non-ready subchannel
    healthListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));

    verify(mockHelper, atLeast(0)).getSynchronizationContext();
    verify(mockHelper, atLeast(0)).getScheduledExecutorService();
    verifyNoMoreInteractions(mockHelper);

    // subchannel  |  state    |   health
    // subchannel1 | TF     | READY
    // subchannel2 | TF      | IDLE
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    inOrder.verify(mockHelper).refreshNameResolution();
    assertThat(pickerCaptor.getValue().pickSubchannel(mockArgs)
        .getStatus()).isEqualTo(Status.UNAVAILABLE);
    // subchannel  |  state    |   health
    // subchannel1 | READY     | READY
    // subchannel2 | TF      | IDLE
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue().pickSubchannel(mockArgs)
        .getSubchannel()).isSameInstanceAs(mockSubchannel1);
    verify(mockHelper, atLeast(0)).getSynchronizationContext();
    verify(mockHelper, atLeast(0)).getScheduledExecutorService();
    verifyNoMoreInteractions(mockHelper);

    healthListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterStateChangeAfterResolution() {
    InOrder inOrder =
        inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4);

    SubchannelStateListener[] stateListeners = new SubchannelStateListener[4];
    Subchannel[] subchannels = new Subchannel[] {
        mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4};

    servers.remove(4);

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    stateListeners[0] = stateListenerCaptor.getValue();

    stateListeners[0].onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    stateListeners[0].onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

    // subchannel reports connecting when pick subchannel is called
    assertEquals(Status.OK, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());
    stateListeners[0].onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));

    Status error = Status.UNAVAILABLE.withDescription("boom!");
    reset(mockHelper);

    if (enableHappyEyeballs) {
      stateListeners[0].onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      forwardTimeByConnectionDelay();
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      stateListeners[1] = stateListenerCaptor.getValue();
      stateListeners[1].onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      forwardTimeByConnectionDelay();
      inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
      stateListeners[2] = stateListenerCaptor.getValue();
      stateListeners[2].onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      forwardTimeByConnectionDelay();
      inOrder.verify(mockSubchannel4).start(stateListenerCaptor.capture());
      stateListeners[3] = stateListenerCaptor.getValue();
      stateListeners[3].onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      forwardTimeByConnectionDelay();
    } else {
      stateListeners[0].onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      for (int i = 1; i < stateListeners.length; i++) {
        inOrder.verify(subchannels[i]).start(stateListenerCaptor.capture());
        stateListeners[i] = stateListenerCaptor.getValue();
        stateListeners[i].onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      }
    }

    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    inOrder.verify(mockHelper).refreshNameResolution();
    assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

    stateListeners[0].onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(mockSubchannel1, picker.pickSubchannel(mockArgs).getSubchannel());
  }

  @Test
  public void pickAfterResolutionAfterTransientValue() {
    InOrder inOrder = inOrder(mockHelper);
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(0));
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();

    reset(mockHelper);

    // An error has happened.
    Status error = Status.UNAVAILABLE.withDescription("boom!");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    inOrder.verify(mockHelper).refreshNameResolution();
    assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

    // Transition from TRANSIENT_ERROR to CONNECTING should also be ignored.
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    verify(mockHelper, atLeast(0)).getSynchronizationContext();
    verify(mockHelper, atLeast(0)).getScheduledExecutorService();
    verifyNoMoreInteractions(mockHelper);
    assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());
  }

  @Test
  public void pickWithDupAddressesUpDownUp() {
    InOrder inOrder = inOrder(mockHelper);
    SocketAddress socketAddress = servers.get(0).getAddresses().get(0);
    EquivalentAddressGroup badEag = new EquivalentAddressGroup(
        Lists.newArrayList(socketAddress, socketAddress));
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(badEag);

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();

    reset(mockHelper);

    // An error has happened.
    Status error = Status.UNAVAILABLE.withDescription("boom!");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    inOrder.verify(mockHelper).refreshNameResolution();
    assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

    // Transition from TRANSIENT_ERROR to CONNECTING should also be ignored.
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    verify(mockHelper, atLeast(0)).getSynchronizationContext();
    verify(mockHelper, atLeast(0)).getScheduledExecutorService();
    verifyNoMoreInteractions(mockHelper);
    assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

    // Transition from CONNECTING to READY .
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertEquals(Status.OK, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());
  }

  @Test
  public void pickWithDupEagsUpDownUp() {
    InOrder inOrder = inOrder(mockHelper);
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(0), servers.get(0));

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();

    reset(mockHelper);

    // An error has happened.
    Status error = Status.UNAVAILABLE.withDescription("boom!");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    inOrder.verify(mockHelper).refreshNameResolution();
    assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

    // Transition from TRANSIENT_ERROR to CONNECTING should also be ignored.
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    verify(mockHelper, atLeast(0)).getSynchronizationContext();
    verify(mockHelper, atLeast(0)).getScheduledExecutorService();
    verifyNoMoreInteractions(mockHelper);
    assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

    // Transition from CONNECTING to READY .
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertEquals(Status.OK, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());
  }

  @Test
  public void nameResolutionError() {
    Status error = Status.NOT_FOUND.withDescription("nameResolutionError");
    loadBalancer.handleNameResolutionError(error);
    verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    PickResult pickResult = pickerCaptor.getValue().pickSubchannel(mockArgs);
    assertNull(pickResult.getSubchannel());
    assertEquals(error, pickResult.getStatus());
    verify(mockSubchannel1, never()).requestConnection();
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void nameResolutionError_emptyAddressList() {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.emptyList()).setAttributes(affinity).build());
    verify(mockHelper).updateBalancingState(connectivityStateCaptor.capture(),
        pickerCaptor.capture());
    PickResult pickResult = pickerCaptor.getValue().pickSubchannel(mockArgs);
    assertThat(pickResult.getSubchannel()).isNull();
    assertThat(pickResult.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(pickResult.getStatus().getDescription()).contains("no usable address");
    verify(mockSubchannel1, never()).requestConnection();
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void nameResolutionAfterSufficientTFs_multipleEags() {
    InOrder inOrder = inOrder(mockHelper);
    acceptXSubchannels(3);
    Status error = Status.UNAVAILABLE.withDescription("boom!");

    // Initial subchannel gets TF, LB is still in CONNECTING
    verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener1 = stateListenerCaptor.getValue();
    stateListener1.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    assertEquals(Status.OK, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

    // Second subchannel gets TF, no UpdateBalancingState called
    verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper, never()).refreshNameResolution();
    inOrder.verify(mockHelper, never()).updateBalancingState(any(), any());

    // Third subchannel gets TF, LB goes into TRANSIENT_FAILURE and does a refreshNameResolution
    verify(mockSubchannel3).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
    stateListener3.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    inOrder.verify(mockHelper).refreshNameResolution();
    assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

    // Only after we have TFs reported for # of subchannels do we call refreshNameResolution
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper, never()).refreshNameResolution();
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper, never()).refreshNameResolution();
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).refreshNameResolution();

    // Now that we have refreshed, the count should have been reset
    // Only after we have TFs reported for # of subchannels do we call refreshNameResolution
    stateListener1.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper, never()).refreshNameResolution();
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper, never()).refreshNameResolution();
    stateListener3.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).refreshNameResolution();
  }

  @Test
  public void nameResolutionAfterSufficientTFs_singleEag() {
    InOrder inOrder = inOrder(mockHelper);
    EquivalentAddressGroup eag = new EquivalentAddressGroup(Arrays.asList(
        new FakeSocketAddress("server1"),
        new FakeSocketAddress("server2"),
        new FakeSocketAddress("server3")));
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(Arrays.asList(eag)).build());
    Status error = Status.UNAVAILABLE.withDescription("boom!");

    // Initial subchannel gets TF, LB is still in CONNECTING
    verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener1 = stateListenerCaptor.getValue();
    stateListener1.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    assertEquals(Status.OK, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

    // Second subchannel gets TF, no UpdateBalancingState called
    verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper, never()).refreshNameResolution();
    inOrder.verify(mockHelper, never()).updateBalancingState(any(), any());

    // Third subchannel gets TF, LB goes into TRANSIENT_FAILURE and does a refreshNameResolution
    verify(mockSubchannel3).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
    stateListener3.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    inOrder.verify(mockHelper).refreshNameResolution();
    assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

    // Only after we have TFs reported for # of subchannels do we call refreshNameResolution
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper, never()).refreshNameResolution();
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper, never()).refreshNameResolution();
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).refreshNameResolution();

    // Now that we have refreshed, the count should have been reset
    // Only after we have TFs reported for # of subchannels do we call refreshNameResolution
    stateListener1.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper, never()).refreshNameResolution();
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper, never()).refreshNameResolution();
    stateListener3.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).refreshNameResolution();
  }

  @Test
  public void nameResolutionSuccessAfterError() {
    loadBalancer.handleNameResolutionError(Status.NOT_FOUND.withDescription("nameResolutionError"));
    verify(mockHelper)
        .updateBalancingState(any(ConnectivityState.class), any(SubchannelPicker.class));
    verify(mockSubchannel1, never()).requestConnection();

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();

    assertNull(pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());

    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertEquals(mockSubchannel1, pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());
  }

  @Test
  public void nameResolutionTemporaryError() {
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(0));
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel1n2);
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener1 = stateListenerCaptor.getValue();
    stateListener1.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertEquals(mockSubchannel1, pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());

    loadBalancer.handleNameResolutionError(
        Status.UNAVAILABLE.withDescription("nameResolutionError"));
    inOrder.verify(mockHelper).updateBalancingState(
        eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    inOrder.verify(mockSubchannel1n2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();

    assertNull(pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());

    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertEquals(mockSubchannel1n2,
        pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());
  }


  @Test
  public void nameResolutionErrorWithStateChanges() {
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(0));
    InOrder inOrder = inOrder(mockHelper);
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();

    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));
    inOrder.verify(mockHelper).updateBalancingState(
        eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));
    inOrder.verify(mockHelper).refreshNameResolution();
    Status error = Status.NOT_FOUND.withDescription("nameResolutionError");
    loadBalancer.handleNameResolutionError(error);
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    PickResult pickResult = pickerCaptor.getValue().pickSubchannel(mockArgs);
    assertNull(pickResult.getSubchannel());
    assertEquals(error, pickResult.getStatus());

    Status error2 = Status.NOT_FOUND.withDescription("nameResolutionError2");
    loadBalancer.handleNameResolutionError(error2);
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());

    pickResult = pickerCaptor.getValue().pickSubchannel(mockArgs);
    assertNull(pickResult.getSubchannel());
    assertEquals(error2, pickResult.getStatus());
  }

  @Test
  public void requestConnection() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);
    List<EquivalentAddressGroup> oldServers = Lists.newArrayList(servers.get(0), servers.get(1));

    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel1).requestConnection();
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));

    // calling requestConnection() only starts next subchannel when it is in TF
    loadBalancer.requestConnection();
    inOrder.verify(mockSubchannel2, never()).start(any());

    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));
    loadBalancer.requestConnection();
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel2).requestConnection();

    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));

    // calling requestConnection is now a no-op
    loadBalancer.requestConnection();
    inOrder.verify(mockHelper, never()).updateBalancingState(any(), any());
    inOrder.verify(mockSubchannel1, never()).requestConnection();
    inOrder.verify(mockSubchannel2, never()).requestConnection();
  }

  @Test
  public void failChannelWhenSubchannelsFail() {
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(0), servers.get(1));
    when(mockSubchannel1.getAllAddresses()).thenReturn(Lists.newArrayList(servers.get(0)));
    when(mockSubchannel2.getAllAddresses()).thenReturn(Lists.newArrayList(servers.get(1)));

    // accept resolved addresses
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2);
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    assertNull(pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());

    inOrder.verify(mockSubchannel1).requestConnection();
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));

    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));

    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    assertEquals(CONNECTION_ERROR, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());
  }

  @Test
  public void updateAddresses_emptyEagList_returns_false() {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    assertFalse(loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.emptyList()).setAttributes(affinity).build()).isOk());
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());
  }

  @Test
  public void updateAddresses_eagListWithNull_returns_false() {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    List<EquivalentAddressGroup> eags = Collections.singletonList(null);
    assertFalse(loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(eags).setAttributes(affinity).build()).isOk());
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());
  }

  @Test
  public void updateAddresses_disjoint_idle() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);
    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers = Lists.newArrayList(servers.get(0), servers.get(1));
    SubchannelStateListener stateListener2 = null;

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    forwardTimeByConnectionDelay();
    if (enableHappyEyeballs) {
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      stateListener2 = stateListenerCaptor.getValue();
    }
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // First connection attempt is successful
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // Verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));

    // Going into IDLE state
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
    picker = pickerCaptor.getValue();

    // Creating second set of disjoint endpoints/addresses
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(2), servers.get(3));

    // Accept new resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());

    // New channels not created until there is a request, remove old ones, keep intersecting ones
    inOrder.verify(mockHelper, never()).createSubchannel(any());
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    verify(mockSubchannel1).shutdown();
    if (enableHappyEyeballs) {
      verify(mockSubchannel2).shutdown();
    }

    // If obsolete subchannel becomes ready, the state should not be affected
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    if (enableHappyEyeballs) {
      stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    }
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());

    // Calling pickSubchannel() creates the subchannel and starts a connection
    picker.pickSubchannel(mockArgs);
    inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();

    // But the picker calls requestConnection() only once
    inOrder.verify(mockSubchannel3).requestConnection();

    // Ready subchannel 3
    stateListener3.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());

    // Picking a subchannel returns subchannel 3
    picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void updateAddresses_disjoint_connecting() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);

    SubchannelStateListener stateListener2 = null;
    SubchannelStateListener stateListener4 = null;

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers = Lists.newArrayList(servers.get(0), servers.get(1));

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel1).requestConnection();
    if (enableHappyEyeballs) {
      forwardTimeByConnectionDelay();
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      stateListener2 = stateListenerCaptor.getValue();
    }
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Creating second set of endpoints/addresses
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(2), servers.get(3));

    // Accept new resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    forwardTimeByConnectionDelay();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
    inOrder.verify(mockSubchannel3).requestConnection();
    SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
    if (enableHappyEyeballs) {
      inOrder.verify(mockSubchannel4).start(stateListenerCaptor.capture());
      stateListener4 = stateListenerCaptor.getValue();
    }
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Old subchannels should shut down (in no particular order) and request a connection
    verify(mockSubchannel1).shutdown();
    if (enableHappyEyeballs) {
      verify(mockSubchannel2).shutdown();
    }
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // If old subchannel becomes ready, the state should not be affected
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    if (enableHappyEyeballs) {
      stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    }
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Fail connection attempt to third address
    stateListener3.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Verify starting connection attempt to fourth address
    if (!enableHappyEyeballs) {
      inOrder.verify(mockSubchannel4).start(stateListenerCaptor.capture());
      stateListener4 = stateListenerCaptor.getValue();
    }
    inOrder.verify(mockSubchannel4).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Succeed connection attempt to fourth address
    stateListener4.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // Verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void updateAddresses_disjoint_ready_twice() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4, mockSubchannel1n2, mockSubchannel2n2);
    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers = Lists.newArrayList(servers.get(0), servers.get(1));
    SubchannelStateListener stateListener2 = null;
    SubchannelStateListener stateListener4 = null;

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // First connection attempt is successful
    inOrder.verify(mockSubchannel1).requestConnection();

    // Trigger second subchannel to connect
    if (enableHappyEyeballs) {
      forwardTimeByConnectionDelay();
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      stateListener2 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());
    }

    // Mark first subchannel ready, verify state change
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));

    if (enableHappyEyeballs) {
      // Successful connection shuts down other subchannel
      inOrder.verify(mockSubchannel2).shutdown();
    }
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());


    // Verify that picker returns correct subchannel
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));

    // Creating second set of endpoints/addresses
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(2), servers.get(3));

    // Accept new resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    inOrder.verify(mockSubchannel1).shutdown();
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
    inOrder.verify(mockSubchannel3, never()).start(stateListenerCaptor.capture());

    // Trigger connection creation
    picker = pickerCaptor.getValue();
    assertEquals(PickResult.withNoResult(), picker.pickSubchannel(mockArgs));
    inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel3).requestConnection();
    stateListener3.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());

    if (enableHappyEyeballs) {
      forwardTimeByConnectionDelay();
      inOrder.verify(mockSubchannel4).start(stateListenerCaptor.capture());
      stateListener4 = stateListenerCaptor.getValue();
    }
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());
    picker = pickerCaptor.getValue();

    // If obsolete subchannel becomes ready, the state should not be affected
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    if (enableHappyEyeballs) {
      stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    }
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Calling pickSubchannel() twice gave the same result
    assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));

    // The picker should only call requestConnection() once
    picker = pickerCaptor.getValue();
    assertEquals(PickResult.withNoResult(), picker.pickSubchannel(mockArgs));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Ready subchannel 3
    stateListener3.onSubchannelState(ConnectivityStateInfo.forNonError(READY));

    if (enableHappyEyeballs) {
      // Successful connection shuts down other subchannel
      inOrder.verify(mockSubchannel4).shutdown();
    }

    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    picker = pickerCaptor.getValue();
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // Verify that pickSubchannel() returns correct subchannel
    assertEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));

    // Creating third set of endpoints/addresses
    List<EquivalentAddressGroup> newestServers = Lists.newArrayList(servers.get(0), servers.get(1));

    // Second address update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newestServers).setAttributes(affinity).build());
    inOrder.verify(mockSubchannel3).shutdown();
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    picker = pickerCaptor.getValue();

    // Calling pickSubchannel() twice gave the same result
    assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));

    // But the picker calls requestConnection() only once
    inOrder.verify(mockSubchannel1n2).start(stateListenerCaptor.capture());
    stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel1n2).requestConnection();
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    assertEquals(PickResult.withNoResult(), pickerCaptor.getValue().pickSubchannel(mockArgs));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // If obsolete subchannel becomes ready, the state should not be affected
    stateListener3.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    if (enableHappyEyeballs) {
      stateListener4.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    }
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Connection attempt to address 1 is unsuccessful
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));

    // Starting connection attempt to address 2
    FakeSubchannel mockSubchannel2Attempt =
        enableHappyEyeballs ? mockSubchannel2n2 : mockSubchannel2;
    inOrder.verify(mockSubchannel2Attempt).start(stateListenerCaptor.capture());
    stateListener2 = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel2Attempt).requestConnection();

    // Connection attempt to address 2 is successful
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockSubchannel1n2).shutdown();

    // Successful connection shuts down other subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    picker = pickerCaptor.getValue();

    // Verify that picker still returns correct subchannel
    assertEquals(
        PickResult.withSubchannel(mockSubchannel2Attempt), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void updateAddresses_disjoint_transient_failure() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);

    SubchannelStateListener stateListener2 = null;

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> addrs = Lists.newArrayList(servers.get(0), servers.get(1));

    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(addrs).setAttributes(affinity).build());
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    if (enableHappyEyeballs) {
      forwardTimeByConnectionDelay();
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      stateListener2 = stateListenerCaptor.getValue();
    }
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Failing first connection attempt
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Starting second connection attempt
    if (!enableHappyEyeballs) {
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      stateListener2 = stateListenerCaptor.getValue();
    }
    inOrder.verify(mockSubchannel2).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Failing second connection attempt
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    // sticky transient failure
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());

    // Creating second set of endpoints/addresses
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(2), servers.get(3));

    // Accept new resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());

    // subchannel 3 still attempts a connection even though we stay in transient failure
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
    verify(mockSubchannel1).shutdown();
    verify(mockSubchannel2).shutdown();
    inOrder.verify(mockSubchannel3).requestConnection();

    // obsolete subchannels should not affect us
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());

    // Third subchannel connection attempt is unsuccessful
    stateListener3.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockSubchannel4).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener4 = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel4).requestConnection();

    // Fourth subchannel connection attempt is successful
    stateListener4.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockSubchannel3).shutdown();
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // Picking a subchannel returns subchannel 3
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void updateAddresses_intersecting_idle() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers =
        Lists.newArrayList(servers.get(0), servers.get(1));

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    if (enableHappyEyeballs) {
      forwardTimeByConnectionDelay();
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    } else {
      stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    }
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // First connection attempt is successful
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // Successful connection attempt shuts down other subchannels
    if (enableHappyEyeballs) {
      inOrder.verify(mockSubchannel2).shutdown();
    }

    // Verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));

    // Going into IDLE state, nothing should happen unless requested
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
    picker = pickerCaptor.getValue();

    // Creating second set of intersecting endpoints/addresses
    List<EquivalentAddressGroup> newServers =
        Lists.newArrayList(servers.get(0), servers.get(2), servers.get(3));

    // Accept new resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());

    // We create new channels and remove old ones, keeping intersecting ones
    inOrder.verify(mockHelper, never()).createSubchannel(createArgsCaptor.capture());
    forwardTimeByConnectionDelay(2);
    verify(mockSubchannel3, never()).start(stateListenerCaptor.capture());
    verify(mockSubchannel4, never()).start(stateListenerCaptor.capture());

    // If obsolete subchannel becomes ready, the state should not be affected
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());

    // Calling pickSubchannel() twice gave the same result
    inOrder.verify(mockHelper, never()).updateBalancingState(any(), any());
    assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));
    assertEquals(PickResult.withNoResult(), picker.pickSubchannel(mockArgs));
    verify(mockSubchannel3, never()).start(stateListenerCaptor.capture());

    // But the picker calls requestConnection() only once
    inOrder.verify(mockSubchannel1).requestConnection();
    inOrder.verify(mockSubchannel1, never()).requestConnection();

    // internal subchannel calls back and reports connecting
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(PickResult.withNoResult(), pickerCaptor.getValue().pickSubchannel(mockArgs));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Ready subchannel 1
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());

    // Picking a subchannel returns subchannel 1 through multiple calls
    picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void updateAddresses_intersecting_connecting() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers =
        Lists.newArrayList(servers.get(0), servers.get(1), servers.get(2));

    // Accept Addresses and verify proper connection flow
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    forwardTimeByConnectionDelay(2);

    // callback from internal subchannel
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    inOrder.verify(mockSubchannel1).requestConnection();

    // Creating second set of endpoints/addresses
    List<EquivalentAddressGroup> newServers =
        Lists.newArrayList(servers.get(0), servers.get(1), servers.get(3), servers.get(4));

    // Accept new resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper, never()).updateBalancingState(eq(CONNECTING), any());
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // keep intersecting ones kept and start over so nothing should try to connect
    verify(mockSubchannel1, never()).shutdown();
    verify(mockSubchannel2, never()).shutdown();
    inOrder.verify(mockSubchannel4, never()).requestConnection();
    verify(mockSubchannel5, never()).start(stateListenerCaptor.capture());

    if (enableHappyEyeballs) {
      forwardTimeByConnectionDelay(2);
      inOrder.verify(mockSubchannel1, never()).requestConnection();
      inOrder.verify(mockSubchannel4).requestConnection();
    }

    // First connection attempt is successful
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // verify that picker returns correct subchannel
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void updateAddresses_intersecting_ready() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);
    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers =
        Lists.newArrayList(servers.get(0), servers.get(1));

    // Accept Addresses and verify proper connection flow
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    forwardTimeByConnectionDelay();
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // First connection attempt is successful
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // Verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));

    // Creating second set of endpoints/addresses
    List<EquivalentAddressGroup> newServers =
        Lists.newArrayList(servers.get(0), servers.get(1), servers.get(2));
    // Accept new resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());

    // The state is still READY after update since we had an intersecting subchannel that was READY.
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // Verify that picker still returns correct subchannel
    picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void updateAddresses_intersecting_transient_failure() {
    assumeTrue(!isSerializingRetries());

    // Starting first connection attempt
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4); // captor: captures

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers =
        Lists.newArrayList(servers.get(0), servers.get(1));

    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Failing first connection attempt
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Starting second connection attempt
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel2).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Failing second connection attempt
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    // sticky transient failure
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());

    // Creating second set of endpoints/addresses
    List<EquivalentAddressGroup> newServers =
        Lists.newArrayList(servers.get(0), servers.get(1), servers.get(2));

    // Accept new resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());

    // subchannel 3 still attempts a connection even though we stay in transient failure
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockSubchannel3).getAttributes();
    inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
    inOrder.verify(mockSubchannel3).requestConnection();

    // no other connections should be requested by LB, we should come out of backoff to request
    verifyNoMoreInteractions(mockSubchannel3);

    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());

    // Second subchannel connection attempt is now successful
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockSubchannel3).shutdown();

    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());

    // Picking a subchannel returns subchannel 3
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void updateAddresses_intersecting_enter_transient_failure() {
    // after an address update occurs, verify that the client properly tries all
    // addresses and only then enters transient failure.
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);

    SubchannelStateListener stateListener3 = null;

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers = Lists.newArrayList(servers.get(0), servers.get(1));

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel1).requestConnection();

    if (enableHappyEyeballs) {
      forwardTimeByConnectionDelay();
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    }

    // callback from internal subchannel
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));

    // Creating second set of endpoints/addresses
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(0), servers.get(2));

    // Accept new resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // We create new channels and remove old ones, keeping intersecting ones
    if (enableHappyEyeballs) {
      inOrder.verify(mockSubchannel2).shutdown();
      forwardTimeByConnectionDelay();
      inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
      stateListener3 = stateListenerCaptor.getValue();
    }

    // First connection attempt is unsuccessful
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());
    if (!enableHappyEyeballs) {
      inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
      stateListener3 = stateListenerCaptor.getValue();
    }

    // Subchannel 3 attempt starts but fails
    inOrder.verify(mockSubchannel3).requestConnection();
    stateListener3.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());
  }

  @Test
  public void updateAddresses_identical_idle() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);
    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers = Lists.newArrayList(servers.get(0), servers.get(1));

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // First connection attempt is successful
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // Verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));

    // Going into IDLE state
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

    // Accept same resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());

    // Verify that no new subchannels were created or started
    verify(mockHelper, times(1)).createSubchannel(createArgsCaptor.capture());
    verify(mockSubchannel1, times(1)).start(stateListenerCaptor.capture());
    verify(mockSubchannel2, times(0)).start(stateListenerCaptor.capture());

    // First connection attempt is successful
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void updateAddresses_identical_connecting() {
    InOrder inOrder = inOrder(mockHelper);

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers = Lists.newArrayList(servers.get(0), servers.get(1));

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(any());
    verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Accept same resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());

    // Verify that started over and found a noop on connecting for first subchannel
    inOrder.verify(mockHelper, never()).createSubchannel(any());

    if (enableHappyEyeballs) {
      forwardTimeByConnectionDelay();
      inOrder.verify(mockHelper).createSubchannel(any());
      verify(mockSubchannel2).start(any());
    }

    // Accept same resolved addresses to update - all were connecting, no updateBalancingState
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockHelper, never()).updateBalancingState(any(), any());

    // Verify that no new subchannels were created or started
    inOrder.verify(mockHelper, never()).createSubchannel(any());
    verify(mockSubchannel1, times(1)).start(any());
    if (enableHappyEyeballs) {
      verify(mockSubchannel2, times(1)).start(any());
    } else {
      verify(mockSubchannel2, times(0)).start(any());
    }

    // First connection attempt is successful
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void updateAddresses_identical_ready() {
    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers = Lists.newArrayList(servers.get(0), servers.get(1));

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // First connection attempt is successful
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // verify that picker returns correct subchannel
    verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));

    // Accept same resolved addresses to update
    reset(mockHelper);
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    fakeClock.forwardTime(CONNECTION_DELAY_INTERVAL_MS, TimeUnit.MILLISECONDS);

    // Verify that no new subchannels were created or started
    verify(mockSubchannel2,never()).start(any());
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // verify that picker hasn't changed via checking mock helper's interactions
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void updateAddresses_identical_transient_failure() {
    assumeTrue(!isSerializingRetries());

    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);
    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers = Lists.newArrayList(servers.get(0), servers.get(1));

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // First connection attempt is unsuccessful
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Second connection attempt is unsuccessful
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    inOrder.verify(mockHelper).refreshNameResolution();
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());

    // Accept same resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());

    // Verify that no new subchannels were created or started
    verify(mockHelper, times(2)).createSubchannel(createArgsCaptor.capture());
    verify(mockSubchannel1, times(1)).start(stateListenerCaptor.capture());
    verify(mockSubchannel2, times(1)).start(stateListenerCaptor.capture());
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());

    // No new connections are requested, subchannels responsible for completing their own backoff
    verify(mockHelper, atLeast(0)).getSynchronizationContext();  // Don't care
    verify(mockHelper, atLeast(0)).getScheduledExecutorService();
    verifyNoMoreInteractions(mockHelper);

    // First connection attempt is successful
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockSubchannel2).shutdown();

    // verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void twoAddressesSeriallyConnect() {
    // Starting first connection attempt
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3);
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel1).requestConnection();

    // Failing first connection attempt
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Starting second connection attempt
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel2).requestConnection();

    // Second connection attempt is successful
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // Verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void multiple_backoffs() {
    // This test case mimics a backoff without implementing one
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(0), servers.get(1));

    // Starting first connection attempt
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel1).requestConnection();

    // Failing first connection attempt
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Starting second connection attempt
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel2).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Failing second connection attempt
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    inOrder.verify(mockHelper).refreshNameResolution();
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());

    // backoff for first address
    forwardTimeByConnectionDelay();
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());

    // Mimic backoff for second address
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());

    // Failing first connection attempt
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());

    // Failing second connection attempt
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());

    // Mimic backoff for first address
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());

    // Mimic backoff for second address
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());

    // Connection attempt to second address is now successful
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockSubchannel1).shutdown();

    // Verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    when(mockSubchannel2.getAllAddresses()).thenReturn(Lists.newArrayList(servers.get(0)));
    assertEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));

    // If first subchannel is ready before it completes shutdown, we still choose subchannel 2
    // This can be verified by checking the mock helper actions after setting it READY.
    reset(mockHelper);
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));

    // Happy Eyeballs, once transient failure is reported, no longer schedules connections.
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void backoff_faster_than_serial_connection() {
    // this tests the case where a subchannel completes its backoff and readies a connection
    // before the other addresses have a chance to complete their connection attempts
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4); // captor: captures

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> addrs = Lists.newArrayList(servers.get(0),
        servers.get(1));

    // Accepting resolved addresses starts all subchannels
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(addrs).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Failing first connection attempt
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Starting second connection attempt
    inOrder.verify(mockSubchannel2).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Mimic backoff for first address
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Connection attempt to first address is now successful
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void success_from_transient_failure() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> addrs = Lists.newArrayList(servers.get(0), servers.get(1));

    // Accepting resolved addresses starts first subchannel and cascades on failures
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(addrs).setAttributes(affinity).build());
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Failing first connection attempt
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Starting second connection attempt
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel2).requestConnection();

    // Mimic backoff for first address
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Failing second connection attempt
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    inOrder.verify(mockHelper).refreshNameResolution();
    //   sticky transient failure
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());

    // Failing connection attempt to first address
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());

    // Mimic backoff for second address
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());

    // Connection attempt to second address is now successful
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockSubchannel1).shutdown();
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());

    // Verify that picker returns correct subchannel
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));

    // If first address is successful, nothing happens. Verify by checking mock helper
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper,never()).updateBalancingState(any(), any());
    inOrder.verify(mockHelper,never()).createSubchannel(any());
  }

  @Test
  public void lastAddressFailingNotTransientFailure() {
    // This tests the case where after an address update, the last address escapes a backoff
    // and reports transient failure before all addresses have failed connection
    // attempts, in which case we should not report TRANSIENT_FAILURE.
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);
    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers = Lists.newArrayList(servers.get(0), servers.get(1));
    SubchannelStateListener stateListener2 = null;

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel1).requestConnection();
    forwardTimeByConnectionDelay();

    if (enableHappyEyeballs) {
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      stateListener2 = stateListenerCaptor.getValue();
    }

    // First connection attempt is unsuccessful
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Second connection attempt is connecting
    if (!enableHappyEyeballs) {
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      stateListener2 = stateListenerCaptor.getValue();
    }
    inOrder.verify(mockSubchannel2).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Accept same resolved addresses to update
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(2), servers.get(1));
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());

    // Verify that no new subchannels were created or started
    inOrder.verify(mockSubchannel1).shutdown();
    inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel3).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Second address connection attempt is unsuccessful, but should not go into transient failure
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Third address connection attempt is unsuccessful, now we enter transient failure
    stateListener3.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());

    // obsolete subchannels have no impact
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());

    // Second subchannel is successful
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // verify that picker returns correct subchannel
    inOrder.verify(mockSubchannel3).shutdown();
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void recreate_shutdown_subchannel() {
    // Take the case where the latter subchannel is readied. If we then go to an IDLE state and
    // re-request a connection, we should start and create a new subchannel for the first
    // address in our list.

    // Starting first connection attempt
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4, mockSubchannel1n2); // captor: captures

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> addrs =
        Lists.newArrayList(servers.get(0), servers.get(1));

    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(addrs).setAttributes(affinity).build());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Failing first connection attempt
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Starting second connection attempt
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel2).requestConnection();

    // Successful second connection attempt
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockSubchannel1).shutdown();
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // Go to IDLE
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());

    SubchannelPicker picker = pickerCaptor.getValue();

    // Calling pickSubchannel() requests a connection.
    assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));
    inOrder.verify(mockSubchannel1n2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel1n2).requestConnection();
    when(mockSubchannel1.getAllAddresses()).thenReturn(Lists.newArrayList(servers.get(0)));

    // gives the same result when called twice
    stateListener3.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // third subchannel connection attempt fails
    stateListener3.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // second subchannel connection attempt succeeds
    inOrder.verify(mockSubchannel2).requestConnection();
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockSubchannel1n2).shutdown();
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // verify that picker returns correct subchannel
    picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void shutdown() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3,
        mockSubchannel4, mockSubchannel5);

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).build());

    forwardTimeByConnectionDelay(servers.size() - 1);
    int expectedSubchannelsCreated = enableHappyEyeballs ? servers.size() : 1;
    inOrder.verify(mockHelper, times(expectedSubchannelsCreated)).createSubchannel(any());

    loadBalancer.shutdown();

    inOrder.verify(mockSubchannel1).shutdown();
    if (enableHappyEyeballs) {
      verify(mockSubchannel2).shutdown();
      verify(mockSubchannel3).shutdown();
      verify(mockSubchannel4).shutdown();
      verify(mockSubchannel5).shutdown();
    }
    assertEquals(SHUTDOWN, loadBalancer.getConcludedConnectivityState());

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).build());
    forwardTimeByConnectionDelay();
    inOrder.verify(mockHelper, never()).refreshNameResolution();
    inOrder.verify(mockSubchannel1, never()).start(any());
    inOrder.verify(mockSubchannel1, never()).requestConnection();
    inOrder.verify(mockSubchannel2, never()).requestConnection();
  }

  @Test
  public void ready_then_transient_failure_again() {
    // Starting first connection attempt
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4, mockSubchannel1n2); // captor: captures

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> addrs =
        Lists.newArrayList(servers.get(0), servers.get(1));

    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(addrs).setAttributes(affinity).build());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Failing first connection attempt
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Starting second connection attempt
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel2).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Successful second connection attempt
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockSubchannel1).shutdown();
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // Go to IDLE
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());

    SubchannelPicker picker = pickerCaptor.getValue();

    // Calling pickSubchannel() requests a connection, gives the same result when called twice.
    assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));
    inOrder.verify(mockSubchannel1n2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel1n2).requestConnection();
    when(mockSubchannel3.getAllAddresses()).thenReturn(Lists.newArrayList(servers.get(0)));
    stateListener3.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // first subchannel connection attempt fails
    stateListener3.onSubchannelState(ConnectivityStateInfo.forTransientFailure(CONNECTION_ERROR));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // second subchannel connection attempt
    inOrder.verify(mockSubchannel2).requestConnection();
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // verify that picker returns correct subchannel
    inOrder.verify(mockSubchannel1n2).shutdown();
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void happy_eyeballs_trigger_connection_delay() {
    assumeTrue(enableHappyEyeballs); // This test is only for happy eyeballs
    // Starting first connection attempt
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1,
        mockSubchannel2, mockSubchannel3, mockSubchannel4);
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());

    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    inOrder.verify(mockSubchannel1).requestConnection();
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();

    // Until we hit the connection delay interval threshold, nothing should happen
    verifyNoMoreInteractions(mockSubchannel2);
    fakeClock.forwardTime(CONNECTION_DELAY_INTERVAL_MS - 1, TimeUnit.MILLISECONDS);
    verifyNoMoreInteractions(mockSubchannel2);

    // After 250 ms, second connection attempt starts
    fakeClock.forwardTime(1, TimeUnit.MILLISECONDS);
    verify(mockHelper, times(2)).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel2).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());
    verifyNoMoreInteractions(mockSubchannel3);

    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Second connection attempt is successful
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // Verify that picker returns correct subchannel
    inOrder.verify(mockSubchannel1).shutdown();
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void happy_eyeballs_connection_results_happen_after_get_to_end() {
    assumeTrue(enableHappyEyeballs); // This test is only for happy eyeballs

    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3);
    Status error = Status.UNAUTHENTICATED.withDescription("simulated failure");

    List<EquivalentAddressGroup> addrs =
        Lists.newArrayList(servers.get(0), servers.get(1), servers.get(2));

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(addrs).setAttributes(affinity).build());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    forwardTimeByConnectionDelay(2);
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // first connection attempt fails
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Move off the end of the list, but connections requests haven't been completed
    forwardTimeByConnectionDelay();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // second connection attempt fails
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());
    // second connection attempt fails again, but still haven't finished third subchannel
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockHelper, never()).refreshNameResolution();

    // last subchannel's connection attempt fails
    stateListener3.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockHelper).refreshNameResolution();


    // Refail the first one, after third time should refreshNameResolution
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper, never()).refreshNameResolution();
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper, never()).refreshNameResolution();
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).refreshNameResolution();
  }


  @Test
  public void happy_eyeballs_pick_pushes_index_over_end() {
    assumeTrue(enableHappyEyeballs); // This test is only for happy eyeballs

    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3,
        mockSubchannel2n2, mockSubchannel3n2);
    Status error = Status.UNAUTHENTICATED.withDescription("simulated failure");

    List<EquivalentAddressGroup> addrs =
        Lists.newArrayList(servers.get(0), servers.get(1), servers.get(2));
    Subchannel[] subchannels = new Subchannel[] {mockSubchannel1, mockSubchannel2, mockSubchannel3};
    SubchannelStateListener[] listeners = new SubchannelStateListener[subchannels.length];
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(addrs).setAttributes(affinity).build());
    forwardTimeByConnectionDelay(2);
    for (int i = 0; i < subchannels.length; i++) {
      inOrder.verify(subchannels[i]).start(stateListenerCaptor.capture());
      listeners[i] = stateListenerCaptor.getValue();
      listeners[i].onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    }
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(addrs).setAttributes(affinity).build());
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
    SubchannelPicker requestingPicker = pickerCaptor.getValue();

    // First pick moves index to addr 2
    PickResult pickResult = requestingPicker.pickSubchannel(mockArgs);
    assertEquals("RequestConnectionPicker", requestingPicker.getClass().getSimpleName());
    assertEquals(PickResult.withNoResult(), pickResult);
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());

    // Second pick moves index to addr 3
    pickResult = requestingPicker.pickSubchannel(mockArgs);
    assertEquals(PickResult.withNoResult(), pickResult);

    // Sending TF state to one subchannel pushes index past end, but shouldn't do anything
    listeners[2].onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper, never()).updateBalancingState(eq(TRANSIENT_FAILURE), any());

    // Put the LB into TF
    listeners[0].onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    listeners[1].onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    PickResult pickResultTF = pickerCaptor.getValue().pickSubchannel(mockArgs);
    assertFalse(pickResultTF.getStatus().isOk());

    // Doing a pick on the old RequestConnectionPicker when past the index end
    pickResult = requestingPicker.pickSubchannel(mockArgs);
    assertEquals(PickResult.withNoResult(), pickResult);
    inOrder.verify(mockHelper, never()).updateBalancingState(any(), any());

    // Try pushing after end with just picks
    listeners[0].onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    verify(mockSubchannel2).shutdown();
    verify(mockSubchannel3).shutdown();
    listeners[0].onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(addrs).setAttributes(affinity).build());
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
    SubchannelPicker requestingPicker2 = pickerCaptor.getValue();
    for (int i = 0; i <= subchannels.length; i++) {
      pickResult = requestingPicker2.pickSubchannel(mockArgs);
      assertEquals(PickResult.withNoResult(), pickResult);
    }
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());

    listeners[0].onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockSubchannel2n2).start(stateListenerCaptor.capture());
    stateListenerCaptor.getValue().onSubchannelState(
        ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockSubchannel3n2).start(stateListenerCaptor.capture());
    stateListenerCaptor.getValue().onSubchannelState(
        ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());
  }

  @Test
  public void happy_eyeballs_fail_then_trigger_connection_delay() {
    assumeTrue(enableHappyEyeballs); // This test is only for happy eyeballs
    // Starting first connection attempt
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3);
    assertEquals(IDLE, loadBalancer.getConcludedConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // indicates scheduling a connection
    inOrder.verify(mockSubchannel1).requestConnection();
    inOrder.verify(mockHelper).getSynchronizationContext();
    inOrder.verify(mockHelper).getScheduledExecutorService();

    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Until we hit the connection delay interval threshold, no connections should be requested
    verify(mockSubchannel1, times(1)).requestConnection();
    verify(mockSubchannel2, times(0)).requestConnection();
    fakeClock.forwardTime(CONNECTION_DELAY_INTERVAL_MS - 1, TimeUnit.MILLISECONDS);
    verify(mockSubchannel1, times(1)).requestConnection();
    verify(mockSubchannel2, times(0)).requestConnection();

    // If a connection fails, the next scheduled connection is reset to happen 250 ms later
    Status error = Status.UNAUTHENTICATED.withDescription("simulated failure");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());
    verify(mockSubchannel2, times(1)).requestConnection();

    // This time, after 1 ms, no connection attempt occurs
    fakeClock.forwardTime(1, TimeUnit.MILLISECONDS);
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    verify(mockSubchannel1, times(1)).requestConnection();
    verify(mockSubchannel2, times(1)).requestConnection();

    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    verify(mockSubchannel3, times(0)).requestConnection();

    // After 250 ms, second connection attempt starts
    // Skip subchannel 2 and request to address 3
    fakeClock.forwardTime(CONNECTION_DELAY_INTERVAL_MS - 1, TimeUnit.MILLISECONDS);
    verify(mockSubchannel1, times(1)).requestConnection();
    verify(mockSubchannel2, times(1)).requestConnection();
    verify(mockSubchannel3, times(1)).requestConnection();
    fakeClock.forwardTime(1, TimeUnit.MILLISECONDS);
    inOrder.verify(mockSubchannel2).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Simulate first connection attempt coming out of backoff
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(CONNECTING, loadBalancer.getConcludedConnectivityState());

    // Both subchannels racing, second connection attempt is successful
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConcludedConnectivityState());

    // Verify that picker returns correct subchannel
    inOrder.verify(mockSubchannel1).shutdown();
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void advance_index_then_request_connection() {
    loadBalancer.requestConnection(); // should be handled without throwing exception

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    forwardTimeByConnectionDelay(servers.size());

    loadBalancer.requestConnection(); // should be handled without throwing exception
  }

  @Test
  public void serialized_retries_two_passes() {
    assumeTrue(serializeRetries); // This test is only for serialized retries

    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3);
    Status error = Status.UNAUTHENTICATED.withDescription("simulated failure");

    List<EquivalentAddressGroup> addrs =
        Lists.newArrayList(servers.get(0), servers.get(1), servers.get(2));
    Subchannel[] subchannels = new Subchannel[]{mockSubchannel1, mockSubchannel2, mockSubchannel3};
    SubchannelStateListener[] listeners = new SubchannelStateListener[subchannels.length];
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(addrs).build());
    forwardTimeByConnectionDelay(2);
    for (int i = 0; i < subchannels.length; i++) {
      inOrder.verify(subchannels[i]).start(stateListenerCaptor.capture());
      inOrder.verify(subchannels[i]).requestConnection();
      listeners[i] = stateListenerCaptor.getValue();
      listeners[i].onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    }
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConcludedConnectivityState());
    assertFalse("Index should be at end", loadBalancer.isIndexValid());

    forwardTimeByBackoffDelay(); // should trigger retry
    for (int i = 0; i < subchannels.length; i++) {
      inOrder.verify(subchannels[i]).requestConnection();
      listeners[i].onSubchannelState(ConnectivityStateInfo.forTransientFailure(error)); // cascade
    }
    inOrder.verify(subchannels[0], never()).requestConnection(); // should wait for backoff delay

    forwardTimeByBackoffDelay(); // should trigger retry again
    for (int i = 0; i < subchannels.length; i++) {
      inOrder.verify(subchannels[i]).requestConnection();
      assertEquals(i, loadBalancer.getGroupIndex());
      listeners[i].onSubchannelState(ConnectivityStateInfo.forTransientFailure(error)); // cascade
    }
  }

  @Test
  public void index_looping() {
    Attributes.Key<String> key = Attributes.Key.create("some-key");
    Attributes attr1 = Attributes.newBuilder().set(key, "1").build();
    Attributes attr2 = Attributes.newBuilder().set(key, "2").build();
    Attributes attr3 = Attributes.newBuilder().set(key, "3").build();
    SocketAddress addr1 = new FakeSocketAddress("addr1");
    SocketAddress addr2 = new FakeSocketAddress("addr2");
    SocketAddress addr3 = new FakeSocketAddress("addr3");
    SocketAddress addr4 = new FakeSocketAddress("addr4");
    SocketAddress addr5 = new FakeSocketAddress("addr5");
    PickFirstLeafLoadBalancer.IndexI index = PickFirstLeafLoadBalancer.IndexI.create(Arrays.asList(
        new EquivalentAddressGroup(Arrays.asList(addr1, addr2), attr1),
        new EquivalentAddressGroup(Arrays.asList(addr3), attr2),
        new EquivalentAddressGroup(Arrays.asList(addr4, addr5), attr3)));
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr1);
    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr1);
    assertThat(index.isAtBeginning()).isTrue();
    assertThat(index.isValid()).isTrue();

    index.increment();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr2);
    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr1);
    assertThat(index.isAtBeginning()).isFalse();
    assertThat(index.isValid()).isTrue();

    index.increment();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr3);
    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr2);
    assertThat(index.isAtBeginning()).isFalse();
    assertThat(index.isValid()).isTrue();

    index.increment();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr4);
    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr3);
    assertThat(index.isAtBeginning()).isFalse();
    assertThat(index.isValid()).isTrue();

    index.increment();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr5);
    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr3);
    assertThat(index.isAtBeginning()).isFalse();
    assertThat(index.isValid()).isTrue();

    index.increment();
    assertThat(index.isAtBeginning()).isFalse();
    assertThat(index.isValid()).isFalse();

    index.reset();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr1);
    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr1);
    assertThat(index.isAtBeginning()).isTrue();
    assertThat(index.isValid()).isTrue();

    // We want to make sure both groupIndex and addressIndex are reset
    index.increment();
    index.increment();
    index.increment();
    index.increment();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr5);
    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr3);
    index.reset();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr1);
    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr1);
  }

  @Test
  public void index_updateGroups_resets() {
    SocketAddress addr1 = new FakeSocketAddress("addr1");
    SocketAddress addr2 = new FakeSocketAddress("addr2");
    SocketAddress addr3 = new FakeSocketAddress("addr3");
    PickFirstLeafLoadBalancer.IndexI index = PickFirstLeafLoadBalancer.IndexI.create(Arrays.asList(
        new EquivalentAddressGroup(Arrays.asList(addr1)),
        new EquivalentAddressGroup(Arrays.asList(addr2, addr3))));
    index.increment();
    index.increment();
    // We want to make sure both groupIndex and addressIndex are reset
    index.updateGroups(ImmutableList.of(
        new EquivalentAddressGroup(Arrays.asList(addr1)),
        new EquivalentAddressGroup(Arrays.asList(addr2, addr3))));
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr1);
  }

  @Test
  public void index_seekTo() {
    SocketAddress addr1 = new FakeSocketAddress("addr1");
    SocketAddress addr2 = new FakeSocketAddress("addr2");
    SocketAddress addr3 = new FakeSocketAddress("addr3");
    PickFirstLeafLoadBalancer.IndexI index = PickFirstLeafLoadBalancer.IndexI.create(Arrays.asList(
        new EquivalentAddressGroup(Arrays.asList(addr1, addr2)),
        new EquivalentAddressGroup(Arrays.asList(addr3))));
    assertThat(index.seekTo(addr3)).isTrue();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr3);
    assertThat(index.seekTo(addr1)).isTrue();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr1);
    assertThat(index.seekTo(addr2)).isTrue();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr2);
    index.seekTo(new FakeSocketAddress("addr4"));
    // Failed seekTo doesn't change the index
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr2);
  }

  @Test
  public void index_interleaving() {
    InetSocketAddress addr1_6 = new InetSocketAddress("f38:1:1", 1234);
    InetSocketAddress addr1_4 = new InetSocketAddress("10.1.1.1", 1234);
    InetSocketAddress addr2_4 = new InetSocketAddress("10.1.1.2", 1234);
    InetSocketAddress addr3_4 = new InetSocketAddress("10.1.1.3", 1234);
    InetSocketAddress addr4_4 = new InetSocketAddress("10.1.1.4", 1234);
    InetSocketAddress addr4_6 = new InetSocketAddress("f38:1:4", 1234);

    PickFirstLeafLoadBalancer.IndexI index = PickFirstLeafLoadBalancer.IndexI.create(Arrays.asList(
        new EquivalentAddressGroup(Arrays.asList(addr1_4, addr1_6)),
        new EquivalentAddressGroup(Arrays.asList(addr2_4)),
        new EquivalentAddressGroup(Arrays.asList(addr3_4)),
        new EquivalentAddressGroup(Arrays.asList(addr4_4, addr4_6))));

    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr1_4);
    assertThat(index.isAtBeginning()).isTrue();

    index.increment();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr1_6);
    assertThat(index.isAtBeginning()).isFalse();
    assertThat(index.isValid()).isTrue();
    assertThat(index.getGroupIndex()).isEqualTo(0);

    index.increment();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr2_4);
    assertThat(index.getGroupIndex()).isEqualTo(1);

    index.increment();
    if (enableHappyEyeballs) {
      assertThat(index.getCurrentAddress()).isSameInstanceAs(addr4_6);
      assertThat(index.getGroupIndex()).isEqualTo(3);
    } else {
      assertThat(index.getCurrentAddress()).isSameInstanceAs(addr3_4);
      assertThat(index.getGroupIndex()).isEqualTo(2);
    }

    index.increment();
    if (enableHappyEyeballs) {
      assertThat(index.getCurrentAddress()).isSameInstanceAs(addr3_4);
      assertThat(index.getGroupIndex()).isEqualTo(2);
    } else {
      assertThat(index.getCurrentAddress()).isSameInstanceAs(addr4_4);
      assertThat(index.getGroupIndex()).isEqualTo(3);
    }

    // Move to last entry
    assertThat(index.increment()).isTrue();
    assertThat(index.isValid()).isTrue();
    assertThat(index.getGroupIndex()).isEqualTo(3);

    // Move off of the end
    assertThat(index.increment()).isFalse();
    assertThat(index.isValid()).isFalse();
    assertThrows(IllegalStateException.class, index::getCurrentAddress);

    // Reset
    index.reset();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr1_4);
    assertThat(index.isAtBeginning()).isTrue();
    assertThat(index.isValid()).isTrue();

    // Seek to an address
    assertThat(index.seekTo(addr4_4)).isTrue();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr4_4);
  }

  private static class FakeSocketAddress extends SocketAddress {
    final String name;

    FakeSocketAddress(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return "FakeSocketAddress(" + name + ")";
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

  private void forwardTimeByConnectionDelay() {
    fakeClock.forwardTime(CONNECTION_DELAY_INTERVAL_MS, TimeUnit.MILLISECONDS);
  }

  private void forwardTimeByConnectionDelay(int times) {
    for (int i = 0; i < times; i++) {
      forwardTimeByConnectionDelay();
    }
  }

  private void forwardTimeByBackoffDelay() {
    backoffMillis = (long) (backoffMillis * 1.8); // backoff factor default is 1.6 with Jitter .2
    fakeClock.forwardTime(backoffMillis, TimeUnit.MILLISECONDS);
  }

  private void acceptXSubchannels(int num) {
    List<EquivalentAddressGroup> newServers = new ArrayList<>();
    for (int i = 0; i < num; i++) {
      newServers.add(servers.get(i));
    }
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
  }

  /**
   * This is currently only used for mocks, but could be used in a real test.
   */
  private static class FakeSubchannel extends Subchannel {
    private final Attributes attributes;
    private List<EquivalentAddressGroup> eags;
    private SubchannelStateListener listener;

    @Keep
    public FakeSubchannel(List<EquivalentAddressGroup> eags, Attributes attributes) {
      this.eags = Collections.unmodifiableList(eags);
      this.attributes = attributes;
    }

    @Override
    public List<EquivalentAddressGroup> getAllAddresses() {
      return eags;
    }

    @Override
    public Attributes getAttributes() {
      return attributes;
    }

    @Override
    public void start(SubchannelStateListener listener) {
      this.listener = checkNotNull(listener, "listener");
    }

    @Override
    public void updateAddresses(List<EquivalentAddressGroup> addrs) {
      this.eags = Collections.unmodifiableList(addrs);
    }

    @Override
    public void shutdown() {
      listener.onSubchannelState(ConnectivityStateInfo.forNonError(SHUTDOWN));
    }

    @Override
    public void requestConnection() {
    }

    @Override
    public String toString() {
      return "FakeSubchannel@" + hashCode() + "(" + eags + ")";
    }
  }

  private class MockHelperImpl extends LoadBalancer.Helper {
    private final List<Subchannel> subchannels;

    public MockHelperImpl(List<? extends Subchannel> subchannels) {
      this.subchannels = new ArrayList<Subchannel>(subchannels);
    }

    @Override
    public ManagedChannel createOobChannel(EquivalentAddressGroup eag, String authority) {
      return null;
    }

    @Override
    public String getAuthority() {
      return null;
    }

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
      // ignore
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
    public void refreshNameResolution() {
      // noop
    }

    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs args) {
      for (int i = 0; i < subchannels.size(); i++) {
        Subchannel subchannel = subchannels.get(i);
        List<EquivalentAddressGroup> addrs = subchannel.getAllAddresses();
        verify(subchannel, atLeast(1)).getAllAddresses(); // ignore the interaction
        if (!args.getAddresses().equals(addrs)) {
          continue;
        }
        subchannels.remove(i);
        return subchannel;
      }
      throw new IllegalArgumentException("Unexpected addresses: " + args.getAddresses());
    }
  }
}
