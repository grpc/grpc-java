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
import io.grpc.Status;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class SecurityPoliciesTest {
  private static final int MY_UID = Process.myUid();
  private static final int OTHER_UID = MY_UID + 1;

  private static final String PERMISSION_DENIED_REASONS = "some reasons";

  private SecurityPolicy policy;

  @Test
  public void testInternalOnly() throws Exception {
    policy = SecurityPolicies.internalOnly();
    assertThat(policy.checkAuthorization(MY_UID).getCode()).isEqualTo(Status.OK.getCode());
    assertThat(policy.checkAuthorization(OTHER_UID).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
  }

  @Test
  public void testPermissionDenied() throws Exception {
    policy = SecurityPolicies.permissionDenied(PERMISSION_DENIED_REASONS);
    assertThat(policy.checkAuthorization(MY_UID).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
    assertThat(policy.checkAuthorization(MY_UID).getDescription())
        .isEqualTo(PERMISSION_DENIED_REASONS);
    assertThat(policy.checkAuthorization(OTHER_UID).getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
    assertThat(policy.checkAuthorization(OTHER_UID).getDescription())
        .isEqualTo(PERMISSION_DENIED_REASONS);
  }
}
