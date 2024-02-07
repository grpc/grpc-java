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

/** Parsed RateLimitQuotaFilterConfig. */
@AutoValue
abstract class RlqsFilterConfig implements FilterConfig {

  @Override
  public final String typeUrl() {
    return RlqsFilter.TYPE_URL;
  }

  abstract String domain();
  abstract GrpcService rlqsService();

  public static RlqsFilterConfig create(String domain, GrpcService rlqsService) {
    return new AutoValue_RlqsFilterConfig(domain, rlqsService);
  }

  // TODO(sergiitk): add rlqs_server, bucket_matchers.
}
