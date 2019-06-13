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
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.xds.XdsLoadBalancerProvider.XDS_POLICY_NAME;
import static java.util.logging.Level.FINEST;

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
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.GrpcAttributes;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.xds.LocalityStore.LocalityStoreImpl;
import io.grpc.xds.XdsComms.AdsStreamCallback;
import io.grpc.xds.XdsLoadReportClientImpl.XdsLoadReportClientFactory;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

/**
 * A {@link LoadBalancer} that uses the XDS protocol.
 */
final class XdsLoadBalancer extends LoadBalancer {

  private final LocalityStore localityStore;
  private final Helper helper;
  private final LoadBalancerRegistry lbRegistry;
  private final FallbackManager fallbackManager;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final XdsLoadReportClientFactory lrsClientFactory;

  @Nullable
  private XdsLoadReportClient lrsClient;
  @Nullable
  private XdsLbState xdsLbState;
  private final AdsStreamCallback adsStreamCallback = new AdsStreamCallback() {

    @Override
    public void onWorking() {
      if (fallbackManager.childPolicyHasBeenReady) {
        // cancel Fallback-After-Startup timer if there's any
        fallbackManager.cancelFallbackTimer();
      }

      fallbackManager.childBalancerWorked = true;
      lrsClient.startLoadReporting();
    }

    @Override
    public void onError() {
      if (!fallbackManager.childBalancerWorked) {
        // start Fallback-at-Startup immediately
        fallbackManager.useFallbackPolicy();
      } else if (fallbackManager.childPolicyHasBeenReady) {
        // TODO: schedule a timer for Fallback-After-Startup
      } // else: the Fallback-at-Startup timer is still pending, noop and wait
    }

    @Override
    public void onAllDrop() {
      fallbackManager.cancelFallback();
    }
  };

  private LbConfig fallbackPolicy;

  XdsLoadBalancer(Helper helper, LoadBalancerRegistry lbRegistry,
      BackoffPolicy.Provider backoffPolicyProvider) {
    this(helper, lbRegistry, backoffPolicyProvider, XdsLoadReportClientFactory.getInstance(),
        new FallbackManager(helper, lbRegistry));
  }

  private XdsLoadBalancer(Helper helper,
      LoadBalancerRegistry lbRegistry,
      BackoffPolicy.Provider backoffPolicyProvider,
      XdsLoadReportClientFactory lrsClientFactory,
      FallbackManager fallbackManager) {
    this(helper, lbRegistry, backoffPolicyProvider, lrsClientFactory, fallbackManager,
        new LocalityStoreImpl(new LocalityStoreHelper(helper, fallbackManager), lbRegistry));
  }

  @VisibleForTesting
  XdsLoadBalancer(Helper helper,
      LoadBalancerRegistry lbRegistry,
      BackoffPolicy.Provider backoffPolicyProvider,
      XdsLoadReportClientFactory lrsClientFactory,
      FallbackManager fallbackManager,
      LocalityStore localityStore) {
    this.helper = checkNotNull(helper, "helper");
    this.lbRegistry = checkNotNull(lbRegistry, "lbRegistry");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    this.lrsClientFactory = checkNotNull(lrsClientFactory, "lrsClientFactory");
    this.fallbackManager = checkNotNull(fallbackManager, "fallbackManager");
    this.localityStore = checkNotNull(localityStore, "localityStore");
  }

  private static final class LocalityStoreHelper extends ForwardingLoadBalancerHelper {

    final Helper delegate;
    final FallbackManager fallbackManager;

    LocalityStoreHelper(Helper delegate, FallbackManager fallbackManager) {
      this.delegate = checkNotNull(delegate, "delegate");
      this.fallbackManager = checkNotNull(fallbackManager, "fallbackManager");
    }

