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

/**
 * Internal accessor for configuring Census features. Do not use this.
 */
@Internal
public class InternalCensus {

  /**
   * Key to access the configuration if the default client side census features are disabled.
   */
  @Internal
  public static final CallOptions.Key<Boolean> DISABLE_CLIENT_DEFAULT_CENSUS =
      CallOptions.Key.create("io.grpc.DISABLE_CLIENT_DEFAULT_CENSUS_STATS");
}
