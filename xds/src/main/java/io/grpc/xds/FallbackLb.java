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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.util.ForwardingLoadBalancer;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.XdsLoadBalancerProvider.XdsConfig;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.List;

/** Fallback load balancer. Handles fallback policy changes. */
final class FallbackLb extends ForwardingLoadBalancer {

  private final Helper fallbackLbHelper;
  private final GracefulSwitchLoadBalancer fallbackPolicyLb;

  FallbackLb(Helper fallbackLbHelper) {
    this.fallbackLbHelper = checkNotNull(fallbackLbHelper, "fallbackLbHelper");
    fallbackPolicyLb = new GracefulSwitchLoadBalancer(fallbackLbHelper);
  }

  @Override
  protected LoadBalancer delegate() {
    return fallbackPolicyLb;
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    XdsConfig xdsConfig = (XdsConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    PolicySelection fallbackPolicy = xdsConfig.fallbackPolicy;
    if (fallbackPolicy == null) {
      // In the latest xDS design, fallback is not supported.
      fallbackLbHelper.updateBalancingState(
          TRANSIENT_FAILURE,
          new ErrorPicker(Status.UNAVAILABLE.withDescription("Fallback is not supported")));
      return;
    }
    fallbackPolicyLb.switchTo(fallbackPolicy.getProvider());

    List<EquivalentAddressGroup> servers = resolvedAddresses.getAddresses();
    // TODO(zhangkun83): FIXME(#5496): this is a temporary hack.
    if (servers.isEmpty()
        && !fallbackPolicyLb.canHandleEmptyAddressListFromNameResolution()) {
      fallbackPolicyLb.handleNameResolutionError(Status.UNAVAILABLE.withDescription(
          "NameResolver returned no usable address."
              + " addrs=" + resolvedAddresses));
    } else {
      // TODO(carl-mastrangelo): propagate the load balancing config policy
      ResolvedAddresses fallbackResolvedAddresses = resolvedAddresses.toBuilder()
          .setAddresses(servers)
          .setLoadBalancingPolicyConfig(fallbackPolicy.getConfig())
          .build();
      fallbackPolicyLb.handleResolvedAddresses(fallbackResolvedAddresses);
    }
  }
}
