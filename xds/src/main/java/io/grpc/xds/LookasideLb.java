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
import static java.util.logging.Level.FINEST;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/** Lookaside load balancer that handles EDS config. */
final class LookasideLb extends LoadBalancer {

  private final ChannelLogger channelLogger;
  private final EdsUpdateCallback edsUpdateCallback;
  private final GracefulSwitchLoadBalancer switchingLoadBalancer;
  private final LoadBalancerRegistry lbRegistry;
  private final LocalityStoreFactory localityStoreFactory;
  private final LoadReportClientFactory loadReportClientFactory;
  private final Bootstrapper bootstrapper;

  // Most recent XdsConfig.
  // Becomes non-null once handleResolvedAddresses() successfully.
  @Nullable
  private XdsConfig xdsConfig;
  // Most recent EndpointWatcher.
  // Becomes non-null once handleResolvedAddresses() successfully.
  @Nullable
  private EndpointWatcher endpointWatcher;

  @Nullable
  private ObjectPool<XdsClient> cachedXdsClientRef; // Cached for optimization.
  @Nullable
  private LoadReportClient cachedLrsClient; // Cached for optimization.

  LookasideLb(Helper lookasideLbHelper, EdsUpdateCallback edsUpdateCallback) {
    this(
        lookasideLbHelper,
        edsUpdateCallback,
        LoadBalancerRegistry.getDefaultRegistry(),
        LocalityStoreFactory.getInstance(),
        LoadReportClientFactory.getInstance(),
        Bootstrapper.getInstance());
  }

  @VisibleForTesting
  LookasideLb(
      Helper lookasideLbHelper,
      EdsUpdateCallback edsUpdateCallback,
      LoadBalancerRegistry lbRegistry,
      LocalityStoreFactory localityStoreFactory,
      LoadReportClientFactory loadReportClientFactory,
      Bootstrapper bootstrapper) {
    this.channelLogger = lookasideLbHelper.getChannelLogger();
    this.edsUpdateCallback = edsUpdateCallback;
    this.lbRegistry = lbRegistry;
    this.switchingLoadBalancer = new GracefulSwitchLoadBalancer(lookasideLbHelper);
    this.localityStoreFactory = localityStoreFactory;
    this.loadReportClientFactory = loadReportClientFactory;
    this.bootstrapper = bootstrapper;
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    channelLogger.log(ChannelLogLevel.DEBUG, "Received ResolvedAddresses '%s'", resolvedAddresses);

    // In the future, xdsConfig can be gotten directly by
    // resolvedAddresses.getLoadBalancingPolicyConfig().
    Attributes attributes = resolvedAddresses.getAttributes();
    Map<String, ?> newRawLbConfig = checkNotNull(
        attributes.get(ATTR_LOAD_BALANCING_CONFIG), "ATTR_LOAD_BALANCING_CONFIG not available");
    ConfigOrError cfg =
        XdsLoadBalancerProvider.parseLoadBalancingConfigPolicy(newRawLbConfig, lbRegistry);
    if (cfg.getError() != null) {
      throw cfg.getError().asRuntimeException();
    }
    final XdsConfig newXdsConfig = (XdsConfig) cfg.getConfig();

    // If XdsConfig is changed, do a graceful switch.
    if (!newXdsConfig.equals(xdsConfig)) {
      LoadBalancerProvider fixedXdsConfigBalancerProvider =
          new FixedXdsConfigBalancerProvider(newXdsConfig);
      switchingLoadBalancer.switchTo(fixedXdsConfigBalancerProvider);
    }

    resolvedAddresses = resolvedAddresses.toBuilder()
        .setAttributes(attributes.toBuilder().discard(ATTR_LOAD_BALANCING_CONFIG).build())
        .build();
    switchingLoadBalancer.handleResolvedAddresses(resolvedAddresses);

    // The endpointWatcher is also updated after switchingLoadBalancer.handleResolvedAddresses().
    xdsConfig = newXdsConfig;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    channelLogger.log(ChannelLogLevel.ERROR, "Name resolution error: '%s'", error);
    switchingLoadBalancer.handleNameResolutionError(error);
  }

  @Override
  public boolean canHandleEmptyAddressListFromNameResolution() {
    return true;
  }

  @Override
  public void shutdown() {
    channelLogger.log(ChannelLogLevel.DEBUG, "EDS load balancer is shutting down");
    switchingLoadBalancer.shutdown();
  }

  private static ManagedChannel initLbChannel(
      Helper helper,
      String balancerName,
      List<ChannelCreds> channelCredsList) {
    ManagedChannel channel = null;
    try {
      channel = helper.createResolvingOobChannel(balancerName);
    } catch (UnsupportedOperationException uoe) {
      // Temporary solution until createResolvingOobChannel is implemented.
      // FIXME (https://github.com/grpc/grpc-java/issues/5495)
      Logger logger = Logger.getLogger(LookasideLb.class.getName());
      if (logger.isLoggable(FINEST)) {
        logger.log(
            FINEST,
            "createResolvingOobChannel() not supported by the helper: " + helper,
            uoe);
        logger.log(FINEST, "creating oob channel for target {0}", balancerName);
      }

      // Use the first supported channel credentials configuration.
      // Currently, only "google_default" is supported.
      for (ChannelCreds creds : channelCredsList) {
        if (creds.getType().equals("google_default")) {
          channel = GoogleDefaultChannelBuilder.forTarget(balancerName).build();
          break;
        }
      }
      if (channel == null) {
        channel = ManagedChannelBuilder.forTarget(balancerName).build();
      }
    }
    return channel;
  }

