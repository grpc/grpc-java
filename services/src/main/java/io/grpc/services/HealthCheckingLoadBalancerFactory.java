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

import io.grpc.Attributes;
import io.grpc.LoadBalancer;

public final class HealthCheckingLoadBalancerFactory extends LoadBalancer.Factory {
  private static final Attributes.Key<LoadBalancerImpl.HealthCheckState> KEY_HEALTH_CHECK_STATE =
      Attributes.Key.create("io.grpc.services.HealthCheckingLoadBalancerFactory.healthCheckState");

  private final LoadBalancer.Factory delegateFactory;
  private final BackoffPolicy.Provider backoffPolicyProvider;

  public HealthCheckingLoadBalancerFactory(
      LoadBalancer.Factory delegateFactory, BackoffPolicy.Provider backoffPolicyProvider) {
    this.delegateFactory = checkNotNull(delegateFactory, "delegateFactory");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    LoadBalancer delegateBalancer = delegate.newLoadBalancer(new HelperImpl(helper));
    return new LoadBalancerImpl(
        helper.getSynchronizationContext(), helper.getScheduledExecutorService(), delegateBalancer);
  }

  private static final class HelperImpl extends ForwardingLoadBalancerHelper {
    private final LoadBalancer.Helper delegate;

    HelperImpl(LoadBalancer.Helper delegate) {
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    protected LoadBalancer.Helper delegate() {
      return delegate;
    }

    @Override
    public Subchannel createSubchannel(List<EquivalentAddressGroup> addrs, Attributes attrs) {
      HealthCheckState hcState = new HealthCheckState();
      Subchannel subchannel = super.createSubchannel(
          addrs, attrs.toBuilder().set(KEY_HEALTH_CHECK_STATE, hcState).build());
      hcState.init(subchannel);
      return subchannel;
    }
  }

  private final class LoadBalancerImpl extends ForwardingLoadBalancer {
    final LoadBalancer delegate;
    final SynchronizationContext syncContext;
    final ScheduledExecutorService timerService;

    LoadBalancerImpl(
        SynchronizationContext syncContext, ScheduledExecutorService timerService,
        LoadBalancer delegate) {
      this.syncContext = checkNotNull(syncContext, "syncContext");
      this.timerService = checkNotNull(timerService, "timerService");
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    protected LoadBalancer delegate() {
      return delegate;
    }

    @Override
    public void handleSubchannelState(
        Subchannel subchannel, ConnectivityStateInfo stateInfo) {
      HealthCheckState hcState =
          checkNotNull(subchannel.getAttributes().get(KEY_HEALTH_CHECK_STATE), "hcState");
      if (stateInfo.getState().equals(ConnectivityState.READY)) {
        hcState.makeSureHealthCheckStarted();
      } else {
        hcState.makeSureHealthCheckStopped();
        super.handleSubchannelState(subchannel, stateInfo);
      }
    }

    // All methods are run from syncContext
    private final class HealthCheckState {
      final Runnable retryTask = new Runnable() {
          @Override
          public void run() {
            startRpc();
          }
        };

      final ClientCall.Listener<HealthCheckResponse> responseListener =
          new ClientCall.Listener<>() {
            @Override
            public void onMessage(final HealthCheckResponse response) {
              syncContext.execute(new Runnable() {
                  @Override
                  public void run() {
                    handleResponse(response);
                  }
                });
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
              syncContext.execute(new Runnable() {
                  @Override
                  public void run() {
                    handleStreamClosed();
                  }
                });
            }
          };

      Subchannel subchannel;
      @Nullable
      ClientCall<HealthCheckRequest, HealthCheckResponse> activeCall;
      ConnectivityStateInfo concludedState = ConnectivityStateInfo.forNonError(IDLE);
      boolean running;
      ScheduledHandle retryTimer;

      void handleResponse(HealthCheckResponse response) {
        if (!running) {
          return;
        }
        if (response.getStatus() == ServingStatus.SERVING) {
          gotoState(ConnectivityStateInfo.forNonError(READY));
        } else {
          gotoState(
              ConnectivityStateInfo.forTransientFailure(
                  Status.UNAVAILABLE.withDescription(
                      "Health-check service responded "
                      + response.getStatus() + " for '" + service + "'")));
        }
      }

      void handleStreamClosed() {
        activeCall = null;
        if (running) {
          if (backoffPolicy == null) {
            backoffPolicy = backoffPolicyProvider.get();
          }
          delayNanos =
              prevRpcStartNanos + backoffPolicy.nextBackoffNanos() - time.currentTimeNanos();
          if (delayNanos <= 0) {
            startRpc();
          } else {
            retryTimer = syncContext.schedule(retryTask, delayNanos, TimeUnit.NANOSECONDS,
                timerService);
          }
        }
      }

      void init(Subchannel subchannel) {
        checkState(this.subchannel == null, "init() already called");
        this.subchannel = checkNotNull(subchannel, "subchannel");
      }

      void makeSureHealthCheckStarted() {
        running = true;
        if (activeCall == null) {
          gotoState(ConnectivityStateInfo.forNonError(CONNECTING));
          startRpc();
        }
      }

      void makeSureHealthCheckStopped() {
        if (activeCall != null) {
          activeCall.cancel("Client stops health check", null);
        }
        running = false;
      }

      private void startRpc() {
        checkState(activeCall == null, "previous health-checking RPC has not been cleaned up");
        checkState(subchannel != null, "init() not called");
        activeCall = subchannel.asChannel().newCall(
            HealthGrpc.getWatchMethod(), CallOptions.DEFAULT);
        activeCall.start(responseListener);
        activeCall.request(1);
      }

      private void gotoState(ConnectivityStateInfo newState) {
        checkState(subchannel != null, "init() not called");
        if (concludedState != newState) {
          concludedState = newState;
          handleChannelState(subchannel, concludedState);
        }
      }
    }
  }
}
