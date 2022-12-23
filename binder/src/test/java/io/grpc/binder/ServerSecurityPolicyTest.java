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

import static com.google.common.truth.Truth.assertThat;

import android.os.Process;
import com.google.common.base.Function;
import io.grpc.Status;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class ServerSecurityPolicyTest {

  private static final String SERVICE1 = "service_one";
  private static final String SERVICE2 = "service_two";
  private static final String SERVICE3 = "service_three";

  private static final int MY_UID = Process.myUid();
  private static final int OTHER_UID = MY_UID + 1;

  ServerSecurityPolicy policy;

  @Test
  public void testDefaultInternalOnly() {
    policy = new ServerSecurityPolicy();
    assertThat(policy.checkAuthorizationForService(MY_UID, SERVICE1).getCode())
        .isEqualTo(Status.OK.getCode());
    assertThat(policy.checkAuthorizationForService(MY_UID, SERVICE2).getCode())
        .isEqualTo(Status.OK.getCode());
  }

  @Test
  public void testInternalOnly_AnotherUid() {
    policy = new ServerSecurityPolicy();
    assertThat(policy.checkAuthorizationForService(OTHER_UID, SERVICE1).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
    assertThat(policy.checkAuthorizationForService(OTHER_UID, SERVICE2).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  @Test
  public void testBuilderDefault() {
    policy = ServerSecurityPolicy.newBuilder().build();
    assertThat(policy.checkAuthorizationForService(MY_UID, SERVICE1).getCode())
        .isEqualTo(Status.OK.getCode());
    assertThat(policy.checkAuthorizationForService(OTHER_UID, SERVICE1).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  @Test
  public void testPerService() {
    policy =
        ServerSecurityPolicy.newBuilder()
            .servicePolicy(SERVICE2, policy((uid) -> Status.OK))
            .build();

    assertThat(policy.checkAuthorizationForService(MY_UID, SERVICE1).getCode())
        .isEqualTo(Status.OK.getCode());
    assertThat(policy.checkAuthorizationForService(OTHER_UID, SERVICE1).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
    assertThat(policy.checkAuthorizationForService(MY_UID, SERVICE2).getCode())
        .isEqualTo(Status.OK.getCode());
    assertThat(policy.checkAuthorizationForService(OTHER_UID, SERVICE2).getCode())
        .isEqualTo(Status.OK.getCode());
  }

  @Test
  public void testPerServiceNoDefault() {
    policy =
        ServerSecurityPolicy.newBuilder()
            .servicePolicy(SERVICE1, policy((uid) -> Status.INTERNAL))
            .servicePolicy(
                SERVICE2, policy((uid) -> uid == OTHER_UID ? Status.OK : Status.PERMISSION_DENIED))
            .build();

    // Uses the specified policy for service1.
    assertThat(policy.checkAuthorizationForService(MY_UID, SERVICE1).getCode())
        .isEqualTo(Status.INTERNAL.getCode());
    assertThat(policy.checkAuthorizationForService(OTHER_UID, SERVICE1).getCode())
        .isEqualTo(Status.INTERNAL.getCode());

    // Uses the specified policy for service2.
    assertThat(policy.checkAuthorizationForService(MY_UID, SERVICE2).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
    assertThat(policy.checkAuthorizationForService(OTHER_UID, SERVICE2).getCode())
        .isEqualTo(Status.OK.getCode());

    // Falls back to the default.
    assertThat(policy.checkAuthorizationForService(MY_UID, SERVICE3).getCode())
        .isEqualTo(Status.OK.getCode());
    assertThat(policy.checkAuthorizationForService(OTHER_UID, SERVICE3).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  private static SecurityPolicy policy(Function<Integer, Status> func) {
    return new SecurityPolicy() {
      @Override
      public Status checkAuthorization(int uid) {
        return func.apply(uid);
      }
    };
  }
}
