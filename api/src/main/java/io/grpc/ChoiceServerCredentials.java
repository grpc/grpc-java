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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Provides a list of {@link ServerCredentials}, where any one may be used. The credentials are in
 * preference order.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/7621")
public final class ChoiceServerCredentials extends ServerCredentials {
  /**
   * Constructs with the provided {@code creds} as options, with preferred credentials first.
   *
   * @throws IllegalArgumentException if no creds are provided
   */
  public static ServerCredentials create(ServerCredentials... creds) {
    if (creds.length == 0) {
      throw new IllegalArgumentException("At least one credential is required");
    }
    return new ChoiceServerCredentials(creds);
  }

  private final List<ServerCredentials> creds;

  private ChoiceServerCredentials(ServerCredentials... creds) {
    for (ServerCredentials cred : creds) {
      if (cred == null) {
        throw new NullPointerException();
      }
    }
    this.creds = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(creds)));
  }

  /** Non-empty list of credentials, in preference order. */
  public List<ServerCredentials> getCredentialsList() {
    return creds;
  }
}
