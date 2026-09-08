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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A wrapper {@link LoadBalancer} that lazily creates and delegates to a child LoadBalancer
 * (typically {@code pick_first}) only when a connection attempt is explicitly requested.
 *
 * <p>Until a connection is requested, this balancer remains in the {@link ConnectivityState#IDLE}
 * state upon receiving resolved addresses without eagerly connecting. It implements
 * {@link PickerEndpoint.ExitIdler} to allow worker threads in {@link AutoShardingPicker} to
 * trigger connection attempts safely and non-blockingly via
 * {@link io.grpc.SynchronizationContext}.
 *
 * <p>Threading model: {@link #exitIdle()} is thread-safe and may be called concurrently by
 * application/worker threads during picker execution. All other {@link LoadBalancer} lifecycle
 * methods (such as {@link #acceptResolvedAddresses}, {@link #requestConnection},
 * {@link #handleNameResolutionError}, and {@link #shutdown}) must be invoked from the
 * {@link io.grpc.SynchronizationContext}.
 */
@ThreadSafe
final class LazyChildLoadBalancer extends LoadBalancer implements PickerEndpoint.ExitIdler {
  private final Helper helper;
  private final LoadBalancerProvider delegateProvider;
  private final AtomicBoolean connectingScheduled = new AtomicBoolean(false);

  @Nullable private LoadBalancer delegate;
  @Nullable private ResolvedAddresses lastResolvedAddresses;
  private boolean connectionRequested = false;
  private volatile boolean shutdown = false;

  /**
   * Constructs a {@link LazyChildLoadBalancer}.
   *
   * @param helper the parent load balancer helper
   * @param delegateProvider provider used to instantiate the child load balancer (e.g. pick_first)
   */
  LazyChildLoadBalancer(Helper helper, LoadBalancerProvider delegateProvider) {
    this.helper = checkNotNull(helper, "helper");
    this.delegateProvider = checkNotNull(delegateProvider, "delegateProvider");
  }

  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    checkNotNull(resolvedAddresses, "resolvedAddresses");
    if (shutdown) {
      return Status.FAILED_PRECONDITION.withDescription("LoadBalancer is shutdown");
    }
    lastResolvedAddresses = resolvedAddresses;
    if (connectionRequested) {
      boolean newlyCreated = false;
      if (delegate == null) {
        delegate = delegateProvider.newLoadBalancer(helper);
        newlyCreated = true;
      }
      Status status = delegate.acceptResolvedAddresses(resolvedAddresses);
      if (newlyCreated && status.isOk()) {
        delegate.requestConnection();
      }
      return status;
    } else {
      // Report IDLE state until connection is explicitly requested
      helper.updateBalancingState(
          ConnectivityState.IDLE,
          new FixedResultPicker(PickResult.withNoResult()));
      return Status.OK;
    }
  }

  @Override
  public void handleNameResolutionError(Status error) {
    checkNotNull(error, "error");
    if (shutdown) {
      return;
    }
    if (delegate != null) {
      delegate.handleNameResolutionError(error);
    } else {
      helper.updateBalancingState(
          ConnectivityState.TRANSIENT_FAILURE,
          new FixedResultPicker(PickResult.withError(error)));
    }
  }

  @Override
  public void requestConnection() {
    if (shutdown) {
      return;
    }
    connectionRequested = true;
    if (delegate == null && lastResolvedAddresses != null) {
      delegate = delegateProvider.newLoadBalancer(helper);
      delegate.acceptResolvedAddresses(lastResolvedAddresses);
    }
    if (delegate != null) {
      delegate.requestConnection();
    }
  }

  /**
   * Callback invoked from worker threads during {@link AutoShardingPicker#pickSubchannel}.
   *
   * <p>Protects against thundering herds by using an {@link AtomicBoolean} guard to schedule
   * {@link #requestConnection()} onto the {@link io.grpc.SynchronizationContext} at most once.
   */
  @Override
  public void exitIdle() {
    if (shutdown) {
      return;
    }
    if (connectingScheduled.compareAndSet(false, true)) {
      helper.getSynchronizationContext().execute(this::requestConnection);
    }
  }

  @Override
  public void shutdown() {
    shutdown = true;
    if (delegate != null) {
      delegate.shutdown();
      delegate = null;
    }
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("connectionRequested", connectionRequested)
        .add("shutdown", shutdown)
        .add("delegate", delegate)
        .toString();
  }

  @VisibleForTesting
  boolean isConnectionRequested() {
    return connectionRequested;
  }

  @VisibleForTesting
  @Nullable
  LoadBalancer getDelegate() {
    return delegate;
  }
}
