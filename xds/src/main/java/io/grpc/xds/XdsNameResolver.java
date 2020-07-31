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
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.InternalConfigSelector;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.Metadata;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.JsonParser;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.EnvoyProtoData.ClusterWeight;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.EnvoyProtoData.Route;
import io.grpc.xds.EnvoyProtoData.RouteAction;
import io.grpc.xds.XdsClient.ConfigUpdate;
import io.grpc.xds.XdsClient.ConfigWatcher;
import io.grpc.xds.XdsClient.RefCountedXdsClientObjectPool;
import io.grpc.xds.XdsClient.XdsChannelFactory;
import io.grpc.xds.XdsClient.XdsClientFactory;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
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

  private static final CallOptions.Key<String> CLUSTER_SELECTION_KEY =
      CallOptions.Key.create("io.grpc.xds.CLUSTER_SELECTION_KEY");

  private final XdsLogger logger;
  private final String authority;
  private final XdsChannelFactory channelFactory;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  private final ServiceConfigParser serviceConfigParser;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final Supplier<Stopwatch> stopwatchSupplier;
  private final Bootstrapper bootstrapper;
  private final Map<String, AtomicInteger> clusterRefs = new ConcurrentHashMap<>();

  private volatile List<Route> routes = Collections.emptyList();
  private Listener2 listener;
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
    this.listener = checkNotNull(listener, "listener");

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
    xdsClient.watchConfigData(authority, new ConfigWatcherImpl());
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
          int select = ThreadLocalRandom.current().nextInt(totalWeight);
          int accumulator = 0;
          for (ClusterWeight weightedCluster : action.getWeightedCluster()) {
            accumulator += weightedCluster.getWeight();
            if (select < accumulator) {
              cluster = weightedCluster.getName();
              break;
            }
          }
        }
        if (cluster == null) {
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
      
      return
          Result.newBuilder()
              .setCallOptions(args.getCallOptions().withOption(CLUSTER_SELECTION_KEY, cluster))
              // TODO (chengyuanzhang): generate config for method
              .setConfig(
                  serviceConfigParser.parseServiceConfig(Collections.<String, Object>emptyMap()))
              .setCommittedCallback(new SelectionCompleted())
              .build();
    }

    private boolean retainCluster(String cluster) {
      AtomicInteger refCount = clusterRefs.get(cluster);
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
        updateResolutionResult();
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void updateResolutionResult() {
    String serviceConfigJson = generateServiceConfigJson(clusterRefs.keySet());
    logger.log(XdsLogLevel.INFO, "Generated service config:\n{0}", serviceConfigJson);
    Map<String, ?> serviceConfig;
    try {
      serviceConfig = (Map<String, ?>) JsonParser.parse(serviceConfigJson);
    } catch (IOException e) {
      throw new AssertionError("Malformed lb config: " + e);
    }
    Attributes attrs =
        Attributes.newBuilder()
            .set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
            .set(InternalConfigSelector.KEY, new ConfigSelector())
            .build();
    ConfigOrError parsedServiceConfig = serviceConfigParser.parseServiceConfig(serviceConfig);
    ResolutionResult result =
        ResolutionResult.newBuilder()
            .setAttributes(attrs)
            .setServiceConfig(parsedServiceConfig)
            .build();
    listener.onResult(result);
  }

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
      for (String newCluster : clusters) {
        if (!clusterRefs.containsKey(newCluster)) {
          clusterRefs.put(newCluster, new AtomicInteger(1));
        }
      }
      // Update service config to include newly added clusters.
      updateResolutionResult();
      // Make newly added clusters selectable by config selector.
      routes = update.getRoutes();
      // Drops reference for deleted clusters, update service config to remove deleted clusters
      // not in use.
      boolean shouldUpdateResult = false;
      for (Map.Entry<String, AtomicInteger> entry : clusterRefs.entrySet()) {
        if (!clusters.contains(entry.getKey())) {
          int count = entry.getValue().decrementAndGet();
          if (count == 0) {
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
  static String generateServiceConfigJson(Collection<String> clusters) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append("  \"loadBalancingConfig\": [{\n");
    sb.append("    \"cluster_manager_experimental\": {\n");
    sb.append("      \"childPolicy\": {\n");
    int i = 0;
    for (String cluster : clusters) {
      sb.append("        \"" + cluster + "\": {\n");
      sb.append("          \"lbPolicy\": [{\n");
      sb.append("            \"cds_experimental\": {\n");
      sb.append("              \"cluster\": \"" + cluster + "\"\n");
      sb.append("            }\n");
      sb.append("          }]\n");
      sb.append("        }");
      if (i < clusters.size() - 1) {
        sb.append(",");
      }
      sb.append("\n");
      i++;
    }
    sb.append("      }\n");
    sb.append("    }\n");
    sb.append("  }]\n");
    sb.append("}");
    return sb.toString();
  }

  @Override
  public void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutdown");
    if (xdsClient != null) {
      xdsClient = xdsClientPool.returnObject(xdsClient);
    }
  }
}
