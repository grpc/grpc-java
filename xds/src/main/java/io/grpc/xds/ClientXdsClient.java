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

import com.github.udpa.udpa.type.v1.TypedStruct;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;
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
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.core.v3.SocketAddress.PortSpecifierCase;
import io.envoyproxy.envoy.config.core.v3.TrafficDirection;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RetryPolicy.RetryBackOff;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.envoyproxy.envoy.type.v3.FractionalPercent.DenominatorType;
import io.grpc.ChannelCredentials;
import io.grpc.Context;
import io.grpc.EquivalentAddressGroup;
import io.grpc.Grpc;
import io.grpc.InternalLogId;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.TimeProvider;
import io.grpc.xds.AbstractXdsClient.ResourceType;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.Endpoints.DropOverload;
import io.grpc.xds.Endpoints.LbEndpoint;
import io.grpc.xds.Endpoints.LocalityLbEndpoints;
import io.grpc.xds.EnvoyServerProtoData.CidrRange;
import io.grpc.xds.EnvoyServerProtoData.ConnectionSourceType;
import io.grpc.xds.EnvoyServerProtoData.FilterChain;
import io.grpc.xds.EnvoyServerProtoData.FilterChainMatch;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.Filter.ClientInterceptorBuilder;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.Filter.NamedFilterConfig;
import io.grpc.xds.Filter.ServerInterceptorBuilder;
import io.grpc.xds.LoadStatsManager2.ClusterDropStats;
import io.grpc.xds.LoadStatsManager2.ClusterLocalityStats;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.VirtualHost.Route.RouteAction;
import io.grpc.xds.VirtualHost.Route.RouteAction.ClusterWeight;
import io.grpc.xds.VirtualHost.Route.RouteAction.HashPolicy;
import io.grpc.xds.VirtualHost.Route.RouteAction.RetryPolicy;
import io.grpc.xds.VirtualHost.Route.RouteMatch;
import io.grpc.xds.VirtualHost.Route.RouteMatch.PathMatcher;
import io.grpc.xds.XdsClient.ResourceStore;
import io.grpc.xds.XdsClient.XdsResponseHandler;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.internal.Matchers.FractionMatcher;
import io.grpc.xds.internal.Matchers.HeaderMatcher;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * XdsClient implementation for client side usages.
 */
final class ClientXdsClient extends XdsClient implements XdsResponseHandler, ResourceStore {

