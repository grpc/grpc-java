/*
 * Copyright 2021 The gRPC Authors
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

import io.grpc.ExperimentalApi;
import io.grpc.Status;
import javax.annotation.CheckReturnValue;

/**
 * Static factory methods for creating untrusted security policies.
 */
@CheckReturnValue
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8022")
public final class UntrustedSecurityPolicies {

  private UntrustedSecurityPolicies() {}

  /**
   * Return a security policy which allows any peer on device.
   * Servers should only use this policy if they intend to expose
   * a service to all applications on device.
   * Clients should only use this policy if they don't need to trust the
   * application they're connecting to.
   */
  public static SecurityPolicy untrustedPublic() {
    return new SecurityPolicy() {
      @Override
      public Status checkAuthorization(int uid) {
        return Status.OK;
      }
    };
  }
}
