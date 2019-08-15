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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.util.ForwardingLoadBalancerHelper;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

/**
 * A {@link LoadBalancer} that uses the XDS protocol.
 *
 * <p>This class manages fallback handling. The logic for child policy handling and fallback policy
 * handling is provided by LookasideLb and FallbackLb.
 */
// TODO(zdapeng): migrate name to XdsLoadBlancer
final class XdsLoadBalancer2 extends LoadBalancer {

  private static final long FALLBACK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10); // same as grpclb

  private final Helper helper;
  private final LoadBalancer lookasideLb;
  private final LoadBalancer.Factory fallbackLbFactory;
  private final AdsCallback adsCallback = new AdsCallback() {
    @Override
    public void onWorking() {
      if (childPolicyHasBeenReady) {
        // cancel Fallback-After-Startup timer if there's any
        cancelFallbackTimer();
      }

      childBalancerWorked = true;
    }

    @Override
    public void onError() {
      if (!childBalancerWorked) {
        // start Fallback-at-Startup immediately
        useFallbackPolicy();
      } else if (childPolicyHasBeenReady) {
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
  // Scheduled only once.  Never reset.
  @CheckForNull
  private ScheduledHandle fallbackTimer;
  private boolean childBalancerWorked;
  private boolean childPolicyHasBeenReady;

  // TODO(zdapeng): Add XdsLoadBalancer2(Helper helper) with default factories
  @VisibleForTesting
  XdsLoadBalancer2(
      Helper helper,
      LookasideLbFactory lookasideLbFactory,
      LoadBalancer.Factory fallbackLbFactory) {
    this.helper = helper;
    this.lookasideLb = lookasideLbFactory.newLoadBalancer(new LookasideLbHelper(), adsCallback);
    this.fallbackLbFactory = fallbackLbFactory;
  }

  @Override
  public boolean canHandleEmptyAddressListFromNameResolution() {
    return true;
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    this.resolvedAddresses = resolvedAddresses;

    if (isInFallbackMode()) {
      fallbackLb.handleResolvedAddresses(this.resolvedAddresses);
    }

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

    lookasideLb.handleResolvedAddresses(resolvedAddresses);
  }

  @Override
  public void handleNameResolutionError(Status error) {
    lookasideLb.handleNameResolutionError(error);
    if (isInFallbackMode()) {
      fallbackLb.handleNameResolutionError(error);
    }
  }

  @Override
  public void requestConnection() {
    lookasideLb.requestConnection();
    if (isInFallbackMode()) {
      fallbackLb.requestConnection();
    }
  }

  @Override
  public void shutdown() {
    helper.getChannelLogger().log(
        ChannelLogLevel.INFO, "Shutting down XDS balancer");
    lookasideLb.shutdown();
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

  private final class LookasideLbHelper extends ForwardingLoadBalancerHelper {

    @Override
    protected Helper delegate() {
      return helper;
    }

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
      if (newState == ConnectivityState.READY) {
        checkState(
            childBalancerWorked,
            "channel goes to READY before the load balancer even worked");
        childPolicyHasBeenReady = true;
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
      checkNotNull(balancer, "there is a bug");
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

  /** Factory of a look-aside load balancer. The interface itself is for convenience in test. */
  @VisibleForTesting
  interface LookasideLbFactory {
    LoadBalancer newLoadBalancer(Helper helper, AdsCallback adsCallback);
  }

  /**
   * Callback on ADS stream events. The callback methods should be called in a proper {@link
   * io.grpc.SynchronizationContext}.
   */
  interface AdsCallback {

    /**
     * Once the response observer receives the first response.
     */
    void onWorking();

    /**
     * Once an error occurs in ADS stream.
     */
    void onError();

    /**
     * Once receives a response indicating that 100% of calls should be dropped.
     */
    void onAllDrop();
  }
}
