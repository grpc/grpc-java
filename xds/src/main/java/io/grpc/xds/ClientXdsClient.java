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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.xds.EnvoyServerProtoData.TRANSPORT_SOCKET_NAME_TLS;

import com.github.udpa.udpa.type.v1.TypedStruct;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CaseFormat;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.Durations;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import io.envoyproxy.envoy.config.cluster.v3.CircuitBreakers.Thresholds;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.CustomClusterType;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbPolicy;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.RingHashLbConfig;
import io.envoyproxy.envoy.config.core.v3.HttpProtocolOptions;
import io.envoyproxy.envoy.config.core.v3.RoutingPriority;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.envoyproxy.envoy.type.v3.FractionalPercent.DenominatorType;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.xds.Endpoints.DropOverload;
import io.grpc.xds.Endpoints.LbEndpoint;
import io.grpc.xds.Endpoints.LocalityLbEndpoints;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.Filter.ConfigOrError;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.Filter.NamedFilterConfig;
import io.grpc.xds.LoadStatsManager2.ClusterDropStats;
import io.grpc.xds.LoadStatsManager2.ClusterLocalityStats;
import io.grpc.xds.Matchers.FractionMatcher;
import io.grpc.xds.Matchers.HeaderMatcher;
import io.grpc.xds.Matchers.PathMatcher;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.VirtualHost.Route.RouteAction;
import io.grpc.xds.VirtualHost.Route.RouteAction.ClusterWeight;
import io.grpc.xds.VirtualHost.Route.RouteAction.HashPolicy;
import io.grpc.xds.VirtualHost.Route.RouteMatch;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * XdsClient implementation for client side usages.
 */
final class ClientXdsClient extends AbstractXdsClient {

  // Longest time to wait, since the subscription to some resource, for concluding its absence.
  @VisibleForTesting
  static final int INITIAL_RESOURCE_FETCH_TIMEOUT_SEC = 15;
  @VisibleForTesting
  static final String AGGREGATE_CLUSTER_TYPE_NAME = "envoy.clusters.aggregate";
  @VisibleForTesting
  static final String HASH_POLICY_FILTER_STATE_KEY = "io.grpc.channel_id";
  @VisibleForTesting
  static boolean enableFaultInjection =
      Boolean.parseBoolean(System.getenv("GRPC_XDS_EXPERIMENTAL_FAULT_INJECTION"));

  private static final String TYPE_URL_HTTP_CONNECTION_MANAGER_V2 =
      "type.googleapis.com/envoy.config.filter.network.http_connection_manager.v2"
          + ".HttpConnectionManager";
  static final String TYPE_URL_HTTP_CONNECTION_MANAGER =
      "type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3"
          + ".HttpConnectionManager";
  private static final String TYPE_URL_UPSTREAM_TLS_CONTEXT =
      "type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext";
  private static final String TYPE_URL_UPSTREAM_TLS_CONTEXT_V2 =
      "type.googleapis.com/envoy.api.v2.auth.UpstreamTlsContext";
  private static final String TYPE_URL_CLUSTER_CONFIG_V2 =
      "type.googleapis.com/envoy.config.cluster.aggregate.v2alpha.ClusterConfig";
  private static final String TYPE_URL_CLUSTER_CONFIG =
      "type.googleapis.com/envoy.extensions.clusters.aggregate.v3.ClusterConfig";
  private static final String TYPE_URL_TYPED_STRUCT =
      "type.googleapis.com/udpa.type.v1.TypedStruct";
  private static final String TYPE_URL_FILTER_CONFIG =
      "type.googleapis.com/envoy.config.route.v3.FilterConfig";

  private final Map<String, ResourceSubscriber> ldsResourceSubscribers = new HashMap<>();
  private final Map<String, ResourceSubscriber> rdsResourceSubscribers = new HashMap<>();
  private final Map<String, ResourceSubscriber> cdsResourceSubscribers = new HashMap<>();
  private final Map<String, ResourceSubscriber> edsResourceSubscribers = new HashMap<>();
  private final LoadStatsManager2 loadStatsManager;
  private final LoadReportClient lrsClient;
  private boolean reportingLoad;

  ClientXdsClient(ManagedChannel channel, boolean useProtocolV3, Node node,
      ScheduledExecutorService timeService, BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier) {
    super(channel, useProtocolV3, node, timeService, backoffPolicyProvider, stopwatchSupplier);
    loadStatsManager = new LoadStatsManager2(stopwatchSupplier);
    lrsClient = new LoadReportClient(loadStatsManager, channel, useProtocolV3, node,
        getSyncContext(), timeService, backoffPolicyProvider, stopwatchSupplier);
  }

