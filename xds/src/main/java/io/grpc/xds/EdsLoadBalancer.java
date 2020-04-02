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
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.internal.ObjectPool;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.EdsLoadBalancerProvider.EdsConfig;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.LocalityStore.LocalityStoreFactory;
import io.grpc.xds.XdsClient.EndpointUpdate;
import io.grpc.xds.XdsClient.EndpointWatcher;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/** Load balancer for the EDS LB policy. */
final class EdsLoadBalancer extends LoadBalancer {

  private final InternalLogId logId;
  private final XdsLogger logger;
  private final ResourceUpdateCallback resourceUpdateCallback;
  private final GracefulSwitchLoadBalancer switchingLoadBalancer;
  private final LoadBalancerRegistry lbRegistry;
  private final LocalityStoreFactory localityStoreFactory;
  private final Helper edsLbHelper;

  @Nullable
  private ObjectPool<XdsClient> xdsClientPool;
  @Nullable
  private XdsClient xdsClient;
  private String clusterName;
  @Nullable
  private String edsServiceName;

  EdsLoadBalancer(Helper edsLbHelper, ResourceUpdateCallback resourceUpdateCallback) {
    this(
        edsLbHelper,
        resourceUpdateCallback,
        LoadBalancerRegistry.getDefaultRegistry(),
        LocalityStoreFactory.getInstance());
  }

  @VisibleForTesting
  EdsLoadBalancer(
      Helper edsLbHelper,
      ResourceUpdateCallback resourceUpdateCallback,
      LoadBalancerRegistry lbRegistry,
      LocalityStoreFactory localityStoreFactory) {
    this.edsLbHelper = checkNotNull(edsLbHelper, "edsLbHelper");
    this.resourceUpdateCallback = checkNotNull(resourceUpdateCallback, "resourceUpdateCallback");
    this.lbRegistry = checkNotNull(lbRegistry, "lbRegistry");
    this.localityStoreFactory = checkNotNull(localityStoreFactory, "localityStoreFactory");
    this.switchingLoadBalancer = new GracefulSwitchLoadBalancer(edsLbHelper);
    logId = InternalLogId.allocate("eds-lb", edsLbHelper.getAuthority());
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    Object lbConfig = resolvedAddresses.getLoadBalancingPolicyConfig();
    checkNotNull(lbConfig, "missing EDS lb config");
    EdsConfig newConfig = (EdsConfig) lbConfig;
    if (logger.isLoggable(XdsLogLevel.INFO)) {
      logger.log(
          XdsLogLevel.INFO,
          "Received EDS lb config: cluster={0}, endpoint_picking_policy={1}, "
              + "eds_service_name={3}, report_load={4}",
          newConfig.clusterName,
          newConfig.endpointPickingPolicy != null
              ? newConfig.endpointPickingPolicy.getProvider().getPolicyName() : "",
          newConfig.edsServiceName,
          newConfig.lrsServerName != null);
    }
    boolean firstUpdate = false;
    if (clusterName == null) {
      clusterName = newConfig.clusterName;
      firstUpdate = true;
    } else {
      checkArgument(clusterName.equals(newConfig.clusterName), "cluster name should not change");
    }
    if (xdsClientPool == null) {
      xdsClientPool = resolvedAddresses.getAttributes().get(XdsAttributes.XDS_CLIENT_POOL);
      xdsClient = xdsClientPool.getObject();
    }
    if (firstUpdate || !Objects.equals(edsServiceName, newConfig.edsServiceName)) {
      LoadBalancer.Factory clusterEndpointsLoadBalancerFactory =
          new ClusterEndpointsBalancerFactory(newConfig.edsServiceName);
      switchingLoadBalancer.switchTo(clusterEndpointsLoadBalancerFactory);
    }
    switchingLoadBalancer.handleResolvedAddresses(resolvedAddresses);
    edsServiceName = newConfig.edsServiceName;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
    // This will go into TRANSIENT_FAILURE if we have not yet received any endpoint update and
    // otherwise keep running with the data we had previously.
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
    if (xdsClient != null) {
      xdsClient = xdsClientPool.returnObject(xdsClient);
    }
  }

  /**
   * A load balancer factory that provides a load balancer for a given cluster service.
   */
  private final class ClusterEndpointsBalancerFactory extends LoadBalancer.Factory {
    @Nullable final String clusterServiceName;
    final LoadStatsStore loadStatsStore;

