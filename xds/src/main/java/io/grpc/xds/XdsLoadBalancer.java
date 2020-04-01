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
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.Attributes;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.EdsLoadBalancer.ResourceUpdateCallback;
import io.grpc.xds.EdsLoadBalancerProvider.EdsConfig;
import io.grpc.xds.XdsClient.RefCountedXdsClientObjectPool;
import io.grpc.xds.XdsClient.XdsChannelFactory;
import io.grpc.xds.XdsClient.XdsClientFactory;
import io.grpc.xds.XdsLoadBalancerProvider.XdsConfig;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

/**
 * A {@link LoadBalancer} that uses the XDS protocol.
 *
 * <p>This class manages fallback handling. The logic for child policy handling and fallback policy
 * handling is provided by EdsLoadBalancer and FallbackLb.
 */
final class XdsLoadBalancer extends LoadBalancer {

  private static final long FALLBACK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10); // same as grpclb

  private final Helper helper;
  private final String authority;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  private final LoadBalancer primaryLb;
  private final LoadBalancer.Factory fallbackLbFactory;
  private final ObjectPool<XdsClient> xdsClientPool;
  private final ResourceUpdateCallback resourceUpdateCallback = new ResourceUpdateCallback() {
    @Override
    public void onWorking() {
      if (primaryPolicyHasBeenReady) {
        // cancel Fallback-After-Startup timer if there's any
        cancelFallbackTimer();
      }

      primaryPolicyWorked = true;
    }

    @Override
    public void onError() {
      if (!primaryPolicyWorked) {
        // start Fallback-at-Startup immediately
        useFallbackPolicy();
      } else if (primaryPolicyHasBeenReady) {
        // TODO: schedule a timer for Fallback-After-Startup
      } // else: the Fallback-at-Startup timer is still pending, noop and wait
    }

    @Override
    public void onAllDrop() {
      cancelFallback();
    }
  };

  @Nullable
  private LoadBalancer fallbackLb;
  @Nullable
  private ResolvedAddresses resolvedAddresses;
  // Scheduled only once.  Never reset to null.
  @CheckForNull
  private ScheduledHandle fallbackTimer;
  private boolean primaryPolicyWorked;
  private boolean primaryPolicyHasBeenReady;

  XdsLoadBalancer(Helper helper) {
    this(helper, new EdsLoadBalancerFactory(), new FallbackLbFactory());
  }

  @VisibleForTesting
  XdsLoadBalancer(
      Helper helper,
      PrimaryLbFactory primaryLbFactory,
      LoadBalancer.Factory fallbackLbFactory) {
    this.helper = checkNotNull(helper, "helper");
    this.authority = checkNotNull(helper.getAuthority(), "authority");
    this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    this.timeService = checkNotNull(helper.getScheduledExecutorService(), "timeService");
    this.primaryLb =
        checkNotNull(primaryLbFactory, "primaryLbFactory")
            .newLoadBalancer(new PrimaryLbHelper(), resourceUpdateCallback);
    this.fallbackLbFactory = checkNotNull(fallbackLbFactory, "fallbackLbFactory");
    xdsClientPool =
        createXdsClientPool(Bootstrapper.getInstance(), XdsChannelFactory.getInstance());
  }

  @Nullable
  private ObjectPool<XdsClient> createXdsClientPool(
      Bootstrapper bootstrapper, final XdsChannelFactory channelFactory) {
    BootstrapInfo bootstrapInfo;
    try {
      bootstrapInfo = bootstrapper.readBootstrap();
    } catch (Exception e) {
      helper.updateBalancingState(
          TRANSIENT_FAILURE,
          new ErrorPicker(
              Status.UNAVAILABLE.withDescription("Failed to bootstrap").withCause(e)));
      return null;
    }

    final List<ServerInfo> serverList = bootstrapInfo.getServers();
    final Node node = bootstrapInfo.getNode();
    if (serverList.isEmpty()) {
      helper.updateBalancingState(
          TRANSIENT_FAILURE,
          new ErrorPicker(
              Status.UNAVAILABLE
                  .withDescription("No management server provided by bootstrap")));
      return null;
    }
    XdsClientFactory xdsClientFactory = new XdsClientFactory() {
      @Override
      XdsClient createXdsClient() {
        return
            new XdsClientImpl(
                authority,
                serverList,
                channelFactory,
                node,
                syncContext,
                timeService,
                new ExponentialBackoffPolicy.Provider(),
                GrpcUtil.STOPWATCH_SUPPLIER);
      }
    };
    return new RefCountedXdsClientObjectPool(xdsClientFactory);
  }

  @Override
  public boolean canHandleEmptyAddressListFromNameResolution() {
    // This does not sound correct, but it's fine as we don't support fallback at this moment. 
    // TODO(zdapeng): revisit it once we officially support fallback.
    return true;
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    this.resolvedAddresses = resolvedAddresses;
    Object lbConfig = resolvedAddresses.getLoadBalancingPolicyConfig();
    if (lbConfig == null) {
      helper.updateBalancingState(
          TRANSIENT_FAILURE,
          new ErrorPicker(Status.UNAVAILABLE.withDescription("Missing xDS lb config")));
      return;
    }
    if (xdsClientPool == null) {
      useFallbackPolicy();
      return;
    }
    XdsConfig newXdsConfig = (XdsConfig) lbConfig;
    EdsConfig edsConfig =
        new EdsConfig(
            authority,
            newXdsConfig.edsServiceName,
            newXdsConfig.lrsServerName,
            newXdsConfig.childPolicy);
    primaryLb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(
                Attributes.newBuilder().set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool).build())
            .setLoadBalancingPolicyConfig(edsConfig)
            .build());
    if (isInFallbackMode()) {
      fallbackLb.handleResolvedAddresses(this.resolvedAddresses);
    } else {
      class EnterFallbackTask implements Runnable {
        @Override
        public void run() {
          useFallbackPolicy();
        }
      }

      fallbackTimer =
          syncContext.schedule(
              new EnterFallbackTask(),
              FALLBACK_TIMEOUT_MS,
              TimeUnit.MILLISECONDS,
              timeService);
    }
  }

  @Override
  public void handleNameResolutionError(Status error) {
    primaryLb.handleNameResolutionError(error);
    if (isInFallbackMode()) {
      fallbackLb.handleNameResolutionError(error);
    }
  }

  @Override
  public void requestConnection() {
    primaryLb.requestConnection();
    if (isInFallbackMode()) {
      fallbackLb.requestConnection();
    }
  }

  @Override
  public void shutdown() {
    helper.getChannelLogger().log(
        ChannelLogLevel.INFO, "Shutting down XDS balancer");
    primaryLb.shutdown();
    cancelFallback();
  }

  @Deprecated
  public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
    throw new UnsupportedOperationException(
        "handleSubchannelState() not supported by XdsLoadBalancer");
  }

  private void cancelFallbackTimer() {
    if (fallbackTimer != null) {
      fallbackTimer.cancel();
    }
  }

  private void cancelFallback() {
    cancelFallbackTimer();
    if (isInFallbackMode()) {
      helper.getChannelLogger().log(
          ChannelLogLevel.INFO, "Shutting down XDS fallback balancer");
      fallbackLb.shutdown();
      fallbackLb = null;
    }
  }

  private void useFallbackPolicy() {
    if (isInFallbackMode()) {
      return;
    }
    cancelFallbackTimer();
    helper.getChannelLogger().log(
        ChannelLogLevel.INFO, "Using XDS fallback policy");

    FallbackLbHelper fallbackLbHelper = new FallbackLbHelper();
    fallbackLb = fallbackLbFactory.newLoadBalancer(fallbackLbHelper);
    fallbackLbHelper.balancer = fallbackLb;
    fallbackLb.handleResolvedAddresses(resolvedAddresses);
  }

  /**
   * Fallback mode being on indicates that an update from child LBs will be ignored unless the
   * update triggers turning off the fallback mode first.
   */
  private boolean isInFallbackMode() {
    return fallbackLb != null;
  }

  private final class PrimaryLbHelper extends ForwardingLoadBalancerHelper {

    @Override
    protected Helper delegate() {
      return helper;
    }

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
      if (newState == ConnectivityState.READY) {
        checkState(
            primaryPolicyWorked,
            "channel goes to READY before the load balancer even worked");
        primaryPolicyHasBeenReady = true;
        cancelFallback();
      }
      if (!isInFallbackMode()) {
        helper.getChannelLogger().log(
            ChannelLogLevel.INFO, "Picker updated - state: {0}, picker: {1}", newState, newPicker);
        helper.updateBalancingState(newState, newPicker);
      }
    }
  }

  private final class FallbackLbHelper extends ForwardingLoadBalancerHelper {
    LoadBalancer balancer;

    @Override
    protected Helper delegate() {
      return helper;
    }

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
      checkNotNull(balancer, "balancer not set yet");
      if (balancer != fallbackLb) {
        // ignore updates from a misbehaving shutdown fallback balancer
        return;
      }
      helper.getChannelLogger().log(
          ChannelLogLevel.INFO,
          "Picker updated - state: {0}, picker: {1}", newState, newPicker);
      super.updateBalancingState(newState, newPicker);
    }
  }

  /** Factory of load balancer for the primary policy.*/
  // The interface itself is for convenience in test.
  @VisibleForTesting
  interface PrimaryLbFactory {
    LoadBalancer newLoadBalancer(Helper helper, ResourceUpdateCallback resourceUpdateCallback);
  }

  private static final class EdsLoadBalancerFactory implements PrimaryLbFactory {
    @Override
    public LoadBalancer newLoadBalancer(
        Helper edsLbHelper, ResourceUpdateCallback resourceUpdateCallback) {
      return new EdsLoadBalancer(edsLbHelper, resourceUpdateCallback);
    }
  }

  private static final class FallbackLbFactory extends LoadBalancer.Factory {
    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      return new FallbackLb(helper);
    }
  }
}
