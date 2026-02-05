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

import com.github.xds.type.matcher.v3.Matcher;
import io.grpc.xds.internal.matcher.MatcherRunner.MatchContext;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

final class MatcherTree extends UnifiedMatcher {
  private static final String TYPE_URL_HTTP_ATTRIBUTES_CEL_INPUT =
      "type.googleapis.com/xds.type.matcher.v3.HttpAttributesCelMatchInput";
  private final MatchInput<MatchContext> input;
  @Nullable private final Map<String, OnMatch> exactMatchMap;
  @Nullable private final Map<String, OnMatch> prefixMatchMap;
  @Nullable private final OnMatch onNoMatch;
  
  MatcherTree(Matcher.MatcherTree proto, @Nullable Matcher.OnMatch onNoMatchProto,
      java.util.function.Predicate<String> actionValidator) {
    if (!proto.hasInput()) {
      throw new IllegalArgumentException("MatcherTree must have input");
    }
    this.input = UnifiedMatcher.resolveInput(proto.getInput());
    if (proto.getInput().getTypedConfig().getTypeUrl()
        .equals(TYPE_URL_HTTP_ATTRIBUTES_CEL_INPUT)) {
      throw new IllegalArgumentException(
          "HttpAttributesCelMatchInput cannot be used with MatcherTree");
    }
    
    if (proto.hasCustomMatch()) {
      throw new IllegalArgumentException("MatcherTree does not support custom_match");
    }
    
    if (proto.hasExactMatchMap()) {
      Matcher.MatcherTree.MatchMap matchMap = proto.getExactMatchMap();
      if (matchMap.getMapCount() == 0) {
        throw new IllegalArgumentException(
            "MatcherTree exact_match_map must contain at least one entry");
      }
      this.exactMatchMap = new HashMap<>();
      for (Map.Entry<String, Matcher.OnMatch> entry : 
          matchMap.getMapMap().entrySet()) {
        this.exactMatchMap.put(entry.getKey(),
            new OnMatch(entry.getValue(), actionValidator));
      }
      this.prefixMatchMap = null;
    } else if (proto.hasPrefixMatchMap()) {
      Matcher.MatcherTree.MatchMap matchMap = proto.getPrefixMatchMap();
      if (matchMap.getMapCount() == 0) {
        throw new IllegalArgumentException(
            "MatcherTree prefix_match_map must contain at least one entry");
      }
      this.prefixMatchMap = new HashMap<>();
      for (Map.Entry<String, Matcher.OnMatch> entry : 
          matchMap.getMapMap().entrySet()) {
        this.prefixMatchMap.put(entry.getKey(),
            new OnMatch(entry.getValue(), actionValidator));
      }
      this.exactMatchMap = null;
    } else {
      throw new IllegalArgumentException(
          "MatcherTree must have either exact_match_map or prefix_match_map");
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

    Object valueObj = input.apply(context);
    if (!(valueObj instanceof String)) {
      return onNoMatch != null ? onNoMatch.evaluate(context, depth) : MatchResult.noMatch();
    }
    String value = (String) valueObj;
    if (exactMatchMap != null) {
      OnMatch match = exactMatchMap.get(value);
      if (match != null) {
        return match.evaluate(context, depth);
      }
      return onNoMatch != null ? onNoMatch.evaluate(context, depth) : MatchResult.noMatch();
    } else if (prefixMatchMap != null) {
      java.util.List<String> matchingPrefixes = new java.util.ArrayList<>();
      for (String prefix : prefixMatchMap.keySet()) {
        if (value.startsWith(prefix)) {
          matchingPrefixes.add(prefix);
        }
      }
      
      if (matchingPrefixes.isEmpty()) {
        return onNoMatch != null ? onNoMatch.evaluate(context, depth) : MatchResult.noMatch();
      }

      // Sort by length descending (longest first)
      java.util.Collections.sort(matchingPrefixes, new java.util.Comparator<String>() {
        @Override
        public int compare(String s1, String s2) {
          return Integer.compare(s2.length(), s1.length());
        }
      });
      
      boolean matchedAtLeastOnce = false;
      java.util.List<com.github.xds.core.v3.TypedExtensionConfig> accumulatedActions = 
          new java.util.ArrayList<>();
          
      for (String prefix : matchingPrefixes) {
        OnMatch onMatch = prefixMatchMap.get(prefix);
        MatchResult result = onMatch.evaluate(context, depth);
        if (result.matched) {
          matchedAtLeastOnce = true;
          accumulatedActions.addAll(result.actions);
          if (!onMatch.keepMatching) {
            return MatchResult.create(accumulatedActions);
          }
        }
      }
      
      if (matchedAtLeastOnce) {
        return MatchResult.create(accumulatedActions);
      }
      // If we found matching prefixes but none of them resulted in a match (nested logic failed),
      // we still "found a key" in the tree structure.
      // According to the test "matcherTree_exactMatch_shouldNotFallBackToOnNoMatch_ifKeyFound",
      // finding a key prevents onNoMatch.
      // So we return noMatch() here, NOT onNoMatch.evaluate().
      return MatchResult.noMatch();
    }
    
    return onNoMatch != null ? onNoMatch.evaluate(context, depth) : MatchResult.noMatch();
  }
}
