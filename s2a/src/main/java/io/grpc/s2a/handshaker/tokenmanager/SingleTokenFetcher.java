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

package io.grpc.s2a.handshaker.tokenmanager;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import io.grpc.s2a.handshaker.S2AIdentity;
import java.util.Optional;

/** Fetches a single access token via an environment variable. */
public final class SingleTokenFetcher implements TokenFetcher {
  private static final String ENVIRONMENT_VARIABLE = "S2A_ACCESS_TOKEN";

  /** Set an access token via a flag. */
  @Parameters(separators = "=")
  public static class Flags {
    @Parameter(
        names = "--s2a_access_token",
        description = "The access token used to authenticate to S2A.")
    private static String accessToken = System.getenv(ENVIRONMENT_VARIABLE);

    public synchronized void reset() {
      accessToken = null;
    }
  }

  private final String token;

  /**
   * Creates a {@code SingleTokenFetcher} from {@code ENVIRONMENT_VARIABLE}, and returns an empty
   * {@code Optional} instance if the token could not be fetched.
   */
  public static Optional<TokenFetcher> create() {
    return Optional.ofNullable(Flags.accessToken).map(SingleTokenFetcher::new);
  }

  private SingleTokenFetcher(String token) {
    this.token = token;
  }

  @Override
  public String getDefaultToken() {
    return token;
  }

  @Override
  public String getToken(S2AIdentity identity) {
    return token;
  }
}