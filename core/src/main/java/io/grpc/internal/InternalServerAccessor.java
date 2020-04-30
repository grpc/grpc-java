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

package io.grpc.internal;

import io.grpc.Context;
import io.grpc.Internal;
import io.grpc.Server;

/**
 * Internal accessor for getting the {@link Server} instance inside server RPC {@link Context}.
 * This is intended for usage internal to the gRPC team, as it's unclear to us what users would
 * need. If you *really* think you need to use this, please file an issue for us to discuss a
 * public API.
 */
@Internal
public class InternalServerAccessor {
  public static final Context.Key<Server> SERVER_KEY = ServerImpl.SERVER_CONTEXT_KEY;

  // Prevent instantiation.
  private InternalServerAccessor() {
  }
}
