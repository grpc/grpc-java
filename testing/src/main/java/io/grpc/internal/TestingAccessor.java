/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

import io.opencensus.stats.StatsRecorder;
import io.opencensus.tags.Tagger;
import io.opencensus.tags.propagation.TagContextBinarySerializer;

/**
 * Test helper that allows accessing package-private stuff.
 */
public final class TestingAccessor {
  /**
   * Sets a custom stats implementation for tests.
   */
  public static void setStatsImplementation(
      AbstractManagedChannelImplBuilder<?> builder,
      Tagger tagger,
      TagContextBinarySerializer tagCtxSerializer,
      StatsRecorder statsRecorder) {
    builder.overrideCensusStatsModule(
        new CensusStatsModule(
            tagger, tagCtxSerializer, statsRecorder, GrpcUtil.STOPWATCH_SUPPLIER, true));
  }

  /**
   * Sets a custom stats implementation for tests.
   */
  public static void setStatsImplementation(
      AbstractServerImplBuilder<?> builder,
      Tagger tagger,
      TagContextBinarySerializer tagCtxSerializer,
      StatsRecorder statsRecorder) {
    builder.overrideCensusStatsModule(
        new CensusStatsModule(
            tagger, tagCtxSerializer, statsRecorder, GrpcUtil.STOPWATCH_SUPPLIER, true));
  }

  private TestingAccessor() {
  }
}
