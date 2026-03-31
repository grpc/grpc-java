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
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.xds.internal.extauthz.AuthzResponse.Decision;
import io.grpc.xds.internal.headermutations.HeaderMutations.ResponseHeaderMutations;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AuthzResponseTest {
  @Test
  public void testAllow() {
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("foo", Metadata.ASCII_STRING_MARSHALLER), "bar");
    AuthzResponse response = AuthzResponse.allow(headers).build();
    assertThat(response.decision()).isEqualTo(Decision.ALLOW);
    assertThat(response.headers()).hasValue(headers);
    assertThat(response.status()).isEmpty();
    assertThat(response.responseHeaderMutations().headers()).isEmpty();
  }

  @Test
  public void testAllowWithHeaderMutations() {
    Metadata headers = new Metadata();
    ResponseHeaderMutations mutations =
        ResponseHeaderMutations.create(ImmutableList.of(HeaderValueOption.newBuilder()
            .setHeader(HeaderValue.newBuilder().setKey("key").setValue("value")).build()));
    AuthzResponse response =
        AuthzResponse.allow(headers).setResponseHeaderMutations(mutations).build();
    assertThat(response.decision()).isEqualTo(Decision.ALLOW);
    assertThat(response.responseHeaderMutations()).isEqualTo(mutations);
  }

  @Test
  public void testDeny() {
    Status status = Status.PERMISSION_DENIED.withDescription("reason");
    AuthzResponse response = AuthzResponse.deny(status).build();
    assertThat(response.decision()).isEqualTo(Decision.DENY);
    assertThat(response.status()).hasValue(status);
    assertThat(response.headers()).isEmpty();
    assertThat(response.responseHeaderMutations().headers()).isEmpty();
  }
}
