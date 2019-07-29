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
import io.envoyproxy.udpa.data.orca.v1.OrcaLoadReport;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ExperimentalApi;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.services.CallMetricRecorder;
import io.grpc.services.InternalCallMetricRecorder;
import java.util.Map;

/**
 * A {@link ServerInterceptor} that intercepts a {@link ServerCall} by running server-side RPC
 * handling under a {@link Context} that records custom per-request metrics provided by server
 * applications and sends to client side along with the response in the format of Open Request
 * Cost Aggregation (ORCA).
 *
 * @since 1.23.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/6021")
public final class OrcaMetricReportingServerInterceptor implements ServerInterceptor {

  private static final OrcaMetricReportingServerInterceptor INSTANCE =
      new OrcaMetricReportingServerInterceptor();

  @VisibleForTesting
  static final Metadata.Key<OrcaLoadReport> ORCA_ENDPOINT_LOAD_METRICS_KEY =
      Metadata.Key.of(
          "x-endpoint-load-metrics-bin",
          ProtoUtils.metadataMarshaller(OrcaLoadReport.getDefaultInstance()));

  @VisibleForTesting
  OrcaMetricReportingServerInterceptor() {
  }

  public static OrcaMetricReportingServerInterceptor getInstance() {
    return INSTANCE;
  }

  @Override
  public <ReqT, RespT> Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    ServerCall<ReqT, RespT> trailerAttachingCall =
        new SimpleForwardingServerCall<ReqT, RespT>(call) {
          @Override
          public void close(Status status, Metadata trailers) {
            Map<String, Double> metricValues =
                InternalCallMetricRecorder.finalizeAndDump(CallMetricRecorder.getCurrent());
            // Only attach a metric report if there are some metric values to be reported.
            if (!metricValues.isEmpty()) {
              OrcaLoadReport report =
                  OrcaLoadReport.newBuilder().putAllRequestCost(metricValues).build();
              trailers.put(ORCA_ENDPOINT_LOAD_METRICS_KEY, report);
            }
            super.close(status, trailers);
          }
        };
    final CallMetricRecorder recorder = InternalCallMetricRecorder.newCallMetricRecorder();
    return Contexts.interceptCall(
        Context.current().withValue(InternalCallMetricRecorder.CONTEXT_KEY, recorder),
        trailerAttachingCall,
        headers,
        next);
  }
}
