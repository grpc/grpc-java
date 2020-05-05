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

package io.grpc.rls;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.rls.internal.AdaptiveThrottler;
import io.grpc.rls.internal.CachingRlsLbClient;
import io.grpc.rls.internal.ChildLbResolvedAddressFactory;
import io.grpc.rls.internal.LbPolicyConfiguration;
import javax.annotation.Nullable;

/**
 * Implementation of {@link LoadBalancer} backed by route lookup service.
 */
final class RlsLoadBalancer extends LoadBalancer {

  private final Helper helper;
  @Nullable
  private LbPolicyConfiguration lbPolicyConfiguration;
  @Nullable
  private CachingRlsLbClient routeLookupClient;

  RlsLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    LbPolicyConfiguration lbPolicyConfiguration =
        (LbPolicyConfiguration) resolvedAddresses.getLoadBalancingPolicyConfig();
    checkNotNull(lbPolicyConfiguration, "Missing rls lb config");
    if (!lbPolicyConfiguration.equals(this.lbPolicyConfiguration)) {
      boolean needToConnect = this.lbPolicyConfiguration == null
          || !this.lbPolicyConfiguration.getRouteLookupConfig().getLookupService().equals(
          lbPolicyConfiguration.getRouteLookupConfig().getLookupService());
      if (needToConnect) {
        if (routeLookupClient != null) {
          routeLookupClient.close();
        }
        routeLookupClient = CachingRlsLbClient.newBuilder()
            .setHelper(helper)
            .setLbPolicyConfig(lbPolicyConfiguration)
            .setThrottler(AdaptiveThrottler.builder().build())
            .setResolvedAddressesFactory(
                new ChildLbResolvedAddressFactory(
                    resolvedAddresses.getAddresses(), resolvedAddresses.getAttributes()))
            .build();
      }
      // TODO(creamsoup) allow incremental service config update. for initial use case, it is 
      //  not required.
      this.lbPolicyConfiguration = lbPolicyConfiguration;
      helper.getChannelLogger()
          .log(ChannelLogLevel.INFO, "LbPolicyConfiguration updated to {0}", lbPolicyConfiguration);
    }
  }

  @Override
  public void requestConnection() {
    routeLookupClient.requestConnection();
  }

  @Override
  public void handleNameResolutionError(final Status error) {
    class ErrorPicker extends SubchannelPicker {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withError(error);
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("error", error)
            .toString();
      }
    }

    if (routeLookupClient != null) {
      routeLookupClient.close();
      routeLookupClient = null;
      lbPolicyConfiguration = null;
    }
    helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, new ErrorPicker());
  }

  @Override
  public void shutdown() {
    if (routeLookupClient != null) {
      routeLookupClient.close();
      routeLookupClient = null;
    }
  }
}
