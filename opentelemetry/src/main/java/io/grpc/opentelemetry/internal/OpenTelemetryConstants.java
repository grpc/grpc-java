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

package io.grpc.opentelemetry.internal;

public final class OpenTelemetryConstants {

  public static final String INSTRUMENTATION_SCOPE = "grpc-java";

  public static final String METHOD_KEY = "grpc.method";

  public static final String STATUS_KEY = "grpc.status";

  public static final String TARGET_KEY = "grpc.target";

  public static final String CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME = "grpc.client.attempt.started";

  public static final String CLIENT_ATTEMPT_DURATION_INSTRUMENT_NAME
      = "grpc.client.attempt.duration";

  public static final String CLIENT_ATTEMPT_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE
      = "grpc.client.attempt.sent_total_compressed_message_size";

  public static final String CLIENT_ATTEMPT_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE
      = "grpc.client.attempt.rcvd_total_compressed_message_size";

  public static final String CLIENT_CALL_DURATION = "grpc.client.call.duration";

  public static final String SERVER_CALL_COUNT = "grpc.server.call.started";

  public static final String SERVER_CALL_DURATION = "grpc.server.call.duration";

  public static final String SERVER_CALL_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE
      = "grpc.server.call.sent_total_compressed_message_size";

  public static final String SERVER_CALL_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE
      = "grpc.server.call.rcvd_total_compressed_message_size";

  private OpenTelemetryConstants() {
  }
}
