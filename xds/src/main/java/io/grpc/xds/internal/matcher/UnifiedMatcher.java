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
 * Represents a compiled xDS Matcher.
 */
public abstract class UnifiedMatcher {

  static final int MAX_RECURSION_DEPTH = 16;
 
  @Nullable
  public abstract MatchResult match(MatchContext context, int depth);

  static MatchInput resolveInput(TypedExtensionConfig config) {
    String typeUrl = config.getTypedConfig().getTypeUrl();
    MatchInputProvider provider = MatchInputRegistry.getDefaultRegistry().getProvider(typeUrl);
    if (provider == null) {
      throw new IllegalArgumentException("Unsupported input type: " + typeUrl);
    }
    return provider.getInput(config);
  }

  /**
   * Parses a proto Matcher into a UnifiedMatcher.
   *
   * @param proto the proto matcher
   * @param actionValidator a predicate that returns true if the action type URL is supported
   */
  public static UnifiedMatcher fromProto(Matcher proto,
      java.util.function.Predicate<String> actionValidator) {
    checkRecursionDepth(proto, 0);
    Matcher.OnMatch onNoMatch = proto.hasOnNoMatch() ? proto.getOnNoMatch() : null;
    if (proto.hasMatcherList()) {
      return new MatcherList(proto.getMatcherList(), onNoMatch, actionValidator);
    } else if (proto.hasMatcherTree()) {
      return new MatcherTree(proto.getMatcherTree(), onNoMatch, actionValidator);
    }
    return new NoOpMatcher(onNoMatch, actionValidator);
  }

  /**
   * Parses a proto Matcher into a UnifiedMatcher, allowing all actions.
   */
  public static UnifiedMatcher fromProto(Matcher proto) {
    return fromProto(proto, (typeUrl) -> true);
  }

  private static void checkRecursionDepth(Matcher proto, int currentDepth) {
    if (currentDepth > MAX_RECURSION_DEPTH) {
      throw new IllegalArgumentException(
          "Matcher tree depth exceeds limit of " + MAX_RECURSION_DEPTH);
    }
    if (proto.hasMatcherList()) {
      for (Matcher.MatcherList.FieldMatcher fm : proto.getMatcherList().getMatchersList()) {
        if (fm.hasOnMatch() && fm.getOnMatch().hasMatcher()) {
          checkRecursionDepth(fm.getOnMatch().getMatcher(), currentDepth + 1);
        }
      }
    } else if (proto.hasMatcherTree()) {
      Matcher.MatcherTree tree = proto.getMatcherTree();
      if (tree.hasExactMatchMap()) {
        for (Matcher.OnMatch onMatch : tree.getExactMatchMap().getMapMap().values()) {
          if (onMatch.hasMatcher()) {
            checkRecursionDepth(onMatch.getMatcher(), currentDepth + 1);
          }
        }
      } else if (tree.hasPrefixMatchMap()) {
        for (Matcher.OnMatch onMatch : tree.getPrefixMatchMap().getMapMap().values()) {
          if (onMatch.hasMatcher()) {
            checkRecursionDepth(onMatch.getMatcher(), currentDepth + 1);
          }
        }
      }
    }
    if (proto.hasOnNoMatch() && proto.getOnNoMatch().hasMatcher()) {
      checkRecursionDepth(proto.getOnNoMatch().getMatcher(), currentDepth + 1);
    }
  }
  
  private static final class NoOpMatcher extends UnifiedMatcher {
    @Nullable private final OnMatch onNoMatch;
    
    NoOpMatcher(@Nullable Matcher.OnMatch onNoMatchProto,
        java.util.function.Predicate<String> actionValidator) {
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
      if (onNoMatch != null) {
        return onNoMatch.evaluate(context, depth);
      }
      return MatchResult.noMatch();
    }
  }
}
