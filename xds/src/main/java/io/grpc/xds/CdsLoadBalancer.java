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
import static io.grpc.xds.XdsLbPolicies.EDS_POLICY_NAME;
import static io.grpc.xds.XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME;

import com.google.common.annotations.VisibleForTesting;
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
import io.grpc.xds.XdsClient.CdsResourceWatcher;
import io.grpc.xds.XdsClient.CdsUpdate;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import io.grpc.xds.internal.sds.SslContextProviderSupplier;
import io.grpc.xds.internal.sds.TlsContextManager;
import io.grpc.xds.internal.sds.TlsContextManagerImpl;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Load balancer for cds_experimental LB policy. One instance per cluster.
 */
final class CdsLoadBalancer extends LoadBalancer {
  private final XdsLogger logger;
  private final Helper helper;
  private final LoadBalancerRegistry lbRegistry;
  private final TlsContextManager tlsContextManager;
  // TODO(sanjaypujare): remove once xds security is released
  private boolean enableXdsSecurity;
  private static final String XDS_SECURITY_ENV_VAR = "GRPC_XDS_EXPERIMENTAL_SECURITY_SUPPORT";
  private String clusterName;
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private CdsLbState cdsLbState;
  private ResolvedAddresses resolvedAddresses;

  CdsLoadBalancer(Helper helper) {
    this(helper, LoadBalancerRegistry.getDefaultRegistry(), TlsContextManagerImpl.getInstance());
  }

  @VisibleForTesting
  CdsLoadBalancer(Helper helper, LoadBalancerRegistry lbRegistry,
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

  // TODO(sanjaypujare): remove once xDS security is released
  private boolean isXdsSecurityEnabled() {
    return enableXdsSecurity || Boolean.valueOf(System.getenv(XDS_SECURITY_ENV_VAR));
  }

  // TODO(sanjaypujare): remove once xDS security is released
  @VisibleForTesting
  void setXdsSecurity(boolean enable) {
    enableXdsSecurity = enable;
  }

  private final class ChannelSecurityLbHelper extends ForwardingLoadBalancerHelper {
    @Nullable
    private SslContextProviderSupplier sslContextProviderSupplier;

    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs createSubchannelArgs) {
      if (sslContextProviderSupplier != null) {
        createSubchannelArgs =
            createSubchannelArgs
                .toBuilder()
                .setAddresses(
                    addSslContextProviderSupplier(createSubchannelArgs.getAddresses(),
                        sslContextProviderSupplier))
                .build();
      }
      return delegate().createSubchannel(createSubchannelArgs);
    }

    private List<EquivalentAddressGroup> addSslContextProviderSupplier(
        List<EquivalentAddressGroup> addresses,
        SslContextProviderSupplier supplier) {
      if (supplier == null || addresses == null) {
        return addresses;
      }
      ArrayList<EquivalentAddressGroup> copyList = new ArrayList<>(addresses.size());
      for (EquivalentAddressGroup eag : addresses) {
        EquivalentAddressGroup eagCopy =
            new EquivalentAddressGroup(eag.getAddresses(),
                eag.getAttributes()
                    .toBuilder()
                    .set(XdsAttributes.ATTR_SSL_CONTEXT_PROVIDER_SUPPLIER, supplier)
                    .build());
        copyList.add(eagCopy);
      }
      return copyList;
    }

    @Override
    protected Helper delegate() {
      return helper;
    }
  }

  private final class CdsLbState implements CdsResourceWatcher {
    private final ChannelSecurityLbHelper lbHelper = new ChannelSecurityLbHelper();
    @Nullable
    LoadBalancer edsBalancer;

    private CdsLbState() {
      xdsClient.watchCdsResource(clusterName, this);
      logger.log(XdsLogLevel.INFO,
          "Started watcher for cluster {0} with xDS client {1}", clusterName, xdsClient);
    }

    @Override
    public void onChanged(CdsUpdate newUpdate) {
      if (logger.isLoggable(XdsLogLevel.INFO)) {
        logger.log(
            XdsLogLevel.INFO,
            "Received cluster update from xDS client {0}: "
                + "cluster_name={1}, eds_service_name={2}, lb_policy={3}, report_load={4}",
            xdsClient, newUpdate.getClusterName(), newUpdate.getEdsServiceName(),
            newUpdate.getLbPolicy(), newUpdate.getLrsServerName() != null);
      }
      // FIXME(chengyuanzhang): handle error correctly to avoid being unnecessarily fragile.
      checkArgument(
          newUpdate.getLbPolicy().equals("round_robin"), "can only support round_robin policy");
      LoadBalancerProvider endpointPickingPolicyProvider =
          lbRegistry.getProvider(newUpdate.getLbPolicy());
      LoadBalancerProvider localityPickingPolicyProvider =
          lbRegistry.getProvider(WEIGHTED_TARGET_POLICY_NAME);  // hardcode to weighted-target
      final EdsConfig edsConfig =
          new EdsConfig(
              /* clusterName = */ newUpdate.getClusterName(),
              /* edsServiceName = */ newUpdate.getEdsServiceName(),
              /* lrsServerName = */ newUpdate.getLrsServerName(),
              new PolicySelection(localityPickingPolicyProvider, null /* by EDS policy */),
              new PolicySelection(endpointPickingPolicyProvider, null));
      if (isXdsSecurityEnabled()) {
        updateSslContextProviderSupplier(newUpdate.getUpstreamTlsContext());
      }
      if (edsBalancer == null) {
        edsBalancer = lbRegistry.getProvider(EDS_POLICY_NAME).newLoadBalancer(lbHelper);
      }
      edsBalancer.handleResolvedAddresses(
          resolvedAddresses.toBuilder().setLoadBalancingPolicyConfig(edsConfig).build());
    }

    /** For new UpstreamTlsContext value, release old SslContextProvider. */
    private void updateSslContextProviderSupplier(UpstreamTlsContext newUpstreamTlsContext) {
      SslContextProviderSupplier oldSslContextProviderSupplier =
          lbHelper.sslContextProviderSupplier;
      if (oldSslContextProviderSupplier != null) {
        UpstreamTlsContext oldUpstreamTlsContext =
            oldSslContextProviderSupplier.getUpstreamTlsContext();

        if (oldUpstreamTlsContext.equals(newUpstreamTlsContext)) {
          return;
        }
        oldSslContextProviderSupplier.close();
      }
      if (newUpstreamTlsContext != null) {
        lbHelper.sslContextProviderSupplier =
            new SslContextProviderSupplier(newUpstreamTlsContext, tlsContextManager);
      } else {
        lbHelper.sslContextProviderSupplier = null;
      }
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      logger.log(XdsLogLevel.INFO, "Resource {0} is unavailable", resourceName);
      if (edsBalancer != null) {
        edsBalancer.shutdown();
        edsBalancer = null;
      }
      helper.updateBalancingState(
          TRANSIENT_FAILURE,
          new ErrorPicker(
              Status.UNAVAILABLE.withDescription("Resource " + resourceName + " is unavailable")));
    }

    @Override
    public void onError(Status error) {
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

    void shutdown() {
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
