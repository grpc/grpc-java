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

import com.google.common.truth.Expect;
import io.grpc.s2a.internal.handshaker.S2AIdentity;
import io.grpc.s2a.internal.handshaker.tokenmanager.SingleTokenFetcher;
import java.util.Optional;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
 
/** Unit tests for {@link GetAuthenticationMechanisms}. */
@RunWith(JUnit4.class)
public final class GetAuthenticationMechanismsTest {
  @Rule public final Expect expect = Expect.create();
  private static final String TOKEN = "access_token";
  private static String originalAccessToken;

  @BeforeClass
  public static void setUpClass() {
    originalAccessToken = SingleTokenFetcher.getAccessToken();
    // Set the token that the client will use to authenticate to the S2A.
    SingleTokenFetcher.setAccessToken(TOKEN);
  }

  @AfterClass
  public static void tearDownClass() {
    SingleTokenFetcher.setAccessToken(originalAccessToken);
  }

  @Test
  public void getAuthMechanisms_emptyIdentity_success() {
    expect
        .that(GetAuthenticationMechanisms.getAuthMechanism(Optional.empty()))
        .isEqualTo(
            Optional.of(AuthenticationMechanism.newBuilder().setToken("access_token").build()));
  }

  @Test
  public void getAuthMechanisms_nonEmptyIdentity_success() {
    S2AIdentity fakeIdentity = S2AIdentity.fromSpiffeId("fake-spiffe-id");
    expect
        .that(GetAuthenticationMechanisms.getAuthMechanism(Optional.of(fakeIdentity)))
        .isEqualTo(
            Optional.of(
                AuthenticationMechanism.newBuilder()
                    .setIdentity(fakeIdentity.getIdentity())
                    .setToken("access_token")
                    .build()));
  }
}
