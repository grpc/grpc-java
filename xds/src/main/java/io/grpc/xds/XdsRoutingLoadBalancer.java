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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.ConnectivityState;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsRoutingLoadBalancerProvider.MethodName;
import io.grpc.xds.XdsRoutingLoadBalancerProvider.Route;
import io.grpc.xds.XdsRoutingLoadBalancerProvider.XdsRoutingConfig;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Load balancer for xds_routing policy. */
final class XdsRoutingLoadBalancer extends LoadBalancer {

  private final XdsLogger logger;
  private final Helper helper;
  private final Map<String, GracefulSwitchLoadBalancer> routeBalancers = new HashMap<>();
  private final Map<String, RouteHelper> routeHelpers = new HashMap<>();

  private Map<String, PolicySelection> actions = ImmutableMap.of();
  private List<Route> routes = ImmutableList.of();

  XdsRoutingLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
    logger = XdsLogger.withLogId(
        InternalLogId.allocate("xds-routing-lb", helper.getAuthority()));
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    XdsRoutingConfig xdsRoutingConfig =
        (XdsRoutingConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    checkNotNull(xdsRoutingConfig, "Missing xds_routing lb config");

    Map<String, PolicySelection> newActions = xdsRoutingConfig.actions;
    for (String actionName : newActions.keySet()) {
      PolicySelection action = newActions.get(actionName);
      if (!actions.containsKey(actionName)) {
        RouteHelper routeHelper = new RouteHelper();
        GracefulSwitchLoadBalancer routeBalancer = new GracefulSwitchLoadBalancer(routeHelper);
        routeBalancer.switchTo(action.getProvider());
        routeHelpers.put(actionName, routeHelper);
        routeBalancers.put(actionName, routeBalancer);
      } else if (!action.getProvider().equals(actions.get(actionName).getProvider())) {
        routeBalancers.get(actionName).switchTo(action.getProvider());
      }
    }

    this.routes = xdsRoutingConfig.routes;
    this.actions = newActions;

    for (String actionName : actions.keySet()) {
      routeBalancers.get(actionName).handleResolvedAddresses(
          resolvedAddresses.toBuilder()
              .setLoadBalancingPolicyConfig(actions.get(actionName).getConfig())
              .build());
    }

    // Cleanup removed actions.
    // TODO(zdapeng): cache removed actions for 15 minutes.
    for (String actionName : routeBalancers.keySet()) {
      if (!actions.containsKey(actionName)) {
        routeBalancers.get(actionName).shutdown();
      }
    }
    routeBalancers.keySet().retainAll(actions.keySet());
    routeHelpers.keySet().retainAll(actions.keySet());
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
    if (routeBalancers.isEmpty()) {
      helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
    }
    for (LoadBalancer routeBalancer : routeBalancers.values()) {
      routeBalancer.handleNameResolutionError(error);
    }
  }

  @Override
  public void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutdown");
    for (LoadBalancer routeBalancer : routeBalancers.values()) {
      routeBalancer.shutdown();
    }
  }

  @Override
  public boolean canHandleEmptyAddressListFromNameResolution() {
    return true;
  }

  private void updateOverallBalancingState() {
    ConnectivityState overallState = null;
    // Use LinkedHashMap to preserve the order of routes.
    Map<MethodName, SubchannelPicker> routePickers = new LinkedHashMap<>();
    for (Route route : routes) {
      RouteHelper routeHelper = routeHelpers.get(route.actionName);
      routePickers.put(route.methodName, routeHelper.currentPicker);
      ConnectivityState routeState = routeHelper.currentState;
      overallState = aggregateState(overallState, routeState);
    }
    if (overallState != null) {
      SubchannelPicker picker = new PathMatchingSubchannelPicker(routePickers);
      helper.updateBalancingState(overallState, picker);
    }
  }

  @Nullable
  private static ConnectivityState aggregateState(
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

  /**
   * The lb helper for a single route balancer.
   */
  private final class RouteHelper extends ForwardingLoadBalancerHelper {
    ConnectivityState currentState = CONNECTING;
    SubchannelPicker currentPicker = BUFFER_PICKER;

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
      currentState = newState;
      currentPicker = newPicker;
      updateOverallBalancingState();
    }

    @Override
    protected Helper delegate() {
      return helper;
    }
  }

  private static final class PathMatchingSubchannelPicker extends SubchannelPicker {

    final Map<MethodName, SubchannelPicker> routePickers;

    /**
     * Constructs a picker that will match the path of PickSubchannelArgs with the given map.
     * The order of the map entries matters. First match will be picked even if second match is an
     * exact (service + method) path match.
     */
    PathMatchingSubchannelPicker(Map<MethodName, SubchannelPicker> routePickers) {
      this.routePickers = routePickers;
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      for (MethodName methodName : routePickers.keySet()) {
        if (match(args.getMethodDescriptor(), methodName)) {
          return routePickers.get(methodName).pickSubchannel(args);
        }
      }
      // At least the default route should match, otherwise there is a bug.
      throw new IllegalStateException("PathMatchingSubchannelPicker: error in matching path");
    }

    boolean match(MethodDescriptor<?, ?> methodDescriptor, MethodName methodName) {
      if (methodName.service.isEmpty() && methodName.method.isEmpty()) {
        return true;
      }
      if (methodName.method.isEmpty()) {
        return methodName.service.equals(methodDescriptor.getServiceName());
      }
      return (methodName.service + '/' + methodName.method)
          .equals(methodDescriptor.getFullMethodName());
    }
  }
}
