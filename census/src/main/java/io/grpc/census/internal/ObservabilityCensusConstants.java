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

import io.opencensus.stats.Aggregation;
import io.opencensus.stats.Aggregation.Distribution;
import io.opencensus.stats.BucketBoundaries;
import io.opencensus.stats.View;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Temporary holder class for the observability specific OpenCensus constants.
 *  The class will be removed once the new views are added in OpenCensus library. */
public final class ObservabilityCensusConstants {

  static final List<Double> RPC_BYTES_BUCKET_BOUNDARIES =
      Collections.unmodifiableList(
          Arrays.asList(
              0.0,
              1024.0,
              2048.0,
              4096.0,
              16384.0,
              65536.0,
              262144.0,
              1048576.0,
              4194304.0,
              16777216.0,
              67108864.0,
              268435456.0,
              1073741824.0,
              4294967296.0));

  static final Aggregation AGGREGATION_WITH_BYTES_HISTOGRAM =
      Distribution.create(BucketBoundaries.create(RPC_BYTES_BUCKET_BOUNDARIES));

  public static final View GRPC_CLIENT_SENT_COMPRESSED_MESSAGE_BYTES_PER_RPC =
      View.create(
          View.Name.create("grpc.io/client/sent_compressed_message_bytes_per_rpc"),
          "Compressed message bytes sent per client RPC attempt",
          GRPC_CLIENT_SENT_BYTES_PER_RPC,
          AGGREGATION_WITH_BYTES_HISTOGRAM,
          Arrays.asList(GRPC_CLIENT_METHOD, GRPC_CLIENT_STATUS));

  public static final View GRPC_CLIENT_RECEIVED_COMPRESSED_MESSAGE_BYTES_PER_RPC =
      View.create(
          View.Name.create("grpc.io/client/received_compressed_message_bytes_per_rpc"),
          "Compressed message bytes received per client RPC attempt",
          GRPC_CLIENT_RECEIVED_BYTES_PER_RPC,
          AGGREGATION_WITH_BYTES_HISTOGRAM,
          Arrays.asList(GRPC_CLIENT_METHOD, GRPC_CLIENT_STATUS));

  public static final View GRPC_SERVER_SENT_COMPRESSED_MESSAGE_BYTES_PER_RPC =
      View.create(
          View.Name.create("grpc.io/server/sent_compressed_message_bytes_per_rpc"),
          "Compressed message bytes sent per server RPC",
          GRPC_SERVER_SENT_BYTES_PER_RPC,
          AGGREGATION_WITH_BYTES_HISTOGRAM,
          Arrays.asList(GRPC_SERVER_METHOD, GRPC_SERVER_STATUS));

  public static final View GRPC_SERVER_RECEIVED_COMPRESSED_MESSAGE_BYTES_PER_RPC =
      View.create(
          View.Name.create("grpc.io/server/received_compressed_message_bytes_per_rpc"),
          "Compressed message bytes received per server RPC",
          GRPC_SERVER_RECEIVED_BYTES_PER_RPC,
          AGGREGATION_WITH_BYTES_HISTOGRAM,
          Arrays.asList(GRPC_SERVER_METHOD, GRPC_SERVER_STATUS));

  private ObservabilityCensusConstants() {}
}
