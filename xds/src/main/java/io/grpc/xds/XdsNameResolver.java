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
import static io.grpc.xds.Bootstrapper.XDSTP_SCHEME;

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
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.Bootstrapper.AuthorityInfo;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.ClusterSpecifierPlugin.PluginConfig;
import io.grpc.xds.Filter.ClientInterceptorBuilder;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.Filter.NamedFilterConfig;
import io.grpc.xds.RouteLookupServiceClusterSpecifierPlugin.RlsPluginConfig;
import io.grpc.xds.ThreadSafeRandom.ThreadSafeRandomImpl;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.VirtualHost.Route.RouteAction;
import io.grpc.xds.VirtualHost.Route.RouteAction.ClusterWeight;
import io.grpc.xds.VirtualHost.Route.RouteAction.HashPolicy;
import io.grpc.xds.VirtualHost.Route.RouteAction.RetryPolicy;
import io.grpc.xds.XdsClient.ResourceWatcher;
import io.grpc.xds.XdsListenerResource.LdsUpdate;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsNameResolverProvider.CallCounterProvider;
import io.grpc.xds.XdsNameResolverProvider.XdsClientPoolFactory;
import io.grpc.xds.XdsRouteConfigureResource.RdsUpdate;
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
  @VisibleForTesting
  static boolean enableTimeout =
      Strings.isNullOrEmpty(System.getenv("GRPC_XDS_EXPERIMENTAL_ENABLE_TIMEOUT"))
          || Boolean.parseBoolean(System.getenv("GRPC_XDS_EXPERIMENTAL_ENABLE_TIMEOUT"));

  private final InternalLogId logId;
  private final XdsLogger logger;
  @Nullable
  private final String targetAuthority;
  private final String serviceAuthority;
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

  private volatile RoutingConfig routingConfig = RoutingConfig.empty;
  private Listener2 listener;
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private CallCounterProvider callCounterProvider;
  private ResolveState resolveState;
  // Workaround for https://github.com/grpc/grpc-java/issues/8886 . This should be handled in
  // XdsClient instead of here.
  private boolean receivedConfig;

  XdsNameResolver(
      @Nullable String targetAuthority, String name, @Nullable String overrideAuthority,
      ServiceConfigParser serviceConfigParser,
      SynchronizationContext syncContext, ScheduledExecutorService scheduler,
      @Nullable Map<String, ?> bootstrapOverride) {
    this(targetAuthority, name, overrideAuthority, serviceConfigParser, syncContext, scheduler,
        SharedXdsClientPoolProvider.getDefaultProvider(), ThreadSafeRandomImpl.instance,
        FilterRegistry.getDefaultRegistry(), bootstrapOverride);
  }

  @VisibleForTesting
  XdsNameResolver(
      @Nullable String targetAuthority, String name, @Nullable String overrideAuthority,
      ServiceConfigParser serviceConfigParser,
      SynchronizationContext syncContext, ScheduledExecutorService scheduler,
      XdsClientPoolFactory xdsClientPoolFactory, ThreadSafeRandom random,
      FilterRegistry filterRegistry, @Nullable Map<String, ?> bootstrapOverride) {
    this.targetAuthority = targetAuthority;
    serviceAuthority = GrpcUtil.checkAuthority(checkNotNull(name, "name"));
    this.overrideAuthority = overrideAuthority;
    this.serviceConfigParser = checkNotNull(serviceConfigParser, "serviceConfigParser");
    this.syncContext = checkNotNull(syncContext, "syncContext");
    this.scheduler = checkNotNull(scheduler, "scheduler");
    this.xdsClientPoolFactory = bootstrapOverride == null ? checkNotNull(xdsClientPoolFactory,
            "xdsClientPoolFactory") : new SharedXdsClientPoolProvider();
    this.xdsClientPoolFactory.setBootstrapOverride(bootstrapOverride);
    this.random = checkNotNull(random, "random");
    this.filterRegistry = checkNotNull(filterRegistry, "filterRegistry");
    randomChannelId = random.nextLong();
    logId = InternalLogId.allocate("xds-resolver", name);
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogLevel.INFO, "Created resolver for {0}", name);
  }

  @Override
  public String getServiceAuthority() {
    return serviceAuthority;
  }

  @Override
  public void start(Listener2 listener) {
    this.listener = checkNotNull(listener, "listener");
    try {
      xdsClientPool = xdsClientPoolFactory.getOrCreate();
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
            .set(InternalXdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
            .set(InternalXdsAttributes.CALL_COUNTER_PROVIDER, callCounterProvider)
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
      String cluster = null;
      Route selectedRoute = null;
      RoutingConfig routingCfg;
      Map<String, FilterConfig> selectedOverrideConfigs;
      List<ClientInterceptor> filterInterceptors = new ArrayList<>();
      Metadata headers = args.getHeaders();
      do {
        routingCfg = routingConfig;
        selectedOverrideConfigs = new HashMap<>(routingCfg.virtualHostOverrideConfig);
        for (Route route : routingCfg.routes) {
          if (RoutingUtils.matchRoute(
                  route.routeMatch(), "/" + args.getMethodDescriptor().getFullMethodName(),
              headers, random)) {
            selectedRoute = route;
            selectedOverrideConfigs.putAll(route.filterConfigOverrides());
            break;
          }
        }
        if (selectedRoute == null) {
          return Result.forError(
              Status.UNAVAILABLE.withDescription("Could not find xDS route matching RPC"));
        }
        if (selectedRoute.routeAction() == null) {
          return Result.forError(Status.UNAVAILABLE.withDescription(
              "Could not route RPC to Route with non-forwarding action"));
        }
        RouteAction action = selectedRoute.routeAction();
        if (action.cluster() != null) {
          cluster = prefixedClusterName(action.cluster());
        } else if (action.weightedClusters() != null) {
          long totalWeight = 0;
          for (ClusterWeight weightedCluster : action.weightedClusters()) {
            totalWeight += weightedCluster.weight();
          }
          long select = random.nextLong(totalWeight);
          long accumulator = 0;
          for (ClusterWeight weightedCluster : action.weightedClusters()) {
            accumulator += weightedCluster.weight();
            if (select < accumulator) {
              cluster = prefixedClusterName(weightedCluster.name());
              selectedOverrideConfigs.putAll(weightedCluster.filterConfigOverrides());
              break;
            }
          }
        } else if (action.namedClusterSpecifierPluginConfig() != null) {
          cluster =
              prefixedClusterSpecifierPluginName(action.namedClusterSpecifierPluginConfig().name());
        }
      } while (!retainCluster(cluster));
      Long timeoutNanos = null;
      if (enableTimeout) {
        if (selectedRoute != null) {
          timeoutNanos = selectedRoute.routeAction().timeoutNano();
        }
        if (timeoutNanos == null) {
          timeoutNanos = routingCfg.fallbackTimeoutNano;
        }
        if (timeoutNanos <= 0) {
          timeoutNanos = null;
        }
      }
      RetryPolicy retryPolicy =
          selectedRoute == null ? null : selectedRoute.routeAction().retryPolicy();
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
      if (routingCfg.filterChain != null) {
        for (NamedFilterConfig namedFilter : routingCfg.filterChain) {
          FilterConfig filterConfig = namedFilter.filterConfig;
          Filter filter = filterRegistry.get(filterConfig.typeUrl());
          if (filter instanceof ClientInterceptorBuilder) {
            ClientInterceptor interceptor = ((ClientInterceptorBuilder) filter)
                .buildClientInterceptor(
                    filterConfig, selectedOverrideConfigs.get(namedFilter.name),
                    args, scheduler);
            if (interceptor != null) {
              filterInterceptors.add(interceptor);
            }
          }
        }
      }
      final String finalCluster = cluster;
      final long hash = generateHash(selectedRoute.routeAction().hashPolicies(), headers);
      class ClusterSelectionInterceptor implements ClientInterceptor {
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            final MethodDescriptor<ReqT, RespT> method, CallOptions callOptions,
            final Channel next) {
          final CallOptions callOptionsForCluster =
              callOptions.withOption(CLUSTER_SELECTION_KEY, finalCluster)
                  .withOption(RPC_HASH_KEY, hash);
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

      filterInterceptors.add(new ClusterSelectionInterceptor());
      return
          Result.newBuilder()
              .setConfig(config)
              .setInterceptor(combineInterceptors(filterInterceptors))
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

  private static ClientInterceptor combineInterceptors(final List<ClientInterceptor> interceptors) {
    checkArgument(!interceptors.isEmpty(), "empty interceptors");
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

  private class ResolveState implements ResourceWatcher<LdsUpdate> {
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
    public void onChanged(final LdsUpdate update) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped) {
            return;
          }
          logger.log(XdsLogLevel.INFO, "Receive LDS resource update: {0}", update);
          HttpConnectionManager httpConnectionManager = update.httpConnectionManager();
          List<VirtualHost> virtualHosts = httpConnectionManager.virtualHosts();
          String rdsName = httpConnectionManager.rdsName();
          cleanUpRouteDiscoveryState();
          if (virtualHosts != null) {
            updateRoutes(virtualHosts, httpConnectionManager.httpMaxStreamDurationNano(),
                httpConnectionManager.httpFilterConfigs());
          } else {
            routeDiscoveryState = new RouteDiscoveryState(
                rdsName, httpConnectionManager.httpMaxStreamDurationNano(),
                httpConnectionManager.httpFilterConfigs());
            logger.log(XdsLogLevel.INFO, "Start watching RDS resource {0}", rdsName);
            xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),
                rdsName, routeDiscoveryState);
          }
        }
      });
    }

    @Override
    public void onError(final Status error) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped || receivedConfig) {
            return;
          }
          listener.onError(Status.UNAVAILABLE.withCause(error.getCause()).withDescription(
              String.format("Unable to load LDS %s. xDS server returned: %s: %s",
              ldsResourceName, error.getCode(), error.getDescription())));
        }
      });
    }

    @Override
    public void onResourceDoesNotExist(final String resourceName) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped) {
            return;
          }
          String error = "LDS resource does not exist: " + resourceName;
          logger.log(XdsLogLevel.INFO, error);
          cleanUpRouteDiscoveryState();
          cleanUpRoutes(error);
        }
      });
    }

    private void start() {
      logger.log(XdsLogLevel.INFO, "Start watching LDS resource {0}", ldsResourceName);
      xdsClient.watchXdsResource(XdsListenerResource.getInstance(), ldsResourceName, this);
    }

    private void stop() {
      logger.log(XdsLogLevel.INFO, "Stop watching LDS resource {0}", ldsResourceName);
      stopped = true;
      cleanUpRouteDiscoveryState();
      xdsClient.cancelXdsResourceWatch(XdsListenerResource.getInstance(), ldsResourceName, this);
    }

    // called in syncContext
    private void updateRoutes(List<VirtualHost> virtualHosts, long httpMaxStreamDurationNano,
        @Nullable List<NamedFilterConfig> filterConfigs) {
      String authority = overrideAuthority != null ? overrideAuthority : ldsResourceName;
      VirtualHost virtualHost = RoutingUtils.findVirtualHostForHostName(virtualHosts, authority);
      if (virtualHost == null) {
        String error = "Failed to find virtual host matching hostname: " + authority;
        logger.log(XdsLogLevel.WARNING, error);
        cleanUpRoutes(error);
        return;
      }

      List<Route> routes = virtualHost.routes();

      // Populate all clusters to which requests can be routed to through the virtual host.
      Set<String> clusters = new HashSet<>();
      // uniqueName -> clusterName
      Map<String, String> clusterNameMap = new HashMap<>();
      // uniqueName -> pluginConfig
      Map<String, RlsPluginConfig> rlsPluginConfigMap = new HashMap<>();
      for (Route route : routes) {
        RouteAction action = route.routeAction();
        String prefixedName;
        if (action != null) {
          if (action.cluster() != null) {
            prefixedName = prefixedClusterName(action.cluster());
            clusters.add(prefixedName);
            clusterNameMap.put(prefixedName, action.cluster());
          } else if (action.weightedClusters() != null) {
            for (ClusterWeight weighedCluster : action.weightedClusters()) {
              prefixedName = prefixedClusterName(weighedCluster.name());
              clusters.add(prefixedName);
              clusterNameMap.put(prefixedName, weighedCluster.name());
            }
          } else if (action.namedClusterSpecifierPluginConfig() != null) {
            PluginConfig pluginConfig = action.namedClusterSpecifierPluginConfig().config();
            if (pluginConfig instanceof RlsPluginConfig) {
              prefixedName = prefixedClusterSpecifierPluginName(
                  action.namedClusterSpecifierPluginConfig().name());
              clusters.add(prefixedName);
              rlsPluginConfigMap.put(prefixedName, (RlsPluginConfig) pluginConfig);
            }
          }
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
      routingConfig =
          new RoutingConfig(
              httpMaxStreamDurationNano, routes, filterConfigs,
              virtualHost.filterConfigOverrides());
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
      routingConfig = RoutingConfig.empty;
      // Without addresses the default LB (normally pick_first) should become TRANSIENT_FAILURE, and
      // the config selector handles the error message itself. Once the LB API allows providing
      // failure information for addresses yet still providing a service config, the config seector
      // could be avoided.
      listener.onResult(ResolutionResult.newBuilder()
          .setAttributes(Attributes.newBuilder()
            .set(InternalConfigSelector.KEY,
              new FailingConfigSelector(Status.UNAVAILABLE.withDescription(error)))
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
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (RouteDiscoveryState.this != routeDiscoveryState) {
              return;
            }
            logger.log(XdsLogLevel.INFO, "Received RDS resource update: {0}", update);
            updateRoutes(update.virtualHosts, httpMaxStreamDurationNano,
                filterConfigs);
          }
        });
      }

      @Override
      public void onError(final Status error) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (RouteDiscoveryState.this != routeDiscoveryState || receivedConfig) {
              return;
            }
            listener.onError(Status.UNAVAILABLE.withCause(error.getCause()).withDescription(
                String.format("Unable to load RDS %s. xDS server returned: %s: %s",
                resourceName, error.getCode(), error.getDescription())));
          }
        });
      }

      @Override
      public void onResourceDoesNotExist(final String resourceName) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (RouteDiscoveryState.this != routeDiscoveryState) {
              return;
            }
            String error = "RDS resource does not exist: " + resourceName;
            logger.log(XdsLogLevel.INFO, error);
            cleanUpRoutes(error);
          }
        });
      }
    }
  }

  /**
   * VirtualHost-level configuration for request routing.
   */
  private static class RoutingConfig {
    private final long fallbackTimeoutNano;
    final List<Route> routes;
    // Null if HttpFilter is not supported.
    @Nullable final List<NamedFilterConfig> filterChain;
    final Map<String, FilterConfig> virtualHostOverrideConfig;

    private static RoutingConfig empty = new RoutingConfig(
        0, Collections.emptyList(), null, Collections.emptyMap());

    private RoutingConfig(
        long fallbackTimeoutNano, List<Route> routes, @Nullable List<NamedFilterConfig> filterChain,
        Map<String, FilterConfig> virtualHostOverrideConfig) {
      this.fallbackTimeoutNano = fallbackTimeoutNano;
      this.routes = routes;
      checkArgument(filterChain == null || !filterChain.isEmpty(), "filterChain is empty");
      this.filterChain = filterChain == null ? null : Collections.unmodifiableList(filterChain);
      this.virtualHostOverrideConfig = Collections.unmodifiableMap(virtualHostOverrideConfig);
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