  // Longest time to wait, since the subscription to some resource, for concluding its absence.
  @VisibleForTesting
  static final int INITIAL_RESOURCE_FETCH_TIMEOUT_SEC = 15;
  private static final String TRANSPORT_SOCKET_NAME_TLS = "envoy.transport_sockets.tls";
  @VisibleForTesting
  static final long DEFAULT_RING_HASH_LB_POLICY_MIN_RING_SIZE = 1024L;
  @VisibleForTesting
  static final long DEFAULT_RING_HASH_LB_POLICY_MAX_RING_SIZE = 8 * 1024 * 1024L;
  @VisibleForTesting
  static final long MAX_RING_HASH_LB_POLICY_RING_SIZE = 8 * 1024 * 1024L;
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
  // TODO(zdapeng): need to discuss how to handle unsupported values.
  private static final Set<Code> SUPPORTED_RETRYABLE_CODES =
      Collections.unmodifiableSet(EnumSet.of(
          Code.CANCELLED, Code.DEADLINE_EXCEEDED, Code.INTERNAL, Code.RESOURCE_EXHAUSTED,
          Code.UNAVAILABLE));

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          logger.log(
              XdsLogLevel.ERROR,
              "Uncaught exception in XdsClient SynchronizationContext. Panic!",
              e);
          // TODO(chengyuanzhang): better error handling.
          throw new AssertionError(e);
        }
      });
  private final FilterRegistry filterRegistry = FilterRegistry.getDefaultRegistry();
  private final Map<ServerInfo, AbstractXdsClient> serverChannelMap = new HashMap<>();
  private final Map<String, ResourceSubscriber> ldsResourceSubscribers = new HashMap<>();
  private final Map<String, ResourceSubscriber> rdsResourceSubscribers = new HashMap<>();
  private final Map<String, ResourceSubscriber> cdsResourceSubscribers = new HashMap<>();
  private final Map<String, ResourceSubscriber> edsResourceSubscribers = new HashMap<>();
  private final LoadStatsManager2 loadStatsManager;
  private final Map<ServerInfo, LoadReportClient> serverLrsClientMap = new HashMap<>();
  private final XdsChannelFactory xdsChannelFactory;
  private final Bootstrapper.BootstrapInfo bootstrapInfo;
  private final Context context;
  private final ScheduledExecutorService timeService;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final Supplier<Stopwatch> stopwatchSupplier;
  private final TimeProvider timeProvider;
  private boolean reportingLoad;
  private final TlsContextManager tlsContextManager;
  private final InternalLogId logId;
  private final XdsLogger logger;
  private volatile boolean isShutdown;

  // TODO(zdapeng): rename to XdsClientImpl
  ClientXdsClient(
      XdsChannelFactory xdsChannelFactory,
      Bootstrapper.BootstrapInfo bootstrapInfo,
      Context context,
      ScheduledExecutorService timeService,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier,
      TimeProvider timeProvider,
      TlsContextManager tlsContextManager) {
    this.xdsChannelFactory = xdsChannelFactory;
    this.bootstrapInfo = bootstrapInfo;
    this.context = context;
    this.timeService = timeService;
    loadStatsManager = new LoadStatsManager2(stopwatchSupplier);
    this.backoffPolicyProvider = backoffPolicyProvider;
    this.stopwatchSupplier = stopwatchSupplier;
    this.timeProvider = timeProvider;
    this.tlsContextManager = checkNotNull(tlsContextManager, "tlsContextManager");
    logId = InternalLogId.allocate("xds-client", null);
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogLevel.INFO, "Created");
  }

  private void maybeCreateXdsChannelWithLrs(ServerInfo serverInfo) {
    syncContext.throwIfNotInThisSynchronizationContext();
    if (serverChannelMap.containsKey(serverInfo)) {
      return;
    }
    AbstractXdsClient xdsChannel = new AbstractXdsClient(
        xdsChannelFactory,
        serverInfo,
        bootstrapInfo.node(),
        this,
        this,
        context,
        timeService,
        syncContext,
        backoffPolicyProvider,
        stopwatchSupplier);
    LoadReportClient lrsClient = new LoadReportClient(
        loadStatsManager, xdsChannel.channel(), context, serverInfo.useProtocolV3(),
        bootstrapInfo.node(), syncContext, timeService, backoffPolicyProvider, stopwatchSupplier);
    serverChannelMap.put(serverInfo, xdsChannel);
    serverLrsClientMap.put(serverInfo, lrsClient);
  }

  @Override
  public void handleLdsResponse(
      ServerInfo serverInfo, String versionInfo, List<Any> resources, String nonce) {
    syncContext.throwIfNotInThisSynchronizationContext();
    Map<String, ParsedResource> parsedResources = new HashMap<>(resources.size());
    Set<String> unpackedResources = new HashSet<>(resources.size());
    Set<String> invalidResources = new HashSet<>();
    List<String> errors = new ArrayList<>();
    Set<String> retainedRdsResources = new HashSet<>();

    for (int i = 0; i < resources.size(); i++) {
      Any resource = resources.get(i);

      // Unpack the Listener.
      boolean isResourceV3 = resource.getTypeUrl().equals(ResourceType.LDS.typeUrl());
      Listener listener;
      try {
        listener = unpackCompatibleType(resource, Listener.class, ResourceType.LDS.typeUrl(),
            ResourceType.LDS.typeUrlV2());
      } catch (InvalidProtocolBufferException e) {
        errors.add("LDS response Resource index " + i + " - can't decode Listener: " + e);
        continue;
      }
      String listenerName = listener.getName();
      unpackedResources.add(listenerName);

      // Process Listener into LdsUpdate.
      LdsUpdate ldsUpdate;
      try {
        if (listener.hasApiListener()) {
          ldsUpdate = processClientSideListener(
              listener, retainedRdsResources, enableFaultInjection && isResourceV3);
        } else {
          ldsUpdate = processServerSideListener(
              listener, retainedRdsResources, enableRbac && isResourceV3);
        }
      } catch (ResourceInvalidException e) {
        errors.add(
            "LDS response Listener '" + listenerName + "' validation error: " + e.getMessage());
        invalidResources.add(listenerName);
        continue;
      }

      // LdsUpdate parsed successfully.
      parsedResources.put(listenerName, new ParsedResource(ldsUpdate, resource));
    }
    logger.log(XdsLogLevel.INFO,
        "Received LDS Response version {0} nonce {1}. Parsed resources: {2}",
        versionInfo, nonce, unpackedResources);
    handleResourceUpdate(
        serverInfo, ResourceType.LDS, parsedResources, invalidResources, retainedRdsResources,
        versionInfo, nonce, errors);
  }

  private LdsUpdate processClientSideListener(
      Listener listener, Set<String> rdsResources, boolean parseHttpFilter)
      throws ResourceInvalidException {
    // Unpack HttpConnectionManager from the Listener.
    HttpConnectionManager hcm;
    try {
      hcm = unpackCompatibleType(
          listener.getApiListener().getApiListener(), HttpConnectionManager.class,
          TYPE_URL_HTTP_CONNECTION_MANAGER, TYPE_URL_HTTP_CONNECTION_MANAGER_V2);
    } catch (InvalidProtocolBufferException e) {
      throw new ResourceInvalidException(
          "Could not parse HttpConnectionManager config from ApiListener", e);
    }
    return LdsUpdate.forApiListener(parseHttpConnectionManager(
        hcm, rdsResources, filterRegistry, parseHttpFilter, true /* isForClient */));
  }

  private LdsUpdate processServerSideListener(
      Listener proto, Set<String> rdsResources, boolean parseHttpFilter)
      throws ResourceInvalidException {
    Set<String> certProviderInstances = null;
    if (getBootstrapInfo() != null && getBootstrapInfo().certProviders() != null) {
      certProviderInstances = getBootstrapInfo().certProviders().keySet();
    }
    return LdsUpdate.forTcpListener(parseServerSideListener(
        proto, rdsResources, tlsContextManager, filterRegistry, certProviderInstances,
        parseHttpFilter));
  }

  @VisibleForTesting
  static EnvoyServerProtoData.Listener parseServerSideListener(
      Listener proto, Set<String> rdsResources, TlsContextManager tlsContextManager,
      FilterRegistry filterRegistry, Set<String> certProviderInstances, boolean parseHttpFilter)
      throws ResourceInvalidException {
    if (!proto.getTrafficDirection().equals(TrafficDirection.INBOUND)) {
      throw new ResourceInvalidException(
          "Listener " + proto.getName() + " with invalid traffic direction: "
              + proto.getTrafficDirection());
    }
    if (!proto.getListenerFiltersList().isEmpty()) {
      throw new ResourceInvalidException(
          "Listener " + proto.getName() + " cannot have listener_filters");
    }
    if (proto.hasUseOriginalDst()) {
      throw new ResourceInvalidException(
          "Listener " + proto.getName() + " cannot have use_original_dst set to true");
    }

    String address = null;
    if (proto.getAddress().hasSocketAddress()) {
      SocketAddress socketAddress = proto.getAddress().getSocketAddress();
      address = socketAddress.getAddress();
      switch (socketAddress.getPortSpecifierCase()) {
        case NAMED_PORT:
          address = address + ":" + socketAddress.getNamedPort();
          break;
        case PORT_VALUE:
          address = address + ":" + socketAddress.getPortValue();
          break;
        default:
          // noop
      }
    }

    List<FilterChain> filterChains = new ArrayList<>();
    Set<FilterChainMatch> uniqueSet = new HashSet<>();
    for (io.envoyproxy.envoy.config.listener.v3.FilterChain fc : proto.getFilterChainsList()) {
      filterChains.add(
          parseFilterChain(fc, rdsResources, tlsContextManager, filterRegistry, uniqueSet,
              certProviderInstances, parseHttpFilter));
    }
    FilterChain defaultFilterChain = null;
    if (proto.hasDefaultFilterChain()) {
      defaultFilterChain = parseFilterChain(
          proto.getDefaultFilterChain(), rdsResources, tlsContextManager, filterRegistry,
          null, certProviderInstances, parseHttpFilter);
    }

    return new EnvoyServerProtoData.Listener(
        proto.getName(), address, Collections.unmodifiableList(filterChains), defaultFilterChain);
  }

  @VisibleForTesting
  static FilterChain parseFilterChain(
      io.envoyproxy.envoy.config.listener.v3.FilterChain proto, Set<String> rdsResources,
      TlsContextManager tlsContextManager, FilterRegistry filterRegistry,
      Set<FilterChainMatch> uniqueSet, Set<String> certProviderInstances, boolean parseHttpFilters)
      throws ResourceInvalidException {
    if (proto.getFiltersCount() != 1) {
      throw new ResourceInvalidException("FilterChain " + proto.getName()
              + " should contain exact one HttpConnectionManager filter");
    }
    io.envoyproxy.envoy.config.listener.v3.Filter filter = proto.getFiltersList().get(0);
    if (!filter.hasTypedConfig()) {
      throw new ResourceInvalidException(
          "FilterChain " + proto.getName() + " contains filter " + filter.getName()
              + " without typed_config");
    }
    Any any = filter.getTypedConfig();
    // HttpConnectionManager is the only supported network filter at the moment.
    if (!any.getTypeUrl().equals(TYPE_URL_HTTP_CONNECTION_MANAGER)) {
      throw new ResourceInvalidException(
          "FilterChain " + proto.getName() + " contains filter " + filter.getName()
              + " with unsupported typed_config type " + any.getTypeUrl());
    }
    HttpConnectionManager hcmProto;
    try {
      hcmProto = any.unpack(HttpConnectionManager.class);
    } catch (InvalidProtocolBufferException e) {
      throw new ResourceInvalidException("FilterChain " + proto.getName() + " with filter "
          + filter.getName() + " failed to unpack message", e);
    }
    io.grpc.xds.HttpConnectionManager httpConnectionManager = parseHttpConnectionManager(
            hcmProto, rdsResources, filterRegistry, parseHttpFilters, false /* isForClient */);

    EnvoyServerProtoData.DownstreamTlsContext downstreamTlsContext = null;
    if (proto.hasTransportSocket()) {
      if (!TRANSPORT_SOCKET_NAME_TLS.equals(proto.getTransportSocket().getName())) {
        throw new ResourceInvalidException("transport-socket with name "
            + proto.getTransportSocket().getName() + " not supported.");
      }
      DownstreamTlsContext downstreamTlsContextProto;
      try {
        downstreamTlsContextProto =
            proto.getTransportSocket().getTypedConfig().unpack(DownstreamTlsContext.class);
      } catch (InvalidProtocolBufferException e) {
        throw new ResourceInvalidException("FilterChain " + proto.getName()
            + " failed to unpack message", e);
      }
      downstreamTlsContext =
          EnvoyServerProtoData.DownstreamTlsContext.fromEnvoyProtoDownstreamTlsContext(
              validateDownstreamTlsContext(downstreamTlsContextProto, certProviderInstances));
    }

    FilterChainMatch filterChainMatch = parseFilterChainMatch(proto.getFilterChainMatch());
    checkForUniqueness(uniqueSet, filterChainMatch);
    return new FilterChain(
        proto.getName(),
        filterChainMatch,
        httpConnectionManager,
        downstreamTlsContext,
        tlsContextManager
    );
  }

  @VisibleForTesting
  static DownstreamTlsContext validateDownstreamTlsContext(
      DownstreamTlsContext downstreamTlsContext, Set<String> certProviderInstances)
      throws ResourceInvalidException {
    if (downstreamTlsContext.hasCommonTlsContext()) {
      validateCommonTlsContext(downstreamTlsContext.getCommonTlsContext(), certProviderInstances,
          true);
    } else {
      throw new ResourceInvalidException(
          "common-tls-context is required in downstream-tls-context");
    }
    if (downstreamTlsContext.hasRequireSni()) {
      throw new ResourceInvalidException(
          "downstream-tls-context with require-sni is not supported");
    }
    DownstreamTlsContext.OcspStaplePolicy ocspStaplePolicy = downstreamTlsContext
        .getOcspStaplePolicy();
    if (ocspStaplePolicy != DownstreamTlsContext.OcspStaplePolicy.UNRECOGNIZED
        && ocspStaplePolicy != DownstreamTlsContext.OcspStaplePolicy.LENIENT_STAPLING) {
      throw new ResourceInvalidException(
          "downstream-tls-context with ocsp_staple_policy value " + ocspStaplePolicy.name()
              + " is not supported");
    }
    return downstreamTlsContext;
  }

  @VisibleForTesting
  static io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext
      validateUpstreamTlsContext(
      io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext upstreamTlsContext,
      Set<String> certProviderInstances)
      throws ResourceInvalidException {
    if (upstreamTlsContext.hasCommonTlsContext()) {
      validateCommonTlsContext(upstreamTlsContext.getCommonTlsContext(), certProviderInstances,
          false);
    } else {
      throw new ResourceInvalidException("common-tls-context is required in upstream-tls-context");
    }
    return upstreamTlsContext;
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

  private static void checkForUniqueness(Set<FilterChainMatch> uniqueSet,
      FilterChainMatch filterChainMatch) throws ResourceInvalidException {
    if (uniqueSet != null) {
      List<FilterChainMatch> crossProduct = getCrossProduct(filterChainMatch);
      for (FilterChainMatch cur : crossProduct) {
        if (!uniqueSet.add(cur)) {
          throw new ResourceInvalidException("FilterChainMatch must be unique. "
              + "Found duplicate: " + cur);
        }
      }
    }
  }

  private static List<FilterChainMatch> getCrossProduct(FilterChainMatch filterChainMatch) {
    // repeating fields to process:
    // prefixRanges, applicationProtocols, sourcePrefixRanges, sourcePorts, serverNames
    List<FilterChainMatch> expandedList = expandOnPrefixRange(filterChainMatch);
    expandedList = expandOnApplicationProtocols(expandedList);
    expandedList = expandOnSourcePrefixRange(expandedList);
    expandedList = expandOnSourcePorts(expandedList);
    return expandOnServerNames(expandedList);
  }

  private static List<FilterChainMatch> expandOnPrefixRange(FilterChainMatch filterChainMatch) {
    ArrayList<FilterChainMatch> expandedList = new ArrayList<>();
    if (filterChainMatch.getPrefixRanges().isEmpty()) {
      expandedList.add(filterChainMatch);
    } else {
      for (EnvoyServerProtoData.CidrRange cidrRange : filterChainMatch.getPrefixRanges()) {
        expandedList.add(new FilterChainMatch(filterChainMatch.getDestinationPort(),
            Arrays.asList(cidrRange),
            Collections.unmodifiableList(filterChainMatch.getApplicationProtocols()),
            Collections.unmodifiableList(filterChainMatch.getSourcePrefixRanges()),
            filterChainMatch.getConnectionSourceType(),
            Collections.unmodifiableList(filterChainMatch.getSourcePorts()),
            Collections.unmodifiableList(filterChainMatch.getServerNames()),
            filterChainMatch.getTransportProtocol()));
      }
    }
    return expandedList;
  }

  private static List<FilterChainMatch> expandOnApplicationProtocols(
      Collection<FilterChainMatch> set) {
    ArrayList<FilterChainMatch> expandedList = new ArrayList<>();
    for (FilterChainMatch filterChainMatch : set) {
      if (filterChainMatch.getApplicationProtocols().isEmpty()) {
        expandedList.add(filterChainMatch);
      } else {
        for (String applicationProtocol : filterChainMatch.getApplicationProtocols()) {
          expandedList.add(new FilterChainMatch(filterChainMatch.getDestinationPort(),
              Collections.unmodifiableList(filterChainMatch.getPrefixRanges()),
              Arrays.asList(applicationProtocol),
              Collections.unmodifiableList(filterChainMatch.getSourcePrefixRanges()),
              filterChainMatch.getConnectionSourceType(),
              Collections.unmodifiableList(filterChainMatch.getSourcePorts()),
              Collections.unmodifiableList(filterChainMatch.getServerNames()),
              filterChainMatch.getTransportProtocol()));
        }
      }
    }
    return expandedList;
  }

  private static List<FilterChainMatch> expandOnSourcePrefixRange(
      Collection<FilterChainMatch> set) {
    ArrayList<FilterChainMatch> expandedList = new ArrayList<>();
    for (FilterChainMatch filterChainMatch : set) {
      if (filterChainMatch.getSourcePrefixRanges().isEmpty()) {
        expandedList.add(filterChainMatch);
      } else {
        for (EnvoyServerProtoData.CidrRange cidrRange : filterChainMatch.getSourcePrefixRanges()) {
          expandedList.add(new FilterChainMatch(filterChainMatch.getDestinationPort(),
              Collections.unmodifiableList(filterChainMatch.getPrefixRanges()),
              Collections.unmodifiableList(filterChainMatch.getApplicationProtocols()),
              Arrays.asList(cidrRange),
              filterChainMatch.getConnectionSourceType(),
              Collections.unmodifiableList(filterChainMatch.getSourcePorts()),
              Collections.unmodifiableList(filterChainMatch.getServerNames()),
              filterChainMatch.getTransportProtocol()));
        }
      }
    }
    return expandedList;
  }

  private static List<FilterChainMatch> expandOnSourcePorts(Collection<FilterChainMatch> set) {
    ArrayList<FilterChainMatch> expandedList = new ArrayList<>();
    for (FilterChainMatch filterChainMatch : set) {
      if (filterChainMatch.getSourcePorts().isEmpty()) {
        expandedList.add(filterChainMatch);
      } else {
        for (Integer sourcePort : filterChainMatch.getSourcePorts()) {
          expandedList.add(new FilterChainMatch(filterChainMatch.getDestinationPort(),
              Collections.unmodifiableList(filterChainMatch.getPrefixRanges()),
              Collections.unmodifiableList(filterChainMatch.getApplicationProtocols()),
              Collections.unmodifiableList(filterChainMatch.getSourcePrefixRanges()),
              filterChainMatch.getConnectionSourceType(),
              Arrays.asList(sourcePort),
              Collections.unmodifiableList(filterChainMatch.getServerNames()),
              filterChainMatch.getTransportProtocol()));
        }
      }
    }
    return expandedList;
  }

  private static List<FilterChainMatch> expandOnServerNames(Collection<FilterChainMatch> set) {
    ArrayList<FilterChainMatch> expandedList = new ArrayList<>();
    for (FilterChainMatch filterChainMatch : set) {
      if (filterChainMatch.getServerNames().isEmpty()) {
        expandedList.add(filterChainMatch);
      } else {
        for (String serverName : filterChainMatch.getServerNames()) {
          expandedList.add(new FilterChainMatch(filterChainMatch.getDestinationPort(),
              Collections.unmodifiableList(filterChainMatch.getPrefixRanges()),
              Collections.unmodifiableList(filterChainMatch.getApplicationProtocols()),
              Collections.unmodifiableList(filterChainMatch.getSourcePrefixRanges()),
              filterChainMatch.getConnectionSourceType(),
              Collections.unmodifiableList(filterChainMatch.getSourcePorts()),
              Arrays.asList(serverName),
              filterChainMatch.getTransportProtocol()));
        }
      }
    }
    return expandedList;
  }

  private static FilterChainMatch parseFilterChainMatch(
      io.envoyproxy.envoy.config.listener.v3.FilterChainMatch proto)
      throws ResourceInvalidException {
    List<CidrRange> prefixRanges = new ArrayList<>();
    List<CidrRange> sourcePrefixRanges = new ArrayList<>();
    try {
      for (io.envoyproxy.envoy.config.core.v3.CidrRange range : proto.getPrefixRangesList()) {
        prefixRanges.add(new CidrRange(range.getAddressPrefix(), range.getPrefixLen().getValue()));
      }
      for (io.envoyproxy.envoy.config.core.v3.CidrRange range
          : proto.getSourcePrefixRangesList()) {
        sourcePrefixRanges.add(
            new CidrRange(range.getAddressPrefix(), range.getPrefixLen().getValue()));
      }
    } catch (UnknownHostException e) {
      throw new ResourceInvalidException("Failed to create CidrRange", e);
    }
    ConnectionSourceType sourceType;
    switch (proto.getSourceType()) {
      case ANY:
        sourceType = ConnectionSourceType.ANY;
        break;
      case EXTERNAL:
        sourceType = ConnectionSourceType.EXTERNAL;
        break;
      case SAME_IP_OR_LOOPBACK:
        sourceType = ConnectionSourceType.SAME_IP_OR_LOOPBACK;
        break;
      default:
        throw new ResourceInvalidException("Unknown source-type: " + proto.getSourceType());
    }
    return new FilterChainMatch(
        proto.getDestinationPort().getValue(),
        prefixRanges,
        proto.getApplicationProtocolsList(),
        sourcePrefixRanges,
        sourceType,
        proto.getSourcePortsList(),
        proto.getServerNamesList(),
        proto.getTransportProtocol());
  }

  @VisibleForTesting
  static io.grpc.xds.HttpConnectionManager parseHttpConnectionManager(
      HttpConnectionManager proto, Set<String> rdsResources, FilterRegistry filterRegistry,
      boolean parseHttpFilter, boolean isForClient) throws ResourceInvalidException {
    if (enableRbac && proto.getXffNumTrustedHops() != 0) {
      throw new ResourceInvalidException(
          "HttpConnectionManager with xff_num_trusted_hops unsupported");
    }
    if (enableRbac && !proto.getOriginalIpDetectionExtensionsList().isEmpty()) {
      throw new ResourceInvalidException("HttpConnectionManager with "
          + "original_ip_detection_extensions unsupported");
    }
    // Obtain max_stream_duration from Http Protocol Options.
    long maxStreamDuration = 0;
    if (proto.hasCommonHttpProtocolOptions()) {
      HttpProtocolOptions options = proto.getCommonHttpProtocolOptions();
      if (options.hasMaxStreamDuration()) {
        maxStreamDuration = Durations.toNanos(options.getMaxStreamDuration());
      }
    }

    // Parse http filters.
    List<NamedFilterConfig> filterConfigs = null;
    if (parseHttpFilter) {
      if (proto.getHttpFiltersList().isEmpty()) {
        throw new ResourceInvalidException("Missing HttpFilter in HttpConnectionManager.");
      }
      filterConfigs = new ArrayList<>();
      Set<String> names = new HashSet<>();
      for (int i = 0; i < proto.getHttpFiltersCount(); i++) {
        io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
                httpFilter = proto.getHttpFiltersList().get(i);
        String filterName = httpFilter.getName();
        if (!names.add(filterName)) {
          throw new ResourceInvalidException(
              "HttpConnectionManager contains duplicate HttpFilter: " + filterName);
        }
        StructOrError<FilterConfig> filterConfig =
            parseHttpFilter(httpFilter, filterRegistry, isForClient);
        if ((i == proto.getHttpFiltersCount() - 1)
                && (filterConfig == null || !isTerminalFilter(filterConfig.struct))) {
          throw new ResourceInvalidException("The last HttpFilter must be a terminal filter: "
                  + filterName);
        }
        if (filterConfig == null) {
          continue;
        }
        if (filterConfig.getErrorDetail() != null) {
          throw new ResourceInvalidException(
              "HttpConnectionManager contains invalid HttpFilter: "
                  + filterConfig.getErrorDetail());
        }
        if ((i < proto.getHttpFiltersCount() - 1) && isTerminalFilter(filterConfig.getStruct())) {
          throw new ResourceInvalidException("A terminal HttpFilter must be the last filter: "
                  + filterName);
        }
        filterConfigs.add(new NamedFilterConfig(filterName, filterConfig.struct));
      }
    }

    // Parse inlined RouteConfiguration or RDS.
    if (proto.hasRouteConfig()) {
      List<VirtualHost> virtualHosts = new ArrayList<>();
      for (io.envoyproxy.envoy.config.route.v3.VirtualHost virtualHostProto
          : proto.getRouteConfig().getVirtualHostsList()) {
        StructOrError<VirtualHost> virtualHost =
            parseVirtualHost(virtualHostProto, filterRegistry, parseHttpFilter);
        if (virtualHost.getErrorDetail() != null) {
          throw new ResourceInvalidException(
              "HttpConnectionManager contains invalid virtual host: "
                  + virtualHost.getErrorDetail());
        }
        virtualHosts.add(virtualHost.getStruct());
      }
      return io.grpc.xds.HttpConnectionManager.forVirtualHosts(
          maxStreamDuration, virtualHosts, filterConfigs);
    }
    if (proto.hasRds()) {
      Rds rds = proto.getRds();
      if (!rds.hasConfigSource()) {
        throw new ResourceInvalidException(
            "HttpConnectionManager contains invalid RDS: missing config_source");
      }
      if (!rds.getConfigSource().hasAds()) {
        throw new ResourceInvalidException(
            "HttpConnectionManager contains invalid RDS: must specify ADS");
      }
      // Collect the RDS resource referenced by this HttpConnectionManager.
      rdsResources.add(rds.getRouteConfigName());
      return io.grpc.xds.HttpConnectionManager.forRdsName(
          maxStreamDuration, rds.getRouteConfigName(), filterConfigs);
    }
    throw new ResourceInvalidException(
        "HttpConnectionManager neither has inlined route_config nor RDS");
  }

  // hard-coded: currently router config is the only terminal filter.
  private static boolean isTerminalFilter(FilterConfig filterConfig) {
    return RouterFilter.ROUTER_CONFIG.equals(filterConfig);
  }

  @VisibleForTesting
  @Nullable // Returns null if the filter is optional but not supported.
  static StructOrError<FilterConfig> parseHttpFilter(
      io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
          httpFilter, FilterRegistry filterRegistry, boolean isForClient) {
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
    Message rawConfig = httpFilter.getTypedConfig();
    String typeUrl = httpFilter.getTypedConfig().getTypeUrl();
    if (typeUrl.equals(TYPE_URL_TYPED_STRUCT)) {
      TypedStruct typedStruct;
      try {
        typedStruct = httpFilter.getTypedConfig().unpack(TypedStruct.class);
      } catch (InvalidProtocolBufferException e) {
        return StructOrError.fromError(
            "HttpFilter [" + filterName + "] contains invalid proto: " + e);
      }
      typeUrl = typedStruct.getTypeUrl();
      rawConfig = typedStruct.getValue();
    }
    Filter filter = filterRegistry.get(typeUrl);
    if ((isForClient && !(filter instanceof ClientInterceptorBuilder))
        || (!isForClient && !(filter instanceof ServerInterceptorBuilder))) {
      if (isOptional) {
        return null;
      } else {
        return StructOrError.fromError(
            "HttpFilter [" + filterName + "](" + typeUrl + ") is required but unsupported for "
                + (isForClient ? "client" : "server"));
      }
    }
    ConfigOrError<? extends FilterConfig> filterConfig = filter.parseFilterConfig(rawConfig);
    if (filterConfig.errorDetail != null) {
      return StructOrError.fromError(
          "Invalid filter config for HttpFilter [" + filterName + "]: " + filterConfig.errorDetail);
    }
    return StructOrError.fromStruct(filterConfig.config);
  }

  private static StructOrError<VirtualHost> parseVirtualHost(
      io.envoyproxy.envoy.config.route.v3.VirtualHost proto, FilterRegistry filterRegistry,
      boolean parseHttpFilter) {
    String name = proto.getName();
    List<Route> routes = new ArrayList<>(proto.getRoutesCount());
    for (io.envoyproxy.envoy.config.route.v3.Route routeProto : proto.getRoutesList()) {
      StructOrError<Route> route = parseRoute(routeProto, filterRegistry, parseHttpFilter);
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
      if (typeUrl.equals(TYPE_URL_TYPED_STRUCT)) {
        TypedStruct typedStruct;
        try {
          typedStruct = anyConfig.unpack(TypedStruct.class);
        } catch (InvalidProtocolBufferException e) {
          return StructOrError.fromError(
              "FilterConfig [" + name + "] contains invalid proto: " + e);
        }
        typeUrl = typedStruct.getTypeUrl();
        rawConfig = typedStruct.getValue();
      }
      Filter filter = filterRegistry.get(typeUrl);
      if (filter == null) {
        if (isOptional) {
          continue;
        }
        return StructOrError.fromError(
            "HttpFilter [" + name + "](" + typeUrl + ") is required but unsupported");
      }
      ConfigOrError<? extends FilterConfig> filterConfig =
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
      boolean parseHttpFilter) {
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
            parseRouteAction(proto.getRoute(), filterRegistry, parseHttpFilter);
        if (routeAction == null) {
          return null;
        }
        if (routeAction.errorDetail != null) {
          return StructOrError.fromError(
              "Route [" + proto.getName() + "] contains invalid RouteAction: "
                  + routeAction.getErrorDetail());
        }
        return StructOrError.fromStruct(
            Route.forAction(routeMatch.struct, routeAction.struct, overrideConfigs));
      case NON_FORWARDING_ACTION:
        return StructOrError.fromStruct(
            Route.forNonForwardingAction(routeMatch.struct, overrideConfigs));
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

  /**
   * Parses the RouteAction config. The returned result may contain a (parsed form)
   * {@link RouteAction} or an error message. Returns {@code null} if the RouteAction
   * should be ignored.
   */
  @VisibleForTesting
  @Nullable
  static StructOrError<RouteAction> parseRouteAction(
      io.envoyproxy.envoy.config.route.v3.RouteAction proto, FilterRegistry filterRegistry,
      boolean parseHttpFilter) {
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
        return StructOrError.fromStruct(RouteAction.forWeightedClusters(
            weightedClusters, hashPolicies, timeoutNano, retryPolicy));
      case CLUSTERSPECIFIER_NOT_SET:
      default:
        return StructOrError.fromError(
            "Unknown cluster specifier: " + proto.getClusterSpecifierCase());
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
    ImmutableList.Builder<Code> retryableStatusCodesBuilder = ImmutableList.builder();
    for (String retryOn : retryOns) {
      Code code;
      try {
        code = Code.valueOf(retryOn.toUpperCase(Locale.US).replace('-', '_'));
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
    List<Code> retryableStatusCodes = retryableStatusCodesBuilder.build();
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
    StructOrError<Map<String, FilterConfig>> overrideConfigs =
        parseOverrideFilterConfigs(proto.getTypedPerFilterConfigMap(), filterRegistry);
    if (overrideConfigs.errorDetail != null) {
      return StructOrError.fromError(
          "ClusterWeight [" + proto.getName() + "] contains invalid HttpFilter config: "
              + overrideConfigs.errorDetail);
    }
    return StructOrError.fromStruct(ClusterWeight.create(
        proto.getName(), proto.getWeight().getValue(), overrideConfigs.struct));
  }

  @Override
  public void handleRdsResponse(
      ServerInfo serverInfo, String versionInfo, List<Any> resources, String nonce) {
    syncContext.throwIfNotInThisSynchronizationContext();
    Map<String, ParsedResource> parsedResources = new HashMap<>(resources.size());
    Set<String> unpackedResources = new HashSet<>(resources.size());
    Set<String> invalidResources = new HashSet<>();
    List<String> errors = new ArrayList<>();

    for (int i = 0; i < resources.size(); i++) {
      Any resource = resources.get(i);

      // Unpack the RouteConfiguration.
      RouteConfiguration routeConfig;
      try {
        routeConfig = unpackCompatibleType(resource, RouteConfiguration.class,
            ResourceType.RDS.typeUrl(), ResourceType.RDS.typeUrlV2());
      } catch (InvalidProtocolBufferException e) {
        errors.add("RDS response Resource index " + i + " - can't decode RouteConfiguration: " + e);
        continue;
      }
      String routeConfigName = routeConfig.getName();
      unpackedResources.add(routeConfigName);

      // Process RouteConfiguration into RdsUpdate.
      RdsUpdate rdsUpdate;
      boolean isResourceV3 = resource.getTypeUrl().equals(ResourceType.RDS.typeUrl());
      try {
        rdsUpdate = processRouteConfiguration(
            routeConfig, filterRegistry, enableFaultInjection && isResourceV3);
      } catch (ResourceInvalidException e) {
        errors.add(
            "RDS response RouteConfiguration '" + routeConfigName + "' validation error: " + e
                .getMessage());
        invalidResources.add(routeConfigName);
        continue;
      }

      parsedResources.put(routeConfigName, new ParsedResource(rdsUpdate, resource));
    }
    logger.log(XdsLogLevel.INFO,
        "Received RDS Response version {0} nonce {1}. Parsed resources: {2}",
        versionInfo, nonce, unpackedResources);
    handleResourceUpdate(
        serverInfo, ResourceType.RDS, parsedResources, invalidResources,
        Collections.<String>emptySet(), versionInfo, nonce, errors);
  }

  private static RdsUpdate processRouteConfiguration(
      RouteConfiguration routeConfig, FilterRegistry filterRegistry, boolean parseHttpFilter)
      throws ResourceInvalidException {
    List<VirtualHost> virtualHosts = new ArrayList<>(routeConfig.getVirtualHostsCount());
    for (io.envoyproxy.envoy.config.route.v3.VirtualHost virtualHostProto
        : routeConfig.getVirtualHostsList()) {
      StructOrError<VirtualHost> virtualHost =
          parseVirtualHost(virtualHostProto, filterRegistry, parseHttpFilter);
      if (virtualHost.getErrorDetail() != null) {
        throw new ResourceInvalidException(
            "RouteConfiguration contains invalid virtual host: " + virtualHost.getErrorDetail());
      }
      virtualHosts.add(virtualHost.getStruct());
    }
    return new RdsUpdate(virtualHosts);
  }

  @Override
  public void handleCdsResponse(
      ServerInfo serverInfo, String versionInfo, List<Any> resources, String nonce) {
    syncContext.throwIfNotInThisSynchronizationContext();
    Map<String, ParsedResource> parsedResources = new HashMap<>(resources.size());
    Set<String> unpackedResources = new HashSet<>(resources.size());
    Set<String> invalidResources = new HashSet<>();
    List<String> errors = new ArrayList<>();
    Set<String> retainedEdsResources = new HashSet<>();

    for (int i = 0; i < resources.size(); i++) {
      Any resource = resources.get(i);

      // Unpack the Cluster.
      Cluster cluster;
      try {
        cluster = unpackCompatibleType(
            resource, Cluster.class, ResourceType.CDS.typeUrl(), ResourceType.CDS.typeUrlV2());
      } catch (InvalidProtocolBufferException e) {
        errors.add("CDS response Resource index " + i + " - can't decode Cluster: " + e);
        continue;
      }
      String clusterName = cluster.getName();

      // Management server is required to always send newly requested resources, even if they
      // may have been sent previously (proactively). Thus, client does not need to cache
      // unrequested resources.
      if (!cdsResourceSubscribers.containsKey(clusterName)) {
        continue;
      }
      unpackedResources.add(clusterName);

      // Process Cluster into CdsUpdate.
      CdsUpdate cdsUpdate;
      try {
        Set<String> certProviderInstances = null;
        if (getBootstrapInfo() != null && getBootstrapInfo().certProviders() != null) {
          certProviderInstances = getBootstrapInfo().certProviders().keySet();
        }
        cdsUpdate = parseCluster(cluster, retainedEdsResources, certProviderInstances);
      } catch (ResourceInvalidException e) {
        errors.add(
            "CDS response Cluster '" + clusterName + "' validation error: " + e.getMessage());
        invalidResources.add(clusterName);
        continue;
      }
      parsedResources.put(clusterName, new ParsedResource(cdsUpdate, resource));
    }
    logger.log(XdsLogLevel.INFO,
        "Received CDS Response version {0} nonce {1}. Parsed resources: {2}",
        versionInfo, nonce, unpackedResources);
    handleResourceUpdate(
        serverInfo, ResourceType.CDS, parsedResources, invalidResources, retainedEdsResources,
        versionInfo, nonce, errors);
  }

  @VisibleForTesting
  static CdsUpdate parseCluster(Cluster cluster, Set<String> retainedEdsResources,
      Set<String> certProviderInstances)
      throws ResourceInvalidException {
    StructOrError<CdsUpdate.Builder> structOrError;
    switch (cluster.getClusterDiscoveryTypeCase()) {
      case TYPE:
        structOrError = parseNonAggregateCluster(cluster, retainedEdsResources,
            certProviderInstances);
        break;
      case CLUSTER_TYPE:
        structOrError = parseAggregateCluster(cluster);
        break;
      case CLUSTERDISCOVERYTYPE_NOT_SET:
      default:
        throw new ResourceInvalidException(
            "Cluster " + cluster.getName() + ": unspecified cluster discovery type");
    }
    if (structOrError.getErrorDetail() != null) {
      throw new ResourceInvalidException(structOrError.getErrorDetail());
    }
    CdsUpdate.Builder updateBuilder = structOrError.getStruct();

    if (cluster.getLbPolicy() == LbPolicy.RING_HASH) {
      RingHashLbConfig lbConfig = cluster.getRingHashLbConfig();
      long minRingSize =
          lbConfig.hasMinimumRingSize()
              ? lbConfig.getMinimumRingSize().getValue()
              : DEFAULT_RING_HASH_LB_POLICY_MIN_RING_SIZE;
      long maxRingSize =
          lbConfig.hasMaximumRingSize()
              ? lbConfig.getMaximumRingSize().getValue()
              : DEFAULT_RING_HASH_LB_POLICY_MAX_RING_SIZE;
      if (lbConfig.getHashFunction() != RingHashLbConfig.HashFunction.XX_HASH
          || minRingSize > maxRingSize
          || maxRingSize > MAX_RING_HASH_LB_POLICY_RING_SIZE) {
        throw new ResourceInvalidException(
            "Cluster " + cluster.getName() + ": invalid ring_hash_lb_config: " + lbConfig);
      }
      updateBuilder.ringHashLbPolicy(minRingSize, maxRingSize);
    } else if (cluster.getLbPolicy() == LbPolicy.ROUND_ROBIN) {
      updateBuilder.roundRobinLbPolicy();
    } else {
      throw new ResourceInvalidException(
          "Cluster " + cluster.getName() + ": unsupported lb policy: " + cluster.getLbPolicy());
    }

    return updateBuilder.build();
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
      Cluster cluster, Set<String> edsResources, Set<String> certProviderInstances) {
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
    if (cluster.getTransportSocketMatchesCount() > 0) {
      return StructOrError.fromError("Cluster " + clusterName
          + ": transport-socket-matches not supported.");
    }
    if (cluster.hasTransportSocket()) {
      if (!TRANSPORT_SOCKET_NAME_TLS.equals(cluster.getTransportSocket().getName())) {
        return StructOrError.fromError("transport-socket with name "
            + cluster.getTransportSocket().getName() + " not supported.");
      }
      try {
        upstreamTlsContext = UpstreamTlsContext.fromEnvoyProtoUpstreamTlsContext(
                validateUpstreamTlsContext(
            unpackCompatibleType(cluster.getTransportSocket().getTypedConfig(),
                io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext.class,
                TYPE_URL_UPSTREAM_TLS_CONTEXT, TYPE_URL_UPSTREAM_TLS_CONTEXT_V2),
                certProviderInstances));
      } catch (InvalidProtocolBufferException | ResourceInvalidException e) {
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
      if (!cluster.hasLoadAssignment()) {
        return StructOrError.fromError(
            "Cluster " + clusterName + ": LOGICAL_DNS clusters must have a single host");
      }
      ClusterLoadAssignment assignment = cluster.getLoadAssignment();
      if (assignment.getEndpointsCount() != 1
          || assignment.getEndpoints(0).getLbEndpointsCount() != 1) {
        return StructOrError.fromError(
            "Cluster " + clusterName + ": LOGICAL_DNS clusters must have a single "
                + "locality_lb_endpoint and a single lb_endpoint");
      }
      io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint lbEndpoint =
          assignment.getEndpoints(0).getLbEndpoints(0);
      if (!lbEndpoint.hasEndpoint() || !lbEndpoint.getEndpoint().hasAddress()
          || !lbEndpoint.getEndpoint().getAddress().hasSocketAddress()) {
        return StructOrError.fromError(
            "Cluster " + clusterName
                + ": LOGICAL_DNS clusters must have an endpoint with address and socket_address");
      }
      SocketAddress socketAddress = lbEndpoint.getEndpoint().getAddress().getSocketAddress();
      if (!socketAddress.getResolverName().isEmpty()) {
        return StructOrError.fromError(
            "Cluster " + clusterName
                + ": LOGICAL DNS clusters must NOT have a custom resolver name set");
      }
      if (socketAddress.getPortSpecifierCase() != PortSpecifierCase.PORT_VALUE) {
        return StructOrError.fromError(
            "Cluster " + clusterName
                + ": LOGICAL DNS clusters socket_address must have port_value");
      }
      String dnsHostName =
          String.format("%s:%d", socketAddress.getAddress(), socketAddress.getPortValue());
      return StructOrError.fromStruct(CdsUpdate.forLogicalDns(
          clusterName, dnsHostName, lrsServerName, maxConcurrentRequests, upstreamTlsContext));
    }
    return StructOrError.fromError(
        "Cluster " + clusterName + ": unsupported built-in discovery type: " + type);
  }

  @Override
  public void handleEdsResponse(
      ServerInfo serverInfo, String versionInfo, List<Any> resources, String nonce) {
    syncContext.throwIfNotInThisSynchronizationContext();
    Map<String, ParsedResource> parsedResources = new HashMap<>(resources.size());
    Set<String> unpackedResources = new HashSet<>(resources.size());
    Set<String> invalidResources = new HashSet<>();
    List<String> errors = new ArrayList<>();

    for (int i = 0; i < resources.size(); i++) {
      Any resource = resources.get(i);

      // Unpack the ClusterLoadAssignment.
      ClusterLoadAssignment assignment;
      try {
        assignment =
            unpackCompatibleType(resource, ClusterLoadAssignment.class, ResourceType.EDS.typeUrl(),
                ResourceType.EDS.typeUrlV2());
      } catch (InvalidProtocolBufferException e) {
        errors.add(
            "EDS response Resource index " + i + " - can't decode ClusterLoadAssignment: " + e);
        continue;
      }
      String clusterName = assignment.getClusterName();

      // Skip information for clusters not requested.
      // Management server is required to always send newly requested resources, even if they
      // may have been sent previously (proactively). Thus, client does not need to cache
      // unrequested resources.
      if (!edsResourceSubscribers.containsKey(clusterName)) {
        continue;
      }
      unpackedResources.add(clusterName);

      // Process ClusterLoadAssignment into EdsUpdate.
      EdsUpdate edsUpdate;
      try {
        edsUpdate = processClusterLoadAssignment(assignment);
      } catch (ResourceInvalidException e) {
        errors.add("EDS response ClusterLoadAssignment '" + clusterName
            + "' validation error: " + e.getMessage());
        invalidResources.add(clusterName);
        continue;
      }
      parsedResources.put(clusterName, new ParsedResource(edsUpdate, resource));
    }
    logger.log(
        XdsLogLevel.INFO, "Received EDS Response version {0} nonce {1}. Parsed resources: {2}",
        versionInfo, nonce, unpackedResources);
    handleResourceUpdate(
        serverInfo, ResourceType.EDS, parsedResources, invalidResources,
        Collections.<String>emptySet(), versionInfo, nonce, errors);
  }

  private static EdsUpdate processClusterLoadAssignment(ClusterLoadAssignment assignment)
      throws ResourceInvalidException {
    Set<Integer> priorities = new HashSet<>();
    Map<Locality, LocalityLbEndpoints> localityLbEndpointsMap = new LinkedHashMap<>();
    List<DropOverload> dropOverloads = new ArrayList<>();
    int maxPriority = -1;
    for (io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints localityLbEndpointsProto
        : assignment.getEndpointsList()) {
      StructOrError<LocalityLbEndpoints> structOrError =
          parseLocalityLbEndpoints(localityLbEndpointsProto);
      if (structOrError == null) {
        continue;
      }
      if (structOrError.getErrorDetail() != null) {
        throw new ResourceInvalidException(structOrError.getErrorDetail());
      }

      LocalityLbEndpoints localityLbEndpoints = structOrError.getStruct();
      maxPriority = Math.max(maxPriority, localityLbEndpoints.priority());
      priorities.add(localityLbEndpoints.priority());
      // Note endpoints with health status other than HEALTHY and UNKNOWN are still
      // handed over to watching parties. It is watching parties' responsibility to
      // filter out unhealthy endpoints. See EnvoyProtoData.LbEndpoint#isHealthy().
      localityLbEndpointsMap.put(
          parseLocality(localityLbEndpointsProto.getLocality()),
          localityLbEndpoints);
    }
    if (priorities.size() != maxPriority + 1) {
      throw new ResourceInvalidException("ClusterLoadAssignment has sparse priorities");
    }

    for (ClusterLoadAssignment.Policy.DropOverload dropOverloadProto
        : assignment.getPolicy().getDropOverloadsList()) {
      dropOverloads.add(parseDropOverload(dropOverloadProto));
    }
    return new EdsUpdate(assignment.getClusterName(), localityLbEndpointsMap, dropOverloads);
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
  public void handleStreamClosed(Status error) {
    syncContext.throwIfNotInThisSynchronizationContext();
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
  public void handleStreamRestarted(ServerInfo serverInfo) {
    syncContext.throwIfNotInThisSynchronizationContext();
    for (ResourceSubscriber subscriber : ldsResourceSubscribers.values()) {
      if (subscriber.serverInfo.equals(serverInfo)) {
        subscriber.restartTimer();
      }
    }
    for (ResourceSubscriber subscriber : rdsResourceSubscribers.values()) {
      if (subscriber.serverInfo.equals(serverInfo)) {
        subscriber.restartTimer();
      }
    }
    for (ResourceSubscriber subscriber : cdsResourceSubscribers.values()) {
      if (subscriber.serverInfo.equals(serverInfo)) {
        subscriber.restartTimer();
      }
    }
    for (ResourceSubscriber subscriber : edsResourceSubscribers.values()) {
      if (subscriber.serverInfo.equals(serverInfo)) {
        subscriber.restartTimer();
      }
    }
  }

  @Override
  void shutdown() {
    syncContext.execute(
        new Runnable() {
          @Override
          public void run() {
            if (isShutdown) {
              return;
            }
            isShutdown = true;
            for (AbstractXdsClient xdsChannel : serverChannelMap.values()) {
              xdsChannel.shutdown();
            }
            if (reportingLoad) {
              for (final LoadReportClient lrsClient : serverLrsClientMap.values()) {
                lrsClient.stopLoadReporting();
              }
            }
            cleanUpResourceTimers();
          }
        });
  }

  @Override
  boolean isShutDown() {
    return isShutdown;
  }

  private Map<String, ResourceSubscriber> getSubscribedResourcesMap(ResourceType type) {
    switch (type) {
      case LDS:
        return ldsResourceSubscribers;
      case RDS:
        return rdsResourceSubscribers;
      case CDS:
        return cdsResourceSubscribers;
      case EDS:
        return edsResourceSubscribers;
      case UNKNOWN:
      default:
        throw new AssertionError("Unknown resource type");
    }
  }

  @Nullable
  @Override
  public Collection<String> getSubscribedResources(ServerInfo serverInfo, ResourceType type) {
    Map<String, ResourceSubscriber> resources = getSubscribedResourcesMap(type);
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    for (String key : resources.keySet()) {
      if (resources.get(key).serverInfo.equals(serverInfo)) {
        builder.add(key);
      }
    }
    Collection<String> retVal = builder.build();
    return retVal.isEmpty() ? null : retVal;
  }

  @Override
  Map<String, ResourceMetadata> getSubscribedResourcesMetadata(ResourceType type) {
    Map<String, ResourceMetadata> metadataMap = new HashMap<>();
    for (Map.Entry<String, ResourceSubscriber> entry : getSubscribedResourcesMap(type).entrySet()) {
      metadataMap.put(entry.getKey(), entry.getValue().metadata);
    }
    return metadataMap;
  }

  @Override
  TlsContextManager getTlsContextManager() {
    return tlsContextManager;
  }

  @Override
  void watchLdsResource(final String resourceName, final LdsResourceWatcher watcher) {
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = ldsResourceSubscribers.get(resourceName);
        if (subscriber == null) {
          logger.log(XdsLogLevel.INFO, "Subscribe LDS resource {0}", resourceName);
          subscriber = new ResourceSubscriber(ResourceType.LDS, resourceName);
          ldsResourceSubscribers.put(resourceName, subscriber);
          subscriber.xdsChannel.adjustResourceSubscription(ResourceType.LDS);
        }
        subscriber.addWatcher(watcher);
      }
    });
  }

  @Override
  void cancelLdsResourceWatch(final String resourceName, final LdsResourceWatcher watcher) {
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = ldsResourceSubscribers.get(resourceName);
        subscriber.removeWatcher(watcher);
        if (!subscriber.isWatched()) {
          subscriber.stopTimer();
          logger.log(XdsLogLevel.INFO, "Unsubscribe LDS resource {0}", resourceName);
          ldsResourceSubscribers.remove(resourceName);
          subscriber.xdsChannel.adjustResourceSubscription(ResourceType.LDS);
        }
      }
    });
  }

  @Override
  void watchRdsResource(final String resourceName, final RdsResourceWatcher watcher) {
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = rdsResourceSubscribers.get(resourceName);
        if (subscriber == null) {
          logger.log(XdsLogLevel.INFO, "Subscribe RDS resource {0}", resourceName);
          subscriber = new ResourceSubscriber(ResourceType.RDS, resourceName);
          rdsResourceSubscribers.put(resourceName, subscriber);
          subscriber.xdsChannel.adjustResourceSubscription(ResourceType.RDS);
        }
        subscriber.addWatcher(watcher);
      }
    });
  }

  @Override
  void cancelRdsResourceWatch(final String resourceName, final RdsResourceWatcher watcher) {
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = rdsResourceSubscribers.get(resourceName);
        subscriber.removeWatcher(watcher);
        if (!subscriber.isWatched()) {
          subscriber.stopTimer();
          logger.log(XdsLogLevel.INFO, "Unsubscribe RDS resource {0}", resourceName);
          rdsResourceSubscribers.remove(resourceName);
          subscriber.xdsChannel.adjustResourceSubscription(ResourceType.RDS);
        }
      }
    });
  }

  @Override
  void watchCdsResource(final String resourceName, final CdsResourceWatcher watcher) {
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = cdsResourceSubscribers.get(resourceName);
        if (subscriber == null) {
          logger.log(XdsLogLevel.INFO, "Subscribe CDS resource {0}", resourceName);
          subscriber = new ResourceSubscriber(ResourceType.CDS, resourceName);
          cdsResourceSubscribers.put(resourceName, subscriber);
          subscriber.xdsChannel.adjustResourceSubscription(ResourceType.CDS);
        }
        subscriber.addWatcher(watcher);
      }
    });
  }

  @Override
  void cancelCdsResourceWatch(final String resourceName, final CdsResourceWatcher watcher) {
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = cdsResourceSubscribers.get(resourceName);
        subscriber.removeWatcher(watcher);
        if (!subscriber.isWatched()) {
          subscriber.stopTimer();
          logger.log(XdsLogLevel.INFO, "Unsubscribe CDS resource {0}", resourceName);
          cdsResourceSubscribers.remove(resourceName);
          subscriber.xdsChannel.adjustResourceSubscription(ResourceType.CDS);
        }
      }
    });
  }

  @Override
  void watchEdsResource(final String resourceName, final EdsResourceWatcher watcher) {
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = edsResourceSubscribers.get(resourceName);
        if (subscriber == null) {
          logger.log(XdsLogLevel.INFO, "Subscribe EDS resource {0}", resourceName);
          subscriber = new ResourceSubscriber(ResourceType.EDS, resourceName);
          edsResourceSubscribers.put(resourceName, subscriber);
          subscriber.xdsChannel.adjustResourceSubscription(ResourceType.EDS);
        }
        subscriber.addWatcher(watcher);
      }
    });
  }

  @Override
  void cancelEdsResourceWatch(final String resourceName, final EdsResourceWatcher watcher) {
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = edsResourceSubscribers.get(resourceName);
        subscriber.removeWatcher(watcher);
        if (!subscriber.isWatched()) {
          subscriber.stopTimer();
          logger.log(XdsLogLevel.INFO, "Unsubscribe EDS resource {0}", resourceName);
          edsResourceSubscribers.remove(resourceName);
          subscriber.xdsChannel.adjustResourceSubscription(ResourceType.EDS);
        }
      }
    });
  }

  @Override
  ClusterDropStats addClusterDropStats(
      String clusterName, @Nullable String edsServiceName) {
    ClusterDropStats dropCounter =
        loadStatsManager.getClusterDropStats(clusterName, edsServiceName);
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        if (!reportingLoad) {
          // TODO(https://github.com/grpc/grpc-java/issues/8628): consume ServerInfo arg.
          serverLrsClientMap.values().iterator().next().startLoadReporting();
          reportingLoad = true;
        }
      }
    });
    return dropCounter;
  }

  @Override
  ClusterLocalityStats addClusterLocalityStats(
      String clusterName, @Nullable String edsServiceName,
      Locality locality) {
    ClusterLocalityStats loadCounter =
        loadStatsManager.getClusterLocalityStats(clusterName, edsServiceName, locality);
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        if (!reportingLoad) {
          // TODO(https://github.com/grpc/grpc-java/issues/8628): consume ServerInfo arg.
          serverLrsClientMap.values().iterator().next().startLoadReporting();
          reportingLoad = true;
        }
      }
    });
    return loadCounter;
  }

  @Override
  Bootstrapper.BootstrapInfo getBootstrapInfo() {
    return bootstrapInfo;
  }

  // TODO(https://github.com/grpc/grpc-java/issues/8629): remove this
  @Override
  String getCurrentVersion(ResourceType type) {
    if (serverChannelMap.isEmpty()) {
      return "";
    }
    return serverChannelMap.values().iterator().next().getCurrentVersion(type);
  }
  
  @Override
  public String toString() {
    return logId.toString();
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

  private void handleResourceUpdate(
      ServerInfo serverInfo, ResourceType type, Map<String, ParsedResource> parsedResources,
      Set<String> invalidResources, Set<String> retainedResources, String version, String nonce,
      List<String> errors) {
    String errorDetail = null;
    if (errors.isEmpty()) {
      checkArgument(invalidResources.isEmpty(), "found invalid resources but missing errors");
      serverChannelMap.get(serverInfo).ackResponse(type, version, nonce);
    } else {
      errorDetail = Joiner.on('\n').join(errors);
      logger.log(XdsLogLevel.WARNING,
          "Failed processing {0} Response version {1} nonce {2}. Errors:\n{3}",
          type, version, nonce, errorDetail);
      serverChannelMap.get(serverInfo).nackResponse(type, nonce, errorDetail);
    }
    long updateTime = timeProvider.currentTimeNanos();
    for (Map.Entry<String, ResourceSubscriber> entry : getSubscribedResourcesMap(type).entrySet()) {
      String resourceName = entry.getKey();
      ResourceSubscriber subscriber = entry.getValue();
      // Attach error details to the subscribed resources that included in the ADS update.
      if (invalidResources.contains(resourceName)) {
        subscriber.onRejected(version, updateTime, errorDetail);
      }
      // Notify the watchers.
      if (parsedResources.containsKey(resourceName)) {
        subscriber.onData(parsedResources.get(resourceName), version, updateTime);
      } else if (type == ResourceType.LDS || type == ResourceType.CDS) {
        if (subscriber.data != null && invalidResources.contains(resourceName)) {
          // Update is rejected but keep using the cached data.
          if (type == ResourceType.LDS) {
            LdsUpdate ldsUpdate = (LdsUpdate) subscriber.data;
            io.grpc.xds.HttpConnectionManager hcm = ldsUpdate.httpConnectionManager();
            if (hcm != null) {
              String rdsName = hcm.rdsName();
              if (rdsName != null) {
                retainedResources.add(rdsName);
              }
            }
          } else {
            CdsUpdate cdsUpdate = (CdsUpdate) subscriber.data;
            String edsName = cdsUpdate.edsServiceName();
            if (edsName == null) {
              edsName = cdsUpdate.clusterName();
            }
            retainedResources.add(edsName);
          }
          continue;
        }
        // For State of the World services, notify watchers when their watched resource is missing
        // from the ADS update.
        subscriber.onAbsent();
      }
    }
    // LDS/CDS responses represents the state of the world, RDS/EDS resources not referenced in
    // LDS/CDS resources should be deleted.
    if (type == ResourceType.LDS || type == ResourceType.CDS) {
      Map<String, ResourceSubscriber> dependentSubscribers =
          type == ResourceType.LDS ? rdsResourceSubscribers : edsResourceSubscribers;
      for (String resource : dependentSubscribers.keySet()) {
        if (!retainedResources.contains(resource)) {
          dependentSubscribers.get(resource).onAbsent();
        }
      }
    }
  }

  private static final class ParsedResource {
    private final ResourceUpdate resourceUpdate;
    private final Any rawResource;

    private ParsedResource(ResourceUpdate resourceUpdate, Any rawResource) {
      this.resourceUpdate = checkNotNull(resourceUpdate, "resourceUpdate");
      this.rawResource = checkNotNull(rawResource, "rawResource");
    }

    private ResourceUpdate getResourceUpdate() {
      return resourceUpdate;
    }

    private Any getRawResource() {
      return rawResource;
    }
  }

  /**
   * Tracks a single subscribed resource.
   */
  private final class ResourceSubscriber {
    private final ServerInfo serverInfo;
    private final AbstractXdsClient xdsChannel;
    private final ResourceType type;
    private final String resource;
    private final Set<ResourceWatcher> watchers = new HashSet<>();
    private ResourceUpdate data;
    private boolean absent;
    private ScheduledHandle respTimer;
    private ResourceMetadata metadata;

    ResourceSubscriber(ResourceType type, String resource) {
      syncContext.throwIfNotInThisSynchronizationContext();
      this.type = type;
      this.resource = resource;
      this.serverInfo = getServerInfo();
      // Initialize metadata in UNKNOWN state to cover the case when resource subscriber,
      // is created but not yet requested because the client is in backoff.
      this.metadata = ResourceMetadata.newResourceMetadataUnknown();
      maybeCreateXdsChannelWithLrs(serverInfo);
      this.xdsChannel = serverChannelMap.get(serverInfo);
      if (xdsChannel.isInBackoff()) {
        return;
      }
      restartTimer();
    }

    // TODO(zdapeng): add resourceName arg and support xdstp:// resources
    private ServerInfo getServerInfo() {
      return bootstrapInfo.servers().get(0); // use first server
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
          logger.log(XdsLogLevel.INFO, "{0} resource {1} initial fetch timeout",
              type, resource);
          respTimer = null;
          onAbsent();
        }

        @Override
        public String toString() {
          return type + this.getClass().getSimpleName();
        }
      }

      // Initial fetch scheduled or rescheduled, transition metadata state to REQUESTED.
      metadata = ResourceMetadata.newResourceMetadataRequested();
      respTimer = syncContext.schedule(
          new ResourceNotFound(), INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS,
          timeService);
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

    void onData(ParsedResource parsedResource, String version, long updateTime) {
      if (respTimer != null && respTimer.isPending()) {
        respTimer.cancel();
        respTimer = null;
      }
      this.metadata = ResourceMetadata
          .newResourceMetadataAcked(parsedResource.getRawResource(), version, updateTime);
      ResourceUpdate oldData = this.data;
      this.data = parsedResource.getResourceUpdate();
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
      logger.log(XdsLogLevel.INFO, "Conclude {0} resource {1} not exist", type, resource);
      if (!absent) {
        data = null;
        absent = true;
        metadata = ResourceMetadata.newResourceMetadataDoesNotExist();
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

    void onRejected(String rejectedVersion, long rejectedTime, String rejectedDetails) {
      metadata = ResourceMetadata
          .newResourceMetadataNacked(metadata, rejectedVersion, rejectedTime, rejectedDetails);
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
  static final class ResourceInvalidException extends Exception {
    private static final long serialVersionUID = 0L;

    private ResourceInvalidException(String message) {
      super(message, null, false, false);
    }

    private ResourceInvalidException(String message, Throwable cause) {
      super(cause != null ? message + ": " + cause.getMessage() : message, cause, false, false);
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

  abstract static class XdsChannelFactory {
    static final XdsChannelFactory DEFAULT_XDS_CHANNEL_FACTORY = new XdsChannelFactory() {
      @Override
      ManagedChannel create(ServerInfo serverInfo) {
        String target = serverInfo.target();
        ChannelCredentials channelCredentials = serverInfo.channelCredentials();
        return Grpc.newChannelBuilder(target, channelCredentials)
            .keepAliveTime(5, TimeUnit.MINUTES)
            .build();
      }
    };

    abstract ManagedChannel create(ServerInfo serverInfo);
  }
}
