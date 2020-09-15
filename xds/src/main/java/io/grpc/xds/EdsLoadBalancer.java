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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.Attributes;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.EdsLoadBalancerProvider.EdsConfig;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.LoadStatsManager.LoadStatsStore;
import io.grpc.xds.LocalityStore.LocalityStoreFactory;
import io.grpc.xds.XdsClient.EndpointUpdate;
import io.grpc.xds.XdsClient.EndpointWatcher;
import io.grpc.xds.XdsClient.RefCountedXdsClientObjectPool;
import io.grpc.xds.XdsClient.XdsChannel;
import io.grpc.xds.XdsClient.XdsClientFactory;
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
  private final GracefulSwitchLoadBalancer switchingLoadBalancer;
  private final LoadBalancerRegistry lbRegistry;
  private final LocalityStoreFactory localityStoreFactory;
  private final Bootstrapper bootstrapper;
  private final XdsChannelFactory channelFactory;
  private final Helper helper;

  @Nullable
  private ObjectPool<XdsClient> xdsClientPool;
  @Nullable
  private XdsClient xdsClient;
  @Nullable
  private String clusterName;
  @Nullable
  private String edsServiceName;

  EdsLoadBalancer(Helper helper) {
    this(
        helper,
        LoadBalancerRegistry.getDefaultRegistry(),
        LocalityStoreFactory.getInstance(),
        Bootstrapper.getInstance(),
        XdsChannelFactory.getInstance());
  }

  @VisibleForTesting
  EdsLoadBalancer(
      Helper helper,
      LoadBalancerRegistry lbRegistry,
      LocalityStoreFactory localityStoreFactory,
      Bootstrapper bootstrapper,
      XdsChannelFactory channelFactory) {
    this.helper = checkNotNull(helper, "helper");
    this.lbRegistry = checkNotNull(lbRegistry, "lbRegistry");
    this.localityStoreFactory = checkNotNull(localityStoreFactory, "localityStoreFactory");
    this.bootstrapper = checkNotNull(bootstrapper, "bootstrapper");
    this.channelFactory = checkNotNull(channelFactory, "channelFactory");
    this.switchingLoadBalancer = new GracefulSwitchLoadBalancer(helper);
    logId = InternalLogId.allocate("eds-lb", helper.getAuthority());
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    Object lbConfig = resolvedAddresses.getLoadBalancingPolicyConfig();
    if (lbConfig == null) {
      handleNameResolutionError(Status.UNAVAILABLE.withDescription("Missing EDS lb config"));
      return;
    }
    EdsConfig newEdsConfig = (EdsConfig) lbConfig;
    if (logger.isLoggable(XdsLogLevel.INFO)) {
      logger.log(
          XdsLogLevel.INFO,
          "Received EDS lb config: cluster={0}, child_policy={1}, "
              + "eds_service_name={2}, report_load={3}",
          newEdsConfig.clusterName,
          newEdsConfig.endpointPickingPolicy.getProvider().getPolicyName(),
          newEdsConfig.edsServiceName,
          newEdsConfig.lrsServerName != null);
    }
    boolean firstUpdate = false;
    if (clusterName == null) {
      firstUpdate = true;
    }
    clusterName = newEdsConfig.clusterName;
    if (xdsClientPool == null) {
      Attributes attributes = resolvedAddresses.getAttributes();
      xdsClientPool = attributes.get(XdsAttributes.XDS_CLIENT_POOL);
      if (xdsClientPool == null) {
        final BootstrapInfo bootstrapInfo;
        final XdsChannel channel;
        try {
          bootstrapInfo = bootstrapper.readBootstrap();
          channel = channelFactory.createChannel(bootstrapInfo.getServers());
        } catch (Exception e) {
          helper.updateBalancingState(
              TRANSIENT_FAILURE,
              new ErrorPicker(
                  Status.UNAVAILABLE.withDescription("Failed to bootstrap").withCause(e)));
          return;
        }

        final Node node = bootstrapInfo.getNode();
        XdsClientFactory xdsClientFactory = new XdsClientFactory() {
          @Override
          XdsClient createXdsClient() {
            return
                new XdsClientImpl(
                    helper.getAuthority(),
                    channel,
                    node,
                    helper.getSynchronizationContext(),
                    helper.getScheduledExecutorService(),
                    new ExponentialBackoffPolicy.Provider(),
                    GrpcUtil.STOPWATCH_SUPPLIER);
          }
        };
        xdsClientPool = new RefCountedXdsClientObjectPool(xdsClientFactory);
      } else {
        logger.log(XdsLogLevel.INFO, "Use xDS client from channel");
      }
      xdsClient = xdsClientPool.getObject();
    }

    // Note: childPolicy change will be handled in LocalityStore, to be implemented.
    // If edsServiceName in XdsConfig is changed, do a graceful switch.
    if (firstUpdate || !Objects.equals(newEdsConfig.edsServiceName, edsServiceName)) {
      LoadBalancer.Factory clusterEndpointsLoadBalancerFactory =
          new ClusterEndpointsBalancerFactory(newEdsConfig.edsServiceName);
      switchingLoadBalancer.switchTo(clusterEndpointsLoadBalancerFactory);
    }
    switchingLoadBalancer.handleResolvedAddresses(resolvedAddresses);
    this.edsServiceName = newEdsConfig.edsServiceName;
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

    ClusterEndpointsBalancerFactory(@Nullable String clusterServiceName) {
      this.clusterServiceName = clusterServiceName;
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
        LoadStatsStore loadStatsStore = xdsClient.addClientStats(clusterName, clusterServiceName);
        localityStore =
            localityStoreFactory.newLocalityStore(logId, helper, lbRegistry, loadStatsStore);
        endpointWatcher = new EndpointWatcherImpl();
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
            xdsClient.reportClientStats();
            isReportingLoad = true;
          }
        } else {
          if (isReportingLoad) {
            xdsClient.cancelClientStatsReport();
            isReportingLoad = false;
          }
        }
        // TODO(zddapeng): In handleResolvedAddresses() handle child policy change if any.
      }

      @Override
      public void handleNameResolutionError(Status error) {
        if (!endpointWatcher.endpointsReceived) {
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
          xdsClient.cancelClientStatsReport();
          isReportingLoad = false;
        }
        localityStore.reset();
        xdsClient.removeClientStats(clusterName, clusterServiceName);
        xdsClient.cancelEndpointDataWatch(resourceName, endpointWatcher);
        logger.log(
            XdsLogLevel.INFO,
            "Cancelled endpoint watcher on {0} with xDS client {1}",
            resourceName,
            xdsClient);
      }

      private final class EndpointWatcherImpl implements EndpointWatcher {
        boolean endpointsReceived;

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
          endpointsReceived = true;
          List<DropOverload> dropOverloads = endpointUpdate.getDropPolicies();
          ImmutableList.Builder<DropOverload> dropOverloadsBuilder = ImmutableList.builder();
          for (DropOverload dropOverload : dropOverloads) {
            dropOverloadsBuilder.add(dropOverload);
            if (dropOverload.getDropsPerMillion() == 1_000_000) {
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
        public void onResourceDoesNotExist(String resourceName) {
          logger.log(XdsLogLevel.INFO, "Resource {0} is unavailable", resourceName);
          if (isReportingLoad) {
            xdsClient.cancelClientStatsReport();
            isReportingLoad = false;
          }
          localityStore.reset();
          helper.updateBalancingState(
              TRANSIENT_FAILURE,
              new ErrorPicker(
                  Status.UNAVAILABLE.withDescription(
                      "Resource " + resourceName + " is unavailable")));
        }

        @Override
        public void onError(Status error) {
          logger.log(
              XdsLogLevel.WARNING,
              "Received error from xDS client {0}: {1}: {2}",
              xdsClient,
              error.getCode(),
              error.getDescription());
          if (!endpointsReceived) {
            helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
          }
        }
      }
    }
  }
}
