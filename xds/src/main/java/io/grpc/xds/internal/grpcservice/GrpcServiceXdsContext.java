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
import io.grpc.Internal;
import java.util.Optional;

/**
 * Contextual abstraction needed during xDS plugin parsing.
 * Represents the context for a single target URI.
 */
@AutoValue
@Internal
public abstract class GrpcServiceXdsContext {

  public abstract boolean isTrustedControlPlane();

  public abstract Optional<AllowedGrpcService> validAllowedGrpcService();

  public abstract boolean isTargetUriSchemeSupported();

  public static GrpcServiceXdsContext create(
      boolean isTrustedControlPlane,
      Optional<AllowedGrpcService> validAllowedGrpcService,
      boolean isTargetUriSchemeSupported) {
    return new AutoValue_GrpcServiceXdsContext(
        isTrustedControlPlane,
        validAllowedGrpcService,
        isTargetUriSchemeSupported);
  }

}
