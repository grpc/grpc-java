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
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.xds.XdsLoadBalancerProvider.XDS_POLICY_NAME;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.CdsLoadBalancerProvider.CdsConfig;
import io.grpc.xds.XdsClient.ClusterUpdate;
import io.grpc.xds.XdsClient.ClusterWatcher;
import io.grpc.xds.XdsLoadBalancerProvider.XdsConfig;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Load balancer for experimental_cds LB policy.
 */
public final class CdsLoadBalancer extends LoadBalancer {
  private final ChannelLogger channelLogger;
  private final LoadBalancerRegistry lbRegistry;
  private final GracefulSwitchLoadBalancer switchingLoadBalancer;
  private final Helper helper;

  // The following fields become non-null once handleResolvedAddresses() successfully.

  // Most recent CdsConfig.
  @Nullable
  private CdsConfig cdsConfig;
  // Most recent ClusterWatcher.
  @Nullable
  private ClusterWatcher clusterWatcher;
  @Nullable
  private ObjectPool<XdsClient> xdsClientRef;
  @Nullable
  private XdsClient xdsClient;

  CdsLoadBalancer(Helper helper) {
    this(helper, LoadBalancerRegistry.getDefaultRegistry());
  }

  @VisibleForTesting
  CdsLoadBalancer(Helper helper, LoadBalancerRegistry lbRegistry) {
    this.helper = helper;
    this.channelLogger = helper.getChannelLogger();
    this.lbRegistry = lbRegistry;
    this.switchingLoadBalancer = new GracefulSwitchLoadBalancer(helper);
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    channelLogger.log(ChannelLogLevel.DEBUG, "Received ResolvedAddresses '%s'", resolvedAddresses);
    Attributes attributes = resolvedAddresses.getAttributes();
    if (xdsClientRef == null) {
      xdsClientRef = resolvedAddresses.getAttributes().get(XdsAttributes.XDS_CLIENT_REF);
      if (xdsClientRef == null) {
        // TODO(zdapeng): create a new xdsClient from bootstrap if no one exists.
        helper.updateBalancingState(
            TRANSIENT_FAILURE,
            new ErrorPicker(Status.UNAVAILABLE.withDescription(
                "XDS_CLIENT_REF attributes not available from resolve addresses")));
        return;
      }
      xdsClient = xdsClientRef.getObject();
    }

    Map<String, ?> newRawLbConfig = attributes.get(ATTR_LOAD_BALANCING_CONFIG);
    if (newRawLbConfig == null) {
      // This will not happen when the service config error handling is implemented.
      // For now simply go to TRANSIENT_FAILURE.
      helper.updateBalancingState(
          TRANSIENT_FAILURE,
          new ErrorPicker(
              Status.UNAVAILABLE.withDescription("ATTR_LOAD_BALANCING_CONFIG not available")));
      return;
    }
    ConfigOrError cfg =
        CdsLoadBalancerProvider.parseLoadBalancingConfigPolicy(newRawLbConfig);
    if (cfg.getError() != null) {
      // This will not happen when the service config error handling is implemented.
      // For now simply go to TRANSIENT_FAILURE.
      helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(cfg.getError()));
      return;
    }
    final CdsConfig newCdsConfig = (CdsConfig) cfg.getConfig();

    // If CdsConfig is changed, do a graceful switch.
    if (!newCdsConfig.equals(cdsConfig)) {
      LoadBalancerProvider fixedCdsConfigBalancerProvider =
          new FixedCdsConfigBalancerProvider(newCdsConfig);
      switchingLoadBalancer.switchTo(fixedCdsConfigBalancerProvider);
    }

    switchingLoadBalancer.handleResolvedAddresses(resolvedAddresses);