  @Override
  protected void handleLdsResponse(String versionInfo, List<Any> resources, String nonce) {
    // Unpack Listener messages.
    List<Listener> listeners = new ArrayList<>(resources.size());
    List<String> listenerNames = new ArrayList<>(resources.size());
    boolean isResourceV3 = false;
    try {
      for (Any res : resources) {
        if (res.getTypeUrl().equals(ResourceType.LDS.typeUrl())) {
          isResourceV3 = true;
        }
        Listener listener = unpackCompatibleType(res, Listener.class, ResourceType.LDS.typeUrl(),
            ResourceType.LDS.typeUrlV2());
        listeners.add(listener);
        listenerNames.add(listener.getName());
      }
    } catch (InvalidProtocolBufferException e) {
      getLogger().log(XdsLogLevel.WARNING, "Failed to unpack Listeners in LDS response {0}", e);
      nackResponse(ResourceType.LDS, nonce, "Malformed LDS response: " + e);
      return;
    }
    getLogger().log(XdsLogLevel.INFO, "Received LDS response for resources: {0}", listenerNames);

    // Unpack HttpConnectionManager messages.
    Map<String, HttpConnectionManager> httpConnectionManagers = new HashMap<>(listeners.size());
    Map<String, Listener> serverSideListeners = new HashMap<>(listeners.size());
    try {
      for (Listener listener : listeners) {
        if (listener.hasApiListener()) {
          HttpConnectionManager hcm = unpackCompatibleType(
                  listener.getApiListener().getApiListener(), HttpConnectionManager.class,
                  TYPE_URL_HTTP_CONNECTION_MANAGER, TYPE_URL_HTTP_CONNECTION_MANAGER_V2);
          httpConnectionManagers.put(listener.getName(), hcm);
        } else {
          serverSideListeners.put(listener.getName(), listener);
        }
      }
    } catch (InvalidProtocolBufferException e) {
      getLogger().log(
          XdsLogLevel.WARNING,
          "Failed to unpack HttpConnectionManagers in Listeners of LDS response {0}", e);
      nackResponse(ResourceType.LDS, nonce, "Malformed LDS response: " + e);
      return;
    }

    Map<String, LdsUpdate> ldsUpdates = new HashMap<>();
    Set<String> rdsNames = new HashSet<>();
    for (Map.Entry<String, HttpConnectionManager> entry : httpConnectionManagers.entrySet()) {
      LdsUpdate update;
      String listenerName = entry.getKey();
      HttpConnectionManager hcm = entry.getValue();
      long maxStreamDuration = 0;
      if (hcm.hasCommonHttpProtocolOptions()) {
        HttpProtocolOptions options = hcm.getCommonHttpProtocolOptions();
        if (options.hasMaxStreamDuration()) {
          maxStreamDuration = Durations.toNanos(options.getMaxStreamDuration());
        }
      }
      boolean parseFilter = enableFaultInjection && isResourceV3;
      List<NamedFilterConfig> filterChain = null;
      if (parseFilter) {
        filterChain = new ArrayList<>();
        List<io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter>
            httpFilters = hcm.getHttpFiltersList();
        for (io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
                 httpFilter : httpFilters) {
          String filterName = httpFilter.getName();
          StructOrError<FilterConfig> filterConfig = parseHttpFilter(httpFilter);
          if (filterConfig == null) {
            continue;
          }
          if (filterConfig.errorDetail != null) {
            nackResponse(
                ResourceType.LDS, nonce,
                "Error parsing HttpFilter: " + filterConfig.errorDetail);
            return;
          }
          filterChain.add(new NamedFilterConfig(filterName, filterConfig.struct));
        }
      }

      if (hcm.hasRouteConfig()) {
        List<VirtualHost> virtualHosts = new ArrayList<>();
        for (io.envoyproxy.envoy.config.route.v3.VirtualHost virtualHostProto
            : hcm.getRouteConfig().getVirtualHostsList()) {
          StructOrError<VirtualHost> virtualHost = parseVirtualHost(virtualHostProto, parseFilter);
          if (virtualHost.getErrorDetail() != null) {
            nackResponse(ResourceType.LDS, nonce,
                "Listener " + listenerName + " contains invalid virtual host: "
                    + virtualHost.getErrorDetail());
            return;
          }
          virtualHosts.add(virtualHost.getStruct());
        }
        update = new LdsUpdate(maxStreamDuration, virtualHosts, filterChain);
      } else if (hcm.hasRds()) {
        Rds rds = hcm.getRds();
        if (!rds.getConfigSource().hasAds()) {
          nackResponse(ResourceType.LDS, nonce,
              "Listener " + listenerName + " with RDS config_source not set to ADS");
          return;
        }
        update =
            new LdsUpdate(maxStreamDuration, rds.getRouteConfigName(), filterChain);
        rdsNames.add(rds.getRouteConfigName());
      } else {
        nackResponse(ResourceType.LDS, nonce,
            "Listener " + listenerName + " without inline RouteConfiguration or RDS");
        return;
      }
      ldsUpdates.put(listenerName, update);
    }
    // process serverSideListeners if any
    for (Map.Entry<String, Listener> entry : serverSideListeners.entrySet()) {
      String listenerName = entry.getKey();
      Listener listener = entry.getValue();
      LdsUpdate update;

      StructOrError<EnvoyServerProtoData.Listener> convertedListener =
              parseServerSideListener(listener);
      if (convertedListener.getErrorDetail() != null) {
        nackResponse(ResourceType.LDS, nonce, convertedListener.getErrorDetail());
        return;
      }
      update = new LdsUpdate(convertedListener.getStruct());
      ldsUpdates.put(listenerName, update);
    }
    ackResponse(ResourceType.LDS, versionInfo, nonce);

    for (String resource : ldsResourceSubscribers.keySet()) {
      ResourceSubscriber subscriber = ldsResourceSubscribers.get(resource);
      if (ldsUpdates.containsKey(resource)) {
        subscriber.onData(ldsUpdates.get(resource));
      } else {
        subscriber.onAbsent();
      }
    }
    for (String resource : rdsResourceSubscribers.keySet()) {
      if (!rdsNames.contains(resource)) {
        ResourceSubscriber subscriber = rdsResourceSubscribers.get(resource);
        subscriber.onAbsent();
      }
    }
  }

  @VisibleForTesting
  @Nullable // Returns null if the filter is optional but not supported.
  static StructOrError<FilterConfig> parseHttpFilter(
      io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
          httpFilter) {
    String filterName = httpFilter.getName();
    boolean isOptional = httpFilter.getIsOptional();
    if (!httpFilter.hasTypedConfig()) {
      if (isOptional) {
        return null;
      } else {
        return StructOrError.fromError(
            "HttpFilter [" + filterName + "] is not optional and has no typed config");
      }
    }
    return parseRawFilterConfig(filterName, httpFilter.getTypedConfig(), isOptional, false);
  }

  @Nullable // Returns null if the filter should be ignored.
  private static StructOrError<FilterConfig> parseRawFilterConfig(
      String filterName, Any anyConfig, Boolean isOptional, boolean isOverrideConfig) {
    checkArgument(
        isOptional != null || isOverrideConfig, "isOptional can't be null for top-level config");
    String typeUrl = anyConfig.getTypeUrl();
    if (isOverrideConfig) {
      isOptional = false;
      if (typeUrl.equals(TYPE_URL_FILTER_CONFIG)) {
        io.envoyproxy.envoy.config.route.v3.FilterConfig filterConfig;
        try {
          filterConfig =
              anyConfig.unpack(io.envoyproxy.envoy.config.route.v3.FilterConfig.class);
        } catch (InvalidProtocolBufferException e) {
          return StructOrError.fromError(
              "HttpFilter [" + filterName + "] contains invalid proto: " + e);
        }
        isOptional = filterConfig.getIsOptional();
        anyConfig = filterConfig.getConfig();
        typeUrl = anyConfig.getTypeUrl();
      }
    }
    Message rawConfig = anyConfig;
    if (anyConfig.getTypeUrl().equals(TYPE_URL_TYPED_STRUCT)) {
      TypedStruct typedStruct;
      try {
        typedStruct = anyConfig.unpack(TypedStruct.class);
      } catch (InvalidProtocolBufferException e) {
        return StructOrError.fromError(
            "HttpFilter [" + filterName + "] contains invalid proto: " + e);
      }
      typeUrl = typedStruct.getTypeUrl();
      rawConfig = typedStruct.getValue();
    }
    Filter filter = FilterRegistry.getDefaultRegistry().get(typeUrl);
    if (filter == null) {
      if (isOptional) {
        return null;
      } else {
        return StructOrError.fromError(
            "HttpFilter [" + filterName + "] is not optional and has an unsupported config type: "
                + typeUrl);
      }
    }
    ConfigOrError<? extends FilterConfig> filterConfig = isOverrideConfig
        ? filter.parseFilterConfigOverride(rawConfig) : filter.parseFilterConfig(rawConfig);
    if (filterConfig.errorDetail != null) {
      return StructOrError.fromError(
          "Invalid filter config for HttpFilter [" + filterName + "]: " + filterConfig.errorDetail);
    }
    return StructOrError.fromStruct(filterConfig.config);
  }

  @VisibleForTesting static StructOrError<EnvoyServerProtoData.Listener> parseServerSideListener(
      Listener listener) {
    try {
      return StructOrError.fromStruct(
          EnvoyServerProtoData.Listener.fromEnvoyProtoListener(listener));
    } catch (InvalidProtocolBufferException e) {
      return StructOrError.fromError(
          "Failed to unpack Listener " + listener.getName() + ":" + e.getMessage());
    } catch (IllegalArgumentException e) {
      return StructOrError.fromError(e.getMessage());
    }
  }

