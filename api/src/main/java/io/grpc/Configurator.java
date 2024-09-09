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

package io.grpc;

/**
 * Provides hooks for modifying gRPC channels and servers during their construction.
 */
interface Configurator {
  /**
   * Allows implementations to modify the channel builder.
   *
   * @param channelBuilder the channel builder being constructed
   */
  default void configureChannelBuilder(ManagedChannelBuilder<?> channelBuilder) {}

  /**
   * Allows implementations to modify the server builder.
   *
   * @param serverBuilder the server builder being constructed
   */
  default void configureServerBuilder(ServerBuilder<?> serverBuilder) {}
}
