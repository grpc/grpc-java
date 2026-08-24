/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.testing.integration;

import io.grpc.opentelemetry.GrpcOpenTelemetry;
import io.grpc.opentelemetry.InternalGrpcOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import java.util.HashMap;
import java.util.Map;
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement;

/**
 * Utility methods for OpenTelemetry configuration in integration testing.
 */
public final class OpenTelemetryUtil {

  private OpenTelemetryUtil() {}

  /**
   * Initializes and registers OpenTelemetry tracing for interop client and server.
   *
   * @param otelCollectorAddress optional collector address (e.g. "localhost:4317")
   * @return the configured {@link OpenTelemetrySdk}
   */
  @IgnoreJRERequirement // OpenTelemetry uses Java 8+ APIs
  public static OpenTelemetrySdk setupOpenTelemetry(String otelCollectorAddress) {
    AutoConfiguredOpenTelemetrySdkBuilder sdkBuilder =
        AutoConfiguredOpenTelemetrySdk.builder();
    Map<String, String> properties = new HashMap<>();
    properties.put("otel.traces.exporter", "otlp");
    properties.put("otel.exporter.otlp.protocol", "grpc");
    // Reduce BatchSpanProcessor export delay from default 5000ms to 100ms for fast test runs.
    properties.put("otel.bsp.schedule.delay", "100");
    if (otelCollectorAddress != null && !otelCollectorAddress.isEmpty()) {
      String endpoint = otelCollectorAddress;
      if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
        endpoint = "http://" + endpoint;
      }
      properties.put("otel.exporter.otlp.endpoint", endpoint);
    }
    sdkBuilder.addPropertiesSupplier(() -> properties);
    AutoConfiguredOpenTelemetrySdk autoSdk = sdkBuilder.build();
    OpenTelemetrySdk openTelemetrySdk = autoSdk.getOpenTelemetrySdk();
    GrpcOpenTelemetry.Builder grpcOpentelemetryBuilder = GrpcOpenTelemetry.newBuilder()
        .sdk(openTelemetrySdk);
    InternalGrpcOpenTelemetry.enableTracing(grpcOpentelemetryBuilder, true);
    GrpcOpenTelemetry grpcOpenTelemetry = grpcOpentelemetryBuilder.build();
    grpcOpenTelemetry.registerGlobal();
    return openTelemetrySdk;
  }
}
