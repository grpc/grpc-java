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
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.Optional;
import javax.annotation.Nullable;

@AutoValue
public abstract class RateLimitResult {
  // TODO(sergiitk): make RateLimitResult an interface,
  //  RlqsRateLimitResult extends it - which contains DenyResponse.

  public abstract Optional<DenyResponse> denyResponse();

  public final boolean isAllowed() {
    return !isDenied();
  }

  public final boolean isDenied() {
    return denyResponse().isPresent();
  }

  public static RateLimitResult deny(@Nullable DenyResponse denyResponse) {
    if (denyResponse == null) {
      denyResponse = DenyResponse.create();
    }
    return new AutoValue_RateLimitResult(Optional.of(denyResponse));
  }

  public static RateLimitResult allow() {
    return new AutoValue_RateLimitResult(Optional.empty());
  }

  @AutoValue
  public abstract static class DenyResponse {
    public abstract Status status();

    public abstract Metadata headersToAdd();

    public static DenyResponse create(Status status, Metadata headersToAdd) {
      return new AutoValue_RateLimitResult_DenyResponse(status, headersToAdd);
    }

    public static DenyResponse create(Status status) {
      return create(status, new Metadata());
    }

    public static DenyResponse create() {
      return create(Status.UNAVAILABLE.withDescription(""));
    }
  }
}
