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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.xds.LookasideLb.EndpointUpdateCallback;
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
  private final EndpointUpdateCallback edsUpdateCallback = new EndpointUpdateCallback() {
    @Override
    public void onWorking() {
      if (mainPolicyHasBeenReady) {
        // cancel Fallback-After-Startup timer if there's any
        cancelFallbackTimer();
      }

      mainPolicyWorked = true;
    }

    @Override
    public void onError(Status error) {
      checkArgument(!error.isOk(), "Error code must not be OK");
      if (!mainPolicyWorked) {
        // start Fallback-at-Startup immediately
        useFallbackPolicy(Status.UNAVAILABLE.withCause(error.asRuntimeException()).withDescription(
            "No endpoint update had been received when encountering an error from the main LB"
                + " policy. The balancer entered fallback mode because of this error."));
      } else if (mainPolicyHasBeenReady) {
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
  private boolean mainPolicyWorked;
  private boolean mainPolicyHasBeenReady;

  XdsLoadBalancer2(Helper helper) {
    this(helper, new LookasideLbFactoryImpl(), new FallbackLbFactory());
  }

  @VisibleForTesting
  XdsLoadBalancer2(
      Helper helper,
      LookasideLbFactory lookasideLbFactory,
      LoadBalancer.Factory fallbackLbFactory) {
    this.helper = helper;
    this.lookasideLb = lookasideLbFactory.newLoadBalancer(new LookasideLbHelper(),
        edsUpdateCallback);
    this.fallbackLbFactory = fallbackLbFactory;
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

    if (isInFallbackMode()) {
      fallbackLb.handleResolvedAddresses(this.resolvedAddresses);
    }

    if (fallbackTimer == null) {
      class EnterFallbackTask implements Runnable {

        @Override
        public void run() {
          useFallbackPolicy(Status.UNAVAILABLE.withDescription(
              "Channel was not ready when timeout for entering fallback mode expired."));
        }
      }

      fallbackTimer = helper.getSynchronizationContext().schedule(
          new EnterFallbackTask(), FALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS,
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

  private void useFallbackPolicy(Status fallbackReason) {
    if (isInFallbackMode()) {
      return;
    }
    cancelFallbackTimer();
    helper.getChannelLogger().log(
        ChannelLogLevel.INFO, "Using XDS fallback policy");

    FallbackLbHelper fallbackLbHelper = new FallbackLbHelper(fallbackReason);
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
            mainPolicyWorked,
            "Channel goes to READY before the load balancer even worked");
        mainPolicyHasBeenReady = true;
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
    final Status fallbackReason;
    LoadBalancer balancer;

    FallbackLbHelper(Status fallbackReason) {
      this.fallbackReason = fallbackReason;
    }

    @Override
    protected Helper delegate() {
      return helper;
    }

    @Override
    public void updateBalancingState(ConnectivityState newState, final SubchannelPicker newPicker) {
      checkNotNull(balancer, "balancer not set yet");
      if (balancer != fallbackLb) {
        // ignore updates from a misbehaving shutdown fallback balancer
        return;
      }

      SubchannelPicker picker = new SubchannelPicker() {
        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
          PickResult pickResult = newPicker.pickSubchannel(args);
          Status status = pickResult.getStatus();
          if (status.isOk()) {
            return pickResult;
          }

          Exception cause = fallbackReason.asRuntimeException();
          cause.addSuppressed(status.asRuntimeException());
          return PickResult.withError(
              Status.fromCode(fallbackReason.getCode())
                  .withDescription("The main LB policy had been not working and the fallback LB"
                      + " policy also failed.")
                  .withCause(cause));
        }

        @Override
        public String toString() {
          return MoreObjects.toStringHelper("FallbackPicker")
              .add("fallbackReason", fallbackReason)
              .add("picker", newPicker)
              .toString();
        }
      };

      helper.getChannelLogger().log(
          ChannelLogLevel.INFO,
          "Picker updated - state: {0}, picker: {1}", newState, picker);
      super.updateBalancingState(newState, picker);
    }
  }

  /** Factory of a look-aside load balancer. The interface itself is for convenience in test. */
  @VisibleForTesting
  interface LookasideLbFactory {
    LoadBalancer newLoadBalancer(Helper helper, EndpointUpdateCallback edsUpdateCallback);
  }

  private static final class LookasideLbFactoryImpl implements LookasideLbFactory {
    @Override
    public LoadBalancer newLoadBalancer(
        Helper lookasideLbHelper, EndpointUpdateCallback edsUpdateCallback) {
      return new LookasideLb(lookasideLbHelper, edsUpdateCallback);
    }
  }

  private static final class FallbackLbFactory extends LoadBalancer.Factory {
    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      return new FallbackLb(helper);
    }
  }
}
