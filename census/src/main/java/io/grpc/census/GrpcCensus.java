/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.census;

import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import io.grpc.ClientInterceptor;
import io.grpc.ExperimentalApi;
import io.grpc.ManagedChannelBuilder;
import io.grpc.ServerBuilder;
import io.grpc.ServerStreamTracer;
import io.opencensus.trace.Tracing;

/**
 *  The entrypoint for OpenCensus instrumentation functionality in gRPC.
 *
 *  <p>GrpcCensus uses {@link io.opencensus.api.OpenCensus} APIs for instrumentation.
 *
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/12178")
public final class GrpcCensus {

  private final boolean statsEnabled;
  private final boolean tracingEnabled;

  private GrpcCensus(Builder builder) {
    this.statsEnabled = builder.statsEnabled;
    this.tracingEnabled = builder.tracingEnabled;
  }

  /**
   * Creates a new builder for {@link GrpcCensus}.
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  private static final Supplier<Stopwatch> STOPWATCH_SUPPLIER = new Supplier<Stopwatch>() {
    @Override
    public Stopwatch get() {
      return Stopwatch.createUnstarted();
    }
  };

  /**
   * Configures a {@link ServerBuilder} to enable census stats and tracing.
   *
   * @param serverBuilder The server builder to configure.
   * @return The configured server builder.
   */
  public <T extends ServerBuilder<T>> T configureServerBuilder(T serverBuilder) {
    if (statsEnabled) {
      serverBuilder.addStreamTracerFactory(newServerStatsStreamTracerFactory());
    }
    if (tracingEnabled) {
      serverBuilder.addStreamTracerFactory(newServerTracingStreamTracerFactory());
    }
    return serverBuilder;
  }

  /**
   * Configures a {@link ManagedChannelBuilder} to enable census stats and tracing.
   *
   * @param channelBuilder The channel builder to configure.
   * @return The configured channel builder.
   */
  public <T extends ManagedChannelBuilder<T>> T configureChannelBuilder(T channelBuilder) {
    if (statsEnabled) {
      channelBuilder.intercept(newClientStatsInterceptor());
    }
    if (tracingEnabled) {
      channelBuilder.intercept(newClientTracingInterceptor());
    }
    return channelBuilder;
  }

  /**
   * Returns a {@link ClientInterceptor} with default stats implementation.
   */
  private static ClientInterceptor newClientStatsInterceptor() {
    CensusStatsModule censusStats =
        new CensusStatsModule(
            STOPWATCH_SUPPLIER,
            true,
            true,
            true,
            false,
            true);
    return censusStats.getClientInterceptor();
  }

  /**
   * Returns a {@link ClientInterceptor} with default tracing implementation.
   */
  private static ClientInterceptor newClientTracingInterceptor() {
    CensusTracingModule censusTracing =
        new CensusTracingModule(
            Tracing.getTracer(),
            Tracing.getPropagationComponent().getBinaryFormat());
    return censusTracing.getClientInterceptor();
  }

  /**
   * Returns a {@link ServerStreamTracer.Factory} with default stats implementation.
   */
  private static ServerStreamTracer.Factory newServerStatsStreamTracerFactory() {
    CensusStatsModule censusStats =
        new CensusStatsModule(
            STOPWATCH_SUPPLIER,
            true,
            true,
            true,
            false,
            true);
    return censusStats.getServerTracerFactory();
  }

  /**
   * Returns a {@link ServerStreamTracer.Factory} with default tracing implementation.
   */
  private static ServerStreamTracer.Factory newServerTracingStreamTracerFactory() {
    CensusTracingModule censusTracing =
        new CensusTracingModule(
            Tracing.getTracer(),
            Tracing.getPropagationComponent().getBinaryFormat());
    return censusTracing.getServerTracerFactory();
  }

  /**
   * Builder for {@link GrpcCensus}.
   */
  public static final class Builder {
    private boolean statsEnabled = true;
    private boolean tracingEnabled = true;

    private Builder() {
    }

    /**
     * Disables stats collection.
     */
    public Builder disableStats() {
      this.statsEnabled = false;
      return this;
    }

    /**
     * Disables tracing.
     */
    public Builder disableTracing() {
      this.tracingEnabled = false;
      return this;
    }

    /**
     * Builds a new {@link GrpcCensus}.
     */
    public GrpcCensus build() {
      return new GrpcCensus(this);
    }
  }
}
