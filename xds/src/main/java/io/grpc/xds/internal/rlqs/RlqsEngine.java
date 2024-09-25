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
import io.grpc.xds.internal.matchers.HttpMatchInput;
import io.grpc.xds.internal.matchers.Matcher;
import io.grpc.xds.internal.rlqs.RlqsBucket.RateLimitResult;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RlqsEngine {
  private static final Logger logger = Logger.getLogger(RlqsEngine.class.getName());

  private final RlqsApiClient rlqsApiClient;
  private final Matcher<HttpMatchInput, RlqsBucketSettings> bucketMatchers;
  private final RlqsBucketCache bucketCache;
  private final String configHash;
  private final ScheduledExecutorService timeService;
  private final ConcurrentHashMap<Long, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

  public RlqsEngine(
      RemoteServerInfo rlqsServer, String domain,
      Matcher<HttpMatchInput, RlqsBucketSettings> bucketMatchers, String configHash,
      ScheduledExecutorService timeService) {
    this.bucketMatchers = bucketMatchers;
    this.configHash = configHash;
    this.timeService = timeService;
    bucketCache = new RlqsBucketCache();
    rlqsApiClient = new RlqsApiClient(rlqsServer, domain, bucketCache);
  }

  public RateLimitResult evaluate(HttpMatchInput input) {
    RlqsBucketSettings bucketSettings = bucketMatchers.match(input);
    RlqsBucketId bucketId = bucketSettings.toBucketId(input);
    RlqsBucket bucket = bucketCache.getBucket(bucketId);
    if (bucket != null) {
      return bucket.rateLimit();
    }
    bucket = new RlqsBucket(bucketId, bucketSettings);
    RateLimitResult rateLimitResult = rlqsApiClient.processFirstBucketRequest(bucket);
    registerReportTimer(bucketSettings.reportingIntervalMillis());
    return rateLimitResult;
  }

  private void registerReportTimer(final long reportingIntervalMillis) {
    // TODO(sergiitk): [IMPL] cap the interval.
    if (timers.containsKey(reportingIntervalMillis)) {
      return;
    }
    // TODO(sergiitk): [IMPL] consider manually extending.
    ScheduledFuture<?> schedule = timeService.scheduleWithFixedDelay(
        () -> reportBucketsWithInterval(reportingIntervalMillis),
        reportingIntervalMillis,
        reportingIntervalMillis,
        TimeUnit.MILLISECONDS);
    timers.put(reportingIntervalMillis, schedule);
  }

  private void reportBucketsWithInterval(long reportingIntervalMillis) {
    ImmutableList<RlqsBucket> bucketsToReport =
        bucketCache.getBucketsToReport(reportingIntervalMillis);
    // TODO(sergiitk): [IMPL] destroy timer if empty
    rlqsApiClient.sendUsageReports(bucketsToReport);
  }

  public void shutdown() {
    // TODO(sergiitk): [IMPL] Timers shutdown
    // TODO(sergiitk): [IMPL] RlqsEngine shutdown
    logger.log(Level.FINER, "Shutting down RlqsEngine with hash {0}", configHash);
    rlqsApiClient.shutdown();
  }
}
