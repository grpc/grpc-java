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

package io.grpc.xds.internal.grpcservice;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

/**
 * Wrapper for allowed gRPC services keyed by target URI.
 */
@AutoValue
public abstract class AllowedGrpcServices {
  public abstract ImmutableMap<String, AllowedGrpcService> services();

  public static AllowedGrpcServices create(Map<String, AllowedGrpcService> services) {
    return new AutoValue_AllowedGrpcServices(ImmutableMap.copyOf(services));
  }

  public static AllowedGrpcServices empty() {
    return create(ImmutableMap.of());
  }
}
