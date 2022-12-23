/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.binder;

import io.grpc.Status;
import javax.annotation.CheckReturnValue;

/**
 * Decides whether a given Android UID is authorized to access some resource.
 *
 * While it's possible to extend this class to define your own policy, it's strongly
 * recommended that you only use the policies provided by the {@link SecurityPolicies} or
 * {@link UntrustedSecurityPolicies} classes. Implementing your own security policy requires
 * significant care, and an understanding of the details and pitfalls of Android security.
 *
 * <p><b>IMPORTANT</b> For any concrete extensions of this class, it's assumed that the
 * authorization status of a given UID will <b>not</b> change as long as a process with that UID is
 * alive.
 *
 * <p>In order words, we expect the security policy for a given transport to remain constant for the
 * lifetime of that transport. This is considered acceptable because no transport will survive the
 * re-installation of the applications involved.
 */
@CheckReturnValue
public abstract class SecurityPolicy {

  protected SecurityPolicy() {}

  /**
   * Decides whether the given Android UID is authorized. (Validity is implementation dependent).
   *
   * <p><b>IMPORTANT</b>: This method may block for extended periods of time.
   *
   * <p>As long as any given UID has active processes, this method should return the same value for
   * that UID. In order words, policy changes which occur while a transport instance is active, will
   * have no effect on that transport instance.
   *
   * @param uid The Android UID to authenticate.
   * @return A gRPC {@link Status} object, with OK indicating authorized.
   */
  public abstract Status checkAuthorization(int uid);
}
