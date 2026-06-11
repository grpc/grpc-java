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

package io.grpc.xds.internal.matcher;

import com.google.common.annotations.VisibleForTesting;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Registry for {@link MatcherProvider}.
 */
public final class MatcherRegistry {
  private static MatcherRegistry instance;

  private final Map<String, MatcherProvider> matcherProviders = new HashMap<>();

  private MatcherRegistry() {
  }

  public static synchronized MatcherRegistry getDefaultRegistry() {
    if (instance == null) {
      instance = newRegistry().register(
          new CelStateMatcher.Provider());
    }
    return instance;
  }

  @VisibleForTesting
  public static MatcherRegistry newRegistry() {
    return new MatcherRegistry();
  }

  @VisibleForTesting
  public MatcherRegistry register(MatcherProvider... providers) {
    for (MatcherProvider provider : providers) {
      matcherProviders.put(provider.typeUrl(), provider);
    }
    return this;
  }

  @Nullable
  public MatcherProvider getMatcherProvider(String typeUrl) {
    return matcherProviders.get(typeUrl);
  }
}
