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

package io.grpc.xds.internal.grpcservice;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import io.grpc.CallCredentials;
import java.time.Duration;
import java.util.Optional;


/**
 * A Java representation of the {@link io.envoyproxy.envoy.config.core.v3.GrpcService} proto. This
 * class encapsulates the configuration for a gRPC service, including target URI, credentials, and
 * other settings. This class is immutable and uses the AutoValue library for its implementation.
 */
@AutoValue
public abstract class GrpcServiceConfig {

  public static Builder builder() {
    return new AutoValue_GrpcServiceConfig.Builder();
  }

  public abstract GoogleGrpcConfig googleGrpc();

  public abstract Optional<Duration> timeout();

  public abstract ImmutableList<HeaderValue> initialMetadata();

  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder googleGrpc(GoogleGrpcConfig googleGrpc);

    public abstract Builder timeout(Duration timeout);

    public abstract Builder initialMetadata(ImmutableList<HeaderValue> initialMetadata);

    public abstract GrpcServiceConfig build();
  }

  /**
   * Represents the configuration for a Google gRPC service, as defined in the
   * {@link io.envoyproxy.envoy.config.core.v3.GrpcService.GoogleGrpc} proto. This class
   * encapsulates settings specific to Google's gRPC implementation, such as target URI and
   * credentials.
   */
  @AutoValue
  public abstract static class GoogleGrpcConfig {

    public static Builder builder() {
      return new AutoValue_GrpcServiceConfig_GoogleGrpcConfig.Builder();
    }

    public abstract String target();

    public abstract ConfiguredChannelCredentials configuredChannelCredentials();

    public abstract Optional<CallCredentials> callCredentials();

    @AutoValue.Builder
    public abstract static class Builder {
      public abstract Builder target(String target);

      public abstract Builder configuredChannelCredentials(
          ConfiguredChannelCredentials channelCredentials);

      public abstract Builder callCredentials(CallCredentials callCredentials);

      public abstract GoogleGrpcConfig build();
    }
  }


}
