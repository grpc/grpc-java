/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.gcp.csm.observability;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.ExperimentalApi;
import io.grpc.InternalConfigurator;
import io.grpc.InternalConfiguratorRegistry;
import io.grpc.ManagedChannelBuilder;
import io.grpc.ServerBuilder;
import io.grpc.opentelemetry.GrpcOpenTelemetry;
import io.grpc.opentelemetry.InternalGrpcOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import java.io.Closeable;
import java.util.Collection;
import java.util.Collections;

/**
 * The entrypoint for GCP's CSM OpenTelemetry metrics functionality in gRPC.
 *
 * <p>CsmObservability uses {@link io.opentelemetry.api.OpenTelemetry} APIs for instrumentation.
 * When no SDK is explicitly added no telemetry data will be collected. See
 * {@code io.opentelemetry.sdk.OpenTelemetrySdk} for information on how to construct the SDK.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/11249")
public final class CsmObservability implements Closeable {
  private final GrpcOpenTelemetry delegate;
  private final MetadataExchanger exchanger;

  public static Builder newBuilder() {
    return new Builder();
  }

  private CsmObservability(Builder builder) {
    this.delegate = builder.delegate.build();
    this.exchanger = builder.exchanger;
  }

  /**
   * Registers CsmObservability globally, applying its configuration to all subsequently created
   * gRPC channels and servers.
   *
   * <p>Note: Only one of CsmObservability and GrpcOpenTelemetry instance can be registered
   * globally. Any subsequent call to {@code registerGlobal()} will throw an {@code
   * IllegalStateException}.
   */
  public void registerGlobal() {
    InternalConfiguratorRegistry.setConfigurators(Collections.singletonList(
        new InternalConfigurator() {
          @Override
          public void configureChannelBuilder(ManagedChannelBuilder<?> channelBuilder) {
            CsmObservability.this.configureChannelBuilder(channelBuilder);
          }

          @Override
          public void configureServerBuilder(ServerBuilder<?> serverBuilder) {
            CsmObservability.this.configureServerBuilder(serverBuilder);
          }
        }));
  }

  @VisibleForTesting
  void configureChannelBuilder(ManagedChannelBuilder<?> builder) {
    delegate.configureChannelBuilder(builder);
  }

  @VisibleForTesting
  void configureServerBuilder(ServerBuilder<?> serverBuilder) {
    delegate.configureServerBuilder(serverBuilder);
    exchanger.configureServerBuilder(serverBuilder);
  }

  @Override
  public void close() {}

  /**
   * Builder for configuring {@link CsmObservability}.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/11249")
  public static final class Builder {
    private final GrpcOpenTelemetry.Builder delegate = GrpcOpenTelemetry.newBuilder();
    private final MetadataExchanger exchanger;

    private Builder() {
      this(new MetadataExchanger());
    }

    @VisibleForTesting
    Builder(MetadataExchanger exchanger) {
      this.exchanger = exchanger;
      InternalGrpcOpenTelemetry.builderPlugin(delegate, exchanger);
    }

    /**
     * Sets the {@link io.opentelemetry.api.OpenTelemetry} entrypoint to use. This can be used to
     * configure OpenTelemetry by returning the instance created by a
     * {@code io.opentelemetry.sdk.OpenTelemetrySdkBuilder}.
     */
    public Builder sdk(OpenTelemetry sdk) {
      delegate.sdk(sdk);
      return this;
    }

    /**
     * Adds optionalLabelKey to all the metrics that can provide value for the
     * optionalLabelKey.
     */
    public Builder addOptionalLabel(String optionalLabelKey) {
      delegate.addOptionalLabel(optionalLabelKey);
      return this;
    }

    /**
     * Enables the specified metrics for collection and export. By default, only a subset of
     * metrics are enabled.
     */
    public Builder enableMetrics(Collection<String> enableMetrics) {
      delegate.enableMetrics(enableMetrics);
      return this;
    }

    /**
     * Disables the specified metrics from being collected and exported.
     */
    public Builder disableMetrics(Collection<String> disableMetrics) {
      delegate.disableMetrics(disableMetrics);
      return this;
    }

    /**
     * Disable all metrics. If set to true all metrics must be explicitly enabled.
     */
    public Builder disableAllMetrics() {
      delegate.disableAllMetrics();
      return this;
    }

    /**
     * Returns a new {@link CsmObservability} built with the configuration of this {@link
     * Builder}.
     */
    public CsmObservability build() {
      return new CsmObservability(this);
    }
  }
}
