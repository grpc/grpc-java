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

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.internal.GrpcUtil.IMPLEMENTATION_VERSION;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import io.grpc.ClientInterceptor;
import io.grpc.ExperimentalApi;
import io.grpc.ServerStreamTracer;
import io.grpc.opentelemetry.internal.OpenTelemetryConstants;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;

/**
 *  The entrypoint for OpenTelemetry metrics functionality in gRPC.
 *
 *  <p>OpenTelemetryModule uses {@link io.opentelemetry.api.OpenTelemetry} APIs for instrumentation.
 *  When no SDK is explicitly added no telemetry data will be collected. See
 *  {@link io.opentelemetry.sdk.OpenTelemetrySdk} for information on how to construct the SDK.
 *
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/10591")
public final class OpenTelemetryModule {

  private static final Supplier<Stopwatch> STOPWATCH_SUPPLIER = new Supplier<Stopwatch>() {
    @Override
    public Stopwatch get() {
      return Stopwatch.createUnstarted();
    }
  };

  private final OpenTelemetry openTelemetryInstance;
  private final MeterProvider meterProvider;
  private final Meter meter;
  private final OpenTelemetryMetricsResource resource;

  public static Builder newBuilder() {
    return new Builder();
  }

  private OpenTelemetryModule(Builder builder) {
    this.openTelemetryInstance = checkNotNull(builder.openTelemetrySdk, "openTelemetrySdk");
    this.meterProvider = checkNotNull(openTelemetryInstance.getMeterProvider(), "meterProvider");
    this.meter = this.meterProvider
        .meterBuilder(OpenTelemetryConstants.INSTRUMENTATION_SCOPE)
        .setInstrumentationVersion(IMPLEMENTATION_VERSION)
        .build();
    this.resource = createMetricInstruments(meter);
  }

  @VisibleForTesting
  OpenTelemetry getOpenTelemetryInstance() {
    return this.openTelemetryInstance;
  }

  @VisibleForTesting
  MeterProvider getMeterProvider() {
    return this.meterProvider;
  }

  @VisibleForTesting
  Meter getMeter() {
    return this.meter;
  }

  @VisibleForTesting
  OpenTelemetryMetricsResource getResource() {
    return this.resource;
  }

  /**
   * Returns a {@link ClientInterceptor} with metrics implementation.
   */
  public ClientInterceptor getClientInterceptor() {
    OpenTelemetryMetricsModule openTelemetryMetricsModule =
        new OpenTelemetryMetricsModule(
            STOPWATCH_SUPPLIER,
            resource);
    return openTelemetryMetricsModule.getClientInterceptor();
  }

  /**
   * Returns a {@link ServerStreamTracer.Factory} with metrics implementation.
   */
  public ServerStreamTracer.Factory getServerStreamTracerFactory() {
    OpenTelemetryMetricsModule openTelemetryMetricsModule =
        new OpenTelemetryMetricsModule(
            STOPWATCH_SUPPLIER,
            resource);
    return openTelemetryMetricsModule.getServerTracerFactory();
  }

  @VisibleForTesting
  static OpenTelemetryMetricsResource createMetricInstruments(Meter meter) {
    OpenTelemetryMetricsResource.Builder builder = OpenTelemetryMetricsResource.builder();

    builder.clientCallDurationCounter(
        meter.histogramBuilder("grpc.client.call.duration")
            .setUnit("s")
            .setDescription(
                "Time taken by gRPC to complete an RPC from application's perspective")
            .build());

    builder.clientAttemptCountCounter(
        meter.counterBuilder("grpc.client.attempt.started")
            .setUnit("{attempt}")
            .setDescription("Number of client call attempts started")
            .build());

    builder.clientAttemptDurationCounter(
        meter.histogramBuilder(
                "grpc.client.attempt.duration")
            .setUnit("s")
            .setDescription("Time taken to complete a client call attempt")
            .build());

    builder.clientTotalSentCompressedMessageSizeCounter(
        meter.histogramBuilder(
                "grpc.client.attempt.sent_total_compressed_message_size")
            .setUnit("By")
            .setDescription("Compressed message bytes sent per client call attempt")
            .ofLongs()
            .build());

    builder.clientTotalReceivedCompressedMessageSizeCounter(
        meter.histogramBuilder(
                "grpc.client.attempt.rcvd_total_compressed_message_size")
            .setUnit("By")
            .setDescription("Compressed message bytes received per call attempt")
            .ofLongs()
            .build());

    builder.serverCallCountCounter(
        meter.counterBuilder("grpc.server.call.started")
            .setUnit("{call}")
            .setDescription("Number of server calls started")
            .build());

    builder.serverCallDurationCounter(
        meter.histogramBuilder("grpc.server.call.duration")
            .setUnit("s")
            .setDescription(
                "Time taken to complete a call from server transport's perspective")
            .build());

    builder.serverTotalSentCompressedMessageSizeCounter(
        meter.histogramBuilder(
                "grpc.server.call.sent_total_compressed_message_size")
            .setUnit("By")
            .setDescription("Compressed message bytes sent per server call")
            .ofLongs()
            .build());

    builder.serverTotalReceivedCompressedMessageSizeCounter(
        meter.histogramBuilder(
                "grpc.server.call.rcvd_total_compressed_message_size")
            .setUnit("By")
            .setDescription("Compressed message bytes received per server call")
            .ofLongs()
            .build());

    return builder.build();
  }

  /**
   * Builder for configuring {@link OpenTelemetryModule}.
   */
  public static class Builder {
    private OpenTelemetry openTelemetrySdk = OpenTelemetry.noop();

    private Builder() {}

    /**
     * Sets the {@link io.opentelemetry.api.OpenTelemetry} entrypoint to use. This can be used to
     * configure OpenTelemetry by returning the instance created by a
     * {@link io.opentelemetry.sdk.OpenTelemetrySdkBuilder}.
     */
    public Builder sdk(OpenTelemetry sdk) {
      this.openTelemetrySdk = sdk;
      return this;
    }

    /**
     * Returns a new {@link OpenTelemetryModule} built with the configuration of this {@link
     * Builder}.
     */
    public OpenTelemetryModule build() {
      return new OpenTelemetryModule(this);
    }
  }
}
