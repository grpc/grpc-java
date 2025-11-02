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
import io.grpc.Status;
import io.grpc.xds.internal.headermutations.HeaderMutations;
import java.util.Optional;

/**
 * Represents the outcome of an authorization check, detailing whether the request is allowed or
 * denied and including any associated headers or status information.
 */
@AutoValue
abstract class AuthzResponse {

  /** Defines the authorization decision. */
  public enum Decision {
    /** The request is permitted. */
    ALLOW,
    /** The request is rejected. */
    DENY,
  }

  private static final HeaderMutations EMPTY_MUTATIONS =
      HeaderMutations.create(ImmutableList.of(), ImmutableList.of());

  /**
   * Creates a builder for an ALLOW response, initializing with the specified request header
   * mutations.
   */
  static Builder allow(HeaderMutations requestHeaderMutations) {
    return new AutoValue_AuthzResponse.Builder().setDecision(Decision.ALLOW)
        .setResponseHeaderMutations(EMPTY_MUTATIONS)
        .setRequestHeaderMutations(requestHeaderMutations);
  }

  /** Creates a builder for a DENY response, initializing with the specified status. */
  static Builder deny(Status status) {
    return new AutoValue_AuthzResponse.Builder().setDecision(Decision.DENY)
        .setResponseHeaderMutations(EMPTY_MUTATIONS)
        .setRequestHeaderMutations(EMPTY_MUTATIONS)
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
   * Returns mutations to be applied to the request headers. This is used for ALLOW decisions.
   */
  public abstract HeaderMutations requestHeaderMutations();

  /**
   * Returns mutations to be applied to the response headers. This is used for both ALLOW and DENY
   * decisions.
   */
  public abstract HeaderMutations responseHeaderMutations();

  /** Builder for creating {@link AuthzResponse} instances. */
  @AutoValue.Builder
  abstract static class Builder {

    abstract Builder setDecision(Decision decision);

    abstract Builder setStatus(Status status);

    public abstract Builder setRequestHeaderMutations(
        HeaderMutations requestHeaderMutations);

    public abstract Builder setResponseHeaderMutations(
        HeaderMutations responseHeaderMutations);

    public abstract AuthzResponse build();
  }
}
