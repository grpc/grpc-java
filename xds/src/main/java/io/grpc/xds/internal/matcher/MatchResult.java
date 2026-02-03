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

import static com.google.common.base.Preconditions.checkNotNull;

import com.github.xds.core.v3.TypedExtensionConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of a matching operation.
 */
public final class MatchResult {
  public final List<TypedExtensionConfig> actions; 
  public final boolean matched;

  private MatchResult(List<TypedExtensionConfig> actions, boolean matched) {
    this.actions = checkNotNull(actions, "actions");
    this.matched = matched;
  }

  public static MatchResult create(TypedExtensionConfig action) {
    return new MatchResult(Collections.singletonList(action), true);
  }

  public static MatchResult create(List<TypedExtensionConfig> actions) {
    return new MatchResult(new ArrayList<>(actions), true);
  }
  
  public static MatchResult noMatch() {
    return new MatchResult(Collections.emptyList(), false);
  }
}
