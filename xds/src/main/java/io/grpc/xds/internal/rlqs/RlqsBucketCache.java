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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class RlqsBucketCache {
  // TODO(sergiitk): consider volatile + synchronize instead
  private final ConcurrentMap<Long, Set<RlqsBucket>> bucketsPerInterval = new ConcurrentHashMap<>();
  private final ConcurrentMap<RlqsBucketId, RlqsBucket> buckets = new ConcurrentHashMap<>();

  RlqsBucket getBucket(RlqsBucketId bucketId) {
    return buckets.get(bucketId);
  }

  void insertBucket(RlqsBucket bucket) {
    // read synchronize trick
    if (buckets.get(bucket.getBucketId()) != null) {
      return;
    }
    synchronized (this) {
      long interval = bucket.getReportingIntervalMillis();
      if (!bucketsPerInterval.containsKey(interval)) {
        bucketsPerInterval.put(interval, Sets.newConcurrentHashSet());
      }

      bucketsPerInterval.get(bucket.getReportingIntervalMillis()).add(bucket);
      buckets.put(bucket.getBucketId(), bucket);
    }
  }

  void deleteBucket(RlqsBucketId bucketId) {
    RlqsBucket bucket = buckets.get(bucketId);
    bucketsPerInterval.get(bucket.getReportingIntervalMillis()).remove(bucket);
    buckets.remove(bucket.getBucketId());
  }

  void updateBucket(RlqsBucketId bucketId, RateLimitStrategy rateLimitStrategy, long ttlMillis) {
    RlqsBucket bucket = buckets.get(bucketId);
    bucket.updateAction(rateLimitStrategy, ttlMillis);
  }

  public ImmutableList<RlqsBucket> getBucketsToReport(long reportingIntervalMillis) {
    ImmutableList.Builder<RlqsBucket> report = ImmutableList.builder();
    for (RlqsBucket bucket : bucketsPerInterval.get(reportingIntervalMillis)) {
      report.add(bucket);
    }
    return report.build();
  }
}
