/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc.services;

import io.grpc.ExperimentalApi;

/**
 * Provides a reflection service for Protobuf services (including the reflection service itself).
 *
 * <p>Separately tracks mutable and immutable services. Throws an exception if either group of
 * services contains multiple Protobuf files with declarations of the same service, method, type, or
 * extension.
 *
 * @deprecated Use {@link io.grpc.protobuf.services.ProtoReflectionService} instead.
 */
@Deprecated
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/2222")
public final class ProtoReflectionService
    extends io.grpc.protobuf.services.ProtoReflectionService {
  private ProtoReflectionService() {}
}
