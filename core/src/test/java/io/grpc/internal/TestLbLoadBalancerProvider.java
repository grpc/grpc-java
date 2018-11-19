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

package io.grpc.internal;

import io.grpc.Attributes;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

public final class TestLbLoadBalancerProvider extends LoadBalancerProvider {
  public static final String POLICY_NAME = "test_lb";

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public int getPriority() {
    return 5;
  }

  @Override
  public String getPolicyName() {
    return POLICY_NAME;
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return new TestLbLoadBalancer(helper);
  }

  public static final class TestLbLoadBalancer extends LoadBalancer {
    public final Helper helper;
    public final Queue<ResolvedAddressGroups> resolvedAddressGroupsList =
        new ArrayDeque<ResolvedAddressGroups>();
    private boolean isShutdown;

    private TestLbLoadBalancer(Helper helper) {
      this.helper = helper;
    }

    @Override
    public void handleResolvedAddressGroups(
        List<EquivalentAddressGroup> servers, Attributes attributes) {
      resolvedAddressGroupsList.add(new ResolvedAddressGroups(servers, attributes));
    }

    @Override
    public void handleNameResolutionError(Status error) {
    }

    @Override
    public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
    }

    @Override
    public void shutdown() {
      isShutdown = true;
    }

    public boolean isShutdown() {
      return isShutdown;
    }
  }

  public static final class ResolvedAddressGroups {
    public final List<EquivalentAddressGroup> servers;
    public final Attributes attributes;

    private ResolvedAddressGroups(List<EquivalentAddressGroup> servers, Attributes attributes) {
      this.servers = servers;
      this.attributes = attributes;
    }
  }
}