  /**
   * A LoadBalancerProvider that provides a load balancer with a fixed XdsConfig.
   */
  private final class FixedXdsConfigBalancerProvider extends LoadBalancerProvider {
    final XdsConfig xdsConfig;
    final XdsConfig oldXdsConfig;
    final EndpointWatcher oldEndpointWatcher;

    FixedXdsConfigBalancerProvider(XdsConfig xdsConfig) {
      this.xdsConfig = xdsConfig;
      oldXdsConfig = LookasideLb.this.xdsConfig;
      oldEndpointWatcher = endpointWatcher;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 5;
    }

    // A synthetic policy name identified by xds config.
    @Override
    public String getPolicyName() {
      return "xds_policy__balancer_name_" + xdsConfig.balancerName
          + "__childPolicy_" + xdsConfig.childPolicy
          + "__fallbackPolicy_" + xdsConfig.fallbackPolicy
          + "__edsServiceName_" + xdsConfig.edsServiceName
          + "__lrsServerName_" + xdsConfig.lrsServerName;
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      return new FixedXdsConfigBalancer(helper);
    }

    final class FixedXdsConfigBalancer extends LoadBalancer {
      final Helper helper;

      // All fields become non-null once handleResolvedAddresses() successfully.
      // All fields are assigned at most once.
      @Nullable
      ObjectPool<XdsClient> xdsClientRef;
      @Nullable
      XdsClient xdsClient;
      @Nullable
      LocalityStore localityStore;
      @Nullable
      LoadReportClient lrsClient;
      @Nullable
      EndpointWatcher endpointWatcher;

      FixedXdsConfigBalancer(Helper helper) {
        this.helper = helper;
      }

      @Override
      public void handleNameResolutionError(Status error) {}

      @Override
      public boolean canHandleEmptyAddressListFromNameResolution() {
        return true;
      }

      @Override
      public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
        if (xdsClientRef != null) {
          // handleResolvedAddresses() already called, so no-op here, because the config is fixed in
          // this balancer.
          return;
        }

        // TODO(zdapeng): First try to get loadStatsStore from XdsAttributes.LOAD_STATS_STORE_REF.
        LoadStatsStore loadStatsStore = new LoadStatsStoreImpl();
        localityStore = localityStoreFactory.newLocalityStore(
            helper, lbRegistry, loadStatsStore);
        // TODO(zdapeng): Use XdsClient to do Lrs directly.
        final LoadReportCallback lrsCallback =
            new LoadReportCallback() {
              @Override
              public void onReportResponse(long reportIntervalNano) {
                localityStore.updateOobMetricsReportInterval(reportIntervalNano);
              }
            };