    @Override
    protected Helper delegate() {
      return delegate;
    }

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {

      if (newState == READY) {
        checkState(
            fallbackManager.childBalancerWorked,
            "channel goes to READY before the load balancer even worked");
        fallbackManager.childPolicyHasBeenReady = true;
        fallbackManager.cancelFallback();
      }

      if (!fallbackManager.isInFallbackMode()) {
        delegate.updateBalancingState(newState, newPicker);
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
    ManagedChannel lbChannel;
    if (xdsLbState == null) {
      lbChannel = initLbChannel(helper, newBalancerName);
      lrsClient =
          lrsClientFactory.createLoadReportClient(lbChannel, helper, backoffPolicyProvider,
              localityStore.getStatsStore());
    } else if (!newBalancerName.equals(xdsLbState.balancerName)) {
      lrsClient.stopLoadReporting();
      ManagedChannel oldChannel =
          xdsLbState.shutdownAndReleaseChannel(
              String.format("Changing balancer name from %s to %s", xdsLbState.balancerName,
                  newBalancerName));
      oldChannel.shutdown();
      lbChannel = initLbChannel(helper, newBalancerName);
      lrsClient =
          lrsClientFactory.createLoadReportClient(lbChannel, helper, backoffPolicyProvider,
              localityStore.getStatsStore());
    } else if (!Objects.equal(
        getPolicyNameOrNull(childPolicy),
        getPolicyNameOrNull(xdsLbState.childPolicy))) {
      // Changing child policy does not affect load reporting.
      lbChannel =
          xdsLbState.shutdownAndReleaseChannel(
              String.format("Changing child policy from %s to %s", xdsLbState.childPolicy,
                  childPolicy));
    } else { // effectively no change in policy, keep xdsLbState unchanged
      return;
    }
    xdsLbState =
        new XdsLbState(newBalancerName, childPolicy, helper, localityStore, lbChannel,
            adsStreamCallback);
  }

  private static ManagedChannel initLbChannel(Helper helper, String balancerName) {
    ManagedChannel channel;
    try {
      channel = helper.createResolvingOobChannel(balancerName);
    } catch (UnsupportedOperationException uoe) {
      // Temporary solution until createResolvingOobChannel is implemented
      // FIXME (https://github.com/grpc/grpc-java/issues/5495)
      Logger logger = Logger.getLogger(XdsLoadBalancer.class.getName());
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
    if (fallbackManager.isInFallbackMode()) {
      fallbackManager.fallbackBalancer.handleNameResolutionError(error);
    }
    if (xdsLbState == null && !fallbackManager.isInFallbackMode()) {
      helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
    }
  }

  /**
   * This is only for the subchannel that is created by the child/fallback balancer using the
   * old API {@link LoadBalancer.Helper#createSubchannel(EquivalentAddressGroup, Attributes)} or
   * {@link LoadBalancer.Helper#createSubchannel(List, Attributes)}. Otherwise, it either won't be
   * called or won't have any effect.
   */
  @Deprecated
  @Override
  public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
    if (fallbackManager.isInFallbackMode()) {
      fallbackManager.fallbackBalancer.handleSubchannelState(subchannel, newState);
    }

    // xdsLbState should never be null here since handleSubchannelState cannot be called while the
    // lb is shutdown.
    xdsLbState.handleSubchannelState(subchannel, newState);
  }

  @Override
  public void shutdown() {
    if (xdsLbState != null) {
      lrsClient.stopLoadReporting();
      lrsClient = null;
      ManagedChannel channel = xdsLbState.shutdownAndReleaseChannel("Client shutdown");
      channel.shutdown();
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
    private final LoadBalancerRegistry lbRegistry;

    private LbConfig fallbackPolicy;

    // read-only for outer class
    private LoadBalancer fallbackBalancer;

    // Scheduled only once.  Never reset.
    @CheckForNull
    private ScheduledHandle fallbackTimer;

    private List<EquivalentAddressGroup> fallbackServers = ImmutableList.of();
    private Attributes fallbackAttributes;

    // allow value write by outer class
    private boolean childBalancerWorked;
    private boolean childPolicyHasBeenReady;

    FallbackManager(Helper helper, LoadBalancerRegistry lbRegistry) {
      this.helper = checkNotNull(helper, "helper");
      this.lbRegistry = checkNotNull(lbRegistry, "lbRegistry");
    }

    /**
     * Fallback mode being on indicates that an update from child LBs will be ignored unless the
     * update triggers turning off the fallback mode first.
     */
    boolean isInFallbackMode() {
      return fallbackBalancer != null;
    }

    void cancelFallbackTimer() {
      if (fallbackTimer != null) {
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
        LoadBalancer balancer;

        @Override
        public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
          checkNotNull(balancer, "there is a bug");
          if (balancer != fallbackBalancer) {
            // ignore updates from a misbehaving shutdown fallback balancer
            return;
          }
          super.updateBalancingState(newState, newPicker);
        }

        @Override
        protected Helper delegate() {
          return helper;
        }
      }

      FallbackBalancerHelper fallbackBalancerHelper = new FallbackBalancerHelper();
      fallbackBalancer = lbRegistry.getProvider(fallbackPolicy.getPolicyName())
          .newLoadBalancer(fallbackBalancerHelper);
      fallbackBalancerHelper.balancer = fallbackBalancer;
      propagateFallbackAddresses();
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
          propagateFallbackAddresses();
        } else {
          fallbackBalancer.shutdown();
          fallbackBalancer = null;
          useFallbackPolicy();
        }
      }
    }

    private void propagateFallbackAddresses() {
      String fallbackPolicyName = fallbackPolicy.getPolicyName();
      List<EquivalentAddressGroup> servers = fallbackServers;

      // Some addresses in the list may be grpclb-v1 balancer addresses, so if the fallback policy
      // does not support grpclb-v1 balancer addresses, then we need to exclude them from the list.
      if (!fallbackPolicyName.equals("grpclb") && !fallbackPolicyName.equals(XDS_POLICY_NAME)) {
        ImmutableList.Builder<EquivalentAddressGroup> backends = ImmutableList.builder();
        for (EquivalentAddressGroup eag : fallbackServers) {
          if (eag.getAttributes().get(GrpcAttributes.ATTR_LB_ADDR_AUTHORITY) == null) {
            backends.add(eag);
          }
        }
        servers = backends.build();
      }

      // TODO(zhangkun83): FIXME(#5496): this is a temporary hack.
      if (servers.isEmpty()
          && !fallbackBalancer.canHandleEmptyAddressListFromNameResolution()) {
        fallbackBalancer.handleNameResolutionError(Status.UNAVAILABLE.withDescription(
            "NameResolver returned no usable address."
                + " addrs=" + fallbackServers + ", attrs=" + fallbackAttributes));
      } else {
        // TODO(carl-mastrangelo): propagate the load balancing config policy
        fallbackBalancer.handleResolvedAddresses(
            ResolvedAddresses.newBuilder()
                .setAddresses(servers)
                .setAttributes(fallbackAttributes)
                .build());
      }
    }

    void startFallbackTimer() {
      if (fallbackTimer == null) {
        class FallbackTask implements Runnable {
          @Override
          public void run() {
            useFallbackPolicy();
          }
        }

        fallbackTimer = helper.getSynchronizationContext().schedule(
            new FallbackTask(), FALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS,
            helper.getScheduledExecutorService());
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
