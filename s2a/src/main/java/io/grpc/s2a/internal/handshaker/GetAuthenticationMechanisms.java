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

package io.grpc.s2a.internal.handshaker;

import com.google.errorprone.annotations.Immutable;
import io.grpc.s2a.internal.handshaker.S2AIdentity;
import io.grpc.s2a.internal.handshaker.tokenmanager.AccessTokenManager;
import java.util.Optional;

/** Retrieves the authentication mechanism for a given local identity. */
@Immutable
final class GetAuthenticationMechanisms {
  private static final Optional<AccessTokenManager> TOKEN_MANAGER = AccessTokenManager.create();

  /**
   * Retrieves the authentication mechanism for a given local identity.
   *
   * @param localIdentity the identity for which to fetch a token.
   * @return an {@link AuthenticationMechanism} for the given local identity.
   */
  static Optional<AuthenticationMechanism> getAuthMechanism(Optional<S2AIdentity> localIdentity) {
    if (!TOKEN_MANAGER.isPresent()) {
      return Optional.empty();
    }
    AccessTokenManager manager = TOKEN_MANAGER.get();
    // If no identity is provided, fetch the default access token and DO NOT attach an identity
    // to the request.
    if (!localIdentity.isPresent()) {
      return Optional.of(
              AuthenticationMechanism.newBuilder().setToken(manager.getDefaultToken()).build());
    } else {
      // Fetch an access token for the provided identity.
      return Optional.of(
              AuthenticationMechanism.newBuilder()
                  .setIdentity(localIdentity.get().getIdentity())
                  .setToken(manager.getToken(localIdentity.get()))
                  .build());
    }
  }

  private GetAuthenticationMechanisms() {}
}