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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.xds.client.Bootstrapper.XDSTP_SCHEME;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.protobuf.util.Durations;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.InternalConfigSelector;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MetricRecorder;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.ClusterSpecifierPlugin.PluginConfig;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.Filter.NamedFilterConfig;
import io.grpc.xds.RouteLookupServiceClusterSpecifierPlugin.RlsPluginConfig;
import io.grpc.xds.ThreadSafeRandom.ThreadSafeRandomImpl;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.VirtualHost.Route.RouteAction;
import io.grpc.xds.VirtualHost.Route.RouteAction.ClusterWeight;
import io.grpc.xds.VirtualHost.Route.RouteAction.HashPolicy;
import io.grpc.xds.VirtualHost.Route.RouteAction.RetryPolicy;
import io.grpc.xds.VirtualHost.Route.RouteMatch;
import io.grpc.xds.XdsNameResolverProvider.CallCounterProvider;
import io.grpc.xds.XdsRouteConfigureResource.RdsUpdate;
import io.grpc.xds.client.Bootstrapper.AuthorityInfo;
import io.grpc.xds.client.Bootstrapper.BootstrapInfo;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsClient.ResourceWatcher;
import io.grpc.xds.client.XdsLogger;
import io.grpc.xds.client.XdsLogger.XdsLogLevel;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * A {@link NameResolver} for resolving gRPC target names with "xds:" scheme.
 *
 * <p>Resolving a gRPC target involves contacting the control plane management server via xDS
 * protocol to retrieve service information and produce a service config to the caller.
 *
 * @see XdsNameResolverProvider
 */
final class XdsNameResolver extends NameResolver {

  static final CallOptions.Key<String> CLUSTER_SELECTION_KEY =
      CallOptions.Key.create("io.grpc.xds.CLUSTER_SELECTION_KEY");
  static final CallOptions.Key<Long> RPC_HASH_KEY =
      CallOptions.Key.create("io.grpc.xds.RPC_HASH_KEY");
  static final CallOptions.Key<Boolean> AUTO_HOST_REWRITE_KEY =
      CallOptions.Key.create("io.grpc.xds.AUTO_HOST_REWRITE_KEY");
  @VisibleForTesting
  static boolean enableTimeout =
      Strings.isNullOrEmpty(System.getenv("GRPC_XDS_EXPERIMENTAL_ENABLE_TIMEOUT"))
          || Boolean.parseBoolean(System.getenv("GRPC_XDS_EXPERIMENTAL_ENABLE_TIMEOUT"));

  private final InternalLogId logId;
  private final XdsLogger logger;
  @Nullable
  private final String targetAuthority;
  private final String target;
  private final String serviceAuthority;
  // Encoded version of the service authority as per 
  // https://datatracker.ietf.org/doc/html/rfc3986#section-3.2.
  private final String encodedServiceAuthority;
  private final String overrideAuthority;
  private final ServiceConfigParser serviceConfigParser;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService scheduler;
  private final XdsClientPoolFactory xdsClientPoolFactory;
  private final ThreadSafeRandom random;
  private final FilterRegistry filterRegistry;
  private final XxHash64 hashFunc = XxHash64.INSTANCE;
  // Clusters (with reference counts) to which new/existing requests can be/are routed.
  // put()/remove() must be called in SyncContext, and get() can be called in any thread.
  private final ConcurrentMap<String, ClusterRefState> clusterRefs = new ConcurrentHashMap<>();
  private final ConfigSelector configSelector = new ConfigSelector();
  private final long randomChannelId;
  private final MetricRecorder metricRecorder;
  // Must be accessed in syncContext.
  // Filter instances are unique per channel, and per filter (name+typeUrl).
  // NamedFilterConfig.filterStateKey -> filter_instance.
  private final HashMap<String, Filter> activeFilters = new HashMap<>();

  private volatile RoutingConfig routingConfig = RoutingConfig.EMPTY;
  private Listener2 listener;
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private CallCounterProvider callCounterProvider;
  private ResolveState resolveState;
  // Workaround for https://github.com/grpc/grpc-java/issues/8886 . This should be handled in
  // XdsClient instead of here.
  private boolean receivedConfig;

  XdsNameResolver(
      URI targetUri, String name, @Nullable String overrideAuthority,
      ServiceConfigParser serviceConfigParser,
      SynchronizationContext syncContext, ScheduledExecutorService scheduler,
      @Nullable Map<String, ?> bootstrapOverride,
      MetricRecorder metricRecorder) {
    this(targetUri, targetUri.getAuthority(), name, overrideAuthority, serviceConfigParser,
        syncContext, scheduler, SharedXdsClientPoolProvider.getDefaultProvider(),
        ThreadSafeRandomImpl.instance, FilterRegistry.getDefaultRegistry(), bootstrapOverride,
        metricRecorder);
  }

