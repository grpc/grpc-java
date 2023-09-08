/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.examples.oauth;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.OAuth2Credentials;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;

/**
 * Subclass of {@link OAuth2Credentials } with a simple implementation of
 * {@link OAuth2Credentials#refreshAccessToken()}.
 */
public class ExampleOAuth2Credentials extends OAuth2Credentials {

  public ExampleOAuth2Credentials(String clientId) {
    super(new AccessToken(Constant.ACCESS_TOKEN + ":" + clientId,
        new Date()));
  }


  /**
   * Refreshes access token by simply appending ":+1" to the previous value.
   */
  @Override
  public AccessToken refreshAccessToken() throws IOException {
    AccessToken accessToken = getAccessToken();
    if (accessToken == null) {
      throw new IOException("No existing token found");
    }
    String tokenValue = accessToken.getTokenValue();
    return new AccessToken(tokenValue + ":" + Constant.REFRESH_SUFFIX,
        Date.from((Instant.now().plusSeconds(120))));
  }
}
