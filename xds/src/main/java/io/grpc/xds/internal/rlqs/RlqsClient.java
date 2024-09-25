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

import com.google.protobuf.Duration;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse.BucketAction;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse.BucketAction.QuotaAssignmentAction;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaServiceGrpc;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaServiceGrpc.RateLimitQuotaServiceStub;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaUsageReports;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaUsageReports.BucketQuotaUsage;
import io.envoyproxy.envoy.type.v3.RateLimitStrategy;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.client.Bootstrapper.RemoteServerInfo;
import io.grpc.xds.internal.rlqs.RlqsBucket.RateLimitResult;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RlqsClient {
  private static final Logger logger = Logger.getLogger(RlqsClient.class.getName());

  private final RemoteServerInfo serverInfo;
  private final String domain;
  private final RlqsStream rlqsStream;
  private final RlqsBucketCache bucketCache;

  RlqsClient(RemoteServerInfo serverInfo, String domain, RlqsBucketCache bucketCache) {
    this.serverInfo = serverInfo;
    this.domain = domain;
    this.rlqsStream = new RlqsStream(serverInfo, domain);
    this.bucketCache = bucketCache;
  }

  RateLimitResult sendInitialReport(RlqsBucket bucket) {
    bucketCache.insertBucket(bucket);
    // Register first request to the bucket for the initial report.
    RateLimitResult rateLimitResult = bucket.rateLimit();

    // Send initial usage report.
    BucketQuotaUsage bucketQuotaUsage = toUsageReport(bucket);
    // TODO(sergiitk): [IMPL] domain logic not needed anymore.
    bucket.reset();
    rlqsStream.reportUsage(RateLimitQuotaUsageReports.newBuilder()
        .setDomain(domain)
        .addBucketQuotaUsages(bucketQuotaUsage)
        .build());
    return rateLimitResult;
  }

  void sendUsageReports(List<RlqsBucket> buckets) {
    RateLimitQuotaUsageReports.Builder reports = RateLimitQuotaUsageReports.newBuilder();
    for (RlqsBucket bucket : buckets) {
      BucketQuotaUsage bucketQuotaUsage = toUsageReport(bucket);
      bucket.reset();
      reports.addBucketQuotaUsages(bucketQuotaUsage);
    }
    rlqsStream.reportUsage(reports.build());
  }

  void abandonBucket(RlqsBucketId bucketId) {
    bucketCache.deleteBucket(bucketId);
  }

  void updateBucketAssignment(
      RlqsBucketId bucketId, RateLimitStrategy rateLimitStrategy, Duration duration) {
    // Deadline.after(Durations.toMillis(ttl), TimeUnit.MILLISECONDS);
  }

  BucketQuotaUsage toUsageReport(RlqsBucket bucket) {
    // TODO(sergiitk): consider moving to RlqsBucket, and adding something like reportAndReset
    return null;
  }

  public void shutdown() {
    logger.log(Level.FINER, "Shutting down RlqsClient to {0}", serverInfo.target());
    // TODO(sergiitk): [IMPL] RlqsClient shutdown
  }

  public void handleStreamClosed() {
    // TODO(sergiitk): [IMPL] reconnect on stream down.
  }

  private class RlqsStream {
    private final AtomicBoolean isFirstReport = new AtomicBoolean(true);
    private final ManagedChannel channel;
    private final String domain;
    private final ClientCallStreamObserver<RateLimitQuotaUsageReports> clientCallStream;

    RlqsStream(RemoteServerInfo serverInfo, String domain) {
      this.domain = domain;
      channel = Grpc.newChannelBuilder(serverInfo.target(), serverInfo.channelCredentials())
          .keepAliveTime(10, TimeUnit.SECONDS)
          .keepAliveWithoutCalls(true)
          .build();
      // keepalive?
      // TODO(sergiitk): [IMPL] Manage State changes?
      RateLimitQuotaServiceStub stub = RateLimitQuotaServiceGrpc.newStub(channel);
      clientCallStream = (ClientCallStreamObserver<RateLimitQuotaUsageReports>)
          stub.streamRateLimitQuotas(new RlqsStreamObserver());
      // TODO(sergiitk): [IMPL] set on ready handler?
      // TODO(sergiitk): [QUESTION] a nice way to handle setting domain in the first usage report?
      //                 - probably an interceptor
    }

    void reportUsage(RateLimitQuotaUsageReports usageReports) {
      if (isFirstReport.compareAndSet(true, false)) {
        usageReports = usageReports.toBuilder().setDomain(domain).build();
      }
      clientCallStream.onNext(usageReports);
    }

    /**
     * RLQS Stream observer.
     *
     * <p>See {@link io.grpc.alts.internal.AltsHandshakerStub.Reader} for examples.
     * See {@link io.grpc.stub.ClientResponseObserver} for flow control examples.
     */
    private class RlqsStreamObserver implements StreamObserver<RateLimitQuotaResponse> {
      @Override
      public void onNext(RateLimitQuotaResponse response) {
        for (BucketAction bucketAction : response.getBucketActionList()) {
          switch (bucketAction.getBucketActionCase()) {
            case ABANDON_ACTION:
              abandonBucket(RlqsBucketId.fromEnvoyProto(bucketAction.getBucketId()));
              break;
            case QUOTA_ASSIGNMENT_ACTION:
              QuotaAssignmentAction quotaAssignmentAction = bucketAction.getQuotaAssignmentAction();
              updateBucketAssignment(RlqsBucketId.fromEnvoyProto(bucketAction.getBucketId()),
                  quotaAssignmentAction.getRateLimitStrategy(),
                  quotaAssignmentAction.getAssignmentTimeToLive());
              break;
            default:
              // TODO(sergiitk): error
          }
        }
      }

      @Override
      public void onError(Throwable t) {

      }

      @Override
      public void onCompleted() {

      }
    }
  }
}
