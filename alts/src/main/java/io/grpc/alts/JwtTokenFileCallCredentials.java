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

package io.grpc.alts;

import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.OAuth2Credentials;
import com.google.common.io.Files;
import io.grpc.CallCredentials;
import io.grpc.auth.MoreCallCredentials;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT token file call credentials.
 * See gRFC A97 (https://github.com/grpc/proposal/pull/492).
 */
public final class JwtTokenFileCallCredentials extends OAuth2Credentials {
  private static final long serialVersionUID = 452556614608513984L;
  private String path = null;

  private JwtTokenFileCallCredentials(String path) {
    this.path = path;
  }

  @Override
  public AccessToken refreshAccessToken() throws IOException {
    String tokenString = new String(Files.toByteArray(new File(path)), StandardCharsets.UTF_8);
    Long expTime = JsonWebSignature.parse(new GsonFactory(), tokenString)
        .getPayload()
        .getExpirationTimeSeconds();
    if (expTime == null) {
      throw new IOException("No expiration time found for JWT token");
    }

    return AccessToken.newBuilder()
        .setTokenValue(tokenString)
        .setExpirationTime(new Date(expTime * 1000L))
        .build();
  }

  // using {@link MoreCallCredentials} adapter to be compatible with {@link CallCredentials} iface
  public static CallCredentials create(String path) {
    JwtTokenFileCallCredentials jwtTokenFileCallCredentials = new JwtTokenFileCallCredentials(path);
    return MoreCallCredentials.from(jwtTokenFileCallCredentials);
  }
}
