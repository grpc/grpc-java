/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds.internal;

import com.github.xds.type.matcher.v3.Matcher;
import com.github.xds.type.matcher.v3.Matcher.MatcherList.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Implementation of the xDS Unified Matcher API.
 * 
 * @param <T> The type of the action result.
 */
public abstract class UnifiedMatcher<T> {

  /**
   * Represents the inputs required for matching.
   */
  public interface MatchingData {
    /**
     * Retrieves the value for the given input configuration.
     * The implementation is responsible for interpreting the input config (e.g.,
     * extracting header name).
     */
    @Nullable
    String getRelayedInput(com.github.xds.core.v3.TypedExtensionConfig inputConfig);
  }

  /**
   * Action parser interface.
   */
  public interface ActionParser<T> {
    T parse(com.github.xds.core.v3.TypedExtensionConfig config);
  }

  /**
   * Evaluates the matcher against the provided data.
   * Returns the action config if matched, null otherwise.
   */
  @Nullable
  public abstract T match(MatchingData data);

  public static <T> UnifiedMatcher<T> create(Matcher proto, ActionParser<T> parser) {
    return createRecursive(proto, parser, 0);
  }

  private static <T> UnifiedMatcher<T> createRecursive(Matcher proto, ActionParser<T> parser,
      int depth) {
    if (depth > 8) {
      throw new IllegalArgumentException("Maximum recursion depth of 8 exceeded");
    }
    if (proto.hasMatcherList()) {
      return new MatcherList<>(proto.getMatcherList(), parser, depth);
    } else if (proto.hasMatcherTree()) {
      return new MatcherTree<>(proto.getMatcherTree(), parser, depth);
    }
    return new OnNoMatch<>(proto.getOnNoMatch(), parser, depth);
  }

  private static class OnNoMatch<T> extends UnifiedMatcher<T> {
    @Nullable
    private final UnifiedMatcher<T> delegate;

    OnNoMatch(Matcher.OnMatch proto, ActionParser<T> parser, int depth) {
      if (proto.hasMatcher()) {
        this.delegate = createRecursive(proto.getMatcher(), parser, depth + 1);
      } else if (proto.hasAction()) {
        this.delegate = new ActionMatcher<>(proto.getAction(), parser);
      } else {
        this.delegate = null;
      }
    }

    @Override
    public T match(MatchingData data) {
      return delegate != null ? delegate.match(data) : null;
    }
  }

  private static class ActionMatcher<T> extends UnifiedMatcher<T> {
    private final T action;

    ActionMatcher(com.github.xds.core.v3.TypedExtensionConfig proto, ActionParser<T> parser) {
      this.action = parser.parse(proto);
    }

    @Override
    public T match(MatchingData data) {
      return action;
    }
  }

  private static class MatcherList<T> extends UnifiedMatcher<T> {
    private final List<FieldMatcher<T>> fieldMatchers;

    MatcherList(Matcher.MatcherList proto, ActionParser<T> parser, int depth) {
      List<FieldMatcher<T>> matchers = new ArrayList<>();
      for (Matcher.MatcherList.FieldMatcher fieldMatcher : proto.getMatchersList()) {
        matchers.add(new FieldMatcher<>(fieldMatcher, parser, depth));
      }
      this.fieldMatchers = ImmutableList.copyOf(matchers);
    }

    @Override
    public T match(MatchingData data) {
      for (FieldMatcher<T> matcher : fieldMatchers) {
        T result = matcher.match(data);
        if (result != null) {
          return result;
        }
      }
      return null;
    }
  }

  private static class FieldMatcher<T> {
    private final UnifiedPredicate predicate;
    private final UnifiedMatcher<T> onMatch;

    FieldMatcher(Matcher.MatcherList.FieldMatcher proto, ActionParser<T> parser, int depth) {
      this.predicate = UnifiedPredicate.create(proto.getPredicate());
      Matcher.OnMatch onMatchProto = proto.getOnMatch();
      if (onMatchProto.hasAction()) {
        this.onMatch = new ActionMatcher<>(onMatchProto.getAction(), parser);
      } else if (onMatchProto.hasMatcher()) {
        this.onMatch = createRecursive(onMatchProto.getMatcher(), parser, depth + 1);
      } else {
        this.onMatch = new AlwaysFalseMatcher<>();
      }
    }

    @Nullable
    T match(MatchingData data) {
      if (predicate.matches(data)) {
        return onMatch.match(data);
      }
      return null;
    }
  }

  private static class MatcherTree<T> extends UnifiedMatcher<T> {
    private final MatcherInput input;
    private final Map<String, UnifiedMatcher<T>> exactMap;
    private final Map<String, UnifiedMatcher<T>> prefixMap;

    MatcherTree(Matcher.MatcherTree proto, ActionParser<T> parser, int depth) {
      this.input = new MatcherInput(proto.getInput());
      this.exactMap = parseMap(proto.getExactMatchMap().getMapMap(), parser, depth);
      this.prefixMap = parseMap(proto.getPrefixMatchMap().getMapMap(), parser, depth);
    }

