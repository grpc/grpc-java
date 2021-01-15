/*
 * Copyright 2020 The gRPC Authors
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
import static com.google.common.base.Preconditions.checkState;

import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.ClientLoadCounter.LoadRecordingSubchannelPicker;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.LoadStatsManager.LoadStatsStore;
import io.grpc.xds.LrsLoadBalancerProvider.LrsConfig;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.Objects;
import javax.annotation.CheckForNull;

/**
 * Load balancer for lrs policy.
 */
final class LrsLoadBalancer extends LoadBalancer {
  private final LoadBalancer.Helper helper;
  @CheckForNull
  private GracefulSwitchLoadBalancer switchingLoadBalancer;
  private LoadStatsStore loadStatsStore;
  private String clusterName;
  private String edsServiceName;
  private Locality locality;
  private String childPolicyName;

  LrsLoadBalancer(LoadBalancer.Helper helper) {
    this.helper = checkNotNull(helper, "helper");
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    LrsConfig config = (LrsConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    LoadStatsStore store = resolvedAddresses.getAttributes().get(
        InternalXdsAttributes.ATTR_CLUSTER_SERVICE_LOAD_STATS_STORE);
    checkNotNull(config, "missing LRS lb config");
    checkNotNull(store, "missing cluster service stats object");
    checkAndSetUp(config, store);

    if (switchingLoadBalancer == null) {
      final ClientLoadCounter counter = loadStatsStore.addLocality(config.locality);
      LoadBalancer.Helper loadRecordingHelper = new ForwardingLoadBalancerHelper() {
        @Override
        protected Helper delegate() {
          return helper;
        }

        @Override
        public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
          SubchannelPicker loadRecordingPicker =
              new LoadRecordingSubchannelPicker(counter, newPicker);
          super.updateBalancingState(newState, loadRecordingPicker);
        }
      };
      switchingLoadBalancer = new GracefulSwitchLoadBalancer(loadRecordingHelper);
    }
    String updatedChildPolicyName = config.childPolicy.getProvider().getPolicyName();
    if (!Objects.equals(childPolicyName, updatedChildPolicyName)) {
      switchingLoadBalancer.switchTo(config.childPolicy.getProvider());
      childPolicyName = updatedChildPolicyName;
    }
    ResolvedAddresses downStreamResult =
        resolvedAddresses.toBuilder()
            .setLoadBalancingPolicyConfig(config.childPolicy.getConfig())
            .build();
    switchingLoadBalancer.handleResolvedAddresses(downStreamResult);
  }

  @Override
  public void handleNameResolutionError(Status error) {
    if (switchingLoadBalancer != null) {
      switchingLoadBalancer.handleNameResolutionError(error);
    } else {
      helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, new ErrorPicker(error));
    }
  }

  @Override
  public void shutdown() {
    if (switchingLoadBalancer != null) {
      loadStatsStore.removeLocality(locality);
      switchingLoadBalancer.shutdown();
    }
  }

  private void checkAndSetUp(LrsConfig config, LoadStatsStore store) {
    checkState(
        clusterName == null || clusterName.equals(config.clusterName),
        "cluster name should not change");
    checkState(
        edsServiceName == null || edsServiceName.equals(config.edsServiceName),
        "edsServiceName should not change");
    checkState(locality == null || locality.equals(config.locality), "locality should not change");
    checkState(
        loadStatsStore == null || loadStatsStore.equals(store),
        "loadStatsStore should not change");
    clusterName = config.clusterName;
    edsServiceName = config.edsServiceName;
    locality = config.locality;
    loadStatsStore = store;
  }
}