  private static StructOrError<VirtualHost> parseVirtualHost(
      io.envoyproxy.envoy.config.route.v3.VirtualHost proto, boolean parseFilter) {
    String name = proto.getName();
    List<Route> routes = new ArrayList<>(proto.getRoutesCount());
    for (io.envoyproxy.envoy.config.route.v3.Route routeProto : proto.getRoutesList()) {
      StructOrError<Route> route = parseRoute(routeProto, parseFilter);
      if (route == null) {
        continue;
      }
      if (route.getErrorDetail() != null) {
        return StructOrError.fromError(
            "Virtual host [" + name + "] contains invalid route : " + route.getErrorDetail());
      }
      routes.add(route.getStruct());
    }
    if (!parseFilter) {
      return StructOrError.fromStruct(VirtualHost.create(
          name, proto.getDomainsList(), routes, new HashMap<String, FilterConfig>()));
    }
    StructOrError<Map<String, FilterConfig>> overrideConfigs =
        parseOverrideFilterConfigs(proto.getTypedPerFilterConfigMap());
    if (overrideConfigs.errorDetail != null) {
      return StructOrError.fromError(
          "VirtualHost [" + proto.getName() + "] contains invalid HttpFilter config: "
              + overrideConfigs.errorDetail);
    }
    return StructOrError.fromStruct(VirtualHost.create(
        name, proto.getDomainsList(), routes, overrideConfigs.struct));
  }

  @VisibleForTesting
  static StructOrError<Map<String, FilterConfig>> parseOverrideFilterConfigs(
      Map<String, Any> rawFilterConfigMap) {
    Map<String, FilterConfig> overrideConfigs = new HashMap<>();
    for (String name : rawFilterConfigMap.keySet()) {
      Any anyConfig = rawFilterConfigMap.get(name);
      StructOrError<FilterConfig> filterConfig = parseRawFilterConfig(name, anyConfig, null, true);
      if (filterConfig == null) {
        continue;
      }
      if (filterConfig.errorDetail != null) {
        return StructOrError.fromError(filterConfig.errorDetail);
      }
      overrideConfigs.put(name, filterConfig.struct);
    }
    return StructOrError.fromStruct(overrideConfigs);
  }

  @VisibleForTesting
  @Nullable
  static StructOrError<Route> parseRoute(
      io.envoyproxy.envoy.config.route.v3.Route proto, boolean parseFilter) {
    StructOrError<RouteMatch> routeMatch = parseRouteMatch(proto.getMatch());
    if (routeMatch == null) {
      return null;
    }
    if (routeMatch.getErrorDetail() != null) {
      return StructOrError.fromError(
          "Invalid route [" + proto.getName() + "]: " + routeMatch.getErrorDetail());
    }

    StructOrError<RouteAction> routeAction;
    switch (proto.getActionCase()) {
      case ROUTE:
        routeAction = parseRouteAction(proto.getRoute(), parseFilter);
        break;
      case REDIRECT:
        return StructOrError.fromError("Unsupported action type: redirect");
      case DIRECT_RESPONSE:
        return StructOrError.fromError("Unsupported action type: direct_response");
      case FILTER_ACTION:
        return StructOrError.fromError("Unsupported action type: filter_action");
      case ACTION_NOT_SET:
      default:
        return StructOrError.fromError("Unknown action type: " + proto.getActionCase());
    }
    if (routeAction == null) {
      return null;
    }
    if (routeAction.getErrorDetail() != null) {
      return StructOrError.fromError(
          "Invalid route [" + proto.getName() + "]: " + routeAction.getErrorDetail());
    }
    if (!parseFilter) {
      return StructOrError.fromStruct(Route.create(
          routeMatch.getStruct(), routeAction.getStruct(), new HashMap<String, FilterConfig>()));
    }
    StructOrError<Map<String, FilterConfig>> overrideConfigs =
        parseOverrideFilterConfigs(proto.getTypedPerFilterConfigMap());
    if (overrideConfigs.errorDetail != null) {
      return StructOrError.fromError(
          "Route [" + proto.getName() + "] contains invalid HttpFilter config: "
              + overrideConfigs.errorDetail);
    }
    return StructOrError.fromStruct(Route.create(
        routeMatch.getStruct(), routeAction.getStruct(), overrideConfigs.struct));
  }

  @VisibleForTesting
  @Nullable
  static StructOrError<RouteMatch> parseRouteMatch(
      io.envoyproxy.envoy.config.route.v3.RouteMatch proto) {
    if (proto.getQueryParametersCount() != 0) {
      return null;
    }
    StructOrError<PathMatcher> pathMatch = parsePathMatcher(proto);
    if (pathMatch.getErrorDetail() != null) {
      return StructOrError.fromError(pathMatch.getErrorDetail());
    }

    FractionMatcher fractionMatch = null;
    if (proto.hasRuntimeFraction()) {
      StructOrError<FractionMatcher> parsedFraction =
          parseFractionMatcher(proto.getRuntimeFraction().getDefaultValue());
      if (parsedFraction.getErrorDetail() != null) {
        return StructOrError.fromError(parsedFraction.getErrorDetail());
      }
      fractionMatch = parsedFraction.getStruct();
    }

    List<HeaderMatcher> headerMatchers = new ArrayList<>();
    for (io.envoyproxy.envoy.config.route.v3.HeaderMatcher hmProto : proto.getHeadersList()) {
      StructOrError<HeaderMatcher> headerMatcher = parseHeaderMatcher(hmProto);
      if (headerMatcher.getErrorDetail() != null) {
        return StructOrError.fromError(headerMatcher.getErrorDetail());
      }
      headerMatchers.add(headerMatcher.getStruct());
    }

    return StructOrError.fromStruct(RouteMatch.create(
        pathMatch.getStruct(), headerMatchers, fractionMatch));
  }

  @VisibleForTesting
  static StructOrError<PathMatcher> parsePathMatcher(
      io.envoyproxy.envoy.config.route.v3.RouteMatch proto) {
    boolean caseSensitive = proto.getCaseSensitive().getValue();
    switch (proto.getPathSpecifierCase()) {
      case PREFIX:
        return StructOrError.fromStruct(
            PathMatcher.fromPrefix(proto.getPrefix(), caseSensitive));
      case PATH:
        return StructOrError.fromStruct(PathMatcher.fromPath(proto.getPath(), caseSensitive));
      case SAFE_REGEX:
        String rawPattern = proto.getSafeRegex().getRegex();
        Pattern safeRegEx;
        try {
          safeRegEx = Pattern.compile(rawPattern);
        } catch (PatternSyntaxException e) {
          return StructOrError.fromError("Malformed safe regex pattern: " + e.getMessage());
        }
        return StructOrError.fromStruct(PathMatcher.fromRegEx(safeRegEx));
      case PATHSPECIFIER_NOT_SET:
      default:
        return StructOrError.fromError("Unknown path match type");
    }
  }

  private static StructOrError<FractionMatcher> parseFractionMatcher(FractionalPercent proto) {
    int numerator = proto.getNumerator();
    int denominator = 0;
    switch (proto.getDenominator()) {
      case HUNDRED:
        denominator = 100;
        break;
      case TEN_THOUSAND:
        denominator = 10_000;
        break;
      case MILLION:
        denominator = 1_000_000;
        break;
      case UNRECOGNIZED:
      default:
        return StructOrError.fromError(
            "Unrecognized fractional percent denominator: " + proto.getDenominator());
    }
    return StructOrError.fromStruct(FractionMatcher.create(numerator, denominator));
  }

