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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.InternalConfigSelector;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.EnvoyProtoData.ClusterWeight;
import io.grpc.xds.EnvoyProtoData.Route;
import io.grpc.xds.EnvoyProtoData.RouteAction;
import io.grpc.xds.EnvoyProtoData.VirtualHost;
import io.grpc.xds.ThreadSafeRandom.ThreadSafeRandomImpl;
import io.grpc.xds.XdsClient.LdsResourceWatcher;
import io.grpc.xds.XdsClient.LdsUpdate;
import io.grpc.xds.XdsClient.RdsResourceWatcher;
import io.grpc.xds.XdsClient.RdsUpdate;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsNameResolverProvider.CallCounterProvider;
import io.grpc.xds.XdsNameResolverProvider.XdsClientPoolFactory;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
  @VisibleForTesting
  static boolean enableTimeout =
      Boolean.parseBoolean(System.getenv("GRPC_XDS_EXPERIMENTAL_ENABLE_TIMEOUT"));

  private final XdsLogger logger;
  private final String authority;
  private final ServiceConfigParser serviceConfigParser;
  private final SynchronizationContext syncContext;
  private final XdsClientPoolFactory xdsClientPoolFactory;
  private final ThreadSafeRandom random;
  private final ConcurrentMap<String, AtomicInteger> clusterRefs = new ConcurrentHashMap<>();
  private final ConfigSelector configSelector = new ConfigSelector();

  private volatile RoutingConfig routingConfig = RoutingConfig.empty;
  private Listener2 listener;
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private CallCounterProvider callCounterProvider;
  private ResolveState resolveState;

  XdsNameResolver(String name, ServiceConfigParser serviceConfigParser,
      SynchronizationContext syncContext) {
    this(name, serviceConfigParser, syncContext, SharedXdsClientPoolProvider.getDefaultProvider(),
        ThreadSafeRandomImpl.instance);
  }

  @VisibleForTesting
  XdsNameResolver(String name, ServiceConfigParser serviceConfigParser,
      SynchronizationContext syncContext, XdsClientPoolFactory xdsClientPoolFactory,
      ThreadSafeRandom random) {
    authority = GrpcUtil.checkAuthority(checkNotNull(name, "name"));
    this.serviceConfigParser = checkNotNull(serviceConfigParser, "serviceConfigParser");
    this.syncContext = checkNotNull(syncContext, "syncContext");
    this.xdsClientPoolFactory = checkNotNull(xdsClientPoolFactory, "xdsClientPoolFactory");
    this.random = checkNotNull(random, "random");
    logger = XdsLogger.withLogId(InternalLogId.allocate("xds-resolver", name));
    logger.log(XdsLogLevel.INFO, "Created resolver for {0}", name);
  }

  @Override
  public String getServiceAuthority() {
    return authority;
  }

  @Override
  public void start(Listener2 listener) {
    this.listener = checkNotNull(listener, "listener");
    try {
      xdsClientPool = xdsClientPoolFactory.getXdsClientPool();
    } catch (Exception e) {
      listener.onError(
          Status.UNAVAILABLE.withDescription("Failed to initialize xDS").withCause(e));
      return;
    }
    xdsClient = xdsClientPool.getObject();
    callCounterProvider = SharedCallCounterMap.getInstance();
    resolveState = new ResolveState();
    resolveState.start();
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
  static Map<String, ?> generateServiceConfigWithMethodTimeoutConfig(long timeoutNano) {
    String timeout = timeoutNano / 1_000_000_000.0 + "s";
    Map<String, Object> methodConfig = new HashMap<>();
    methodConfig.put(
        "name", Collections.singletonList(Collections.emptyMap()));
    methodConfig.put("timeout", timeout);
    return Collections.singletonMap(
        "methodConfig", Collections.singletonList(Collections.unmodifiableMap(methodConfig)));
  }

  @VisibleForTesting
  static Map<String, ?> generateServiceConfigWithLoadBalancingConfig(Collection<String> clusters) {
    Map<String, Object> childPolicy = new HashMap<>();
    for (String cluster : clusters) {
      List<Map<String, Map<String, String>>> lbPolicy =
          Collections.singletonList(
              Collections.singletonMap(
                  "cds_experimental", Collections.singletonMap("cluster", cluster)));
      childPolicy.put(cluster, Collections.singletonMap("lbPolicy", lbPolicy));
    }
    return Collections.singletonMap("loadBalancingConfig",
        Collections.singletonList(
            Collections.singletonMap(
                "cluster_manager_experimental", Collections.singletonMap(
                    "childPolicy", Collections.unmodifiableMap(childPolicy)))));
  }

  @VisibleForTesting
  XdsClient getXdsClient() {
    return xdsClient;
  }

  private void updateResolutionResult() {
    Map<String, ?> rawServiceConfig =
        generateServiceConfigWithLoadBalancingConfig(clusterRefs.keySet());
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
  }

  @VisibleForTesting
  @Nullable
  static VirtualHost findVirtualHostForHostName(List<VirtualHost> virtualHosts, String hostName) {
    // Domain search order:
    //  1. Exact domain names: ``www.foo.com``.
    //  2. Suffix domain wildcards: ``*.foo.com`` or ``*-bar.foo.com``.
    //  3. Prefix domain wildcards: ``foo.*`` or ``foo-*``.
    //  4. Special wildcard ``*`` matching any domain.
    //
    //  The longest wildcards match first.
    //  Assuming only a single virtual host in the entire route configuration can match
    //  on ``*`` and a domain must be unique across all virtual hosts.
    int matchingLen = -1; // longest length of wildcard pattern that matches host name
    boolean exactMatchFound = false;  // true if a virtual host with exactly matched domain found
    VirtualHost targetVirtualHost = null;  // target VirtualHost with longest matched domain
    for (VirtualHost vHost : virtualHosts) {
      for (String domain : vHost.getDomains()) {
        boolean selected = false;
        if (matchHostName(hostName, domain)) { // matching
          if (!domain.contains("*")) { // exact matching
            exactMatchFound = true;
            targetVirtualHost = vHost;
            break;
          } else if (domain.length() > matchingLen) { // longer matching pattern
            selected = true;
          } else if (domain.length() == matchingLen && domain.startsWith("*")) { // suffix matching
            selected = true;
          }
        }
        if (selected) {
          matchingLen = domain.length();
          targetVirtualHost = vHost;
        }
      }
      if (exactMatchFound) {
        break;
      }
    }
    return targetVirtualHost;
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
      String cluster = null;
      Route selectedRoute = null;
      do {
        for (Route route : routingConfig.routes) {
          if (route.getRouteMatch().matches(
              "/" + args.getMethodDescriptor().getFullMethodName(), asciiHeaders)) {
            selectedRoute = route;
            break;
          }
        }
        if (selectedRoute == null) {
          return Result.forError(
              Status.UNAVAILABLE.withDescription("Could not find xDS route matching RPC"));
        }
        RouteAction action = selectedRoute.getRouteAction();
        if (action.getCluster() != null) {
          cluster = action.getCluster();
        } else if (action.getWeightedCluster() != null) {
          int totalWeight = 0;
          for (ClusterWeight weightedCluster : action.getWeightedCluster()) {
            totalWeight += weightedCluster.getWeight();
          }
          int select = random.nextInt(totalWeight);
          int accumulator = 0;
          for (ClusterWeight weightedCluster : action.getWeightedCluster()) {
            accumulator += weightedCluster.getWeight();
            if (select < accumulator) {
              cluster = weightedCluster.getName();
              break;
            }
          }
        }
      } while (!retainCluster(cluster));
      // TODO(chengyuanzhang): avoid service config generation and parsing for each call.
      Map<String, ?> rawServiceConfig = Collections.emptyMap();
      if (enableTimeout) {
        Long timeoutNano = selectedRoute.getRouteAction().getTimeoutNano();
        if (timeoutNano == null) {
          timeoutNano = routingConfig.fallbackTimeoutNano;
        }
        if (timeoutNano > 0) {
          rawServiceConfig = generateServiceConfigWithMethodTimeoutConfig(timeoutNano);
        }
      }
      ConfigOrError parsedServiceConfig = serviceConfigParser.parseServiceConfig(rawServiceConfig);
      Object config = parsedServiceConfig.getConfig();
      if (config == null) {
        releaseCluster(cluster);
        return Result.forError(
            parsedServiceConfig.getError().augmentDescription(
                "Failed to parse service config (method config)"));
      }
      final String finalCluster = cluster;

      class ClusterSelectionInterceptor implements ClientInterceptor {
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
          CallOptions callOptionsForCluster =
              callOptions.withOption(CLUSTER_SELECTION_KEY, finalCluster);
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
              .setInterceptor(new ClusterSelectionInterceptor())
              .build();
    }

    private boolean retainCluster(String cluster) {
      AtomicInteger refCount = clusterRefs.get(cluster);
      if (refCount == null) {
        return false;
      }
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
      int count = clusterRefs.get(cluster).decrementAndGet();
      if (count == 0) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (clusterRefs.get(cluster).get() == 0) {
              clusterRefs.remove(cluster);
              updateResolutionResult();
            }
          }
        });
      }
    }
  }

  private class ResolveState implements LdsResourceWatcher {
    private final ConfigOrError emptyServiceConfig =
        serviceConfigParser.parseServiceConfig(Collections.<String, Object>emptyMap());
    private final ResolutionResult emptyResult =
        ResolutionResult.newBuilder()
            .setServiceConfig(emptyServiceConfig)
            // let channel take action for no config selector
            .build();
    private boolean stopped;
    private Set<String> existingClusters;
    @Nullable
    private String rdsResource;
    @Nullable
    private RdsResourceWatcher rdsWatcher;
    private long httpMaxStreamDurationNano;

    @Override
    public void onChanged(final LdsUpdate update) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped) {
            return;
          }
          logger.log(XdsLogLevel.INFO, "Receive LDS resource update: {0}", update);
          httpMaxStreamDurationNano = update.httpMaxStreamDurationNano;
          List<VirtualHost> virtualHosts = update.virtualHosts;
          String rdsName = update.rdsName;
          if (rdsName != null && rdsName.equals(rdsResource)) {
            return;
          }
          cleanUpRdsWatcher();
          if (virtualHosts != null) {
            updateRoutes(virtualHosts);
          } else {
            rdsResource = rdsName;
            rdsWatcher = new RdsResourceWatcherImpl();
            logger.log(XdsLogLevel.INFO, "Start watching RDS resource {0}", rdsResource);
            xdsClient.watchRdsResource(rdsResource, rdsWatcher);
          }
        }
      });
    }

    @Override
    public void onError(final Status error) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped) {
            return;
          }
          logger.log(
              XdsLogLevel.WARNING,
              "Received error from xDS client {0}: {1}", xdsClient, error.getDescription());
          listener.onError(error);
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
          logger.log(XdsLogLevel.INFO, "LDS resource {0} unavailable", resourceName);
          cleanUpRdsWatcher();
          listener.onResult(emptyResult);
        }
      });
    }

    private void start() {
      logger.log(XdsLogLevel.INFO, "Start watching LDS resource {0}", authority);
      xdsClient.watchLdsResource(authority, this);
    }

    private void stop() {
      logger.log(XdsLogLevel.INFO, "Stop watching LDS resource {0}", authority);
      stopped = true;
      cleanUpRdsWatcher();
      xdsClient.cancelLdsResourceWatch(authority, this);
    }

    private void updateRoutes(List<VirtualHost> virtualHosts) {
      VirtualHost virtualHost = findVirtualHostForHostName(virtualHosts, authority);
      if (virtualHost == null) {
        logger.log(XdsLogLevel.WARNING,
            "Failed to find virtual host matching hostname {0}", authority);
        listener.onResult(emptyResult);
        return;
      }
      List<Route> routes = virtualHost.getRoutes();
      Set<String> clusters = new HashSet<>();
      for (Route route : routes) {
        RouteAction action = route.getRouteAction();
        if (action.getCluster() != null) {
          clusters.add(action.getCluster());
        } else if (action.getWeightedCluster() != null) {
          for (ClusterWeight weighedCluster : action.getWeightedCluster()) {
            clusters.add(weighedCluster.getName());
          }
        }
      }
      Set<String> addedClusters =
          existingClusters == null ? clusters : Sets.difference(clusters, existingClusters);
      Set<String> deletedClusters =
          existingClusters == null
              ? Collections.<String>emptySet() : Sets.difference(existingClusters, clusters);
      existingClusters = clusters;
      boolean shouldUpdateResult = false;
      for (String cluster : addedClusters) {
        if (clusterRefs.containsKey(cluster)) {
          clusterRefs.get(cluster).incrementAndGet();
        } else {
          clusterRefs.put(cluster, new AtomicInteger(1));
          shouldUpdateResult = true;
        }
      }
      // Update service config to include newly added clusters.
      if (shouldUpdateResult) {
        updateResolutionResult();
      }
      // Make newly added clusters selectable by config selector and deleted clusters no longer
      // selectable.
      routingConfig = new RoutingConfig(httpMaxStreamDurationNano, routes);
      shouldUpdateResult = false;
      for (String cluster : deletedClusters) {
        int count = clusterRefs.get(cluster).decrementAndGet();
        if (count == 0) {
          clusterRefs.remove(cluster);
          shouldUpdateResult = true;
        }
      }
      if (shouldUpdateResult) {
        updateResolutionResult();
      }
    }

    private void cleanUpRdsWatcher() {
      if (rdsWatcher != null) {
        logger.log(XdsLogLevel.INFO, "Stop watching RDS resource {0}", rdsResource);
        xdsClient.cancelRdsResourceWatch(rdsResource, rdsWatcher);
        rdsResource = null;
        rdsWatcher = null;
      }
    }

    private class RdsResourceWatcherImpl implements RdsResourceWatcher {

      @Override
      public void onChanged(final RdsUpdate update) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (RdsResourceWatcherImpl.this != rdsWatcher) {
              return;
            }
            updateRoutes(update.virtualHosts);
          }
        });
      }

      @Override
      public void onError(final Status error) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (RdsResourceWatcherImpl.this != rdsWatcher) {
              return;
            }
            logger.log(
                XdsLogLevel.WARNING,
                "Received error from xDS client {0}: {1}", xdsClient, error.getDescription());
            listener.onError(error);
          }
        });
      }

      @Override
      public void onResourceDoesNotExist(final String resourceName) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (RdsResourceWatcherImpl.this != rdsWatcher) {
              return;
            }
            logger.log(XdsLogLevel.INFO, "RDS resource {0} unavailable", resourceName);
            listener.onResult(emptyResult);
          }
        });
      }
    }
  }

  /**
   * Grouping of the list of usable routes and their corresponding fallback timeout value.
   */
  private static class RoutingConfig {
    private long fallbackTimeoutNano;
    private List<Route> routes;

    private static RoutingConfig empty = new RoutingConfig(0L, Collections.<Route>emptyList());

    private RoutingConfig(long fallbackTimeoutNano, List<Route> routes) {
      this.fallbackTimeoutNano = fallbackTimeoutNano;
      this.routes = routes;
    }
  }
}
