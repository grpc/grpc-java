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
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.xds.LocalityStore.LocalityStoreImpl;
import io.grpc.xds.XdsComms.AdsStreamCallback;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

/**
 * A {@link LoadBalancer} that uses the XDS protocol.
 */
final class XdsLoadBalancer extends LoadBalancer {

  @VisibleForTesting
  static final Attributes.Key<AtomicReference<ConnectivityStateInfo>> STATE_INFO =
      Attributes.Key.create("io.grpc.xds.XdsLoadBalancer.stateInfo");

  private final LocalityStore localityStore;
  private final Helper helper;
  private final LoadBalancerRegistry lbRegistry;
  private final FallbackManager fallbackManager;

  private final AdsStreamCallback adsStreamCallback = new AdsStreamCallback() {

    @Override
    public void onWorking() {
      if (fallbackManager.isAfterStartup) {
        // cancel Fallback-After-Startup timer if there's any
        fallbackManager.cancelFallbackTimer();
      }

      fallbackManager.balancerWorked = true;
    }

    @Override
    public void onError() {
      if (!fallbackManager.balancerWorked) {
        // start Fallback-at-Startup immediately
        fallbackManager.useFallbackPolicy();
      } else if (fallbackManager.isAfterStartup) {
        // TODO: schedule a timer for Fallback-After-Startup
      } // else: the Fallback-at-Startup timer is still pending, noop and wait
    }
  };

  @Nullable
  private XdsLbState xdsLbState;

  private LbConfig fallbackPolicy;

  XdsLoadBalancer(Helper helper, LoadBalancerRegistry lbRegistry) {
    this.helper = helper;
    this.lbRegistry = lbRegistry;
    this.localityStore = new LocalityStoreImpl(new LocalityStoreHelper(), lbRegistry);
    fallbackManager = new FallbackManager(helper, localityStore, lbRegistry);
  }

  private final class LocalityStoreHelper extends ForwardingLoadBalancerHelper {

    @Override
    protected Helper delegate() {
      return helper;
    }

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {

      if (newState == READY) {
        checkState(
            fallbackManager.balancerWorked,
            "channel goes to READY before the load balancer even worked");
        fallbackManager.isAfterStartup = true;
        fallbackManager.cancelFallback();
      }

      if (fallbackManager.fallbackBalancer == null) {
        helper.updateBalancingState(newState, newPicker);
      }
    }
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    List<EquivalentAddressGroup> servers = resolvedAddresses.getAddresses();
    Attributes attributes = resolvedAddresses.getAttributes();
    Map<String, ?> newRawLbConfig = checkNotNull(
        attributes.get(ATTR_LOAD_BALANCING_CONFIG), "ATTR_LOAD_BALANCING_CONFIG not available");

    ConfigOrError cfg =
        XdsLoadBalancerProvider.parseLoadBalancingConfigPolicy(newRawLbConfig, lbRegistry);
    if (cfg.getError() != null) {
      throw cfg.getError().asRuntimeException();
    }
    XdsConfig xdsConfig = (XdsConfig) cfg.getConfig();
    fallbackPolicy = xdsConfig.fallbackPolicy;
    fallbackManager.updateFallbackServers(servers, attributes, fallbackPolicy);
    fallbackManager.startFallbackTimer();
    handleNewConfig(xdsConfig);
    xdsLbState.handleResolvedAddressGroups(servers, attributes);
  }

