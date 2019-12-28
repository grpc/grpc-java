/*
 * Copyright 2017 The gRPC Authors
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

package io.grpc.internal;

import io.grpc.census.CensusStatsAccessor;
import io.grpc.census.CensusStatsModule;

/**
 * Test helper that allows accessing package-private stuff.
 */
public final class TestingAccessor {
  /**
   * Sets a custom stats implementation for tests.
   */
  public static void setStatsImplementation(
      AbstractManagedChannelImplBuilder<?> builder, CensusStatsModule censusStats) {
    builder.setCensusStatsInterceptor(CensusStatsAccessor.getClientInterceptor(censusStats));
  }

  /**
   * Sets a custom stats implementation for tests.
   */
  public static void setStatsImplementation(
      AbstractServerImplBuilder<?> builder, CensusStatsModule censusStats) {
    builder.setCensusStreamTracerFactory(
        CensusStatsAccessor.getServerStreamTracerFactory(censusStats));
  }

  private TestingAccessor() {
  }
}