  @VisibleForTesting
  static StructOrError<HeaderMatcher> parseHeaderMatcher(
      io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto) {
    switch (proto.getHeaderMatchSpecifierCase()) {
      case EXACT_MATCH:
        return StructOrError.fromStruct(HeaderMatcher.forExactValue(
            proto.getName(), proto.getExactMatch(), proto.getInvertMatch()));
      case SAFE_REGEX_MATCH:
        String rawPattern = proto.getSafeRegexMatch().getRegex();
        Pattern safeRegExMatch;
        try {
          safeRegExMatch = Pattern.compile(rawPattern);
        } catch (PatternSyntaxException e) {
          return StructOrError.fromError(
              "HeaderMatcher [" + proto.getName() + "] contains malformed safe regex pattern: "
                  + e.getMessage());
        }
        return StructOrError.fromStruct(HeaderMatcher.forSafeRegEx(
            proto.getName(), safeRegExMatch, proto.getInvertMatch()));
      case RANGE_MATCH:
        HeaderMatcher.Range rangeMatch = HeaderMatcher.Range.create(
            proto.getRangeMatch().getStart(), proto.getRangeMatch().getEnd());
        return StructOrError.fromStruct(HeaderMatcher.forRange(
            proto.getName(), rangeMatch, proto.getInvertMatch()));
      case PRESENT_MATCH:
        return StructOrError.fromStruct(HeaderMatcher.forPresent(
            proto.getName(), proto.getPresentMatch(), proto.getInvertMatch()));
      case PREFIX_MATCH:
        return StructOrError.fromStruct(HeaderMatcher.forPrefix(
            proto.getName(), proto.getPrefixMatch(), proto.getInvertMatch()));
      case SUFFIX_MATCH:
        return StructOrError.fromStruct(HeaderMatcher.forSuffix(
            proto.getName(), proto.getSuffixMatch(), proto.getInvertMatch()));
      case HEADERMATCHSPECIFIER_NOT_SET:
      default:
        return StructOrError.fromError("Unknown header matcher type");
    }
  }

  @VisibleForTesting
  @Nullable
  static StructOrError<RouteAction> parseRouteAction(
      io.envoyproxy.envoy.config.route.v3.RouteAction proto, boolean parseFilter) {
    Long timeoutNano = null;
    if (proto.hasMaxStreamDuration()) {
      io.envoyproxy.envoy.config.route.v3.RouteAction.MaxStreamDuration maxStreamDuration
          = proto.getMaxStreamDuration();
      if (maxStreamDuration.hasGrpcTimeoutHeaderMax()) {
        timeoutNano = Durations.toNanos(maxStreamDuration.getGrpcTimeoutHeaderMax());
      } else if (maxStreamDuration.hasMaxStreamDuration()) {
        timeoutNano = Durations.toNanos(maxStreamDuration.getMaxStreamDuration());
      }
    }
    List<HashPolicy> hashPolicies = new ArrayList<>();
    for (io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy config
        : proto.getHashPolicyList()) {
      HashPolicy policy = null;
      boolean terminal = config.getTerminal();
      switch (config.getPolicySpecifierCase()) {
        case HEADER:
          io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.Header headerCfg =
              config.getHeader();
          Pattern regEx = null;
          String regExSubstitute = null;
          if (headerCfg.hasRegexRewrite() && headerCfg.getRegexRewrite().hasPattern()
              && headerCfg.getRegexRewrite().getPattern().hasGoogleRe2()) {
            regEx = Pattern.compile(headerCfg.getRegexRewrite().getPattern().getRegex());
            regExSubstitute = headerCfg.getRegexRewrite().getSubstitution();
          }
          policy = HashPolicy.forHeader(
              terminal, headerCfg.getHeaderName(), regEx, regExSubstitute);
          break;
        case FILTER_STATE:
          if (config.getFilterState().getKey().equals(HASH_POLICY_FILTER_STATE_KEY)) {
            policy = HashPolicy.forChannelId(terminal);
          }
          break;
        default:
          // Ignore
      }
      if (policy != null) {
        hashPolicies.add(policy);
      }
    }

    switch (proto.getClusterSpecifierCase()) {
      case CLUSTER:
        return StructOrError.fromStruct(RouteAction.forCluster(
            proto.getCluster(), hashPolicies, timeoutNano));
      case CLUSTER_HEADER:
        return null;
      case WEIGHTED_CLUSTERS:
        List<io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight> clusterWeights
            = proto.getWeightedClusters().getClustersList();
        if (clusterWeights.isEmpty()) {
          return StructOrError.fromError("No cluster found in weighted cluster list");
        }
        List<ClusterWeight> weightedClusters = new ArrayList<>();
        for (io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight clusterWeight
            : clusterWeights) {
          StructOrError<ClusterWeight> clusterWeightOrError =
              parseClusterWeight(clusterWeight, parseFilter);
          if (clusterWeightOrError.getErrorDetail() != null) {
            return StructOrError.fromError("RouteAction contains invalid ClusterWeight: "
                + clusterWeightOrError.getErrorDetail());
          }
          weightedClusters.add(clusterWeightOrError.getStruct());
        }
        // TODO(chengyuanzhang): validate if the sum of weights equals to total weight.
        return StructOrError.fromStruct(RouteAction.forWeightedClusters(
            weightedClusters, hashPolicies, timeoutNano));
      case CLUSTERSPECIFIER_NOT_SET:
      default:
        return StructOrError.fromError(
            "Unknown cluster specifier: " + proto.getClusterSpecifierCase());
    }
  }

  @VisibleForTesting
  static StructOrError<ClusterWeight> parseClusterWeight(
      io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight proto,
      boolean parseFilter) {
    if (!parseFilter) {
      return StructOrError.fromStruct(ClusterWeight.create(
          proto.getName(), proto.getWeight().getValue(), new HashMap<String, FilterConfig>()));
    }
    StructOrError<Map<String, FilterConfig>> overrideConfigs =
        parseOverrideFilterConfigs(proto.getTypedPerFilterConfigMap());
    if (overrideConfigs.errorDetail != null) {
      return StructOrError.fromError(
          "ClusterWeight [" + proto.getName() + "] contains invalid HttpFilter config: "
              + overrideConfigs.errorDetail);
    }
    return StructOrError.fromStruct(ClusterWeight.create(
        proto.getName(), proto.getWeight().getValue(), overrideConfigs.struct));
  }

  @Override
  protected void handleRdsResponse(String versionInfo, List<Any> resources, String nonce) {
    // Unpack RouteConfiguration messages.
    Map<String, RouteConfiguration> routeConfigs = new HashMap<>(resources.size());
    boolean isResourceV3 = false;
    try {
      for (Any res : resources) {
        if (res.getTypeUrl().equals(ResourceType.RDS.typeUrl())) {
          isResourceV3 = true;
        }
        RouteConfiguration rc =
            unpackCompatibleType(res, RouteConfiguration.class, ResourceType.RDS.typeUrl(),
                ResourceType.RDS.typeUrlV2());
        routeConfigs.put(rc.getName(), rc);
      }
    } catch (InvalidProtocolBufferException e) {
      getLogger().log(
          XdsLogLevel.WARNING, "Failed to unpack RouteConfiguration in RDS response {0}", e);
      nackResponse(ResourceType.RDS, nonce, "Malformed RDS response: " + e);
      return;
    }
    getLogger().log(
        XdsLogLevel.INFO, "Received RDS response for resources: {0}", routeConfigs.keySet());

    Map<String, RdsUpdate> rdsUpdates = new HashMap<>();
    boolean parseFilter = enableFaultInjection && isResourceV3;
    for (Map.Entry<String, RouteConfiguration> entry : routeConfigs.entrySet()) {
      String routeConfigName = entry.getKey();
      RouteConfiguration routeConfig = entry.getValue();
      List<VirtualHost> virtualHosts =
          new ArrayList<>(routeConfig.getVirtualHostsCount());
      for (io.envoyproxy.envoy.config.route.v3.VirtualHost virtualHostProto
          : routeConfig.getVirtualHostsList()) {
        StructOrError<VirtualHost> virtualHost = parseVirtualHost(virtualHostProto, parseFilter);
        if (virtualHost.getErrorDetail() != null) {
          nackResponse(ResourceType.RDS, nonce, "RouteConfiguration " + routeConfigName
              + " contains invalid virtual host: " + virtualHost.getErrorDetail());
          return;
        }
        virtualHosts.add(virtualHost.getStruct());
      }
      rdsUpdates.put(routeConfigName, new RdsUpdate(virtualHosts));
    }
    ackResponse(ResourceType.RDS, versionInfo, nonce);

    for (String resource : rdsResourceSubscribers.keySet()) {
      if (rdsUpdates.containsKey(resource)) {
        ResourceSubscriber subscriber = rdsResourceSubscribers.get(resource);
        subscriber.onData(rdsUpdates.get(resource));
      }
    }
  }

