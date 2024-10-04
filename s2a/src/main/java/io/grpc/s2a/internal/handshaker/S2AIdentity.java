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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.errorprone.annotations.ThreadSafe;

/**
 * Stores an identity in such a way that it can be sent to the S2A handshaker service. The identity
 * may be formatted as a SPIFFE ID or as a hostname.
 */
@ThreadSafe
public final class S2AIdentity {
  private final Identity identity;

  /** Returns an {@link S2AIdentity} instance with SPIFFE ID set to {@code spiffeId}. */
  public static S2AIdentity fromSpiffeId(String spiffeId) {
    checkNotNull(spiffeId);
    return new S2AIdentity(Identity.newBuilder().setSpiffeId(spiffeId).build());
  }

  /** Returns an {@link S2AIdentity} instance with hostname set to {@code hostname}. */
  public static S2AIdentity fromHostname(String hostname) {
    checkNotNull(hostname);
    return new S2AIdentity(Identity.newBuilder().setHostname(hostname).build());
  }

  /** Returns an {@link S2AIdentity} instance with UID set to {@code uid}. */
  public static S2AIdentity fromUid(String uid) {
    checkNotNull(uid);
    return new S2AIdentity(Identity.newBuilder().setUid(uid).build());
  }

  /** Returns an {@link S2AIdentity} instance with {@code identity} set. */
  public static S2AIdentity fromIdentity(Identity identity) {
    return new S2AIdentity(identity == null ? Identity.getDefaultInstance() : identity);
  }

  private S2AIdentity(Identity identity) {
    this.identity = identity;
  }

  /** Returns the proto {@link Identity} representation of this identity instance. */
  public Identity getIdentity() {
    return identity;
  }
}