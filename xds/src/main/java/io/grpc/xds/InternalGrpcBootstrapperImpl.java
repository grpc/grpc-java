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

package io.grpc.xds;

import io.grpc.Internal;
import io.grpc.xds.client.Bootstrapper.BootstrapInfo;
import io.grpc.xds.client.XdsInitializationException;
import java.util.Map;

/**
 * Internal accessors for GrpcBootstrapperImpl.
 */
@Internal
public final class InternalGrpcBootstrapperImpl {
  private InternalGrpcBootstrapperImpl() {} // prevent instantiation

  public static BootstrapInfo parseBootstrap(Map<String, ?> bootstrap)
      throws XdsInitializationException {
    return new GrpcBootstrapperImpl().bootstrap(bootstrap);
  }
}