    ClusterEndpointsBalancerFactory(@Nullable String clusterServiceName) {
      this.clusterServiceName = clusterServiceName;
      loadStatsStore = new LoadStatsStoreImpl(clusterName, clusterServiceName);
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      return new ClusterEndpointsBalancer(helper);
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof ClusterEndpointsBalancerFactory)) {
        return false;
      }
      ClusterEndpointsBalancerFactory that = (ClusterEndpointsBalancerFactory) o;
      return Objects.equals(clusterServiceName, that.clusterServiceName);
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), clusterServiceName);
    }

    /**
     * Load-balances endpoints for a given cluster.
     */
    final class ClusterEndpointsBalancer extends LoadBalancer {
      // Name of the resource to be used for querying endpoint information.
      final String resourceName;
      final Helper helper;
      final EndpointWatcherImpl endpointWatcher;
      final LocalityStore localityStore;
      boolean isReportingLoad;

      ClusterEndpointsBalancer(Helper helper) {
        this.helper = helper;
        resourceName = clusterServiceName != null ? clusterServiceName : clusterName;
        localityStore =
            localityStoreFactory.newLocalityStore(logId, helper, lbRegistry, loadStatsStore);
        endpointWatcher = new EndpointWatcherImpl(localityStore);
        logger.log(
            XdsLogLevel.INFO,
            "Start endpoint watcher on {0} with xDS client {1}",
            resourceName,
            xdsClient);
        xdsClient.watchEndpointData(resourceName, endpointWatcher);
      }

      @Override
      public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
        EdsConfig config = (EdsConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
        if (config.lrsServerName != null) {
          if (!config.lrsServerName.equals("")) {
            throw new AssertionError("Can only report load to the same management server");
          }
          if (!isReportingLoad) {
            logger.log(
                XdsLogLevel.INFO,
                "Start reporting loads for cluster: {0}, cluster_service: {1}",
                clusterName,
                clusterServiceName);
            xdsClient.reportClientStats(clusterName, clusterServiceName, loadStatsStore);
            isReportingLoad = true;
          }
        } else {
          if (isReportingLoad) {
            logger.log(
                XdsLogLevel.INFO,
                "Stop reporting loads for cluster: {0}, cluster_service: {1}",
                clusterName,
                clusterServiceName);
            xdsClient.cancelClientStatsReport(clusterName, clusterServiceName);
            isReportingLoad = false;
          }
        }
        // TODO(zddapeng): In handleResolvedAddresses() handle child policy change if any.
      }

      @Override
      public void handleNameResolutionError(Status error) {
        // Go into TRANSIENT_FAILURE if we have not yet received any endpoint update. Otherwise,
        // we keep running with the data we had previously.
        if (!endpointWatcher.firstEndpointUpdateReceived) {
          helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
        }
      }

      @Override
      public boolean canHandleEmptyAddressListFromNameResolution() {
        return true;
      }

      @Override
      public void shutdown() {
        if (isReportingLoad) {
          logger.log(
              XdsLogLevel.INFO,
              "Stop reporting loads for cluster: {0}, cluster_service: {1}",
              clusterName,
              clusterServiceName);
          xdsClient.cancelClientStatsReport(clusterName, clusterServiceName);
          isReportingLoad = false;
        }
        localityStore.reset();
        xdsClient.cancelEndpointDataWatch(resourceName, endpointWatcher);
        logger.log(
            XdsLogLevel.INFO,
            "Cancelled endpoint watcher on {0} with xDS client {1}",
            resourceName,
            xdsClient);
      }
    }
  }

  /**
   * Callbacks for the EDS-only-with-fallback usecase. Being deprecated.
   */
  interface ResourceUpdateCallback {

    void onWorking();

    void onError();

    void onAllDrop();
  }

  private final class EndpointWatcherImpl implements EndpointWatcher {

    final LocalityStore localityStore;
    boolean firstEndpointUpdateReceived;

    EndpointWatcherImpl(LocalityStore localityStore) {
      this.localityStore = localityStore;
    }

    @Override
    public void onEndpointChanged(EndpointUpdate endpointUpdate) {
      logger.log(XdsLogLevel.DEBUG, "Received endpoint update: {0}", endpointUpdate);
      if (logger.isLoggable(XdsLogLevel.INFO)) {
        logger.log(
            XdsLogLevel.INFO,
            "Received endpoint update from xDS client {0}: cluster_name={1}, {2} localities, "
            + "{3} drop categories",
            xdsClient,
            endpointUpdate.getClusterName(),
            endpointUpdate.getLocalityLbEndpointsMap().size(),
            endpointUpdate.getDropPolicies().size());
      }

      if (!firstEndpointUpdateReceived) {
        firstEndpointUpdateReceived = true;
        resourceUpdateCallback.onWorking();
      }

      List<DropOverload> dropOverloads = endpointUpdate.getDropPolicies();
      ImmutableList.Builder<DropOverload> dropOverloadsBuilder = ImmutableList.builder();
      for (DropOverload dropOverload : dropOverloads) {
        dropOverloadsBuilder.add(dropOverload);
        if (dropOverload.getDropsPerMillion() == 1_000_000) {
          resourceUpdateCallback.onAllDrop();
          break;
        }
      }
      localityStore.updateDropPercentage(dropOverloadsBuilder.build());

      ImmutableMap.Builder<Locality, LocalityLbEndpoints> localityEndpointsMapping =
          new ImmutableMap.Builder<>();
      for (Map.Entry<Locality, LocalityLbEndpoints> entry
          : endpointUpdate.getLocalityLbEndpointsMap().entrySet()) {
        int localityWeight = entry.getValue().getLocalityWeight();

        if (localityWeight != 0) {
          localityEndpointsMapping.put(entry.getKey(), entry.getValue());
        }
      }

      localityStore.updateLocalityStore(localityEndpointsMapping.build());
    }

    @Override
    public void onError(Status error) {
      logger.log(
          XdsLogLevel.WARNING,
          "Received error from xDS client {0}: {1}: {2}",
          xdsClient,
          error.getCode(),
          error.getDescription());
      resourceUpdateCallback.onError();
      // If we get an error before getting any valid result, we should put the channel in
      // TRANSIENT_FAILURE; if they get an error after getting a valid result, we keep using the
      // previous channel state.
      if (!firstEndpointUpdateReceived) {
        edsLbHelper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
      }
    }
  }
}
