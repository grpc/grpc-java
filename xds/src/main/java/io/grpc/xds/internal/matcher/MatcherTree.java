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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import javax.annotation.Nullable;

final class MatcherTree extends UnifiedMatcher {
  private static final String TYPE_URL_HTTP_ATTRIBUTES_CEL_INPUT =
      "type.googleapis.com/xds.type.matcher.v3.HttpAttributesCelMatchInput";
  private final MatchInput input;
  @Nullable 
  private final Map<String, OnMatch> exactMatchMap;
  @Nullable 
  private final PrefixTrie prefixTrie;
  @Nullable 
  private final OnMatch onNoMatch;
  
  MatcherTree(Matcher.MatcherTree proto, @Nullable Matcher.OnMatch onNoMatchProto,
      Predicate<String> actionValidator) {
    if (!proto.hasInput()) {
      throw new IllegalArgumentException("MatcherTree must have input");
    }
    if (proto.getInput().getTypedConfig().getTypeUrl()
        .equals(TYPE_URL_HTTP_ATTRIBUTES_CEL_INPUT)) {
      throw new IllegalArgumentException(
          "HttpAttributesCelMatchInput cannot be used with MatcherTree");
    }
    
    if (proto.hasCustomMatch()) {
      throw new IllegalArgumentException("MatcherTree does not support custom_match");
    }
    
    this.input = UnifiedMatcher.resolveInput(proto.getInput());
    
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
      this.prefixTrie = null;
    } else if (proto.hasPrefixMatchMap()) {
      Matcher.MatcherTree.MatchMap matchMap = proto.getPrefixMatchMap();
      if (matchMap.getMapCount() == 0) {
        throw new IllegalArgumentException(
            "MatcherTree prefix_match_map must contain at least one entry");
      }
      this.prefixTrie = new PrefixTrie();
      for (Map.Entry<String, Matcher.OnMatch> entry : 
          matchMap.getMapMap().entrySet()) {
        this.prefixTrie.insert(entry.getKey(),
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
  MatchResult match(MatchContext context) {

    Object valueObj = input.apply(context);
    if (!(valueObj instanceof String)) {
      return onNoMatch != null ? onNoMatch.evaluate(context) : MatchResult.noMatch();
    }
    String value = (String) valueObj;
    if (exactMatchMap != null) {
      return matchExact(value, context);
    }
    return matchPrefix(value, context);
  }

  private MatchResult matchExact(String value, MatchContext context) {
    OnMatch match = exactMatchMap.get(value);
    if (match != null) {
      MatchResult result = match.evaluate(context);

      List<TypedExtensionConfig> accumulated = new ArrayList<>(result.actions);

      if (result.matched && !match.keepMatching) {
        return MatchResult.create(accumulated);
      }

      return MatchResult.noMatch(accumulated);
    }
    return onNoMatch != null ? onNoMatch.evaluate(context) : MatchResult.noMatch();
  }

  private MatchResult matchPrefix(String value, MatchContext context) {
    List<OnMatch> matchingPrefixes = prefixTrie.matchPrefixes(value);

    if (matchingPrefixes.isEmpty()) {
      return onNoMatch != null ? onNoMatch.evaluate(context) : MatchResult.noMatch();
    }

    List<TypedExtensionConfig> accumulatedActions = new ArrayList<>();

    for (OnMatch onMatch : matchingPrefixes) {
      MatchResult result = onMatch.evaluate(context);
      accumulatedActions.addAll(result.actions);

      if (result.matched && !onMatch.keepMatching) {
        return MatchResult.create(accumulatedActions);
      }
    }

    return MatchResult.noMatch(accumulatedActions);
  }

  private static final class PrefixTrie {
    private final TrieNode root = new TrieNode();

    void insert(String prefix, OnMatch onMatch) {
      TrieNode current = root;
      for (int i = 0; i < prefix.length(); i++) {
        char c = prefix.charAt(i);
        TrieNode child = current.children.get(c);
        if (child == null) {
          child = new TrieNode();
          current.children.put(c, child);
        }
        current = child;
      }
      current.onMatch = onMatch;
    }

    List<OnMatch> matchPrefixes(String value) {
      List<OnMatch> matchingPrefixes = new ArrayList<>();
      TrieNode current = root;
      if (current.onMatch != null) {
        matchingPrefixes.add(current.onMatch);
      }
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        current = current.children.get(c);
        if (current == null) {
          break;
        }
        if (current.onMatch != null) {
          matchingPrefixes.add(current.onMatch);
        }
      }

      // Evaluate longest matching prefix first
      Collections.reverse(matchingPrefixes);
      return matchingPrefixes;
    }

    private static final class TrieNode {
      final Map<Character, TrieNode> children = new HashMap<>();
      @Nullable
      OnMatch onMatch;
    }
  }
}