  @VisibleForTesting
  XdsNameResolver(
      URI targetUri, @Nullable String targetAuthority, String name,
      @Nullable String overrideAuthority, ServiceConfigParser serviceConfigParser,
      SynchronizationContext syncContext, ScheduledExecutorService scheduler,
      XdsClientPoolFactory xdsClientPoolFactory, ThreadSafeRandom random,
      FilterRegistry filterRegistry, @Nullable Map<String, ?> bootstrapOverride,
      MetricRecorder metricRecorder) {
    this.targetAuthority = targetAuthority;
    target = targetUri.toString();

    // The name might have multiple slashes so encode it before verifying.
    serviceAuthority = checkNotNull(name, "name");
    this.encodedServiceAuthority = 
      GrpcUtil.checkAuthority(GrpcUtil.AuthorityEscaper.encodeAuthority(serviceAuthority));

    this.overrideAuthority = overrideAuthority;
    this.serviceConfigParser = checkNotNull(serviceConfigParser, "serviceConfigParser");
    this.syncContext = checkNotNull(syncContext, "syncContext");
    this.scheduler = checkNotNull(scheduler, "scheduler");
    this.xdsClientPoolFactory = bootstrapOverride == null ? checkNotNull(xdsClientPoolFactory,
            "xdsClientPoolFactory") : new SharedXdsClientPoolProvider();
    this.xdsClientPoolFactory.setBootstrapOverride(bootstrapOverride);
    this.random = checkNotNull(random, "random");
    this.filterRegistry = checkNotNull(filterRegistry, "filterRegistry");
    this.metricRecorder = metricRecorder;
    randomChannelId = random.nextLong();
    logId = InternalLogId.allocate("xds-resolver", name);
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogLevel.INFO, "Created resolver for {0}", name);
  }

  @Override
  public String getServiceAuthority() {
    return encodedServiceAuthority;
  }

  @Override
  public void start(Listener2 listener) {
    this.listener = checkNotNull(listener, "listener");
    try {
      xdsClientPool = xdsClientPoolFactory.getOrCreate(target, metricRecorder);
    } catch (Exception e) {
      listener.onError(
          Status.UNAVAILABLE.withDescription("Failed to initialize xDS").withCause(e));
      return;
    }
    xdsClient = xdsClientPool.getObject();
    BootstrapInfo bootstrapInfo = xdsClient.getBootstrapInfo();
    String listenerNameTemplate;
    if (targetAuthority == null) {
      listenerNameTemplate = bootstrapInfo.clientDefaultListenerResourceNameTemplate();
    } else {
      AuthorityInfo authorityInfo = bootstrapInfo.authorities().get(targetAuthority);
      if (authorityInfo == null) {
        listener.onError(Status.INVALID_ARGUMENT.withDescription(
            "invalid target URI: target authority not found in the bootstrap"));
        return;
      }
      listenerNameTemplate = authorityInfo.clientListenerResourceNameTemplate();
    }
    String replacement = serviceAuthority;
    if (listenerNameTemplate.startsWith(XDSTP_SCHEME)) {
      replacement = XdsClient.percentEncodePath(replacement);
    }
    String ldsResourceName = expandPercentS(listenerNameTemplate, replacement);
    if (!XdsClient.isResourceNameValid(ldsResourceName, XdsListenerResource.getInstance().typeUrl())
        ) {
      listener.onError(Status.INVALID_ARGUMENT.withDescription(
          "invalid listener resource URI for service authority: " + serviceAuthority));
      return;
    }
    ldsResourceName = XdsClient.canonifyResourceName(ldsResourceName);
    callCounterProvider = SharedCallCounterMap.getInstance();
    resolveState = new ResolveState(ldsResourceName);

    resolveState.start();
  }

  private static String expandPercentS(String template, String replacement) {
    return template.replace("%s", replacement);
  }

  @Override
  public void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutdown");
    if (resolveState != null) {
      resolveState.stop();
    }
    if (xdsClient != null) {
      xdsClient = xdsClientPool.returnObject(xdsClient);
    }
  }

  @VisibleForTesting
  static Map<String, ?> generateServiceConfigWithMethodConfig(
      @Nullable Long timeoutNano, @Nullable RetryPolicy retryPolicy) {
    if (timeoutNano == null
        && (retryPolicy == null || retryPolicy.retryableStatusCodes().isEmpty())) {
      return Collections.emptyMap();
    }
    ImmutableMap.Builder<String, Object> methodConfig = ImmutableMap.builder();
    methodConfig.put(
        "name", Collections.singletonList(Collections.emptyMap()));
    if (retryPolicy != null && !retryPolicy.retryableStatusCodes().isEmpty()) {
      ImmutableMap.Builder<String, Object> rawRetryPolicy = ImmutableMap.builder();
      rawRetryPolicy.put("maxAttempts", (double) retryPolicy.maxAttempts());
      rawRetryPolicy.put("initialBackoff", Durations.toString(retryPolicy.initialBackoff()));
      rawRetryPolicy.put("maxBackoff", Durations.toString(retryPolicy.maxBackoff()));
      rawRetryPolicy.put("backoffMultiplier", 2D);
      List<String> codes = new ArrayList<>(retryPolicy.retryableStatusCodes().size());
      for (Code code : retryPolicy.retryableStatusCodes()) {
        codes.add(code.name());
      }
      rawRetryPolicy.put(
          "retryableStatusCodes", Collections.unmodifiableList(codes));
      if (retryPolicy.perAttemptRecvTimeout() != null) {
        rawRetryPolicy.put(
            "perAttemptRecvTimeout", Durations.toString(retryPolicy.perAttemptRecvTimeout()));
      }
      methodConfig.put("retryPolicy", rawRetryPolicy.buildOrThrow());
    }
    if (timeoutNano != null) {
      String timeout = timeoutNano / 1_000_000_000.0 + "s";
      methodConfig.put("timeout", timeout);
    }
    return Collections.singletonMap(
        "methodConfig", Collections.singletonList(methodConfig.buildOrThrow()));
  }

  @VisibleForTesting
  XdsClient getXdsClient() {
    return xdsClient;
  }

  // called in syncContext
  private void updateResolutionResult() {
    syncContext.throwIfNotInThisSynchronizationContext();

    ImmutableMap.Builder<String, Object> childPolicy = new ImmutableMap.Builder<>();
    for (String name : clusterRefs.keySet()) {
      Map<String, ?> lbPolicy = clusterRefs.get(name).toLbPolicy();
      childPolicy.put(name, ImmutableMap.of("lbPolicy", ImmutableList.of(lbPolicy)));
    }
    Map<String, ?> rawServiceConfig = ImmutableMap.of(
        "loadBalancingConfig",
        ImmutableList.of(ImmutableMap.of(
            XdsLbPolicies.CLUSTER_MANAGER_POLICY_NAME,
            ImmutableMap.of("childPolicy", childPolicy.buildOrThrow()))));

    if (logger.isLoggable(XdsLogLevel.INFO)) {
      logger.log(
          XdsLogLevel.INFO, "Generated service config:\n{0}", new Gson().toJson(rawServiceConfig));
    }
    ConfigOrError parsedServiceConfig = serviceConfigParser.parseServiceConfig(rawServiceConfig);
    Attributes attrs =
        Attributes.newBuilder()
            .set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
            .set(XdsAttributes.CALL_COUNTER_PROVIDER, callCounterProvider)
            .set(InternalConfigSelector.KEY, configSelector)
            .build();
    ResolutionResult result =
        ResolutionResult.newBuilder()
            .setAttributes(attrs)
            .setServiceConfig(parsedServiceConfig)
            .build();
    listener.onResult(result);
    receivedConfig = true;
  }

  /**
   * Returns {@code true} iff {@code hostName} matches the domain name {@code pattern} with
   * case-insensitive.
   *
   * <p>Wildcard pattern rules:
   * <ol>
   * <li>A single asterisk (*) matches any domain.</li>
   * <li>Asterisk (*) is only permitted in the left-most or the right-most part of the pattern,
   *     but not both.</li>
   * </ol>
   */
  @VisibleForTesting
  static boolean matchHostName(String hostName, String pattern) {
    checkArgument(hostName.length() != 0 && !hostName.startsWith(".") && !hostName.endsWith("."),
        "Invalid host name");
    checkArgument(pattern.length() != 0 && !pattern.startsWith(".") && !pattern.endsWith("."),
        "Invalid pattern/domain name");

    hostName = hostName.toLowerCase(Locale.US);
    pattern = pattern.toLowerCase(Locale.US);
    // hostName and pattern are now in lower case -- domain names are case-insensitive.

    if (!pattern.contains("*")) {
      // Not a wildcard pattern -- hostName and pattern must match exactly.
      return hostName.equals(pattern);
    }
    // Wildcard pattern

    if (pattern.length() == 1) {
      return true;
    }

    int index = pattern.indexOf('*');

    // At most one asterisk (*) is allowed.
    if (pattern.indexOf('*', index + 1) != -1) {
      return false;
    }

    // Asterisk can only match prefix or suffix.
    if (index != 0 && index != pattern.length() - 1) {
      return false;
    }

    // HostName must be at least as long as the pattern because asterisk has to
    // match one or more characters.
    if (hostName.length() < pattern.length()) {
      return false;
    }

    if (index == 0 && hostName.endsWith(pattern.substring(1))) {
      // Prefix matching fails.
      return true;
    }

    // Pattern matches hostname if suffix matching succeeds.
    return index == pattern.length() - 1
        && hostName.startsWith(pattern.substring(0, pattern.length() - 1));
  }

  private final class ConfigSelector extends InternalConfigSelector {
    @Override
    public Result selectConfig(PickSubchannelArgs args) {
      RoutingConfig routingCfg;
      RouteData selectedRoute;
      String cluster;
      ClientInterceptor filters;
      Metadata headers = args.getHeaders();
      String path = "/" + args.getMethodDescriptor().getFullMethodName();
      do {
        routingCfg = routingConfig;
        selectedRoute = null;
        for (RouteData route : routingCfg.routes) {
          if (RoutingUtils.matchRoute(route.routeMatch, path, headers, random)) {
            selectedRoute = route;
            break;
          }
        }
        if (selectedRoute == null) {
          return Result.forError(
              Status.UNAVAILABLE.withDescription("Could not find xDS route matching RPC"));
        }
        if (selectedRoute.routeAction == null) {
          return Result.forError(Status.UNAVAILABLE.withDescription(
              "Could not route RPC to Route with non-forwarding action"));
        }
        RouteAction action = selectedRoute.routeAction;
        if (action.cluster() != null) {
          cluster = prefixedClusterName(action.cluster());
          filters = selectedRoute.filterChoices.get(0);
        } else if (action.weightedClusters() != null) {
          // XdsRouteConfigureResource verifies the total weight will not be 0 or exceed uint32
          long totalWeight = 0;
          for (ClusterWeight weightedCluster : action.weightedClusters()) {
            totalWeight += weightedCluster.weight();
          }
          long select = random.nextLong(totalWeight);
          long accumulator = 0;
          for (int i = 0; ; i++) {
            ClusterWeight weightedCluster = action.weightedClusters().get(i);
            accumulator += weightedCluster.weight();
            if (select < accumulator) {
              cluster = prefixedClusterName(weightedCluster.name());
              filters = selectedRoute.filterChoices.get(i);
              break;
            }
          }
        } else if (action.namedClusterSpecifierPluginConfig() != null) {
          cluster =
              prefixedClusterSpecifierPluginName(action.namedClusterSpecifierPluginConfig().name());
          filters = selectedRoute.filterChoices.get(0);
        } else {
          // updateRoutes() discards routes with unknown actions
          throw new AssertionError();
        }
      } while (!retainCluster(cluster));

      final RouteAction routeAction = selectedRoute.routeAction;
      Long timeoutNanos = null;
      if (enableTimeout) {
        timeoutNanos = routeAction.timeoutNano();
        if (timeoutNanos == null) {
          timeoutNanos = routingCfg.fallbackTimeoutNano;
        }
        if (timeoutNanos <= 0) {
          timeoutNanos = null;
        }
      }
      RetryPolicy retryPolicy = routeAction.retryPolicy();
      // TODO(chengyuanzhang): avoid service config generation and parsing for each call.
      Map<String, ?> rawServiceConfig =
          generateServiceConfigWithMethodConfig(timeoutNanos, retryPolicy);
      ConfigOrError parsedServiceConfig = serviceConfigParser.parseServiceConfig(rawServiceConfig);
      Object config = parsedServiceConfig.getConfig();
      if (config == null) {
        releaseCluster(cluster);
        return Result.forError(
            parsedServiceConfig.getError().augmentDescription(
                "Failed to parse service config (method config)"));
      }
      final String finalCluster = cluster;
      final long hash = generateHash(routeAction.hashPolicies(), headers);
      class ClusterSelectionInterceptor implements ClientInterceptor {
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            final MethodDescriptor<ReqT, RespT> method, CallOptions callOptions,
            final Channel next) {
          CallOptions callOptionsForCluster =
              callOptions.withOption(CLUSTER_SELECTION_KEY, finalCluster)
                  .withOption(RPC_HASH_KEY, hash);
          if (routeAction.autoHostRewrite()) {
            callOptionsForCluster = callOptionsForCluster.withOption(AUTO_HOST_REWRITE_KEY, true);
          }
          return new SimpleForwardingClientCall<ReqT, RespT>(
              next.newCall(method, callOptionsForCluster)) {
            @Override
            public void start(Listener<RespT> listener, Metadata headers) {
              listener = new SimpleForwardingClientCallListener<RespT>(listener) {
                boolean committed;

                @Override
                public void onHeaders(Metadata headers) {
                  committed = true;
                  releaseCluster(finalCluster);
                  delegate().onHeaders(headers);
                }

                @Override
                public void onClose(Status status, Metadata trailers) {
                  if (!committed) {
                    releaseCluster(finalCluster);
                  }
                  delegate().onClose(status, trailers);
                }
              };
              delegate().start(listener, headers);
            }
          };
        }
      }

      return
          Result.newBuilder()
              .setConfig(config)
              .setInterceptor(combineInterceptors(
                  ImmutableList.of(filters, new ClusterSelectionInterceptor())))
              .build();
    }

    private boolean retainCluster(String cluster) {
      ClusterRefState clusterRefState = clusterRefs.get(cluster);
      if (clusterRefState == null) {
        return false;
      }
      AtomicInteger refCount = clusterRefState.refCount;
      int count;
      do {
        count = refCount.get();
        if (count == 0) {
          return false;
        }
      } while (!refCount.compareAndSet(count, count + 1));
      return true;
    }

    private void releaseCluster(final String cluster) {
      int count = clusterRefs.get(cluster).refCount.decrementAndGet();
      if (count == 0) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (clusterRefs.get(cluster).refCount.get() == 0) {
              clusterRefs.remove(cluster);
              updateResolutionResult();
            }
          }
        });
      }
    }

    private long generateHash(List<HashPolicy> hashPolicies, Metadata headers) {
      Long hash = null;
      for (HashPolicy policy : hashPolicies) {
        Long newHash = null;
        if (policy.type() == HashPolicy.Type.HEADER) {
          String value = getHeaderValue(headers, policy.headerName());
          if (value != null) {
            if (policy.regEx() != null && policy.regExSubstitution() != null) {
              value = policy.regEx().matcher(value).replaceAll(policy.regExSubstitution());
            }
            newHash = hashFunc.hashAsciiString(value);
          }
        } else if (policy.type() == HashPolicy.Type.CHANNEL_ID) {
          newHash = hashFunc.hashLong(randomChannelId);
        }
        if (newHash != null ) {
          // Rotating the old value prevents duplicate hash rules from cancelling each other out
          // and preserves all of the entropy.
          long oldHash = hash != null ? ((hash << 1L) | (hash >> 63L)) : 0;
          hash = oldHash ^ newHash;
        }
        // If the policy is a terminal policy and a hash has been generated, ignore
        // the rest of the hash policies.
        if (policy.isTerminal() && hash != null) {
          break;
        }
      }
      return hash == null ? random.nextLong() : hash;
    }
  }

  static final class PassthroughClientInterceptor implements ClientInterceptor {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      return next.newCall(method, callOptions);
    }
  }

  private static ClientInterceptor combineInterceptors(final List<ClientInterceptor> interceptors) {
    if (interceptors.size() == 0) {
      return new PassthroughClientInterceptor();
    }
    if (interceptors.size() == 1) {
      return interceptors.get(0);
    }
    return new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        next = ClientInterceptors.interceptForward(next, interceptors);
        return next.newCall(method, callOptions);
      }
    };
  }

  @Nullable
  private static String getHeaderValue(Metadata headers, String headerName) {
    if (headerName.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
      return null;
    }
    if (headerName.equals("content-type")) {
      return "application/grpc";
    }
    Metadata.Key<String> key;
    try {
      key = Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER);
    } catch (IllegalArgumentException e) {
      return null;
    }
    Iterable<String> values = headers.getAll(key);
    return values == null ? null : Joiner.on(",").join(values);
  }

  private static String prefixedClusterName(String name) {
    return "cluster:" + name;
  }

  private static String prefixedClusterSpecifierPluginName(String pluginName) {
    return "cluster_specifier_plugin:" + pluginName;
  }

  private static final class FailingConfigSelector extends InternalConfigSelector {
    private final Result result;

    public FailingConfigSelector(Status error) {
      this.result = Result.forError(error);
    }

    @Override
    public Result selectConfig(PickSubchannelArgs args) {
      return result;
    }
  }

  private class ResolveState implements ResourceWatcher<XdsListenerResource.LdsUpdate> {
    private final ConfigOrError emptyServiceConfig =
        serviceConfigParser.parseServiceConfig(Collections.<String, Object>emptyMap());
    private final String ldsResourceName;
    private boolean stopped;
    @Nullable
    private Set<String> existingClusters;  // clusters to which new requests can be routed
    @Nullable
    private RouteDiscoveryState routeDiscoveryState;

    ResolveState(String ldsResourceName) {
      this.ldsResourceName = ldsResourceName;
    }

    @Override
    public void onChanged(final XdsListenerResource.LdsUpdate update) {
      if (stopped) {
        return;
      }
      logger.log(XdsLogLevel.INFO, "Receive LDS resource update: {0}", update);
      HttpConnectionManager httpConnectionManager = update.httpConnectionManager();
      List<VirtualHost> virtualHosts = httpConnectionManager.virtualHosts();
      String rdsName = httpConnectionManager.rdsName();
      ImmutableList<NamedFilterConfig> filterConfigs = httpConnectionManager.httpFilterConfigs();
      long streamDurationNano = httpConnectionManager.httpMaxStreamDurationNano();

      // Create/update HCM-bound state.
      cleanUpRouteDiscoveryState();
      updateActiveFilters(filterConfigs);

      // Routes specified directly in LDS.
      if (virtualHosts != null) {
        updateRoutes(virtualHosts, streamDurationNano, filterConfigs);
        return;
      }
      // Routes provided by RDS.
      routeDiscoveryState = new RouteDiscoveryState(rdsName, streamDurationNano, filterConfigs);
      logger.log(XdsLogLevel.INFO, "Start watching RDS resource {0}", rdsName);
      xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),
          rdsName, routeDiscoveryState, syncContext);
    }

    @Override
    public void onError(final Status error) {
      if (stopped || receivedConfig) {
        return;
      }
      listener.onError(Status.UNAVAILABLE.withCause(error.getCause()).withDescription(
          String.format("Unable to load LDS %s. xDS server returned: %s: %s",
          ldsResourceName, error.getCode(), error.getDescription())));
    }

    @Override
    public void onResourceDoesNotExist(final String resourceName) {
      if (stopped) {
        return;
      }
      String error = "LDS resource does not exist: " + resourceName;
      logger.log(XdsLogLevel.INFO, error);
      cleanUpRouteDiscoveryState();
      updateActiveFilters(null);
      cleanUpRoutes(error);
    }

    private void start() {
      logger.log(XdsLogLevel.INFO, "Start watching LDS resource {0}", ldsResourceName);
      xdsClient.watchXdsResource(XdsListenerResource.getInstance(),
          ldsResourceName, this, syncContext);
    }

    private void stop() {
      logger.log(XdsLogLevel.INFO, "Stop watching LDS resource {0}", ldsResourceName);
      stopped = true;
      cleanUpRouteDiscoveryState();
      updateActiveFilters(null);
      xdsClient.cancelXdsResourceWatch(XdsListenerResource.getInstance(), ldsResourceName, this);
    }

    // called in syncContext
    private void updateActiveFilters(@Nullable List<NamedFilterConfig> filterConfigs) {
      if (filterConfigs == null) {
        filterConfigs = ImmutableList.of();
      }
      Set<String> filtersToShutdown = new HashSet<>(activeFilters.keySet());
      for (NamedFilterConfig namedFilter : filterConfigs) {
        String typeUrl = namedFilter.filterConfig.typeUrl();
        String filterKey = namedFilter.filterStateKey();

        Filter.Provider provider = filterRegistry.get(typeUrl);
        checkNotNull(provider, "provider %s", typeUrl);
        Filter filter = activeFilters.computeIfAbsent(filterKey, k -> provider.newInstance());
        checkNotNull(filter, "filter %s", filterKey);
        filtersToShutdown.remove(filterKey);
      }

      // Shutdown filters not present in current HCM.
      for (String filterKey : filtersToShutdown) {
        Filter filterToShutdown = activeFilters.remove(filterKey);
        checkNotNull(filterToShutdown, "filterToShutdown %s", filterKey);
        filterToShutdown.close();
      }
    }

    // called in syncContext
    private void updateRoutes(List<VirtualHost> virtualHosts, long httpMaxStreamDurationNano,
        @Nullable List<NamedFilterConfig> filterConfigs) {
      String authority = overrideAuthority != null ? overrideAuthority : encodedServiceAuthority;
      VirtualHost virtualHost = RoutingUtils.findVirtualHostForHostName(virtualHosts, authority);
      if (virtualHost == null) {
        String error = "Failed to find virtual host matching hostname: " + authority;
        logger.log(XdsLogLevel.WARNING, error);
        cleanUpRoutes(error);
        return;
      }

      List<Route> routes = virtualHost.routes();
      ImmutableList.Builder<RouteData> routesData = ImmutableList.builder();

      // Populate all clusters to which requests can be routed to through the virtual host.
      Set<String> clusters = new HashSet<>();
      // uniqueName -> clusterName
      Map<String, String> clusterNameMap = new HashMap<>();
      // uniqueName -> pluginConfig
      Map<String, RlsPluginConfig> rlsPluginConfigMap = new HashMap<>();
      for (Route route : routes) {
        RouteAction action = route.routeAction();
        String prefixedName;
        if (action == null) {
          routesData.add(new RouteData(route.routeMatch(), null, ImmutableList.of()));
        } else if (action.cluster() != null) {
          prefixedName = prefixedClusterName(action.cluster());
          clusters.add(prefixedName);
          clusterNameMap.put(prefixedName, action.cluster());
          ClientInterceptor filters = createFilters(filterConfigs, virtualHost, route, null);
          routesData.add(new RouteData(route.routeMatch(), route.routeAction(), filters));
        } else if (action.weightedClusters() != null) {
          ImmutableList.Builder<ClientInterceptor> filterList = ImmutableList.builder();
          for (ClusterWeight weightedCluster : action.weightedClusters()) {
            prefixedName = prefixedClusterName(weightedCluster.name());
            clusters.add(prefixedName);
            clusterNameMap.put(prefixedName, weightedCluster.name());
            filterList.add(createFilters(filterConfigs, virtualHost, route, weightedCluster));
          }
          routesData.add(
              new RouteData(route.routeMatch(), route.routeAction(), filterList.build()));
        } else if (action.namedClusterSpecifierPluginConfig() != null) {
          PluginConfig pluginConfig = action.namedClusterSpecifierPluginConfig().config();
          if (pluginConfig instanceof RlsPluginConfig) {
            prefixedName = prefixedClusterSpecifierPluginName(
                action.namedClusterSpecifierPluginConfig().name());
            clusters.add(prefixedName);
            rlsPluginConfigMap.put(prefixedName, (RlsPluginConfig) pluginConfig);
          }
          ClientInterceptor filters = createFilters(filterConfigs, virtualHost, route, null);
          routesData.add(new RouteData(route.routeMatch(), route.routeAction(), filters));
        } else {
          // Discard route
        }
      }

      // Updates channel's load balancing config whenever the set of selectable clusters changes.
      boolean shouldUpdateResult = existingClusters == null;
      Set<String> addedClusters =
          existingClusters == null ? clusters : Sets.difference(clusters, existingClusters);
      Set<String> deletedClusters =
          existingClusters == null
              ? Collections.emptySet() : Sets.difference(existingClusters, clusters);
      existingClusters = clusters;
      for (String cluster : addedClusters) {
        if (clusterRefs.containsKey(cluster)) {
          clusterRefs.get(cluster).refCount.incrementAndGet();
        } else {
          if (clusterNameMap.containsKey(cluster)) {
            clusterRefs.put(
                cluster,
                ClusterRefState.forCluster(new AtomicInteger(1), clusterNameMap.get(cluster)));
          }
          if (rlsPluginConfigMap.containsKey(cluster)) {
            clusterRefs.put(
                cluster,
                ClusterRefState.forRlsPlugin(
                    new AtomicInteger(1), rlsPluginConfigMap.get(cluster)));
          }
          shouldUpdateResult = true;
        }
      }
      for (String cluster : clusters) {
        RlsPluginConfig rlsPluginConfig = rlsPluginConfigMap.get(cluster);
        if (!Objects.equals(rlsPluginConfig, clusterRefs.get(cluster).rlsPluginConfig)) {
          ClusterRefState newClusterRefState =
              ClusterRefState.forRlsPlugin(clusterRefs.get(cluster).refCount, rlsPluginConfig);
          clusterRefs.put(cluster, newClusterRefState);
          shouldUpdateResult = true;
        }
      }
      // Update service config to include newly added clusters.
      if (shouldUpdateResult) {
        updateResolutionResult();
      }
      // Make newly added clusters selectable by config selector and deleted clusters no longer
      // selectable.
      routingConfig = new RoutingConfig(httpMaxStreamDurationNano, routesData.build());
      shouldUpdateResult = false;
      for (String cluster : deletedClusters) {
        int count = clusterRefs.get(cluster).refCount.decrementAndGet();
        if (count == 0) {
          clusterRefs.remove(cluster);
          shouldUpdateResult = true;
        }
      }
      if (shouldUpdateResult) {
        updateResolutionResult();
      }
    }

    private ClientInterceptor createFilters(
        @Nullable List<NamedFilterConfig> filterConfigs,
        VirtualHost virtualHost,
        Route route,
        @Nullable ClusterWeight weightedCluster) {
      if (filterConfigs == null) {
        return new PassthroughClientInterceptor();
      }

      Map<String, FilterConfig> selectedOverrideConfigs =
          new HashMap<>(virtualHost.filterConfigOverrides());
      selectedOverrideConfigs.putAll(route.filterConfigOverrides());
      if (weightedCluster != null) {
        selectedOverrideConfigs.putAll(weightedCluster.filterConfigOverrides());
      }

      ImmutableList.Builder<ClientInterceptor> filterInterceptors = ImmutableList.builder();
      for (NamedFilterConfig namedFilter : filterConfigs) {
        String name = namedFilter.name;
        FilterConfig config = namedFilter.filterConfig;
        FilterConfig overrideConfig = selectedOverrideConfigs.get(name);
        String filterKey = namedFilter.filterStateKey();

        Filter filter = activeFilters.get(filterKey);
        checkNotNull(filter, "activeFilters.get(%s)", filterKey);
        ClientInterceptor interceptor =
            filter.buildClientInterceptor(config, overrideConfig, scheduler);

        if (interceptor != null) {
          filterInterceptors.add(interceptor);
        }
      }

      // Combine interceptors produced by different filters into a single one that executes
      // them sequentially. The order is preserved.
      return combineInterceptors(filterInterceptors.build());
    }

    private void cleanUpRoutes(String error) {
      if (existingClusters != null) {
        for (String cluster : existingClusters) {
          int count = clusterRefs.get(cluster).refCount.decrementAndGet();
          if (count == 0) {
            clusterRefs.remove(cluster);
          }
        }
        existingClusters = null;
      }
      routingConfig = RoutingConfig.EMPTY;
      // Without addresses the default LB (normally pick_first) should become TRANSIENT_FAILURE, and
      // the config selector handles the error message itself. Once the LB API allows providing
      // failure information for addresses yet still providing a service config, the config seector
      // could be avoided.
      String errorWithNodeId =
          error + ", xDS node ID: " + xdsClient.getBootstrapInfo().node().getId();
      listener.onResult(ResolutionResult.newBuilder()
          .setAttributes(Attributes.newBuilder()
            .set(InternalConfigSelector.KEY,
              new FailingConfigSelector(Status.UNAVAILABLE.withDescription(errorWithNodeId)))
            .build())
          .setServiceConfig(emptyServiceConfig)
          .build());
      receivedConfig = true;
    }

    private void cleanUpRouteDiscoveryState() {
      if (routeDiscoveryState != null) {
        String rdsName = routeDiscoveryState.resourceName;
        logger.log(XdsLogLevel.INFO, "Stop watching RDS resource {0}", rdsName);
        xdsClient.cancelXdsResourceWatch(XdsRouteConfigureResource.getInstance(), rdsName,
            routeDiscoveryState);
        routeDiscoveryState = null;
      }
    }

    /**
     * Discovery state for RouteConfiguration resource. One instance for each Listener resource
     * update.
     */
    private class RouteDiscoveryState implements ResourceWatcher<RdsUpdate> {
      private final String resourceName;
      private final long httpMaxStreamDurationNano;
      @Nullable
      private final List<NamedFilterConfig> filterConfigs;

      private RouteDiscoveryState(String resourceName, long httpMaxStreamDurationNano,
          @Nullable List<NamedFilterConfig> filterConfigs) {
        this.resourceName = resourceName;
        this.httpMaxStreamDurationNano = httpMaxStreamDurationNano;
        this.filterConfigs = filterConfigs;
      }

      @Override
      public void onChanged(final RdsUpdate update) {
        if (RouteDiscoveryState.this != routeDiscoveryState) {
          return;
        }
        logger.log(XdsLogLevel.INFO, "Received RDS resource update: {0}", update);
        updateRoutes(update.virtualHosts, httpMaxStreamDurationNano, filterConfigs);
      }

      @Override
      public void onError(final Status error) {
        if (RouteDiscoveryState.this != routeDiscoveryState || receivedConfig) {
          return;
        }
        listener.onError(Status.UNAVAILABLE.withCause(error.getCause()).withDescription(
            String.format("Unable to load RDS %s. xDS server returned: %s: %s",
            resourceName, error.getCode(), error.getDescription())));
      }

      @Override
      public void onResourceDoesNotExist(final String resourceName) {
        if (RouteDiscoveryState.this != routeDiscoveryState) {
          return;
        }
        String error = "RDS resource does not exist: " + resourceName;
        logger.log(XdsLogLevel.INFO, error);
        cleanUpRoutes(error);
      }
    }
  }

  /**
   * VirtualHost-level configuration for request routing.
   */
  private static class RoutingConfig {
    private final long fallbackTimeoutNano;
    final ImmutableList<RouteData> routes;

    private static final RoutingConfig EMPTY = new RoutingConfig(0, ImmutableList.of());

    private RoutingConfig(long fallbackTimeoutNano, ImmutableList<RouteData> routes) {
      this.fallbackTimeoutNano = fallbackTimeoutNano;
      this.routes = checkNotNull(routes, "routes");
    }
  }

  static final class RouteData {
    final RouteMatch routeMatch;
    /** null implies non-forwarding action. */
    @Nullable
    final RouteAction routeAction;
    /**
     * Only one of these interceptors should be used per-RPC. There are only multiple values in the
     * list for weighted clusters, in which case the order of the list mirrors the weighted
     * clusters.
     */
    final ImmutableList<ClientInterceptor> filterChoices;

    RouteData(RouteMatch routeMatch, @Nullable RouteAction routeAction, ClientInterceptor filter) {
      this(routeMatch, routeAction, ImmutableList.of(filter));
    }

    RouteData(
        RouteMatch routeMatch,
        @Nullable RouteAction routeAction,
        ImmutableList<ClientInterceptor> filterChoices) {
      this.routeMatch = checkNotNull(routeMatch, "routeMatch");
      checkArgument(
          routeAction == null || !filterChoices.isEmpty(),
          "filter may be empty only for non-forwarding action");
      this.routeAction = routeAction;
      if (routeAction != null && routeAction.weightedClusters() != null) {
        checkArgument(
            routeAction.weightedClusters().size() == filterChoices.size(),
            "filter choices must match size of weighted clusters");
      }
      for (ClientInterceptor filter : filterChoices) {
        checkNotNull(filter, "entry in filterChoices is null");
      }
      this.filterChoices = checkNotNull(filterChoices, "filterChoices");
    }
  }

  private static class ClusterRefState {
    final AtomicInteger refCount;
    @Nullable
    final String traditionalCluster;
    @Nullable
    final RlsPluginConfig rlsPluginConfig;

    private ClusterRefState(
        AtomicInteger refCount, @Nullable String traditionalCluster,
        @Nullable RlsPluginConfig rlsPluginConfig) {
      this.refCount = refCount;
      checkArgument(traditionalCluster == null ^ rlsPluginConfig == null,
          "There must be exactly one non-null value in traditionalCluster and pluginConfig");
      this.traditionalCluster = traditionalCluster;
      this.rlsPluginConfig = rlsPluginConfig;
    }

    private Map<String, ?> toLbPolicy() {
      if (traditionalCluster != null) {
        return ImmutableMap.of(
            XdsLbPolicies.CDS_POLICY_NAME,
            ImmutableMap.of("cluster", traditionalCluster));
      } else {
        ImmutableMap<String, ?> rlsConfig = new ImmutableMap.Builder<String, Object>()
            .put("routeLookupConfig", rlsPluginConfig.config())
            .put(
                "childPolicy",
                ImmutableList.of(ImmutableMap.of(XdsLbPolicies.CDS_POLICY_NAME, ImmutableMap.of())))
            .put("childPolicyConfigTargetFieldName", "cluster")
            .buildOrThrow();
        return ImmutableMap.of("rls_experimental", rlsConfig);
      }
    }

    static ClusterRefState forCluster(AtomicInteger refCount, String name) {
      return new ClusterRefState(refCount, name, null);
    }

    static ClusterRefState forRlsPlugin(AtomicInteger refCount, RlsPluginConfig rlsPluginConfig) {
      return new ClusterRefState(refCount, null, rlsPluginConfig);
    }
  }
}
