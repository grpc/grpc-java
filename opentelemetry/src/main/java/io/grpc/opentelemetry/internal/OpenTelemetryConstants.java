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

import com.google.common.collect.ImmutableList;
import io.opentelemetry.api.common.AttributeKey;
import java.util.List;

public final class OpenTelemetryConstants {

  public static final String INSTRUMENTATION_SCOPE = "grpc-java";

  public static final AttributeKey<String> METHOD_KEY = AttributeKey.stringKey("grpc.method");

  public static final AttributeKey<String> STATUS_KEY = AttributeKey.stringKey("grpc.status");

  public static final AttributeKey<String> TARGET_KEY = AttributeKey.stringKey("grpc.target");

  public static final AttributeKey<String> LOCALITY_KEY =
      AttributeKey.stringKey("grpc.lb.locality");

  public static final AttributeKey<String> BACKEND_SERVICE_KEY =
      AttributeKey.stringKey("grpc.lb.backend_service");

  public static final List<Double> LATENCY_BUCKETS =
      ImmutableList.of(
          0d,     0.00001d, 0.00005d, 0.0001d, 0.0003d, 0.0006d, 0.0008d, 0.001d, 0.002d,
          0.003d, 0.004d,   0.005d,   0.006d,  0.008d,  0.01d,   0.013d,  0.016d, 0.02d,
          0.025d, 0.03d,    0.04d,    0.05d,   0.065d,  0.08d,   0.1d,    0.13d,  0.16d,
          0.2d,   0.25d,    0.3d,     0.4d,    0.5d,    0.65d,   0.8d,    1d,     2d,
          5d,     10d,      20d,      50d,     100d);

  public static final List<Long> SIZE_BUCKETS =
      ImmutableList.of(
          0L, 1024L, 2048L, 4096L, 16384L, 65536L, 262144L, 1048576L, 4194304L, 16777216L,
          67108864L, 268435456L, 1073741824L, 4294967296L);

  private OpenTelemetryConstants() {
  }
}
