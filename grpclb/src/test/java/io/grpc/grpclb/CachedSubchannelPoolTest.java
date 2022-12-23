/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.grpclb;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.grpclb.CachedSubchannelPool.SHUTDOWN_TIMEOUT_MS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.SynchronizationContext;
import io.grpc.grpclb.CachedSubchannelPool.ShutdownSubchannelTask;
import io.grpc.grpclb.SubchannelPool.PooledSubchannelStateListener;
import io.grpc.internal.FakeClock;
import java.util.ArrayList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Unit tests for {@link CachedSubchannelPool}. */
@RunWith(JUnit4.class)
public class CachedSubchannelPoolTest {
  private static final EquivalentAddressGroup EAG1 =
      new EquivalentAddressGroup(new FakeSocketAddress("fake-address-1"), Attributes.EMPTY);
  private static final EquivalentAddressGroup EAG2 =
      new EquivalentAddressGroup(new FakeSocketAddress("fake-address-2"), Attributes.EMPTY);
  private static final Attributes.Key<String> ATTR_KEY = Attributes.Key.create("test-attr");
  private static final Attributes ATTRS1 = Attributes.newBuilder().set(ATTR_KEY, "1").build();
  private static final Attributes ATTRS2 = Attributes.newBuilder().set(ATTR_KEY, "2").build();
  
