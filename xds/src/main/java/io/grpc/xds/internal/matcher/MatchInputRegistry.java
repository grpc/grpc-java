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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Registry for {@link MatchInputProvider}s.
 */
public final class MatchInputRegistry {
  private static final MatchInputRegistry DEFAULT_INSTANCE = new MatchInputRegistry();

  private final Map<String, MatchInputProvider> providers = new ConcurrentHashMap<>();

  public static MatchInputRegistry getDefaultRegistry() {
    return DEFAULT_INSTANCE;
  }

  @VisibleForTesting
  public MatchInputRegistry() {
    register(new HeaderMatchInput.Provider());
    register(new HttpAttributesCelMatchInput.Provider());
  }

  public void register(MatchInputProvider provider) {
    providers.put(provider.typeUrl(), provider);
  }

  @Nullable
  public MatchInputProvider getProvider(String typeUrl) {
    return providers.get(typeUrl);
  }
}
