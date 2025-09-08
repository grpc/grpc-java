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

package io.grpc.internal;

import javax.annotation.concurrent.Immutable;

/**
 * Represents the reason for a subchannel disconnection.
 * Implementations are either the SimpleDisconnectError enum or the GoAwayDisconnectError class for
 * dynamic ones.
 */
@Immutable
public interface DisconnectError {
  /**
   * Returns the string representation suitable for use as an error tag.
   *
   * @return The formatted error tag string.
   */
  String toErrorString();
}
