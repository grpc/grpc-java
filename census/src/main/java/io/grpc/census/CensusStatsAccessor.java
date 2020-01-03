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
import io.grpc.ServerStreamTracer;

/**
 * Accessor for getting {@link ClientInterceptor} or {@link ServerStreamTracer.Factory} with
 * default Census stats implementation.
 */
public final class CensusStatsAccessor {

  // Prevent instantiation.
  private CensusStatsAccessor() {
  }

  /**
   * Returns a {@link ClientInterceptor} with default stats implementation.
   */
  public static ClientInterceptor getClientInterceptor(
      Supplier<Stopwatch> stopwatchSupplier,
      boolean propagateTags,
      boolean recordStartedRpcs,
      boolean recordFinishedRpcs,
      boolean recordRealTimeMetrics) {
    CensusStatsModule censusStats =
        new CensusStatsModule(
            stopwatchSupplier,
            propagateTags,
            recordStartedRpcs,
            recordFinishedRpcs,
            recordRealTimeMetrics);
    return getClientInterceptor(censusStats);
  }

  /**
   * Returns a {@link ClientInterceptor} with custom stats implementation.
   */
  public static ClientInterceptor getClientInterceptor(CensusStatsModule censusStats) {
    return censusStats.getClientInterceptor();
  }

  /**
   * Returns a {@link ServerStreamTracer.Factory} with default stats implementation.
   */
  public static ServerStreamTracer.Factory getServerStreamTracerFactory(
      Supplier<Stopwatch> stopwatchSupplier,
      boolean propagateTags,
      boolean recordStartedRpcs,
      boolean recordFinishedRpcs,
      boolean recordRealTimeMetrics) {
    CensusStatsModule censusStats =
        new CensusStatsModule(
            stopwatchSupplier,
            propagateTags,
            recordStartedRpcs,
            recordFinishedRpcs,
            recordRealTimeMetrics);
    return getServerStreamTracerFactory(censusStats);
  }

  /**
   * Returns a {@link ServerStreamTracer.Factory} with custom stats implementation.
   */
  public static ServerStreamTracer.Factory getServerStreamTracerFactory(
      CensusStatsModule censusStats) {
    return censusStats.getServerTracerFactory();
  }
}
