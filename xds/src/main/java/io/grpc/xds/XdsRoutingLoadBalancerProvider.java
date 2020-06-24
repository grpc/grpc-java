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
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
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
import io.grpc.xds.RouteMatch.FractionMatcher;
import io.grpc.xds.RouteMatch.HeaderMatcher;
import io.grpc.xds.RouteMatch.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * The provider for the xds_routing balancing policy.  This class should not be directly referenced
 * in code.  The policy should be accessed through {@link LoadBalancerRegistry#getProvider} with the
 * name "xds_routing_experimental".
 */
@Internal
public final class XdsRoutingLoadBalancerProvider extends LoadBalancerProvider {

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
    return XdsLbPolicies.XDS_ROUTING_POLICY_NAME;
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return new XdsRoutingLoadBalancer(helper);
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
        PolicySelection parsedAction =
            parseAction(
                rawAction,
                this.lbRegistry == null
                    ? LoadBalancerRegistry.getDefaultRegistry() : this.lbRegistry);
        parsedActions.put(name, parsedAction);
      }

      List<Route> parsedRoutes = new ArrayList<>();
      List<Map<String, ?>> rawRoutes = JsonUtil.getListOfObjects(rawConfig, "route");
      if (rawRoutes == null || rawRoutes.isEmpty()) {
        return ConfigOrError.fromError(Status.INTERNAL.withDescription(
            "No routes provided for xds_routing LB policy: " + rawConfig));
      }
      for (Map<String, ?> rawRoute: rawRoutes) {
        Route route = parseRoute(rawRoute);
        if (!parsedActions.containsKey(route.getActionName())) {
          return ConfigOrError.fromError(Status.INTERNAL.withDescription(
              "No action defined for route " + route + " in xds_routing LB policy: " + rawConfig));
        }
        parsedRoutes.add(route);
      }
      return ConfigOrError.fromConfig(new XdsRoutingConfig(parsedRoutes, parsedActions));
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
          Status.fromThrowable(e).withDescription(
              "Failed to parse xds_routing LB config: " + rawConfig));
    }
  }

  private static PolicySelection parseAction(
      Map<String, ?> rawAction, LoadBalancerRegistry registry) {
    List<LbConfig> childConfigCandidates = ServiceConfigUtil.unwrapLoadBalancingConfigList(
        JsonUtil.getListOfObjects(rawAction, "childPolicy"));
    if (childConfigCandidates == null || childConfigCandidates.isEmpty()) {
      throw new RuntimeException("childPolicy not specified");
    }
    ConfigOrError selectedConfigOrError =
        ServiceConfigUtil.selectLbPolicyFromList(childConfigCandidates, registry);
    if (selectedConfigOrError.getError() != null) {
      throw selectedConfigOrError.getError().asRuntimeException();
    }
    return (PolicySelection) selectedConfigOrError.getConfig();
  }

  private static Route parseRoute(Map<String, ?> rawRoute) {
    try {
      String pathExact = JsonUtil.getString(rawRoute, "path");
      String pathPrefix = JsonUtil.getString(rawRoute, "prefix");
      Pattern pathRegex = null;
      String rawPathRegex = JsonUtil.getString(rawRoute, "regex");
      if (rawPathRegex != null) {
        try {
          pathRegex = Pattern.compile(rawPathRegex);
        } catch (PatternSyntaxException e) {
          throw new RuntimeException(e);
        }
      }
      if (!isOneOf(pathExact, pathPrefix, pathRegex)) {
        throw new RuntimeException("must specify exactly one patch match type");
      }
      PathMatcher pathMatcher = new PathMatcher(pathExact, pathPrefix, pathRegex);

      List<HeaderMatcher> headers = new ArrayList<>();
      List<Map<String, ?>> rawHeaders = JsonUtil.getListOfObjects(rawRoute, "headers");
      if (rawHeaders != null) {
        for (Map<String, ?> rawHeader : rawHeaders) {
          HeaderMatcher headerMatcher = parseHeaderMatcher(rawHeader);
          headers.add(headerMatcher);
        }
      }

      FractionMatcher matchFraction = null;
      Map<String, ?> rawFraction = JsonUtil.getObject(rawRoute, "matchFraction");
      if (rawFraction != null) {
        matchFraction = parseFractionMatcher(rawFraction);
      }

      String actionName = JsonUtil.getString(rawRoute, "action");
      if (actionName == null) {
        throw new RuntimeException("action name not specified");
      }
      return new Route(new RouteMatch(pathMatcher, headers, matchFraction), actionName);
    } catch (RuntimeException e) {
      throw new RuntimeException("Failed to parse Route: " + e.getMessage());
    }
  }

  private static HeaderMatcher parseHeaderMatcher(Map<String, ?> rawHeaderMatcher) {
    try {
      String name = JsonUtil.getString(rawHeaderMatcher, "name");
      if (name == null) {
        throw new RuntimeException("header name not specified");
      }
      String exactMatch = JsonUtil.getString(rawHeaderMatcher, "exactMatch");
      Pattern regexMatch = null;
      String rawRegex = JsonUtil.getString(rawHeaderMatcher, "regexMatch");
      if (rawRegex != null) {
        try {
          regexMatch = Pattern.compile(rawRegex);
        } catch (PatternSyntaxException e) {
          throw new RuntimeException(e);
        }
      }
      Map<String, ?> rawRangeMatch = JsonUtil.getObject(rawHeaderMatcher, "rangeMatch");
      HeaderMatcher.Range rangeMatch =
          rawRangeMatch == null ? null : parseHeaderRange(rawRangeMatch);
      Boolean presentMatch = JsonUtil.getBoolean(rawHeaderMatcher, "presentMatch");
      String prefixMatch = JsonUtil.getString(rawHeaderMatcher, "prefixMatch");
      String suffixMatch = JsonUtil.getString(rawHeaderMatcher, "suffixMatch");
      if (!isOneOf(exactMatch, regexMatch, rangeMatch, presentMatch, prefixMatch, suffixMatch)) {
        throw new RuntimeException("must specify exactly one match type");
      }
      Boolean inverted = JsonUtil.getBoolean(rawHeaderMatcher, "invertMatch");
      return new HeaderMatcher(
          name, exactMatch, regexMatch, rangeMatch, presentMatch, prefixMatch, suffixMatch,
          inverted == null ? false : inverted);
    } catch (RuntimeException e) {
      throw new RuntimeException("Failed to parse HeaderMatcher: " + e.getMessage());
    }
  }

  private static boolean isOneOf(Object... objects) {
    int count = 0;
    for (Object o : objects) {
      if (o != null) {
        count++;
      }
    }
    return count == 1;
  }

  private static HeaderMatcher.Range parseHeaderRange(Map<String, ?> rawRange) {
    try {
      Long start = JsonUtil.getNumberAsLong(rawRange, "start");
      if (start == null) {
        throw new RuntimeException("start not specified");
      }
      Long end = JsonUtil.getNumberAsLong(rawRange, "end");
      if (end == null) {
        throw new RuntimeException("end not specified");
      }
      return new HeaderMatcher.Range(start, end);
    } catch (RuntimeException e) {
      throw new RuntimeException("Failed to parse Range: " + e.getMessage());
    }
  }

  private static FractionMatcher parseFractionMatcher(Map<String, ?> rawFraction) {
    try {
      Integer numerator = JsonUtil.getNumberAsInteger(rawFraction, "numerator");
      if (numerator == null) {
        throw new RuntimeException("numerator not specified");
      }
      Integer denominator = JsonUtil.getNumberAsInteger(rawFraction, "denominator");
      if (denominator == null) {
        throw new RuntimeException("denominator not specified");
      }
      return new FractionMatcher(numerator, denominator);
    } catch (RuntimeException e) {
      throw new RuntimeException("Failed to parse Fraction: " + e.getMessage());
    }
  }

  static final class XdsRoutingConfig {

    final List<Route> routes;
    final Map<String, PolicySelection> actions;

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
    private final RouteMatch routeMatch;
    private final String actionName;

    Route(RouteMatch routeMatch, String actionName) {
      this.routeMatch = routeMatch;
      this.actionName = actionName;
    }

    String getActionName() {
      return actionName;
    }

    RouteMatch getRouteMatch() {
      return routeMatch;
    }

    @Override
    public int hashCode() {
      return Objects.hash(routeMatch, actionName);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Route that = (Route) o;
      return Objects.equals(actionName, that.actionName)
          && Objects.equals(routeMatch, that.routeMatch);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("routeMatch", routeMatch)
          .add("actionName", actionName)
          .toString();
    }
  }
}
