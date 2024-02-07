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

package io.grpc.xds.internal.rlqs;

import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;
import com.google.common.collect.ImmutableMap;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.BucketId;
import java.util.Map;

@AutoValue
public abstract class RlqsBucketId {
  // No class loading deadlock, see
  // https://github.com/google/error-prone/issues/2062#issuecomment-1566253739
  public static final RlqsBucketId EMPTY = create(ImmutableMap.of());

  public abstract ImmutableMap<String, String> bucketId();

  public static RlqsBucketId create(Map<String, String> bucketIdMap) {
    if (bucketIdMap.isEmpty()) {
      return EMPTY;
    }
    return new AutoValue_RlqsBucketId(ImmutableMap.copyOf(bucketIdMap));
  }

  public final boolean isEmpty() {
    return bucketId().isEmpty();
  }

  public static RlqsBucketId fromEnvoyProto(BucketId envoyProto) {
    return RlqsBucketId.create(ImmutableMap.copyOf(envoyProto.getBucketMap().entrySet()));
  }

  @Memoized
  public BucketId toEnvoyProto() {
    // TODO(sergiitk): [impl] can be cached.
    return BucketId.newBuilder().putAllBucket(bucketId()).build();
  }
}
