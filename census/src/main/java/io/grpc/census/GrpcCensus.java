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
import io.grpc.ServerBuilder;
import io.grpc.ServerStreamTracer;
import io.opencensus.trace.Tracing;

/**
 *  The entrypoint for OpenCensus instrumentation functionality in gRPC.
 *
 *  <p>GrpcCensus uses {@link io.opencensus.api.OpenCensus} APIs for instrumentation.
 *
 */
public final class GrpcCensus {

  private GrpcCensus() {}

  private static final Supplier<Stopwatch> STOPWATCH_SUPPLIER = new Supplier<Stopwatch>() {
    @Override
    public Stopwatch get() {
      return Stopwatch.createUnstarted();
    }
  };

  /**
   * Returns a {@link ClientInterceptor} with default stats implementation.
   */
  public static ClientInterceptor newClientStatsInterceptor() {
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
  public static ClientInterceptor newClientTracingInterceptor() {
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
   * Configures the given {@link ServerBuilder} with serverStreamTracerFactory for stats.
   *
   * @param serverBuilder             the server builder to configure
   */
  public static void configureServerBuilderWithStatsTracer(ServerBuilder<?> serverBuilder) {
    serverBuilder.addStreamTracerFactory(newServerStatsStreamTracerFactory());
  }

  /**
   * Configures the given {@link ServerBuilder} with serverStreamTracerFactory for tracing.
   *
   * @param serverBuilder             the server builder to configure
   */
  public static void configureServerBuilderWithTracingTracer(ServerBuilder<?> serverBuilder) {
    serverBuilder.addStreamTracerFactory(newServerTracingStreamTracerFactory());
  }

}
