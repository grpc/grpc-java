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
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

final class MatcherList extends UnifiedMatcher {
  private final List<FieldMatcher> matchers;
  @Nullable private final OnMatch onNoMatch;

  MatcherList(Matcher.MatcherList proto, @Nullable Matcher.OnMatch onNoMatchProto,
      java.util.function.Predicate<String> actionValidator) {
    if (proto.getMatchersCount() == 0) {
      throw new IllegalArgumentException("MatcherList must contain at least one FieldMatcher");
    }
    this.matchers = new ArrayList<>(proto.getMatchersCount());
    for (Matcher.MatcherList.FieldMatcher fm : proto.getMatchersList()) {
      matchers.add(new FieldMatcher(fm, actionValidator));
    }
    if (onNoMatchProto != null) {
      this.onNoMatch = new OnMatch(onNoMatchProto, actionValidator);
    } else {
      this.onNoMatch = null;
    }
  }

  @Override
  public MatchResult match(MatchContext context, int depth) {
    if (depth > MAX_RECURSION_DEPTH) {
      return MatchResult.noMatch();
    }

    List<TypedExtensionConfig> accumulated = new ArrayList<>();
    boolean matchedAtLeastOnce = false;
    for (FieldMatcher matcher : matchers) {
      if (matcher.matches(context)) {
        MatchResult result = matcher.onMatch.evaluate(context, depth);
        if (result.matched) {
          accumulated.addAll(result.actions);
          matchedAtLeastOnce = true;
        }
        
        if (!matcher.onMatch.keepMatching) {
          if (!matchedAtLeastOnce) {
            return MatchResult.noMatch();
          }
          break;
        }
      }
    }
    
    if (!matchedAtLeastOnce) {
      if (onNoMatch != null) {
        MatchResult noMatchResult = onNoMatch.evaluate(context, depth);
        if (noMatchResult.matched) {
          accumulated.addAll(noMatchResult.actions);
          matchedAtLeastOnce = true;
        }
      }
    }
    if (matchedAtLeastOnce) {
      return MatchResult.create(accumulated);
    }
    return MatchResult.noMatch();
  }
  
  private static final class FieldMatcher {
    private final PredicateEvaluator predicate;
    private final OnMatch onMatch;
    
    FieldMatcher(Matcher.MatcherList.FieldMatcher proto, 
        java.util.function.Predicate<String> actionValidator) {
      this.predicate = PredicateEvaluator.fromProto(proto.getPredicate());
      this.onMatch = new OnMatch(proto.getOnMatch(), actionValidator);
    }
    
    boolean matches(MatchContext context) {
      return predicate.evaluate(context);
    }
  }
}
