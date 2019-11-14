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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.LoadReportClient.LoadReportCallback;
import io.grpc.xds.XdsClient.EndpointUpdate;
import io.grpc.xds.XdsClient.EndpointWatcher;
import java.util.List;
import java.util.Map;

/**
 * A load balancer that has a lookaside channel. This layer of load balancer creates a channel to
 * the remote load balancer. LrsClient, LocalityStore and XdsComms are three branches below this
 * layer, and their implementations are provided by their factories.
 */
final class LookasideChannelLb extends LoadBalancer {

  private final LoadReportClient lrsClient;
  private final XdsClient xdsClient;

  LookasideChannelLb(
      String edsServiceName,
      LookasideChannelCallback lookasideChannelCallback,
      XdsClient xdsClient,
      LoadReportClient lrsClient,
      final LocalityStore localityStore) {
    this.xdsClient = xdsClient;
    LoadReportCallback lrsCallback =
        new LoadReportCallback() {
          @Override
          public void onReportResponse(long reportIntervalNano) {
            localityStore.updateOobMetricsReportInterval(reportIntervalNano);
          }
        };
    this.lrsClient = lrsClient;

    EndpointWatcher endpointWatcher = new EndpointWatcherImpl(
        lookasideChannelCallback, lrsClient, lrsCallback, localityStore) ;
    xdsClient.watchEndpointData(edsServiceName, endpointWatcher);
  }

  @Override
  public void handleNameResolutionError(Status error) {
    // NO-OP?
  }

  @Override
  public void shutdown() {
    lrsClient.stopLoadReporting();
    xdsClient.shutdown();
  }

  private static final class EndpointWatcherImpl implements EndpointWatcher {

    final LookasideChannelCallback lookasideChannelCallback;
    final LoadReportClient lrsClient;
    final LoadReportCallback lrsCallback;
    final LocalityStore localityStore;
    boolean firstEdsResponseReceived;

    EndpointWatcherImpl(
        LookasideChannelCallback lookasideChannelCallback, LoadReportClient lrsClient,
        LoadReportCallback lrsCallback, LocalityStore localityStore) {
      this.lookasideChannelCallback = lookasideChannelCallback;
      this.lrsClient = lrsClient;
      this.lrsCallback = lrsCallback;
      this.localityStore = localityStore;
    }

    @Override
    public void onEndpointChanged(EndpointUpdate endpointUpdate) {
      if (!firstEdsResponseReceived) {
        firstEdsResponseReceived = true;
        lookasideChannelCallback.onWorking();
        lrsClient.startLoadReporting(lrsCallback);
      }

      List<DropOverload> dropOverloads = endpointUpdate.getDropPolicies();
      ImmutableList.Builder<DropOverload> dropOverloadsBuilder = ImmutableList.builder();
      for (DropOverload dropOverload : dropOverloads) {
        dropOverloadsBuilder.add(dropOverload);
        if (dropOverload.getDropsPerMillion() == 1_000_000) {
          lookasideChannelCallback.onAllDrop();
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
