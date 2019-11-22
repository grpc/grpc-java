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
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
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

/** Lookaside load balancer that handles balancer name changes. */
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
    // In the future, xdsConfig can be gotten directly by
    // resolvedAddresses.getLoadBalancingPolicyConfig().
    Attributes attributes = resolvedAddresses.getAttributes();
    final ObjectPool<XdsClient> xdsClientRefFromResolver =
        resolvedAddresses.getAttributes().get(XdsAttributes.XDS_CLIENT_REF);
    if (xdsClientRefFromResolver != null) {
      cachedXdsClientRef = xdsClientRefFromResolver;
    }

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

    resolvedAddresses = resolvedAddresses.toBuilder().setAttributes(
            resolvedAddresses.getAttributes().toBuilder()
                .discard(ATTR_LOAD_BALANCING_CONFIG)
                .build())
        .build();
    switchingLoadBalancer.handleResolvedAddresses(resolvedAddresses);

    // The endpointWatcher is also updated after switchingLoadBalancer.handleResolvedAddresses().
    xdsConfig = newXdsConfig;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    channelLogger.log(ChannelLogLevel.DEBUG, "Name resolution error: '%s'", error);
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
  private class FixedXdsConfigBalancerProvider extends LoadBalancerProvider {
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

    // A synthetic policy name identified by xds config. The implementation detail doesn't
    // matter.
    @Override
    public String getPolicyName() {
      return "xds_policy__balancer_name_" + xdsConfig.balancerName
          + "__childPolicy_" + xdsConfig.childPolicy
          + "__fallbackPolicy_" + xdsConfig.fallbackPolicy
          + "__edsServiceName_" + xdsConfig.edsServiceName
          + "__lrsServerName_" + xdsConfig.lrsServerName;
    }

    @Override
    public LoadBalancer newLoadBalancer(final Helper helper) {
      return new LoadBalancer() {

        // All fields become non-null once handleResolvedAddresses() successfully.
        // All fields are assigned at most once .
        @Nullable
        ObjectPool<XdsClient> xdsClientRef;
        @Nullable
        XdsClient xdsClient;
        @Nullable
        LocalityStore localityStore;
        @Nullable
        LoadReportClient lrsClient;

        @Override
        public void handleNameResolutionError(Status error) {}

        @Override
        public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
          if (xdsClientRef != null) {
            return;
          }

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

          if (xdsConfig.balancerName != null) {
            // This to handle the legacy usecase that requires balancerName from xds config.

            String oldBalancerName = oldXdsConfig == null ? null : oldXdsConfig.balancerName;
            if (!xdsConfig.balancerName.equals(oldBalancerName)) {
              final ManagedChannel channel = initLbChannel(
                  helper, xdsConfig.balancerName, Collections.<ChannelCreds>emptyList());
              final Node node = Node.newBuilder()
                  .setMetadata(Struct.newBuilder()
                      .putFields(
                          "endpoints_required",
                          Value.newBuilder().setBoolValue(true).build()))
                  .build();
              // TODO(zdapeng): Use XdsClient to do Lrs directly.
              lrsClient = loadReportClientFactory.createLoadReportClient(
                  channel, helper, new ExponentialBackoffPolicy.Provider(), loadStatsStore);
              xdsClientRef = new RefCountedXdsClientObjectPool(new XdsClientFactory() {
                @Override
                XdsClient createXdsClient() {
                  return new XdsComms2(
                      channel, helper, new ExponentialBackoffPolicy.Provider(),
                      GrpcUtil.STOPWATCH_SUPPLIER, node);
                }
              });
            } else {
              lrsClient = cachedLrsClient;
              xdsClientRef = cachedXdsClientRef;
            }
          } else if (cachedXdsClientRef == null) {
            // This is to handle EDS-only with bootstrap usecase: the name resolver resolves
            // a ResolvedAddresses with an XdsConfig without balancerName field.
            final BootstrapInfo bootstrapInfo;
            try {
              bootstrapInfo = bootstrapper.readBootstrap();
            } catch (Exception e) {
              helper.updateBalancingState(
                  ConnectivityState.TRANSIENT_FAILURE,
                  new ErrorPicker(Status.UNAVAILABLE.withCause(e)));
              endpointWatcher = null;
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
                return new XdsComms2(
                    channel, helper, new ExponentialBackoffPolicy.Provider(),
                    GrpcUtil.STOPWATCH_SUPPLIER, bootstrapInfo.getNode());
              }
            });
          } else {
            // This is the XdsResolver or non EDS-only usecase.

            // FIXME(zdapeng): Use XdsClient to do Lrs directly.
            // No-op for now.
            lrsClient = new LoadReportClient() {
              @Override
              public void startLoadReporting(LoadReportCallback callback) {}

              @Override
              public void stopLoadReporting() {}
            };
            xdsClientRef = cachedXdsClientRef;
          }

          cachedLrsClient = lrsClient;
          cachedXdsClientRef = xdsClientRef;

          xdsClient = xdsClientRef.getObject();
          EndpointWatcher newEndpointWatcher =
              new EndpointWatcherImpl(lrsClient, lrsCallback, localityStore);
          xdsClient.watchEndpointData(xdsConfig.edsServiceName, newEndpointWatcher);
          if (oldEndpointWatcher != null) {
            xdsClient.cancelEndpointDataWatch(
                oldXdsConfig.edsServiceName, oldEndpointWatcher);
          }
          endpointWatcher = newEndpointWatcher;
        }

        @Override
        public void shutdown() {
          if (xdsClientRef != null) {
            lrsClient.stopLoadReporting();
            localityStore.reset();
            xdsClientRef.returnObject(xdsClient);
          }
        }
      };
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
      edsUpdateCallback.onError();
    }
  }
}
