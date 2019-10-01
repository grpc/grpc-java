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
import static java.util.logging.Level.FINEST;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy.DropOverload;
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints;
import io.envoyproxy.envoy.type.FractionalPercent;
import io.envoyproxy.envoy.type.FractionalPercent.DenominatorType;
import io.grpc.LoadBalancer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.xds.LoadReportClient.LoadReportCallback;
import io.grpc.xds.XdsComms.AdsStreamCallback;
import io.grpc.xds.XdsComms.LbEndpoint;
import io.grpc.xds.XdsComms.LocalityInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * A load balancer that has a lookaside channel. This layer of load balancer creates a channel to
 * the remote load balancer. LrsClient, LocalityStore and XdsComms are three branches below this
 * layer, and their implementations are provided by their factories.
 */
final class LookasideChannelLb extends LoadBalancer {

  private final ManagedChannel lbChannel;
  private final LoadReportClient lrsClient;
  private final XdsComms2 xdsComms2;

  @VisibleForTesting
  LookasideChannelLb(
      Helper helper,
      AdsStreamCallback adsCallback,
      String balancerName,
      LoadReportClient lrsClient,
      final LocalityStore localityStore) {
    lbChannel = initLbChannel(helper, balancerName);
    LoadReportCallback lrsCallback =
        new LoadReportCallback() {
          @Override
          public void onReportResponse(long reportIntervalNano) {
            localityStore.updateOobMetricsReportInterval(reportIntervalNano);
          }
        };
    this.lrsClient = lrsClient;

    AdsStreamCallback2 adsCallback2 = new AdsStreamCallback2Impl(
        adsCallback, lrsClient, lrsCallback, localityStore) ;
    xdsComms2 = new XdsComms2(
        lbChannel, helper, adsCallback2, new ExponentialBackoffPolicy.Provider(),
        GrpcUtil.STOPWATCH_SUPPLIER);
  }

  private static int rateInMillion(FractionalPercent fractionalPercent) {
    int numerator = fractionalPercent.getNumerator();
    checkArgument(numerator >= 0, "numerator shouldn't be negative in %s", fractionalPercent);

    DenominatorType type = fractionalPercent.getDenominator();
    switch (type) {
      case TEN_THOUSAND:
        numerator *= 100;
        break;
      case HUNDRED:
        numerator *= 100_00;
        break;
      case MILLION:
        break;
      default:
        throw new IllegalArgumentException("unknown denominator type of " + fractionalPercent);
    }

    if (numerator > 1000_000) {
      numerator = 1000_000;
    }

    return numerator;
  }

  private static ManagedChannel initLbChannel(Helper helper, String balancerName) {
    ManagedChannel channel;
    try {
      channel = helper.createResolvingOobChannel(balancerName);
    } catch (UnsupportedOperationException uoe) {
      // Temporary solution until createResolvingOobChannel is implemented
      // FIXME (https://github.com/grpc/grpc-java/issues/5495)
      Logger logger = Logger.getLogger(LookasideChannelLb.class.getName());
      if (logger.isLoggable(FINEST)) {
        logger.log(
            FINEST,
            "createResolvingOobChannel() not supported by the helper: " + helper,
            uoe);
        logger.log(
            FINEST,
            "creating oob channel for target {0} using default ManagedChannelBuilder",
            balancerName);
      }
      channel = ManagedChannelBuilder.forTarget(balancerName).build();
    }
    return channel;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    // NO-OP?
  }

  @Override
  public void shutdown() {
    lrsClient.stopLoadReporting();
    xdsComms2.shutdownLbRpc();
    lbChannel.shutdown();
  }

  // TODO(zdapeng): The old AdsStreamCallback will be renamed to LookasideChannelCallback,
  // and AdsStreamCallback2 will be renamed to AdsStreamCallback
  /**
   * Callback on ADS stream events. The callback methods should be called in a proper {@link
   * io.grpc.SynchronizationContext}.
   */
  interface AdsStreamCallback2 {
    void onEdsResponse(ClusterLoadAssignment clusterLoadAssignment);

    void onError();
  }

  private static final class AdsStreamCallback2Impl implements AdsStreamCallback2 {

    final AdsStreamCallback adsCallback;
    final LoadReportClient lrsClient;
    final LoadReportCallback lrsCallback;
    final LocalityStore localityStore;
    boolean firstEdsResponseReceived;

    AdsStreamCallback2Impl(
        AdsStreamCallback adsCallback, LoadReportClient lrsClient, LoadReportCallback lrsCallback,
        LocalityStore localityStore) {
      this.adsCallback = adsCallback;
      this.lrsClient = lrsClient;
      this.lrsCallback = lrsCallback;
      this.localityStore = localityStore;
    }

    @Override
    public void onEdsResponse(ClusterLoadAssignment clusterLoadAssignment) {
      if (!firstEdsResponseReceived) {
        firstEdsResponseReceived = true;
        adsCallback.onWorking();
        lrsClient.startLoadReporting(lrsCallback);
      }

      List<DropOverload> dropOverloadsProto =
          clusterLoadAssignment.getPolicy().getDropOverloadsList();
      ImmutableList.Builder<XdsComms.DropOverload> dropOverloadsBuilder
          = ImmutableList.builder();
      for (ClusterLoadAssignment.Policy.DropOverload dropOverload
          : dropOverloadsProto) {
        int rateInMillion = rateInMillion(dropOverload.getDropPercentage());
        dropOverloadsBuilder.add(new XdsComms.DropOverload(
            dropOverload.getCategory(), rateInMillion));
        if (rateInMillion == 1000_000) {
          adsCallback.onAllDrop();
          break;
        }
      }
      ImmutableList<XdsComms.DropOverload> dropOverloads = dropOverloadsBuilder.build();
      localityStore.updateDropPercentage(dropOverloads);

      List<LocalityLbEndpoints> localities = clusterLoadAssignment.getEndpointsList();
      ImmutableMap.Builder<XdsLocality, LocalityInfo> localityEndpointsMapping =
          new ImmutableMap.Builder<>();
      for (LocalityLbEndpoints localityLbEndpoints : localities) {
        io.envoyproxy.envoy.api.v2.core.Locality localityProto =
            localityLbEndpoints.getLocality();
        XdsLocality locality = XdsLocality.fromLocalityProto(localityProto);
        List<LbEndpoint> lbEndPoints = new ArrayList<>();
        for (io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint lbEndpoint
            : localityLbEndpoints.getLbEndpointsList()) {
          lbEndPoints.add(new LbEndpoint(lbEndpoint));
        }
        int localityWeight = localityLbEndpoints.getLoadBalancingWeight().getValue();
        int priority = localityLbEndpoints.getPriority();

        if (localityWeight != 0) {
          localityEndpointsMapping.put(
              locality, new LocalityInfo(lbEndPoints, localityWeight, priority));
        }
      }

      localityStore.updateLocalityStore(localityEndpointsMapping.build());
    }

    @Override
    public void onError() {
      adsCallback.onError();
    }
  }
}
