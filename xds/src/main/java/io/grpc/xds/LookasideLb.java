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
import static java.util.logging.Level.FINEST;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Attributes;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.util.ForwardingLoadBalancer;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.LocalityStore.LocalityStoreImpl;
import io.grpc.xds.LookasideChannelLb.LookasideChannelCallback;
import io.grpc.xds.XdsLoadBalancerProvider.XdsConfig;
import java.util.Map;
import java.util.logging.Logger;

/** Lookaside load balancer that handles balancer name changes. */
final class LookasideLb extends ForwardingLoadBalancer {

  private final LookasideChannelCallback lookasideChannelCallback;
  private final LookasideChannelLbFactory lookasideChannelLbFactory;
  private final GracefulSwitchLoadBalancer lookasideChannelLb;
  private final LoadBalancerRegistry lbRegistry;

  private String balancerName;

  LookasideLb(Helper lookasideLbHelper, LookasideChannelCallback lookasideChannelCallback) {
    this(
        lookasideLbHelper, lookasideChannelCallback, new LookasideChannelLbFactoryImpl(),
        LoadBalancerRegistry.getDefaultRegistry());
  }

  @VisibleForTesting
  LookasideLb(
      Helper lookasideLbHelper,
      LookasideChannelCallback lookasideChannelCallback,
      LookasideChannelLbFactory lookasideChannelLbFactory,
      LoadBalancerRegistry lbRegistry) {
    this.lookasideChannelCallback = lookasideChannelCallback;
    this.lookasideChannelLbFactory = lookasideChannelLbFactory;
    this.lbRegistry = lbRegistry;
    this.lookasideChannelLb = new GracefulSwitchLoadBalancer(lookasideLbHelper);
  }

  @Override
  protected LoadBalancer delegate() {
    return lookasideChannelLb;
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    // In the future, xdsConfig can be gotten directly by
    // resolvedAddresses.getLoadBalancingPolicyConfig()
    Attributes attributes = resolvedAddresses.getAttributes();
    Map<String, ?> newRawLbConfig = checkNotNull(
        attributes.get(ATTR_LOAD_BALANCING_CONFIG), "ATTR_LOAD_BALANCING_CONFIG not available");
    ConfigOrError cfg =
        XdsLoadBalancerProvider.parseLoadBalancingConfigPolicy(newRawLbConfig, lbRegistry);
    if (cfg.getError() != null) {
      throw cfg.getError().asRuntimeException();
    }
    XdsConfig xdsConfig = (XdsConfig) cfg.getConfig();

    String newBalancerName = xdsConfig.balancerName;
    if (!newBalancerName.equals(balancerName)) {
      balancerName = newBalancerName; // cache the name and check next time for optimization
      lookasideChannelLb.switchTo(newLookasideChannelLbProvider(newBalancerName));
    }
    lookasideChannelLb.handleResolvedAddresses(resolvedAddresses);
  }

  private LoadBalancerProvider newLookasideChannelLbProvider(final String balancerName) {
    return new LoadBalancerProvider() {
      @Override
      public boolean isAvailable() {
        return true;
      }

      @Override
      public int getPriority() {
        return 5;
      }

      /**
       * A synthetic policy name for LookasideChannelLb identified by balancerName. The
       * implementation detail doesn't matter.
       */
      @Override
      public String getPolicyName() {
        return "xds_child_policy_balancer_name_" + balancerName;
      }

      @Override
      public LoadBalancer newLoadBalancer(Helper helper) {
        return lookasideChannelLbFactory.newLoadBalancer(
            helper, lookasideChannelCallback, balancerName);
      }
    };
  }

  @VisibleForTesting
  interface LookasideChannelLbFactory {
    LoadBalancer newLoadBalancer(
        Helper helper, LookasideChannelCallback lookasideChannelCallback, String balancerName);
  }

  private static final class LookasideChannelLbFactoryImpl implements LookasideChannelLbFactory {

    @Override
    public LoadBalancer newLoadBalancer(
        Helper helper, LookasideChannelCallback lookasideChannelCallback, String balancerName) {
      return new LookasideChannelLb(
          helper, lookasideChannelCallback, initLbChannel(helper, balancerName),
          new LocalityStoreImpl(helper, LoadBalancerRegistry.getDefaultRegistry()));
    }

    private static ManagedChannel initLbChannel(Helper helper, String balancerName) {
      ManagedChannel channel;
      try {
        channel = helper.createResolvingOobChannel(balancerName);
      } catch (UnsupportedOperationException uoe) {
        // Temporary solution until createResolvingOobChannel is implemented
        // FIXME (https://github.com/grpc/grpc-java/issues/5495)
        Logger logger = Logger.getLogger(LookasideChannelLb.class.getName());
        if (logger.isLoggable(FINEST)) {
          logger.log(
              FINEST,
              "createResolvingOobChannel() not supported by the helper: " + helper,
              uoe);
          logger.log(
              FINEST,
              "creating oob channel for target {0} using default ManagedChannelBuilder",
              balancerName);
        }
        channel = ManagedChannelBuilder.forTarget(balancerName).build();
      }
      return channel;
    }
  }
}
