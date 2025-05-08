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
import io.opencensus.stats.StatsRecorder;
import io.opencensus.tags.Tagger;
import io.opencensus.tags.propagation.TagContextBinarySerializer;
import io.opencensus.trace.Tracing;

public final class GrpcCensus {

  private static final Supplier<Stopwatch> STOPWATCH_SUPPLIER = new Supplier<Stopwatch>() {
    @Override
    public Stopwatch get() {
      return Stopwatch.createUnstarted();
    }
  };

  /**
   * Returns a {@link ClientInterceptor} with default stats implementation.
   */
  public static ClientInterceptor newClientStatsInterceptor(
      boolean recordStartedRpcs,
      boolean recordFinishedRpcs,
      boolean recordRealTimeMetrics,
      boolean recordRetryMetrics) {
    CensusStatsModule censusStats =
        new CensusStatsModule(
            STOPWATCH_SUPPLIER,
            true, /* propagateTags */
            recordStartedRpcs,
            recordFinishedRpcs,
            recordRealTimeMetrics,
            recordRetryMetrics);
    return censusStats.getClientInterceptor();
  }

  /**
   * Returns a {@link ClientInterceptor} with custom stats implementation.
   */
  public static ClientInterceptor newClientStatsInterceptor(
      Tagger tagger,
      TagContextBinarySerializer tagCtxSerializer,
      StatsRecorder statsRecorder,
      Supplier<Stopwatch> stopwatchSupplier,
      boolean propagateTags,
      boolean recordStartedRpcs,
      boolean recordFinishedRpcs,
      boolean recordRealTimeMetrics,
      boolean recordRetryMetrics) {
    CensusStatsModule censusStats =
        new CensusStatsModule(
            tagger, tagCtxSerializer, statsRecorder, stopwatchSupplier,
            propagateTags, recordStartedRpcs, recordFinishedRpcs, recordRealTimeMetrics,
            recordRetryMetrics);
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
  public static ServerStreamTracer.Factory getServerStreamTracerFactory(
      boolean recordStartedRpcs,
      boolean recordFinishedRpcs,
      boolean recordRealTimeMetrics) {
    CensusStatsModule censusStats =
        new CensusStatsModule(
            STOPWATCH_SUPPLIER,
            true, /* propagateTags */
            recordStartedRpcs,
            recordFinishedRpcs,
            recordRealTimeMetrics,
            false);
    return censusStats.getServerTracerFactory();
  }

  /**
   * Returns a {@link ServerStreamTracer.Factory} with custom stats implementation.
   */
  public static ServerStreamTracer.Factory getServerStreamTracerFactory(
      Tagger tagger,
      TagContextBinarySerializer tagCtxSerializer,
      StatsRecorder statsRecorder,
      Supplier<Stopwatch> stopwatchSupplier,
      boolean propagateTags,
      boolean recordStartedRpcs,
      boolean recordFinishedRpcs,
      boolean recordRealTimeMetrics) {
    CensusStatsModule censusStats =
        new CensusStatsModule(
            tagger, tagCtxSerializer, statsRecorder, stopwatchSupplier,
            propagateTags, recordStartedRpcs, recordFinishedRpcs, recordRealTimeMetrics, false);
    return censusStats.getServerTracerFactory();
  }

  /**
   * Returns a {@link ServerStreamTracer.Factory} with default tracing implementation.
   */
  public static ServerStreamTracer.Factory getServerStreamTracerFactory() {
    CensusTracingModule censusTracing =
        new CensusTracingModule(
            Tracing.getTracer(),
            Tracing.getPropagationComponent().getBinaryFormat());
    return censusTracing.getServerTracerFactory();
  }

  /**
   * Configures the given {@link ServerBuilder} with the provided serverStreamTracerFactory.
   *
   * @param serverBuilder             the server builder to configure
   * @param serverStreamTracerFactory the server stream tracer factory to configure
   */
  public static void configureServerBuilder(ServerBuilder<?> serverBuilder,
                                            ServerStreamTracer.Factory serverStreamTracerFactory) {
    serverBuilder.addStreamTracerFactory(serverStreamTracerFactory);
  }

}