  private static final ConnectivityStateInfo READY_STATE =
      ConnectivityStateInfo.forNonError(ConnectivityState.READY);
  private static final FakeClock.TaskFilter SHUTDOWN_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          // The task is wrapped by SynchronizationContext, so we can't compare the type
          // directly.
          return command.toString().contains(ShutdownSubchannelTask.class.getSimpleName());
        }
      };

  private final Helper helper = mock(Helper.class);
  private final PooledSubchannelStateListener listener = mock(
      PooledSubchannelStateListener.class,
      AdditionalAnswers.delegatesTo(new PooledSubchannelStateListener() {
        @Override
        public void onSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
          syncContext.throwIfNotInThisSynchronizationContext();
        }
      }));

  private final FakeClock clock = new FakeClock();
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final SubchannelPool pool = new CachedSubchannelPool(helper);
  private final ArrayList<Subchannel> mockSubchannels = new ArrayList<>();
  private final ArgumentCaptor<CreateSubchannelArgs> createSubchannelArgsCaptor
      = ArgumentCaptor.forClass(CreateSubchannelArgs.class);

  @Before
  public void setUp() {
    doAnswer(new Answer<Subchannel>() {
        @Override
        public Subchannel answer(InvocationOnMock invocation) throws Throwable {
          Subchannel subchannel = mock(Subchannel.class);
          CreateSubchannelArgs args = (CreateSubchannelArgs) invocation.getArguments()[0];
          when(subchannel.getAllAddresses()).thenReturn(args.getAddresses());
          when(subchannel.getAttributes()).thenReturn(args.getAttributes());
          mockSubchannels.add(subchannel);
          return subchannel;
        }
      }).when(helper).createSubchannel(any(CreateSubchannelArgs.class));
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    when(helper.getScheduledExecutorService()).thenReturn(clock.getScheduledExecutorService());
    pool.registerListener(listener);
  }

  @After
  public void wrapUp() {
    if (mockSubchannels.isEmpty()) {
      return;
    }
    // Sanity checks
    for (Subchannel subchannel : mockSubchannels) {
      verify(subchannel, atMost(1)).shutdown();
    }
    verify(listener, atLeast(0))
        .onSubchannelState(any(Subchannel.class), any(ConnectivityStateInfo.class));
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void subchannelExpireAfterReturned() {
    Subchannel subchannel1 = pool.takeOrCreateSubchannel(EAG1, ATTRS1);
    assertThat(subchannel1).isNotNull();
    InOrder inOrder = Mockito.inOrder(helper);
    inOrder.verify(helper).createSubchannel(createSubchannelArgsCaptor.capture());
    CreateSubchannelArgs createSubchannelArgs = createSubchannelArgsCaptor.getValue();
    assertThat(createSubchannelArgs.getAddresses()).containsExactly(EAG1);
    assertThat(createSubchannelArgs.getAttributes()).isEqualTo(ATTRS1);

    Subchannel subchannel2 = pool.takeOrCreateSubchannel(EAG2, ATTRS2);
    assertThat(subchannel2).isNotNull();
    assertThat(subchannel2).isNotSameInstanceAs(subchannel1);
    inOrder.verify(helper).createSubchannel(createSubchannelArgsCaptor.capture());
    createSubchannelArgs = createSubchannelArgsCaptor.getValue();
    assertThat(createSubchannelArgs.getAddresses()).containsExactly(EAG2);
    assertThat(createSubchannelArgs.getAttributes()).isEqualTo(ATTRS2);

    pool.returnSubchannel(subchannel1, READY_STATE);

    // subchannel1 is 1ms away from expiration.
    clock.forwardTime(SHUTDOWN_TIMEOUT_MS - 1, MILLISECONDS);
    verify(subchannel1, never()).shutdown();

    pool.returnSubchannel(subchannel2, READY_STATE);

    // subchannel1 expires. subchannel2 is (SHUTDOWN_TIMEOUT_MS - 1) away from expiration.
    clock.forwardTime(1, MILLISECONDS);
    verify(subchannel1).shutdown();

    // subchanne2 expires.
    clock.forwardTime(SHUTDOWN_TIMEOUT_MS - 1, MILLISECONDS);
    verify(subchannel2).shutdown();

    assertThat(clock.numPendingTasks()).isEqualTo(0);
  }

  @Test
  public void subchannelReused() {
    Subchannel subchannel1 = pool.takeOrCreateSubchannel(EAG1, ATTRS1);
    assertThat(subchannel1).isNotNull();
    InOrder inOrder = Mockito.inOrder(helper);
    inOrder.verify(helper).createSubchannel(createSubchannelArgsCaptor.capture());
    CreateSubchannelArgs createSubchannelArgs = createSubchannelArgsCaptor.getValue();
    assertThat(createSubchannelArgs.getAddresses()).containsExactly(EAG1);
    assertThat(createSubchannelArgs.getAttributes()).isEqualTo(ATTRS1);

    Subchannel subchannel2 = pool.takeOrCreateSubchannel(EAG2, ATTRS2);
    assertThat(subchannel2).isNotNull();
    assertThat(subchannel2).isNotSameInstanceAs(subchannel1);
    inOrder.verify(helper).createSubchannel(createSubchannelArgsCaptor.capture());
    createSubchannelArgs = createSubchannelArgsCaptor.getValue();
    assertThat(createSubchannelArgs.getAddresses()).containsExactly(EAG2);
    assertThat(createSubchannelArgs.getAttributes()).isEqualTo(ATTRS2);

    pool.returnSubchannel(subchannel1, READY_STATE);

    // subchannel1 is 1ms away from expiration.
    clock.forwardTime(SHUTDOWN_TIMEOUT_MS - 1, MILLISECONDS);

    // This will cancel the shutdown timer for subchannel1
    Subchannel subchannel1a = pool.takeOrCreateSubchannel(EAG1, ATTRS1);
    assertThat(subchannel1a).isSameInstanceAs(subchannel1);

    pool.returnSubchannel(subchannel2, READY_STATE);

    // subchannel2 expires SHUTDOWN_TIMEOUT_MS after being returned
    clock.forwardTime(SHUTDOWN_TIMEOUT_MS - 1, MILLISECONDS);
    verify(subchannel2, never()).shutdown();
    clock.forwardTime(1, MILLISECONDS);
    verify(subchannel2).shutdown();

    // pool will create a new channel for EAG2 when requested
    Subchannel subchannel2a = pool.takeOrCreateSubchannel(EAG2, ATTRS2);
    assertThat(subchannel2a).isNotSameInstanceAs(subchannel2);
    inOrder.verify(helper).createSubchannel(createSubchannelArgsCaptor.capture());
    createSubchannelArgs = createSubchannelArgsCaptor.getValue();
    assertThat(createSubchannelArgs.getAddresses()).containsExactly(EAG2);
    assertThat(createSubchannelArgs.getAttributes()).isEqualTo(ATTRS2);

    // subchannel1 expires SHUTDOWN_TIMEOUT_MS after being returned
    pool.returnSubchannel(subchannel1a, READY_STATE);
    clock.forwardTime(SHUTDOWN_TIMEOUT_MS - 1, MILLISECONDS);
    verify(subchannel1a, never()).shutdown();
    clock.forwardTime(1, MILLISECONDS);
    verify(subchannel1a).shutdown();

    assertThat(clock.numPendingTasks()).isEqualTo(0);
  }

  @Test
  public void returnDuplicateAddressSubchannel() {
    Subchannel subchannel1 = pool.takeOrCreateSubchannel(EAG1, ATTRS1);
    Subchannel subchannel2 = pool.takeOrCreateSubchannel(EAG1, ATTRS2);
    Subchannel subchannel3 = pool.takeOrCreateSubchannel(EAG2, ATTRS1);
    assertThat(subchannel1).isNotSameInstanceAs(subchannel2);

    assertThat(clock.getPendingTasks(SHUTDOWN_TASK_FILTER)).isEmpty();
    pool.returnSubchannel(subchannel2, READY_STATE);
    assertThat(clock.getPendingTasks(SHUTDOWN_TASK_FILTER)).hasSize(1);

    // If the subchannel being returned has an address that is the same as a subchannel in the pool,
    // the returned subchannel will be shut down.
    verify(subchannel1, never()).shutdown();
    pool.returnSubchannel(subchannel1, READY_STATE);
    assertThat(clock.getPendingTasks(SHUTDOWN_TASK_FILTER)).hasSize(1);
    verify(subchannel1).shutdown();

    pool.returnSubchannel(subchannel3, READY_STATE);
    assertThat(clock.getPendingTasks(SHUTDOWN_TASK_FILTER)).hasSize(2);
    // Returning the same subchannel twice has no effect.
    pool.returnSubchannel(subchannel3, READY_STATE);
    assertThat(clock.getPendingTasks(SHUTDOWN_TASK_FILTER)).hasSize(2);

    verify(subchannel2, never()).shutdown();
    verify(subchannel3, never()).shutdown();
  }

  @Test
  public void clear() {
    Subchannel subchannel1 = pool.takeOrCreateSubchannel(EAG1, ATTRS1);
    Subchannel subchannel2 = pool.takeOrCreateSubchannel(EAG2, ATTRS2);
    Subchannel subchannel3 = pool.takeOrCreateSubchannel(EAG2, ATTRS2);

    pool.returnSubchannel(subchannel1, READY_STATE);
    pool.returnSubchannel(subchannel2, READY_STATE);

    verify(subchannel1, never()).shutdown();
    verify(subchannel2, never()).shutdown();
    pool.clear();
    verify(subchannel1).shutdown();
    verify(subchannel2).shutdown();

    verify(subchannel3, never()).shutdown();
    assertThat(clock.numPendingTasks()).isEqualTo(0);
  }
}
