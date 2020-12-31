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
import static io.grpc.xds.XdsLbPolicies.EDS_POLICY_NAME;
import static io.grpc.xds.XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME;

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
import io.grpc.xds.EdsLoadBalancerProvider.EdsConfig;
import io.grpc.xds.XdsClient.CdsResourceWatcher;
import io.grpc.xds.XdsClient.CdsUpdate;
import io.grpc.xds.XdsClient.CdsUpdate.ClusterType;
import io.grpc.xds.XdsClient.CdsUpdate.EdsClusterConfig;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import javax.annotation.Nullable;

/**
 * Load balancer for cds_experimental LB policy. One instance per cluster.
 */
final class CdsLoadBalancer extends LoadBalancer {
  private final XdsLogger logger;
  private final Helper helper;
  private final SynchronizationContext syncContext;
  private final LoadBalancerRegistry lbRegistry;
  private String clusterName;
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private CdsLbState cdsLbState;
  private ResolvedAddresses resolvedAddresses;

  CdsLoadBalancer(Helper helper) {
    this(helper, LoadBalancerRegistry.getDefaultRegistry());
  }

  @VisibleForTesting
  CdsLoadBalancer(Helper helper, LoadBalancerRegistry lbRegistry) {
    this.helper = checkNotNull(helper, "helper");
    this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    this.lbRegistry = lbRegistry;
    logger = XdsLogger.withLogId(InternalLogId.allocate("cds-lb", helper.getAuthority()));
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    if (clusterName != null) {
      return;
    }
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    this.resolvedAddresses = resolvedAddresses;
    xdsClientPool = resolvedAddresses.getAttributes().get(XdsAttributes.XDS_CLIENT_POOL);
    checkNotNull(xdsClientPool, "missing xDS client pool");
    xdsClient = xdsClientPool.getObject();
    Object lbConfig = resolvedAddresses.getLoadBalancingPolicyConfig();
    checkNotNull(lbConfig, "missing CDS lb config");
    CdsConfig newCdsConfig = (CdsConfig) lbConfig;
    logger.log(
        XdsLogLevel.INFO,
        "Received CDS lb config: cluster={0}", newCdsConfig.name);
    clusterName = newCdsConfig.name;
    cdsLbState = new CdsLbState();
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
    if (cdsLbState != null) {
      cdsLbState.propagateError(error);
    } else {
      helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
    }
  }

  @Override
  public boolean canHandleEmptyAddressListFromNameResolution() {
    return true;
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

  private final class CdsLbState implements CdsResourceWatcher {
    private boolean shutdown;
    @Nullable
    LoadBalancer edsBalancer;

    private CdsLbState() {
      xdsClient.watchCdsResource(clusterName, this);
      logger.log(XdsLogLevel.INFO,
          "Started watcher for cluster {0} with xDS client {1}", clusterName, xdsClient);
    }

    @Override
    public void onChanged(final CdsUpdate update) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (shutdown) {
            return;
          }
          // TODO(chengyuanzhang): implementations for logical DNS and aggregate clusters.
          if (update.clusterType != ClusterType.EDS) {
            logger.log(XdsLogLevel.WARNING, "Unsupported cluster type: {0}", update.clusterType);
            return;
          }
          EdsClusterConfig clusterConfig = (EdsClusterConfig) update.clusterConfig;
          logger.log(XdsLogLevel.INFO, "EDS cluster {0}, edsServiceName: {1}",
              update.clusterName, clusterConfig.edsServiceName);
          logger.log(XdsLogLevel.DEBUG, "Cluster config: {0}", clusterConfig);

          LoadBalancerProvider endpointPickingPolicyProvider =
              lbRegistry.getProvider(clusterConfig.lbPolicy);
          LoadBalancerProvider localityPickingPolicyProvider =
              lbRegistry.getProvider(WEIGHTED_TARGET_POLICY_NAME);  // hardcode to weighted-target
          final EdsConfig edsConfig =
              new EdsConfig(
                  /* clusterName = */ update.clusterName,
                  /* edsServiceName = */ clusterConfig.edsServiceName,
                  /* lrsServerName = */ clusterConfig.lrsServerName,
                  /* maxConcurrentRequests = */ clusterConfig.maxConcurrentRequests,
                  /* tlsContext = */ clusterConfig.upstreamTlsContext,
                  new PolicySelection(localityPickingPolicyProvider, null /* by EDS policy */),
                  new PolicySelection(endpointPickingPolicyProvider, null));
          if (edsBalancer == null) {
            edsBalancer = lbRegistry.getProvider(EDS_POLICY_NAME).newLoadBalancer(helper);
          }
          edsBalancer.handleResolvedAddresses(
              resolvedAddresses.toBuilder().setLoadBalancingPolicyConfig(edsConfig).build());
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
          if (edsBalancer != null) {
            edsBalancer.shutdown();
            edsBalancer = null;
          }
          helper.updateBalancingState(
              TRANSIENT_FAILURE,
              new ErrorPicker(Status.UNAVAILABLE.withDescription(
                  "Resource " + resourceName + " is unavailable")));
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
              XdsLogLevel.WARNING,
              "Received error from xDS client {0}: {1}: {2}",
              xdsClient,
              error.getCode(),
              error.getDescription());
          if (edsBalancer == null) {
            helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
          }
        }
      });
    }

    void shutdown() {
      shutdown = true;
      xdsClient.cancelCdsResourceWatch(clusterName, this);
      logger.log(XdsLogLevel.INFO,
          "Cancelled watcher for cluster {0} with xDS client {1}", clusterName, xdsClient);
      if (edsBalancer != null) {
        edsBalancer.shutdown();
      }
    }

    void propagateError(Status error) {
      if (edsBalancer != null) {
        edsBalancer.handleNameResolutionError(error);
      } else {
        helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
      }
    }
  }
}
