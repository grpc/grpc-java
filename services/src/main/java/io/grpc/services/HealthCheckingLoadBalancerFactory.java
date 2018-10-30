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

package io.grpc.services;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Factory;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
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
import io.grpc.internal.GrpcAttributes;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.internal.TimeProvider;
import io.grpc.util.ForwardingLoadBalancer;
import io.grpc.util.ForwardingLoadBalancerHelper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Wraps a {@link LoadBalancer} and implements the client-side health-checking
 * (https://github.com/grpc/proposal/blob/master/A17-client-side-health-checking.md).  The
 * Subchannel received by the states wrapped LoadBalancer will be determined by health-checking.
 *
 * <p>Note the original LoadBalancer must call {@code Helper.createSubchannel()} from the
 * SynchronizationContext, or it will throw.
 */
final class HealthCheckingLoadBalancerFactory extends Factory {
  private static final Attributes.Key<HealthCheckState> KEY_HEALTH_CHECK_STATE =
      Attributes.Key.create("io.grpc.services.HealthCheckingLoadBalancerFactory.healthCheckState");

  private final Factory delegateFactory;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final TimeProvider time;

  public HealthCheckingLoadBalancerFactory(
      Factory delegateFactory, BackoffPolicy.Provider backoffPolicyProvider, TimeProvider time) {
    this.delegateFactory = checkNotNull(delegateFactory, "delegateFactory");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    this.time = checkNotNull(time, "time");
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    HelperImpl wrappedHelper = new HelperImpl(helper);
    LoadBalancer delegateBalancer = delegateFactory.newLoadBalancer(wrappedHelper);
    wrappedHelper.init(delegateBalancer);
    return new LoadBalancerImpl(wrappedHelper, delegateBalancer);
  }

  private final class HelperImpl extends ForwardingLoadBalancerHelper {
    private final Helper delegate;
    private final SynchronizationContext syncContext;
    
    private LoadBalancer delegateBalancer;
    @Nullable String healthCheckedService;

    final HashSet<HealthCheckState> hcStates = new HashSet<HealthCheckState>();

    HelperImpl(Helper delegate) {
      this.delegate = checkNotNull(delegate, "delegate");
      this.syncContext = checkNotNull(delegate.getSynchronizationContext(), "syncContext");
    }

    void init(LoadBalancer delegateBalancer) {
      checkState(this.delegateBalancer == null, "init() already called");
      this.delegateBalancer = checkNotNull(delegateBalancer, "delegateBalancer");
    }

    @Override
    protected Helper delegate() {
      return delegate;
    }

    @Override
    public Subchannel createSubchannel(List<EquivalentAddressGroup> addrs, Attributes attrs) {
      // HealthCheckState is not thread-safe, we are requiring the original LoadBalancer calls
      // createSubchannel() from the SynchronizationContext.
      syncContext.throwIfNotInThisSynchronizationContext();
      HealthCheckState hcState = new HealthCheckState(
          delegateBalancer, syncContext, delegate.getScheduledExecutorService());
      hcStates.add(hcState);
      Subchannel subchannel = super.createSubchannel(
          addrs, attrs.toBuilder().set(KEY_HEALTH_CHECK_STATE, hcState).build());
      hcState.init(subchannel);
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

  private static final class LoadBalancerImpl extends ForwardingLoadBalancer {
    final LoadBalancer delegate;
    final HelperImpl helper;
    final SynchronizationContext syncContext;
    final ScheduledExecutorService timerService;

    LoadBalancerImpl(HelperImpl helper, LoadBalancer delegate) {
      this.helper = checkNotNull(helper, "helper");
      this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
      this.timerService = checkNotNull(helper.getScheduledExecutorService(), "timerService");
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    protected LoadBalancer delegate() {
      return delegate;
    }

    @Override
    public void handleResolvedAddressGroups(
        List<EquivalentAddressGroup> servers, Attributes attributes) {
      Map<String, Object> serviceConfig =
          attributes.get(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG);
      String serviceName = ServiceConfigUtil.getHealthCheckedServiceName(serviceConfig);
      helper.setHealthCheckedService(serviceName);
      super.handleResolvedAddressGroups(servers, attributes);
    }

    @Override
    public void handleSubchannelState(
        Subchannel subchannel, ConnectivityStateInfo stateInfo) {
      HealthCheckState hcState =
          checkNotNull(subchannel.getAttributes().get(KEY_HEALTH_CHECK_STATE), "hcState");
      hcState.updateRawState(stateInfo);

      if (Objects.equal(stateInfo.getState(), SHUTDOWN)) {
        helper.hcStates.remove(hcState);
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("delegate", delegate()).toString();
    }
  }

  
  // All methods are run from syncContext
  private final class HealthCheckState {
    private final Runnable retryTask = new Runnable() {
        @Override
        public void run() {
          startRpc();
        }
      };

    private final LoadBalancer delegate;
    private final SynchronizationContext syncContext;
    private final ScheduledExecutorService timerService;

    private Subchannel subchannel;

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
        LoadBalancer delegate, SynchronizationContext syncContext,
        ScheduledExecutorService timerService) {
      this.delegate = checkNotNull(delegate, "delegate");
      this.syncContext = checkNotNull(syncContext, "syncContext");
      this.timerService = checkNotNull(timerService, "timerService");
    }

    void init(Subchannel subchannel) {
      checkState(this.subchannel == null, "init() already called");
      this.subchannel = checkNotNull(subchannel, "subchannel");
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

    void updateRawState(ConnectivityStateInfo rawState) {
      if (Objects.equal(this.rawState.getState(), READY)
          && !Objects.equal(rawState.getState(), READY)) {
        // A connection was lost.  We will reset disabled flag because health check
        // may be available on the new connection.
        disabled = false;
        // TODO(zhangkun83): record this to channel tracer
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
      checkState(activeRpc == null, "previous health-checking RPC has not been cleaned up");
      checkState(subchannel != null, "init() not called");
      gotoState(ConnectivityStateInfo.forNonError(CONNECTING));
      activeRpc = new HcStream();
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
        delegate.handleSubchannelState(subchannel, concludedState);
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
      private String callServiceName;
      private long callStartNanos;
      private boolean callHasResponded;

      HcStream() {
        callStartNanos = time.currentTimeNanos();
        call = subchannel.asChannel().newCall(HealthGrpc.getWatchMethod(), CallOptions.DEFAULT);
        callServiceName = serviceName;
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
        // running == true means the Subchannel's state (rawState) is READY
        if (Objects.equal(response.getStatus(), ServingStatus.SERVING)) {
          gotoState(ConnectivityStateInfo.forNonError(READY));
        } else {
          gotoState(
              ConnectivityStateInfo.forTransientFailure(
                  Status.UNAVAILABLE.withDescription(
                      "Health-check service responded "
                      + response.getStatus() + " for '" + callServiceName + "'")));
        }
        call.request(1);
      }

      void handleStreamClosed(Status status) {
        if (Objects.equal(status.getCode(), Code.UNIMPLEMENTED)) {
          // TODO(zhangkun83): record this to channel tracer
          disabled = true;
          gotoState(rawState);
          return;
        }
        long delayNanos = 0;
        gotoState(
            ConnectivityStateInfo.forTransientFailure(
                Status.UNAVAILABLE.withDescription(
                    "Health-check stream unexpectedly closed with "
                    + status + " for '" + callServiceName + "'")));
        // Use backoff only when server has not responded for the previous call
        if (!callHasResponded) {
          if (backoffPolicy == null) {
            backoffPolicy = backoffPolicyProvider.get();
          }
          delayNanos =
              callStartNanos + backoffPolicy.nextBackoffNanos() - time.currentTimeNanos();
        }
        if (delayNanos <= 0) {
          startRpc();
        } else {
          checkState(!isRetryTimerPending(), "Retry double scheduled");
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
