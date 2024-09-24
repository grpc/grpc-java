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

import io.grpc.xds.client.Bootstrapper.RemoteServerInfo;
import io.grpc.xds.internal.matchers.HttpMatchInput;
import io.grpc.xds.internal.matchers.Matcher;
import io.grpc.xds.internal.rlqs.RlqsBucket.RateLimitResult;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RlqsClient {
  private static final Logger logger = Logger.getLogger(RlqsClient.class.getName());

  private final RlqsApiClient rlqsApiClient;
  private final Matcher<HttpMatchInput, RlqsBucketSettings> bucketMatchers;
  private final RlqsBucketCache bucketCache;
  private final String clientHash;

  public RlqsClient(
      RemoteServerInfo rlqsServer, String domain,
      Matcher<HttpMatchInput, RlqsBucketSettings> bucketMatchers, String clientHash) {
    this.bucketMatchers = bucketMatchers;
    this.clientHash = clientHash;
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
    // TODO(sergiitk): register tickers
    registerTimers(bucket, bucketSettings);
    return rateLimitResult;
  }

  private void registerTimers(RlqsBucket bucket, RlqsBucketSettings bucketSettings) {
  }

  public void shutdown() {
    // TODO(sergiitk): [IMPL] RlqsClient shutdown
    logger.log(Level.FINER, "Shutting down RlqsClient with hash {0}", clientHash);
    rlqsApiClient.shutdown();
  }
}
