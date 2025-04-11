/*
 * Copyright 2024 The gRPC Authors
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

import com.google.common.base.Preconditions;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.util.ForwardingLoadBalancer;

/**
 * A load balancer that starts in IDLE instead of CONNECTING. Once it starts connecting, it
 * instantiates its delegate.
 */
final class LazyLoadBalancer extends ForwardingLoadBalancer {
  private LoadBalancer delegate;

  public LazyLoadBalancer(Helper helper, LoadBalancer.Factory delegateFactory) {
    this.delegate = new LazyDelegate(helper, delegateFactory);
  }

  @Override
  protected LoadBalancer delegate() {
    return delegate;
  }

  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    return delegate.acceptResolvedAddresses(resolvedAddresses);
  }

  private final class LazyDelegate extends LoadBalancer {
    private final Helper helper;
    private final LoadBalancer.Factory delegateFactory;
    private ResolvedAddresses addresses;
    private Status error;
    private boolean updatedBalancingState;

    public LazyDelegate(Helper helper, LoadBalancer.Factory delegateFactory) {
      this.helper = Preconditions.checkNotNull(helper, "helper");
      this.delegateFactory = Preconditions.checkNotNull(delegateFactory, "delegateFactory");
    }

    private LoadBalancer activate() {
      if (delegate != this) {
        return delegate;
      }
      delegate = delegateFactory.newLoadBalancer(helper);
      if (addresses != null) {
        delegate.acceptResolvedAddresses(addresses);
      }
      if (error != null) {
        delegate.handleNameResolutionError(error);
      }
      return delegate;
    }

    @Override
    public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      this.addresses = resolvedAddresses;
      this.error = null;
      initializeBalancingState();
      return Status.OK;
    }

    @Override
    public void handleNameResolutionError(Status error) {
      // Preserve addresses, because even old addresses may be used by the real policy
      this.error = error;
      initializeBalancingState();
    }

    private void initializeBalancingState() {
      if (updatedBalancingState) {
        return;
      }
      helper.updateBalancingState(ConnectivityState.IDLE, new LazyPicker());
      updatedBalancingState = true;
    }

    @Override
    public void requestConnection() {
      activate().requestConnection();
    }

    @Override
    public void shutdown() {
    }

    private final class LazyPicker extends SubchannelPicker {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        helper.getSynchronizationContext().execute(LazyDelegate.this::activate);
        return PickResult.withNoResult();
      }
    }
  }

  public static final class Factory extends LoadBalancer.Factory {
    private final LoadBalancer.Factory delegate;

    public Factory(LoadBalancer.Factory delegate) {
      this.delegate = Preconditions.checkNotNull(delegate, "delegate");
    }

    @Override public LoadBalancer newLoadBalancer(Helper helper) {
      return new LazyLoadBalancer(helper, delegate);
    }
  }
}
