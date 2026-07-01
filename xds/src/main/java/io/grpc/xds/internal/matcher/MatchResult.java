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
final class MatchResult {
  final List<TypedExtensionConfig> actions;
  final boolean matched;

  private MatchResult(
      List<TypedExtensionConfig> actions,
      boolean matched) {
    this.actions =
        Collections.unmodifiableList(
            new ArrayList<>(checkNotNull(actions, "actions")));
    this.matched = matched;
  }

  /**
   * Creates a result indicating a successful match with a terminal action.
   */
  static MatchResult create(List<TypedExtensionConfig> actions) {
    return new MatchResult(actions, true);
  }

  /**
   * Creates a result indicating a match with a terminal action and no accumulated actions.
   */
  static MatchResult create(TypedExtensionConfig action) {
    return new MatchResult(Collections.singletonList(action), true);
  }

  /**
   * Creates a result indicating no terminal match, but potentially with accumulated actions.
   */
  static MatchResult noMatch(List<TypedExtensionConfig> actions) {
    return new MatchResult(actions, false);
  }
  
  static MatchResult noMatch() {
    return new MatchResult(Collections.emptyList(), false);
  }
}
