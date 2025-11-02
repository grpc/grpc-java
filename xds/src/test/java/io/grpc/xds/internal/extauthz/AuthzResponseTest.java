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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import io.grpc.Status;
import io.grpc.xds.internal.extauthz.AuthzResponse.Decision;
import io.grpc.xds.internal.headermutations.HeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderValueOption;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AuthzResponseTest {
  @Test
  public void testAllow() {
    HeaderMutations requestMutations =
        HeaderMutations.create(ImmutableList.of(), ImmutableList.of());
    AuthzResponse response = AuthzResponse.allow(requestMutations).build();
    assertThat(response.decision()).isEqualTo(Decision.ALLOW);
    assertThat(response.requestHeaderMutations()).isEqualTo(requestMutations);
    assertThat(response.status()).isEmpty();
    assertThat(response.responseHeaderMutations().headers()).isEmpty();
  }

  @Test
  public void testAllowWithHeaderMutations() {
    HeaderMutations requestMutations =
        HeaderMutations.create(ImmutableList.of(), ImmutableList.of());
    HeaderMutations responseMutations =
        HeaderMutations.create(
            ImmutableList.of(
                HeaderValueOption.create(
                    io.grpc.xds.internal.grpcservice.HeaderValue.create("key", "value"),
                    HeaderValueOption.HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD)),
            ImmutableList.of());
    AuthzResponse response =
        AuthzResponse.allow(requestMutations)
            .setResponseHeaderMutations(responseMutations)
            .build();
    assertThat(response.decision()).isEqualTo(Decision.ALLOW);
    assertThat(response.requestHeaderMutations()).isEqualTo(requestMutations);
    assertThat(response.responseHeaderMutations()).isEqualTo(responseMutations);
  }

  @Test
  public void testDeny() {
    Status status = Status.PERMISSION_DENIED.withDescription("reason");
    AuthzResponse response = AuthzResponse.deny(status).build();
    assertThat(response.decision()).isEqualTo(Decision.DENY);
    assertThat(response.status()).hasValue(status);
    assertThat(response.requestHeaderMutations().headers()).isEmpty();
    assertThat(response.responseHeaderMutations().headers()).isEmpty();
  }

  @Test
  public void testDenyWithResponseMutations() {
    Status status = Status.PERMISSION_DENIED.withDescription("reason");
    HeaderMutations responseMutations =
        HeaderMutations.create(
            ImmutableList.of(
                HeaderValueOption.create(
                    io.grpc.xds.internal.grpcservice.HeaderValue.create("x-deny-info", "blocked"),
                    HeaderValueOption.HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD)),
            ImmutableList.of());
    AuthzResponse response = AuthzResponse.deny(status)
        .setResponseHeaderMutations(responseMutations)
        .build();
    assertThat(response.decision()).isEqualTo(Decision.DENY);
    assertThat(response.status()).hasValue(status);
    assertThat(response.responseHeaderMutations()).isEqualTo(responseMutations);
    assertThat(response.requestHeaderMutations().headers()).isEmpty();
  }
}
