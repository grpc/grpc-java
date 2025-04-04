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
import com.google.common.base.MoreObjects;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

public final class AutoConfiguredLoadBalancerFactory {

  private final LoadBalancerRegistry registry;
  private final String defaultPolicy;

  public AutoConfiguredLoadBalancerFactory(String defaultPolicy) {
    this(LoadBalancerRegistry.getDefaultRegistry(), defaultPolicy);
  }

  @VisibleForTesting
  AutoConfiguredLoadBalancerFactory(LoadBalancerRegistry registry, String defaultPolicy) {
    this.registry = checkNotNull(registry, "registry");
    this.defaultPolicy = checkNotNull(defaultPolicy, "defaultPolicy");
  }

  public AutoConfiguredLoadBalancer newLoadBalancer(Helper helper) {
    return new AutoConfiguredLoadBalancer(helper);
  }

  private static final class NoopLoadBalancer extends LoadBalancer {

    @Override
    @Deprecated
    @SuppressWarnings("InlineMeSuggester")
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    }

    @Override
    public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      return Status.OK;
    }

    @Override
    public void handleNameResolutionError(Status error) {}

    @Override
    public void shutdown() {}
  }

  @VisibleForTesting
  public final class AutoConfiguredLoadBalancer {
    private final Helper helper;
    private LoadBalancer delegate;
    private LoadBalancerProvider delegateProvider;

    AutoConfiguredLoadBalancer(Helper helper) {
      this.helper = helper;
      delegateProvider = registry.getProvider(defaultPolicy);
      if (delegateProvider == null) {
        throw new IllegalStateException("Could not find policy '" + defaultPolicy
            + "'. Make sure its implementation is either registered to LoadBalancerRegistry or"
            + " included in META-INF/services/io.grpc.LoadBalancerProvider from your jar files.");
      }
      delegate = delegateProvider.newLoadBalancer(helper);
    }

    /**
     * Returns non-OK status if the delegate rejects the resolvedAddresses (e.g. if it does not
     * support an empty list).
     */
    Status tryAcceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      PolicySelection policySelection =
          (PolicySelection) resolvedAddresses.getLoadBalancingPolicyConfig();

      if (policySelection == null) {
        LoadBalancerProvider defaultProvider;
        try {
          defaultProvider = getProviderOrThrow(defaultPolicy, "using default policy");
        } catch (PolicyException e) {
          Status s = Status.INTERNAL.withDescription(e.getMessage());
          helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, new FailingPicker(s));
          delegate.shutdown();
          delegateProvider = null;
          delegate = new NoopLoadBalancer();
          return Status.OK;
        }
        policySelection =
            new PolicySelection(defaultProvider, /* config= */ null);
      }

      if (delegateProvider == null
          || !policySelection.provider.getPolicyName().equals(delegateProvider.getPolicyName())) {
        helper.updateBalancingState(ConnectivityState.CONNECTING, new EmptyPicker());
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

    void handleNameResolutionError(Status error) {
      getDelegate().handleNameResolutionError(error);
    }

    @Deprecated
    void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
      getDelegate().handleSubchannelState(subchannel, stateInfo);
    }

    void requestConnection() {
      getDelegate().requestConnection();
    }

    void shutdown() {
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

  private LoadBalancerProvider getProviderOrThrow(String policy, String choiceReason)
      throws PolicyException {
    LoadBalancerProvider provider = registry.getProvider(policy);
    if (provider == null) {
      throw new PolicyException(
          "Trying to load '" + policy + "' because " + choiceReason + ", but it's unavailable");
    }
    return provider;
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
  @Nullable
  ConfigOrError parseLoadBalancerPolicy(Map<String, ?> serviceConfig) {
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

  @VisibleForTesting
  static final class PolicyException extends Exception {
    private static final long serialVersionUID = 1L;

    private PolicyException(String msg) {
      super(msg);
    }
  }

  private static final class EmptyPicker extends SubchannelPicker {

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return PickResult.withNoResult();
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(EmptyPicker.class).toString();
    }
  }

  private static final class FailingPicker extends SubchannelPicker {
    private final Status failure;

    FailingPicker(Status failure) {
      this.failure = failure;
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return PickResult.withError(failure);
    }
  }
}
