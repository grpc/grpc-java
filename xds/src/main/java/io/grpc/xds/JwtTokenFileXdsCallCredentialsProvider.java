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

package io.grpc.xds;

import io.grpc.CallCredentials;
import io.grpc.internal.JsonUtil;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A wrapper class that supports {@link JwtTokenFileXdsCallCredentialsProvider} for
 * xDS by implementing {@link XdsCredentialsProvider}.
 */
public final class JwtTokenFileXdsCallCredentialsProvider extends XdsCallCredentialsProvider {
  private static final Logger logger = Logger.getLogger(
      JwtTokenFileXdsCallCredentialsProvider.class.getName());
  private static final String CREDS_NAME = "jwt_token_file";

  @Override
  protected CallCredentials newCallCredentials(Map<String, ?> jsonConfig) {
    if (jsonConfig == null) {
      return null;
    }

    String jwtTokenPath = JsonUtil.getString(jsonConfig, getName());
    if (jwtTokenPath == null) {
      logger.log(Level.WARNING, "jwt_token_file credential requires jwt_token_file in the config");
      return null;
    }

    return JwtTokenFileCallCredentials.create(jwtTokenPath);
  }

  @Override
  protected String getName() {
    return CREDS_NAME;
  }
}
