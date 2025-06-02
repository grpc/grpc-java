/*
 * Copyright 2021 The gRPC Authors
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

import io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC;
import io.grpc.Internal;
import io.grpc.ServerInterceptor;
import io.grpc.xds.RbacConfig;
import io.grpc.xds.RbacFilter;

/** This class exposes some functionality in RbacFilter to other packages. */
@Internal
public final class InternalRbacFilter {

  private InternalRbacFilter() {}

  /** Parses RBAC filter config and creates AuthorizationServerInterceptor. */
  public static ServerInterceptor createInterceptor(RBAC rbac) {
    ConfigOrError<RbacConfig> filterConfig = RbacFilter.parseRbacConfig(rbac);
    if (filterConfig.errorDetail != null) {
      throw new IllegalArgumentException(
        String.format("Failed to parse Rbac policy: %s", filterConfig.errorDetail));
    }
    return new RbacFilter().buildServerInterceptor(filterConfig.config, null);
  }
}
