/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds.internal.extauthz;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.xds.internal.headermutations.HeaderMutations.ResponseHeaderMutations;
import java.util.Optional;

/**
 * Represents the outcome of an authorization check, detailing whether the request is allowed or
 * denied and including any associated headers or status information.
 */
@AutoValue
public abstract class AuthzResponse {

  /** Defines the authorization decision. */
  public enum Decision {
    /** The request is permitted. */
    ALLOW,
    /** The request is rejected. */
    DENY,
  }

  /** Creates a builder for an ALLOW response, initializing with the specified headers. */
  public static Builder allow(Metadata headers) {
    return new AutoValue_AuthzResponse.Builder().setDecision(Decision.ALLOW)
        .setResponseHeaderMutations(ResponseHeaderMutations.create(ImmutableList.of()))
        .setHeaders(headers);
  }

  /** Creates a builder for a DENY response, initializing with the specified status. */
  public static Builder deny(Status status) {
    return new AutoValue_AuthzResponse.Builder().setDecision(Decision.DENY)
        .setResponseHeaderMutations(ResponseHeaderMutations.create(ImmutableList.of()))
        .setStatus(status);
  }

  /** Returns the authorization decision. */
  public abstract Decision decision();

  /**
   * For DENY decisions, this provides the status to be returned to the calling client. It is empty
   * for ALLOW decisions.
   */
  public abstract Optional<Status> status();

  /**
   * For ALLOW decisions, this provides the headers to be appended to the request headers for
   * upstream. It is empty for DENY decisions.
   */
  public abstract Optional<Metadata> headers();

  /**
   * Returns mutations to be applied to the response headers. This is used for both ALLOW and DENY
   * decisions.
   */
  public abstract ResponseHeaderMutations responseHeaderMutations();

  /** Builder for creating {@link AuthzResponse} instances. */
  @AutoValue.Builder
  public abstract static class Builder {

    abstract Builder setDecision(Decision decision);

    abstract Builder setStatus(Status status);

    abstract Builder setHeaders(Metadata headers);

    public abstract Builder setResponseHeaderMutations(
        ResponseHeaderMutations responseHeaderMutations);

    public abstract AuthzResponse build();
  }
}
