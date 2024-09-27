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


import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import io.grpc.xds.internal.datatype.RateLimitStrategy;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

final class RlqsBucketCache {
  // TODO(sergiitk): consider volatile + synchronize instead
  private final ConcurrentMap<Long, Set<RlqsBucket>> bucketsPerInterval = new ConcurrentHashMap<>();
  private final ConcurrentMap<RlqsBucketId, RlqsBucket> buckets = new ConcurrentHashMap<>();

  RlqsBucket getOrCreate(
      RlqsBucketId bucketId, RlqsBucketSettings bucketSettings, Consumer<RlqsBucket> onCreate) {
    // read synchronize trick
    RlqsBucket bucket = buckets.get(bucketId);
    if (bucket != null) {
      return bucket;
    }
    synchronized (this) {
      bucket = new RlqsBucket(bucketId, bucketSettings);
      long interval = bucket.getReportingIntervalMillis();
      bucketsPerInterval.computeIfAbsent(interval, k -> Sets.newConcurrentHashSet()).add(bucket);
      buckets.put(bucket.getBucketId(), bucket);
      // TODO(sergiitk): [IMPL] call async
      onCreate.accept(bucket);
      return bucket;
    }
  }

  void deleteBucket(RlqsBucketId bucketId) {
    RlqsBucket bucket = buckets.get(bucketId);
    if (bucket == null) {
      return;
    }
    synchronized (this) {
      buckets.remove(bucket.getBucketId());
      bucketsPerInterval.computeIfPresent(bucket.getReportingIntervalMillis(), (k, buckets) -> {
        buckets.remove(bucket);
        return buckets.isEmpty() ? null : buckets;
      });
    }
  }

  void updateBucket(RlqsBucketId bucketId, RateLimitStrategy rateLimitStrategy, long ttlMillis) {
    RlqsBucket bucket = buckets.get(bucketId);
    bucket.updateAction(rateLimitStrategy, ttlMillis);
  }

  public ImmutableList<RlqsBucket> getBucketsToReport(long reportingIntervalMillis) {
    return ImmutableList.copyOf(
        bucketsPerInterval.getOrDefault(reportingIntervalMillis, Collections.emptySet()));
  }
}
