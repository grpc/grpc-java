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

package io.grpc.xds.internal.datatype;

import static io.grpc.xds.XdsResourceType.ResourceInvalidException;

import com.google.auto.value.AutoValue;
import com.google.protobuf.Duration;
import javax.annotation.Nullable;

@AutoValue
public abstract class GrpcService {
  abstract String targetUri();

  // TODO(sergiitk): do we need this?
  // abstract String statPrefix();

  // TODO(sergiitk): channelCredentials
  // TODO(sergiitk): callCredentials
  // TODO(sergiitk): channelArgs

  /** Optional timeout duration for the gRPC request to the service. */
  @Nullable
  abstract Duration timeout();

  public static GrpcService fromEnvoyProto(
      io.envoyproxy.envoy.config.core.v3.GrpcService grpcServiceProto)
      throws ResourceInvalidException {
    if (grpcServiceProto.getTargetSpecifierCase()
        != io.envoyproxy.envoy.config.core.v3.GrpcService.TargetSpecifierCase.GOOGLE_GRPC) {
      throw ResourceInvalidException.ofResource(grpcServiceProto,
          "Only GoogleGrpc targets supported, got " + grpcServiceProto.getTargetSpecifierCase());
    }
    Builder builder = GrpcService.builder();
    if (grpcServiceProto.hasTimeout()) {
      builder.timeout(grpcServiceProto.getTimeout());
    }
    // GoogleGrpc fields flattened.
    io.envoyproxy.envoy.config.core.v3.GrpcService.GoogleGrpc googleGrpcProto =
        grpcServiceProto.getGoogleGrpc();
    builder.targetUri(googleGrpcProto.getTargetUri());

    // TODO(sergiitk): channelCredentials
    // TODO(sergiitk): callCredentials
    // TODO(sergiitk): channelArgs
    // TODO(sergiitk): statPrefix - (maybe)

    return builder.build();
  }

  public static Builder builder() {
    return new AutoValue_GrpcService.Builder();
  }

  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder targetUri(String targetUri);

    public abstract Builder timeout(Duration timeout);

    public abstract GrpcService build();
  }
}
