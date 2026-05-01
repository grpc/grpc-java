/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.census.internal;

import static io.opencensus.contrib.grpc.metrics.RpcMeasureConstants.GRPC_CLIENT_METHOD;
import static io.opencensus.contrib.grpc.metrics.RpcMeasureConstants.GRPC_CLIENT_RECEIVED_BYTES_PER_RPC;
import static io.opencensus.contrib.grpc.metrics.RpcMeasureConstants.GRPC_CLIENT_SENT_BYTES_PER_RPC;
import static io.opencensus.contrib.grpc.metrics.RpcMeasureConstants.GRPC_CLIENT_STATUS;
import static io.opencensus.contrib.grpc.metrics.RpcMeasureConstants.GRPC_SERVER_METHOD;
import static io.opencensus.contrib.grpc.metrics.RpcMeasureConstants.GRPC_SERVER_RECEIVED_BYTES_PER_RPC;
import static io.opencensus.contrib.grpc.metrics.RpcMeasureConstants.GRPC_SERVER_SENT_BYTES_PER_RPC;
import static io.opencensus.contrib.grpc.metrics.RpcMeasureConstants.GRPC_SERVER_STATUS;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.CallOptions;
import io.opencensus.contrib.grpc.metrics.RpcViewConstants;
import io.opencensus.stats.Aggregation;
import io.opencensus.stats.Measure;
import io.opencensus.stats.Measure.MeasureDouble;
import io.opencensus.stats.View;
import io.opencensus.trace.SpanContext;
import java.util.Arrays;

// TODO(dnvindhya): Remove metric and view definitions from this class once it is moved to
// OpenCensus library.
/**
 * Temporary holder class for the observability specific OpenCensus constants. The class will be
 * removed once the new views are added in OpenCensus library.
 */
@VisibleForTesting
public final class ObservabilityCensusConstants {

  public static CallOptions.Key<SpanContext> CLIENT_TRACE_SPAN_CONTEXT_KEY
      = CallOptions.Key.createWithDefault("Client span context for tracing", SpanContext.INVALID);

  static final Aggregation AGGREGATION_WITH_BYTES_HISTOGRAM =
      RpcViewConstants.GRPC_CLIENT_SENT_BYTES_PER_RPC_VIEW.getAggregation();

  static final Aggregation AGGREGATION_WITH_MILLIS_HISTOGRAM =
      RpcViewConstants.GRPC_CLIENT_ROUNDTRIP_LATENCY_VIEW.getAggregation();

  public static final MeasureDouble API_LATENCY_PER_CALL =
      Measure.MeasureDouble.create(
          "grpc.io/client/api_latency",
          "Time taken by gRPC to complete an RPC from application's perspective",
          "ms");

  public static final View GRPC_CLIENT_API_LATENCY_VIEW =
      View.create(
          View.Name.create("grpc.io/client/api_latency"),
          "Time taken by gRPC to complete an RPC from application's perspective",
          API_LATENCY_PER_CALL,
          AGGREGATION_WITH_MILLIS_HISTOGRAM,
          Arrays.asList(GRPC_CLIENT_METHOD, GRPC_CLIENT_STATUS));

  public static final View GRPC_CLIENT_SENT_COMPRESSED_MESSAGE_BYTES_PER_RPC_VIEW =
      View.create(
          View.Name.create("grpc.io/client/sent_compressed_message_bytes_per_rpc"),
          "Compressed message bytes sent per client RPC attempt",
          GRPC_CLIENT_SENT_BYTES_PER_RPC,
          AGGREGATION_WITH_BYTES_HISTOGRAM,
          Arrays.asList(GRPC_CLIENT_METHOD, GRPC_CLIENT_STATUS));

  public static final View GRPC_CLIENT_RECEIVED_COMPRESSED_MESSAGE_BYTES_PER_RPC_VIEW =
      View.create(
          View.Name.create("grpc.io/client/received_compressed_message_bytes_per_rpc"),
          "Compressed message bytes received per client RPC attempt",
          GRPC_CLIENT_RECEIVED_BYTES_PER_RPC,
          AGGREGATION_WITH_BYTES_HISTOGRAM,
          Arrays.asList(GRPC_CLIENT_METHOD, GRPC_CLIENT_STATUS));

  public static final View GRPC_SERVER_SENT_COMPRESSED_MESSAGE_BYTES_PER_RPC_VIEW =
      View.create(
          View.Name.create("grpc.io/server/sent_compressed_message_bytes_per_rpc"),
          "Compressed message bytes sent per server RPC",
          GRPC_SERVER_SENT_BYTES_PER_RPC,
          AGGREGATION_WITH_BYTES_HISTOGRAM,
          Arrays.asList(GRPC_SERVER_METHOD, GRPC_SERVER_STATUS));

  public static final View GRPC_SERVER_RECEIVED_COMPRESSED_MESSAGE_BYTES_PER_RPC_VIEW =
      View.create(
          View.Name.create("grpc.io/server/received_compressed_message_bytes_per_rpc"),
          "Compressed message bytes received per server RPC",
          GRPC_SERVER_RECEIVED_BYTES_PER_RPC,
          AGGREGATION_WITH_BYTES_HISTOGRAM,
          Arrays.asList(GRPC_SERVER_METHOD, GRPC_SERVER_STATUS));

  private ObservabilityCensusConstants() {}
}
