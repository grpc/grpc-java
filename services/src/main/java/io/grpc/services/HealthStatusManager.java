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

import io.grpc.health.v1.HealthCheckResponse.ServingStatus;

/**
 * A {@code HealthStatusManager} object manages a health check service. A health check service is
 * created in the constructor of {@code HealthStatusManager}, and it can be retrieved by the
 * {@link #getHealthService()} method.
 * The health status manager can update the health statuses of the server.
 *
 * <p>The default, empty-string, service name, {@link #SERVICE_NAME_ALL_SERVICES}, is initialized to
 * {@link ServingStatus#SERVING}.
 *
 * @deprecated Use {@link io.grpc.protobuf.services.HealthStatusManager} instead.
 */
@Deprecated
@io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/4696")
public final class HealthStatusManager extends io.grpc.protobuf.services.HealthStatusManager {
}
