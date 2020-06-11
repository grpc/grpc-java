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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.gson.Gson;
import com.google.re2j.Pattern;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalLogId;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.EnvoyProtoData.ClusterWeight;
import io.grpc.xds.EnvoyProtoData.Route;
import io.grpc.xds.EnvoyProtoData.RouteAction;
import io.grpc.xds.RouteMatch.FractionMatcher;
import io.grpc.xds.RouteMatch.HeaderMatcher;
import io.grpc.xds.RouteMatch.PathMatcher;
import io.grpc.xds.XdsClient.ConfigUpdate;
import io.grpc.xds.XdsClient.ConfigWatcher;
import io.grpc.xds.XdsClient.RefCountedXdsClientObjectPool;
import io.grpc.xds.XdsClient.XdsChannelFactory;
import io.grpc.xds.XdsClient.XdsClientFactory;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;

/**
 * A {@link NameResolver} for resolving gRPC target names with "xds-experimental" scheme.
 *
 * <p>Resolving a gRPC target involves contacting the control plane management server via xDS
 * protocol to retrieve service information and produce a service config to the caller.
 *
 * @see XdsNameResolverProvider
 */
final class XdsNameResolver extends NameResolver {

  private final XdsLogger logger;
  private final String authority;
  private final XdsChannelFactory channelFactory;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  private final ServiceConfigParser serviceConfigParser;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final Supplier<Stopwatch> stopwatchSupplier;
  private final Bootstrapper bootstrapper;

  @Nullable
  private ObjectPool<XdsClient> xdsClientPool;
  @Nullable
  private XdsClient xdsClient;

  XdsNameResolver(
      String name,
      Args args,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier,
      XdsChannelFactory channelFactory,
      Bootstrapper bootstrapper) {
    authority = GrpcUtil.checkAuthority(checkNotNull(name, "name"));
    this.channelFactory = checkNotNull(channelFactory, "channelFactory");
    this.syncContext = checkNotNull(args.getSynchronizationContext(), "syncContext");
    this.timeService = checkNotNull(args.getScheduledExecutorService(), "timeService");
    this.serviceConfigParser = checkNotNull(args.getServiceConfigParser(), "serviceConfigParser");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
    this.bootstrapper = checkNotNull(bootstrapper, "bootstrapper");
    logger = XdsLogger.withLogId(InternalLogId.allocate("xds-resolver", name));
    logger.log(XdsLogLevel.INFO, "Created resolver for {0}", name);
  }

  @Override
  public String getServiceAuthority() {
    return authority;
  }

