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
 * {@link OAuth2Credentials#refreshAccessToken()}. A real implementation
 * will maintain a refresh token and use it to exchange it for a new
 * access token from the authorization server.
 */
public class ExampleOAuth2Credentials extends OAuth2Credentials {

  /**
   * Creates an access token using the passed in clientId. A real
   * implementation will contact the authorization server to get an access
   * token and a refresh token.
   *
   */
  public ExampleOAuth2Credentials(String clientId) {
    super(new AccessToken(Constant.ACCESS_TOKEN + ":" + clientId,
        new Date()));
  }


  /**
   * Refreshes access token by simply appending ":+1" to the previous value.
   * A real implementation will use the existing refresh token to get
   * fresh access and refresh tokens from the authorization server.
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
