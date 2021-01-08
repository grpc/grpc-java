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
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.xds.XdsLbPolicies.LRS_POLICY_NAME;
import static io.grpc.xds.XdsLbPolicies.PRIORITY_POLICY_NAME;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.ClusterImplLoadBalancerProvider.ClusterImplConfig;
import io.grpc.xds.EdsLoadBalancerProvider.EdsConfig;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.LbEndpoint;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.LrsLoadBalancerProvider.LrsConfig;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig.PriorityChildConfig;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedPolicySelection;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedTargetConfig;
import io.grpc.xds.XdsClient.EdsResourceWatcher;
import io.grpc.xds.XdsClient.EdsUpdate;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

final class EdsLoadBalancer2 extends LoadBalancer {

  private final XdsLogger logger;
  private final SynchronizationContext syncContext;
  private final LoadBalancerRegistry lbRegistry;
  private final GracefulSwitchLoadBalancer switchingLoadBalancer;

  // Following fields are effectively final.
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private String cluster;
  private EdsLbState edsLbState;

  EdsLoadBalancer2(LoadBalancer.Helper helper) {
    this(helper, LoadBalancerRegistry.getDefaultRegistry());
  }

  @VisibleForTesting
  EdsLoadBalancer2(LoadBalancer.Helper helper, LoadBalancerRegistry lbRegistry) {
    this.lbRegistry = checkNotNull(lbRegistry, "lbRegistry");
    syncContext = checkNotNull(helper, "helper").getSynchronizationContext();
    switchingLoadBalancer = new GracefulSwitchLoadBalancer(helper);
    InternalLogId logId = InternalLogId.allocate("eds-lb", helper.getAuthority());
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    if (xdsClientPool == null) {
      xdsClientPool = resolvedAddresses.getAttributes().get(XdsAttributes.XDS_CLIENT_POOL);
      xdsClient = xdsClientPool.getObject();
    }
    EdsConfig config = (EdsConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    if (logger.isLoggable(XdsLogLevel.INFO)) {
      logger.log(XdsLogLevel.INFO, "Received EDS lb config: cluster={0}, "
              + "eds_service_name={1}, max_concurrent_requests={2}, locality_picking_policy={3}, "
              + "endpoint_picking_policy={4}, report_load={5}",
          config.clusterName, config.edsServiceName, config.maxConcurrentRequests,
          config.localityPickingPolicy.getProvider().getPolicyName(),
          config.endpointPickingPolicy.getProvider().getPolicyName(),
          config.lrsServerName != null);
    }
    if (cluster == null) {
      cluster = config.clusterName;
    }
    if (edsLbState == null || !Objects.equals(edsLbState.edsServiceName, config.edsServiceName)) {
      edsLbState = new EdsLbState(config.edsServiceName, config.lrsServerName);
      switchingLoadBalancer.switchTo(edsLbState);
    }
    switchingLoadBalancer.handleResolvedAddresses(resolvedAddresses);
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
    switchingLoadBalancer.handleNameResolutionError(error);
  }

  @Override
  public boolean canHandleEmptyAddressListFromNameResolution() {
    return true;
  }

  @Override
  public void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutdown");
    switchingLoadBalancer.shutdown();
    if (xdsClientPool != null) {
      xdsClientPool.returnObject(xdsClient);
    }
  }

  private final class EdsLbState extends LoadBalancer.Factory {
    @Nullable
    private final String edsServiceName;
    @Nullable
    private final String lrsServerName;
    private final String resourceName;

    private EdsLbState(@Nullable String edsServiceName, @Nullable String lrsServerName) {
      this.edsServiceName = edsServiceName;
      this.lrsServerName = lrsServerName;
      resourceName = edsServiceName == null ? cluster : edsServiceName;
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      return new ChildLbState(helper);
    }

    private final class ChildLbState extends LoadBalancer implements EdsResourceWatcher {
      private final Helper lbHelper;
      private ResolvedAddresses resolvedAddresses;
      private boolean shutdown;

