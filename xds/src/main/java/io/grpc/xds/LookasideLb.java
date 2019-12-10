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
import static java.util.logging.Level.FINEST;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.alts.GoogleDefaultChannelBuilder;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.Bootstrapper.ChannelCreds;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.LoadReportClient.LoadReportCallback;
import io.grpc.xds.LoadReportClientImpl.LoadReportClientFactory;
import io.grpc.xds.LocalityStore.LocalityStoreFactory;
import io.grpc.xds.XdsClient.EndpointUpdate;
import io.grpc.xds.XdsClient.EndpointWatcher;
import io.grpc.xds.XdsClient.RefCountedXdsClientObjectPool;
import io.grpc.xds.XdsClient.XdsClientFactory;
import io.grpc.xds.XdsLoadBalancerProvider.XdsConfig;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/** Lookaside load balancer that handles EDS config. */
final class LookasideLb extends LoadBalancer {

  private final ChannelLogger channelLogger;
  private final EndpointUpdateCallback endpointUpdateCallback;
  private final GracefulSwitchLoadBalancer switchingLoadBalancer;
  private final LoadBalancerRegistry lbRegistry;
  private final LocalityStoreFactory localityStoreFactory;
  private final LoadReportClientFactory loadReportClientFactory;
  private final Bootstrapper bootstrapper;
  private final Helper lookasideLbHelper;

  // Most recent XdsConfig.
  @Nullable
  private XdsConfig xdsConfig;
  // Most recent EndpointWatcher.
  @Nullable
  private EndpointWatcher endpointWatcher;
  @Nullable
  private ObjectPool<XdsClient> xdsClientRef;
  @Nullable
  XdsClient xdsClient;
  // Only for EDS-only case.
  // TODO(zdapeng): Stop using it once XdsClientImpl is used.
  @Nullable
  ManagedChannel channel;

  LookasideLb(Helper lookasideLbHelper, EndpointUpdateCallback endpointUpdateCallback) {
    this(
        checkNotNull(lookasideLbHelper, "lookasideLbHelper"),
        checkNotNull(endpointUpdateCallback, "endpointUpdateCallback"),
        LoadBalancerRegistry.getDefaultRegistry(),
        LocalityStoreFactory.getInstance(),
        LoadReportClientFactory.getInstance(),
        Bootstrapper.getInstance());
  }

  @VisibleForTesting
  LookasideLb(
      Helper lookasideLbHelper,
      EndpointUpdateCallback endpointUpdateCallback,
      LoadBalancerRegistry lbRegistry,
      LocalityStoreFactory localityStoreFactory,
      LoadReportClientFactory loadReportClientFactory,
      Bootstrapper bootstrapper) {
    this.lookasideLbHelper = lookasideLbHelper;
    this.channelLogger = lookasideLbHelper.getChannelLogger();
    this.endpointUpdateCallback = endpointUpdateCallback;
    this.lbRegistry = lbRegistry;
    this.switchingLoadBalancer = new GracefulSwitchLoadBalancer(lookasideLbHelper);
    this.localityStoreFactory = localityStoreFactory;
    this.loadReportClientFactory = loadReportClientFactory;
    this.bootstrapper = bootstrapper;
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    channelLogger.log(ChannelLogLevel.DEBUG, "Received ResolvedAddresses '%s'", resolvedAddresses);

    Attributes attributes = resolvedAddresses.getAttributes();
    XdsConfig newXdsConfig;
    Object lbConfig = resolvedAddresses.getLoadBalancingPolicyConfig();
    if (lbConfig != null) {
      if (!(lbConfig instanceof XdsConfig)) {
        lookasideLbHelper.updateBalancingState(
            TRANSIENT_FAILURE,
            new ErrorPicker(Status.UNAVAILABLE.withDescription(
                "Load balancing config '" + lbConfig + "' is not an XdsConfig")));
        return;
      }
      newXdsConfig = (XdsConfig) lbConfig;
    } else {
      // In the future, in all cases xdsConfig can be gotten directly by
      // resolvedAddresses.getLoadBalancingPolicyConfig().
      Map<String, ?> newRawLbConfig = attributes.get(ATTR_LOAD_BALANCING_CONFIG);
      if (newRawLbConfig == null) {
        // This will not happen when the service config error handling is implemented.
        // For now simply go to TRANSIENT_FAILURE.
        lookasideLbHelper.updateBalancingState(
            TRANSIENT_FAILURE,
            new ErrorPicker(
                Status.UNAVAILABLE.withDescription("ATTR_LOAD_BALANCING_CONFIG not available")));
        return;
      }
      ConfigOrError cfg =
          XdsLoadBalancerProvider.parseLoadBalancingConfigPolicy(newRawLbConfig, lbRegistry);
      if (cfg.getError() != null) {
        // This will not happen when the service config error handling is implemented.
        // For now simply go to TRANSIENT_FAILURE.
        lookasideLbHelper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(cfg.getError()));
        return;
      }
      newXdsConfig = (XdsConfig) cfg.getConfig();
    }