  @Override
  protected void handleCdsResponse(String versionInfo, List<Any> resources, String nonce) {
    // Unpack Cluster messages.
    List<Cluster> clusters = new ArrayList<>(resources.size());
    List<String> clusterNames = new ArrayList<>(resources.size());
    try {
      for (Any res : resources) {
        Cluster cluster = unpackCompatibleType(res, Cluster.class, ResourceType.CDS.typeUrl(),
            ResourceType.CDS.typeUrlV2());
        clusters.add(cluster);
        clusterNames.add(cluster.getName());
      }
    } catch (InvalidProtocolBufferException e) {
      getLogger().log(XdsLogLevel.WARNING, "Failed to unpack Clusters in CDS response {0}", e);
      nackResponse(ResourceType.CDS, nonce, "Malformed CDS response: " + e);
      return;
    }
    getLogger().log(XdsLogLevel.INFO, "Received CDS response for resources: {0}", clusterNames);

    Map<String, CdsUpdate> cdsUpdates = new HashMap<>();
    // CDS responses represents the state of the world, EDS resources not referenced in CDS
    // resources should be deleted.
    Set<String> edsResources = new HashSet<>();  // retained EDS resources
    for (Cluster cluster : clusters) {
      String clusterName = cluster.getName();
      // Management server is required to always send newly requested resources, even if they
      // may have been sent previously (proactively). Thus, client does not need to cache
      // unrequested resources.
      if (!cdsResourceSubscribers.containsKey(clusterName)) {
        continue;
      }
      StructOrError<CdsUpdate.Builder> structOrError;
      switch (cluster.getClusterDiscoveryTypeCase()) {
        case TYPE:
          structOrError = parseNonAggregateCluster(cluster, edsResources);
          break;
        case CLUSTER_TYPE:
          structOrError = parseAggregateCluster(cluster);
          break;
        case CLUSTERDISCOVERYTYPE_NOT_SET:
        default:
          nackResponse(ResourceType.CDS, nonce,
              "Cluster " + clusterName + ": cluster discovery type unspecified");
          return;
      }
      if (structOrError.getErrorDetail() != null) {
        nackResponse(ResourceType.CDS, nonce, structOrError.errorDetail);
        return;
      }
      CdsUpdate.Builder updateBuilder = structOrError.getStruct();
      String lbPolicy = CaseFormat.UPPER_UNDERSCORE.to(
          CaseFormat.LOWER_UNDERSCORE, cluster.getLbPolicy().name());
      if (cluster.getLbPolicy() == LbPolicy.RING_HASH) {
        RingHashLbConfig lbConfig = cluster.getRingHashLbConfig();
        if (lbConfig.getHashFunction() != RingHashLbConfig.HashFunction.XX_HASH) {
          nackResponse(ResourceType.CDS, nonce,
              "Cluster " + clusterName + ": unsupported ring hash function: "
                  + lbConfig.getHashFunction());
          return;
        }
        updateBuilder.lbPolicy(lbPolicy, lbConfig.getMinimumRingSize().getValue(),
            lbConfig.getMaximumRingSize().getValue());
      } else if (cluster.getLbPolicy() == LbPolicy.ROUND_ROBIN) {
        updateBuilder.lbPolicy(lbPolicy);
      } else {
        nackResponse(ResourceType.CDS, nonce,
            "Cluster " + clusterName + ": unsupported lb policy: " + cluster.getLbPolicy());
        return;
      }
      cdsUpdates.put(clusterName, updateBuilder.build());
    }
    ackResponse(ResourceType.CDS, versionInfo, nonce);

    for (String resource : cdsResourceSubscribers.keySet()) {
      ResourceSubscriber subscriber = cdsResourceSubscribers.get(resource);
      if (cdsUpdates.containsKey(resource)) {
        subscriber.onData(cdsUpdates.get(resource));
      } else {
        subscriber.onAbsent();
      }
    }
    for (String resource : edsResourceSubscribers.keySet()) {
      ResourceSubscriber subscriber = edsResourceSubscribers.get(resource);
      if (!edsResources.contains(resource)) {
        subscriber.onAbsent();
      }
    }
  }

  private static StructOrError<CdsUpdate.Builder> parseAggregateCluster(Cluster cluster) {
    String clusterName = cluster.getName();
    CustomClusterType customType = cluster.getClusterType();
    String typeName = customType.getName();
    if (!typeName.equals(AGGREGATE_CLUSTER_TYPE_NAME)) {
      return StructOrError.fromError(
          "Cluster " + clusterName + ": unsupported custom cluster type: " + typeName);
    }
    io.envoyproxy.envoy.extensions.clusters.aggregate.v3.ClusterConfig clusterConfig;
    try {
      clusterConfig = unpackCompatibleType(customType.getTypedConfig(),
          io.envoyproxy.envoy.extensions.clusters.aggregate.v3.ClusterConfig.class,
          TYPE_URL_CLUSTER_CONFIG, TYPE_URL_CLUSTER_CONFIG_V2);
    } catch (InvalidProtocolBufferException e) {
      return StructOrError.fromError("Cluster " + clusterName + ": malformed ClusterConfig: " + e);
    }
    return StructOrError.fromStruct(CdsUpdate.forAggregate(
        clusterName, clusterConfig.getClustersList()));
  }

