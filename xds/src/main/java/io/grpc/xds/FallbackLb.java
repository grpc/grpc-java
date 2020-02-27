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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.util.ForwardingLoadBalancer;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.XdsLoadBalancerProvider.XdsConfig;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.List;
import java.util.Map;

/** Fallback load balancer. Handles fallback policy changes. */
final class FallbackLb extends ForwardingLoadBalancer {

  private final Helper fallbackLbHelper;
  private final LoadBalancerRegistry lbRegistry;
  private final GracefulSwitchLoadBalancer fallbackPolicyLb;

  FallbackLb(Helper fallbackLbHelper) {
    this(checkNotNull(fallbackLbHelper, "fallbackLbHelper"),
        LoadBalancerRegistry.getDefaultRegistry());
  }

  @VisibleForTesting
  FallbackLb(Helper fallbackLbHelper, LoadBalancerRegistry lbRegistry) {
    this.fallbackLbHelper = fallbackLbHelper;
    this.lbRegistry = lbRegistry;
    fallbackPolicyLb = new GracefulSwitchLoadBalancer(fallbackLbHelper);
  }

  @Override
  protected LoadBalancer delegate() {
    return fallbackPolicyLb;
  }

  @SuppressWarnings("deprecation")
  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    Attributes attributes = resolvedAddresses.getAttributes();
    XdsConfig xdsConfig;
    Object lbConfig = resolvedAddresses.getLoadBalancingPolicyConfig();
    if (lbConfig != null) {
      if (!(lbConfig instanceof XdsConfig)) {
        fallbackLbHelper.updateBalancingState(
            TRANSIENT_FAILURE,
            new ErrorPicker(Status.UNAVAILABLE.withDescription(
                "Load balancing config '" + lbConfig + "' is not an XdsConfig")));
        return;
      }
      xdsConfig = (XdsConfig) lbConfig;
    } else {
      // In the future, in all cases xdsConfig can be obtained directly by
      // resolvedAddresses.getLoadBalancingPolicyConfig().
      Map<String, ?> newRawLbConfig = attributes.get(ATTR_LOAD_BALANCING_CONFIG);
      if (newRawLbConfig == null) {
        // This will not happen when the service config error handling is implemented.
        // For now simply go to TRANSIENT_FAILURE.
        fallbackLbHelper.updateBalancingState(
            TRANSIENT_FAILURE,
            new ErrorPicker(
                Status.UNAVAILABLE.withDescription("ATTR_LOAD_BALANCING_CONFIG not available")));
        return;
      }
      ConfigOrError cfg =
          XdsLoadBalancerProvider.parseLoadBalancingConfigPolicy(newRawLbConfig, lbRegistry);
      if (cfg.getError() != null) {
        // This will not happen when the service config error handling is implemented.
        // For now simply go to TRANSIENT_FAILURE.
        fallbackLbHelper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(cfg.getError()));
        return;
      }
      xdsConfig = (XdsConfig) cfg.getConfig();
    }

    LbConfig fallbackPolicy = xdsConfig.fallbackPolicy;
    if (fallbackPolicy == null) {
      // In the latest xDS design, fallback is not supported.
      fallbackLbHelper.updateBalancingState(
          TRANSIENT_FAILURE,
          new ErrorPicker(Status.UNAVAILABLE.withDescription("Fallback is not supported")));
      return;
    }
    String newFallbackPolicyName = fallbackPolicy.getPolicyName();
    fallbackPolicyLb.switchTo(lbRegistry.getProvider(newFallbackPolicyName));

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
          .setAttributes(attributes.toBuilder()
              .set(ATTR_LOAD_BALANCING_CONFIG, fallbackPolicy.getRawConfigValue()).build())
          .build();
      fallbackPolicyLb.handleResolvedAddresses(fallbackResolvedAddresses);
    }
  }
}