    // The clusterWatcher is also updated after switchingLoadBalancer.handleResolvedAddresses().
    cdsConfig = newCdsConfig;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    channelLogger.log(ChannelLogLevel.ERROR, "Name resolution error: '%s'", error);
    // Go into TRANSIENT_FAILURE if we have not yet received any cluster resource. Otherwise,
    // we keep running with the data we had previously.
    if (clusterWatcher == null) {
      helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
    } else {
      switchingLoadBalancer.handleNameResolutionError(error);
    }
  }

  @Override
  public boolean canHandleEmptyAddressListFromNameResolution() {
    return true;
  }

  @Override
  public void shutdown() {
    channelLogger.log(ChannelLogLevel.DEBUG, "CDS load balancer is shutting down");

    switchingLoadBalancer.shutdown();
    if (xdsClientRef != null) {
      xdsClientRef.returnObject(xdsClient);
    }
  }

  /**
   * A LoadBalancerProvider that provides a load balancer with a fixed CdsConfig.
   */
  private final class FixedCdsConfigBalancerProvider extends LoadBalancerProvider {

    final CdsConfig cdsConfig;
    final CdsConfig oldCdsConfig;
    final ClusterWatcher oldClusterWatcher;

    FixedCdsConfigBalancerProvider(CdsConfig cdsConfig) {
      this.cdsConfig = cdsConfig;
      oldCdsConfig = CdsLoadBalancer.this.cdsConfig;
      oldClusterWatcher = CdsLoadBalancer.this.clusterWatcher;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 5;
    }

    // A synthetic policy name identified by CDS config.
    @Override
    public String getPolicyName() {
      return "cds_policy__cluster_name_" + cdsConfig.name;
    }

    @Override
    public LoadBalancer newLoadBalancer(final Helper helper) {
      return new LoadBalancer() {
        // Becomes non-null once handleResolvedAddresses() successfully.
        // Assigned at most once.
        @Nullable
        ClusterWatcherImpl clusterWatcher;

        @Override
        public void handleNameResolutionError(Status error) {
          if (clusterWatcher == null || clusterWatcher.edsBalancer == null) {
            // Go into TRANSIENT_FAILURE if we have not yet received any cluster resource.
            // Otherwise, we keep running with the data we had previously.
            helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
          }
        }

        @Override
        public boolean canHandleEmptyAddressListFromNameResolution() {
          return true;
        }

        @Override
        public void shutdown() {
          if (clusterWatcher != null) {
            if (clusterWatcher.edsBalancer != null) {
              clusterWatcher.edsBalancer.shutdown();
            }
            xdsClient.cancelClusterDataWatch(cdsConfig.name, clusterWatcher);
          }
        }

        @Override
        public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
          if (clusterWatcher == null) {
            clusterWatcher = new ClusterWatcherImpl(helper, resolvedAddresses);
            xdsClient.watchClusterData(cdsConfig.name, clusterWatcher);
            if (oldCdsConfig != null) {
              xdsClient.cancelClusterDataWatch(oldCdsConfig.name, oldClusterWatcher);
            }
            CdsLoadBalancer.this.clusterWatcher = clusterWatcher;
          }
        }
      };
    }
  }

  private final class ClusterWatcherImpl implements ClusterWatcher {

    final Helper helper;
    final ResolvedAddresses resolvedAddresses;

    // EDS balancer for the cluster.
    // Becomes non-null once handleResolvedAddresses() successfully.
    // Assigned at most once.
    @Nullable
    LoadBalancer edsBalancer;

    ClusterWatcherImpl(Helper helper, ResolvedAddresses resolvedAddresses) {
      this.helper = helper;
      this.resolvedAddresses = resolvedAddresses;
    }

    @Override
    public void onClusterChanged(ClusterUpdate newUpdate) {
      channelLogger.log(
          ChannelLogLevel.DEBUG, "CDS load balancer received a cluster update: '%s'",  newUpdate);
      checkArgument(
          newUpdate.getLbPolicy().equals("round_robin"),
          "The load balancing policy in ClusterUpdate '%s' is not supported", newUpdate);

      final XdsConfig edsConfig = new XdsConfig(
          /* balancerName = */ null,
          new LbConfig(newUpdate.getLbPolicy(), ImmutableMap.<String, Object>of()),
          /* fallbackPolicy = */ null,
          /* edsServiceName = */ newUpdate.getEdsServiceName(),
          /* lrsServerName = */ newUpdate.getLrsServerName());
      if (edsBalancer == null) {
        edsBalancer = lbRegistry.getProvider(XDS_POLICY_NAME).newLoadBalancer(helper);
      }
      edsBalancer.handleResolvedAddresses(
          resolvedAddresses.toBuilder()
              .setAttributes(
                  resolvedAddresses.getAttributes().toBuilder()
                      .discard(ATTR_LOAD_BALANCING_CONFIG)
                      .build())
              .setLoadBalancingPolicyConfig(edsConfig)
          .build());
    }

    @Override
    public void onError(Status error) {
      channelLogger.log(ChannelLogLevel.ERROR, "CDS load balancer received an error: '%s'",  error);

      // Go into TRANSIENT_FAILURE if we have not yet created the child
      // policy (i.e., we have not yet received valid data for the cluster). Otherwise,
      // we keep running with the data we had previously.
      if (edsBalancer == null) {
        helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
      }
    }
  }

}
