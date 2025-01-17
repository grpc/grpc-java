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
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A registry for all supported {@link Filter}s. Filters can be queried from the registry
 * by any of the {@link Filter#typeUrls() type URLs}.
 */
final class FilterRegistry {
  private static FilterRegistry instance;

  private final Map<String, Filter> supportedFilters = new HashMap<>();

  private FilterRegistry() {}

  static synchronized FilterRegistry getDefaultRegistry() {
    if (instance == null) {
      instance = newRegistry().register(
              FaultFilter.INSTANCE,
              RouterFilter.INSTANCE,
              RbacFilter.INSTANCE,
              RlqsFilter.INSTANCE);
    }
    return instance;
  }

  @VisibleForTesting
  static FilterRegistry newRegistry() {
    return new FilterRegistry();
  }

  @VisibleForTesting
  FilterRegistry register(Filter... filters) {
    for (Filter filter : filters) {
      if (!filter.isEnabled()) {
        continue;
      }
      for (String typeUrl : filter.typeUrls()) {
        supportedFilters.put(typeUrl, filter);
      }
    }
    return this;
  }

  @Nullable
  Filter get(String typeUrl) {
    return supportedFilters.get(typeUrl);
  }
}