    private Map<String, UnifiedMatcher<T>> parseMap(
        Map<String, Matcher.OnMatch> protoMap, ActionParser<T> parser, int depth) {
      ImmutableMap.Builder<String, UnifiedMatcher<T>> builder = ImmutableMap.builder();
      for (Map.Entry<String, Matcher.OnMatch> entry : protoMap.entrySet()) {
        if (entry.getValue().hasAction()) {
          builder.put(entry.getKey(), new ActionMatcher<>(entry.getValue().getAction(), parser));
        } else if (entry.getValue().hasMatcher()) {
          builder.put(entry.getKey(),
              createRecursive(entry.getValue().getMatcher(), parser, depth + 1));
        }
      }
      return builder.build();
    }

    @Override
    public T match(MatchingData data) {
      String value = input.get(data);
      if (value == null) {
        return null;
      }
      // Exact match
      UnifiedMatcher<T> exact = exactMap.get(value);
      if (exact != null) {
        return exact.match(data);
      }
      // Prefix match
      String bestPrefix = null;
      UnifiedMatcher<T> bestMatch = null;
      for (Map.Entry<String, UnifiedMatcher<T>> entry : prefixMap.entrySet()) {
        if (value.startsWith(entry.getKey())) {
          if (bestPrefix == null || entry.getKey().length() > bestPrefix.length()) {
            bestPrefix = entry.getKey();
            bestMatch = entry.getValue();
          }
        }
      }
      if (bestMatch != null) {
        return bestMatch.match(data);
      }
      return null;
    }
  }

  private static class MatcherInput {
    private final com.github.xds.core.v3.TypedExtensionConfig config;

    MatcherInput(com.github.xds.core.v3.TypedExtensionConfig proto) {
      this.config = proto;
    }

    String get(MatchingData data) {
      return data.getRelayedInput(config);
    }
  }

  private static class AlwaysFalseMatcher<T> extends UnifiedMatcher<T> {
    @Override
    public T match(MatchingData data) {
      return null;
    }
  }

  private abstract static class UnifiedPredicate {
    abstract boolean matches(MatchingData data);

    static UnifiedPredicate create(Predicate proto) {
      if (proto.hasSinglePredicate()) {
        return new SinglePredicate(proto.getSinglePredicate());
      } else if (proto.hasOrMatcher()) {
        return new OrPredicate(proto.getOrMatcher());
      } else if (proto.hasAndMatcher()) {
        return new AndPredicate(proto.getAndMatcher());
      } else if (proto.hasNotMatcher()) {
        return new NotPredicate(proto.getNotMatcher());
      }
      return new AlwaysFalsePredicate();
    }
  }

  private static class AlwaysFalsePredicate extends UnifiedPredicate {
    @Override
    boolean matches(MatchingData data) {
      return false;
    }
  }

  private static class SinglePredicate extends UnifiedPredicate {
    private final MatcherInput input;
    private final Matchers.StringMatcher stringMatcher;

    SinglePredicate(Predicate.SinglePredicate proto) {
      this.input = new MatcherInput(proto.getInput());
      if (proto.hasValueMatch()) {
        this.stringMatcher = MatcherParser.parseStringMatcher(proto.getValueMatch());
      } else {
        this.stringMatcher = null;
      }
    }

    @Override
    boolean matches(MatchingData data) {
      if (stringMatcher != null) {
        String value = input.get(data);
        if (value != null) {
          return stringMatcher.matches(value);
        }
      }
      return false;
    }
  }

  private static class OrPredicate extends UnifiedPredicate {
    private final List<UnifiedPredicate> predicates;

    OrPredicate(Predicate.PredicateList proto) {
      List<UnifiedPredicate> list = new ArrayList<>();
      for (Predicate p : proto.getPredicateList()) {
        list.add(UnifiedPredicate.create(p));
      }
      this.predicates = ImmutableList.copyOf(list);
    }

    @Override
    boolean matches(MatchingData data) {
      for (UnifiedPredicate p : predicates) {
        if (p.matches(data)) {
          return true;
        }
      }
      return false;
    }
  }

  private static class AndPredicate extends UnifiedPredicate {
    private final List<UnifiedPredicate> predicates;

    AndPredicate(Predicate.PredicateList proto) {
      List<UnifiedPredicate> list = new ArrayList<>();
      for (Predicate p : proto.getPredicateList()) {
        list.add(UnifiedPredicate.create(p));
      }
      this.predicates = ImmutableList.copyOf(list);
    }

    @Override
    boolean matches(MatchingData data) {
      for (UnifiedPredicate p : predicates) {
        if (!p.matches(data)) {
          return false;
        }
      }
      return true;
    }
  }

  private static class NotPredicate extends UnifiedPredicate {
    private final UnifiedPredicate predicate;

    NotPredicate(Predicate proto) {
      this.predicate = UnifiedPredicate.create(proto);
    }

    @Override
    boolean matches(MatchingData data) {
      return !predicate.matches(data);
    }
  }
}
