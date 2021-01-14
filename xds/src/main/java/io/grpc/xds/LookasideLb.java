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
import static io.grpc.xds.XdsNameResolver.XDS_CHANNEL_CREDS_LIST;
import static io.grpc.xds.XdsNameResolver.XDS_NODE;
import static java.util.logging.Level.FINEST;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.Attributes;
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
import io.grpc.util.ForwardingLoadBalancer;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.Bootstrapper.ChannelCreds;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.LoadReportClient.LoadReportCallback;
import io.grpc.xds.LoadReportClientImpl.LoadReportClientFactory;
import io.grpc.xds.LocalityStore.LocalityStoreFactory;
import io.grpc.xds.XdsClient.EndpointUpdate;
import io.grpc.xds.XdsClient.EndpointWatcher;
import io.grpc.xds.XdsLoadBalancerProvider.XdsConfig;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/** Lookaside load balancer that handles balancer name changes. */
final class LookasideLb extends ForwardingLoadBalancer {

  private final EdsUpdateCallback edsUpdateCallback;
  private final GracefulSwitchLoadBalancer lookasideChannelLb;
  private final LoadBalancerRegistry lbRegistry;
  private final LocalityStoreFactory localityStoreFactory;
  private final LoadReportClientFactory loadReportClientFactory;

  private String balancerName;

  LookasideLb(Helper lookasideLbHelper, EdsUpdateCallback edsUpdateCallback) {
    this(
        lookasideLbHelper,
        edsUpdateCallback,
        LoadBalancerRegistry.getDefaultRegistry(),
        LocalityStoreFactory.getInstance(),
        LoadReportClientFactory.getInstance());
  }

  @VisibleForTesting
  LookasideLb(
      Helper lookasideLbHelper,
      EdsUpdateCallback edsUpdateCallback,
      LoadBalancerRegistry lbRegistry,
      LocalityStoreFactory localityStoreFactory,
      LoadReportClientFactory loadReportClientFactory) {
    this.edsUpdateCallback = edsUpdateCallback;
    this.lbRegistry = lbRegistry;
    this.lookasideChannelLb = new GracefulSwitchLoadBalancer(lookasideLbHelper);
    this.localityStoreFactory = localityStoreFactory;
    this.loadReportClientFactory = loadReportClientFactory;
  }

  @Override
  protected LoadBalancer delegate() {
    return lookasideChannelLb;
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    // In the future, xdsConfig can be gotten directly by
    // resolvedAddresses.getLoadBalancingPolicyConfig()
    Attributes attributes = resolvedAddresses.getAttributes();
    Map<String, ?> newRawLbConfig = checkNotNull(
        attributes.get(ATTR_LOAD_BALANCING_CONFIG), "ATTR_LOAD_BALANCING_CONFIG not available");
    ConfigOrError cfg =
        XdsLoadBalancerProvider.parseLoadBalancingConfigPolicy(newRawLbConfig, lbRegistry);
    if (cfg.getError() != null) {
      throw cfg.getError().asRuntimeException();
    }
    XdsConfig xdsConfig = (XdsConfig) cfg.getConfig();

    final String newBalancerName = xdsConfig.balancerName;

    // The is to handle the legacy usecase that requires balancerName from xds config.
    if (!newBalancerName.equals(balancerName)) {
      balancerName = newBalancerName; // cache the name and check next time for optimization
      Node nodeFromResolvedAddresses = resolvedAddresses.getAttributes().get(XDS_NODE);
      final Node node;
      if (nodeFromResolvedAddresses == null) {
        node = Node.newBuilder()
            .setMetadata(Struct.newBuilder()
                .putFields(
                    "endpoints_required",
                    Value.newBuilder().setBoolValue(true).build()))
            .build();
      } else {
        node = nodeFromResolvedAddresses;
      }
      List<ChannelCreds> channelCredsListFromResolvedAddresses =
          resolvedAddresses.getAttributes().get(XDS_CHANNEL_CREDS_LIST);
      final List<ChannelCreds> channelCredsList;
      if (channelCredsListFromResolvedAddresses == null) {
        channelCredsList = Collections.emptyList();
      } else {
        channelCredsList = channelCredsListFromResolvedAddresses;
      }

      LoadBalancerProvider childBalancerProvider = new LoadBalancerProvider() {
        @Override
        public boolean isAvailable() {
          return true;
        }

        @Override
        public int getPriority() {
          return 5;
        }

        /**
         * A synthetic policy name identified by balancerName. The implementation detail doesn't
         * matter.
         */
        @Override
        public String getPolicyName() {
          return "xds_child_policy_balancer_name_" + newBalancerName;
        }

        @Override
        public LoadBalancer newLoadBalancer(final Helper helper) {
          return new LoadBalancer() {
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
              if (xdsClient == null) {
                ManagedChannel channel = initLbChannel(helper, newBalancerName, channelCredsList);
                xdsClient  = new XdsComms2(
                    channel, helper, new ExponentialBackoffPolicy.Provider(),
                    GrpcUtil.STOPWATCH_SUPPLIER, node);
                LoadStatsStore loadStatsStore = new LoadStatsStoreImpl();
                localityStore = localityStoreFactory.newLocalityStore(
                    helper, lbRegistry, loadStatsStore);
                // TODO(zdapeng): Use XdsClient to do Lrs directly.
                lrsClient = loadReportClientFactory.createLoadReportClient(
                    channel, helper, new ExponentialBackoffPolicy.Provider(),
                    loadStatsStore);
                final LoadReportCallback lrsCallback =
                    new LoadReportCallback() {
                      @Override
                      public void onReportResponse(long reportIntervalNano) {
                        localityStore.updateOobMetricsReportInterval(reportIntervalNano);
                      }
                    };

                EndpointWatcher endpointWatcher =
                    new EndpointWatcherImpl(lrsClient, lrsCallback, localityStore);
                xdsClient.watchEndpointData(node.getCluster(), endpointWatcher);
              }
            }

            @Override
            public void shutdown() {
              if (xdsClient != null) {
                lrsClient.stopLoadReporting();
                localityStore.reset();
                xdsClient.shutdown();
              }
            }
          };
        }
      };

      lookasideChannelLb.switchTo(childBalancerProvider);
    }

    lookasideChannelLb.handleResolvedAddresses(resolvedAddresses);
  }

  private static ManagedChannel initLbChannel(
      Helper helper,
      String balancerName,
      List<ChannelCreds> channelCredsList) {
    ManagedChannel channel = null;
    try {
      channel = helper.createResolvingOobChannel(balancerName);
    } catch (UnsupportedOperationException uoe) {
      // Temporary solution until createResolvingOobChannel is implemented
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
