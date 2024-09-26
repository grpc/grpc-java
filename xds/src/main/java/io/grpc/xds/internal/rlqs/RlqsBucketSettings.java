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

package io.grpc.xds.internal.rlqs;

import com.google.auto.value.AutoValue;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Duration;
import com.google.protobuf.util.Durations;
import io.grpc.xds.internal.datatype.RateLimitStrategy;
import io.grpc.xds.internal.matchers.HttpMatchInput;

@AutoValue
public abstract class RlqsBucketSettings {

  public abstract ImmutableMap<String, Function<HttpMatchInput, String>> bucketIdBuilder();

  public RlqsBucketId toBucketId(HttpMatchInput input) {
    return null;
  }

  public RateLimitStrategy noAssignmentStrategy() {
    return null;
  }

  public RateLimitStrategy expiredAssignmentStrategy() {
    return null;
  }

  public abstract long reportingIntervalMillis();

  public static RlqsBucketSettings create(
      ImmutableMap<String, Function<HttpMatchInput, String>> bucketIdBuilder,
      Duration reportingInterval) {
    return new AutoValue_RlqsBucketSettings(bucketIdBuilder, Durations.toMillis(reportingInterval));
  }
}
