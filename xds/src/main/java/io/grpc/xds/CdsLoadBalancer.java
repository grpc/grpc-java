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

import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.XdsClient.ClusterUpdate;
import io.grpc.xds.XdsClient.ClusterWatcher;
import io.grpc.xds.XdsLoadBalancerProvider.XdsConfig;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Load balancer for experimental_cds LB policy.
 */
public final class CdsLoadBalancer extends LoadBalancer {
  private final ChannelLogger channelLogger;
  private final LoadBalancerRegistry lbRegistry;
  private final GracefulSwitchLoadBalancer switchingLoadBalancer;

  // The following fields become non-null once handleResolvedAddresses() successfully.
  /** Most recent XdsConfig. */
  @Nullable
  private CdsConfig cdsConfig;
  /** Most recent ClusterWatcher. */
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
    this.channelLogger = helper.getChannelLogger();
    this.lbRegistry = lbRegistry;
    this.switchingLoadBalancer = new GracefulSwitchLoadBalancer(helper);
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    channelLogger.log(ChannelLogLevel.DEBUG, "Received ResolvedAddresses '%s'", resolvedAddresses);
    Object lbConfig = resolvedAddresses.getLoadBalancingPolicyConfig();
    Preconditions.checkArgument(
        lbConfig instanceof CdsConfig,
        "Expecting a CDS LB config, but the actual LB config is '%s'",
        lbConfig);

    if (xdsClientRef == null) {
      xdsClientRef = resolvedAddresses.getAttributes().get(XdsAttributes.XDS_CLIENT_REF);

      if (xdsClientRef == null) {
        // TODO(zdapeng): create a new xdsClient from bootstrap if no one exists.
        throw new UnsupportedOperationException(
            "XDS_CLIENT_REF attributes not available in resolvedAddresses " + resolvedAddresses);
      }

      xdsClient = xdsClientRef.getObject();
    }

    final CdsConfig newCdsConfig = (CdsConfig) lbConfig;
    // If CdsConfig is changed, do a graceful switch.
    if (!newCdsConfig.equals(cdsConfig)) {
      final CdsConfig oldCdsConfig = cdsConfig;
      final ClusterWatcher oldClusterWatcher = clusterWatcher;
      // Provides a load balancer with the fixed CdsConfig: newCdsConfig.
      LoadBalancerProvider fixedCdsConfigBalancer = new LoadBalancerProvider() {
        ClusterWatcherImpl clusterWatcher;

        @Override
        public boolean isAvailable() {
          return true;
        }

        @Override
        public int getPriority() {
          return 5;
        }

        /**
         * A synthetic policy name identified by CDS config. The implementation detail doesn't
         * matter.
         */
        @Override
        public String getPolicyName() {
          return "cds_policy__cluster_name_" + newCdsConfig.name;
        }

        @Override
        public LoadBalancer newLoadBalancer(final Helper helper) {
          return new LoadBalancer() {
            @Override
            public void handleNameResolutionError(Status error) {}

            @Override
            public void shutdown() {
              clusterWatcher.edsBalancer.shutdown();
            }

            @Override
            public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
              if (clusterWatcher == null) {
                clusterWatcher = new ClusterWatcherImpl(helper, resolvedAddresses);
                xdsClient.watchClusterData(newCdsConfig.name, clusterWatcher);
                if (oldCdsConfig != null) {
                  xdsClient.cancelClusterDataWatch(oldCdsConfig.name, oldClusterWatcher);
                }
                CdsLoadBalancer.this.clusterWatcher = clusterWatcher;
              }
            }
          };
        }
      };

      switchingLoadBalancer.switchTo(fixedCdsConfigBalancer);
    }

    switchingLoadBalancer.handleResolvedAddresses(resolvedAddresses);

    // clusterWatcher is also updated after switchingLoadBalancer.handleResolvedAddresses
    cdsConfig = newCdsConfig;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    channelLogger.log(ChannelLogLevel.DEBUG, "Name resolution error: '%s'", error);
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

  private final class ClusterWatcherImpl implements ClusterWatcher {

    final LoadBalancer edsBalancer;
    final Helper helper;
    final ResolvedAddresses resolvedAddresses;

    ClusterWatcherImpl(Helper helper, ResolvedAddresses resolvedAddresses) {
      this.helper = helper;
      this.resolvedAddresses = resolvedAddresses;
      this.edsBalancer = lbRegistry.getProvider("xds_experimental").newLoadBalancer(helper);
    }

    @Override
    public void onClusterChanged(ClusterUpdate update) {
      Preconditions.checkArgument(
          update.getLbPolicy().equals("round_robin"),
          "The lbPolicy in ClusterUpdate '%s' is not 'round_robin'", update);

      final XdsConfig edsConfig = new XdsConfig(
          /* balancerName = */ null,
          new LbConfig(update.getLbPolicy(), ImmutableMap.<String, Object>of()),
          /* fallbackPolicy = */ null,
          /* edsServiceName = */ update.getEdsServiceName(),
          /* lrsServerName = */ update.getLrsServerName());

      LoadStatsStore loadStatsStore = new LoadStatsStoreImpl();
      if (update.isEnableLrs()) {
        xdsClient.reportClientStats(
            update.getClusterName(), update.getLrsServerName(), loadStatsStore);
      }
      edsBalancer.handleResolvedAddresses(
          resolvedAddresses.toBuilder()
              .setAttributes(
                  resolvedAddresses.getAttributes().toBuilder()
                      .discard(ATTR_LOAD_BALANCING_CONFIG)
                      .set(XdsAttributes.LOAD_STATS_STORE_REF, loadStatsStore)
                      .build())
              .setLoadBalancingPolicyConfig(edsConfig)
          .build());
    }

    @Override
    public void onError(Status error) {
      helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
    }
  }

  static final class CdsConfig {

    /**
     * Name of cluster to query CDS for.
     */
    final String name;

    CdsConfig(String name) {
      Preconditions.checkArgument(name != null && !name.isEmpty(), "name is null or empty");
      this.name = name;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      CdsConfig cdsConfig = (CdsConfig) o;
      return Objects.equals(name, cdsConfig.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name);
    }
  }
}
