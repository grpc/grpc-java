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
import com.google.common.base.Preconditions;
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse.BucketAction.QuotaAssignmentAction;
import io.grpc.xds.internal.datatype.RateLimitStrategy;
import javax.annotation.Nullable;

@AutoValue
public abstract class RlqsUpdateBucketAction {

  public abstract RlqsBucketId bucketId();

  @Nullable public abstract RateLimitStrategy rateLimitStrategy();

  public abstract long ttlMillis();

  public static RlqsUpdateBucketAction ofQuotaAssignmentAction(
      RlqsBucketId bucketId, RateLimitStrategy rateLimitStrategy, long ttlMillis) {
    Preconditions.checkNotNull(rateLimitStrategy, "rateLimitStrategy");
    return new AutoValue_RlqsUpdateBucketAction(bucketId, rateLimitStrategy, ttlMillis);
  }

  public static RlqsUpdateBucketAction ofQuotaAbandonAction(RlqsBucketId bucketId) {
    return new AutoValue_RlqsUpdateBucketAction(bucketId, null, 0);
  }

  public static RlqsUpdateBucketAction fromEnvoyProto(
      RateLimitQuotaResponse.BucketAction bucketAction) {
    RlqsBucketId bucketId = RlqsBucketId.fromEnvoyProto(bucketAction.getBucketId());
    switch (bucketAction.getBucketActionCase()) {
      case ABANDON_ACTION:
        return RlqsUpdateBucketAction.ofQuotaAbandonAction(bucketId);
      case QUOTA_ASSIGNMENT_ACTION:
        QuotaAssignmentAction quotaAssignment = bucketAction.getQuotaAssignmentAction();
        RateLimitStrategy strategy = RateLimitStrategy.ALLOW_ALL;
        if (quotaAssignment.hasRateLimitStrategy()) {
          strategy = RateLimitStrategy.fromEnvoyProto(quotaAssignment.getRateLimitStrategy());
        }
        return RlqsUpdateBucketAction.ofQuotaAssignmentAction(bucketId, strategy,
            Durations.toMillis(quotaAssignment.getAssignmentTimeToLive()));
      default:
        // TODO(sergiitk): [impl] error
        throw new UnsupportedOperationException("Wrong BlanketRule proto");
    }
  }
}
