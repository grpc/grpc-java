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

import io.opentelemetry.api.common.AttributeKey;

public final class OpenTelemetryConstants {

  public static final String INSTRUMENTATION_SCOPE = "grpc-java";

  public static final AttributeKey<String> METHOD_KEY = AttributeKey.stringKey("grpc.method");

  public static final AttributeKey<String> STATUS_KEY = AttributeKey.stringKey("grpc.status");

  public static final AttributeKey<String> TARGET_KEY = AttributeKey.stringKey("grpc.target");

  public static final AttributeKey<String> LOCALITY_KEY =
      AttributeKey.stringKey("grpc.lb.locality");

  private OpenTelemetryConstants() {
  }
}