  @Override
  public void start(Listener2 listener) {
    BootstrapInfo bootstrapInfo;
    try {
      bootstrapInfo = bootstrapper.readBootstrap();
    } catch (Exception e) {
      listener.onError(Status.UNAVAILABLE.withDescription("Failed to bootstrap").withCause(e));
      return;
    }
    final List<ServerInfo> serverList = bootstrapInfo.getServers();
    final Node node = bootstrapInfo.getNode();
    if (serverList.isEmpty()) {
      listener.onError(
          Status.UNAVAILABLE.withDescription("No management server provided by bootstrap"));
      return;
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
                backoffPolicyProvider,
                stopwatchSupplier);
      }
    };
    xdsClientPool = new RefCountedXdsClientObjectPool(xdsClientFactory);
    xdsClient = xdsClientPool.getObject();
    xdsClient.watchConfigData(authority, new ConfigWatcherImpl(listener));
  }

  private class ConfigWatcherImpl implements ConfigWatcher {

    final Listener2 listener;

    ConfigWatcherImpl(Listener2 listener) {
      this.listener = listener;
    }

    @Override
    public void onConfigChanged(ConfigUpdate update) {
      Map<String, ?> rawLbConfig;
      if (update.getRoutes().size() > 1) {
        logger.log(
            XdsLogLevel.INFO,
            "Received config update with {0} routes from xDS client {1}",
            update.getRoutes().size(),
            xdsClient);
        rawLbConfig = generateXdsRoutingRawConfig(update.getRoutes());
      } else {
        Route defaultRoute = Iterables.getOnlyElement(update.getRoutes());
        RouteAction action = defaultRoute.getRouteAction();
        String clusterName = defaultRoute.getRouteAction().getCluster();
        if (action.getCluster() != null) {
          logger.log(
              XdsLogLevel.INFO,
              "Received config update from xDS client {0}: cluster_name={1}",
              xdsClient,
              clusterName);
          rawLbConfig = generateCdsRawConfig(clusterName);
        } else {
          logger.log(
              XdsLogLevel.INFO,
              "Received config update with one weighted cluster route from xDS client {0}",
              xdsClient);
          List<ClusterWeight> clusterWeights = defaultRoute.getRouteAction().getWeightedCluster();
          rawLbConfig = generateWeightedTargetRawConfig(clusterWeights);
        }
      }

      Map<String, ?> serviceConfig =
          ImmutableMap.of("loadBalancingConfig", ImmutableList.of(rawLbConfig));
      if (logger.isLoggable(XdsLogLevel.INFO)) {
        logger.log(
            XdsLogLevel.INFO,
            "Generated service config:\n{0}",
            new Gson().toJson(serviceConfig));
      }

      Attributes attrs =
          Attributes.newBuilder()
              .set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
              .build();
      ConfigOrError parsedServiceConfig = serviceConfigParser.parseServiceConfig(serviceConfig);
      ResolutionResult result =
          ResolutionResult.newBuilder()
              .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
              .setAttributes(attrs)
              .setServiceConfig(parsedServiceConfig)
              .build();
      listener.onResult(result);
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      logger.log(XdsLogLevel.INFO, "Resource {0} is unavailable", resourceName);
      ConfigOrError parsedServiceConfig =
          serviceConfigParser.parseServiceConfig(Collections.<String, Object>emptyMap());
      ResolutionResult result =
          ResolutionResult.newBuilder()
              .setServiceConfig(parsedServiceConfig)
              .build();
      listener.onResult(result);
    }

    @Override
    public void onError(Status error) {
      logger.log(
          XdsLogLevel.WARNING,
          "Received error from xDS client {0}: {1}", xdsClient, error.getDescription());
      listener.onError(error);
    }
  }

  @VisibleForTesting
  static ImmutableMap<String, ?> generateXdsRoutingRawConfig(List<Route> routes) {
    List<Object> rawRoutes = new ArrayList<>();
    Map<String, Object> rawActions = new LinkedHashMap<>();
    Map<RouteAction, String> existingActions = new HashMap<>();
    for (Route route : routes) {
      RouteAction routeAction = route.getRouteAction();
      String actionName;
      Map<String, ?> actionPolicy;
      if (existingActions.containsKey(routeAction)) {
        actionName = existingActions.get(routeAction);
      } else {
        if (routeAction.getCluster() != null) {
          actionName = "cds:" + routeAction.getCluster();
          actionPolicy = generateCdsRawConfig(routeAction.getCluster());
        } else {
          StringBuilder sb = new StringBuilder("weighted:");
          List<ClusterWeight> clusterWeights = routeAction.getWeightedCluster();
          for (ClusterWeight clusterWeight : clusterWeights) {
            sb.append(clusterWeight.getName()).append('_');
          }
          sb.append(routeAction.hashCode());
          actionName = sb.toString();
          if (rawActions.containsKey(actionName)) {
            // Just in case of hash collision, append existingActions.size() to make actionName
            // unique. However, in case of collision, when new ConfigUpdate is received, actions
            // and actionNames might be associated differently from the previous update, but it
            // is just suboptimal and won't cause a problem.
            actionName = actionName + "_" + existingActions.size();
          }
          actionPolicy = generateWeightedTargetRawConfig(clusterWeights);
        }
        existingActions.put(routeAction, actionName);
        List<?> childPolicies = ImmutableList.of(actionPolicy);
        rawActions.put(actionName, ImmutableMap.of("childPolicy", childPolicies));
      }
      ImmutableMap<String, ?> configRoute = convertToRawRoute(route.getRouteMatch(), actionName);
      rawRoutes.add(configRoute);
    }
    return ImmutableMap.of(
        XdsLbPolicies.XDS_ROUTING_POLICY_NAME,
        ImmutableMap.of(
            "route", Collections.unmodifiableList(rawRoutes),
            "action", Collections.unmodifiableMap(rawActions)));
  }

  @VisibleForTesting
  static ImmutableMap<String, ?> convertToRawRoute(RouteMatch routeMatch, String actionName) {
    ImmutableMap.Builder<String, Object> configRouteBuilder = new ImmutableMap.Builder<>();

    PathMatcher pathMatcher = routeMatch.getPathMatch();
    String path = pathMatcher.getPath();
    String prefix = pathMatcher.getPrefix();
    Pattern regex = pathMatcher.getRegEx();
    if (path != null) {
      configRouteBuilder.put("path", path);
    }
    if (prefix != null) {
      configRouteBuilder.put("prefix", prefix);
    }
    if (regex != null) {
      configRouteBuilder.put("regex", regex.pattern());
    }

    ImmutableList.Builder<Object> rawHeaderMatcherListBuilder = new ImmutableList.Builder<>();
    List<HeaderMatcher> headerMatchers = routeMatch.getHeaderMatchers();
    for (HeaderMatcher headerMatcher : headerMatchers) {
      ImmutableMap.Builder<String, Object> rawHeaderMatcherBuilder = new ImmutableMap.Builder<>();
      rawHeaderMatcherBuilder.put("name", headerMatcher.getName());
      String exactMatch = headerMatcher.getExactMatch();
      Pattern regexMatch = headerMatcher.getRegExMatch();
      HeaderMatcher.Range rangeMatch = headerMatcher.getRangeMatch();
      Boolean presentMatch = headerMatcher.getPresentMatch();
      String prefixMatch = headerMatcher.getPrefixMatch();
      String suffixMatch = headerMatcher.getSuffixMatch();
      if (exactMatch != null) {
        rawHeaderMatcherBuilder.put("exactMatch", exactMatch);
      }
      if (regexMatch != null) {
        rawHeaderMatcherBuilder.put("regexMatch", regexMatch.pattern());
      }
      if (rangeMatch != null) {
        rawHeaderMatcherBuilder
            .put(
                "rangeMatch",
                ImmutableMap.of("start", rangeMatch.getStart(), "end", rangeMatch.getEnd()));
      }
      if (presentMatch != null) {
        rawHeaderMatcherBuilder.put("presentMatch", presentMatch);
      }
      if (prefixMatch != null) {
        rawHeaderMatcherBuilder.put("prefixMatch", prefixMatch);
      }
      if (suffixMatch != null) {
        rawHeaderMatcherBuilder.put("suffixMatch", suffixMatch);
      }
      rawHeaderMatcherBuilder.put("invertMatch", headerMatcher.isInvertedMatch());
      rawHeaderMatcherListBuilder.add(rawHeaderMatcherBuilder.build());
    }
    ImmutableList<?> rawHeaderMatchers = rawHeaderMatcherListBuilder.build();
    if (!rawHeaderMatchers.isEmpty()) {
      configRouteBuilder.put("headers", rawHeaderMatchers);
    }

    FractionMatcher matchFraction = routeMatch.getFractionMatch();
    if (matchFraction != null) {
      configRouteBuilder
          .put(
              "matchFraction",
              ImmutableMap.of(
                  "numerator", matchFraction.getNumerator(),
                  "denominator", matchFraction.getDenominator()));
    }

    configRouteBuilder.put("action", actionName);
    return configRouteBuilder.build();
  }

  @VisibleForTesting
  static ImmutableMap<String, ?> generateWeightedTargetRawConfig(
      List<ClusterWeight> clusterWeights) {
    Map<String, Object> targets = new LinkedHashMap<>();
    for (ClusterWeight clusterWeight : clusterWeights) {
      Map<String, ?> childPolicy = generateCdsRawConfig(clusterWeight.getName());
      Map<String, ?> weightedConfig = ImmutableMap.of(
          "weight",
          (double) clusterWeight.getWeight(),
          "childPolicy",
          ImmutableList.of(childPolicy));
      targets.put(clusterWeight.getName(), weightedConfig);
    }
    return ImmutableMap.of(
        XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME,
        ImmutableMap.of("targets", targets));
  }

  private static ImmutableMap<String, ?> generateCdsRawConfig(String clusterName) {
    return ImmutableMap.of(XdsLbPolicies.CDS_POLICY_NAME, ImmutableMap.of("cluster", clusterName));
  }

  @Override
  public void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutdown");
    if (xdsClient != null) {
      xdsClient = xdsClientPool.returnObject(xdsClient);
    }
  }
}
