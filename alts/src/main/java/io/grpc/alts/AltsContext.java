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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.alts.internal.AltsInternalContext;
import io.grpc.alts.internal.SecurityLevel;

/** {@code AltsContext} contains security-related information on the ALTS channel. */
public class AltsContext {

  private final AltsInternalContext wrapped;

  AltsContext(AltsInternalContext wrapped) {
    this.wrapped = wrapped;
  }

  @VisibleForTesting
  public static AltsContext getDefaultInstance() {
    return new AltsContext(AltsInternalContext.getDefaultInstance());
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
