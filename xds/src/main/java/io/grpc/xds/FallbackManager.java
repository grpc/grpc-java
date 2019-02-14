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

import static io.grpc.LoadBalancer.ATTR_LOAD_BALANCING_CONFIG;

import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerRegistry;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.xds.XdsLbState.SubchannelStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

final class FallbackManager {

  private static final long FALLBACK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10); // same as grpclb

  private final Helper helper;
  private final SubchannelStore subchannelStore;
  private final LoadBalancerRegistry lbRegistry;

  private Map<String, Object> fallbackPolicy;
  private LoadBalancer fallbackBalancer;

  // Scheduled only once.  Never reset.
  @Nullable
  private ScheduledHandle fallbackTimer;

  private List<EquivalentAddressGroup> fallbackServers = ImmutableList.of();
  private Attributes fallbackAttributes;

  // True if there is one active AdsStream which has received the first response.
  // TODO: notify balancer working
  private boolean balancerWorking;

  FallbackManager(
      Helper helper, SubchannelStore subchannelStore, LoadBalancerRegistry lbRegistry) {
    this.helper = helper;
    this.subchannelStore = subchannelStore;
    this.lbRegistry = lbRegistry;
  }

  LoadBalancer fallbackBalancer() {
    return fallbackBalancer;
  }

  void cancelFallback() {
    if (fallbackTimer != null) {
      fallbackTimer.cancel();
    }
    if (fallbackBalancer != null) {
      fallbackBalancer.shutdown();
      fallbackBalancer = null;
    }
  }

  void maybeUseFallbackPolicy() {
    if (fallbackBalancer != null) {
      return;
    }
    if (balancerWorking || subchannelStore.hasReadyBackends()) {
      return;
    }

    helper.getChannelLogger().log(
        ChannelLogLevel.INFO, "Using fallback policy");
    String fallbackPolicyName = ServiceConfigUtil.getBalancerPolicyNameFromLoadBalancingConfig(
        fallbackPolicy);
    fallbackBalancer = lbRegistry.getProvider(fallbackPolicyName)
        .newLoadBalancer(helper);
    fallbackBalancer.handleResolvedAddressGroups(fallbackServers, fallbackAttributes);
    // TODO: maybe update picker
  }

  void updateFallbackServers(
      List<EquivalentAddressGroup> servers, Attributes attributes,
      Map<String, Object> fallbackPolicy) {
    this.fallbackServers = servers;
    this.fallbackAttributes = Attributes.newBuilder()
        .setAll(attributes)
        .set(ATTR_LOAD_BALANCING_CONFIG, fallbackPolicy)
        .build();
    Map<String, Object> currentFallbackPolicy = this.fallbackPolicy;
    this.fallbackPolicy = fallbackPolicy;
    if (fallbackBalancer != null) {
      String currentPolicyName =
          ServiceConfigUtil.getBalancerPolicyNameFromLoadBalancingConfig(currentFallbackPolicy);
      String newPolicyName =
          ServiceConfigUtil.getBalancerPolicyNameFromLoadBalancingConfig(fallbackPolicy);
      if (newPolicyName.equals(currentPolicyName)) {
        fallbackBalancer.handleResolvedAddressGroups(fallbackServers, fallbackAttributes);
      } else {
        fallbackBalancer.shutdown();
        fallbackBalancer = null;
        maybeUseFallbackPolicy();
      }
    }
  }

  void maybeStartFallbackTimer() {
    if (fallbackTimer == null) {
      class FallbackTask implements Runnable {
        @Override
        public void run() {
          maybeUseFallbackPolicy();
        }
      }

      fallbackTimer = helper.getSynchronizationContext().schedule(
          new FallbackTask(), FALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS,
          helper.getScheduledExecutorService());
    }
  }
}
