/*
 * Copyright 2022 The gRPC Authors
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
import static io.grpc.xds.AbstractXdsClient.ResourceType;
import static io.grpc.xds.Bootstrapper.ServerInfo;
import static io.grpc.xds.ClientXdsClient.ResourceInvalidException;
import static io.grpc.xds.XdsClient.ResourceUpdate;
import static io.grpc.xds.XdsClient.canonifyResourceName;
import static io.grpc.xds.XdsClient.isResourceNameValid;

import com.github.udpa.udpa.type.v1.TypedStruct;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.Durations;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import io.envoyproxy.envoy.config.core.v3.TypedExtensionConfig;
import io.envoyproxy.envoy.config.route.v3.ClusterSpecifierPlugin;
import io.envoyproxy.envoy.config.route.v3.RetryPolicy.RetryBackOff;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.service.discovery.v3.Resource;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.grpc.InternalLogId;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.xds.ClusterSpecifierPlugin.NamedPluginConfig;
import io.grpc.xds.ClusterSpecifierPlugin.PluginConfig;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.VirtualHost.Route.RouteAction;
import io.grpc.xds.VirtualHost.Route.RouteAction.ClusterWeight;
import io.grpc.xds.VirtualHost.Route.RouteAction.HashPolicy;
import io.grpc.xds.VirtualHost.Route.RouteAction.RetryPolicy;
import io.grpc.xds.VirtualHost.Route.RouteMatch;
import io.grpc.xds.VirtualHost.Route.RouteMatch.PathMatcher;
import io.grpc.xds.internal.Matchers.FractionMatcher;
import io.grpc.xds.internal.Matchers.HeaderMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

abstract class XdsResourceType {
  static final String TYPE_URL_RESOURCE_V2 = "type.googleapis.com/envoy.api.v2.Resource";
  static final String TYPE_URL_RESOURCE_V3 =
      "type.googleapis.com/envoy.service.discovery.v3.Resource";
  static final String TRANSPORT_SOCKET_NAME_TLS = "envoy.transport_sockets.tls";
  @VisibleForTesting
  static final String AGGREGATE_CLUSTER_TYPE_NAME = "envoy.clusters.aggregate";
  @VisibleForTesting
  static final String HASH_POLICY_FILTER_STATE_KEY = "io.grpc.channel_id";
  @VisibleForTesting
  static boolean enableFaultInjection =
      Strings.isNullOrEmpty(System.getenv("GRPC_XDS_EXPERIMENTAL_FAULT_INJECTION"))
          || Boolean.parseBoolean(System.getenv("GRPC_XDS_EXPERIMENTAL_FAULT_INJECTION"));
  @VisibleForTesting
  static boolean enableRetry =
      Strings.isNullOrEmpty(System.getenv("GRPC_XDS_EXPERIMENTAL_ENABLE_RETRY"))
          || Boolean.parseBoolean(System.getenv("GRPC_XDS_EXPERIMENTAL_ENABLE_RETRY"));
  @VisibleForTesting
  static boolean enableRbac =
      Strings.isNullOrEmpty(System.getenv("GRPC_XDS_EXPERIMENTAL_RBAC"))
          || Boolean.parseBoolean(System.getenv("GRPC_XDS_EXPERIMENTAL_RBAC"));
  @VisibleForTesting
  static boolean enableRouteLookup =
      !Strings.isNullOrEmpty(System.getenv("GRPC_EXPERIMENTAL_XDS_RLS_LB"))
          && Boolean.parseBoolean(System.getenv("GRPC_EXPERIMENTAL_XDS_RLS_LB"));
  @VisibleForTesting
  static boolean enableLeastRequest =
      !Strings.isNullOrEmpty(System.getenv("GRPC_EXPERIMENTAL_ENABLE_LEAST_REQUEST"))
          ? Boolean.parseBoolean(System.getenv("GRPC_EXPERIMENTAL_ENABLE_LEAST_REQUEST"))
          : Boolean.parseBoolean(System.getProperty("io.grpc.xds.experimentalEnableLeastRequest"));
  @VisibleForTesting
  static boolean enableCustomLbConfig =
      Strings.isNullOrEmpty(System.getenv("GRPC_EXPERIMENTAL_XDS_CUSTOM_LB_CONFIG"))
          || Boolean.parseBoolean(System.getenv("GRPC_EXPERIMENTAL_XDS_CUSTOM_LB_CONFIG"));
  static final String TYPE_URL_CLUSTER_CONFIG_V2 =
      "type.googleapis.com/envoy.config.cluster.aggregate.v2alpha.ClusterConfig";
  static final String TYPE_URL_CLUSTER_CONFIG =
      "type.googleapis.com/envoy.extensions.clusters.aggregate.v3.ClusterConfig";
  static final String TYPE_URL_TYPED_STRUCT_UDPA =
      "type.googleapis.com/udpa.type.v1.TypedStruct";
  static final String TYPE_URL_TYPED_STRUCT =
      "type.googleapis.com/xds.type.v3.TypedStruct";
  private static final String TYPE_URL_FILTER_CONFIG =
      "type.googleapis.com/envoy.config.route.v3.FilterConfig";
  // TODO(zdapeng): need to discuss how to handle unsupported values.
  private static final Set<Status.Code> SUPPORTED_RETRYABLE_CODES =
      Collections.unmodifiableSet(EnumSet.of(
          Code.CANCELLED, Code.DEADLINE_EXCEEDED, Code.INTERNAL, Code.RESOURCE_EXHAUSTED,
          Code.UNAVAILABLE));

  private final InternalLogId logId;
  private final XdsLogger logger;
  // Last successfully applied version_info for each resource type. Starts with empty string.
  // A version_info is used to update management server with client's most recent knowledge of
  // resources.
  protected String version = "";
  protected final SynchronizationContext syncContext;

  @Nullable
  abstract String extractResourceName(Message unpackedResource);

  @SuppressWarnings("unchecked")
  abstract Class<? extends com.google.protobuf.Message> unpackedClassName();

  abstract ResourceType typeName();

  abstract String typeUrl();

  abstract String typeUrlV2();

  // non-null for  State of the World resource
  @Nullable
  abstract ResourceType dependentResource();

  public XdsResourceType(SynchronizationContext syncContext) {
    logId = InternalLogId.allocate("xds-client", null);
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogger.XdsLogLevel.INFO, "Created");
    this.syncContext = syncContext;
  }

  ValidatedResourceUpdate parse(
      ServerInfo serverInfo, String versionInfo, List<Any> resources, String nonce) {
    syncContext.throwIfNotInThisSynchronizationContext();
    Map<String, ParsedResource> parsedResources = new HashMap<>(resources.size());
    Set<String> unpackedResources = new HashSet<>(resources.size());
    Set<String> invalidResources = new HashSet<>();
    List<String> errors = new ArrayList<>();
    Set<String> retainedRdsResources = new HashSet<>();

    for (int i = 0; i < resources.size(); i++) {
      Any resource = resources.get(i);

      boolean isResourceV3;
      Message unpackedMessage;
      try {
        resource = maybeUnwrapResources(resource);
        isResourceV3 = resource.getTypeUrl().equals(typeUrl());
        unpackedMessage = unpackCompatibleType(resource, unpackedClassName(),
            typeUrl(), typeUrlV2());
      } catch (InvalidProtocolBufferException e) {
        errors.add(String.format("%s response Resource index %d - can't decode %s: %s",
                typeName(), i, unpackedClassName().getSimpleName(), e.getMessage()));
        continue;
      }
      String name = extractResourceName(unpackedMessage);
      if (name == null || !isResourceNameValid(name, resource.getTypeUrl())) {
        errors.add(
            "Unsupported resource name: " + name + " for type: " + typeName());
        continue;
      }
      String cname = canonifyResourceName(name);
      unpackedResources.add(cname);

      XdsClient.ResourceUpdate resourceUpdate;
      try {
        resourceUpdate = doParse(serverInfo, unpackedMessage, retainedRdsResources, isResourceV3);
      } catch (ClientXdsClient.ResourceInvalidException e) {
        errors.add(String.format("%s response %s '%s' validation error: %s",
                typeName(), unpackedClassName().getSimpleName(), cname, e.getMessage()));
        invalidResources.add(cname);
        continue;
      }

      // LdsUpdate parsed successfully.
      parsedResources.put(cname, new ParsedResource(resourceUpdate, resource));
    }
    logger.log(XdsLogger.XdsLogLevel.INFO,
        "Received {0} Response version {1} nonce {2}. Parsed resources: {3}",
        typeName(), versionInfo, nonce, unpackedResources);
    return new ValidatedResourceUpdate(parsedResources, unpackedResources, invalidResources,
        errors, retainedRdsResources);

  }

  abstract ResourceUpdate doParse(ServerInfo serverInfo, Message unpackedMessage,
                                  Set<String> retainedResources,
                                  boolean isResourceV3)
      throws ResourceInvalidException;

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
  static <T extends com.google.protobuf.Message> T unpackCompatibleType(
      Any any, Class<T> clazz, String typeUrl, String compatibleTypeUrl)
      throws InvalidProtocolBufferException {
    if (any.getTypeUrl().equals(compatibleTypeUrl)) {
      any = any.toBuilder().setTypeUrl(typeUrl).build();
    }
    return any.unpack(clazz);
  }

  private Any maybeUnwrapResources(Any resource)
      throws InvalidProtocolBufferException {
    if (resource.getTypeUrl().equals(TYPE_URL_RESOURCE_V2)
        || resource.getTypeUrl().equals(TYPE_URL_RESOURCE_V3)) {
      return unpackCompatibleType(resource, Resource.class, TYPE_URL_RESOURCE_V3,
          TYPE_URL_RESOURCE_V2).getResource();
    } else {
      return resource;
    }
  }

  protected static List<VirtualHost> extractVirtualHosts(
      RouteConfiguration routeConfig, FilterRegistry filterRegistry, boolean parseHttpFilter)
      throws ResourceInvalidException {
    Map<String, PluginConfig> pluginConfigMap = new HashMap<>();
    ImmutableSet.Builder<String> optionalPlugins = ImmutableSet.builder();

    if (enableRouteLookup) {
      List<ClusterSpecifierPlugin> plugins = routeConfig.getClusterSpecifierPluginsList();
      for (ClusterSpecifierPlugin plugin : plugins) {
        String pluginName = plugin.getExtension().getName();
        PluginConfig pluginConfig = parseClusterSpecifierPlugin(plugin);
        if (pluginConfig != null) {
          if (pluginConfigMap.put(pluginName, pluginConfig) != null) {
            throw new ResourceInvalidException(
                "Multiple ClusterSpecifierPlugins with the same name: " + pluginName);
          }
        } else {
          // The plugin parsed successfully, and it's not supported, but it's marked as optional.
          optionalPlugins.add(pluginName);
        }
      }
    }
    List<VirtualHost> virtualHosts = new ArrayList<>(routeConfig.getVirtualHostsCount());
    for (io.envoyproxy.envoy.config.route.v3.VirtualHost virtualHostProto
        : routeConfig.getVirtualHostsList()) {
      StructOrError<VirtualHost> virtualHost =
          parseVirtualHost(virtualHostProto, filterRegistry, parseHttpFilter, pluginConfigMap,
              optionalPlugins.build());
      if (virtualHost.getErrorDetail() != null) {
        throw new ResourceInvalidException(
            "RouteConfiguration contains invalid virtual host: " + virtualHost.getErrorDetail());
      }
      virtualHosts.add(virtualHost.getStruct());
    }
    return virtualHosts;
  }

  private static StructOrError<VirtualHost> parseVirtualHost(
      io.envoyproxy.envoy.config.route.v3.VirtualHost proto, FilterRegistry filterRegistry,
      boolean parseHttpFilter, Map<String, PluginConfig> pluginConfigMap,
      Set<String> optionalPlugins) {
    String name = proto.getName();
    List<VirtualHost.Route> routes = new ArrayList<>(proto.getRoutesCount());
    for (io.envoyproxy.envoy.config.route.v3.Route routeProto : proto.getRoutesList()) {
      StructOrError<VirtualHost.Route> route = parseRoute(
          routeProto, filterRegistry, parseHttpFilter, pluginConfigMap, optionalPlugins);
      if (route == null) {
        continue;
      }
      if (route.getErrorDetail() != null) {
        return StructOrError.fromError(
            "Virtual host [" + name + "] contains invalid route : " + route.getErrorDetail());
      }
      routes.add(route.getStruct());
    }
    if (!parseHttpFilter) {
      return StructOrError.fromStruct(VirtualHost.create(
          name, proto.getDomainsList(), routes, new HashMap<String, FilterConfig>()));
    }
    StructOrError<Map<String, FilterConfig>> overrideConfigs =
        parseOverrideFilterConfigs(proto.getTypedPerFilterConfigMap(), filterRegistry);
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
      Map<String, Any> rawFilterConfigMap, FilterRegistry filterRegistry) {
    Map<String, FilterConfig> overrideConfigs = new HashMap<>();
    for (String name : rawFilterConfigMap.keySet()) {
      Any anyConfig = rawFilterConfigMap.get(name);
      String typeUrl = anyConfig.getTypeUrl();
      boolean isOptional = false;
      if (typeUrl.equals(TYPE_URL_FILTER_CONFIG)) {
        io.envoyproxy.envoy.config.route.v3.FilterConfig filterConfig;
        try {
          filterConfig =
              anyConfig.unpack(io.envoyproxy.envoy.config.route.v3.FilterConfig.class);
        } catch (InvalidProtocolBufferException e) {
          return StructOrError.fromError(
              "FilterConfig [" + name + "] contains invalid proto: " + e);
        }
        isOptional = filterConfig.getIsOptional();
        anyConfig = filterConfig.getConfig();
        typeUrl = anyConfig.getTypeUrl();
      }
      Message rawConfig = anyConfig;
      try {
        if (typeUrl.equals(TYPE_URL_TYPED_STRUCT_UDPA)) {
          TypedStruct typedStruct = anyConfig.unpack(TypedStruct.class);
          typeUrl = typedStruct.getTypeUrl();
          rawConfig = typedStruct.getValue();
        } else if (typeUrl.equals(TYPE_URL_TYPED_STRUCT)) {
          com.github.xds.type.v3.TypedStruct newTypedStruct =
              anyConfig.unpack(com.github.xds.type.v3.TypedStruct.class);
          typeUrl = newTypedStruct.getTypeUrl();
          rawConfig = newTypedStruct.getValue();
        }
      } catch (InvalidProtocolBufferException e) {
        return StructOrError.fromError(
            "FilterConfig [" + name + "] contains invalid proto: " + e);
      }
      Filter filter = filterRegistry.get(typeUrl);
      if (filter == null) {
        if (isOptional) {
          continue;
        }
        return StructOrError.fromError(
            "HttpFilter [" + name + "](" + typeUrl + ") is required but unsupported");
      }
      ConfigOrError<? extends Filter.FilterConfig> filterConfig =
          filter.parseFilterConfigOverride(rawConfig);
      if (filterConfig.errorDetail != null) {
        return StructOrError.fromError(
            "Invalid filter config for HttpFilter [" + name + "]: " + filterConfig.errorDetail);
      }
      overrideConfigs.put(name, filterConfig.config);
    }
    return StructOrError.fromStruct(overrideConfigs);
  }

  @VisibleForTesting
  @Nullable
  static StructOrError<Route> parseRoute(
      io.envoyproxy.envoy.config.route.v3.Route proto, FilterRegistry filterRegistry,
      boolean parseHttpFilter, Map<String, PluginConfig> pluginConfigMap,
      Set<String> optionalPlugins) {
    StructOrError<RouteMatch> routeMatch = parseRouteMatch(proto.getMatch());
    if (routeMatch == null) {
      return null;
    }
    if (routeMatch.getErrorDetail() != null) {
      return StructOrError.fromError(
          "Route [" + proto.getName() + "] contains invalid RouteMatch: "
              + routeMatch.getErrorDetail());
    }

    Map<String, FilterConfig> overrideConfigs = Collections.emptyMap();
    if (parseHttpFilter) {
      StructOrError<Map<String, FilterConfig>> overrideConfigsOrError =
          parseOverrideFilterConfigs(proto.getTypedPerFilterConfigMap(), filterRegistry);
      if (overrideConfigsOrError.errorDetail != null) {
        return StructOrError.fromError(
            "Route [" + proto.getName() + "] contains invalid HttpFilter config: "
                + overrideConfigsOrError.errorDetail);
      }
      overrideConfigs = overrideConfigsOrError.struct;
    }

    switch (proto.getActionCase()) {
      case ROUTE:
        StructOrError<RouteAction> routeAction =
            parseRouteAction(proto.getRoute(), filterRegistry, parseHttpFilter, pluginConfigMap,
                optionalPlugins);
        if (routeAction == null) {
          return null;
        }
        if (routeAction.errorDetail != null) {
          return StructOrError.fromError(
              "Route [" + proto.getName() + "] contains invalid RouteAction: "
                  + routeAction.getErrorDetail());
        }
        return StructOrError.fromStruct(
            Route.forAction(routeMatch.getStruct(), routeAction.struct, overrideConfigs));
      case NON_FORWARDING_ACTION:
        return StructOrError.fromStruct(
            Route.forNonForwardingAction(routeMatch.getStruct(), overrideConfigs));
      case REDIRECT:
      case DIRECT_RESPONSE:
      case FILTER_ACTION:
      case ACTION_NOT_SET:
      default:
        return StructOrError.fromError(
            "Route [" + proto.getName() + "] with unknown action type: " + proto.getActionCase());
    }
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
            VirtualHost.Route.RouteMatch.PathMatcher.fromPrefix(proto.getPrefix(), caseSensitive));
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

  /**
   * Parses the RouteAction config. The returned result may contain a (parsed form)
   * {@link RouteAction} or an error message. Returns {@code null} if the RouteAction
   * should be ignored.
   */
  @VisibleForTesting
  @Nullable
  static StructOrError<RouteAction> parseRouteAction(
      io.envoyproxy.envoy.config.route.v3.RouteAction proto, FilterRegistry filterRegistry,
      boolean parseHttpFilter, Map<String, PluginConfig> pluginConfigMap,
      Set<String> optionalPlugins) {
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
    RetryPolicy retryPolicy = null;
    if (enableRetry && proto.hasRetryPolicy()) {
      StructOrError<RetryPolicy> retryPolicyOrError = parseRetryPolicy(proto.getRetryPolicy());
      if (retryPolicyOrError != null) {
        if (retryPolicyOrError.errorDetail != null) {
          return StructOrError.fromError(retryPolicyOrError.errorDetail);
        }
        retryPolicy = retryPolicyOrError.struct;
      }
    }
    List<HashPolicy> hashPolicies = new ArrayList<>();
    for (io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy config
        : proto.getHashPolicyList()) {
      VirtualHost.Route.RouteAction.HashPolicy policy = null;
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
            proto.getCluster(), hashPolicies, timeoutNano, retryPolicy));
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
              parseClusterWeight(clusterWeight, filterRegistry, parseHttpFilter);
          if (clusterWeightOrError.getErrorDetail() != null) {
            return StructOrError.fromError("RouteAction contains invalid ClusterWeight: "
                + clusterWeightOrError.getErrorDetail());
          }
          weightedClusters.add(clusterWeightOrError.getStruct());
        }
        // TODO(chengyuanzhang): validate if the sum of weights equals to total weight.
        return StructOrError.fromStruct(VirtualHost.Route.RouteAction.forWeightedClusters(
            weightedClusters, hashPolicies, timeoutNano, retryPolicy));
      case CLUSTER_SPECIFIER_PLUGIN:
        if (enableRouteLookup) {
          String pluginName = proto.getClusterSpecifierPlugin();
          PluginConfig pluginConfig = pluginConfigMap.get(pluginName);
          if (pluginConfig == null) {
            // Skip route if the plugin is not registered, but it's optional.
            if (optionalPlugins.contains(pluginName)) {
              return null;
            }
            return StructOrError.fromError(
                "ClusterSpecifierPlugin for [" + pluginName + "] not found");
          }
          NamedPluginConfig namedPluginConfig = NamedPluginConfig.create(pluginName, pluginConfig);
          return StructOrError.fromStruct(RouteAction.forClusterSpecifierPlugin(
              namedPluginConfig, hashPolicies, timeoutNano, retryPolicy));
        } else {
          return null;
        }
      case CLUSTERSPECIFIER_NOT_SET:
      default:
        return null;
    }
  }

  @Nullable // Return null if we ignore the given policy.
  private static StructOrError<RetryPolicy> parseRetryPolicy(
      io.envoyproxy.envoy.config.route.v3.RetryPolicy retryPolicyProto) {
    int maxAttempts = 2;
    if (retryPolicyProto.hasNumRetries()) {
      maxAttempts = retryPolicyProto.getNumRetries().getValue() + 1;
    }
    Duration initialBackoff = Durations.fromMillis(25);
    Duration maxBackoff = Durations.fromMillis(250);
    if (retryPolicyProto.hasRetryBackOff()) {
      RetryBackOff retryBackOff = retryPolicyProto.getRetryBackOff();
      if (!retryBackOff.hasBaseInterval()) {
        return StructOrError.fromError("No base_interval specified in retry_backoff");
      }
      Duration originalInitialBackoff = initialBackoff = retryBackOff.getBaseInterval();
      if (Durations.compare(initialBackoff, Durations.ZERO) <= 0) {
        return StructOrError.fromError("base_interval in retry_backoff must be positive");
      }
      if (Durations.compare(initialBackoff, Durations.fromMillis(1)) < 0) {
        initialBackoff = Durations.fromMillis(1);
      }
      if (retryBackOff.hasMaxInterval()) {
        maxBackoff = retryPolicyProto.getRetryBackOff().getMaxInterval();
        if (Durations.compare(maxBackoff, originalInitialBackoff) < 0) {
          return StructOrError.fromError(
              "max_interval in retry_backoff cannot be less than base_interval");
        }
        if (Durations.compare(maxBackoff, Durations.fromMillis(1)) < 0) {
          maxBackoff = Durations.fromMillis(1);
        }
      } else {
        maxBackoff = Durations.fromNanos(Durations.toNanos(initialBackoff) * 10);
      }
    }
    Iterable<String> retryOns =
        Splitter.on(',').omitEmptyStrings().trimResults().split(retryPolicyProto.getRetryOn());
    ImmutableList.Builder<Status.Code> retryableStatusCodesBuilder = ImmutableList.builder();
    for (String retryOn : retryOns) {
      Status.Code code;
      try {
        code = Status.Code.valueOf(retryOn.toUpperCase(Locale.US).replace('-', '_'));
      } catch (IllegalArgumentException e) {
        // unsupported value, such as "5xx"
        continue;
      }
      if (!SUPPORTED_RETRYABLE_CODES.contains(code)) {
        // unsupported value
        continue;
      }
      retryableStatusCodesBuilder.add(code);
    }
    List<Status.Code> retryableStatusCodes = retryableStatusCodesBuilder.build();
    return StructOrError.fromStruct(
        RetryPolicy.create(
            maxAttempts, retryableStatusCodes, initialBackoff, maxBackoff,
            /* perAttemptRecvTimeout= */ null));
  }

  @VisibleForTesting
  static StructOrError<ClusterWeight> parseClusterWeight(
      io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight proto,
      FilterRegistry filterRegistry, boolean parseHttpFilter) {
    if (!parseHttpFilter) {
      return StructOrError.fromStruct(ClusterWeight.create(
          proto.getName(), proto.getWeight().getValue(), new HashMap<String, FilterConfig>()));
    }
    StructOrError<Map<String, Filter.FilterConfig>> overrideConfigs =
        parseOverrideFilterConfigs(proto.getTypedPerFilterConfigMap(), filterRegistry);
    if (overrideConfigs.errorDetail != null) {
      return StructOrError.fromError(
          "ClusterWeight [" + proto.getName() + "] contains invalid HttpFilter config: "
              + overrideConfigs.errorDetail);
    }
    return StructOrError.fromStruct(ClusterWeight.create(
        proto.getName(), proto.getWeight().getValue(), overrideConfigs.struct));
  }

  @Nullable // null if the plugin is not supported, but it's marked as optional.
  private static PluginConfig parseClusterSpecifierPlugin(ClusterSpecifierPlugin pluginProto)
      throws ResourceInvalidException {
    return parseClusterSpecifierPlugin(
        pluginProto, ClusterSpecifierPluginRegistry.getDefaultRegistry());
  }

  @Nullable // null if the plugin is not supported, but it's marked as optional.
  @VisibleForTesting
  static PluginConfig parseClusterSpecifierPlugin(
      ClusterSpecifierPlugin pluginProto, ClusterSpecifierPluginRegistry registry)
      throws ResourceInvalidException {
    TypedExtensionConfig extension = pluginProto.getExtension();
    String pluginName = extension.getName();
    Any anyConfig = extension.getTypedConfig();
    String typeUrl = anyConfig.getTypeUrl();
    Message rawConfig = anyConfig;
    if (typeUrl.equals(TYPE_URL_TYPED_STRUCT_UDPA) || typeUrl.equals(TYPE_URL_TYPED_STRUCT)) {
      try {
        TypedStruct typedStruct = unpackCompatibleType(
            anyConfig, TypedStruct.class, TYPE_URL_TYPED_STRUCT_UDPA, TYPE_URL_TYPED_STRUCT);
        typeUrl = typedStruct.getTypeUrl();
        rawConfig = typedStruct.getValue();
      } catch (InvalidProtocolBufferException e) {
        throw new ResourceInvalidException(
            "ClusterSpecifierPlugin [" + pluginName + "] contains invalid proto", e);
      }
    }
    io.grpc.xds.ClusterSpecifierPlugin plugin = registry.get(typeUrl);
    if (plugin == null) {
      if (!pluginProto.getIsOptional()) {
        throw new ResourceInvalidException("Unsupported ClusterSpecifierPlugin type: " + typeUrl);
      }
      return null;
    }
    ConfigOrError<? extends PluginConfig> pluginConfigOrError = plugin.parsePlugin(rawConfig);
    if (pluginConfigOrError.errorDetail != null) {
      throw new ResourceInvalidException(pluginConfigOrError.errorDetail);
    }
    return pluginConfigOrError.config;
  }

  @VisibleForTesting
  static void validateCommonTlsContext(
      CommonTlsContext commonTlsContext, Set<String> certProviderInstances, boolean server)
      throws ResourceInvalidException {
    if (commonTlsContext.hasCustomHandshaker()) {
      throw new ResourceInvalidException(
          "common-tls-context with custom_handshaker is not supported");
    }
    if (commonTlsContext.hasTlsParams()) {
      throw new ResourceInvalidException("common-tls-context with tls_params is not supported");
    }
    if (commonTlsContext.hasValidationContextSdsSecretConfig()) {
      throw new ResourceInvalidException(
          "common-tls-context with validation_context_sds_secret_config is not supported");
    }
    if (commonTlsContext.hasValidationContextCertificateProvider()) {
      throw new ResourceInvalidException(
          "common-tls-context with validation_context_certificate_provider is not supported");
    }
    if (commonTlsContext.hasValidationContextCertificateProviderInstance()) {
      throw new ResourceInvalidException(
          "common-tls-context with validation_context_certificate_provider_instance is not"
              + " supported");
    }
    String certInstanceName = getIdentityCertInstanceName(commonTlsContext);
    if (certInstanceName == null) {
      if (server) {
        throw new ResourceInvalidException(
            "tls_certificate_provider_instance is required in downstream-tls-context");
      }
      if (commonTlsContext.getTlsCertificatesCount() > 0) {
        throw new ResourceInvalidException(
            "tls_certificate_provider_instance is unset");
      }
      if (commonTlsContext.getTlsCertificateSdsSecretConfigsCount() > 0) {
        throw new ResourceInvalidException(
            "tls_certificate_provider_instance is unset");
      }
      if (commonTlsContext.hasTlsCertificateCertificateProvider()) {
        throw new ResourceInvalidException(
            "tls_certificate_provider_instance is unset");
      }
    } else if (certProviderInstances == null || !certProviderInstances.contains(certInstanceName)) {
      throw new ResourceInvalidException(
          "CertificateProvider instance name '" + certInstanceName
              + "' not defined in the bootstrap file.");
    }
    String rootCaInstanceName = getRootCertInstanceName(commonTlsContext);
    if (rootCaInstanceName == null) {
      if (!server) {
        throw new ResourceInvalidException(
            "ca_certificate_provider_instance is required in upstream-tls-context");
      }
    } else {
      if (certProviderInstances == null || !certProviderInstances.contains(rootCaInstanceName)) {
        throw new ResourceInvalidException(
            "ca_certificate_provider_instance name '" + rootCaInstanceName
                + "' not defined in the bootstrap file.");
      }
      CertificateValidationContext certificateValidationContext = null;
      if (commonTlsContext.hasValidationContext()) {
        certificateValidationContext = commonTlsContext.getValidationContext();
      } else if (commonTlsContext.hasCombinedValidationContext() && commonTlsContext
          .getCombinedValidationContext().hasDefaultValidationContext()) {
        certificateValidationContext = commonTlsContext.getCombinedValidationContext()
            .getDefaultValidationContext();
      }
      if (certificateValidationContext != null) {
        if (certificateValidationContext.getMatchSubjectAltNamesCount() > 0 && server) {
          throw new ResourceInvalidException(
              "match_subject_alt_names only allowed in upstream_tls_context");
        }
        if (certificateValidationContext.getVerifyCertificateSpkiCount() > 0) {
          throw new ResourceInvalidException(
              "verify_certificate_spki in default_validation_context is not supported");
        }
        if (certificateValidationContext.getVerifyCertificateHashCount() > 0) {
          throw new ResourceInvalidException(
              "verify_certificate_hash in default_validation_context is not supported");
        }
        if (certificateValidationContext.hasRequireSignedCertificateTimestamp()) {
          throw new ResourceInvalidException(
              "require_signed_certificate_timestamp in default_validation_context is not "
                  + "supported");
        }
        if (certificateValidationContext.hasCrl()) {
          throw new ResourceInvalidException("crl in default_validation_context is not supported");
        }
        if (certificateValidationContext.hasCustomValidatorConfig()) {
          throw new ResourceInvalidException(
              "custom_validator_config in default_validation_context is not supported");
        }
      }
    }
  }

  private static String getIdentityCertInstanceName(CommonTlsContext commonTlsContext) {
    if (commonTlsContext.hasTlsCertificateProviderInstance()) {
      return commonTlsContext.getTlsCertificateProviderInstance().getInstanceName();
    } else if (commonTlsContext.hasTlsCertificateCertificateProviderInstance()) {
      return commonTlsContext.getTlsCertificateCertificateProviderInstance().getInstanceName();
    }
    return null;
  }

  private static String getRootCertInstanceName(CommonTlsContext commonTlsContext) {
    if (commonTlsContext.hasValidationContext()) {
      if (commonTlsContext.getValidationContext().hasCaCertificateProviderInstance()) {
        return commonTlsContext.getValidationContext().getCaCertificateProviderInstance()
            .getInstanceName();
      }
    } else if (commonTlsContext.hasCombinedValidationContext()) {
      CommonTlsContext.CombinedCertificateValidationContext combinedCertificateValidationContext
          = commonTlsContext.getCombinedValidationContext();
      if (combinedCertificateValidationContext.hasDefaultValidationContext()
          && combinedCertificateValidationContext.getDefaultValidationContext()
          .hasCaCertificateProviderInstance()) {
        return combinedCertificateValidationContext.getDefaultValidationContext()
            .getCaCertificateProviderInstance().getInstanceName();
      } else if (combinedCertificateValidationContext
          .hasValidationContextCertificateProviderInstance()) {
        return combinedCertificateValidationContext
            .getValidationContextCertificateProviderInstance().getInstanceName();
      }
    }
    return null;
  }

  static final class ParsedResource {
    private final XdsClient.ResourceUpdate resourceUpdate;
    private final Any rawResource;

    public ParsedResource(ResourceUpdate resourceUpdate, Any rawResource) {
      this.resourceUpdate = checkNotNull(resourceUpdate, "resourceUpdate");
      this.rawResource = checkNotNull(rawResource, "rawResource");
    }

    ResourceUpdate getResourceUpdate() {
      return resourceUpdate;
    }

    Any getRawResource() {
      return rawResource;
    }
  }

  static final class ValidatedResourceUpdate {
    Map<String, ParsedResource> parsedResources;
    Set<String> unpackedResources;
    Set<String> invalidResources;
    List<String> errors;
    Set<String> retainedRdsResources;

    // validated resource update
    public ValidatedResourceUpdate(Map<String, ParsedResource> parsedResources,
                                   Set<String> unpackedResources,
                                   Set<String> invalidResources,
                                   List<String> errors,
                                   Set<String> retainedRdsResources) {
      this.parsedResources = parsedResources;
      this.unpackedResources = unpackedResources;
      this.invalidResources = invalidResources;
      this.errors = errors;
      this.retainedRdsResources = retainedRdsResources;
    }
  }

  @VisibleForTesting
  static final class StructOrError<T> {

    /**
    * Returns a {@link StructOrError} for the successfully converted data object.
    */
    static <T> StructOrError<T> fromStruct(T struct) {
      return new StructOrError<>(struct);
    }

    /**
     * Returns a {@link StructOrError} for the failure to convert the data object.
     */
    static <T> StructOrError<T> fromError(String errorDetail) {
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
