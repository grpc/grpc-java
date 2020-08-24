/*
 * Copyright 2020 The gRPC Authors
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
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.xds.XdsSubchannelPickers.BUFFER_PICKER;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import io.grpc.ConnectivityState;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsRoutingLoadBalancerProvider.Route;
import io.grpc.xds.XdsRoutingLoadBalancerProvider.XdsRoutingConfig;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/** Load balancer for xds_routing policy. */
final class XdsRoutingLoadBalancer extends LoadBalancer {

  @VisibleForTesting
  static final int DELAYED_ACTION_DELETION_TIME_MINUTES = 15;

  private final XdsLogger logger;
  private final Helper helper;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  private final Map<String, ChildLbState> childLbStates = new HashMap<>(); // keyed by action names

  private List<Route> routes = ImmutableList.of();

  XdsRoutingLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
    this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    this.timeService = checkNotNull(helper.getScheduledExecutorService(), "timeService");
    logger = XdsLogger.withLogId(
        InternalLogId.allocate("xds-routing-lb", helper.getAuthority()));
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public void handleResolvedAddresses(final ResolvedAddresses resolvedAddresses) {
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    XdsRoutingConfig xdsRoutingConfig =
        (XdsRoutingConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    Map<String, PolicySelection> newActions = xdsRoutingConfig.actions;
    for (final String actionName : newActions.keySet()) {
      final PolicySelection action = newActions.get(actionName);
      if (!childLbStates.containsKey(actionName)) {
        childLbStates.put(actionName, new ChildLbState(actionName, action.getProvider()));
      } else {
        childLbStates.get(actionName).reactivate(action.getProvider());
      }
      final LoadBalancer childLb = childLbStates.get(actionName).lb;
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          childLb.handleResolvedAddresses(
              resolvedAddresses.toBuilder()
                  .setLoadBalancingPolicyConfig(action.getConfig())
                  .build());
        }
      });
    }
    this.routes = xdsRoutingConfig.routes;
    Set<String> diff = Sets.difference(childLbStates.keySet(), newActions.keySet());
    for (String actionName : diff) {
      childLbStates.get(actionName).deactivate();
    }
    updateOverallBalancingState();
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
    boolean gotoTransientFailure = true;
    for (ChildLbState state : childLbStates.values()) {
      if (!state.deactivated) {
        gotoTransientFailure = false;
        state.lb.handleNameResolutionError(error);
      }
    }
    if (gotoTransientFailure) {
      helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
    }
  }

  @Override
  public void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutdown");
    for (ChildLbState state : childLbStates.values()) {
      state.tearDown();
    }
  }

  @Override
  public boolean canHandleEmptyAddressListFromNameResolution() {
    return true;
  }

  private void updateOverallBalancingState() {
    ConnectivityState overallState = null;
    // Use LinkedHashMap to preserve the order of routes.
    Map<RouteMatch, SubchannelPicker> routePickers = new LinkedHashMap<>();
    for (Route route : routes) {
      ChildLbState state = childLbStates.get(route.getActionName());
      routePickers.put(route.getRouteMatch(), state.currentPicker);
      overallState = aggregateState(overallState, state.currentState);
    }
    if (overallState != null) {
      SubchannelPicker picker = new RouteMatchingSubchannelPicker(routePickers);
      helper.updateBalancingState(overallState, picker);
    }
  }

  @VisibleForTesting
  @Nullable
  static ConnectivityState aggregateState(
      @Nullable ConnectivityState overallState, ConnectivityState childState) {
    if (overallState == null) {
      return childState;
    }
    if (overallState == READY || childState == READY) {
      return READY;
    }
    if (overallState == CONNECTING || childState == CONNECTING) {
      return CONNECTING;
    }
    if (overallState == IDLE || childState == IDLE) {
      return IDLE;
    }
    return overallState;
  }

  private final class ChildLbState {
    private final String name;
    private final GracefulSwitchLoadBalancer lb;
    private LoadBalancerProvider policyProvider;
    private ConnectivityState currentState = CONNECTING;
    private SubchannelPicker currentPicker = BUFFER_PICKER;
    private boolean deactivated;
    @Nullable
    ScheduledHandle deletionTimer;

    private ChildLbState(String name, LoadBalancerProvider policyProvider) {
      this.name = name;
      this.policyProvider = policyProvider;
      lb = new GracefulSwitchLoadBalancer(new RouteHelper());
      lb.switchTo(policyProvider);
    }

    void deactivate() {
      if (deactivated) {
        return;
      }

      class DeletionTask implements Runnable {
        @Override
        public void run() {
          tearDown();
          childLbStates.remove(name);
        }
      }

      deletionTimer =
          syncContext.schedule(
              new DeletionTask(),
              DELAYED_ACTION_DELETION_TIME_MINUTES,
              TimeUnit.MINUTES,
              timeService);
      deactivated = true;
      logger.log(XdsLogLevel.DEBUG, "Route action {0} deactivated", name);
    }

    void reactivate(LoadBalancerProvider policyProvider) {
      if (deletionTimer != null && deletionTimer.isPending()) {
        deletionTimer.cancel();
        deactivated = false;
        logger.log(XdsLogLevel.DEBUG, "Route action {0} reactivated", name);
      }
      if (!this.policyProvider.getPolicyName().equals(policyProvider.getPolicyName())) {
        logger.log(
            XdsLogLevel.DEBUG,
            "Action {0} switching policy from {1} to {2}",
            name, this.policyProvider.getPolicyName(), policyProvider.getPolicyName());
        lb.switchTo(policyProvider);
        this.policyProvider = policyProvider;
      }
    }

    void tearDown() {
      deactivated = true;
      if (deletionTimer != null && deletionTimer.isPending()) {
        deletionTimer.cancel();
      }
      lb.shutdown();
      logger.log(XdsLogLevel.DEBUG, "Route action {0} deleted", name);
    }

    private final class RouteHelper extends ForwardingLoadBalancerHelper {

      @Override
      public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
        currentState = newState;
        currentPicker = newPicker;
        if (!deactivated) {
          updateOverallBalancingState();
        }
      }

      @Override
      protected Helper delegate() {
        return helper;
      }
    }
  }

  @VisibleForTesting
  static final class RouteMatchingSubchannelPicker extends SubchannelPicker {

    @VisibleForTesting
    final Map<RouteMatch, SubchannelPicker> routePickers;

    RouteMatchingSubchannelPicker(Map<RouteMatch, SubchannelPicker> routePickers) {
      this.routePickers = routePickers;
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      // Index ASCII headers by keys.
      Map<String, Iterable<String>> asciiHeaders = new HashMap<>();
      Metadata headers = args.getHeaders();
      for (String headerName : headers.keys()) {
        if (headerName.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
          continue;
        }
        Metadata.Key<String> key = Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER);
        asciiHeaders.put(headerName, headers.getAll(key));
      }
      for (Map.Entry<RouteMatch, SubchannelPicker> entry : routePickers.entrySet()) {
        RouteMatch routeMatch = entry.getKey();
        if (routeMatch.matches(
            "/" + args.getMethodDescriptor().getFullMethodName(), asciiHeaders)) {
          return entry.getValue().pickSubchannel(args);
        }
      }
      return PickResult.withError(Status.UNAVAILABLE.withDescription("no matching route found"));
    }
  }
}
