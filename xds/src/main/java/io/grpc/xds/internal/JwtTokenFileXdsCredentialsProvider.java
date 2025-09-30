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

package io.grpc.xds.internal;

import io.grpc.CallCredentials;
import io.grpc.ChannelCredentials;
import io.grpc.internal.JsonUtil;
import io.grpc.xds.XdsCredentialsProvider;
import io.grpc.xds.internal.JwtTokenFileCallCredentials;
import java.io.File;
import java.util.Map;

/**
 * A wrapper class that supports {@link JwtTokenFileXdsCredentialsProvider} for
 * Xds by implementing {@link XdsCredentialsProvider}.
 */
public final class JwtTokenFileXdsCredentialsProvider extends XdsCredentialsProvider {
  private static final String CREDS_NAME = "jwt_token_file";

  @Override
  protected ChannelCredentials newChannelCredentials(Map<String, ?> jsonConfig) {
    return null;
  }

  @Override
  protected CallCredentials newCallCredentials(Map<String, ?> jsonConfig) {
    if (jsonConfig == null) {
      return null;
    }

    String jwtTokenPath = JsonUtil.getString(jsonConfig, getName());
    if (jwtTokenPath == null || !new File(jwtTokenPath).isFile()) {
      return null;
    }

    return JwtTokenFileCallCredentials.create(jwtTokenPath);
  }

  @Override
  protected String getName() {
    return CREDS_NAME;
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public int priority() {
    return 5;
  }

}
