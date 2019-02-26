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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Attributes;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.internal.ServiceConfigUtil.MalformedConfigException;
import io.grpc.xds.XdsLbState.XdsComms;
import java.util.List;
import java.util.Objects;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;

/**
 * A {@link LoadBalancer} that uses the XDS protocol.
 */
final class XdsLoadBalancer extends LoadBalancer {

  final Helper helper;

  @Nullable
  private XdsLbState xdsLbState;

  XdsLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
  }

  @Override
  public void handleResolvedAddressGroups(
      List<EquivalentAddressGroup> servers, Attributes attributes) {
    Object newLbConfig = checkNotNull(
        attributes.get(ATTR_LOAD_BALANCING_CONFIG), "ATTR_LOAD_BALANCING_CONFIG not available");
    try {
      handleNewConfig(ServiceConfigUtil.unwrapLoadBalancingConfig(newLbConfig));
    } catch (MalformedConfigException e) {
      // TODO: handle it
    }
    xdsLbState.handleResolvedAddressGroups(servers, attributes);
  }

  private void handleNewConfig(LbConfig newLbConfig) throws MalformedConfigException {
    String newBalancerName = ServiceConfigUtil.getBalancerNameFromXdsConfig(newLbConfig);
    LbConfig childPolicy = selectChildPolicy(newLbConfig);
    LbConfig fallbackPolicy = selectFallbackPolicy(newLbConfig);
    XdsComms xdsComms = null;
    if (xdsLbState != null) { // may release and re-use/shutdown xdsComms from current xdsLbState
      if (!newBalancerName.equals(xdsLbState.balancerName)) {
        xdsComms = xdsLbState.shutdownAndReleaseXdsComms();
        if (xdsComms != null) {
          xdsComms.shutdownChannel();
          xdsComms = null;
        }
      } else if (!Objects.equals(childPolicy, xdsLbState.childPolicy)
          // There might be optimization when only fallbackPolicy is changed.
          || !Objects.equals(fallbackPolicy, xdsLbState.fallbackPolicy)) {
        String cancelMessage = "Changing loadbalancing mode";
        xdsComms = xdsLbState.shutdownAndReleaseXdsComms();
        // close the stream but reuse the channel
        if (xdsComms != null) {
          xdsComms.shutdownLbRpc(cancelMessage);
        }
      } else { // effectively no change in policy, keep xdsLbState unchanged
        return;
      }
    }
    xdsLbState = newXdsLbState(
        newBalancerName, childPolicy, fallbackPolicy, xdsComms);
  }

  @CheckReturnValue
  private XdsLbState newXdsLbState(
      String balancerName,
      @Nullable final LbConfig childPolicy,
      @Nullable LbConfig fallbackPolicy,
      @Nullable final XdsComms xdsComms) {

    // TODO: impl
    return new XdsLbState(balancerName, childPolicy, fallbackPolicy, xdsComms) {
      @Override
      void handleResolvedAddressGroups(
          List<EquivalentAddressGroup> servers, Attributes attributes) {}

      @Override
      void propagateError(Status error) {}

      @Override
      void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {}

      @Override
      void shutdown() {}
    };
  }

  @Nullable
  @VisibleForTesting
  static LbConfig selectChildPolicy(LbConfig lbConfig) throws MalformedConfigException {
    List<LbConfig> childConfigs =
        ServiceConfigUtil.getChildPolicyFromXdsConfig(lbConfig);
    return selectSupportedLbPolicy(childConfigs);
  }

  @Nullable
  @VisibleForTesting
  static LbConfig selectFallbackPolicy(@Nullable LbConfig lbConfig)
      throws MalformedConfigException {
    if (lbConfig == null) {
      return null;
    }
    List<LbConfig> fallbackConfigs =
        ServiceConfigUtil.getFallbackPolicyFromXdsConfig(lbConfig);
    return selectSupportedLbPolicy(fallbackConfigs);
  }

  @Nullable
  private static LbConfig selectSupportedLbPolicy(@Nullable List<LbConfig> lbConfigs)
      throws MalformedConfigException {
    if (lbConfigs == null) {
      return null;
    }
    LoadBalancerRegistry loadBalancerRegistry = LoadBalancerRegistry.getDefaultRegistry();
    for (LbConfig lbConfig : lbConfigs) {
      if (loadBalancerRegistry.getProvider(lbConfig.getPolicyName()) != null) {
        return lbConfig;
      }
    }
    return null;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    if (xdsLbState != null) {
      xdsLbState.propagateError(error);
    }
    // TODO: impl
    // else {
    //   helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, new FailingPicker(error));
    // }
  }

  @Override
  public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
    // xdsLbState should never be null here since handleSubchannelState cannot be called while the
    // lb is shutdown.
    xdsLbState.handleSubchannelState(subchannel, newState);
  }

  @Override
  public void shutdown() {
    if (xdsLbState != null) {
      XdsComms xdsComms = xdsLbState.shutdownAndReleaseXdsComms();
      if (xdsComms != null) {
        xdsComms.shutdownChannel();
      }
      xdsLbState = null;
    }
  }

  @Override
  public boolean canHandleEmptyAddressListFromNameResolution() {
    return true;
  }

  @VisibleForTesting
  @Nullable
  XdsLbState getXdsLbState() {
    return xdsLbState;
  }
}
