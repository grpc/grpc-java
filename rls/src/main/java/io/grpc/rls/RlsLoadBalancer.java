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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import javax.annotation.Nullable;

/**
 * Implementation of {@link LoadBalancer} backed by route lookup service.
 */
final class RlsLoadBalancer extends LoadBalancer {

  private final ChannelLogger logger;
  private final Helper helper;
  @VisibleForTesting
  CachingRlsLbClientBuilderProvider cachingRlsLbClientBuilderProvider =
      new DefaultCachingRlsLbClientBuilderProvider();
  @Nullable
  private LbPolicyConfiguration lbPolicyConfiguration;
  @Nullable
  private CachingRlsLbClient routeLookupClient;

  RlsLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
    logger = helper.getChannelLogger();
    logger.log(ChannelLogLevel.DEBUG, "Rls lb created. Authority: {0}", helper.getAuthority());
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(ChannelLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
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
        routeLookupClient =
            cachingRlsLbClientBuilderProvider
                .get()
                .setHelper(helper)
                .setLbPolicyConfig(lbPolicyConfiguration)
                .setResolvedAddressesFactory(
                    new ChildLbResolvedAddressFactory(
                        resolvedAddresses.getAddresses(), resolvedAddresses.getAttributes()))
                .build();
        logger.log(
            ChannelLogLevel.DEBUG, "LbPolicyConfiguration updated to {0}", lbPolicyConfiguration);
      }
      // TODO(creamsoup) allow incremental service config update. for initial use case, it is 
      //  not required.
      this.lbPolicyConfiguration = lbPolicyConfiguration;
    }
  }

  @Override
  public void requestConnection() {
    routeLookupClient.requestConnection();
  }

  @Override
  public void handleNameResolutionError(final Status error) {
    logger.log(ChannelLogLevel.DEBUG, "Received resolution error: {0}", error);
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
    logger.log(ChannelLogLevel.DEBUG, "Rls lb shutdown");
    if (routeLookupClient != null) {
      routeLookupClient.close();
      routeLookupClient = null;
    }
  }

  /**
   * Provides {@link CachingRlsLbClient.Builder} with default settings. This is useful for
   * testing.
   */
  interface CachingRlsLbClientBuilderProvider {
    CachingRlsLbClient.Builder get();
  }

  static final class DefaultCachingRlsLbClientBuilderProvider
      implements CachingRlsLbClientBuilderProvider {

    @Override
    public CachingRlsLbClient.Builder get() {
      return CachingRlsLbClient.newBuilder().setThrottler(AdaptiveThrottler.builder().build());
    }
  }
}
