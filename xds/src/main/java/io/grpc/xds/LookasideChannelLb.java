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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints;
import io.envoyproxy.envoy.type.FractionalPercent;
import io.envoyproxy.envoy.type.FractionalPercent.DenominatorType;
import io.grpc.LoadBalancer;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.xds.ClusterLoadAssignmentData.DropOverload;
import io.grpc.xds.ClusterLoadAssignmentData.LbEndpoint;
import io.grpc.xds.ClusterLoadAssignmentData.LocalityInfo;
import io.grpc.xds.ClusterLoadAssignmentData.XdsLocality;
import io.grpc.xds.LoadReportClient.LoadReportCallback;
import io.grpc.xds.XdsComms2.AdsStreamCallback;
import java.util.ArrayList;
import java.util.List;

/**
 * A load balancer that has a lookaside channel. This layer of load balancer creates a channel to
 * the remote load balancer. LrsClient, LocalityStore and XdsComms are three branches below this
 * layer, and their implementations are provided by their factories.
 */
final class LookasideChannelLb extends LoadBalancer {

  private final ManagedChannel lbChannel;
  private final LoadReportClient lrsClient;
  private final XdsComms2 xdsComms2;

  LookasideChannelLb(
      Helper helper, LookasideChannelCallback lookasideChannelCallback, ManagedChannel lbChannel,
      LocalityStore localityStore, Node node) {
    this(
        helper,
        lookasideChannelCallback,
        lbChannel,
        new LoadReportClientImpl(
            lbChannel, helper, GrpcUtil.STOPWATCH_SUPPLIER, new ExponentialBackoffPolicy.Provider(),
            localityStore.getLoadStatsStore()),
        localityStore,
        node);
  }

  @VisibleForTesting
  LookasideChannelLb(
      Helper helper,
      LookasideChannelCallback lookasideChannelCallback,
      ManagedChannel lbChannel,
      LoadReportClient lrsClient,
      final LocalityStore localityStore,
      Node node) {
    this.lbChannel = lbChannel;
    LoadReportCallback lrsCallback =
        new LoadReportCallback() {
          @Override
          public void onReportResponse(long reportIntervalNano) {
            localityStore.updateOobMetricsReportInterval(reportIntervalNano);
          }
        };
    this.lrsClient = lrsClient;

    AdsStreamCallback adsCallback = new AdsStreamCallbackImpl(
        lookasideChannelCallback, lrsClient, lrsCallback, localityStore) ;
    xdsComms2 = new XdsComms2(
        lbChannel, helper, adsCallback, new ExponentialBackoffPolicy.Provider(),
        GrpcUtil.STOPWATCH_SUPPLIER, node);
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

  private static final class AdsStreamCallbackImpl implements AdsStreamCallback {

    final LookasideChannelCallback lookasideChannelCallback;
    final LoadReportClient lrsClient;
    final LoadReportCallback lrsCallback;
    final LocalityStore localityStore;
    boolean firstEdsResponseReceived;

    AdsStreamCallbackImpl(
        LookasideChannelCallback lookasideChannelCallback, LoadReportClient lrsClient,
        LoadReportCallback lrsCallback, LocalityStore localityStore) {
      this.lookasideChannelCallback = lookasideChannelCallback;
      this.lrsClient = lrsClient;
      this.lrsCallback = lrsCallback;
      this.localityStore = localityStore;
    }

    @Override
    public void onEdsResponse(ClusterLoadAssignment clusterLoadAssignment) {
      if (!firstEdsResponseReceived) {
        firstEdsResponseReceived = true;
        lookasideChannelCallback.onWorking();
        lrsClient.startLoadReporting(lrsCallback);
      }

      List<ClusterLoadAssignment.Policy.DropOverload> dropOverloadsProto =
          clusterLoadAssignment.getPolicy().getDropOverloadsList();
      ImmutableList.Builder<DropOverload> dropOverloadsBuilder = ImmutableList.builder();
      for (ClusterLoadAssignment.Policy.DropOverload dropOverload : dropOverloadsProto) {
        int rateInMillion = rateInMillion(dropOverload.getDropPercentage());
        dropOverloadsBuilder.add(new DropOverload(dropOverload.getCategory(), rateInMillion));
        if (rateInMillion == 1000_000) {
          lookasideChannelCallback.onAllDrop();
          break;
        }
      }
      ImmutableList<DropOverload> dropOverloads = dropOverloadsBuilder.build();
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
      lookasideChannelCallback.onError();
    }
  }


  /**
   * Callback on ADS stream events. The callback methods should be called in a proper {@link
   * io.grpc.SynchronizationContext}.
   */
  interface LookasideChannelCallback {

    /**
     * Once the response observer receives the first response.
     */
    void onWorking();

    /**
     * Once an error occurs in ADS stream.
     */
    void onError();

    /**
     * Once receives a response indicating that 100% of calls should be dropped.
     */
    void onAllDrop();
  }
}
