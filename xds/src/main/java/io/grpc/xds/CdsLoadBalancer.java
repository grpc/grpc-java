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
import static io.grpc.xds.EdsLoadBalancerProvider.EDS_POLICY_NAME;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.EquivalentAddressGroup;
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
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import io.grpc.xds.sds.SslContextProvider;
import io.grpc.xds.sds.TlsContextManager;
import io.grpc.xds.sds.TlsContextManagerImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Load balancer for cds_experimental LB policy.
 */
public final class CdsLoadBalancer extends LoadBalancer {
  private final ChannelLogger channelLogger;
  private final LoadBalancerRegistry lbRegistry;
  private final GracefulSwitchLoadBalancer switchingLoadBalancer;
  private final Helper helper;
  private final TlsContextManager tlsContextManager;

  // The following fields become non-null once handleResolvedAddresses() successfully.

  // Most recent CdsConfig.
  @Nullable
  private CdsConfig cdsConfig;
  // Most recent ClusterWatcher.
  @Nullable
  private ClusterWatcher clusterWatcher;
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
    this.helper = helper;
    this.channelLogger = helper.getChannelLogger();
    this.lbRegistry = lbRegistry;
    this.switchingLoadBalancer = new GracefulSwitchLoadBalancer(helper);
    this.tlsContextManager = tlsContextManager;
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    channelLogger.log(ChannelLogLevel.DEBUG, "Received ResolvedAddresses {0}", resolvedAddresses);
    if (xdsClientPool == null) {
      xdsClientPool = resolvedAddresses.getAttributes().get(XdsAttributes.XDS_CLIENT_POOL);
      if (xdsClientPool == null) {
        // TODO(zdapeng): create a new xdsClient from bootstrap if no one exists.
        helper.updateBalancingState(
            TRANSIENT_FAILURE,
            new ErrorPicker(Status.UNAVAILABLE.withDescription(
                "XDS_CLIENT_POOL attributes not available from resolve addresses")));
        return;
      }
      xdsClient = xdsClientPool.getObject();
    }

    Object lbConfig = resolvedAddresses.getLoadBalancingPolicyConfig();
    if (!(lbConfig instanceof CdsConfig)) {
      helper.updateBalancingState(
          TRANSIENT_FAILURE,
          new ErrorPicker(Status.UNAVAILABLE.withDescription(
              "Load balancing config '" + lbConfig + "' is not a CdsConfig")));
      return;
    }
    CdsConfig newCdsConfig = (CdsConfig) lbConfig;

    // If CdsConfig is changed, do a graceful switch.
    if (!newCdsConfig.equals(cdsConfig)) {
      LoadBalancer.Factory fixedCdsConfigBalancerFactory =
          new FixedCdsConfigBalancerFactory(newCdsConfig);
      switchingLoadBalancer.switchTo(fixedCdsConfigBalancerFactory);
    }

    switchingLoadBalancer.handleResolvedAddresses(resolvedAddresses);

    // The clusterWatcher is also updated after switchingLoadBalancer.handleResolvedAddresses().
    cdsConfig = newCdsConfig;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    channelLogger.log(ChannelLogLevel.ERROR, "Name resolution error: {0}", error);
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
    if (xdsClientPool != null) {
      xdsClientPool.returnObject(xdsClient);
    }
  }

  /**
   * A load balancer factory that provides a load balancer for a given CdsConfig.
   */
  private final class FixedCdsConfigBalancerFactory extends LoadBalancer.Factory {

    final CdsConfig cdsConfig;
    final CdsConfig oldCdsConfig;
    final ClusterWatcher oldClusterWatcher;

    FixedCdsConfigBalancerFactory(CdsConfig cdsConfig) {
      this.cdsConfig = cdsConfig;
      oldCdsConfig = CdsLoadBalancer.this.cdsConfig;
      oldClusterWatcher = CdsLoadBalancer.this.clusterWatcher;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof FixedCdsConfigBalancerFactory)) {
        return false;
      }
      FixedCdsConfigBalancerFactory that = (FixedCdsConfigBalancerFactory) o;
      return cdsConfig.equals(that.cdsConfig);
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), cdsConfig);
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
      channelLogger.log(
          ChannelLogLevel.DEBUG, "CDS load balancer received a cluster update: {0}",  newUpdate);
      checkArgument(
          newUpdate.getLbPolicy().equals("round_robin"),
          "The load balancing policy in ClusterUpdate '%s' is not supported", newUpdate);

      final XdsConfig edsConfig = new XdsConfig(
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
      channelLogger.log(ChannelLogLevel.ERROR, "CDS load balancer received an error: {0}",  error);

      // Go into TRANSIENT_FAILURE if we have not yet created the child
      // policy (i.e., we have not yet received valid data for the cluster). Otherwise,
      // we keep running with the data we had previously.
      if (edsBalancer == null) {
        helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
      }
    }
  }

}