  private static StructOrError<CdsUpdate.Builder> parseNonAggregateCluster(
      Cluster cluster, Set<String> edsResources) {
    String clusterName = cluster.getName();
    String lrsServerName = null;
    Long maxConcurrentRequests = null;
    UpstreamTlsContext upstreamTlsContext = null;
    if (cluster.hasLrsServer()) {
      if (!cluster.getLrsServer().hasSelf()) {
        return StructOrError.fromError(
            "Cluster " + clusterName + ": only support LRS for the same management server");
      }
      lrsServerName = "";
    }
    if (cluster.hasCircuitBreakers()) {
      List<Thresholds> thresholds = cluster.getCircuitBreakers().getThresholdsList();
      for (Thresholds threshold : thresholds) {
        if (threshold.getPriority() != RoutingPriority.DEFAULT) {
          continue;
        }
        if (threshold.hasMaxRequests()) {
          maxConcurrentRequests = (long) threshold.getMaxRequests().getValue();
        }
      }
    }
    if (cluster.hasTransportSocket()
        && TRANSPORT_SOCKET_NAME_TLS.equals(cluster.getTransportSocket().getName())) {
      try {
        upstreamTlsContext = UpstreamTlsContext.fromEnvoyProtoUpstreamTlsContext(
            unpackCompatibleType(cluster.getTransportSocket().getTypedConfig(),
                io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext.class,
                TYPE_URL_UPSTREAM_TLS_CONTEXT, TYPE_URL_UPSTREAM_TLS_CONTEXT_V2));
      } catch (InvalidProtocolBufferException e) {
        return StructOrError.fromError(
            "Cluster " + clusterName + ": malformed UpstreamTlsContext: " + e);
      }
    }

    DiscoveryType type = cluster.getType();
    if (type == DiscoveryType.EDS) {
      String edsServiceName = null;
      io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig edsClusterConfig =
          cluster.getEdsClusterConfig();
      if (!edsClusterConfig.getEdsConfig().hasAds()) {
        return StructOrError.fromError("Cluster " + clusterName
            + ": field eds_cluster_config must be set to indicate to use EDS over ADS.");
      }
      // If the service_name field is set, that value will be used for the EDS request.
      if (!edsClusterConfig.getServiceName().isEmpty()) {
        edsServiceName = edsClusterConfig.getServiceName();
        edsResources.add(edsServiceName);
      } else {
        edsResources.add(clusterName);
      }
      return StructOrError.fromStruct(CdsUpdate.forEds(
          clusterName, edsServiceName, lrsServerName, maxConcurrentRequests, upstreamTlsContext));
    } else if (type.equals(DiscoveryType.LOGICAL_DNS)) {
      return StructOrError.fromStruct(CdsUpdate.forLogicalDns(
          clusterName, lrsServerName, maxConcurrentRequests, upstreamTlsContext));
    }
    return StructOrError.fromError(
        "Cluster " + clusterName + ": unsupported built-in discovery type: " + type);
  }

  @Override
  protected void handleEdsResponse(String versionInfo, List<Any> resources, String nonce) {
    // Unpack ClusterLoadAssignment messages.
    List<ClusterLoadAssignment> clusterLoadAssignments = new ArrayList<>(resources.size());
    List<String> claNames = new ArrayList<>(resources.size());
    try {
      for (Any res : resources) {
        ClusterLoadAssignment assignment =
            unpackCompatibleType(res, ClusterLoadAssignment.class, ResourceType.EDS.typeUrl(),
                ResourceType.EDS.typeUrlV2());
        clusterLoadAssignments.add(assignment);
        claNames.add(assignment.getClusterName());
      }
    } catch (InvalidProtocolBufferException e) {
      getLogger().log(
          XdsLogLevel.WARNING, "Failed to unpack ClusterLoadAssignments in EDS response {0}", e);
      nackResponse(ResourceType.EDS, nonce, "Malformed EDS response: " + e);
      return;
    }
    getLogger().log(XdsLogLevel.INFO, "Received EDS response for resources: {0}", claNames);

    Map<String, EdsUpdate> edsUpdates = new HashMap<>();
    for (ClusterLoadAssignment assignment : clusterLoadAssignments) {
      String clusterName = assignment.getClusterName();
      // Skip information for clusters not requested.
      // Management server is required to always send newly requested resources, even if they
      // may have been sent previously (proactively). Thus, client does not need to cache
      // unrequested resources.
      if (!edsResourceSubscribers.containsKey(clusterName)) {
        continue;
      }
      Set<Integer> priorities = new HashSet<>();
      Map<Locality, LocalityLbEndpoints> localityLbEndpointsMap = new LinkedHashMap<>();
      List<DropOverload> dropOverloads = new ArrayList<>();
      int maxPriority = -1;
      for (io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints localityLbEndpointsProto
          : assignment.getEndpointsList()) {
        StructOrError<LocalityLbEndpoints> localityLbEndpoints =
            parseLocalityLbEndpoints(localityLbEndpointsProto);
        if (localityLbEndpoints == null) {
          continue;
        }
        if (localityLbEndpoints.getErrorDetail() != null) {
          nackResponse(ResourceType.EDS, nonce, "ClusterLoadAssignment " + clusterName + ": "
              + localityLbEndpoints.getErrorDetail());
          return;
        }
        maxPriority = Math.max(maxPriority, localityLbEndpoints.getStruct().priority());
        priorities.add(localityLbEndpoints.getStruct().priority());
        // Note endpoints with health status other than HEALTHY and UNKNOWN are still
        // handed over to watching parties. It is watching parties' responsibility to
        // filter out unhealthy endpoints. See EnvoyProtoData.LbEndpoint#isHealthy().
        localityLbEndpointsMap.put(
            parseLocality(localityLbEndpointsProto.getLocality()),
            localityLbEndpoints.getStruct());
      }
      if (priorities.size() != maxPriority + 1) {
        nackResponse(ResourceType.EDS, nonce,
            "ClusterLoadAssignment " + clusterName + " : sparse priorities.");
        return;
      }
      for (ClusterLoadAssignment.Policy.DropOverload dropOverloadProto
          : assignment.getPolicy().getDropOverloadsList()) {
        dropOverloads.add(parseDropOverload(dropOverloadProto));
      }
      EdsUpdate update = new EdsUpdate(clusterName, localityLbEndpointsMap, dropOverloads);
      edsUpdates.put(clusterName, update);
    }
    ackResponse(ResourceType.EDS, versionInfo, nonce);

    for (String resource : edsResourceSubscribers.keySet()) {
      ResourceSubscriber subscriber = edsResourceSubscribers.get(resource);
      if (edsUpdates.containsKey(resource)) {
        subscriber.onData(edsUpdates.get(resource));
      }
    }
  }

  private static Locality parseLocality(io.envoyproxy.envoy.config.core.v3.Locality proto) {
    return Locality.create(proto.getRegion(), proto.getZone(), proto.getSubZone());
  }

  private static DropOverload parseDropOverload(
      io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Policy.DropOverload proto) {
    return DropOverload.create(proto.getCategory(), getRatePerMillion(proto.getDropPercentage()));
  }

  @VisibleForTesting
  @Nullable
  static StructOrError<LocalityLbEndpoints> parseLocalityLbEndpoints(
      io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints proto) {
    // Filter out localities without or with 0 weight.
    if (!proto.hasLoadBalancingWeight() || proto.getLoadBalancingWeight().getValue() < 1) {
      return null;
    }
    if (proto.getPriority() < 0) {
      return StructOrError.fromError("negative priority");
    }
    List<LbEndpoint> endpoints = new ArrayList<>(proto.getLbEndpointsCount());
    for (io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint endpoint : proto.getLbEndpointsList()) {
      // The endpoint field of each lb_endpoints must be set.
      // Inside of it: the address field must be set.
      if (!endpoint.hasEndpoint() || !endpoint.getEndpoint().hasAddress()) {
        return StructOrError.fromError("LbEndpoint with no endpoint/address");
      }
      io.envoyproxy.envoy.config.core.v3.SocketAddress socketAddress =
          endpoint.getEndpoint().getAddress().getSocketAddress();
      InetSocketAddress addr =
          new InetSocketAddress(socketAddress.getAddress(), socketAddress.getPortValue());
      boolean isHealthy =
          endpoint.getHealthStatus() == io.envoyproxy.envoy.config.core.v3.HealthStatus.HEALTHY
              || endpoint.getHealthStatus()
              == io.envoyproxy.envoy.config.core.v3.HealthStatus.UNKNOWN;
      endpoints.add(LbEndpoint.create(
          new EquivalentAddressGroup(ImmutableList.<java.net.SocketAddress>of(addr)),
          endpoint.getLoadBalancingWeight().getValue(), isHealthy));
    }
    return StructOrError.fromStruct(LocalityLbEndpoints.create(
        endpoints, proto.getLoadBalancingWeight().getValue(), proto.getPriority()));
  }

