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

package io.grpc.util;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.ExperimentalApi;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.ServiceConfigUtil;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * A load balancer that gracefully swaps to a new lb policy. If the channel is currently in a state
 * other than READY, the new policy will be swapped into place immediately.  Otherwise, the channel
 * will keep using the old policy until the new policy reports READY or the old policy exits READY.
 *
 * <p>The child balancer and configuration is specified using service config. Config objects are
 * generally created by calling {@link #parseLoadBalancingPolicyConfig(List)} from a
 * {@link io.grpc.LoadBalancerProvider#parseLoadBalancingPolicyConfig
 * provider's parseLoadBalancingPolicyConfig()} implementation.
 *
 * <p>Alternatively, the balancer may {@link #switchTo(LoadBalancer.Factory) switch to} a policy
 * prior to {@link
 * LoadBalancer#handleResolvedAddresses(ResolvedAddresses) handling resolved addresses} for the
 * first time. This causes graceful switch to ignore the service config and pass through the
 * resolved addresses directly to the child policy.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/5999")
@NotThreadSafe // Must be accessed in SynchronizationContext
public final class GracefulSwitchLoadBalancer extends ForwardingLoadBalancer {
  private final LoadBalancer defaultBalancer = new LoadBalancer() {
    /**
     * Handles newly resolved addresses and metadata attributes from name resolution system.
     *
     * @deprecated  As of release 1.69.0,
     *     use instead {@link #acceptResolvedAddresses(ResolvedAddresses)}
     */
    @Deprecated
    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      //  Most LB policies using this class will receive child policy configuration within the
      //  service config, so they are naturally calling switchTo() just before
      //  handleResolvedAddresses(), within their own handleResolvedAddresses(). If switchTo() is
      //  not called immediately after construction that does open up potential for bugs in the
      //  parent policies, where they fail to call switchTo(). So we will use the exception to try
      //  to notice those bugs quickly, as it will fail very loudly.
      throw new IllegalStateException(
          "GracefulSwitchLoadBalancer must switch to a load balancing policy before handling"
              + " ResolvedAddresses");
    }

    @Override
    public void handleNameResolutionError(final Status error) {
      helper.updateBalancingState(
          ConnectivityState.TRANSIENT_FAILURE,
          new FixedResultPicker(PickResult.withError(error)));
    }

    @Override
    public void shutdown() {}
  };

  @VisibleForTesting
  static final SubchannelPicker BUFFER_PICKER = new SubchannelPicker() {
    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return PickResult.withNoResult();
    }

    @Override
    public String toString() {
      return "BUFFER_PICKER";
    }
  };

  private final Helper helper;

  // While the new policy is not fully switched on, the pendingLb is handling new updates from name
  // resolver, and the currentLb is updating channel state and picker for the given helper.
  // The current fields are guaranteed to be set after the initial swapTo().
  // The pending fields are cleared when it becomes current.
  @Nullable private LoadBalancer.Factory currentBalancerFactory;
  private LoadBalancer currentLb = defaultBalancer;
  @Nullable private LoadBalancer.Factory pendingBalancerFactory;
  private LoadBalancer pendingLb = defaultBalancer;
  private ConnectivityState pendingState;
  private SubchannelPicker pendingPicker;
  private boolean switchToCalled;

  private boolean currentLbIsReady;

  public GracefulSwitchLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
  }

  /**
   * Handles newly resolved addresses and metadata attributes from name resolution system.
   *
   * @deprecated  As of release 1.69.0,
   *     use instead {@link #acceptResolvedAddresses(ResolvedAddresses)}
   */
  @Deprecated
  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    if (switchToCalled) {
      delegate().handleResolvedAddresses(resolvedAddresses);
      return;
    }
    Config config = (Config) resolvedAddresses.getLoadBalancingPolicyConfig();
    switchToInternal(config.childFactory);
    delegate().handleResolvedAddresses(
        resolvedAddresses.toBuilder()
          .setLoadBalancingPolicyConfig(config.childConfig)
          .build());
  }

  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    if (switchToCalled) {
      return delegate().acceptResolvedAddresses(resolvedAddresses);
    }
    Config config = (Config) resolvedAddresses.getLoadBalancingPolicyConfig();
    switchToInternal(config.childFactory);
    return delegate().acceptResolvedAddresses(
        resolvedAddresses.toBuilder()
          .setLoadBalancingPolicyConfig(config.childConfig)
          .build());
  }

  /**
   * Gracefully switch to a new policy defined by the given factory, if the given factory isn't
   * equal to the current one.
   *
   * @deprecated Use {@code parseLoadBalancingPolicyConfig()} and pass the configuration to
   *     {@link io.grpc.LoadBalancer.ResolvedAddresses.Builder#setLoadBalancingPolicyConfig}
   */
  @Deprecated
  public void switchTo(LoadBalancer.Factory newBalancerFactory) {
    switchToCalled = true;
    switchToInternal(newBalancerFactory);
  }

  private void switchToInternal(LoadBalancer.Factory newBalancerFactory) {
    checkNotNull(newBalancerFactory, "newBalancerFactory");

    if (newBalancerFactory.equals(pendingBalancerFactory)) {
      return;
    }
    pendingLb.shutdown();
    pendingLb = defaultBalancer;
    pendingBalancerFactory = null;
    pendingState = ConnectivityState.CONNECTING;
    pendingPicker = BUFFER_PICKER;

    if (newBalancerFactory.equals(currentBalancerFactory)) {
      return;
    }

    class PendingHelper extends ForwardingLoadBalancerHelper {
      LoadBalancer lb;

      @Override
      protected Helper delegate() {
        return helper;
      }

      @Override
      public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
        if (lb == pendingLb) {
          checkState(currentLbIsReady, "there's pending lb while current lb has been out of READY");
          pendingState = newState;
          pendingPicker = newPicker;
          if (newState == ConnectivityState.READY) {
            swap();
          }
        } else if (lb == currentLb) {
          currentLbIsReady = newState == ConnectivityState.READY;
          if (!currentLbIsReady && pendingLb != defaultBalancer) {
            swap(); // current policy exits READY, so swap
          } else {
            helper.updateBalancingState(newState, newPicker);
          }
        }
      }
    }

    PendingHelper pendingHelper = new PendingHelper();
    pendingHelper.lb = newBalancerFactory.newLoadBalancer(pendingHelper);
    pendingLb = pendingHelper.lb;
    pendingBalancerFactory = newBalancerFactory;
    if (!currentLbIsReady) {
      swap(); // the old policy is not READY at the moment, so swap to the new one right now
    }
  }

  private void swap() {
    helper.updateBalancingState(pendingState, pendingPicker);
    currentLb.shutdown();
    currentLb = pendingLb;
    currentBalancerFactory = pendingBalancerFactory;
    pendingLb = defaultBalancer;
    pendingBalancerFactory = null;
  }

  @Override
  protected LoadBalancer delegate() {
    return pendingLb == defaultBalancer ? currentLb : pendingLb;
  }

  @Override
  @Deprecated
  public void handleSubchannelState(
      Subchannel subchannel, ConnectivityStateInfo stateInfo) {
    throw new UnsupportedOperationException(
        "handleSubchannelState() is not supported by " + this.getClass().getName());
  }

  @Override
  public void shutdown() {
    pendingLb.shutdown();
    currentLb.shutdown();
  }

  public String delegateType() {
    return delegate().getClass().getSimpleName();
  }

  /**
   * Provided a JSON list of LoadBalancingConfigs, parse it into a config to pass to GracefulSwitch.
   */
  public static ConfigOrError parseLoadBalancingPolicyConfig(
      List<Map<String, ?>> loadBalancingConfigs) {
    return parseLoadBalancingPolicyConfig(
        loadBalancingConfigs, LoadBalancerRegistry.getDefaultRegistry());
  }

  /**
   * Provided a JSON list of LoadBalancingConfigs, parse it into a config to pass to GracefulSwitch.
   */
  public static ConfigOrError parseLoadBalancingPolicyConfig(
      List<Map<String, ?>> loadBalancingConfigs, LoadBalancerRegistry lbRegistry) {
    List<ServiceConfigUtil.LbConfig> childConfigCandidates =
        ServiceConfigUtil.unwrapLoadBalancingConfigList(loadBalancingConfigs);
    if (childConfigCandidates == null || childConfigCandidates.isEmpty()) {
      return ConfigOrError.fromError(
          Status.INTERNAL.withDescription("No child LB config specified"));
    }
    ConfigOrError selectedConfig =
        ServiceConfigUtil.selectLbPolicyFromList(childConfigCandidates, lbRegistry);
    if (selectedConfig.getError() != null) {
      Status error = selectedConfig.getError();
      return ConfigOrError.fromError(
          Status.INTERNAL
              .withCause(error.getCause())
              .withDescription(error.getDescription())
              .augmentDescription("Failed to select child config"));
    }
    ServiceConfigUtil.PolicySelection selection =
        (ServiceConfigUtil.PolicySelection) selectedConfig.getConfig();
    return ConfigOrError.fromConfig(
        createLoadBalancingPolicyConfig(selection.getProvider(), selection.getConfig()));
  }

  /**
   * Directly create a config to pass to GracefulSwitch. The object returned is the same as would be
   * found in {@code ConfigOrError.getConfig()}.
   */
  public static Object createLoadBalancingPolicyConfig(
      LoadBalancer.Factory childFactory, @Nullable Object childConfig) {
    return new Config(childFactory, childConfig);
  }

  static final class Config {
    final LoadBalancer.Factory childFactory;
    @Nullable
    final Object childConfig;

    public Config(LoadBalancer.Factory childFactory, @Nullable Object childConfig) {
      this.childFactory = checkNotNull(childFactory, "childFactory");
      this.childConfig = childConfig;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Config)) {
        return false;
      }
      Config that = (Config) o;
      return Objects.equal(childFactory, that.childFactory)
          && Objects.equal(childConfig, that.childConfig);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(childFactory, childConfig);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper("GracefulSwitchLoadBalancer.Config")
          .add("childFactory", childFactory)
          .add("childConfig", childConfig)
          .toString();
    }
  }
}
