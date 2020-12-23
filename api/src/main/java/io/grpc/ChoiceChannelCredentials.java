/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc;

import static java.util.Collections.unmodifiableList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Provides a list of {@link ChannelCredentials}, where any one may be used. The credentials are in
 * preference order.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/7479")
public final class ChoiceChannelCredentials extends ChannelCredentials {
  /**
   * Constructs with the provided {@code creds} as options, with preferred credentials first.
   *
   * @throws IllegalArgumentException if no creds are provided
   */
  public static ChannelCredentials create(ChannelCredentials... creds) {
    if (creds.length == 0) {
      throw new IllegalArgumentException("At least one credential is required");
    }
    for (ChannelCredentials cred : creds) {
      if (cred == null) {
        throw new NullPointerException();
      }
    }
    return new ChoiceChannelCredentials(unmodifiableList(new ArrayList<>(Arrays.asList(creds))));
  }

  private final List<ChannelCredentials> creds;

  private ChoiceChannelCredentials(List<ChannelCredentials> creds) {
    this.creds = creds;
  }

  /** Non-empty list of credentials, in preference order. */
  public List<ChannelCredentials> getCredentialsList() {
    return creds;
  }

  @Override
  public ChannelCredentials withoutBearerTokens() {
    List<ChannelCredentials> credsWithoutTokens = new ArrayList<>();
    for (ChannelCredentials cred : creds) {
      credsWithoutTokens.add(cred.withoutBearerTokens());
    }
    return new ChoiceChannelCredentials(unmodifiableList(credsWithoutTokens));
  }
}
