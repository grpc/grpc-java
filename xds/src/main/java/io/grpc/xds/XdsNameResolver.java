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
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.InternalConfigSelector;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.Metadata;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.EnvoyProtoData.ClusterWeight;
import io.grpc.xds.EnvoyProtoData.Route;
import io.grpc.xds.EnvoyProtoData.RouteAction;
import io.grpc.xds.ThreadSafeRandom.ThreadSafeRandomImpl;
import io.grpc.xds.XdsClient.ConfigUpdate;
import io.grpc.xds.XdsClient.ConfigWatcher;
import io.grpc.xds.XdsClient.XdsChannel;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsNameResolverProvider.XdsClientPoolFactory;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

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
  private final Bootstrapper bootstrapper;
  private final XdsChannelFactory channelFactory;
  private final XdsClientPoolFactory xdsClientPoolFactory;
  private final ThreadSafeRandom random;
  private final ConcurrentMap<String, AtomicInteger> clusterRefs = new ConcurrentHashMap<>();
  private final ConfigSelector configSelector = new ConfigSelector();

  private volatile List<Route> routes = Collections.emptyList();
  private Listener2 listener;
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;

  XdsNameResolver(String name,
      ServiceConfigParser serviceConfigParser,
      SynchronizationContext syncContext,
      XdsClientPoolFactory xdsClientPoolFactory) {
    this(name, serviceConfigParser, syncContext, Bootstrapper.getInstance(),
        XdsChannelFactory.getInstance(), xdsClientPoolFactory, ThreadSafeRandomImpl.instance);
  }

  @VisibleForTesting
  XdsNameResolver(
      String name,
      ServiceConfigParser serviceConfigParser,
      SynchronizationContext syncContext,
      Bootstrapper bootstrapper,
      XdsChannelFactory channelFactory,
      XdsClientPoolFactory xdsClientPoolFactory,
      ThreadSafeRandom random) {
    authority = GrpcUtil.checkAuthority(checkNotNull(name, "name"));
    this.serviceConfigParser = checkNotNull(serviceConfigParser, "serviceConfigParser");
    this.syncContext = checkNotNull(syncContext, "syncContext");
    this.bootstrapper = checkNotNull(bootstrapper, "bootstrapper");
    this.channelFactory = checkNotNull(channelFactory, "channelFactory");
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
    BootstrapInfo bootstrapInfo;
    XdsChannel channel;
    try {
      bootstrapInfo = bootstrapper.readBootstrap();
      channel = channelFactory.createChannel(bootstrapInfo.getServers());
    } catch (Exception e) {
      listener.onError(
          Status.UNAVAILABLE.withDescription("Failed to initialize xDS").withCause(e));
      return;
    }
    xdsClientPool = xdsClientPoolFactory.newXdsClientObjectPool(bootstrapInfo, channel);
    xdsClient = xdsClientPool.getObject();
    xdsClient.watchConfigData(authority, new ConfigWatcherImpl());
  }

  @Override
  public void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutdown");
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
            .set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
            .set(InternalConfigSelector.KEY, configSelector)
            .build();
    ResolutionResult result =
        ResolutionResult.newBuilder()
            .setAttributes(attrs)
            .setServiceConfig(parsedServiceConfig)
            .build();
    listener.onResult(result);
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
        for (Route route : routes) {
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
        rawServiceConfig = generateServiceConfigWithMethodTimeoutConfig(
            selectedRoute.getRouteAction().getTimeoutNano());
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
      class SelectionCompleted implements Runnable {
        @Override
        public void run() {
          releaseCluster(finalCluster);
        }
      }

      return
          Result.newBuilder()
              .setCallOptions(args.getCallOptions().withOption(CLUSTER_SELECTION_KEY, cluster))
              .setConfig(config)
              .setCommittedCallback(new SelectionCompleted())
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

  // https://github.com/google/error-prone/issues/1767
  @SuppressWarnings("ModifyCollectionInEnhancedForLoop")
  private class ConfigWatcherImpl implements ConfigWatcher {
    private Set<String> existingClusters;

    @Override
    public void onConfigChanged(ConfigUpdate update) {
      Set<String> clusters = new HashSet<>();
      for (Route route : update.getRoutes()) {
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
      routes = update.getRoutes();
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

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      logger.log(XdsLogLevel.INFO, "Resource {0} is unavailable", resourceName);
      ConfigOrError parsedServiceConfig =
          serviceConfigParser.parseServiceConfig(Collections.<String, Object>emptyMap());
      ResolutionResult result =
          ResolutionResult.newBuilder()
              .setServiceConfig(parsedServiceConfig)
              // let channel take action for no config selector
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
}
