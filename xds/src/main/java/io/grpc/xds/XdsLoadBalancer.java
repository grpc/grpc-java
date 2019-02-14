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
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.internal.ServiceConfigUtil.getBalancerPolicyNameFromLoadBalancingConfig;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.grpc.Attributes;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.xds.XdsLbState.SubchannelStore;
import io.grpc.xds.XdsLbState.XdsComms;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * A {@link LoadBalancer} that uses the XDS protocol.
 */
final class XdsLoadBalancer extends LoadBalancer {

  private static final Attributes.Key<AtomicReference<ConnectivityStateInfo>> STATE_INFO =
      Attributes.Key.create("io.grpc.xds.XdsLoadBalancer.stateInfo");

  private static final ImmutableMap<String, Object> DEFAULT_FALLBACK_POLICY =
      ImmutableMap.of("round_robin", (Object) ImmutableMap.<String, Object>of());

  private final SubchannelStore subchannelStore = new SubchannelStore();
  private final Helper helper;
  private final LoadBalancerRegistry lbRegistry;
  private final FallbackManager fallbackManager;

  @Nullable
  private XdsLbState xdsLbState;

  private Map<String, Object> fallbackPolicy;

  XdsLoadBalancer(Helper helper, LoadBalancerRegistry lbRegistry) {
    this.helper = checkNotNull(helper, "helper");
    this.lbRegistry = lbRegistry;
    fallbackManager = new FallbackManager(helper, subchannelStore, lbRegistry);
  }

  @Override
  public void handleResolvedAddressGroups(
      List<EquivalentAddressGroup> servers, Attributes attributes) {
    Map<String, Object> newLbConfig = checkNotNull(
        attributes.get(ATTR_LOAD_BALANCING_CONFIG), "ATTR_LOAD_BALANCING_CONFIG not available");
    fallbackPolicy = selectFallbackPolicy(newLbConfig, lbRegistry);
    fallbackManager.updateFallbackServers(servers, attributes, fallbackPolicy);
    fallbackManager.maybeStartFallbackTimer();
    handleNewConfig(newLbConfig);
    xdsLbState.handleResolvedAddressGroups(servers, attributes);
  }

  private void handleNewConfig(Map<String, Object> newLbConfig) {
    String newBalancerName = ServiceConfigUtil.getBalancerNameFromXdsConfig(newLbConfig);
    Map<String, Object> childPolicy = selectChildPolicy(newLbConfig, lbRegistry);
    XdsComms xdsComms = null;
    if (xdsLbState != null) { // may release and re-use/shutdown xdsComms from current xdsLbState
      if (!newBalancerName.equals(xdsLbState.balancerName)) {
        xdsComms = xdsLbState.shutdownAndReleaseXdsComms();
        if (xdsComms != null) {
          xdsComms.shutdownChannel();
          xdsComms = null;
        }
      } else if (!Objects.equals(
          getPolicyNameOrNull(childPolicy),
          getPolicyNameOrNull(xdsLbState.childPolicy))) {
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
    xdsLbState = new XdsLbState(newBalancerName, childPolicy, xdsComms, helper, subchannelStore);
  }

  @Nullable
  private static String getPolicyNameOrNull(@Nullable Map<String, Object> config) {
    if (config == null) {
      return null;
    }
    return getBalancerPolicyNameFromLoadBalancingConfig(config);
  }

  @Nullable
  @VisibleForTesting
  static Map<String, Object> selectChildPolicy(
      Map<String, Object> lbConfig, LoadBalancerRegistry lbRegistry) {
    List<Map<String, Object>> childConfigs =
        ServiceConfigUtil.getChildPolicyFromXdsConfig(lbConfig);
    return selectSupportedLbPolicy(childConfigs, lbRegistry);
  }

  @VisibleForTesting
  static Map<String, Object> selectFallbackPolicy(
      Map<String, Object> lbConfig, LoadBalancerRegistry lbRegistry) {
    List<Map<String, Object>> fallbackConfigs =
        ServiceConfigUtil.getFallbackPolicyFromXdsConfig(lbConfig);
    Map<String, Object> fallbackPolicy = selectSupportedLbPolicy(fallbackConfigs, lbRegistry);
    return fallbackPolicy == null ? DEFAULT_FALLBACK_POLICY : fallbackPolicy;
  }

  @Nullable
  private static Map<String, Object> selectSupportedLbPolicy(
      List<Map<String, Object>> lbConfigs, LoadBalancerRegistry lbRegistry) {
    if (lbConfigs == null) {
      return null;
    }
    for (Object lbConfig : lbConfigs) {
      @SuppressWarnings("unchecked")
      Map<String, Object> candidate = (Map<String, Object>) lbConfig;
      String lbPolicy = ServiceConfigUtil.getBalancerPolicyNameFromLoadBalancingConfig(candidate);
      if (lbRegistry.getProvider(lbPolicy) != null) {
        return candidate;
      }
    }
    return null;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    if (xdsLbState != null) {
      if (fallbackManager.fallbackBalancer() != null) {
        fallbackManager.fallbackBalancer().handleNameResolutionError(error);
      } else {
        xdsLbState.handleNameResolutionError(error);
      }
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
    if (newState.getState() == SHUTDOWN) {
      return;
    }

    if (fallbackManager.fallbackBalancer() != null) {
      fallbackManager.fallbackBalancer().handleSubchannelState(subchannel, newState);
    }
    if (subchannelStore.hasSubchannel(subchannel)) {
      if (newState.getState() == IDLE) {
        subchannel.requestConnection();
      }
      subchannel.getAttributes().get(STATE_INFO).set(newState);
      xdsLbState.handleSubchannelState(subchannel, newState);
      fallbackManager.maybeUseFallbackPolicy();
    }
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
    fallbackManager.cancelFallback();
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
