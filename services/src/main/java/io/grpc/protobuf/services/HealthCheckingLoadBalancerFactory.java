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

package io.grpc.protobuf.services;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.LoadBalancer.HEALTH_CONSUMER_LISTENER_ARG_KEY;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import io.grpc.CallOptions;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ClientCall;
import io.grpc.ConnectivityStateInfo;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.util.ForwardingLoadBalancer;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.ForwardingSubchannel;
import io.grpc.util.HealthProducerHelper;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Wraps a {@link LoadBalancer} and implements the client-side health-checking
 * (https://github.com/grpc/proposal/blob/master/A17-client-side-health-checking.md).  The
 * Subchannel received by the states wrapped LoadBalancer will be determined by health-checking.
 *
 * <p>Note the original LoadBalancer must call {@code Helper.createSubchannel()} from the
 * SynchronizationContext, or it will throw.
 */
final class HealthCheckingLoadBalancerFactory extends LoadBalancer.Factory {
  private static final Logger logger =
      Logger.getLogger(HealthCheckingLoadBalancerFactory.class.getName());

  private final LoadBalancer.Factory delegateFactory;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final Supplier<Stopwatch> stopwatchSupplier;

  public HealthCheckingLoadBalancerFactory(
      LoadBalancer.Factory delegateFactory, BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier) {
    this.delegateFactory = checkNotNull(delegateFactory, "delegateFactory");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    HelperImpl wrappedHelper = new HelperImpl(helper);
    LoadBalancer delegateBalancer = delegateFactory.newLoadBalancer(wrappedHelper);
    return new HealthCheckingLoadBalancer(wrappedHelper, delegateBalancer);
  }

  private final class HelperImpl extends ForwardingLoadBalancerHelper {
    private final Helper delegate;
    private final SynchronizationContext syncContext;

    @Nullable String healthCheckedService;

    final HashSet<HealthCheckState> hcStates = new HashSet<>();

    HelperImpl(Helper delegate) {
      this.delegate = new HealthProducerHelper(checkNotNull(delegate, "delegate"));
      this.syncContext = checkNotNull(delegate.getSynchronizationContext(), "syncContext");
    }

    @Override
    protected Helper delegate() {
      return delegate;
    }

    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs args) {
      // HealthCheckState is not thread-safe, we are requiring the original LoadBalancer calls
      // createSubchannel() from the SynchronizationContext.
      syncContext.throwIfNotInThisSynchronizationContext();
      LoadBalancer.SubchannelStateListener healthConsumerListener =
          args.getOption(HEALTH_CONSUMER_LISTENER_ARG_KEY);
      HealthCheckState hcState = new HealthCheckState(
          this, syncContext, delegate.getScheduledExecutorService(), healthConsumerListener);
      if (healthConsumerListener != null) {
        args = args.toBuilder().addOption(HEALTH_CONSUMER_LISTENER_ARG_KEY, hcState).build();
      }
      Subchannel delegate = super.createSubchannel(args);
      hcState.setSubchannel(delegate);
      hcStates.add(hcState);
      Subchannel subchannel = new SubchannelImpl(delegate, hcState);
      if (healthCheckedService != null) {
        hcState.setServiceName(healthCheckedService);
      }
      return subchannel;
    }

    void setHealthCheckedService(@Nullable String service) {
      healthCheckedService = service;
      for (HealthCheckState hcState : hcStates) {
        hcState.setServiceName(service);
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("delegate", delegate()).toString();
    }
  }

  @VisibleForTesting
  static final class SubchannelImpl extends ForwardingSubchannel {
    final Subchannel delegate;
    final HealthCheckState hcState;

    SubchannelImpl(Subchannel delegate, HealthCheckState hcState) {
      this.delegate = checkNotNull(delegate, "delegate");
      this.hcState = checkNotNull(hcState, "hcState");
    }

    @Override
    protected Subchannel delegate() {
      return delegate;
    }

    @Override
    public void start(final SubchannelStateListener listener) {
      if (hcState.stateListener == null) {
        hcState.init(listener);
        delegate().start(hcState);
      } else {
        delegate().start(listener);
      }
    }
  }

  private static final class HealthCheckingLoadBalancer extends ForwardingLoadBalancer {
    final LoadBalancer delegate;
    final HelperImpl helper;

    HealthCheckingLoadBalancer(HelperImpl helper, LoadBalancer delegate) {
      this.helper = checkNotNull(helper, "helper");
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    protected LoadBalancer delegate() {
      return delegate;
    }

    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      Map<String, ?> healthCheckingConfig =
          resolvedAddresses
              .getAttributes()
              .get(LoadBalancer.ATTR_HEALTH_CHECKING_CONFIG);
      String serviceName = ServiceConfigUtil.getHealthCheckedServiceName(healthCheckingConfig);
      helper.setHealthCheckedService(serviceName);
      delegate.handleResolvedAddresses(resolvedAddresses);
    }

    @Override
    public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      Map<String, ?> healthCheckingConfig =
          resolvedAddresses
              .getAttributes()
              .get(LoadBalancer.ATTR_HEALTH_CHECKING_CONFIG);
      String serviceName = ServiceConfigUtil.getHealthCheckedServiceName(healthCheckingConfig);
      helper.setHealthCheckedService(serviceName);
      return delegate.acceptResolvedAddresses(resolvedAddresses);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("delegate", delegate()).toString();
    }
  }

  
  // All methods are run from syncContext
  private final class HealthCheckState implements SubchannelStateListener {
    private final Runnable retryTask = new Runnable() {
        @Override
        public void run() {
          startRpc();
        }
      };

    private final SynchronizationContext syncContext;
    private final ScheduledExecutorService timerService;
    private final HelperImpl helperImpl;
    private Subchannel subchannel;
    private ChannelLogger subchannelLogger;

    // In dual stack new pick first, this becomes health listener from create subchannel args.
    private SubchannelStateListener stateListener;

    // Set when RPC started. Cleared when the RPC has closed or abandoned.
    @Nullable
    private HcStream activeRpc;

    // The service name that should be used for health checking
    private String serviceName;
    private BackoffPolicy backoffPolicy;
    // The state from the underlying Subchannel
    private ConnectivityStateInfo rawState = ConnectivityStateInfo.forNonError(IDLE);
    // The state concluded from health checking
    private ConnectivityStateInfo concludedState = ConnectivityStateInfo.forNonError(IDLE);
    // true if a health check stream should be kept.  When true, either there is an active RPC, or a
    // retry is pending.
    private boolean running;
    // true if server returned UNIMPLEMENTED
    private boolean disabled;
    private ScheduledHandle retryTimer;

    HealthCheckState(
        HelperImpl helperImpl, SynchronizationContext syncContext,
        ScheduledExecutorService timerService,
        @Nullable SubchannelStateListener healthListener) {
      this.helperImpl = checkNotNull(helperImpl, "helperImpl");
      this.syncContext = checkNotNull(syncContext, "syncContext");
      this.timerService = checkNotNull(timerService, "timerService");
      this.stateListener = healthListener;
    }

    void setSubchannel(Subchannel subchannel) {
      this.subchannel = checkNotNull(subchannel, "subchannel");
      this.subchannelLogger = checkNotNull(subchannel.getChannelLogger(), "subchannelLogger");
    }

    // Only called in old pick first. Can be removed after migration.
    void init(SubchannelStateListener listener) {
      checkState(this.stateListener == null, "init() already called");
      this.stateListener = checkNotNull(listener, "listener");
    }

    void setServiceName(@Nullable String newServiceName) {
      if (Objects.equal(newServiceName, serviceName)) {
        return;
      }
      serviceName = newServiceName;
      // If service name has changed while there is active RPC, cancel it so that
      // a new call will be made with the new name.
      String cancelMsg =
          serviceName == null ? "Health check disabled by service config"
          : "Switching to new service name: " + newServiceName;
      stopRpc(cancelMsg);
      adjustHealthCheck();
    }

    @Override
    public void onSubchannelState(ConnectivityStateInfo rawState) {
      if (Objects.equal(this.rawState.getState(), READY)
          && !Objects.equal(rawState.getState(), READY)) {
        // A connection was lost.  We will reset disabled flag because health check
        // may be available on the new connection.
        disabled = false;
      }
      if (Objects.equal(rawState.getState(), SHUTDOWN)) {
        helperImpl.hcStates.remove(this);
      }
      this.rawState = rawState;
      adjustHealthCheck();
    }

    private boolean isRetryTimerPending() {
      return retryTimer != null && retryTimer.isPending();
    }

    // Start or stop health check according to the current states.
    private void adjustHealthCheck() {
      if (!disabled && serviceName != null && Objects.equal(rawState.getState(), READY)) {
        running = true;
        if (activeRpc == null && !isRetryTimerPending()) {
          startRpc();
        }
      } else {
        running = false;
        // Prerequisites for health checking not met.
        // Make sure it's stopped.
        stopRpc("Client stops health check");
        backoffPolicy = null;
        gotoState(rawState);
      }
    }

    private void startRpc() {
      checkState(serviceName != null, "serviceName is null");
      checkState(activeRpc == null, "previous health-checking RPC has not been cleaned up");
      checkState(subchannel != null, "init() not called");
      // Optimization suggested by @markroth: if we are already READY and starting the health
      // checking RPC, either because health check is just enabled or has switched to a new service
      // name, we don't go to CONNECTING, otherwise there will be artificial delays on RPCs
      // waiting for the health check to respond.
      if (!Objects.equal(concludedState.getState(), READY)) {
        subchannelLogger.log(
            ChannelLogLevel.INFO, "CONNECTING: Starting health-check for \"{0}\"", serviceName);
        gotoState(ConnectivityStateInfo.forNonError(CONNECTING));
      }
      activeRpc = new HcStream();
      activeRpc.start();
    }

    private void stopRpc(String msg) {
      if (activeRpc != null) {
        activeRpc.cancel(msg);
        // Abandon this RPC.  We are not interested in anything from this RPC any more.
        activeRpc = null;
      }
      if (retryTimer != null) {
        retryTimer.cancel();
        retryTimer = null;
      }
    }

    private void gotoState(ConnectivityStateInfo newState) {
      checkState(subchannel != null, "init() not called");
      if (!Objects.equal(concludedState, newState)) {
        concludedState = newState;
        stateListener.onSubchannelState(concludedState);
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("running", running)
          .add("disabled", disabled)
          .add("activeRpc", activeRpc)
          .add("serviceName", serviceName)
          .add("rawState", rawState)
          .add("concludedState", concludedState)
          .toString();
    }

    private class HcStream extends ClientCall.Listener<HealthCheckResponse> {
      private final ClientCall<HealthCheckRequest, HealthCheckResponse> call;
      private final String callServiceName;
      private final Stopwatch stopwatch;
      private boolean callHasResponded;

      HcStream() {
        stopwatch = stopwatchSupplier.get().start();
        callServiceName = serviceName;
        call = subchannel.asChannel().newCall(HealthGrpc.getWatchMethod(), CallOptions.DEFAULT);
      }

      void start() {
        call.start(this, new Metadata());
        call.sendMessage(HealthCheckRequest.newBuilder().setService(serviceName).build());
        call.halfClose();
        call.request(1);
      }

      void cancel(String msg) {
        call.cancel(msg, null);
      }

      @Override
      public void onMessage(final HealthCheckResponse response) {
        syncContext.execute(new Runnable() {
            @Override
            public void run() {
              if (activeRpc == HcStream.this) {
                handleResponse(response);
              }
            }
          });
      }

      @Override
      public void onClose(final Status status, Metadata trailers) {
        syncContext.execute(new Runnable() {
            @Override
            public void run() {
              if (activeRpc == HcStream.this) {
                activeRpc = null;
                handleStreamClosed(status);
              }
            }
          });
      }

      void handleResponse(HealthCheckResponse response) {
        callHasResponded = true;
        backoffPolicy = null;
        ServingStatus status = response.getStatus();
        // running == true means the Subchannel's state (rawState) is READY
        if (Objects.equal(status, ServingStatus.SERVING)) {
          subchannelLogger.log(ChannelLogLevel.INFO, "READY: health-check responded SERVING");
          gotoState(ConnectivityStateInfo.forNonError(READY));
        } else {
          subchannelLogger.log(
              ChannelLogLevel.INFO, "TRANSIENT_FAILURE: health-check responded {0}", status);
          String errorDescription =  "Health-check service responded "
              + status + " for '" + callServiceName + "'";
          gotoState(ConnectivityStateInfo.forTransientFailure(
              Status.UNAVAILABLE.withDescription(errorDescription)));
        }
        call.request(1);
      }

      void handleStreamClosed(Status status) {
        if (Objects.equal(status.getCode(), Code.UNIMPLEMENTED)) {
          disabled = true;
          logger.log(
              Level.SEVERE, "Health-check with {0} is disabled. Server returned: {1}",
              new Object[] {subchannel.getAllAddresses(), status});
          subchannelLogger.log(ChannelLogLevel.ERROR, "Health-check disabled: {0}", status);
          subchannelLogger.log(ChannelLogLevel.INFO, "{0} (no health-check)", rawState);
          gotoState(rawState);
          return;
        }
        long delayNanos = 0;
        subchannelLogger.log(
            ChannelLogLevel.INFO, "TRANSIENT_FAILURE: health-check stream closed with {0}", status);
        String errorDescription = "Health-check stream unexpectedly closed with "
            + status + " for '" + callServiceName + "'";
        gotoState(ConnectivityStateInfo.forTransientFailure(
            Status.UNAVAILABLE.withDescription(errorDescription)));
        // Use backoff only when server has not responded for the previous call
        if (!callHasResponded) {
          if (backoffPolicy == null) {
            backoffPolicy = backoffPolicyProvider.get();
          }
          delayNanos = backoffPolicy.nextBackoffNanos() - stopwatch.elapsed(TimeUnit.NANOSECONDS);
        }
        if (delayNanos <= 0) {
          startRpc();
        } else {
          checkState(!isRetryTimerPending(), "Retry double scheduled");
          subchannelLogger.log(
              ChannelLogLevel.DEBUG, "Will retry health-check after {0} ns", delayNanos);
          retryTimer = syncContext.schedule(
              retryTask, delayNanos, TimeUnit.NANOSECONDS, timerService);
        }
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("callStarted", call != null)
            .add("serviceName", callServiceName)
            .add("hasResponded", callHasResponded)
            .toString();
      }
    }
  }
}
