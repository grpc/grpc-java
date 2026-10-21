/*
 * Copyright 2022 The gRPC Authors
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

import io.grpc.ManagedChannelProvider.NewChannelBuilderResult;

/** Internal accessor for {@link ManagedChannelProvider}. */
@Internal
public final class InternalManagedChannelProvider {

  private InternalManagedChannelProvider() {
  }

  public static boolean isAvailable(ManagedChannelProvider provider) {
    return provider.isAvailable();
  }

  public static ManagedChannelBuilder<?> builderForAddress(ManagedChannelProvider provider,
      String name, int port) {
    return provider.builderForAddress(name, port);
  }

  public static ManagedChannelBuilder<?> builderForTarget(ManagedChannelProvider provider,
      String target) {
    return provider.builderForTarget(target);
  }

  public static NewChannelBuilderResult newChannelBuilder(ManagedChannelProvider provider,
      String target, ChannelCredentials creds) {
    return provider.newChannelBuilder(target, creds);
  }
}
