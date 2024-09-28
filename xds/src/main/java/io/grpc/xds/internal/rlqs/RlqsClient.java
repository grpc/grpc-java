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
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse.BucketAction;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaServiceGrpc;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaServiceGrpc.RateLimitQuotaServiceStub;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaUsageReports;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaUsageReports.BucketQuotaUsage;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.client.Bootstrapper.RemoteServerInfo;
import io.grpc.xds.internal.rlqs.RlqsBucket.RlqsBucketUsage;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RlqsClient {
  private static final Logger logger = Logger.getLogger(RlqsClient.class.getName());

  private final RemoteServerInfo serverInfo;
  private final Consumer<List<RlqsUpdateBucketAction>> bucketsUpdateCallback;
  private final RlqsStream rlqsStream;

  RlqsClient(
      RemoteServerInfo serverInfo, String domain,
      Consumer<List<RlqsUpdateBucketAction>> bucketsUpdateCallback) {
    // TODO(sergiitk): [post] check not null.
    this.serverInfo = serverInfo;
    this.bucketsUpdateCallback = bucketsUpdateCallback;
    this.rlqsStream = new RlqsStream(serverInfo, domain);
  }

  public void sendUsageReports(List<RlqsBucketUsage> bucketUsages) {
    if (bucketUsages.isEmpty()) {
      return;
    }
    // TODO(sergiitk): [impl] offload to serialized executor.
    rlqsStream.reportUsage(bucketUsages);
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
    }

    private BucketQuotaUsage toUsageReport(RlqsBucket.RlqsBucketUsage usage) {
      return BucketQuotaUsage.newBuilder()
          .setBucketId(usage.bucketId().toEnvoyProto())
          .setNumRequestsAllowed(usage.numRequestsAllowed())
          .setNumRequestsDenied(usage.numRequestsDenied())
          .setTimeElapsed(Durations.fromNanos(usage.timeElapsedNanos()))
          .build();
    }

    void reportUsage(List<RlqsBucket.RlqsBucketUsage> usageReports) {
      RateLimitQuotaUsageReports.Builder report = RateLimitQuotaUsageReports.newBuilder();
      if (isFirstReport.compareAndSet(true, false)) {
        report.setDomain(domain);
      }
      for (RlqsBucket.RlqsBucketUsage bucketUsage : usageReports) {
        report.addBucketQuotaUsages(toUsageReport(bucketUsage));
      }
      clientCallStream.onNext(report.build());
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
        ImmutableList.Builder<RlqsUpdateBucketAction> updateActions = ImmutableList.builder();
        for (BucketAction bucketAction : response.getBucketActionList()) {
          updateActions.add(RlqsUpdateBucketAction.fromEnvoyProto(bucketAction));
        }
        bucketsUpdateCallback.accept(updateActions.build());
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
