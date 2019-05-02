/*
 * Copyright 2019 The gRPC Authors
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

import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.util.ForwardingLoadBalancerHelper;

/**
 * A loadbalancer {@link Helper} that uses a {@linke SubchannelPool} to manage the lifecycle of
 * subchannels.
 */
public final class SubchannelPoolHelper extends ForwardingLoadBalancerHelper {

  private final Helper delegate;
  private final SubchannelPool subchannelPool;

  public SubchannelPoolHelper(Helper delegate) {
    this(delegate, new CachedSubchannelPool());
  }

  /**
   * TODO: make it package private. Right now it has to be public for grpclb testing.
   */
  public SubchannelPoolHelper(Helper delegate, SubchannelPool subchannelPool) {
    this.delegate = delegate;
    this.subchannelPool = subchannelPool;
    subchannelPool.init(delegate);
  }


  @Override
  protected Helper delegate() {
    return delegate;
  }

  @Override
  public Subchannel createSubchannel(CreateSubchannelArgs args) {
    return subchannelPool.takeOrCreateSubchannel(args);
  }

  @Override
  public void shutdown() {
    subchannelPool.clear();
  }
}
