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
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Duration;
import com.google.protobuf.util.Durations;
import io.grpc.xds.internal.datatype.RateLimitStrategy;
import io.grpc.xds.internal.matchers.HttpMatchInput;
import io.grpc.xds.internal.rlqs.RlqsRateLimitResult.DenyResponse;
import java.util.function.Function;
import javax.annotation.Nullable;

@AutoValue
public abstract class RlqsBucketSettings {
  // TODO(sergiitk): [IMPL] this misses most of the parsing and implementation.

  @Nullable
  public abstract ImmutableMap<String, Function<HttpMatchInput, String>> bucketIdBuilder();

  abstract RlqsBucketId staticBucketId();

  public abstract long reportingIntervalMillis();

  public final RlqsBucketId toBucketId(HttpMatchInput input) {
    if (bucketIdBuilder() == null) {
      return staticBucketId();
    }
    return processBucketBuilder(bucketIdBuilder(), input);
  }

  public RateLimitStrategy noAssignmentStrategy() {
    return null;
  }

  public DenyResponse denyResponse() {
    return DenyResponse.DEFAULT;
  }

  public RateLimitStrategy expiredAssignmentStrategy() {
    return null;
  }

  public static RlqsBucketSettings create(
      ImmutableMap<String, Function<HttpMatchInput, String>> bucketIdBuilder,
      Duration reportingInterval) {
    // TODO(sergiitk): instead of create, use Builder pattern.
    RlqsBucketId staticBucketId = processBucketBuilder(bucketIdBuilder, null);
    return new AutoValue_RlqsBucketSettings(
        staticBucketId.isEmpty() ? bucketIdBuilder : null,
        staticBucketId,
        Durations.toMillis(reportingInterval));
  }

  private static RlqsBucketId processBucketBuilder(
      ImmutableMap<String, Function<HttpMatchInput, String>> bucketIdBuilder,
      HttpMatchInput input) {
    ImmutableMap.Builder<String, String> bucketIdMapBuilder = ImmutableMap.builder();
    if (input == null) {
      // TODO(sergiitk): [IMPL] calculate static map
      return RlqsBucketId.EMPTY;
    }
    for (String key : bucketIdBuilder.keySet()) {
      Function<HttpMatchInput, String> fn = bucketIdBuilder.get(key);
      String value = null;
      if (fn != null) {
        value = fn.apply(input);
      }
      bucketIdMapBuilder.put(key, value != null ? value : "");
    }
    ImmutableMap<String, String> bucketIdMap = bucketIdMapBuilder.build();
    return bucketIdMap.isEmpty() ? RlqsBucketId.EMPTY : RlqsBucketId.create(bucketIdMap);
  }
}
