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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.JsonUtil;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * The provider for the xds_routing balancing policy.  This class should not be directly referenced
 * in code.  The policy should be accessed through {@link LoadBalancerRegistry#getProvider} with the
 * name "xds_routing_experimental".
 */
@Internal
public final class XdsRoutingLoadBalancerProvider extends LoadBalancerProvider {

  static final String XDS_ROUTING_POLICY_NAME = "xds_routing_experimental";

  @Nullable
  private final LoadBalancerRegistry lbRegistry;

  // We can not call this(LoadBalancerRegistry.getDefaultRegistry()), because it will get stuck
  // recursively loading LoadBalancerRegistry and XdsRoutingLoadBalancerProvider.
  public XdsRoutingLoadBalancerProvider() {
    this(null);
  }

  @VisibleForTesting
  XdsRoutingLoadBalancerProvider(@Nullable LoadBalancerRegistry lbRegistry) {
    this.lbRegistry = lbRegistry;
  }

  private LoadBalancerRegistry loadBalancerRegistry() {
    return lbRegistry == null ? LoadBalancerRegistry.getDefaultRegistry() : lbRegistry;
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public int getPriority() {
    return 5;
  }

  @Override
  public String getPolicyName() {
    return XDS_ROUTING_POLICY_NAME;
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    // TODO(zdapeng): pass helper and loadBalancerRegistry() to constructor args.
    return new XdsRoutingLoadBalancer();
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawConfig) {
    try {
      Map<String, ?> actions = JsonUtil.getObject(rawConfig, "action");
      if (actions == null || actions.isEmpty()) {
        return ConfigOrError.fromError(Status.INTERNAL.withDescription(
            "No actions provided for xds_routing LB policy: " + rawConfig));
      }
      Map<String, PolicySelection> parsedActions = new LinkedHashMap<>();
      for (String name : actions.keySet()) {
        Map<String, ?> rawAction = JsonUtil.getObject(actions, name);
        if (rawAction == null) {
          return ConfigOrError.fromError(Status.INTERNAL.withDescription(
              "No config for action " + name + " in xds_routing LB policy: " + rawConfig));
        }
        List<LbConfig> childConfigCandidates = ServiceConfigUtil.unwrapLoadBalancingConfigList(
            JsonUtil.getListOfObjects(rawAction, "childPolicy"));
        if (childConfigCandidates == null || childConfigCandidates.isEmpty()) {
          return ConfigOrError.fromError(Status.INTERNAL.withDescription(
              "No child policy for action " + name + " in xds_routing LB policy: "
                  + rawConfig));
        }

        ConfigOrError selectedConfigOrError =
            ServiceConfigUtil.selectLbPolicyFromList(childConfigCandidates, loadBalancerRegistry());
        if (selectedConfigOrError.getError() != null) {
          return selectedConfigOrError;
        }

        parsedActions.put(name, (PolicySelection) selectedConfigOrError.getConfig());
      }

      List<Map<String, ?>> routes = JsonUtil.getListOfObjects(rawConfig, "route");
      if (routes == null || routes.isEmpty()) {
        return ConfigOrError.fromError(Status.INTERNAL.withDescription(
            "No routes provided for xds_routing LB policy: " + rawConfig));
      }
      List<Route> parsedRoutes = new ArrayList<>();
      Set<MethodName> methodNames = new HashSet<>();
      for (int i = 0; i < routes.size(); i++) {
        Map<String, ?> route = routes.get(i);
        String actionName = JsonUtil.getString(route, "action");
        if (actionName == null) {
          return ConfigOrError.fromError(Status.INTERNAL.withDescription(
              "No action name provided for one of the routes in xds_routing LB policy: "
                  + rawConfig));
        }
        if (!parsedActions.containsKey(actionName)) {
          return ConfigOrError.fromError(Status.INTERNAL.withDescription(
              "No action defined for route " + route + " in xds_routing LB policy: " + rawConfig));
        }
        Map<String, ?> methodName = JsonUtil.getObject(route, "methodName");
        if (methodName == null) {
          return ConfigOrError.fromError(Status.INTERNAL.withDescription(
              "No method_name provided for one of the routes in xds_routing LB policy: "
                  + rawConfig));
        }
        String service = JsonUtil.getString(methodName, "service");
        String method = JsonUtil.getString(methodName, "method");
        if (service == null || method == null) {
          return ConfigOrError.fromError(Status.INTERNAL.withDescription(
              "No service or method provided for one of the routes in xds_routing LB policy: "
                  + rawConfig));
        }
        MethodName parseMethodName = new MethodName(service, method);
        if (i == routes.size() - 1 && !parseMethodName.isDefault()) {
          return ConfigOrError.fromError(Status.INTERNAL.withDescription(
              "The last route in routes is not the default route in xds_routing LB policy: "
                  + rawConfig));
        }
        if (methodNames.contains(parseMethodName)) {
          return ConfigOrError.fromError(Status.INTERNAL.withDescription(
              "Duplicate methodName found in routes in xds_routing LB policy: " + rawConfig));
        }
        methodNames.add(parseMethodName);

        parsedRoutes.add(new Route(actionName, parseMethodName));
      }

      return ConfigOrError.fromConfig(new XdsRoutingConfig(parsedRoutes, parsedActions));
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
          Status.fromThrowable(e).withDescription(
              "Failed to parse xds_routing LB config: " + rawConfig));
    }
  }

  static final class XdsRoutingConfig {

    final List<Route> routes;
    final Map<String, PolicySelection> actions;

    /**
     * Constructs a deeply parsed xds_routing config with the given non-empty list of routes, the
     * action of each of which is provided by the given map of actions.
     */
    @VisibleForTesting
    XdsRoutingConfig(List<Route> routes, Map<String, PolicySelection> actions) {
      this.routes = ImmutableList.copyOf(routes);
      this.actions = ImmutableMap.copyOf(actions);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      XdsRoutingConfig that = (XdsRoutingConfig) o;
      return Objects.equals(routes, that.routes)
          && Objects.equals(actions, that.actions);
    }

    @Override
    public int hashCode() {
      return Objects.hash(routes, actions);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("routes", routes)
          .add("actions", actions)
          .toString();
    }
  }

  static final class Route {

    final String actionName;
    final MethodName methodName;

    @VisibleForTesting
    Route(String actionName, MethodName methodName) {
      this.actionName = actionName;
      this.methodName = methodName;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Route route = (Route) o;
      return Objects.equals(actionName, route.actionName)
          && Objects.equals(methodName, route.methodName);
    }

    @Override
    public int hashCode() {
      return Objects.hash(actionName, methodName);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("actionName", actionName)
          .add("methodName", methodName)
          .toString();
    }
  }

  static final class MethodName {

    final String service;
    final String method;

    @VisibleForTesting
    MethodName(String service, String method) {
      this.service = service;
      this.method = method;
    }

    boolean isDefault() {
      return service.isEmpty() && method.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      MethodName that = (MethodName) o;
      return Objects.equals(service, that.service)
          && Objects.equals(method, that.method);
    }

    @Override
    public int hashCode() {
      return Objects.hash(service, method);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("service", service)
          .add("method", method)
          .toString();
    }
  }
}
