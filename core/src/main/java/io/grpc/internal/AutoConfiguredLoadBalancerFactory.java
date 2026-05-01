/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.FixedResultPicker;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

public final class AutoConfiguredLoadBalancerFactory extends LoadBalancerProvider {

  private final LoadBalancerRegistry registry;
  private final LoadBalancerProvider defaultProvider;

  public AutoConfiguredLoadBalancerFactory(String defaultPolicy) {
    this(LoadBalancerRegistry.getDefaultRegistry(), defaultPolicy);
  }

  @VisibleForTesting
  AutoConfiguredLoadBalancerFactory(LoadBalancerRegistry registry, String defaultPolicy) {
    this.registry = checkNotNull(registry, "registry");
    LoadBalancerProvider provider =
        registry.getProvider(checkNotNull(defaultPolicy, "defaultPolicy"));
    if (provider == null) {
      Status status = Status.INTERNAL.withDescription("Could not find policy '" + defaultPolicy
          + "'. Make sure its implementation is either registered to LoadBalancerRegistry or"
          + " included in META-INF/services/io.grpc.LoadBalancerProvider from your jar files.");
      provider = new FixedPickerLoadBalancerProvider(
          ConnectivityState.TRANSIENT_FAILURE,
          new LoadBalancer.FixedResultPicker(PickResult.withError(status)),
          status);
    }
    this.defaultProvider = provider;
  }

  @Override
  public AutoConfiguredLoadBalancer newLoadBalancer(Helper helper) {
    return new AutoConfiguredLoadBalancer(helper);
  }

  @VisibleForTesting
  public final class AutoConfiguredLoadBalancer extends LoadBalancer {
    private final Helper helper;
    private LoadBalancer delegate;
    private LoadBalancerProvider delegateProvider;

    AutoConfiguredLoadBalancer(Helper helper) {
      this.helper = helper;
      this.delegateProvider = defaultProvider;
      delegate = delegateProvider.newLoadBalancer(helper);
    }

    /**
     * Returns non-OK status if the delegate rejects the resolvedAddresses (e.g. if it does not
     * support an empty list).
     */
    @Override
    public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      PolicySelection policySelection =
          (PolicySelection) resolvedAddresses.getLoadBalancingPolicyConfig();

      if (policySelection == null) {
        policySelection =
            new PolicySelection(defaultProvider, /* config= */ null);
      }

      if (delegateProvider == null
          || !policySelection.provider.getPolicyName().equals(delegateProvider.getPolicyName())) {
        helper.updateBalancingState(
            ConnectivityState.CONNECTING, new FixedResultPicker(PickResult.withNoResult()));
        delegate.shutdown();
        delegateProvider = policySelection.provider;
        LoadBalancer old = delegate;
        delegate = delegateProvider.newLoadBalancer(helper);
        helper.getChannelLogger().log(
            ChannelLogLevel.INFO, "Load balancer changed from {0} to {1}",
            old.getClass().getSimpleName(), delegate.getClass().getSimpleName());
      }
      Object lbConfig = policySelection.config;
      if (lbConfig != null) {
        helper.getChannelLogger().log(
            ChannelLogLevel.DEBUG, "Load-balancing config: {0}", policySelection.config);
      }

      return getDelegate().acceptResolvedAddresses(
          ResolvedAddresses.newBuilder()
              .setAddresses(resolvedAddresses.getAddresses())
              .setAttributes(resolvedAddresses.getAttributes())
              .setLoadBalancingPolicyConfig(lbConfig)
              .build());
    }

    @Override
    public void handleNameResolutionError(Status error) {
      getDelegate().handleNameResolutionError(error);
    }

    @Override
    @Deprecated
    public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
      getDelegate().handleSubchannelState(subchannel, stateInfo);
    }

    @Override
    public void requestConnection() {
      getDelegate().requestConnection();
    }

    @Override
    public void shutdown() {
      delegate.shutdown();
      delegate = null;
    }

    @VisibleForTesting
    public LoadBalancer getDelegate() {
      return delegate;
    }

    @VisibleForTesting
    void setDelegate(LoadBalancer lb) {
      delegate = lb;
    }

    @VisibleForTesting
    LoadBalancerProvider getDelegateProvider() {
      return delegateProvider;
    }
  }

  /**
   * Parses first available LoadBalancer policy from service config. Available LoadBalancer should
   * be registered to {@link LoadBalancerRegistry}. If the first available LoadBalancer policy is
   * invalid, it doesn't fall-back to next available policy, instead it returns error. This also
   * means, it ignores LoadBalancer policies after the first available one even if any of them are
   * invalid.
   *
   * <p>Order of policy preference:
   *
   * <ol>
   *    <li>Policy from "loadBalancingConfig" if present</li>
   *    <li>The policy from deprecated "loadBalancingPolicy" if present</li>
   * </ol>
   * </p>
   *
   * <p>Unlike a normal {@link LoadBalancer.Factory}, this accepts a full service config rather than
   * the LoadBalancingConfig.
   *
   * @return the parsed {@link PolicySelection}, or {@code null} if no selection could be made.
   */
  // TODO(ejona): The Provider API doesn't allow null, but ScParser can handle this and it will need
  // tweaking to ManagedChannelImpl.defaultServiceConfig to fix.
  @Nullable
  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> serviceConfig) {
    try {
      List<LbConfig> loadBalancerConfigs = null;
      if (serviceConfig != null) {
        List<Map<String, ?>> rawLbConfigs =
            ServiceConfigUtil.getLoadBalancingConfigsFromServiceConfig(serviceConfig);
        loadBalancerConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(rawLbConfigs);
      }
      if (loadBalancerConfigs != null && !loadBalancerConfigs.isEmpty()) {
        return ServiceConfigUtil.selectLbPolicyFromList(loadBalancerConfigs, registry);
      }
      return null;
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
          Status.UNKNOWN.withDescription("can't parse load balancer configuration").withCause(e));
    }
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public int getPriority() {
    return 5;
  }

  @Override
  public String getPolicyName() {
    return "auto_configured_internal";
  }
}
