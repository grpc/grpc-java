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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.internal.GrpcUtil;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A registry for all supported {@link Filter}s. Filters can be queried from the registry
 * by any of the {@link Filter.Provider#typeUrls() type URLs}.
 */
final class FilterRegistry {
  private static FilterRegistry instance;

  private final Map<String, Filter.Provider> supportedFilters = new HashMap<>();

  private FilterRegistry() {}

  public static boolean isEnabledGcpAuthnFilter() {
    return GrpcUtil.getFlag("GRPC_EXPERIMENTAL_XDS_GCP_AUTHENTICATION_FILTER", false);
  }

  static synchronized FilterRegistry getDefaultRegistry() {
    if (instance == null) {
      instance = newRegistry().register(
              new FaultFilter.Provider(),
              new RouterFilter.Provider(),
              new RbacFilter.Provider());
      if (isEnabledGcpAuthnFilter()) {
        instance.register(new GcpAuthenticationFilter.Provider());
      }
    }
    return instance;
  }

  @VisibleForTesting
  static FilterRegistry newRegistry() {
    return new FilterRegistry();
  }

  @VisibleForTesting
  FilterRegistry register(Filter.Provider... filters) {
    for (Filter.Provider filter : filters) {
      for (String typeUrl : filter.typeUrls()) {
        supportedFilters.put(typeUrl, filter);
      }
    }
    return this;
  }

  @Nullable
  Filter.Provider get(String typeUrl) {
    return supportedFilters.get(typeUrl);
  }
}
