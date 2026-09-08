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

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public class LazyChildLoadBalancerTest {

  private final Helper mockHelper = mock(Helper.class);
  private final LoadBalancerProvider mockProvider = mock(LoadBalancerProvider.class);
  private final LoadBalancer mockDelegate = mock(LoadBalancer.class);
  private final SynchronizationContext syncContext =
      new SynchronizationContext((t, e) -> {
        throw new AssertionError("Unhandled exception in syncContext", e);
      });

  private LazyChildLoadBalancer lazyLb;
  private ResolvedAddresses resolvedAddresses;

  @Before
  public void setUp() {
    when(mockHelper.getSynchronizationContext()).thenReturn(syncContext);
    when(mockProvider.newLoadBalancer(any())).thenReturn(mockDelegate);
    when(mockDelegate.acceptResolvedAddresses(any())).thenReturn(Status.OK);

    lazyLb = new LazyChildLoadBalancer(mockHelper, mockProvider);
    resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(Collections.singletonList(
            new EquivalentAddressGroup(new SocketAddress() {})))
        .setAttributes(Attributes.EMPTY)
        .build();
  }

  @Test
  public void constructor_nullArguments_throwsNullPointerException() {
    assertThrows(
        NullPointerException.class,
        () -> new LazyChildLoadBalancer(null, mockProvider));
    assertThrows(
        NullPointerException.class,
        () -> new LazyChildLoadBalancer(mockHelper, null));
  }

  @Test
  public void initialResolution_reportsIdle_doesNotCreateChildPolicy() {
    Status status = lazyLb.acceptResolvedAddresses(resolvedAddresses);
    assertThat(status.isOk()).isTrue();

    ArgumentCaptor<SubchannelPicker> pickerCaptor =
        ArgumentCaptor.forClass(SubchannelPicker.class);
    verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class)).hasResult())
        .isFalse();

    verify(mockProvider, never()).newLoadBalancer(any());
    assertThat(lazyLb.getDelegate()).isNull();
    assertThat(lazyLb.isConnectionRequested()).isFalse();
  }

  @Test
  public void requestConnection_createsChildPolicy_forwardsAddresses_andRequestsConnection() {
    lazyLb.acceptResolvedAddresses(resolvedAddresses);
    assertThat(lazyLb.getDelegate()).isNull();

    lazyLb.requestConnection();

    assertThat(lazyLb.isConnectionRequested()).isTrue();
    verify(mockProvider).newLoadBalancer(mockHelper);
    verify(mockDelegate).acceptResolvedAddresses(resolvedAddresses);
    verify(mockDelegate).requestConnection();
    assertThat(lazyLb.getDelegate()).isSameInstanceAs(mockDelegate);
  }

  @Test
  public void requestConnection_beforeResolvedAddresses_createsPolicyWhenAddressesArrive() {
    lazyLb.requestConnection();
    assertThat(lazyLb.isConnectionRequested()).isTrue();
    verify(mockProvider, never()).newLoadBalancer(any());

    lazyLb.acceptResolvedAddresses(resolvedAddresses);
    verify(mockProvider).newLoadBalancer(mockHelper);
    verify(mockDelegate).acceptResolvedAddresses(resolvedAddresses);
    verify(mockDelegate).requestConnection();
  }

  @Test
  public void requestConnection_whenAlreadyCreated_delegatesRequestConnection() {
    lazyLb.acceptResolvedAddresses(resolvedAddresses);
    lazyLb.requestConnection();
    verify(mockDelegate, times(1)).requestConnection();

    lazyLb.requestConnection();
    verify(mockDelegate, times(2)).requestConnection();
  }

  @Test
  public void acceptResolvedAddresses_afterConnectionRequested_forwardsDirectly() {
    lazyLb.acceptResolvedAddresses(resolvedAddresses);
    lazyLb.requestConnection();
    verify(mockDelegate, times(1)).requestConnection();

    ResolvedAddresses newAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(Collections.singletonList(
            new EquivalentAddressGroup(new SocketAddress() {})))
        .setAttributes(Attributes.EMPTY)
        .build();

    lazyLb.acceptResolvedAddresses(newAddresses);
    verify(mockDelegate).acceptResolvedAddresses(newAddresses);
    // Should not request connection again on subsequent address update
    verify(mockDelegate, times(1)).requestConnection();
  }

  @Test
  public void acceptResolvedAddresses_null_throwsNullPointerException() {
    assertThrows(
        NullPointerException.class,
        () -> lazyLb.acceptResolvedAddresses(null));
  }

  @Test
  public void exitIdle_schedulesRequestConnectionOnSyncContext_once() throws Exception {
    lazyLb.acceptResolvedAddresses(resolvedAddresses);

    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);

    for (int i = 0; i < threadCount; i++) {
      executor.execute(() -> {
        try {
          startLatch.await();
          lazyLb.exitIdle();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          doneLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    executor.shutdown();

    // Verify child policy instantiated exactly once despite concurrent exitIdle calls
    verify(mockProvider, times(1)).newLoadBalancer(mockHelper);
    verify(mockDelegate, times(1)).acceptResolvedAddresses(resolvedAddresses);
    verify(mockDelegate, atLeastOnce()).requestConnection();
  }

  @Test
  public void exitIdle_resetsFlagOnSyncContext_allowsSubsequentExitIdle() {
    lazyLb.acceptResolvedAddresses(resolvedAddresses);

    lazyLb.exitIdle();
    verify(mockDelegate, times(1)).requestConnection();

    // Subsequent exitIdle after syncContext execution should request connection again
    lazyLb.exitIdle();
    verify(mockDelegate, times(2)).requestConnection();
  }

  @Test
  public void handleNameResolutionError_null_throwsNullPointerException() {
    assertThrows(
        NullPointerException.class,
        () -> lazyLb.handleNameResolutionError(null));
  }

  @Test
  public void handleNameResolutionError_beforeConnectionRequested_reportsTransientFailure() {
    Status error = Status.UNAVAILABLE.withDescription("dns failed");
    lazyLb.handleNameResolutionError(error);

    ArgumentCaptor<SubchannelPicker> pickerCaptor =
        ArgumentCaptor.forClass(SubchannelPicker.class);
    verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class)).getStatus())
        .isEqualTo(error);
  }

  @Test
  public void handleNameResolutionError_afterConnectionRequested_forwardsToDelegate() {
    lazyLb.acceptResolvedAddresses(resolvedAddresses);
    lazyLb.requestConnection();

    Status error = Status.UNAVAILABLE.withDescription("dns failed");
    lazyLb.handleNameResolutionError(error);
    verify(mockDelegate).handleNameResolutionError(error);
  }

  @Test
  public void shutdown_cleansUpDelegateAndAddresses() {
    lazyLb.acceptResolvedAddresses(resolvedAddresses);
    assertThat(lazyLb.getLastResolvedAddresses()).isNotNull();
    lazyLb.requestConnection();
    assertThat(lazyLb.getDelegate()).isNotNull();

    lazyLb.shutdown();
    verify(mockDelegate).shutdown();
    assertThat(lazyLb.getDelegate()).isNull();
    assertThat(lazyLb.getLastResolvedAddresses()).isNull();
  }

  @Test
  public void operationsAfterShutdown_areNoOpsOrReturnError() {
    lazyLb.acceptResolvedAddresses(resolvedAddresses);
    lazyLb.shutdown();

    Status status = lazyLb.acceptResolvedAddresses(resolvedAddresses);
    assertThat(status.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);

    // None of these should throw or create a child load balancer
    lazyLb.requestConnection();
    lazyLb.exitIdle();
    lazyLb.handleNameResolutionError(Status.UNAVAILABLE);
    verify(mockProvider, never()).newLoadBalancer(any());
  }

  @Test
  public void toString_containsDebugFields() {
    String str = lazyLb.toString();
    assertThat(str).contains("connectionRequested=false");
    assertThat(str).contains("shutdown=false");
  }
}