        // There are three usecases:
        // 1. The EDS-only legacy usecase that requires balancerName from xds config.
        //    TODO(zdapeng): Remove the legacy case.
        // 2. The EDS-only with bootstrap usecase:
        //    The name resolver resolves a ResolvedAddresses with an XdsConfig without balancerName
        //    field. Use the bootstrap information to create a channel.
        // 3. Non EDS-only usecase:
        //    XDS_CLIENT_REF attribute is available from ResolvedAddresses either from
        //    XdsNameResolver or CDS policy.
        // We assume XdsConfig switching happens only within one usecase, and there is no switching
        // between different usecases.
        if (xdsConfig.balancerName != null) {
          // This is the EDS-only legacy usecase that requires balancerName from xds config.

          if (oldXdsConfig == null || !xdsConfig.balancerName.equals(oldXdsConfig.balancerName)) {
            final ManagedChannel channel = initLbChannel(
                helper, xdsConfig.balancerName, Collections.<ChannelCreds>emptyList());
            // TODO(zdapeng): Use XdsClient to do Lrs directly.
            lrsClient = loadReportClientFactory.createLoadReportClient(
                channel, helper, new ExponentialBackoffPolicy.Provider(), loadStatsStore);
            xdsClientRef = new RefCountedXdsClientObjectPool(new XdsClientFactory() {
              @Override
              XdsClient createXdsClient() {
                return new XdsComms2(
                    channel, helper, new ExponentialBackoffPolicy.Provider(),
                    GrpcUtil.STOPWATCH_SUPPLIER, Node.getDefaultInstance());
              }
            });
          } else {
            // The balancerName is unchanged, no need to create new channel.
            // Use the cached lrsClient and xdsClientRef, which are using the existing channel.
            lrsClient = cachedLrsClient;
            xdsClientRef = cachedXdsClientRef;
          }
        } else {
          // This is either the EDS-only with bootstrap usecase or non EDS-only usecase.
          if (cachedXdsClientRef != null) {
            xdsClientRef = cachedXdsClientRef;
            lrsClient = cachedLrsClient;
          } else {
            Attributes attributes = resolvedAddresses.getAttributes();
            final ObjectPool<XdsClient> xdsClientRefFromResolver =
                attributes.get(XdsAttributes.XDS_CLIENT_REF);
            if (xdsClientRefFromResolver != null) {
              // This is the Non EDS-only usecase.
              xdsClientRef = xdsClientRefFromResolver;
              // FIXME(zdapeng): Use XdsClient to do Lrs directly.
              // No-op for now.
              lrsClient = new LoadReportClient() {
                @Override
                public void startLoadReporting(LoadReportCallback callback) {
                }

                @Override
                public void stopLoadReporting() {
                }
              };
            } else {
              // This is the EDS-only with bootstrap usecase.
              final BootstrapInfo bootstrapInfo;
              try {
                bootstrapInfo = bootstrapper.readBootstrap();
              } catch (Exception e) {
                helper.updateBalancingState(
                    ConnectivityState.TRANSIENT_FAILURE,
                    new ErrorPicker(Status.UNAVAILABLE.withCause(e)));
                LookasideLb.this.endpointWatcher = null;
                return;
              }
              final ManagedChannel channel = initLbChannel(
                  helper, bootstrapInfo.getServerUri(),
                  bootstrapInfo.getChannelCredentials());
              // TODO(zdapeng): Use XdsClient to do Lrs directly.
              lrsClient = loadReportClientFactory.createLoadReportClient(
                  channel, helper, new ExponentialBackoffPolicy.Provider(), loadStatsStore);
              xdsClientRef = new RefCountedXdsClientObjectPool(new XdsClientFactory() {
                @Override
                XdsClient createXdsClient() {
                  // TODO(zdapeng): Replace XdsComms2 with XdsClientImpl.
                  return new XdsComms2(
                      channel, helper, new ExponentialBackoffPolicy.Provider(),
                      GrpcUtil.STOPWATCH_SUPPLIER, bootstrapInfo.getNode());
                }
              });
            }
          }
        }

        // At this point the lrsClient and xdsClientRef are assigned in all usecases, cache them for
        // later use.
        cachedLrsClient = lrsClient;
        cachedXdsClientRef = xdsClientRef;

        xdsClient = xdsClientRef.getObject();
        // TODO(zdapeng): Call xdsClient.reportClientStats() and xdsClient.cancelClientStatsReport()
        // based on if lrsServerName is null instead of using lrsClient.
        endpointWatcher = new EndpointWatcherImpl(lrsClient, lrsCallback, localityStore);
        xdsClient.watchEndpointData(xdsConfig.edsServiceName, endpointWatcher);
        if (oldEndpointWatcher != null) {
          xdsClient.cancelEndpointDataWatch(oldXdsConfig.edsServiceName, oldEndpointWatcher);
        }
        LookasideLb.this.endpointWatcher = endpointWatcher;
      }

      @Override
      public void shutdown() {
        if (xdsClientRef != null) {
          lrsClient.stopLoadReporting();
          localityStore.reset();
          xdsClient.cancelEndpointDataWatch(xdsConfig.edsServiceName, endpointWatcher);
          xdsClientRef.returnObject(xdsClient);
        }
      }
    }
  }

  /**
   * Callbacks for the EDS-only-with-fallback usecase. Being deprecated.
   */
  interface EdsUpdateCallback {

    void onWorking();

    void onError();

    void onAllDrop();
  }

  private final class EndpointWatcherImpl implements EndpointWatcher {

    final LoadReportClient lrsClient;
    final LoadReportCallback lrsCallback;
    final LocalityStore localityStore;
    boolean firstEdsUpdateReceived;

    EndpointWatcherImpl(
        LoadReportClient lrsClient, LoadReportCallback lrsCallback, LocalityStore localityStore) {
      this.lrsClient = lrsClient;
      this.lrsCallback = lrsCallback;
      this.localityStore = localityStore;
    }

    @Override
    public void onEndpointChanged(EndpointUpdate endpointUpdate) {
      channelLogger.log(ChannelLogLevel.DEBUG, "Received an EDS update: '%s'",  endpointUpdate);

      if (!firstEdsUpdateReceived) {
        firstEdsUpdateReceived = true;
        edsUpdateCallback.onWorking();
        lrsClient.startLoadReporting(lrsCallback);
      }

      List<DropOverload> dropOverloads = endpointUpdate.getDropPolicies();
      ImmutableList.Builder<DropOverload> dropOverloadsBuilder = ImmutableList.builder();
      for (DropOverload dropOverload : dropOverloads) {
        dropOverloadsBuilder.add(dropOverload);
        if (dropOverload.getDropsPerMillion() == 1_000_000) {
          edsUpdateCallback.onAllDrop();
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
      channelLogger.log(ChannelLogLevel.ERROR, "Received an EDS error: '%s'",  error);
      edsUpdateCallback.onError();
    }
  }
}
