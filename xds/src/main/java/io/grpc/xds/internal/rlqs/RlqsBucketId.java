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
import com.google.common.collect.ImmutableMap;
import java.util.Map;

@AutoValue
public abstract class RlqsBucketId {
  public abstract ImmutableMap<String, String> bucketId();

  public static RlqsBucketId create(ImmutableMap<String, String> bucketId) {
    return new AutoValue_RlqsBucketId(bucketId);
  }

  public static RlqsBucketId fromEnvoyProto(
      io.envoyproxy.envoy.service.rate_limit_quota.v3.BucketId envoyProto) {
    ImmutableMap.Builder<String, String> bucketId = ImmutableMap.builder();
    for (Map.Entry<String, String> entry : envoyProto.getBucketMap().entrySet()) {
      bucketId.put(entry.getKey(), entry.getValue());
    }
    return RlqsBucketId.create(bucketId.build());
  }

}
