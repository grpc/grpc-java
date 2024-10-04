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
import io.grpc.xds.client.Bootstrapper.RemoteServerInfo;
import io.grpc.xds.internal.datatype.RateLimitStrategy;
import io.grpc.xds.internal.matchers.HttpMatchInput;
import io.grpc.xds.internal.matchers.Matcher;
import io.grpc.xds.internal.rlqs.RlqsBucket.RlqsBucketUsage;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RlqsEngine {
  private static final Logger logger = Logger.getLogger(RlqsEngine.class.getName());

  private final RlqsClient rlqsClient;
  private final Matcher<HttpMatchInput, RlqsBucketSettings> bucketMatchers;
  private final RlqsBucketCache bucketCache;
  private final long configHash;
  private final ScheduledExecutorService scheduler;
  private final ConcurrentMap<Long, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

  public RlqsEngine(
      RemoteServerInfo rlqsServer, String domain,
      Matcher<HttpMatchInput, RlqsBucketSettings> bucketMatchers, long configHash,
      ScheduledExecutorService scheduler) {
    this.bucketMatchers = bucketMatchers;
    this.configHash = configHash;
    this.scheduler = scheduler;
    bucketCache = new RlqsBucketCache();
    rlqsClient = new RlqsClient(rlqsServer, domain, this::onBucketsUpdate);
  }

  public RlqsRateLimitResult rateLimit(HttpMatchInput input) {
    RlqsBucketSettings bucketSettings = bucketMatchers.match(input);
    RlqsBucketId bucketId = bucketSettings.toBucketId(input);
    // Special case when bucket id builder not set, or has no values.
    if (bucketId.isEmpty()) {
      return rateLimitWithoutReports(bucketSettings);
    }
    RlqsBucket bucket = bucketCache.getOrCreate(bucketId, bucketSettings, newBucket -> {
      // Called if a new bucket was created.
      scheduleImmediateReport(newBucket);
      registerReportTimer(newBucket.getReportingIntervalMillis());
    });
    return bucket.rateLimit();
  }

  private static RlqsRateLimitResult rateLimitWithoutReports(RlqsBucketSettings bucketSettings) {
    if (bucketSettings.noAssignmentStrategy().rateLimit()) {
      return RlqsRateLimitResult.deny(bucketSettings.denyResponse());
    }
    return RlqsRateLimitResult.allow();
  }

  private void onBucketsUpdate(List<RlqsUpdateBucketAction> bucketActions) {
    // TODO(sergiitk): [impl] ensure no more than 1 update at a time.
    for (RlqsUpdateBucketAction bucketAction : bucketActions) {
      RlqsBucketId bucketId = bucketAction.bucketId();
      RateLimitStrategy rateLimitStrategy = bucketAction.rateLimitStrategy();
      if (rateLimitStrategy == null) {
        bucketCache.deleteBucket(bucketId);
        continue;
      }
      bucketCache.updateBucket(bucketId, rateLimitStrategy, bucketAction.ttlMillis());
    }
  }

  private void scheduleImmediateReport(RlqsBucket newBucket) {
    try {
      ScheduledFuture<?> unused = scheduler.schedule(
          () -> rlqsClient.sendUsageReports(ImmutableList.of(newBucket.snapshotAndResetUsage())),
          1, TimeUnit.MICROSECONDS);
    } catch (RejectedExecutionException e) {
      // Shouldn't happen.
      logger.finer("Couldn't schedule immediate report for bucket " + newBucket.getBucketId());
    }
  }

  private void registerReportTimer(final long intervalMillis) {
    // TODO(sergiitk): [IMPL] cap the interval.
    timers.computeIfAbsent(intervalMillis, k -> newTimer(intervalMillis));
  }

  private ScheduledFuture<?> newTimer(final long intervalMillis) {
    return scheduler.scheduleWithFixedDelay(
        () -> reportBucketsWithInterval(intervalMillis),
        intervalMillis,
        intervalMillis,
        TimeUnit.MILLISECONDS);
  }

  private void reportBucketsWithInterval(long intervalMillis) {
    ImmutableList.Builder<RlqsBucketUsage> reports = ImmutableList.builder();
    for (RlqsBucket bucket : bucketCache.getBucketsToReport(intervalMillis)) {
      reports.add(bucket.snapshotAndResetUsage());
    }
    rlqsClient.sendUsageReports(reports.build());
  }

  public void shutdown() {
    // TODO(sergiitk): [IMPL] Timers shutdown
    // TODO(sergiitk): [IMPL] RlqsEngine shutdown
    logger.log(Level.FINER, "Shutting down RlqsEngine with hash {0}", configHash);
    rlqsClient.shutdown();
  }
}
