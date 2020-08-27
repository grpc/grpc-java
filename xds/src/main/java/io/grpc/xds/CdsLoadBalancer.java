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
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.xds.CdsLoadBalancerProvider.CdsConfig;
import io.grpc.xds.EdsLoadBalancerProvider.EdsConfig;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.XdsClient.ClusterUpdate;
import io.grpc.xds.XdsClient.ClusterWatcher;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import io.grpc.xds.internal.sds.SslContextProvider;
import io.grpc.xds.internal.sds.TlsContextManager;
import io.grpc.xds.internal.sds.TlsContextManagerImpl;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Load balancer for cds_experimental LB policy. One instance per cluster.
 */
final class CdsLoadBalancer extends LoadBalancer {
  // TODO(sanjaypujare): remove once xds security is released
  @VisibleForTesting
  static boolean enableSecurity =
      Boolean.parseBoolean(System.getenv("GRPC_XDS_EXPERIMENTAL_SECURITY_SUPPORT"));
  private final XdsLogger logger;
  private final LoadBalancer.Helper helper;
  private final LoadBalancerRegistry lbRegistry;
  private final TlsContextManager tlsContextManager;
  private String clusterName;
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private ChildLbState childLbState;

  CdsLoadBalancer(LoadBalancer.Helper helper) {
    this(helper, LoadBalancerRegistry.getDefaultRegistry(), TlsContextManagerImpl.getInstance());
  }

  @VisibleForTesting
  CdsLoadBalancer(LoadBalancer.Helper helper, LoadBalancerRegistry lbRegistry,
      TlsContextManager tlsContextManager) {
    this.helper = checkNotNull(helper, "helper");
    this.lbRegistry = lbRegistry;
    this.tlsContextManager = tlsContextManager;
    logger = XdsLogger.withLogId(InternalLogId.allocate("cds-lb", helper.getAuthority()));
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    if (clusterName != null) {
      return;
    }
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    xdsClientPool = resolvedAddresses.getAttributes().get(XdsAttributes.XDS_CLIENT_POOL);
    xdsClient = xdsClientPool.getObject();
    Object lbConfig = resolvedAddresses.getLoadBalancingPolicyConfig();
    CdsConfig newCdsConfig = (CdsConfig) lbConfig;
    logger.log(XdsLogLevel.INFO, "Received CDS lb config: cluster={0}", newCdsConfig.name);
    clusterName = newCdsConfig.name;
    childLbState = new ChildLbState();
    childLbState.start();
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
    if (childLbState != null) {
      childLbState.propagateError(error);
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
    if (childLbState != null) {
      childLbState.shutdown();
    }
    if (xdsClientPool != null) {
      xdsClientPool.returnObject(xdsClient);
    }
  }

  private final class ChannelSecurityLbHelper extends ForwardingLoadBalancerHelper {
    @Nullable
    private SslContextProvider sslContextProvider;

    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs createSubchannelArgs) {
      if (sslContextProvider != null) {
        createSubchannelArgs =
            createSubchannelArgs
                .toBuilder()
                .setAddresses(
                    addUpstreamTlsContext(createSubchannelArgs.getAddresses(),
                        sslContextProvider.getUpstreamTlsContext()))
                .build();
      }
      return delegate().createSubchannel(createSubchannelArgs);
    }

    private List<EquivalentAddressGroup> addUpstreamTlsContext(
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
      return helper;
    }
  }

  private final class ChildLbState implements ClusterWatcher {
    private final ChannelSecurityLbHelper lbHelper = new ChannelSecurityLbHelper();
    @Nullable
    private LoadBalancer lb;

    @Override
    public void onClusterChanged(ClusterUpdate update) {
      if (logger.isLoggable(XdsLogLevel.INFO)) {
        logger.log(XdsLogLevel.INFO, "Received cluster update from xDS client {0}: "
                + "cluster_name={1}, eds_service_name={2}, lb_policy={3}, report_load={4}",
            xdsClient, update.getClusterName(), update.getEdsServiceName(),
            update.getLbPolicy(), update.getLrsServerName() != null);
      }
      LoadBalancerProvider lbProvider = lbRegistry.getProvider(update.getLbPolicy());
      final EdsConfig edsConfig =
          new EdsConfig(update.getClusterName(), update.getEdsServiceName(),
              update.getLrsServerName(), new PolicySelection(lbProvider, null, null));
      if (enableSecurity) {
        updateSslContextProvider(update.getUpstreamTlsContext());
      }
      if (lb == null) {
        lb = lbRegistry.getProvider(XdsLbPolicies.EDS_POLICY_NAME).newLoadBalancer(lbHelper);
      }
      lb.handleResolvedAddresses(
          ResolvedAddresses.newBuilder()
              .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
              .setAttributes(
                  Attributes.newBuilder()
                      .set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
                      .build())
              .setLoadBalancingPolicyConfig(edsConfig)
              .build());
    }

    /** For new UpstreamTlsContext value, release old SslContextProvider. */
    private void updateSslContextProvider(UpstreamTlsContext newUpstreamTlsContext) {
      SslContextProvider oldSslContextProvider = lbHelper.sslContextProvider;
      if (oldSslContextProvider != null) {
        UpstreamTlsContext oldUpstreamTlsContext = oldSslContextProvider.getUpstreamTlsContext();
        if (oldUpstreamTlsContext.equals(newUpstreamTlsContext)) {
          return;
        }
        tlsContextManager.releaseClientSslContextProvider(oldSslContextProvider);
      }
      if (newUpstreamTlsContext != null) {
        lbHelper.sslContextProvider =
            tlsContextManager.findOrCreateClientSslContextProvider(newUpstreamTlsContext);
      } else {
        lbHelper.sslContextProvider = null;
      }
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      logger.log(XdsLogLevel.INFO, "Resource {0} is unavailable", resourceName);
      if (lb != null) {
        lb.shutdown();
        lb = null;
      }
      helper.updateBalancingState(
          TRANSIENT_FAILURE,
          new ErrorPicker(
              Status.UNAVAILABLE.withDescription("Resource " + resourceName + " is unavailable")));
    }

    @Override
    public void onError(Status error) {
      logger.log(XdsLogLevel.WARNING, "Received error from xDS client {0}: {1}", xdsClient, error);
      if (lb == null) {
        helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
      }
    }

    void start() {
      xdsClient.watchClusterData(clusterName, this);
      logger.log(XdsLogLevel.INFO,
          "Started watcher for cluster {0} with xDS client {1}", clusterName, xdsClient);
    }

    void shutdown() {
      xdsClient.cancelClusterDataWatch(clusterName, this);
      logger.log(XdsLogLevel.INFO,
          "Cancelled watcher for cluster {0} with xDS client {1}", clusterName, xdsClient);
      if (lb != null) {
        lb.shutdown();
      }
    }

    void propagateError(Status error) {
      if (lb != null) {
        lb.handleNameResolutionError(error);
      } else {
        helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
      }
    }
  }
}
