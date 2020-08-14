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
import io.grpc.xds.XdsClient.XdsClientPoolFactory;
import io.grpc.xds.XdsLogger.XdsLogLevel;
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
import javax.annotation.Nullable;

/**
 * A {@link NameResolver} for resolving gRPC target names with "xds:" scheme.
 *
 * <p>Resolving a gRPC target involves contacting the control plane management server via xDS
 * protocol to retrieve service information and produce a service config to the caller.
 *
 * @see XdsNameResolverProvider
 */
final class XdsNameResolver2 extends NameResolver {

  static final CallOptions.Key<String> CLUSTER_SELECTION_KEY =
      CallOptions.Key.create("io.grpc.xds.CLUSTER_SELECTION_KEY");

  private final XdsLogger logger;
  private final String authority;
  private final ServiceConfigParser serviceConfigParser;
  private final SynchronizationContext syncContext;
  private final Bootstrapper bootstrapper;
  private final XdsClientPoolFactory xdsClientPoolFactory;
  private final ThreadSafeRandom random;
  private final ConcurrentMap<String, AtomicInteger> clusterRefs = new ConcurrentHashMap<>();

  private volatile List<Route> routes = Collections.emptyList();
  private Listener2 listener;
  @Nullable
  private ObjectPool<XdsClient> xdsClientPool;
  @Nullable
  private XdsClient xdsClient;

  XdsNameResolver2(String name,
      ServiceConfigParser serviceConfigParser,
      SynchronizationContext syncContext,
      XdsClientPoolFactory xdsClientPoolFactory) {
    this(name, serviceConfigParser, syncContext, Bootstrapper.getInstance(),
        xdsClientPoolFactory, ThreadSafeRandomImpl.instance);
  }

  XdsNameResolver2(
      String name,
      ServiceConfigParser serviceConfigParser,
      SynchronizationContext syncContext,
      Bootstrapper bootstrapper,
      XdsClientPoolFactory xdsClientPoolFactory,
      ThreadSafeRandom random) {
    authority = GrpcUtil.checkAuthority(checkNotNull(name, "name"));
    this.serviceConfigParser = checkNotNull(serviceConfigParser, "serviceConfigParser");
    this.syncContext = checkNotNull(syncContext, "syncContext");
    this.bootstrapper = checkNotNull(bootstrapper, "bootstrapper");
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
    BootstrapInfo bootstrapInfo;
    try {
      bootstrapInfo = bootstrapper.readBootstrap();
    } catch (Exception e) {
      listener.onError(Status.UNAVAILABLE.withDescription("Failed to bootstrap").withCause(e));
      return;
    }
    this.listener = checkNotNull(listener, "listener");
    xdsClientPool = xdsClientPoolFactory.newXdsClientObjectPool(bootstrapInfo);
    xdsClient = xdsClientPool.getObject();
    xdsClient.watchConfigData(authority, new ConfigWatcherImpl());
  }

  @VisibleForTesting
  final class ConfigSelector extends InternalConfigSelector {
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
          return Result.forError(Status.UNAVAILABLE.withDescription("Failed to route the RPC"));
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
        if (cluster == null) {  // should not happen if routing rules are configured correctly
          return Result.forError(
              Status.UNAVAILABLE.withDescription("Failed to route the RPC with selected action"));
        }
      } while (!retainCluster(cluster));
      final String finalCluster = cluster;
      class SelectionCompleted implements Runnable {
        @Override
        public void run() {
          releaseCluster(finalCluster);
        }
      }

      Map<String, ?> rawServiceConfig =
          generateServiceConfigWithMethodConfig(
              args.getMethodDescriptor().getFullMethodName(),
              selectedRoute.getRouteAction().getTimeoutNano());
      if (logger.isLoggable(XdsLogLevel.INFO)) {
        logger.log(XdsLogLevel.INFO,
            "Generated service config (method config):\n{0}", new Gson().toJson(rawServiceConfig));
      }
      ConfigOrError parsedServiceConfig = serviceConfigParser.parseServiceConfig(rawServiceConfig);
      Object config = parsedServiceConfig.getConfig();
      if (config == null) {
        throw new AssertionError(
            "Bug: invalid config", parsedServiceConfig.getError().asException());
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

    private void releaseCluster(String cluster) {
      int count = clusterRefs.get(cluster).decrementAndGet();
      if (count == 0) {
        clusterRefs.remove(cluster);
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            updateResolutionResult();
          }
        });
      }
    }
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
            .set(InternalConfigSelector.KEY, new ConfigSelector())
            .build();
    ResolutionResult result =
        ResolutionResult.newBuilder()
            .setAttributes(attrs)
            .setServiceConfig(parsedServiceConfig)
            .build();
    listener.onResult(result);
  }

  @SuppressWarnings("ModifyCollectionInEnhancedForLoop")  // ok for concurrent map
  private class ConfigWatcherImpl implements ConfigWatcher {
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
      boolean receivedNewCluster = false;
      for (String newCluster : clusters) {
        if (clusterRefs.putIfAbsent(newCluster, new AtomicInteger(1)) == null) {
          receivedNewCluster = true;
        }
      }
      // Update service config to include newly added clusters.
      if (receivedNewCluster) {
        updateResolutionResult();
      }
      // Make newly added clusters selectable by config selector.
      routes = update.getRoutes();
      // Drops reference for deleted clusters, update service config to remove deleted clusters
      // not in use.
      boolean shouldUpdateResult = false;
      for (Map.Entry<String, AtomicInteger> entry : clusterRefs.entrySet()) {
        if (!clusters.contains(entry.getKey())) {
          int count = entry.getValue().decrementAndGet();
          if (count == 0) {
            clusterRefs.remove(entry.getKey());
            shouldUpdateResult = true;
          }
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

  @VisibleForTesting
  static Map<String, ?> generateServiceConfigWithMethodConfig(
      String fullMethodName, long timeoutNano) {
    int index = fullMethodName.lastIndexOf('/');
    String serviceName = fullMethodName.substring(0, index);
    String methodName = fullMethodName.substring(index + 1);
    String timeout = timeoutNano / 1_000_000_000.0 + "s";
    Map<String, String> serviceMethod = new HashMap<>();
    serviceMethod.put("service", serviceName);
    serviceMethod.put("method", methodName);
    Map<String, Object> methodConfig = new HashMap<>();
    methodConfig.put(
        "name", Collections.singletonList(Collections.unmodifiableMap(serviceMethod)));
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
  @Nullable
  XdsClient getXdsClient() {
    return xdsClient;
  }

  @Override
  public void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutdown");
    if (xdsClient != null) {
      xdsClient = xdsClientPool.returnObject(xdsClient);
    }
  }
}
