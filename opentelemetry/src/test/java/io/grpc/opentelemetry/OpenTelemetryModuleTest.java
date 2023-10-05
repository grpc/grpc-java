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

package io.grpc.opentelemetry;

import static com.google.common.truth.Truth.assertThat;

import io.grpc.internal.GrpcUtil;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.Test;

public class OpenTelemetryModuleTest {
  private final InMemoryMetricReader inMemoryMetricReader = InMemoryMetricReader.create();
  private final SdkMeterProvider meterProvider =
      SdkMeterProvider.builder().registerMetricReader(inMemoryMetricReader).build();
  private final OpenTelemetry noopOpenTelemetry = OpenTelemetry.noop();

  @Test
  public void build() {
    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build();
    OpenTelemetryModule openTelemetryModule = OpenTelemetryModule.builder()
        .sdk(sdk)
        .build();

    assertThat(openTelemetryModule.getOpenTelemetryInstance()).isSameInstanceAs(sdk);
    assertThat(openTelemetryModule.getMeterProvider()).isNotNull();
    assertThat(openTelemetryModule.getMeter()).isSameInstanceAs(
        meterProvider.meterBuilder("grpc-java")
            .setInstrumentationVersion(GrpcUtil.IMPLEMENTATION_VERSION)
            .build());
  }

  @Test
  public void builderDefaults() {
    OpenTelemetryModule module = OpenTelemetryModule.builder().build();

    assertThat(module.getOpenTelemetryInstance()).isNotNull();
    assertThat(module.getOpenTelemetryInstance()).isSameInstanceAs(noopOpenTelemetry);
    assertThat(module.getMeterProvider()).isNotNull();
    assertThat(module.getMeterProvider())
        .isSameInstanceAs(noopOpenTelemetry.getMeterProvider());
    assertThat(module.getMeter()).isSameInstanceAs(noopOpenTelemetry
        .getMeterProvider()
        .meterBuilder("grpc-java")
        .setInstrumentationVersion(GrpcUtil.IMPLEMENTATION_VERSION)
        .build());
  }
}
