/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.alts;

import io.grpc.ExperimentalApi;
import io.grpc.alts.internal.AltsInternalContext;
import io.grpc.alts.internal.HandshakerResult;
import io.grpc.alts.internal.Identity;

/** {@code AltsContext} contains security-related information on the ALTS channel. */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/7864")
public final class AltsContext {

  private final AltsInternalContext wrapped;

  AltsContext(AltsInternalContext wrapped) {
    this.wrapped = wrapped;
  }

  /**
   * Creates an {@code AltsContext} for testing purposes.
   * @param peerServiceAccount the peer service account of the to be created {@code AltsContext}
   * @param localServiceAccount the local service account of the to be created {@code AltsContext}
   * @return the created {@code AltsContext}
   */
  public static AltsContext createTestInstance(String peerServiceAccount,
      String localServiceAccount) {
    return new AltsContext(new AltsInternalContext(HandshakerResult.newBuilder()
        .setPeerIdentity(Identity.newBuilder().setServiceAccount(peerServiceAccount).build())
        .setLocalIdentity(Identity.newBuilder().setServiceAccount(localServiceAccount).build())
        .build()));
  }

  /**
   * Get security level.
   *
   * @return the context's security level.
   */
  public SecurityLevel getSecurityLevel() {
    switch (wrapped.getSecurityLevel()) {
      case SECURITY_NONE:
        return SecurityLevel.SECURITY_NONE;
      case INTEGRITY_ONLY:
        return SecurityLevel.INTEGRITY_ONLY;
      case INTEGRITY_AND_PRIVACY:
        return SecurityLevel.INTEGRITY_AND_PRIVACY;
      default:
        return SecurityLevel.UNKNOWN;
    }
  }

  /**
   * Get peer service account.
   *
   * @return the context's peer service account.
   */
  public String getPeerServiceAccount() {
    return wrapped.getPeerServiceAccount();
  }

  /**
   * Get local service account.
   *
   * @return the context's local service account.
   */
  public String getLocalServiceAccount() {
    return wrapped.getLocalServiceAccount();
  }

  /** SecurityLevel of the ALTS channel. */
  public enum SecurityLevel {
    UNKNOWN,
    SECURITY_NONE,
    INTEGRITY_ONLY,
    INTEGRITY_AND_PRIVACY,
  }
}
