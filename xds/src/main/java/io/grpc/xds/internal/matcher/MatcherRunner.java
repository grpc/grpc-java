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

import com.github.xds.core.v3.TypedExtensionConfig;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Executes a UnifiedMatcher against a request.
 */
public final class MatcherRunner {
  private MatcherRunner() {}

  /**
   * runs the matcher.
   */
  @Nullable
  public static List<TypedExtensionConfig> checkMatch(
      UnifiedMatcher matcher, MatchContext context) {
    MatchResult result = matcher.match(context, 0);
    if (!result.matched) {
      return null;
    }
    
    List<TypedExtensionConfig> allActions = 
        new ArrayList<>(result.keepMatchingActions);
    if (result.action != null) {
      allActions.add(result.action);
    }
    
    if (allActions.isEmpty()) {
      return null;
    }
    return allActions;
  }

}