  private void handleNewConfig(XdsConfig xdsConfig) {
    String newBalancerName = xdsConfig.newBalancerName;
    LbConfig childPolicy = xdsConfig.childPolicy;
    XdsComms xdsComms = null;
    if (xdsLbState != null) { // may release and re-use/shutdown xdsComms from current xdsLbState
      if (!newBalancerName.equals(xdsLbState.balancerName)) {
        xdsComms = xdsLbState.shutdownAndReleaseXdsComms();
        if (xdsComms != null) {
          xdsComms.shutdownChannel();
          xdsComms = null;
        }
      } else if (!Objects.equal(
          getPolicyNameOrNull(childPolicy),
          getPolicyNameOrNull(xdsLbState.childPolicy))) {
        String cancelMessage = "Changing loadbalancing mode";
        xdsComms = xdsLbState.shutdownAndReleaseXdsComms();
        // close the stream but reuse the channel
        if (xdsComms != null) {
          xdsComms.shutdownLbRpc(cancelMessage);
          xdsComms.refreshAdsStream();
        }
      } else { // effectively no change in policy, keep xdsLbState unchanged
        return;
      }
    }
    xdsLbState = new XdsLbState(
        newBalancerName, childPolicy, xdsComms, helper, localityStore, adsStreamCallback);
  }

  @Nullable
  private static String getPolicyNameOrNull(@Nullable LbConfig config) {
    if (config == null) {
      return null;
    }
    return config.getPolicyName();
  }

  @Override
  public void handleNameResolutionError(Status error) {
    if (xdsLbState != null) {
      xdsLbState.handleNameResolutionError(error);
    }
    if (fallbackManager.fallbackBalancer != null) {
      fallbackManager.fallbackBalancer.handleNameResolutionError(error);
    }
    if (xdsLbState == null && fallbackManager.fallbackBalancer == null) {
      helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
    }
  }

  /**
   * This is only for the subchannel that is created by the the child/fallback balancer using the
   * old API {@link LoadBalancer.Helper#createSubchannel(EquivalentAddressGroup, Attributes)} or
   * {@link LoadBalancer.Helper#createSubchannel(List, Attributes)}. Otherwise, it either won't be
   * called or won't have any effect.
   */
  @Deprecated
  @Override
  public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
    if (fallbackManager.fallbackBalancer != null) {
      fallbackManager.fallbackBalancer.handleSubchannelState(subchannel, newState);
    }

