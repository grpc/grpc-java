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

import com.google.protobuf.Duration;
import io.envoyproxy.envoy.api.v2.core.Locality;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats;
import io.envoyproxy.envoy.api.v2.endpoint.UpstreamLocalityStats;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.xds.XdsClientLoadRecorder.ClientLoadCounter;
import java.util.HashMap;
import java.util.Map;

/**
 * An {@link XdsLoadReportStore} instance holds the client side load stats for a cluster. Methods
 * should be called in a synchronized context.
 */
class XdsLoadReportStore {

  private final String clusterName;
  private final Map<Locality, XdsClientLoadRecorder.ClientLoadCounter> localityLoadCounters;

  XdsLoadReportStore(String clusterName) {
    this.clusterName = checkNotNull(clusterName, "clusterName");
    localityLoadCounters = new HashMap<>();
  }

  /**
   * Generates a {@link ClusterStats} containing load stats in locality granularity.
   */
  ClusterStats generateLoadReport(Duration interval) {
    ClusterStats.Builder statsBuilder = ClusterStats.newBuilder().setClusterName(clusterName)
        .setLoadReportInterval(interval);
    for (Locality locality : localityLoadCounters.keySet()) {
      XdsClientLoadRecorder.ClientLoadCounter counter = localityLoadCounters.get(locality);
      XdsClientLoadRecorder.ClientLoadSnapshot snapshot = counter.snapshot();
      statsBuilder
          .addUpstreamLocalityStats(UpstreamLocalityStats.newBuilder()
              .setLocality(locality)
              .setTotalSuccessfulRequests(snapshot.callsSucceed)
              .setTotalErrorRequests(snapshot.callsFailed)
              .setTotalRequestsInProgress(snapshot.callsInProgress));
    }
    return statsBuilder.build();
  }

  /**
   * Intercepts a in-locality PickResult with load recording {@link ClientStreamTracer.Factory}.
   */
  PickResult interceptPickeResult(PickResult pickResult, Locality locality) {
    if (pickResult.getStatus() != Status.OK) {
      return pickResult;
    }
    ClientStreamTracer.Factory originFactory = pickResult.getStreamTracerFactory();
    if (originFactory == null) {
      originFactory = new ClientStreamTracer.Factory() {
        @Override
        public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
          return new ClientStreamTracer() {
          };
        }
      };
    }
    XdsClientLoadRecorder.ClientLoadCounter counter;
    counter = localityLoadCounters.get(locality);
    if (counter == null) {
      counter = new ClientLoadCounter();
      localityLoadCounters.put(locality, counter);
    }
    XdsClientLoadRecorder recorder = new XdsClientLoadRecorder(counter, originFactory);
    return PickResult.withSubchannel(pickResult.getSubchannel(), recorder);
  }
}
