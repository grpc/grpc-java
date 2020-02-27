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
import static io.grpc.xds.EdsLoadBalancerProvider.EDS_POLICY_NAME;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.CdsLoadBalancerProvider.CdsConfig;
import io.grpc.xds.XdsClient.ClusterUpdate;
import io.grpc.xds.XdsClient.ClusterWatcher;
import io.grpc.xds.XdsLoadBalancerProvider.XdsConfig;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import io.grpc.xds.internal.sds.SslContextProvider;
import io.grpc.xds.internal.sds.TlsContextManager;
import io.grpc.xds.internal.sds.TlsContextManagerImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Load balancer for cds_experimental LB policy.
 */
public final class CdsLoadBalancer extends LoadBalancer {
  private final XdsLogger logger;
  private final LoadBalancerRegistry lbRegistry;
  private final GracefulSwitchLoadBalancer switchingLoadBalancer;
  private final TlsContextManager tlsContextManager;

  // The following fields become non-null once handleResolvedAddresses() successfully.

  // Most recent cluster name.
  @Nullable
  private String clusterName;
  @Nullable
  private ObjectPool<XdsClient> xdsClientPool;
  @Nullable
  private XdsClient xdsClient;

  CdsLoadBalancer(Helper helper) {
    this(helper, LoadBalancerRegistry.getDefaultRegistry(), TlsContextManagerImpl.getInstance());
  }