  /**
   * Helper method to unpack serialized {@link com.google.protobuf.Any} message, while replacing
   * Type URL {@code compatibleTypeUrl} with {@code typeUrl}.
   *
   * @param <T> The type of unpacked message
   * @param any serialized message to unpack
   * @param clazz the class to unpack the message to
   * @param typeUrl type URL to replace message Type URL, when it's compatible
   * @param compatibleTypeUrl compatible Type URL to be replaced with {@code typeUrl}
   * @return Unpacked message
   * @throws InvalidProtocolBufferException if the message couldn't be unpacked
   */
  private static <T extends com.google.protobuf.Message> T unpackCompatibleType(
      Any any, Class<T> clazz, String typeUrl, String compatibleTypeUrl)
      throws InvalidProtocolBufferException {
    if (any.getTypeUrl().equals(compatibleTypeUrl)) {
      any = any.toBuilder().setTypeUrl(typeUrl).build();
    }
    return any.unpack(clazz);
  }

  private static int getRatePerMillion(FractionalPercent percent) {
    int numerator = percent.getNumerator();
    DenominatorType type = percent.getDenominator();
    switch (type) {
      case TEN_THOUSAND:
        numerator *= 100;
        break;
      case HUNDRED:
        numerator *= 10_000;
        break;
      case MILLION:
        break;
      case UNRECOGNIZED:
      default:
        throw new IllegalArgumentException("Unknown denominator type of " + percent);
    }

    if (numerator > 1_000_000 || numerator < 0) {
      numerator = 1_000_000;
    }
    return numerator;
  }

  @Override
  protected void handleStreamClosed(Status error) {
    cleanUpResourceTimers();
    for (ResourceSubscriber subscriber : ldsResourceSubscribers.values()) {
      subscriber.onError(error);
    }
    for (ResourceSubscriber subscriber : rdsResourceSubscribers.values()) {
      subscriber.onError(error);
    }
    for (ResourceSubscriber subscriber : cdsResourceSubscribers.values()) {
      subscriber.onError(error);
    }
    for (ResourceSubscriber subscriber : edsResourceSubscribers.values()) {
      subscriber.onError(error);
    }
  }

  @Override
  protected void handleStreamRestarted() {
    for (ResourceSubscriber subscriber : ldsResourceSubscribers.values()) {
      subscriber.restartTimer();
    }
    for (ResourceSubscriber subscriber : rdsResourceSubscribers.values()) {
      subscriber.restartTimer();
    }
    for (ResourceSubscriber subscriber : cdsResourceSubscribers.values()) {
      subscriber.restartTimer();
    }
    for (ResourceSubscriber subscriber : edsResourceSubscribers.values()) {
      subscriber.restartTimer();
    }
  }

  @Override
  protected void handleShutdown() {
    if (reportingLoad) {
      lrsClient.stopLoadReporting();
    }
    cleanUpResourceTimers();
  }

  @Nullable
  @Override
  Collection<String> getSubscribedResources(ResourceType type) {
    switch (type) {
      case LDS:
        return ldsResourceSubscribers.isEmpty() ? null : ldsResourceSubscribers.keySet();
      case RDS:
        return rdsResourceSubscribers.isEmpty() ? null : rdsResourceSubscribers.keySet();
      case CDS:
        return cdsResourceSubscribers.isEmpty() ? null : cdsResourceSubscribers.keySet();
      case EDS:
        return edsResourceSubscribers.isEmpty() ? null : edsResourceSubscribers.keySet();
      case UNKNOWN:
      default:
        throw new AssertionError("Unknown resource type");
    }
  }

