/*
 * Copyright 2022 The gRPC Authors
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
import static io.grpc.xds.XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME;

import com.google.common.base.MoreObjects;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedPolicySelection;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedTargetConfig;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * This load balancer acts as a parent for the {@link WeightedTargetLoadBalancer} and configures
 * it with a child policy in its configuration and locality weights it gets from an attribute in
 * {@link io.grpc.LoadBalancer.ResolvedAddresses}.
 */
final class WrrLocalityLoadBalancer extends LoadBalancer {

  private final XdsLogger logger;
  private final Helper helper;
  private final GracefulSwitchLoadBalancer switchLb;
  private final LoadBalancerRegistry lbRegistry;

  WrrLocalityLoadBalancer(Helper helper) {
    this(helper, LoadBalancerRegistry.getDefaultRegistry());
  }

  WrrLocalityLoadBalancer(Helper helper, LoadBalancerRegistry lbRegistry) {
    this.helper = checkNotNull(helper, "helper");
    this.lbRegistry = lbRegistry;
    switchLb = new GracefulSwitchLoadBalancer(helper);
    logger = XdsLogger.withLogId(
        InternalLogId.allocate("xds-wrr-locality-lb", helper.getAuthority()));
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public boolean acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);

    // The configuration with the child policy is combined with the locality weights
    // to produce the weighted target LB config.
    WrrLocalityConfig wrrLocalityConfig
        = (WrrLocalityConfig) resolvedAddresses.getLoadBalancingPolicyConfig();

    // A map of locality weights is built up from the locality weight attributes in each address.
    Map<Locality, Integer> localityWeights = new HashMap<>();
    for (EquivalentAddressGroup eag : resolvedAddresses.getAddresses()) {
      Attributes eagAttrs = eag.getAttributes();
      Locality locality = eagAttrs.get(InternalXdsAttributes.ATTR_LOCALITY);
      Integer localityWeight = eagAttrs.get(InternalXdsAttributes.ATTR_LOCALITY_WEIGHT);

      if (locality == null) {
        helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(
            Status.UNAVAILABLE.withDescription("wrr_locality error: no locality provided")));
        return false;
      }
      if (localityWeight == null) {
        helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(
            Status.UNAVAILABLE.withDescription(
                "wrr_locality error: no weight provided for locality " + locality)));
        return false;
      }

      if (!localityWeights.containsKey(locality)) {
        localityWeights.put(locality, localityWeight);
      } else if (!localityWeights.get(locality).equals(localityWeight)) {
        logger.log(XdsLogLevel.WARNING,
            "Locality {0} has both weights {1} and {2}, using weight {1}", locality,
            localityWeights.get(locality), localityWeight);
      }
    }

    // Weighted target LB expects a WeightedPolicySelection for each locality as it will create a
    // child LB for each.
    Map<String, WeightedPolicySelection> weightedPolicySelections = new HashMap<>();
    for (Locality locality : localityWeights.keySet()) {
      weightedPolicySelections.put(locality.toString(),
          new WeightedPolicySelection(localityWeights.get(locality),
              wrrLocalityConfig.childPolicy));
    }

    switchLb.switchTo(lbRegistry.getProvider(WEIGHTED_TARGET_POLICY_NAME));
    switchLb.handleResolvedAddresses(
        resolvedAddresses.toBuilder()
            .setLoadBalancingPolicyConfig(new WeightedTargetConfig(weightedPolicySelections))
            .build());

    return true;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
    switchLb.handleNameResolutionError(error);
  }

  @Override
  public void shutdown() {
    switchLb.shutdown();
  }

  /**
   * The LB config for {@link WrrLocalityLoadBalancer}.
   */
  static final class WrrLocalityConfig {

    final PolicySelection childPolicy;

    WrrLocalityConfig(PolicySelection childPolicy) {
      this.childPolicy = childPolicy;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      WrrLocalityConfig that = (WrrLocalityConfig) o;
      return Objects.equals(childPolicy, that.childPolicy);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(childPolicy);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("childPolicy", childPolicy).toString();
    }
  }
}
