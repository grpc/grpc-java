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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;

import io.grpc.FlagResetRule;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InternalFeatureFlags;
import io.grpc.ManagedChannel;
import io.grpc.opentelemetry.GrpcOpenTelemetry;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/**
 * xDS + OpenTelemetry E2E integration test using a fake control plane.
 * This class is skipped from Bazel builds because Bazel doesn't compile the
 * grpc-opentelemetry module.
 */
@RunWith(Parameterized.class)
public class FakeControlPlaneXdsOtelIntegrationTest {

  @Rule(order = 0)
  public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();
  @Rule(order = 1)
  public final ControlPlaneRule controlPlane = new ControlPlaneRule();
  @Rule(order = 2)
  public final DataPlaneRule dataPlane = new DataPlaneRule(controlPlane);
  @Rule(order = 3)
  public final FlagResetRule flagResetRule = new FlagResetRule();

  @Parameters(name = "enableRfc3986UrisParam={0}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][] {{true}, {false}});
  }

  @Parameter public boolean enableRfc3986UrisParam;

  @Test
  public void testInMemoryMetricReader() {
    InMemoryMetricReader metricReader = InMemoryMetricReader.create();
    SdkMeterProvider meterProvider = SdkMeterProvider.builder()
        .registerMetricReader(metricReader)
        .build();
    io.opentelemetry.api.metrics.LongCounter counter = meterProvider
        .meterBuilder("test-scope")
        .build()
        .counterBuilder("test-counter")
        .build();
    counter.add(10);
    assertThat(metricReader.collectAllMetrics()).isNotEmpty();
  }

  @Before
  public void setupRfc3986UrisFeatureFlag() throws Exception {
    flagResetRule.setFlagForTest(
        InternalFeatureFlags::setRfc3986UrisEnabled, enableRfc3986UrisParam);
  }

  @Test
  public void childChannelConfigurator_passesOtelSdkToChannel_E2E() throws Exception {
    InMemoryMetricReader metricReader = InMemoryMetricReader.create();
    SdkMeterProvider meterProvider = SdkMeterProvider.builder()
        .registerMetricReader(metricReader)
        .build();
    OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
        .setMeterProvider(meterProvider)
        .build();
    GrpcOpenTelemetry grpcOtel = GrpcOpenTelemetry.newBuilder()
        .sdk(openTelemetry)
        .build();

    ManagedChannel channel = grpcCleanupRule.register(
        Grpc.newChannelBuilder("test-xds:///test-server",
            InsecureChannelCredentials.create())
        .childChannelConfigurator(grpcOtel::configureChannelBuilder)
        .build());

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        SimpleServiceGrpc.newBlockingStub(channel);
    blockingStub.unaryRpc(SimpleRequest.getDefaultInstance());

    // Shut down the channel to complete the RPC cycle.
    channel.shutdownNow();
    channel.awaitTermination(5, TimeUnit.SECONDS);

    // Verify that OpenTelemetry metrics specifically from the xDS Control Plane ADS stream
    // successfully propagated, with the exact stream method name preserved.
    boolean foundTargetMethod = false;
    for (MetricData metric : metricReader.collectAllMetrics()) {
      String name = metric.getName();
      if ("grpc.client.attempt.started".equals(name) || "grpc.client.call.duration".equals(name)) {
        if (metric.toString().contains("envoy.service.discovery.v3.AggregatedDiscoveryService")) {
          foundTargetMethod = true;
          break;
        }
      }
    }
    assertThat(foundTargetMethod).isTrue();
  }
}