    if (xdsClientRef == null) {
      // Init xdsClientRef and xdsClient.
      // There are two usecases:
      // 1. The EDS-only:
      //    The name resolver resolves a ResolvedAddresses with an XdsConfig. Use the bootstrap
      //    information to create a channel.
      // 2. Non EDS-only usecase:
      //    XDS_CLIENT_REF attribute is available from ResolvedAddresses either from
      //    XdsNameResolver or CDS policy.
      //
      // We assume XdsConfig switching happens only within one usecase, and there is no switching
      // between different usecases.

      xdsClientRef = attributes.get(XdsAttributes.XDS_CLIENT_REF);
      if (xdsClientRef == null) { // This is the EDS-only usecase.
        final BootstrapInfo bootstrapInfo;
        try {
          bootstrapInfo = bootstrapper.readBootstrap();
        } catch (Exception e) {
          lookasideLbHelper.updateBalancingState(
              TRANSIENT_FAILURE,
              new ErrorPicker(Status.UNAVAILABLE.withCause(e)));
          return;
        }

        List<ServerInfo> serverList = bootstrapInfo.getServers();
        if (serverList.isEmpty()) {
          lookasideLbHelper.updateBalancingState(
              TRANSIENT_FAILURE,
              new ErrorPicker(
                  Status.UNAVAILABLE
                      .withDescription("No traffic director provided by bootstrap")));
          return;
        }
        // Currently we only support using the first server from bootstrap.
        ServerInfo serverInfo = serverList.get(0);
        channel = initLbChannel(
            lookasideLbHelper, serverInfo.getServerUri(),
            serverInfo.getChannelCredentials());
        xdsClientRef = new RefCountedXdsClientObjectPool(new XdsClientFactory() {
          @Override
          XdsClient createXdsClient() {
            // TODO(zdapeng): Replace XdsComms2 with XdsClientImpl.
            return new XdsComms2(
                channel, lookasideLbHelper, new ExponentialBackoffPolicy.Provider(),
                GrpcUtil.STOPWATCH_SUPPLIER, bootstrapInfo.getNode());
          }
        });
      }
      xdsClient = xdsClientRef.getObject();
    }

    // Note: childPolicy change will be handled in LocalityStore, to be implemented.
    // If edsServiceName in XdsConfig is changed, do a graceful switch.
    if (xdsConfig == null
        || !Objects.equals(newXdsConfig.edsServiceName, xdsConfig.edsServiceName)) {
      String edsServiceName = newXdsConfig.edsServiceName;

      // The edsServiceName field is null in legacy gRPC client with EDS: use target authority for
      // querying endpoints, but in the future we expect this to be explicitly given by EDS config.
      // We assume if edsServiceName is null, it will always be null in later resolver updates;
      // and if edsServiceName is not null, it will always be not null.
      if (edsServiceName == null) {
        edsServiceName = lookasideLbHelper.getAuthority();
      }

      LoadBalancer.Factory clusterEndpointsLoadBalancerFactory =
          new ClusterEndpointsBalancerFactory(edsServiceName);
      switchingLoadBalancer.switchTo(clusterEndpointsLoadBalancerFactory);
    }
    resolvedAddresses = resolvedAddresses.toBuilder()
        .setAttributes(attributes.toBuilder().discard(ATTR_LOAD_BALANCING_CONFIG).build())
        .setLoadBalancingPolicyConfig(newXdsConfig)
        .build();
    switchingLoadBalancer.handleResolvedAddresses(resolvedAddresses);
    this.xdsConfig = newXdsConfig;

