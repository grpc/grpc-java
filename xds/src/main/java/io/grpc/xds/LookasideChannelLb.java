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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.LoadBalancer;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.LoadReportClient.LoadReportCallback;
import io.grpc.xds.XdsComms2.AdsStreamCallback;
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
      for (ClusterLoadAssignment.Policy.DropOverload drop : dropOverloadsProto) {
        DropOverload dropOverload = DropOverload.fromEnvoyProtoDropOverload(drop);
        dropOverloadsBuilder.add(dropOverload);
        if (dropOverload.getDropsPerMillion() == 1_000_000) {
          lookasideChannelCallback.onAllDrop();
          break;
        }
      }
      ImmutableList<DropOverload> dropOverloads = dropOverloadsBuilder.build();
      localityStore.updateDropPercentage(dropOverloads);

      List<io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints> localities =
          clusterLoadAssignment.getEndpointsList();
      ImmutableMap.Builder<Locality, LocalityLbEndpoints> localityEndpointsMapping =
          new ImmutableMap.Builder<>();
      for (io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints localityLbEndpoints
          : localities) {
        Locality locality = Locality.fromEnvoyProtoLocality(localityLbEndpoints.getLocality());
        int localityWeight = localityLbEndpoints.getLoadBalancingWeight().getValue();

        if (localityWeight != 0) {
          localityEndpointsMapping.put(
              locality, LocalityLbEndpoints.fromEnvoyProtoLocalityLbEndpoints(localityLbEndpoints));
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