  @VisibleForTesting
  CdsLoadBalancer(Helper helper, LoadBalancerRegistry lbRegistry,
      TlsContextManager tlsContextManager) {
    checkNotNull(helper, "helper");
    this.lbRegistry = lbRegistry;
    this.switchingLoadBalancer = new GracefulSwitchLoadBalancer(helper);
    this.tlsContextManager = tlsContextManager;
    logger = XdsLogger.withLogId(InternalLogId.allocate("cds-lb", helper.getAuthority()));
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    if (xdsClientPool == null) {
      xdsClientPool = resolvedAddresses.getAttributes().get(XdsAttributes.XDS_CLIENT_POOL);
      checkNotNull(xdsClientPool, "missing xDS client pool");
      xdsClient = xdsClientPool.getObject();
    }

    Object lbConfig = resolvedAddresses.getLoadBalancingPolicyConfig();
    checkNotNull(lbConfig, "missing CDS lb config");
    CdsConfig newCdsConfig = (CdsConfig) lbConfig;
    logger.log(
        XdsLogLevel.INFO,
        "Received CDS lb config: cluster={0}", newCdsConfig.name);

    // If cluster is changed, do a graceful switch.
    if (!newCdsConfig.name.equals(clusterName)) {
      LoadBalancer.Factory clusterBalancerFactory = new ClusterBalancerFactory(newCdsConfig.name);
      switchingLoadBalancer.switchTo(clusterBalancerFactory);
    }
    switchingLoadBalancer.handleResolvedAddresses(resolvedAddresses);
    clusterName = newCdsConfig.name;
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

  /**
   * A load balancer factory that provides a load balancer for a given cluster.
   */
  private final class ClusterBalancerFactory extends LoadBalancer.Factory {

    final String clusterName;

    ClusterBalancerFactory(String clusterName) {
      this.clusterName = clusterName;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof ClusterBalancerFactory)) {
        return false;
      }
      ClusterBalancerFactory that = (ClusterBalancerFactory) o;
      return clusterName.equals(that.clusterName);
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), clusterName);
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
            xdsClient.cancelClusterDataWatch(clusterName, clusterWatcher);
            logger.log(
                XdsLogLevel.INFO,
                "Cancelled cluster watcher on {0} with xDS client {1}",
                clusterName, xdsClient);
          }
        }

        @Override
        public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
          if (clusterWatcher == null) {
            clusterWatcher = new ClusterWatcherImpl(helper, resolvedAddresses);
            logger.log(
                XdsLogLevel.INFO,
                "Start cluster watcher on {0} with xDS client {1}",
                clusterName, xdsClient);
            xdsClient.watchClusterData(clusterName, clusterWatcher);
          }
        }
      };
    }
  }

  private static final class EdsLoadBalancingHelper extends ForwardingLoadBalancerHelper {
    private final Helper delegate;
    private final AtomicReference<SslContextProvider<UpstreamTlsContext>> sslContextProvider;

    EdsLoadBalancingHelper(Helper helper,
        AtomicReference<SslContextProvider<UpstreamTlsContext>> sslContextProvider) {
      this.delegate = helper;
      this.sslContextProvider = sslContextProvider;
    }

    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs createSubchannelArgs) {
      if (sslContextProvider.get() != null) {
        createSubchannelArgs =
            createSubchannelArgs
                .toBuilder()
                .setAddresses(
                    addUpstreamTlsContext(createSubchannelArgs.getAddresses(),
                        sslContextProvider.get().getSource()))
                .build();
      }
      return delegate.createSubchannel(createSubchannelArgs);
    }

    private static List<EquivalentAddressGroup> addUpstreamTlsContext(
        List<EquivalentAddressGroup> addresses,
        UpstreamTlsContext upstreamTlsContext) {
      if (upstreamTlsContext == null || addresses == null) {
        return addresses;
      }
      ArrayList<EquivalentAddressGroup> copyList = new ArrayList<>(addresses.size());
      for (EquivalentAddressGroup eag : addresses) {
        EquivalentAddressGroup eagCopy =
            new EquivalentAddressGroup(eag.getAddresses(),
                eag.getAttributes()
                .toBuilder()
                .set(XdsAttributes.ATTR_UPSTREAM_TLS_CONTEXT, upstreamTlsContext)
                .build()
                );
        copyList.add(eagCopy);
      }
      return copyList;
    }

    @Override
    protected Helper delegate() {
      return delegate;
    }
  }

  private final class ClusterWatcherImpl implements ClusterWatcher {

    final EdsLoadBalancingHelper helper;
    final ResolvedAddresses resolvedAddresses;

    // EDS balancer for the cluster.
    // Becomes non-null once handleResolvedAddresses() successfully.
    // Assigned at most once.
    @Nullable
    LoadBalancer edsBalancer;

    ClusterWatcherImpl(Helper helper, ResolvedAddresses resolvedAddresses) {
      this.helper = new EdsLoadBalancingHelper(helper,
          new AtomicReference<SslContextProvider<UpstreamTlsContext>>());
      this.resolvedAddresses = resolvedAddresses;
    }

    @Override
    public void onClusterChanged(ClusterUpdate newUpdate) {
      if (logger.isLoggable(XdsLogLevel.INFO)) {
        logger.log(
            XdsLogLevel.INFO,
            "Received cluster update from xDS client {0}: "
                + "cluster_name={1}, eds_service_name={2}, lb_policy={3}, report_load={4}",
            xdsClient, newUpdate.getClusterName(), newUpdate.getEdsServiceName(),
            newUpdate.getLbPolicy(), newUpdate.getLrsServerName() != null);
      }
      checkArgument(
          newUpdate.getLbPolicy().equals("round_robin"), "can only support round_robin policy");

      final XdsConfig edsConfig =
          new XdsConfig(
              /* cluster = */ newUpdate.getClusterName(),
              new LbConfig(newUpdate.getLbPolicy(), ImmutableMap.<String, Object>of()),
              /* fallbackPolicy = */ null,
              /* edsServiceName = */ newUpdate.getEdsServiceName(),
              /* lrsServerName = */ newUpdate.getLrsServerName());
      updateSslContextProvider(newUpdate.getUpstreamTlsContext());
      if (edsBalancer == null) {
        edsBalancer = lbRegistry.getProvider(EDS_POLICY_NAME).newLoadBalancer(helper);
      }
      edsBalancer.handleResolvedAddresses(
          resolvedAddresses.toBuilder().setLoadBalancingPolicyConfig(edsConfig).build());
    }

    /** For new UpstreamTlsContext value, release old SslContextProvider. */
    private void updateSslContextProvider(UpstreamTlsContext newUpstreamTlsContext) {
      SslContextProvider<UpstreamTlsContext> oldSslContextProvider =
          helper.sslContextProvider.get();
      if (oldSslContextProvider != null) {
        UpstreamTlsContext oldUpstreamTlsContext = oldSslContextProvider.getSource();

        if (oldUpstreamTlsContext.equals(newUpstreamTlsContext)) {
          return;
        }
        tlsContextManager.releaseClientSslContextProvider(oldSslContextProvider);
      }
      if (newUpstreamTlsContext != null) {
        SslContextProvider<UpstreamTlsContext> newSslContextProvider =
            tlsContextManager.findOrCreateClientSslContextProvider(newUpstreamTlsContext);
        helper.sslContextProvider.set(newSslContextProvider);
      } else {
        helper.sslContextProvider.set(null);
      }
    }

    @Override
    public void onError(Status error) {
      logger.log(
          XdsLogLevel.WARNING,
          "Received error from xDS client {0}: {1}: {2}",
          xdsClient,
          error.getCode(),
          error.getDescription());

      // Go into TRANSIENT_FAILURE if we have not yet created the child
      // policy (i.e., we have not yet received valid data for the cluster). Otherwise,
      // we keep running with the data we had previously.
      if (edsBalancer == null) {
        helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
      }
    }
  }

}
