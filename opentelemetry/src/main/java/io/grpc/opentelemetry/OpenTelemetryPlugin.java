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

package io.grpc.opentelemetry;

import io.grpc.CallOptions;
import io.grpc.Metadata;
import io.opentelemetry.api.common.AttributesBuilder;

/**
 * Injects behavior into {@link GrpcOpenTelemetry}.
 */
interface OpenTelemetryPlugin {
  /**
   * Limited ability to disable the plugin based on the target. This only has an effect for
   * per-call metrics.
   *
   * <p>Ideally this method wouldn't exist and it'd be handled by wrapping GrpcOpenTelemetry and
   * conditionally delegating to it. But this is needed by CSM until ChannelBuilders have a
   * consistent target over their life; currently specifying nameResolverFactory can change the
   * target's scheme.
   */
  default boolean enablePluginForChannel(String target) {
    return true;
  }

  ClientCallPlugin newClientCallPlugin();

  ServerStreamPlugin newServerStreamPlugin(Metadata inboundMetadata);

  interface ClientCallPlugin {
    ClientStreamPlugin newClientStreamPlugin();

    default void addMetadata(Metadata toMetadata) {}

    default CallOptions filterCallOptions(CallOptions options) {
      return options;
    }
  }

  interface ClientStreamPlugin {
    default void inboundHeaders(Metadata headers) {}

    default void inboundTrailers(Metadata trailers) {}

    default void addLabels(AttributesBuilder to) {}
  }

  interface ServerStreamPlugin {
    default void addLabels(AttributesBuilder to) {}
  }
}