      // Updated by endpoint discovery.
      private LoadBalancer lb;
      private List<EquivalentAddressGroup> endpointAddresses;
      private Map<Integer, Map<Locality, Integer>> prioritizedLocalityWeights;
      private List<DropOverload> dropOverloads;

      private ChildLbState(Helper helper) {
        lbHelper = helper;
        logger.log(
            XdsLogLevel.INFO,
            "Start endpoint watcher on {0} with xDS client {1}", resourceName, xdsClient);
        xdsClient.watchEdsResource(resourceName, this);
      }

      @Override
      public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
        boolean updated = !Objects.equals(this.resolvedAddresses, resolvedAddresses);
        this.resolvedAddresses = resolvedAddresses;
        if (lb != null && updated) {
          handleResourceUpdate();
        }
      }

      @Override
      public void handleNameResolutionError(Status error) {
        if (lb != null) {
          lb.handleNameResolutionError(error);
        } else {
          lbHelper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
        }
      }

      @Override
      public void shutdown() {
        shutdown = true;
        xdsClient.cancelEdsResourceWatch(resourceName, this);
        logger.log(
            XdsLogLevel.INFO,
            "Cancelled endpoint watcher on {0} with xDS client {1}", resourceName, xdsClient);
        if (lb != null) {
          lb.shutdown();
        }
      }

      @Override
      public boolean canHandleEmptyAddressListFromNameResolution() {
        return true;
      }

