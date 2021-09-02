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

import android.os.Process;
import io.grpc.ExperimentalApi;
import io.grpc.Status;
import javax.annotation.CheckReturnValue;

/** Static factory methods for creating standard security policies. */
@CheckReturnValue
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8022")
public final class SecurityPolicies {

  private static final int MY_UID = Process.myUid();

  private SecurityPolicies() {}

  public static ServerSecurityPolicy serverInternalOnly() {
    return new ServerSecurityPolicy();
  }

  public static SecurityPolicy internalOnly() {
    return new SecurityPolicy() {
      @Override
      public Status checkAuthorization(int uid) {
        return uid == MY_UID
            ? Status.OK
            : Status.PERMISSION_DENIED.withDescription(
                "Rejected by (internal-only) security policy");
      }
    };
  }

  public static SecurityPolicy permissionDenied(String description) {
    Status denied = Status.PERMISSION_DENIED.withDescription(description);
    return new SecurityPolicy() {
      @Override
      public Status checkAuthorization(int uid) {
        return denied;
      }
    };
  }
}
