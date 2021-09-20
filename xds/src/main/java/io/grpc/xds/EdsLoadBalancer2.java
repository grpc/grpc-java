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
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.EdsLoadBalancerProvider.EdsConfig;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.LbEndpoint;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.LoadStatsManager.LoadStatsStore;
import io.grpc.xds.LrsLoadBalancerProvider.LrsConfig;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig;
import io.grpc.xds.ThreadSafeRandom.ThreadSafeRandomImpl;
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
  private final LoadBalancerRegistry lbRegistry;
  private final ThreadSafeRandom random;
  private final GracefulSwitchLoadBalancer switchingLoadBalancer;
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private String cluster;
  private EdsLbState edsLbState;

  EdsLoadBalancer2(LoadBalancer.Helper helper) {
    this(helper, LoadBalancerRegistry.getDefaultRegistry(), ThreadSafeRandomImpl.instance);
  }

  @VisibleForTesting
  EdsLoadBalancer2(
      LoadBalancer.Helper helper, LoadBalancerRegistry lbRegistry, ThreadSafeRandom random) {
    this.lbRegistry = checkNotNull(lbRegistry, "lbRegistry");
    this.random = checkNotNull(random, "random");
    switchingLoadBalancer = new GracefulSwitchLoadBalancer(checkNotNull(helper, "helper"));
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
              + "eds_service_name={1}, endpoint_picking_policy={2}, report_load={3}",
          config.clusterName, config.edsServiceName,
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
      @Nullable
      private final LoadStatsStore loadStatsStore;
      private final DropHandlingLbHelper lbHelper;
      private List<EquivalentAddressGroup> endpointAddresses = Collections.emptyList();
      private Map<Integer, Map<Locality, Integer>> prioritizedLocalityWeights
          = Collections.emptyMap();
      private ResolvedAddresses resolvedAddresses;
      private PolicySelection localityPickingPolicy;
      private PolicySelection endpointPickingPolicy;
      @Nullable
      private LoadBalancer lb;

      private ChildLbState(Helper helper) {
        if (lrsServerName != null) {
          loadStatsStore = xdsClient.addClientStats(cluster, edsServiceName);
          xdsClient.reportClientStats();
        } else {
          loadStatsStore = null;
        }
        lbHelper = new DropHandlingLbHelper(helper);
        logger.log(
            XdsLogLevel.INFO,
            "Start endpoint watcher on {0} with xDS client {1}", resourceName, xdsClient);
        xdsClient.watchEdsResource(resourceName, this);
      }

      @Override
      public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
        this.resolvedAddresses = resolvedAddresses;
        EdsConfig config = (EdsConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
        if (lb != null) {
          if (!config.localityPickingPolicy.equals(localityPickingPolicy)
              || !config.endpointPickingPolicy.equals(endpointPickingPolicy)) {
            PriorityLbConfig childConfig =
                generatePriorityLbConfig(cluster, edsServiceName, lrsServerName,
                    config.localityPickingPolicy, config.endpointPickingPolicy, lbRegistry,
                    prioritizedLocalityWeights);
            // TODO(chengyuanzhang): to be deleted after migrating to use XdsClient API.
            Attributes attributes;
            if (lrsServerName != null) {
              attributes =
                  resolvedAddresses.getAttributes().toBuilder()
                      .set(XdsAttributes.ATTR_CLUSTER_SERVICE_LOAD_STATS_STORE, loadStatsStore)
                      .build();
            } else {
              attributes = resolvedAddresses.getAttributes();
            }
            lb.handleResolvedAddresses(
                resolvedAddresses.toBuilder()
                    .setAddresses(endpointAddresses)
                    .setAttributes(attributes)
                    .setLoadBalancingPolicyConfig(childConfig)
                    .build());
          }
        }
        localityPickingPolicy = config.localityPickingPolicy;
        endpointPickingPolicy = config.endpointPickingPolicy;
      }

      @Override
      public void handleNameResolutionError(Status error) {
        if (lb != null) {
          lb.handleNameResolutionError(error);
        } else {
          lbHelper.helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
        }
      }

      @Override
      public void shutdown() {
        if (lrsServerName != null) {
          xdsClient.cancelClientStatsReport();
          xdsClient.removeClientStats(cluster, edsServiceName);
        }
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
      public void onChanged(EdsUpdate update) {
        logger.log(XdsLogLevel.DEBUG,
            "Received endpoint update from xDS client {0}: {1}", xdsClient, update);
        if (logger.isLoggable(XdsLogLevel.INFO)) {
          logger.log(
              XdsLogLevel.INFO,
              "Received endpoint update: cluster_name={0}, {1} localities, {2} drop categories",
              update.getClusterName(), update.getLocalityLbEndpointsMap().size(),
              update.getDropPolicies().size());
        }
        lbHelper.updateDropPolicies(update.getDropPolicies());
        Map<Locality, LocalityLbEndpoints> localityLbEndpoints = update.getLocalityLbEndpointsMap();
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
        if (localityPickingPolicy != null && endpointPickingPolicy != null) {
          PriorityLbConfig config = generatePriorityLbConfig(cluster, edsServiceName,
              lrsServerName, localityPickingPolicy, endpointPickingPolicy, lbRegistry,
              prioritizedLocalityWeights);
          // TODO(chengyuanzhang): to be deleted after migrating to use XdsClient API.
          Attributes attributes;
          if (lrsServerName != null) {
            attributes =
                resolvedAddresses.getAttributes().toBuilder()
                    .set(XdsAttributes.ATTR_CLUSTER_SERVICE_LOAD_STATS_STORE, loadStatsStore)
                    .build();
          } else {
            attributes = resolvedAddresses.getAttributes();
          }
          lb.handleResolvedAddresses(
              resolvedAddresses.toBuilder()
                  .setAddresses(endpointAddresses)
                  .setAttributes(attributes)
                  .setLoadBalancingPolicyConfig(config)
                  .build());
        }
      }

      @Override
      public void onResourceDoesNotExist(String resourceName) {
        logger.log(XdsLogLevel.INFO, "Resource {0} is unavailable", resourceName);
        propagateResourceError(
            Status.UNAVAILABLE.withDescription("Resource " + resourceName + " is unavailable"));
      }

      @Override
      public void onError(Status error) {
        logger.log(
            XdsLogLevel.WARNING, "Received error from xDS client {0}: {1}", xdsClient, error);
        if (lb == null) {
          lbHelper.helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
        }
      }

      private void propagateResourceError(Status error) {
        if (lb != null) {
          lb.shutdown();
          lb = null;
        }
        lbHelper.helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
      }

      private final class DropHandlingLbHelper extends ForwardingLoadBalancerHelper {
        private final Helper helper;
        private List<DropOverload> dropPolicies = Collections.emptyList();

        private DropHandlingLbHelper(Helper helper) {
          this.helper = helper;
        }

        @Override
        public void updateBalancingState(
            ConnectivityState newState, final SubchannelPicker newPicker) {
          SubchannelPicker picker = new SubchannelPicker() {
            List<DropOverload> dropOverloads = dropPolicies;

            @Override
            public PickResult pickSubchannel(PickSubchannelArgs args) {
              for (DropOverload dropOverload : dropOverloads) {
                int rand = random.nextInt(1_000_000);
                if (rand < dropOverload.getDropsPerMillion()) {
                  logger.log(
                      XdsLogLevel.INFO,
                      "Drop request with category: {0}", dropOverload.getCategory());
                  if (loadStatsStore != null) {
                    loadStatsStore.recordDroppedRequest(dropOverload.getCategory());
                  }
                  return PickResult.withDrop(
                      Status.UNAVAILABLE.withDescription("Dropped: " + dropOverload.getCategory()));
                }
              }
              return newPicker.pickSubchannel(args);
            }
          };
          delegate().updateBalancingState(newState, picker);
        }

        @Override
        protected Helper delegate() {
          return helper;
        }

        private void updateDropPolicies(List<DropOverload> dropOverloads) {
          dropPolicies = dropOverloads;
        }
      }
    }
  }

  @VisibleForTesting
  static PriorityLbConfig generatePriorityLbConfig(
      String cluster, String edsServiceName, String lrsServerName,
      PolicySelection localityPickingPolicy, PolicySelection endpointPickingPolicy,
      LoadBalancerRegistry lbRegistry,
      Map<Integer, Map<Locality, Integer>> prioritizedLocalityWeights) {
    Map<String, PolicySelection> childPolicies = new HashMap<>();
    List<String> priorities = new ArrayList<>();
    for (Integer priority : prioritizedLocalityWeights.keySet()) {
      WeightedTargetConfig childConfig =
          generateWeightedTargetLbConfig(cluster, edsServiceName, lrsServerName,
              endpointPickingPolicy, lbRegistry, prioritizedLocalityWeights.get(priority));
      PolicySelection childPolicySelection =
          new PolicySelection(localityPickingPolicy.getProvider(), childConfig);
      String childName = priorityName(priority);
      childPolicies.put(childName, childPolicySelection);
      priorities.add(childName);
    }
    Collections.sort(priorities);
    return new PriorityLbConfig(
        Collections.unmodifiableMap(childPolicies), Collections.unmodifiableList(priorities));
  }

  @VisibleForTesting
  static WeightedTargetConfig generateWeightedTargetLbConfig(
      String cluster, String edsServiceName, String lrsServerName,
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