      @Override
      public void onChanged(final EdsUpdate update) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (shutdown) {
              return;
            }
            logger.log(XdsLogLevel.DEBUG,
                "Received endpoint update from xDS client {0}: {1}", xdsClient, update);
            if (logger.isLoggable(XdsLogLevel.INFO)) {
              logger.log(XdsLogLevel.INFO, "Received endpoint update: cluster_name={0}, "
                      + "{1} localities, {2} drop categories", update.clusterName,
                  update.localityLbEndpointsMap.size(), update.dropPolicies.size());
            }
            dropOverloads = update.dropPolicies;
            Map<Locality, LocalityLbEndpoints> localityLbEndpoints =
                update.localityLbEndpointsMap;
            endpointAddresses = new ArrayList<>();
            prioritizedLocalityWeights = new HashMap<>();
            for (Locality locality : localityLbEndpoints.keySet()) {
              LocalityLbEndpoints localityLbInfo = localityLbEndpoints.get(locality);
              int priority = localityLbInfo.getPriority();
              boolean discard = true;
              for (LbEndpoint endpoint : localityLbInfo.getEndpoints()) {
                if (endpoint.isHealthy()) {
                  discard = false;
                  EquivalentAddressGroup eag =
                      AddressFilter.setPathFilter(
                          endpoint.getAddress(),
                          Arrays.asList(priorityName(priority), localityName(locality)));
                  endpointAddresses.add(eag);
                }
              }
              if (discard) {
                logger.log(XdsLogLevel.INFO, "Discard locality {0} with 0 healthy endpoints");
                continue;
              }
              if (!prioritizedLocalityWeights.containsKey(priority)) {
                prioritizedLocalityWeights.put(priority, new HashMap<Locality, Integer>());
              }
              prioritizedLocalityWeights.get(priority).put(
                  locality, localityLbInfo.getLocalityWeight());
            }
            if (prioritizedLocalityWeights.isEmpty()) {
              propagateResourceError(
                  Status.UNAVAILABLE.withDescription("No usable priority/locality/endpoint"));
              return;
            }
            if (lb == null) {
              lb = lbRegistry.getProvider(PRIORITY_POLICY_NAME).newLoadBalancer(lbHelper);
            }
            handleResourceUpdate();
          }
        });
      }

      @Override
      public void onResourceDoesNotExist(final String resourceName) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (shutdown) {
              return;
            }
            logger.log(XdsLogLevel.INFO, "Resource {0} is unavailable", resourceName);
            propagateResourceError(Status.UNAVAILABLE.withDescription(
                "Resource " + resourceName + " is unavailable"));
          }
        });
      }

      @Override
      public void onError(final Status error) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (shutdown) {
              return;
            }
            logger.log(
                XdsLogLevel.WARNING, "Received error from xDS client {0}: {1}", xdsClient, error);
            if (lb == null) {
              lbHelper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
            }
          }
        });
      }

      private void handleResourceUpdate() {
        // Populate configurations used by downstream LB policies from the freshest result.
        EdsConfig config = (EdsConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
        // Config for each priority.
        Map<String, PriorityChildConfig> priorityChildConfigs = new HashMap<>();
        List<String> priorities = new ArrayList<>();
        for (Integer priority : prioritizedLocalityWeights.keySet()) {
          WeightedTargetConfig weightedTargetConfig =
              generateWeightedTargetLbConfig(cluster, edsServiceName, lrsServerName,
                  config.endpointPickingPolicy, lbRegistry,
                  prioritizedLocalityWeights.get(priority));
          PolicySelection localityPicking =
              new PolicySelection(config.localityPickingPolicy.getProvider(),
                  weightedTargetConfig);
          ClusterImplConfig clusterImplConfig =
              new ClusterImplConfig(cluster, edsServiceName, lrsServerName,
                  config.maxConcurrentRequests, dropOverloads, localityPicking, config.tlsContext);
          LoadBalancerProvider clusterImplLbProvider =
              lbRegistry.getProvider(XdsLbPolicies.CLUSTER_IMPL_POLICY_NAME);
          PolicySelection clusterImplPolicy =
              new PolicySelection(clusterImplLbProvider, clusterImplConfig);
          String priorityName = priorityName(priority);
          priorityChildConfigs.put(priorityName, new PriorityChildConfig(clusterImplPolicy, true));
          priorities.add(priorityName);
        }
        Collections.sort(priorities);
        PriorityLbConfig priorityLbConfig =
            new PriorityLbConfig(Collections.unmodifiableMap(priorityChildConfigs),
                Collections.unmodifiableList(priorities));
        lb.handleResolvedAddresses(
            resolvedAddresses.toBuilder()
                .setAddresses(endpointAddresses)
                .setLoadBalancingPolicyConfig(priorityLbConfig)
                .build());
      }

      private void propagateResourceError(Status error) {
        if (lb != null) {
          lb.shutdown();
          lb = null;
        }
        lbHelper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
      }
    }
  }

  @VisibleForTesting
  static WeightedTargetConfig generateWeightedTargetLbConfig(
      String cluster, @Nullable String edsServiceName, @Nullable String lrsServerName,
      PolicySelection endpointPickingPolicy, LoadBalancerRegistry lbRegistry,
      Map<Locality, Integer> localityWeights) {
    Map<String, WeightedPolicySelection> targets = new HashMap<>();
    for (Locality locality : localityWeights.keySet()) {
      int weight = localityWeights.get(locality);
      PolicySelection childPolicy;
      if (lrsServerName != null) {
        LrsConfig childConfig =
            new LrsConfig(cluster, edsServiceName, lrsServerName, locality, endpointPickingPolicy);
        LoadBalancerProvider childPolicyProvider = lbRegistry.getProvider(LRS_POLICY_NAME);
        childPolicy = new PolicySelection(childPolicyProvider, childConfig);
      } else {
        childPolicy = endpointPickingPolicy;
      }
      targets.put(localityName(locality), new WeightedPolicySelection(weight, childPolicy));
    }
    return new WeightedTargetConfig(Collections.unmodifiableMap(targets));
  }

  /** Generate a string to be used as the key for the given priority in the LB policy config. */
  private static String priorityName(int priority) {
    return "priority" + priority;
  }

  /** Generate a string to be used as the key for the given locality in the LB policy config. */
  private static String localityName(Locality locality) {
    return locality.toString();
  }
}