  @Override
  void watchLdsResource(final String resourceName, final LdsResourceWatcher watcher) {
    getSyncContext().execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = ldsResourceSubscribers.get(resourceName);
        if (subscriber == null) {
          getLogger().log(XdsLogLevel.INFO, "Subscribe LDS resource {0}", resourceName);
          subscriber = new ResourceSubscriber(ResourceType.LDS, resourceName);
          ldsResourceSubscribers.put(resourceName, subscriber);
          adjustResourceSubscription(ResourceType.LDS);
        }
        subscriber.addWatcher(watcher);
      }
    });
  }

  @Override
  void cancelLdsResourceWatch(final String resourceName, final LdsResourceWatcher watcher) {
    getSyncContext().execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = ldsResourceSubscribers.get(resourceName);
        subscriber.removeWatcher(watcher);
        if (!subscriber.isWatched()) {
          subscriber.stopTimer();
          getLogger().log(XdsLogLevel.INFO, "Unsubscribe LDS resource {0}", resourceName);
          ldsResourceSubscribers.remove(resourceName);
          adjustResourceSubscription(ResourceType.LDS);
        }
      }
    });
  }

  @Override
  void watchRdsResource(final String resourceName, final RdsResourceWatcher watcher) {
    getSyncContext().execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = rdsResourceSubscribers.get(resourceName);
        if (subscriber == null) {
          getLogger().log(XdsLogLevel.INFO, "Subscribe RDS resource {0}", resourceName);
          subscriber = new ResourceSubscriber(ResourceType.RDS, resourceName);
          rdsResourceSubscribers.put(resourceName, subscriber);
          adjustResourceSubscription(ResourceType.RDS);
        }
        subscriber.addWatcher(watcher);
      }
    });
  }

  @Override
  void cancelRdsResourceWatch(final String resourceName, final RdsResourceWatcher watcher) {
    getSyncContext().execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = rdsResourceSubscribers.get(resourceName);
        subscriber.removeWatcher(watcher);
        if (!subscriber.isWatched()) {
          subscriber.stopTimer();
          getLogger().log(XdsLogLevel.INFO, "Unsubscribe RDS resource {0}", resourceName);
          rdsResourceSubscribers.remove(resourceName);
          adjustResourceSubscription(ResourceType.RDS);
        }
      }
    });
  }

  @Override
  void watchCdsResource(final String resourceName, final CdsResourceWatcher watcher) {
    getSyncContext().execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = cdsResourceSubscribers.get(resourceName);
        if (subscriber == null) {
          getLogger().log(XdsLogLevel.INFO, "Subscribe CDS resource {0}", resourceName);
          subscriber = new ResourceSubscriber(ResourceType.CDS, resourceName);
          cdsResourceSubscribers.put(resourceName, subscriber);
          adjustResourceSubscription(ResourceType.CDS);
        }
        subscriber.addWatcher(watcher);
      }
    });
  }

  @Override
  void cancelCdsResourceWatch(final String resourceName, final CdsResourceWatcher watcher) {
    getSyncContext().execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = cdsResourceSubscribers.get(resourceName);
        subscriber.removeWatcher(watcher);
        if (!subscriber.isWatched()) {
          subscriber.stopTimer();
          getLogger().log(XdsLogLevel.INFO, "Unsubscribe CDS resource {0}", resourceName);
          cdsResourceSubscribers.remove(resourceName);
          adjustResourceSubscription(ResourceType.CDS);
        }
      }
    });
  }

  @Override
  void watchEdsResource(final String resourceName, final EdsResourceWatcher watcher) {
    getSyncContext().execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = edsResourceSubscribers.get(resourceName);
        if (subscriber == null) {
          getLogger().log(XdsLogLevel.INFO, "Subscribe EDS resource {0}", resourceName);
          subscriber = new ResourceSubscriber(ResourceType.EDS, resourceName);
          edsResourceSubscribers.put(resourceName, subscriber);
          adjustResourceSubscription(ResourceType.EDS);
        }
        subscriber.addWatcher(watcher);
      }
    });
  }

  @Override
  void cancelEdsResourceWatch(final String resourceName, final EdsResourceWatcher watcher) {
    getSyncContext().execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = edsResourceSubscribers.get(resourceName);
        subscriber.removeWatcher(watcher);
        if (!subscriber.isWatched()) {
          subscriber.stopTimer();
          getLogger().log(XdsLogLevel.INFO, "Unsubscribe EDS resource {0}", resourceName);
          edsResourceSubscribers.remove(resourceName);
          adjustResourceSubscription(ResourceType.EDS);
        }
      }
    });
  }

  @Override
  ClusterDropStats addClusterDropStats(String clusterName, @Nullable String edsServiceName) {
    ClusterDropStats dropCounter =
        loadStatsManager.getClusterDropStats(clusterName, edsServiceName);
    getSyncContext().execute(new Runnable() {
      @Override
      public void run() {
        if (!reportingLoad) {
          lrsClient.startLoadReporting();
          reportingLoad = true;
        }
      }
    });
    return dropCounter;
  }

  @Override
  ClusterLocalityStats addClusterLocalityStats(String clusterName,
      @Nullable String edsServiceName, Locality locality) {
    ClusterLocalityStats loadCounter =
        loadStatsManager.getClusterLocalityStats(clusterName, edsServiceName, locality);
    getSyncContext().execute(new Runnable() {
      @Override
      public void run() {
        if (!reportingLoad) {
          lrsClient.startLoadReporting();
          reportingLoad = true;
        }
      }
    });
    return loadCounter;
  }

  private void cleanUpResourceTimers() {
    for (ResourceSubscriber subscriber : ldsResourceSubscribers.values()) {
      subscriber.stopTimer();
    }
    for (ResourceSubscriber subscriber : rdsResourceSubscribers.values()) {
      subscriber.stopTimer();
    }
    for (ResourceSubscriber subscriber : cdsResourceSubscribers.values()) {
      subscriber.stopTimer();
    }
    for (ResourceSubscriber subscriber : edsResourceSubscribers.values()) {
      subscriber.stopTimer();
    }
  }

  /**
   * Tracks a single subscribed resource.
   */
  private final class ResourceSubscriber {
    private final ResourceType type;
    private final String resource;
    private final Set<ResourceWatcher> watchers = new HashSet<>();
    private ResourceUpdate data;
    private boolean absent;
    private ScheduledHandle respTimer;

    ResourceSubscriber(ResourceType type, String resource) {
      this.type = type;
      this.resource = resource;
      if (isInBackoff()) {
        return;
      }
      restartTimer();
    }

    void addWatcher(ResourceWatcher watcher) {
      checkArgument(!watchers.contains(watcher), "watcher %s already registered", watcher);
      watchers.add(watcher);
      if (data != null) {
        notifyWatcher(watcher, data);
      } else if (absent) {
        watcher.onResourceDoesNotExist(resource);
      }
    }

    void removeWatcher(ResourceWatcher watcher) {
      checkArgument(watchers.contains(watcher), "watcher %s not registered", watcher);
      watchers.remove(watcher);
    }

    void restartTimer() {
      if (data != null || absent) {  // resource already resolved
        return;
      }
      class ResourceNotFound implements Runnable {
        @Override
        public void run() {
          getLogger().log(XdsLogLevel.INFO, "{0} resource {1} initial fetch timeout",
              type, resource);
          respTimer = null;
          onAbsent();
        }

        @Override
        public String toString() {
          return type + this.getClass().getSimpleName();
        }
      }

      respTimer = getSyncContext().schedule(
          new ResourceNotFound(), INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS,
          getTimeService());
    }

    void stopTimer() {
      if (respTimer != null && respTimer.isPending()) {
        respTimer.cancel();
        respTimer = null;
      }
    }

    boolean isWatched() {
      return !watchers.isEmpty();
    }

    void onData(ResourceUpdate data) {
      if (respTimer != null && respTimer.isPending()) {
        respTimer.cancel();
        respTimer = null;
      }
      ResourceUpdate oldData = this.data;
      this.data = data;
      absent = false;
      if (!Objects.equals(oldData, data)) {
        for (ResourceWatcher watcher : watchers) {
          notifyWatcher(watcher, data);
        }
      }
    }

    void onAbsent() {
      if (respTimer != null && respTimer.isPending()) {  // too early to conclude absence
        return;
      }
      getLogger().log(XdsLogLevel.INFO, "Conclude {0} resource {1} not exist", type, resource);
      if (!absent) {
        data = null;
        absent = true;
        for (ResourceWatcher watcher : watchers) {
          watcher.onResourceDoesNotExist(resource);
        }
      }
    }

    void onError(Status error) {
      if (respTimer != null && respTimer.isPending()) {
        respTimer.cancel();
        respTimer = null;
      }
      for (ResourceWatcher watcher : watchers) {
        watcher.onError(error);
      }
    }

    private void notifyWatcher(ResourceWatcher watcher, ResourceUpdate update) {
      switch (type) {
        case LDS:
          ((LdsResourceWatcher) watcher).onChanged((LdsUpdate) update);
          break;
        case RDS:
          ((RdsResourceWatcher) watcher).onChanged((RdsUpdate) update);
          break;
        case CDS:
          ((CdsResourceWatcher) watcher).onChanged((CdsUpdate) update);
          break;
        case EDS:
          ((EdsResourceWatcher) watcher).onChanged((EdsUpdate) update);
          break;
        case UNKNOWN:
        default:
          throw new AssertionError("should never be here");
      }
    }
  }

  @VisibleForTesting
  static final class StructOrError<T> {

    /**
     * Returns a {@link StructOrError} for the successfully converted data object.
     */
    private static <T> StructOrError<T> fromStruct(T struct) {
      return new StructOrError<>(struct);
    }

    /**
     * Returns a {@link StructOrError} for the failure to convert the data object.
     */
    private static <T> StructOrError<T> fromError(String errorDetail) {
      return new StructOrError<>(errorDetail);
    }

    private final String errorDetail;
    private final T struct;

    private StructOrError(T struct) {
      this.struct = checkNotNull(struct, "struct");
      this.errorDetail = null;
    }

    private StructOrError(String errorDetail) {
      this.struct = null;
      this.errorDetail = checkNotNull(errorDetail, "errorDetail");
    }

    /**
     * Returns struct if exists, otherwise null.
     */
    @VisibleForTesting
    @Nullable
    T getStruct() {
      return struct;
    }

    /**
     * Returns error detail if exists, otherwise null.
     */
    @VisibleForTesting
    @Nullable
    String getErrorDetail() {
      return errorDetail;
    }
  }
}