    // xdsLbState should never be null here since handleSubchannelState cannot be called while the
    // lb is shutdown.
    xdsLbState.handleSubchannelState(subchannel, newState);
  }

  @Override
  public void shutdown() {
    if (xdsLbState != null) {
      XdsComms xdsComms = xdsLbState.shutdownAndReleaseXdsComms();
      if (xdsComms != null) {
        xdsComms.shutdownChannel();
      }
      xdsLbState = null;
    }
    fallbackManager.cancelFallback();
  }

  @Override
  public boolean canHandleEmptyAddressListFromNameResolution() {
    return true;
  }

  @Nullable
  XdsLbState getXdsLbStateForTest() {
    return xdsLbState;
  }

  @VisibleForTesting
  static final class FallbackManager {

    private static final long FALLBACK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10); // same as grpclb

    private final Helper helper;
    private final LocalityStore localityStore;
    private final LoadBalancerRegistry lbRegistry;

    private LbConfig fallbackPolicy;

    // read-only for outer class
    private LoadBalancer fallbackBalancer;

    // Scheduled only once.  Never reset.
    @CheckForNull
    private ScheduledHandle fallbackTimer;
    @Nullable
    private FallbackTask fallbackTask;

    private List<EquivalentAddressGroup> fallbackServers = ImmutableList.of();
    private Attributes fallbackAttributes;

    // allow value write by outer class
    private boolean balancerWorked;
    private boolean isAfterStartup;

    FallbackManager(
        Helper helper, LocalityStore localityStore, LoadBalancerRegistry lbRegistry) {
      this.helper = helper;
      this.localityStore = localityStore;
      this.lbRegistry = lbRegistry;
    }

    void cancelFallbackTimer() {
      if (fallbackTimer != null) {
        fallbackTask.cancelled = true;
        fallbackTimer.cancel();
      }
    }

    void cancelFallback() {
      cancelFallbackTimer();
      if (fallbackBalancer != null) {
        fallbackBalancer.shutdown();
        fallbackBalancer = null;
      }
    }

    void useFallbackPolicy() {
      if (fallbackBalancer != null) {
        return;
      }

      cancelFallbackTimer();

      helper.getChannelLogger().log(
          ChannelLogLevel.INFO, "Using fallback policy");

      final class FallbackBalancerHelper extends ForwardingLoadBalancerHelper {

        @Override
        public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
          if (newState == SHUTDOWN) {
            return;
          }
          super.updateBalancingState(newState, newPicker);
        }

        @Override
        protected Helper delegate() {
          return helper;
        }
      }

      fallbackBalancer = lbRegistry.getProvider(fallbackPolicy.getPolicyName())
          .newLoadBalancer(new FallbackBalancerHelper());
      // TODO(carl-mastrangelo): propagate the load balancing config policy
      fallbackBalancer.handleResolvedAddresses(
          ResolvedAddresses.newBuilder()
              .setAddresses(fallbackServers)
              .setAttributes(fallbackAttributes)
              .build());
    }

    void updateFallbackServers(
        List<EquivalentAddressGroup> servers, Attributes attributes,
        LbConfig fallbackPolicy) {
      this.fallbackServers = servers;
      this.fallbackAttributes = Attributes.newBuilder()
          .setAll(attributes)
          .set(ATTR_LOAD_BALANCING_CONFIG, fallbackPolicy.getRawConfigValue())
          .build();
      LbConfig currentFallbackPolicy = this.fallbackPolicy;
      this.fallbackPolicy = fallbackPolicy;
      if (fallbackBalancer != null) {
        if (fallbackPolicy.getPolicyName().equals(currentFallbackPolicy.getPolicyName())) {
          // TODO(carl-mastrangelo): propagate the load balancing config policy
          fallbackBalancer.handleResolvedAddresses(
              ResolvedAddresses.newBuilder()
                  .setAddresses(fallbackServers)
                  .setAttributes(fallbackAttributes)
                  .build());
        } else {
          fallbackBalancer.shutdown();
          fallbackBalancer = null;
          useFallbackPolicy();
        }
      }
    }

    void startFallbackTimer() {
      if (fallbackTimer == null) {
        fallbackTask = new FallbackTask();
        fallbackTimer = helper.getSynchronizationContext().schedule(
            fallbackTask, FALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS,
            helper.getScheduledExecutorService());
      }
    }

    // Must be accessed in SynchronizationContext
    class FallbackTask implements Runnable {

      boolean cancelled;

      @Override
      public void run() {
        if (!cancelled) {
          useFallbackPolicy();
        }
      }
    }
  }

  /**
   * Represents a successfully parsed and validated LoadBalancingConfig for XDS.
   */
  static final class XdsConfig {
    private final String newBalancerName;
    // TODO(carl-mastrangelo): make these Object's containing the fully parsed child configs.
    @Nullable
    private final LbConfig childPolicy;
    @Nullable
    private final LbConfig fallbackPolicy;

    XdsConfig(
        String newBalancerName, @Nullable LbConfig childPolicy, @Nullable LbConfig fallbackPolicy) {
      this.newBalancerName = checkNotNull(newBalancerName, "newBalancerName");
      this.childPolicy = childPolicy;
      this.fallbackPolicy = fallbackPolicy;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("newBalancerName", newBalancerName)
          .add("childPolicy", childPolicy)
          .add("fallbackPolicy", fallbackPolicy)
          .toString();
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof XdsConfig)) {
        return false;
      }
      XdsConfig that = (XdsConfig) obj;
      return Objects.equal(this.newBalancerName, that.newBalancerName)
          && Objects.equal(this.childPolicy, that.childPolicy)
          && Objects.equal(this.fallbackPolicy, that.fallbackPolicy);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(newBalancerName, childPolicy, fallbackPolicy);
    }
  }
}
