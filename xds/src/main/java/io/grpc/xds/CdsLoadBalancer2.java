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

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.xds.XdsLbPolicies.CLUSTER_RESOLVER_POLICY_NAME;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.xds.CdsLoadBalancerProvider.CdsConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig.DiscoveryMechanism;
import io.grpc.xds.XdsClient.ResourceWatcher;
import io.grpc.xds.XdsClusterResource.CdsUpdate;
import io.grpc.xds.XdsClusterResource.CdsUpdate.ClusterType;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Load balancer for cds_experimental LB policy. One instance per top-level cluster.
 * The top-level cluster may be a plain EDS/logical-DNS cluster or an aggregate cluster formed
 * by a group of sub-clusters in a tree hierarchy.
 */
final class CdsLoadBalancer2 extends LoadBalancer {
  private final XdsLogger logger;
  private final Helper helper;
  private final SynchronizationContext syncContext;
  private final LoadBalancerRegistry lbRegistry;
  // Following fields are effectively final.
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private CdsLbState cdsLbState;
  private ResolvedAddresses resolvedAddresses;

  CdsLoadBalancer2(Helper helper) {
    this(helper, LoadBalancerRegistry.getDefaultRegistry());
  }

  @VisibleForTesting
  CdsLoadBalancer2(Helper helper, LoadBalancerRegistry lbRegistry) {
    this.helper = checkNotNull(helper, "helper");
    this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    this.lbRegistry = checkNotNull(lbRegistry, "lbRegistry");
    logger = XdsLogger.withLogId(InternalLogId.allocate("cds-lb", helper.getAuthority()));
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public boolean acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    if (this.resolvedAddresses != null) {
      return true;
    }
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    this.resolvedAddresses = resolvedAddresses;
    xdsClientPool = resolvedAddresses.getAttributes().get(InternalXdsAttributes.XDS_CLIENT_POOL);
    xdsClient = xdsClientPool.getObject();
    CdsConfig config = (CdsConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    logger.log(XdsLogLevel.INFO, "Config: {0}", config);
    cdsLbState = new CdsLbState(config.name);
    cdsLbState.start();
    return true;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
    if (cdsLbState != null && cdsLbState.childLb != null) {
      cdsLbState.childLb.handleNameResolutionError(error);
    } else {
      helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
    }
  }

  @Override
  public void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutdown");
    if (cdsLbState != null) {
      cdsLbState.shutdown();
    }
    if (xdsClientPool != null) {
      xdsClientPool.returnObject(xdsClient);
    }
  }

  /**
   * The state of a CDS working session of {@link CdsLoadBalancer2}. Created and started when
   * receiving the CDS LB policy config with the top-level cluster name.
   */
  private final class CdsLbState {

    private final ClusterState root;
    private final Map<String, ClusterState> clusterStates = new ConcurrentHashMap<>();
    private LoadBalancer childLb;

    private CdsLbState(String rootCluster) {
      root = new ClusterState(rootCluster);
    }

    private void start() {
      root.start();
    }

    private void shutdown() {
      root.shutdown();
      if (childLb != null) {
        childLb.shutdown();
      }
    }

