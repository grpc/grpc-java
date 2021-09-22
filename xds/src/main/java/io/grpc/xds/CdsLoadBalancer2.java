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
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.xds.CdsLoadBalancerProvider.CdsConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig.DiscoveryMechanism;
import io.grpc.xds.RingHashLoadBalancer.RingHashConfig;
import io.grpc.xds.XdsClient.CdsResourceWatcher;
import io.grpc.xds.XdsClient.CdsUpdate;
import io.grpc.xds.XdsClient.CdsUpdate.ClusterType;
import io.grpc.xds.XdsClient.CdsUpdate.LbPolicy;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
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
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    if (this.resolvedAddresses != null) {
      return;
    }
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    this.resolvedAddresses = resolvedAddresses;
    xdsClientPool = resolvedAddresses.getAttributes().get(InternalXdsAttributes.XDS_CLIENT_POOL);
    xdsClient = xdsClientPool.getObject();
    CdsConfig config = (CdsConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    logger.log(XdsLogLevel.INFO, "Config: {0}", config);
    cdsLbState = new CdsLbState(config.name);
    cdsLbState.start();
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
            DiscoveryMechanism instance;
            if (clusterState.result.clusterType() ==  ClusterType.EDS) {
              instance = DiscoveryMechanism.forEds(
                  clusterState.name, clusterState.result.edsServiceName(),
                  clusterState.result.lrsServerName(), clusterState.result.maxConcurrentRequests(),
                  clusterState.result.upstreamTlsContext());
            } else {  // logical DNS
              instance = DiscoveryMechanism.forLogicalDns(
                  clusterState.name, clusterState.result.dnsHostName(),
                  clusterState.result.lrsServerName(), clusterState.result.maxConcurrentRequests(),
                  clusterState.result.upstreamTlsContext());
            }
            instances.add(instance);
          } else {
            if (clusterState.childClusterStates != null) {
              queue.addAll(clusterState.childClusterStates.values());
            }
          }
        }
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
      LoadBalancerProvider lbProvider = null;
      Object lbConfig = null;
      if (root.result.lbPolicy() == LbPolicy.RING_HASH) {
        lbProvider = lbRegistry.getProvider("ring_hash");
        lbConfig = new RingHashConfig(root.result.minRingSize(), root.result.maxRingSize());
      }
      if (lbProvider == null) {
        lbProvider = lbRegistry.getProvider("round_robin");
        lbConfig = null;
      }
      ClusterResolverConfig config = new ClusterResolverConfig(
          Collections.unmodifiableList(instances), new PolicySelection(lbProvider, lbConfig));
      if (childLb == null) {
        childLb = lbRegistry.getProvider(CLUSTER_RESOLVER_POLICY_NAME).newLoadBalancer(helper);
      }
      childLb.handleResolvedAddresses(
          resolvedAddresses.toBuilder().setLoadBalancingPolicyConfig(config).build());
    }

    private void handleClusterDiscoveryError(Status error) {
      if (childLb != null) {
        childLb.handleNameResolutionError(error);
      } else {
        helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
      }
    }

    private final class ClusterState implements CdsResourceWatcher {
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
        xdsClient.watchCdsResource(name, this);
      }

      void shutdown() {
        shutdown = true;
        xdsClient.cancelCdsResourceWatch(name, this);
        if (childClusterStates != null) {  // recursively shut down all descendants
          for (ClusterState state : childClusterStates.values()) {
            state.shutdown();
          }
        }
      }

      @Override
      public void onError(final Status error) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (shutdown) {
              return;
            }
            // All watchers should receive the same error, so we only propagate it once.
            if (ClusterState.this == root) {
              handleClusterDiscoveryError(error);
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
                if (childClusterStates == null || !childClusterStates.containsKey(cluster)) {
                  ClusterState childState = new ClusterState(cluster);
                  childState.start();
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
