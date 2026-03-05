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
import javax.annotation.Nullable;

/**
 * Result of a matching operation.
 */
public final class MatchResult {
  @Nullable
  public final TypedExtensionConfig action;
  public final List<TypedExtensionConfig> keepMatchingActions;
  public final boolean matched;

  private MatchResult(
      @Nullable TypedExtensionConfig action,
      List<TypedExtensionConfig> keepMatchingActions,
      boolean matched) {
    this.action = action;
    this.keepMatchingActions =
        Collections.unmodifiableList(
            new ArrayList<>(checkNotNull(keepMatchingActions, "keepMatchingActions")));
    this.matched = matched;
  }

  /**
   * Creates a result indicating a successful match with a terminal action.
   */
  public static MatchResult create(
      @Nullable TypedExtensionConfig action,
      List<TypedExtensionConfig> keepMatchingActions) {
    return new MatchResult(action, keepMatchingActions, true);
  }

  /**
   * Creates a result indicating a match with a terminal action and no accumulated actions.
   */
  public static MatchResult create(TypedExtensionConfig action) {
    return new MatchResult(action, Collections.emptyList(), true);
  }

  /**
   * Creates a result indicating no terminal match, but potentially with accumulated actions.
   */
  public static MatchResult noMatch(List<TypedExtensionConfig> keepMatchingActions) {
    return new MatchResult(null, keepMatchingActions, false);
  }
  
  public static MatchResult noMatch() {
    return new MatchResult(null, Collections.emptyList(), false);
  }
}