    private void handleClusterDiscovered() {
      List<DiscoveryMechanism> instances = new ArrayList<>();

      // Used for loop detection to break the infinite recursion that loops would cause
      Map<ClusterState, List<ClusterState>> parentClusters = new HashMap<>();
      Status loopStatus = null;

      // Level-order traversal.
      // Collect configurations for all non-aggregate (leaf) clusters.
      Queue<ClusterState> queue = new ArrayDeque<>();
      queue.add(root);
      while (!queue.isEmpty()) {
        int size = queue.size();
        for (int i = 0; i < size; i++) {
          ClusterState clusterState = queue.remove();
          if (!clusterState.discovered) {
            return;  // do not proceed until all clusters discovered
          }
          if (clusterState.result == null) {  // resource revoked or not exists
            continue;
          }
          if (clusterState.isLeaf) {
            if (instances.stream().map(inst -> inst.cluster).noneMatch(clusterState.name::equals)) {
              DiscoveryMechanism instance;
              if (clusterState.result.clusterType() == ClusterType.EDS) {
                instance = DiscoveryMechanism.forEds(
                    clusterState.name, clusterState.result.edsServiceName(),
                    clusterState.result.lrsServerInfo(),
                    clusterState.result.maxConcurrentRequests(),
                    clusterState.result.upstreamTlsContext(),
                    clusterState.result.outlierDetection());
              } else {  // logical DNS
                instance = DiscoveryMechanism.forLogicalDns(
                    clusterState.name, clusterState.result.dnsHostName(),
                    clusterState.result.lrsServerInfo(),
                    clusterState.result.maxConcurrentRequests(),
                    clusterState.result.upstreamTlsContext());
              }
              instances.add(instance);
            }
          } else {
            if (clusterState.childClusterStates == null) {
              continue;
            }
            // Do loop detection and break recursion if detected
            List<String> namesCausingLoops = identifyLoops(clusterState, parentClusters);
            if (namesCausingLoops.isEmpty()) {
              queue.addAll(clusterState.childClusterStates.values());
            } else {
              // Do cleanup
              if (childLb != null) {
                childLb.shutdown();
                childLb = null;
              }
              if (loopStatus != null) {
                logger.log(XdsLogLevel.WARNING,
                    "Multiple loops in CDS config.  Old msg:  " + loopStatus.getDescription());
              }
              loopStatus = Status.UNAVAILABLE.withDescription(String.format(
                  "CDS error: circular aggregate clusters directly under %s for "
                      + "root cluster %s, named %s",
                  clusterState.name, root.name, namesCausingLoops));
            }
          }
        }
      }

      if (loopStatus != null) {
        helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(loopStatus));
        return;
      }

      if (instances.isEmpty()) {  // none of non-aggregate clusters exists
        if (childLb != null) {
          childLb.shutdown();
          childLb = null;
        }
        Status unavailable =
            Status.UNAVAILABLE.withDescription("CDS error: found 0 leaf (logical DNS or EDS) "
                + "clusters for root cluster " + root.name);
        helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(unavailable));
        return;
      }

      // The LB policy config is provided in service_config.proto/JSON format. It is unwrapped
      // to determine the name of the policy in the load balancer registry.
      LbConfig unwrappedLbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(
          root.result.lbPolicyConfig());
      LoadBalancerProvider lbProvider = lbRegistry.getProvider(unwrappedLbConfig.getPolicyName());
      if (lbProvider == null) {
        throw NameResolver.ConfigOrError.fromError(Status.UNAVAILABLE.withDescription(
                "No provider available for LB: " + unwrappedLbConfig.getPolicyName())).getError()
            .asRuntimeException();
      }
      NameResolver.ConfigOrError configOrError = lbProvider.parseLoadBalancingPolicyConfig(
          unwrappedLbConfig.getRawConfigValue());
      if (configOrError.getError() != null) {
        throw configOrError.getError().augmentDescription("Unable to parse the LB config")
            .asRuntimeException();
      }

