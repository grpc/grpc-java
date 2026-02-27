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
import com.github.xds.type.matcher.v3.Matcher.MatcherList.Predicate;
import io.grpc.xds.internal.Matchers;
import io.grpc.xds.internal.matcher.MatcherRunner.MatchContext;
import java.util.ArrayList;
import java.util.List;

abstract class PredicateEvaluator {
  abstract boolean evaluate(MatchContext context);
  
  static PredicateEvaluator fromProto(Predicate proto) {
    if (proto.hasSinglePredicate()) {
      return new SinglePredicateEvaluator(proto.getSinglePredicate());
    } else if (proto.hasOrMatcher()) {
      return new OrMatcherEvaluator(proto.getOrMatcher());
    } else if (proto.hasAndMatcher()) {
      return new AndMatcherEvaluator(proto.getAndMatcher());
    } else if (proto.hasNotMatcher()) {
      return new NotMatcherEvaluator(proto.getNotMatcher());
    }
    throw new IllegalArgumentException(
        "Predicate must have one of: single_predicate, or_matcher, and_matcher, not_matcher");
  }

  private static final class SinglePredicateEvaluator extends PredicateEvaluator {
    private final MatchInput input;
    private final Matcher matcher;
    
    SinglePredicateEvaluator(Predicate.SinglePredicate proto) {
      if (!proto.hasInput()) {
        throw new IllegalArgumentException("SinglePredicate must have input");
      }
      this.input = UnifiedMatcher.resolveInput(proto.getInput());
      
      if (proto.hasValueMatch()) {
        Matchers.StringMatcher stringMatcher = fromStringMatcherProto(proto.getValueMatch());
        this.matcher = new Matcher() {
          @Override
          public boolean match(Object value) {
            if (value instanceof String) {
              return stringMatcher.matches((String) value);
            }
            return false;
          }

          @Override
          public Class<?> inputType() {
            return String.class;
          }
        };
      } else if (proto.hasCustomMatch()) {
        TypedExtensionConfig customConfig = proto.getCustomMatch();
        MatcherProvider provider = MatcherRegistry.getDefaultRegistry()
            .getMatcherProvider(customConfig.getTypedConfig().getTypeUrl());
        if (provider == null) {
          throw new IllegalArgumentException("Unsupported custom_match matcher: " 
              + customConfig.getTypedConfig().getTypeUrl());
        }
        this.matcher = provider.getMatcher(customConfig);
      } else {
        throw new IllegalArgumentException(
            "SinglePredicate must have either value_match or custom_match");
      }

      if (!input.outputType().isAssignableFrom(matcher.inputType()) 
          && !matcher.inputType().isAssignableFrom(input.outputType())) {
        throw new IllegalArgumentException("Type mismatch: input " + input.outputType().getName() 
            + " not compatible with matcher " + matcher.inputType().getName());
      }
    }
    
    @Override boolean evaluate(MatchContext context) {
      return matcher.match(input.apply(context));
    }
    
    private static Matchers.StringMatcher fromStringMatcherProto(
        com.github.xds.type.matcher.v3.StringMatcher proto) {
      return io.grpc.xds.internal.MatcherParser.parseStringMatcher(proto);
    }
  }
  
  private static final class OrMatcherEvaluator extends PredicateEvaluator {
    private final List<PredicateEvaluator> evaluators;
    
    OrMatcherEvaluator(Predicate.PredicateList proto) {
      if (proto.getPredicateCount() < 2) {
        throw new IllegalArgumentException("OrMatcher must have at least 2 predicates");
      }
      this.evaluators = new ArrayList<>(proto.getPredicateCount());
      for (Predicate p : proto.getPredicateList()) {
        evaluators.add(PredicateEvaluator.fromProto(p));
      }
    }
    
    @Override boolean evaluate(MatchContext context) {
      for (PredicateEvaluator e : evaluators) {
        if (e.evaluate(context)) {
          return true;
        }
      }
      return false;
    }
  }

  private static final class AndMatcherEvaluator extends PredicateEvaluator {
    private final List<PredicateEvaluator> evaluators;
    
    AndMatcherEvaluator(Predicate.PredicateList proto) {
      if (proto.getPredicateCount() < 2) {
        throw new IllegalArgumentException("AndMatcher must have at least 2 predicates");
      }
      this.evaluators = new ArrayList<>(proto.getPredicateCount());
      for (Predicate p : proto.getPredicateList()) {
        evaluators.add(PredicateEvaluator.fromProto(p));
      }
    }
    
    @Override boolean evaluate(MatchContext context) {
      for (PredicateEvaluator e : evaluators) {
        if (!e.evaluate(context)) {
          return false;
        }
      }
      return true;
    }
  }

  private static final class NotMatcherEvaluator extends PredicateEvaluator {
    private final PredicateEvaluator evaluator;
    
    NotMatcherEvaluator(Predicate proto) {
      this.evaluator = PredicateEvaluator.fromProto(proto);
    }
    
    @Override boolean evaluate(MatchContext context) {
      return !evaluator.evaluate(context);
    }
  }
}
