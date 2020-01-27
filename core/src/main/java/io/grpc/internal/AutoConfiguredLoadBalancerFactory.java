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
import static io.grpc.LoadBalancer.ATTR_LOAD_BALANCING_CONFIG;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
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
import java.util.logging.Logger;
import javax.annotation.Nullable;

// TODO(creamsoup) fully deprecate LoadBalancer.ATTR_LOAD_BALANCING_CONFIG
@SuppressWarnings("deprecation")
public final class AutoConfiguredLoadBalancerFactory {
  private static final Logger logger =
      Logger.getLogger(AutoConfiguredLoadBalancerFactory.class.getName());
  private static final String GRPCLB_POLICY_NAME = "grpclb";

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
    private boolean roundRobinDueToGrpclbDepMissing;

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
     * AutoConfiguredLoadBalancer} doesn't expose {@code
     * canHandleEmptyAddressListFromNameResolution} because it depends on the delegated LB.
     */
    Status tryHandleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      List<EquivalentAddressGroup> servers = resolvedAddresses.getAddresses();
      Attributes attributes = resolvedAddresses.getAttributes();
      if (attributes.get(ATTR_LOAD_BALANCING_CONFIG) != null) {
        throw new IllegalArgumentException(
            "Unexpected ATTR_LOAD_BALANCING_CONFIG from upstream: "
                + attributes.get(ATTR_LOAD_BALANCING_CONFIG));
      }
      PolicySelection policySelection =
          (PolicySelection) resolvedAddresses.getLoadBalancingPolicyConfig();
      ResolvedPolicySelection resolvedSelection;

      try {
        resolvedSelection = resolveLoadBalancerProvider(servers, policySelection);
      } catch (PolicyException e) {
        Status s = Status.INTERNAL.withDescription(e.getMessage());
        helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, new FailingPicker(s));
        delegate.shutdown();
        delegateProvider = null;
        delegate = new NoopLoadBalancer();
        return Status.OK;
      }
      PolicySelection selection = resolvedSelection.policySelection;

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
      Object lbConfig = selection.config;
      if (lbConfig != null) {
        helper.getChannelLogger().log(
            ChannelLogLevel.DEBUG, "Load-balancing config: {0}", selection.config);
        attributes =
            attributes.toBuilder().set(ATTR_LOAD_BALANCING_CONFIG, selection.rawConfig).build();
      }

      LoadBalancer delegate = getDelegate();
      if (resolvedSelection.serverList.isEmpty()
          && !delegate.canHandleEmptyAddressListFromNameResolution()) {
        return Status.UNAVAILABLE.withDescription(
            "NameResolver returned no usable address. addrs=" + servers + ", attrs=" + attributes);
      } else {
        delegate.handleResolvedAddresses(
            ResolvedAddresses.newBuilder()
                .setAddresses(resolvedSelection.serverList)
                .setAttributes(attributes)
                .setLoadBalancingPolicyConfig(lbConfig)
                .build());
        return Status.OK;
      }
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

    /**
     * Resolves a load balancer based on given criteria.  If policySelection is {@code null} and
     * given servers contains any gRPC LB addresses, it will fall back to "grpclb". If no gRPC LB
     * addresses are not present, it will fall back to {@link #defaultPolicy}.
     *
     * @param servers The list of servers reported
     * @param policySelection the selected policy from raw service config
     * @return the resolved policy selection
     */
    @VisibleForTesting
    ResolvedPolicySelection resolveLoadBalancerProvider(
        List<EquivalentAddressGroup> servers, @Nullable PolicySelection policySelection)
        throws PolicyException {
      // Check for balancer addresses
      boolean haveBalancerAddress = false;
      List<EquivalentAddressGroup> backendAddrs = new ArrayList<>();
      for (EquivalentAddressGroup s : servers) {
        if (s.getAttributes().get(GrpcAttributes.ATTR_LB_ADDR_AUTHORITY) != null) {
          haveBalancerAddress = true;
        } else {
          backendAddrs.add(s);
        }
      }

      if (policySelection != null) {
        String policyName = policySelection.provider.getPolicyName();
        return new ResolvedPolicySelection(
            policySelection, policyName.equals(GRPCLB_POLICY_NAME) ? servers : backendAddrs);
      }

      if (haveBalancerAddress) {
        // This is a special case where the existence of balancer address in the resolved address
        // selects "grpclb" policy if the service config couldn't select a policy
        LoadBalancerProvider grpclbProvider = registry.getProvider(GRPCLB_POLICY_NAME);
        if (grpclbProvider == null) {
          if (backendAddrs.isEmpty()) {
            throw new PolicyException(
                "Received ONLY balancer addresses but grpclb runtime is missing");
          }
          if (!roundRobinDueToGrpclbDepMissing) {
            // We don't log the warning every time we have an update.
            roundRobinDueToGrpclbDepMissing = true;
            String errorMsg = "Found balancer addresses but grpclb runtime is missing."
                + " Will use round_robin. Please include grpc-grpclb in your runtime dependencies.";
            helper.getChannelLogger().log(ChannelLogLevel.ERROR, errorMsg);
            logger.warning(errorMsg);
          }
          return new ResolvedPolicySelection(
              new PolicySelection(
                  getProviderOrThrow(
                      "round_robin", "received balancer addresses but grpclb runtime is missing"),
                  /* rawConfig = */ null,
                  /* config= */ null),
              backendAddrs);
        }
        return new ResolvedPolicySelection(
            new PolicySelection(
                grpclbProvider, /* rawConfig= */ null, /* config= */ null), servers);
      }
      // No balancer address this time.  If balancer address shows up later, we want to make sure
      // the warning is logged one more time.
      roundRobinDueToGrpclbDepMissing = false;

      // No config nor balancer address. Use default.
      return new ResolvedPolicySelection(
          new PolicySelection(
              getProviderOrThrow(defaultPolicy, "using default policy"),
              /* rawConfig= */ null,
              /* config= */ null),
          servers);
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
  ConfigOrError parseLoadBalancerPolicy(Map<String, ?> serviceConfig, ChannelLogger channelLogger) {
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
          if (provider == null) {
            policiesTried.add(policy);
          } else {
            if (!policiesTried.isEmpty()) {
              channelLogger.log(
                  ChannelLogLevel.DEBUG,
                  "{0} specified by Service Config are not available", policiesTried);
            }
            ConfigOrError parsedLbPolicyConfig =
                provider.parseLoadBalancingPolicyConfig(lbConfig.getRawConfigValue());
            if (parsedLbPolicyConfig.getError() != null) {
              return parsedLbPolicyConfig;
            }
            return ConfigOrError.fromConfig(
                new PolicySelection(
                    provider, lbConfig.getRawConfigValue(), parsedLbPolicyConfig.getConfig()));
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
    @Nullable final Map<String, ?> rawConfig;
    @Nullable final Object config;

    PolicySelection(
        LoadBalancerProvider provider,
        @Nullable Map<String, ?> rawConfig,
        @Nullable Object config) {
      this.provider = checkNotNull(provider, "provider");
      this.rawConfig = rawConfig;
      this.config = config;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      PolicySelection that = (PolicySelection) o;
      return Objects.equal(provider, that.provider)
          && Objects.equal(rawConfig, that.rawConfig)
          && Objects.equal(config, that.config);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(provider, rawConfig, config);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("provider", provider)
          .add("rawConfig", rawConfig)
          .add("config", config)
          .toString();
    }
  }

  @VisibleForTesting
  static final class ResolvedPolicySelection {
    final PolicySelection policySelection;
    final List<EquivalentAddressGroup> serverList;

    ResolvedPolicySelection(
        PolicySelection policySelection, List<EquivalentAddressGroup> serverList) {
      this.policySelection = checkNotNull(policySelection, "policySelection");
      this.serverList = Collections.unmodifiableList(checkNotNull(serverList, "serverList"));
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("policySelection", policySelection)
          .add("serverList", serverList)
          .toString();
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
