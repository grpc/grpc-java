/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.xds.client;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import io.grpc.CallCredentials;
import io.grpc.Internal;
import java.util.Map;
import java.util.Optional;

/**
 * Wrapper for allowed gRPC services keyed by target URI.
 */
@Internal
@AutoValue
public abstract class AllowedGrpcServices {
  public abstract ImmutableMap<String, AllowedGrpcService> services();

  public static AllowedGrpcServices create(Map<String, AllowedGrpcService> services) {
    return new AutoValue_AllowedGrpcServices(ImmutableMap.copyOf(services));
  }

  public static AllowedGrpcServices empty() {
    return create(ImmutableMap.of());
  }

  /**
   * Represents an allowed gRPC service configuration with call credentials.
   */
  @Internal
  @AutoValue
  public abstract static class AllowedGrpcService {
    public abstract ConfiguredChannelCredentials configuredChannelCredentials();

    public abstract Optional<CallCredentials> callCredentials();

    public static Builder builder() {
      return new AutoValue_AllowedGrpcServices_AllowedGrpcService.Builder();
    }

    @AutoValue.Builder
    public abstract static class Builder {
      public abstract Builder configuredChannelCredentials(
          ConfiguredChannelCredentials credentials);

      public abstract Builder callCredentials(CallCredentials callCredentials);

      public abstract AllowedGrpcService build();
    }
  }
}