    // TODO(zdapeng): If lrsServerName in XdsConfig is changed, call xdsClient.reportClientStats()
    //     and/or xdsClient.cancelClientStatsReport().
  }

  @Override
  public void handleNameResolutionError(Status error) {
    channelLogger.log(ChannelLogLevel.ERROR, "Name resolution error: '%s'", error);
    // Go into TRANSIENT_FAILURE if we have not yet received any endpoint update. Otherwise,
    // we keep running with the data we had previously.
    if (endpointWatcher == null) {
      lookasideLbHelper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
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
    channelLogger.log(ChannelLogLevel.DEBUG, "EDS load balancer is shutting down");
    switchingLoadBalancer.shutdown();
    if (xdsClientRef != null) {
      xdsClientRef.returnObject(xdsClient);
    }
  }

  private static ManagedChannel initLbChannel(
      Helper helper,
      String serverUri,
      List<ChannelCreds> channelCredsList) {
    ManagedChannel channel = null;
    try {
      channel = helper.createResolvingOobChannel(serverUri);
    } catch (UnsupportedOperationException uoe) {
      // Temporary solution until createResolvingOobChannel is implemented.
      // FIXME (https://github.com/grpc/grpc-java/issues/5495)
      Logger logger = Logger.getLogger(LookasideLb.class.getName());
      if (logger.isLoggable(FINEST)) {
        logger.log(
            FINEST,
            "createResolvingOobChannel() not supported by the helper: " + helper,
            uoe);
        logger.log(FINEST, "creating oob channel for target {0}", serverUri);
      }

      // Use the first supported channel credentials configuration.
      // Currently, only "google_default" is supported.
      for (ChannelCreds creds : channelCredsList) {
        if (creds.getType().equals("google_default")) {
          channel = GoogleDefaultChannelBuilder.forTarget(serverUri).build();
          break;
        }
      }
      if (channel == null) {
        channel = ManagedChannelBuilder.forTarget(serverUri).build();
      }
    }
    return channel;
  }

  /**
   * A load balancer factory that provides a load balancer for a given cluster.
   */
  private final class ClusterEndpointsBalancerFactory extends LoadBalancer.Factory {
    final String edsServiceName;
    @Nullable
    final String oldEdsServiceName;
    @Nullable
    final EndpointWatcher oldEndpointWatcher;

    ClusterEndpointsBalancerFactory(String edsServiceName) {
      this.edsServiceName = edsServiceName;
      if (xdsConfig != null) {
        oldEdsServiceName = xdsConfig.edsServiceName;
      } else {
        oldEdsServiceName = null;
      }
      oldEndpointWatcher = endpointWatcher;
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
      return edsServiceName.equals(that.edsServiceName);
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), edsServiceName);
    }

    /**
     * Load-balances endpoints for a given cluster.
     */
    final class ClusterEndpointsBalancer extends LoadBalancer {
      final Helper helper;

      // All fields become non-null once handleResolvedAddresses() successfully.
      // All fields are assigned at most once.
      @Nullable
      LocalityStore localityStore;
      @Nullable
      LoadReportClient lrsClient;
      @Nullable
      EndpointWatcherImpl endpointWatcher;

      ClusterEndpointsBalancer(Helper helper) {
        this.helper = helper;
      }

      @Override
      public void handleNameResolutionError(Status error) {
        // Go into TRANSIENT_FAILURE if we have not yet received any endpoint update. Otherwise,
        // we keep running with the data we had previously.
        if (endpointWatcher == null || !endpointWatcher.firstEndpointUpdateReceived) {
          helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
        }
      }

      @Override
      public boolean canHandleEmptyAddressListFromNameResolution() {
        return true;
      }

      @Override
      public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
        if (endpointWatcher != null) {
          // TODO(zddapeng): Handle child policy changed if any.
          return;
        }

        LoadStatsStore loadStatsStore = new LoadStatsStoreImpl();
        localityStore = localityStoreFactory.newLocalityStore(helper, lbRegistry, loadStatsStore);
        LoadReportCallback lrsCallback =
            new LoadReportCallback() {
              @Override
              public void onReportResponse(long reportIntervalNano) {
                localityStore.updateOobMetricsReportInterval(reportIntervalNano);
              }
            };

        // TODO(zdapeng): Use XdsClient to do Lrs directly.
        // For now create an LRS Client.
        if (channel != null) {
          lrsClient =
              loadReportClientFactory.createLoadReportClient(
                  channel,
                  helper.getAuthority(),
                  Node.getDefaultInstance(),
                  helper.getSynchronizationContext(),
                  helper.getScheduledExecutorService(),
                  new ExponentialBackoffPolicy.Provider(),
                  GrpcUtil.STOPWATCH_SUPPLIER);
          lrsClient.addLoadStatsStore(edsServiceName, loadStatsStore);
        } else {
          lrsClient = new LoadReportClient() {
            @Override
            public void startLoadReporting(LoadReportCallback callback) {}

            @Override
            public void stopLoadReporting() {}

            @Override
            public void addLoadStatsStore(
                String clusterServiceName, LoadStatsStore loadStatsStore) {
            }

            @Override
            public void removeLoadStatsStore(String clusterServiceName) {
            }
          };
        }

        endpointWatcher = new EndpointWatcherImpl(lrsClient, lrsCallback, localityStore);
        xdsClient.watchEndpointData(edsServiceName, endpointWatcher);
        if (oldEndpointWatcher != null && oldEdsServiceName != null) {
          xdsClient.cancelEndpointDataWatch(oldEdsServiceName, oldEndpointWatcher);
        }
        LookasideLb.this.endpointWatcher = endpointWatcher;
      }

      @Override
      public void shutdown() {
        if (endpointWatcher != null) {
          lrsClient.stopLoadReporting();
          localityStore.reset();
          xdsClient.cancelEndpointDataWatch(edsServiceName, endpointWatcher);
        }
      }
    }
  }

  /**
   * Callbacks for the EDS-only-with-fallback usecase. Being deprecated.
   */
  interface EndpointUpdateCallback {

    void onWorking();

    void onError();

    void onAllDrop();
  }

  private final class EndpointWatcherImpl implements EndpointWatcher {

    final LoadReportClient lrsClient;
    final LoadReportCallback lrsCallback;
    final LocalityStore localityStore;
    boolean firstEndpointUpdateReceived;

    EndpointWatcherImpl(
        LoadReportClient lrsClient, LoadReportCallback lrsCallback, LocalityStore localityStore) {
      this.lrsClient = lrsClient;
      this.lrsCallback = lrsCallback;
      this.localityStore = localityStore;
    }

    @Override
    public void onEndpointChanged(EndpointUpdate endpointUpdate) {
      channelLogger.log(
          ChannelLogLevel.DEBUG,
          "EDS load balancer received an endpoint update: '%s'",
          endpointUpdate);

      if (!firstEndpointUpdateReceived) {
        firstEndpointUpdateReceived = true;
        endpointUpdateCallback.onWorking();
        lrsClient.startLoadReporting(lrsCallback);
      }

      List<DropOverload> dropOverloads = endpointUpdate.getDropPolicies();
      ImmutableList.Builder<DropOverload> dropOverloadsBuilder = ImmutableList.builder();
      for (DropOverload dropOverload : dropOverloads) {
        dropOverloadsBuilder.add(dropOverload);
        if (dropOverload.getDropsPerMillion() == 1_000_000) {
          endpointUpdateCallback.onAllDrop();
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
      channelLogger.log(
          ChannelLogLevel.ERROR, "EDS load balancer received an error: '%s'",  error);
      endpointUpdateCallback.onError();
    }
  }
}
