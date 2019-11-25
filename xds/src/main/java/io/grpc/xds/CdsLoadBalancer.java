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
import io.grpc.xds.XdsClient.ClusterUpdate;
import io.grpc.xds.XdsClient.ClusterWatcher;
import io.grpc.xds.XdsLoadBalancerProvider.XdsConfig;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.Map;
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
        throw new UnsupportedOperationException(
            "XDS_CLIENT_REF attributes not available in resolvedAddresses " + resolvedAddresses);
      }
      xdsClient = xdsClientRef.getObject();
    }

    Map<String, ?> newRawLbConfig = checkNotNull(
        attributes.get(ATTR_LOAD_BALANCING_CONFIG), "ATTR_LOAD_BALANCING_CONFIG not available");
    ConfigOrError cfg =
        CdsLoadBalancerProvider.parseLoadBalancingConfigPolicy(newRawLbConfig);
    if (cfg.getError() != null) {
      throw cfg.getError().asRuntimeException();
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
        public void handleNameResolutionError(Status error) {}

        @Override
        public boolean canHandleEmptyAddressListFromNameResolution() {
          return true;
        }

        @Override
        public void shutdown() {
          if (clusterWatcher != null && clusterWatcher.edsBalancer != null) {
            clusterWatcher.edsBalancer.shutdown();
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
          // Else handleResolvedAddresses() already called, so no-op here because the config is
          // fixed in this balancer.
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

    // LoadStatsStore for the cluster.
    // Becomes non-null once handleResolvedAddresses() successfully.
    // Assigned at most once.
    @Nullable
    LoadStatsStore loadStatsStore;

    ClusterWatcherImpl(Helper helper, ResolvedAddresses resolvedAddresses) {
      this.helper = helper;
      this.resolvedAddresses = resolvedAddresses;
    }

    @Override
    public void onClusterChanged(ClusterUpdate newUpdate) {
      channelLogger.log(ChannelLogLevel.DEBUG, "Received a CDS update: '%s'",  newUpdate);
      checkArgument(
          newUpdate.getLbPolicy().equals("round_robin"),
          "The load balancing policy in ClusterUpdate '%s' is not supported", newUpdate);

      final XdsConfig edsConfig = new XdsConfig(
          /* balancerName = */ null,
          new LbConfig(newUpdate.getLbPolicy(), ImmutableMap.<String, Object>of()),
          /* fallbackPolicy = */ null,
          /* edsServiceName = */ newUpdate.getEdsServiceName(),
          /* lrsServerName = */ newUpdate.getLrsServerName());

      if (loadStatsStore == null) {
        loadStatsStore = new LoadStatsStoreImpl();
      }
      if (edsBalancer == null) {
        edsBalancer = lbRegistry.getProvider(XDS_POLICY_NAME).newLoadBalancer(helper);
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
      channelLogger.log(ChannelLogLevel.ERROR, "Received a CDS error: '%s'",  error);

      // Go into TRANSIENT_FAILURE if we have not yet created the child
      // policy (i.e., we have not yet received valid data for the cluster). Otherwise,
      // we keep running with the data we had previously.
      if (edsBalancer == null) {
        helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
      }
    }
  }

  static final class CdsConfig {

    /**
     * Name of cluster to query CDS for.
     */
    final String name;

    CdsConfig(String name) {
      checkArgument(name != null && !name.isEmpty(), "name is null or empty");
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
