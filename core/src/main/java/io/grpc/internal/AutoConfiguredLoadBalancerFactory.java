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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Attributes;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
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
import java.util.ArrayList;
import java.util.Collections;
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
    public void handleResolvedAddressGroups(List<EquivalentAddressGroup> s, Attributes a) {}

    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {}

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

    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      tryHandleResolvedAddresses(resolvedAddresses);
    }

    /**
     * Returns non-OK status if resolvedAddresses is empty and delegate lb requires address ({@link
     * LoadBalancer#canHandleEmptyAddressListFromNameResolution()} returns {@code false}). {@code
     * AutoConfiguredLoadBalancer} doesn't expose {@code canHandleEmptyAddressListFromNameResolution}
     * because delegate LB may not be deterministic.
     */
    Status tryHandleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      List<EquivalentAddressGroup> servers = resolvedAddresses.getAddresses();
      checkArgument(
          resolvedAddresses.getLoadBalancingPolicyConfig() == null
              || resolvedAddresses.getLoadBalancingPolicyConfig() instanceof ManagedChannelServiceConfig,
          "wrong!"); //TODO better error message

      PolicySelection selection = null;
      if (resolvedAddresses.getLoadBalancingPolicyConfig() != null) {
        ManagedChannelServiceConfig mcsc =
            (ManagedChannelServiceConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
        selection = (PolicySelection) mcsc.getLoadBalancingConfig();
      }

      if (selection == null) {
        try {
          selection =
              new PolicySelection(
                  getProviderOrThrow(defaultPolicy, "using default policy"),
                  servers,
                  null);
        } catch (PolicyException e) {
          Status s = Status.INTERNAL.withDescription(e.getMessage());
          helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, new FailingPicker(s));
          delegate.shutdown();
          delegateProvider = null;
          delegate = new NoopLoadBalancer();
          return Status.OK;
        }
      }

      if (delegateProvider == null
          || !selection.provider.getPolicyName().equals(delegateProvider.getPolicyName())) {
        helper.updateBalancingState(ConnectivityState.CONNECTING, new EmptyPicker());
        delegate.shutdown();
        delegateProvider = selection.provider;
        LoadBalancer old = delegate;
        delegate = delegateProvider.newLoadBalancer(helper);
        helper.getChannelLogger().log(
            ChannelLogLevel.INFO, "Load balancer changed from {0} to {1}",
            old.getClass().getSimpleName(), delegate.getClass().getSimpleName());
      }

      LoadBalancer delegate = getDelegate();
      if (selection.serverList.isEmpty()
          && !delegate.canHandleEmptyAddressListFromNameResolution()) {
        return Status.UNAVAILABLE.withDescription(
            "NameResolver returned no usable address. addrs=" + servers + ", config="
                + resolvedAddresses.getLoadBalancingPolicyConfig());
      } else {
        delegate.handleResolvedAddresses(
            ResolvedAddresses.newBuilder()
                .setAddresses(selection.serverList)
                .setAttributes(resolvedAddresses.getAttributes())
                .setLoadBalancingPolicyConfig(selection.config)
                .build());
        return Status.OK;
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

  /**
   * Unlike a normal {@link LoadBalancer.Factory}, this accepts a full service config rather than
   * the LoadBalancingConfig.
   *
   * @return null if no selection could be made.
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
        List<String> policiesTried = new ArrayList<>();
        for (LbConfig lbConfig : loadBalancerConfigs) {
          String policy = lbConfig.getPolicyName();
          LoadBalancerProvider provider = registry.getProvider(policy);
          policiesTried.add(policy);
          if (provider != null) {
            ConfigOrError parsedConfig =
                provider.parseLoadBalancingPolicyConfig(lbConfig.getRawConfigValue());
            if (parsedConfig.getError() != null) {
              // TODO(jihuncho) verify this behavior with ejona@
              policiesTried.add(policy);
              continue;
            }
            return ConfigOrError.fromConfig(new PolicySelection(
                provider,
                /* serverList= */ Collections.<EquivalentAddressGroup>emptyList(),
                parsedConfig));
          }
        }
        return ConfigOrError.fromError(
            Status.UNKNOWN.withDescription(
                "None of " + policiesTried + " specified by Service Config are available."));
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

  @VisibleForTesting
  static final class PolicySelection {
    final LoadBalancerProvider provider;
    @Nullable final List<EquivalentAddressGroup> serverList;
    @Nullable final ConfigOrError config;

    PolicySelection(
        LoadBalancerProvider provider,
        List<EquivalentAddressGroup> serverList,
        @Nullable ConfigOrError config) {
      this.provider = checkNotNull(provider, "provider");
      this.serverList = Collections.unmodifiableList(checkNotNull(serverList, "serverList"));
      this.config = config;
    }
  }

  private static final class EmptyPicker extends SubchannelPicker {

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return PickResult.withNoResult();
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
