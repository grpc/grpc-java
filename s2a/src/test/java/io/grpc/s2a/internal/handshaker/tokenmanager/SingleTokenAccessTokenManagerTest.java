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

package io.grpc.s2a.internal.handshaker.tokenmanager;

import static com.google.common.truth.Truth.assertThat;

import io.grpc.s2a.internal.handshaker.S2AIdentity;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SingleTokenAccessTokenManagerTest {
  private static final S2AIdentity IDENTITY = S2AIdentity.fromSpiffeId("spiffe_id");
  private static final String TOKEN = "token";

  private String originalAccessToken;

  @Before
  public void setUp() {
    originalAccessToken = SingleTokenFetcher.getAccessToken();
    SingleTokenFetcher.setAccessToken(null);
  }

  @After
  public void tearDown() {
    SingleTokenFetcher.setAccessToken(originalAccessToken);
  }

  @Test
  public void getDefaultToken_success() throws Exception {
    SingleTokenFetcher.setAccessToken(TOKEN);
    Optional<AccessTokenManager> manager = AccessTokenManager.create();
    assertThat(manager).isPresent();
    assertThat(manager.get().getDefaultToken()).isEqualTo(TOKEN);
  }

  @Test
  public void getToken_success() throws Exception {
    SingleTokenFetcher.setAccessToken(TOKEN);
    Optional<AccessTokenManager> manager = AccessTokenManager.create();
    assertThat(manager).isPresent();
    assertThat(manager.get().getToken(IDENTITY)).isEqualTo(TOKEN);
  }

  @Test
  public void getToken_noEnvironmentVariable() throws Exception {
    assertThat(SingleTokenFetcher.create()).isEmpty();
  }

  @Test
  public void create_success() throws Exception {
    SingleTokenFetcher.setAccessToken(TOKEN);
    Optional<AccessTokenManager> manager = AccessTokenManager.create();
    assertThat(manager).isPresent();
    assertThat(manager.get().getToken(IDENTITY)).isEqualTo(TOKEN);
  }

  @Test
  public void create_noEnvironmentVariable() throws Exception {
    assertThat(AccessTokenManager.create()).isEmpty();
  }
}