      ClusterResolverConfig config = new ClusterResolverConfig(
          Collections.unmodifiableList(instances),
          new PolicySelection(lbProvider, configOrError.getConfig()));
      if (childLb == null) {
        childLb = lbRegistry.getProvider(CLUSTER_RESOLVER_POLICY_NAME).newLoadBalancer(helper);
      }
      childLb.handleResolvedAddresses(
          resolvedAddresses.toBuilder().setLoadBalancingPolicyConfig(config).build());
    }

    /**
     * Returns children that would cause loops and builds up the parentClusters map.
     **/

    private List<String> identifyLoops(ClusterState clusterState,
        Map<ClusterState, List<ClusterState>> parentClusters) {
      Set<String> ancestors = new HashSet<>();
      ancestors.add(clusterState.name);
      addAncestors(ancestors, clusterState, parentClusters);

      List<String> namesCausingLoops = new ArrayList<>();
      for (ClusterState state : clusterState.childClusterStates.values()) {
        if (ancestors.contains(state.name)) {
          namesCausingLoops.add(state.name);
        }
      }

      // Update parent map with entries from remaining children to clusterState
      clusterState.childClusterStates.values().stream()
          .filter(child -> !namesCausingLoops.contains(child.name))
          .forEach(
              child -> parentClusters.computeIfAbsent(child, k -> new ArrayList<>())
                  .add(clusterState));

      return namesCausingLoops;
    }

    /** Recursively add all parents to the ancestors list. **/
    private void addAncestors(Set<String> ancestors, ClusterState clusterState,
        Map<ClusterState, List<ClusterState>> parentClusters) {
      List<ClusterState> directParents = parentClusters.get(clusterState);
      if (directParents != null) {
        directParents.stream().map(c -> c.name).forEach(ancestors::add);
        directParents.forEach(p -> addAncestors(ancestors, p, parentClusters));
      }
    }

    private void handleClusterDiscoveryError(Status error) {
      if (childLb != null) {
        childLb.handleNameResolutionError(error);
      } else {
        helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
      }
    }

    private final class ClusterState implements ResourceWatcher<CdsUpdate> {
      private final String name;
      @Nullable
      private Map<String, ClusterState> childClusterStates;
      @Nullable
      private CdsUpdate result;
      // Following fields are effectively final.
      private boolean isLeaf;
      private boolean discovered;
      private boolean shutdown;

      private ClusterState(String name) {
        this.name = name;
      }

      private void start() {
        shutdown = false;
        xdsClient.watchXdsResource(XdsClusterResource.getInstance(), name, this);
      }

      void shutdown() {
        shutdown = true;
        xdsClient.cancelXdsResourceWatch(XdsClusterResource.getInstance(), name, this);
        if (childClusterStates != null) {
          // recursively shut down all descendants
          childClusterStates.values().stream()
              .filter(state -> !state.shutdown)
              .forEach(ClusterState::shutdown);
        }
      }

      @Override
      public void onError(Status error) {
        Status status = Status.UNAVAILABLE
            .withDescription(
                String.format("Unable to load CDS %s. xDS server returned: %s: %s",
                  name, error.getCode(), error.getDescription()))
            .withCause(error.getCause());
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (shutdown) {
              return;
            }
            // All watchers should receive the same error, so we only propagate it once.
            if (ClusterState.this == root) {
              handleClusterDiscoveryError(status);
            }
          }
        });
      }

      @Override
      public void onResourceDoesNotExist(String resourceName) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (shutdown) {
              return;
            }
            discovered = true;
            result = null;
            if (childClusterStates != null) {
              for (ClusterState state : childClusterStates.values()) {
                state.shutdown();
              }
              childClusterStates = null;
            }
            handleClusterDiscovered();
          }
        });
      }

      @Override
      public void onChanged(final CdsUpdate update) {
        class ClusterDiscovered implements Runnable {
          @Override
          public void run() {
            if (shutdown) {
              return;
            }

            logger.log(XdsLogLevel.DEBUG, "Received cluster update {0}", update);
            discovered = true;
            result = update;
            if (update.clusterType() == ClusterType.AGGREGATE) {
              isLeaf = false;
              logger.log(XdsLogLevel.INFO, "Aggregate cluster {0}, underlying clusters: {1}",
                  update.clusterName(), update.prioritizedClusterNames());
              Map<String, ClusterState> newChildStates = new LinkedHashMap<>();
              for (String cluster : update.prioritizedClusterNames()) {
                if (newChildStates.containsKey(cluster)) {
                  logger.log(XdsLogLevel.WARNING,
                      String.format("duplicate cluster name %s in aggregate %s is being ignored",
                          cluster, update.clusterName()));
                  continue;
                }
                if (childClusterStates == null || !childClusterStates.containsKey(cluster)) {
                  ClusterState childState;
                  if (clusterStates.containsKey(cluster)) {
                    childState = clusterStates.get(cluster);
                    if (childState.shutdown) {
                      childState.start();
                    }
                  } else {
                    childState = new ClusterState(cluster);
                    clusterStates.put(cluster, childState);
                    childState.start();
                  }
                  newChildStates.put(cluster, childState);
                } else {
                  newChildStates.put(cluster, childClusterStates.remove(cluster));
                }
              }
              if (childClusterStates != null) {  // stop subscribing to revoked child clusters
                for (ClusterState watcher : childClusterStates.values()) {
                  watcher.shutdown();
                }
              }
              childClusterStates = newChildStates;
            } else if (update.clusterType() == ClusterType.EDS) {
              isLeaf = true;
              logger.log(XdsLogLevel.INFO, "EDS cluster {0}, edsServiceName: {1}",
                  update.clusterName(), update.edsServiceName());
            } else {  // logical DNS
              isLeaf = true;
              logger.log(XdsLogLevel.INFO, "Logical DNS cluster {0}", update.clusterName());
            }
            handleClusterDiscovered();
          }
        }

        syncContext.execute(new ClusterDiscovered());
      }
    }
  }
}
