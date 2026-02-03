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
import com.github.xds.type.matcher.v3.Matcher;
import io.grpc.xds.internal.matcher.MatcherRunner.MatchContext;
import javax.annotation.Nullable;

/**
 * Handles the action to take upon a match (recurse or return action).
 */
final class OnMatch {
  @Nullable private final UnifiedMatcher nestedMatcher;
  @Nullable private final TypedExtensionConfig action;
  final boolean keepMatching;
  
  OnMatch(Matcher.OnMatch proto, java.util.function.Predicate<String> actionValidator) {
    this.keepMatching = proto.getKeepMatching();
    if (proto.hasMatcher()) {
      this.nestedMatcher = UnifiedMatcher.fromProto(proto.getMatcher(), actionValidator);
      this.action = null;
    } else if (proto.hasAction()) {
      this.nestedMatcher = null;
      this.action = proto.getAction();
      String typeUrl = this.action.getTypedConfig().getTypeUrl();
      if (!actionValidator.test(typeUrl)) {
        throw new IllegalArgumentException("Unsupported action type: " + typeUrl);
      }
    } else {
      throw new IllegalArgumentException("OnMatch must have either matcher or action");
    }
  }
  
  MatchResult evaluate(MatchContext context, int depth) {
    if (nestedMatcher != null) {
      return nestedMatcher.match(context, depth + 1);
    }
    return MatchResult.create(action);
  }
}
