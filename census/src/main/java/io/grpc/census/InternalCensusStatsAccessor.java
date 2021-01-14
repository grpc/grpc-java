/*
 * Copyright 2019 The gRPC Authors
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
import io.grpc.Internal;
import io.grpc.ServerStreamTracer;
import io.opencensus.stats.StatsRecorder;
import io.opencensus.tags.Tagger;
import io.opencensus.tags.propagation.TagContextBinarySerializer;

/**
 * Accessor for getting {@link ClientInterceptor} or {@link ServerStreamTracer.Factory} with
 * default Census stats implementation.
 */
@Internal
public final class InternalCensusStatsAccessor {

  private static final Supplier<Stopwatch> STOPWATCH_SUPPLIER = new Supplier<Stopwatch>() {
    @Override
    public Stopwatch get() {
      return Stopwatch.createUnstarted();
    }
  };

  // Prevent instantiation.
  private InternalCensusStatsAccessor() {
  }

  /**
   * Returns a {@link ClientInterceptor} with default stats implementation.
   */
  public static ClientInterceptor getClientInterceptor(
      boolean recordStartedRpcs,
      boolean recordFinishedRpcs,
      boolean recordRealTimeMetrics) {
    CensusStatsModule censusStats =
        new CensusStatsModule(
            STOPWATCH_SUPPLIER,
            true, /* propagateTags */
            recordStartedRpcs,
            recordFinishedRpcs,
            recordRealTimeMetrics);
    return censusStats.getClientInterceptor();
  }

  /**
   * Returns a {@link ClientInterceptor} with custom stats implementation.
   */
  public static ClientInterceptor getClientInterceptor(
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
            propagateTags, recordStartedRpcs, recordFinishedRpcs, recordRealTimeMetrics);
    return censusStats.getClientInterceptor();
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
            recordRealTimeMetrics);
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
            propagateTags, recordStartedRpcs, recordFinishedRpcs, recordRealTimeMetrics);
    return censusStats.getServerTracerFactory();
  }
}
