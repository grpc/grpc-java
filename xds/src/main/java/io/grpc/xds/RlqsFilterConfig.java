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

package io.grpc.xds;

import com.google.auto.value.AutoValue;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.internal.datatype.GrpcService;
import io.grpc.xds.internal.matchers.HttpMatchInput;
import io.grpc.xds.internal.matchers.Matcher;
import io.grpc.xds.internal.rlqs.RlqsBucketSettings;
import javax.annotation.Nullable;

/** Parsed RateLimitQuotaFilterConfig. */
@AutoValue
public abstract class RlqsFilterConfig implements FilterConfig {

  @Override
  public final String typeUrl() {
    return RlqsFilter.TYPE_URL;
  }

  public abstract String domain();

  @Nullable
  public abstract GrpcService rlqsService();

  @Nullable
  public abstract Matcher<HttpMatchInput, RlqsBucketSettings> bucketMatchers();

  public static Builder builder() {
    return new AutoValue_RlqsFilterConfig.Builder();
  }

  abstract Builder toBuilder();

  @AutoValue.Builder
  abstract static class Builder {
    abstract Builder domain(String domain);

    abstract Builder rlqsService(GrpcService rlqsService);

    public abstract Builder bucketMatchers(Matcher<HttpMatchInput, RlqsBucketSettings> matcher);

    abstract RlqsFilterConfig build();
  }

}
