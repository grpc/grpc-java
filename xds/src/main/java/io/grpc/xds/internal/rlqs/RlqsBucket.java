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
import io.grpc.Deadline;
import io.grpc.xds.internal.datatype.RateLimitStrategy;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;


public class RlqsBucket {
  private final RlqsBucketId bucketId;
  private final long reportingIntervalMillis;

  private final RateLimitStrategy noAssignmentStrategy;
  private final RateLimitStrategy expiredAssignmentStrategy;

  // TODO(sergiitk): [impl] consider AtomicLongFieldUpdater
  private final AtomicLong lastSnapshotTimeNanos = new AtomicLong(-1);
  private final AtomicLong numRequestsAllowed = new AtomicLong();
  private final AtomicLong numRequestsDenied = new AtomicLong();

  // TODO(sergiitk): [impl] consider AtomicReferenceFieldUpdater
  @Nullable
  private volatile RateLimitStrategy assignmentStrategy = null;
  private volatile long assignmentExpiresTimeNanos;
  // TODO(sergiitk): needed for expired_assignment_behavior_timeout
  private volatile long lastAssignmentTimeNanos;

  RlqsBucket(RlqsBucketId bucketId, RlqsBucketSettings bucketSettings) {
    // TODO(sergiitk): [design] consider lock per bucket instance
    this.bucketId = bucketId;
    reportingIntervalMillis = bucketSettings.reportingIntervalMillis();
    expiredAssignmentStrategy = bucketSettings.expiredAssignmentStrategy();
    noAssignmentStrategy = bucketSettings.noAssignmentStrategy();
  }

  public RlqsBucketId getBucketId() {
    return bucketId;
  }

  public long getReportingIntervalMillis() {
    return reportingIntervalMillis;
  }

  public RateLimitResult rateLimit() {
    RateLimitResult rateLimitResult = resolveStrategy().rateLimit();
    if (rateLimitResult.isAllowed()) {
      numRequestsAllowed.incrementAndGet();
    } else {
      numRequestsDenied.incrementAndGet();
    }
    // TODO(sergiitk): [impl] when RateLimitResult broken into RlqsRateLimitResult,
    //   augment with deny response strategy
    return rateLimitResult;
  }

  private RateLimitStrategy resolveStrategy() {
    if (assignmentStrategy == null) {
      return noAssignmentStrategy;
    }
    if (assignmentExpiresTimeNanos > nanoTimeNow()) {
      // TODO(sergiitk): handle expired behavior properly: it has own ttl,
      //   after the bucket is abandoned.
      //   Also, there's reuse last assignment option.
      return expiredAssignmentStrategy;
    }
    return assignmentStrategy;
  }

  public RlqsBucketUsage snapshotAndResetUsage() {
    // TODO(sergiitk): [IMPL] ensure synchronized
    long snapAllowed = numRequestsAllowed.get();
    long snapDenied = numRequestsDenied.get();
    long snapTime = nanoTimeNow();

    // Reset stats.
    numRequestsAllowed.addAndGet(-snapAllowed);
    numRequestsDenied.addAndGet(-snapDenied);

    long lastSnapTime = lastSnapshotTimeNanos.getAndSet(snapTime);
    // First snapshot.
    if (lastSnapTime < 0) {
      lastSnapTime = snapTime;
    }
    return RlqsBucketUsage.create(bucketId, snapAllowed, snapDenied, snapTime - lastSnapTime);
  }

  public void updateAction(RateLimitStrategy strategy, long ttlMillis) {
    // TODO(sergiitk): [IMPL] ensure synchronized
    lastAssignmentTimeNanos = nanoTimeNow();
    assignmentExpiresTimeNanos = lastAssignmentTimeNanos + (ttlMillis * 1_000_000);
    assignmentStrategy = strategy;
  }

  private static long nanoTimeNow() {
    return Deadline.getSystemTicker().nanoTime();
  }

  @AutoValue
  public abstract static class RlqsBucketUsage {

    public abstract RlqsBucketId bucketId();

    public abstract long numRequestsAllowed();

    public abstract long numRequestsDenied();

    public abstract long timeElapsedNanos();

    public static RlqsBucketUsage create(
        RlqsBucketId bucketId, long numRequestsAllowed, long numRequestsDenied,
        long timeElapsedNanos) {
      return new AutoValue_RlqsBucket_RlqsBucketUsage(bucketId, numRequestsAllowed,
          numRequestsDenied, timeElapsedNanos);
    }
  }
}
