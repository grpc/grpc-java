/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.netty;

import java.security.Provider;

/**
 * A stand-in provider when you must provide a provider but you don't actually have one.
 */
final class UnhelpfulSecurityProvider extends Provider {
  private static final long serialVersionUID = 0;

  @SuppressWarnings("deprecation") // double-based constructor deprecated in Java 9
  public UnhelpfulSecurityProvider() {
    super("UnhelpfulSecurityProvider", 0.0, "A Provider that provides nothing");
  }
}
