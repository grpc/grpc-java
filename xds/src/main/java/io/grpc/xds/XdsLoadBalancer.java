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
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.internal.ServiceConfigUtil;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A {@link LoadBalancer} that uses the XDS protocol.
 */
final class XdsLoadBalancer extends LoadBalancer {

  final Helper helper;

  @Nullable
  Map<String, Object> lbConfig;

  @Nullable
  private XdsLbState xdsLbState;

  XdsLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
  }

  @Override
  public void handleResolvedAddressGroups(
      List<EquivalentAddressGroup> servers, Attributes attributes) {
    Map<String, Object> newLbConfig = checkNotNull(
        attributes.get(ATTR_LOAD_BALANCING_CONFIG), "ATTR_LOAD_BALANCING_CONFIG not available");
    if (!newLbConfig.equals(lbConfig)) {
      handleNewConfig(newLbConfig);
    }
    xdsLbState.handleResolvedAddressGroups(servers, attributes);
  }

  private void handleNewConfig(Map<String, Object> newLbConfig) {
    String newBalancerName = ServiceConfigUtil.getBalancerNameFromXdsConfig(newLbConfig);
    Map<String, Object> fallbackPolicy = selectFallbackPolicy(newLbConfig);
    if (lbConfig == null) {
      updateXdsLbState(
          helper, newBalancerName, selectChildPolicy(newLbConfig), fallbackPolicy, null, null);
    } else if (!newBalancerName.equals(
        ServiceConfigUtil.getBalancerNameFromXdsConfig(lbConfig))) {
      xdsLbState.shutdown();
      xdsLbState.shutdownLbComm();
      updateXdsLbState(
          helper, newBalancerName, selectChildPolicy(newLbConfig), fallbackPolicy, null, null);
    } else if (!Objects.equals(
        ServiceConfigUtil.getChildPolicyFromXdsConfig(newLbConfig),
        ServiceConfigUtil.getChildPolicyFromXdsConfig(lbConfig))) {
      XdsLbState.Mode currentMode = xdsLbState.mode();
      Map<String, Object> childPolicy = selectChildPolicy(newLbConfig);
      Status cancel = Status.CANCELLED.withDescription("Changing loadbalancing mode");
      switch (currentMode) {
        case STANDARD:
          if (childPolicy != null) {
            // GOTO CUSTOM mode, close the stream but reuse the channel
            xdsLbState.shutdown();
            xdsLbState.shutdownLbRpc(cancel);
            updateXdsLbState(
                helper, newBalancerName, childPolicy, fallbackPolicy, xdsLbState.lbCommChannel(),
                null);
          } // else, still STANDARD mode
          break;
        case CUSTOM:
          if (childPolicy != null) {
            // GOTO a new CUSTOM mode, close the stream but reuse the channel
            xdsLbState.shutdown();
            xdsLbState.shutdownLbRpc(cancel);
            updateXdsLbState(
                helper, newBalancerName, childPolicy, fallbackPolicy, xdsLbState.lbCommChannel(),
                null);
          } else {
            // GOTO STANDARD mode, reuse the stream
            updateXdsLbState(
                helper, newBalancerName, null, fallbackPolicy, xdsLbState.lbCommChannel(),
                xdsLbState.adsStream());
          }
          break;
        default:
          throw new AssertionError("Unsupported xds plugin mode: " + currentMode);
      }
    }
    lbConfig = newLbConfig;
  }

  private void updateXdsLbState(
      Helper helper,
      String balancerName,
      @Nullable final Map<String, Object> childPolicy,
      @Nullable Map<String, Object> fallbackPolicy,
      @Nullable final ManagedChannel channel,
      @Nullable final AdsStream adsStream) {

    // TODO: impl
    xdsLbState = new XdsLbState() {
      @Override
      void handleResolvedAddressGroups(
          List<EquivalentAddressGroup> servers, Attributes attributes) {}

      @Override
      void propagateError(Status error) {}

      @Override
      void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {}

      @Override
      void shutdown() {}

      @Nullable
      @Override
      ManagedChannel lbCommChannel() {
        return channel;
      }

      @Nullable
      @Override
      AdsStream adsStream() {
        return adsStream;
      }

      @Override
      Mode mode() {
        return childPolicy == null ? Mode.STANDARD : Mode.CUSTOM;
      }
    };
  }

  @Nullable
  @VisibleForTesting
  static Map<String, Object> selectChildPolicy(Map<String, Object> lbConfig) {
    List<Map<String, Object>> childConfigs =
        ServiceConfigUtil.getChildPolicyFromXdsConfig(lbConfig);
    return selectSupportedLbPolicy(childConfigs);
  }

  @Nullable
  @VisibleForTesting
  static Map<String, Object> selectFallbackPolicy(Map<String, Object> lbConfig) {
    if (lbConfig == null) {
      return null;
    }
    List<Map<String, Object>> fallbackConfigs =
        ServiceConfigUtil.getFallbackPolicyFromXdsConfig(lbConfig);
    return selectSupportedLbPolicy(fallbackConfigs);
  }

  private static Map<String, Object> selectSupportedLbPolicy(List<Map<String, Object>> lbConfigs) {
    if (lbConfigs == null) {
      return null;
    }
    LoadBalancerRegistry loadBalancerRegistry = LoadBalancerRegistry.getDefaultRegistry();
    for (Object lbConfig : lbConfigs) {
      @SuppressWarnings("unchecked")
      Map<String, Object> candidate = (Map<String, Object>) lbConfig;
      String lbPolicy = candidate.entrySet().iterator().next().getKey();
      if (loadBalancerRegistry.getProvider(lbPolicy) != null) {
        return candidate;
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
      xdsLbState.shutdown();
      xdsLbState.shutdownLbComm();
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
