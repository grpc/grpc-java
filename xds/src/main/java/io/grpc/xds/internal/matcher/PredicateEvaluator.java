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
import dev.cel.runtime.CelEvaluationException;
import io.grpc.xds.internal.Matchers;
import io.grpc.xds.internal.matcher.MatcherRunner.MatchContext;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

abstract class PredicateEvaluator {
  private static final String TYPE_URL_CEL_MATCHER =
      "type.googleapis.com/xds.type.matcher.v3.CelMatcher";
  private static final String TYPE_URL_HTTP_ATTRIBUTES_CEL_INPUT =
      "type.googleapis.com/xds.type.matcher.v3.HttpAttributesCelMatchInput";

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
    private final MatchInput<MatchContext> input;
    @Nullable private final Matchers.StringMatcher stringMatcher;
    @Nullable private final CelMatcher celMatcher;
    
    SinglePredicateEvaluator(Predicate.SinglePredicate proto) {
      if (!proto.hasInput()) {
        throw new IllegalArgumentException("SinglePredicate must have input");
      }
      this.input = UnifiedMatcher.resolveInput(proto.getInput());
      
      if (proto.hasValueMatch()) {
        if (proto.getInput().getTypedConfig().getTypeUrl()
            .equals(TYPE_URL_HTTP_ATTRIBUTES_CEL_INPUT)) {
          throw new IllegalArgumentException(
              "HttpAttributesCelMatchInput cannot be used with StringMatcher");
        }
        this.stringMatcher = fromStringMatcherProto(proto.getValueMatch());
        this.celMatcher = null;
      } else if (proto.hasCustomMatch()) {
        this.stringMatcher = null;
        TypedExtensionConfig customConfig = proto.getCustomMatch();
        if (customConfig.getTypedConfig().getTypeUrl().equals(TYPE_URL_CEL_MATCHER)) {
          try {
            com.github.xds.type.matcher.v3.CelMatcher celProto = customConfig.getTypedConfig()
                .unpack(com.github.xds.type.matcher.v3.CelMatcher.class);
            if (celProto.hasExprMatch()) {
              com.github.xds.type.v3.CelExpression expr = celProto.getExprMatch();
              if (expr.hasCelExprChecked()) {
                dev.cel.common.CelAbstractSyntaxTree ast = 
                    dev.cel.common.CelProtoAbstractSyntaxTree.fromCheckedExpr(
                        expr.getCelExprChecked()).getAst();
                this.celMatcher = CelMatcher.compile(ast);
              } else {
                throw new IllegalArgumentException(
                    "CelMatcher must have cel_expr_checked");
              }
            } else {
              throw new IllegalArgumentException("CelMatcher must have expr_match");
            }
          } catch (Exception e) {
            throw new IllegalArgumentException("Invalid CelMatcher config", e);
          }
        } else {
          throw new IllegalArgumentException("Unsupported custom_match matcher: " 
              + customConfig.getTypedConfig().getTypeUrl());
        }
        if (this.celMatcher != null && !proto.getInput().getTypedConfig().getTypeUrl()
            .equals(TYPE_URL_HTTP_ATTRIBUTES_CEL_INPUT)) {
          throw new IllegalArgumentException(
              "CelMatcher can only be used with HttpAttributesCelMatchInput");
        }
      } else {
        throw new IllegalArgumentException(
            "SinglePredicate must have either value_match or custom_match");
      }
    }
    
    @Override boolean evaluate(MatchContext context) {
      Object value = input.apply(context);
      if (stringMatcher != null) {
        if (value instanceof String) {
          return stringMatcher.matches((String) value);
        }
        return false;
      }
      if (celMatcher != null) {
        try {
          return celMatcher.match(value);
        } catch (CelEvaluationException e) {
          return false;
        }
      }
      return false; 
    }
    
    private static Matchers.StringMatcher fromStringMatcherProto(
        com.github.xds.type.matcher.v3.StringMatcher proto) {
      if (proto.hasExact()) {
        return Matchers.StringMatcher.forExact(proto.getExact(), proto.getIgnoreCase());
      }
      if (proto.hasPrefix()) {
        String prefix = proto.getPrefix();
        if (prefix.isEmpty()) {
          throw new IllegalArgumentException(
              "StringMatcher prefix (match_pattern) must be non-empty");
        }
        return Matchers.StringMatcher.forPrefix(prefix, proto.getIgnoreCase());
      }
      if (proto.hasSuffix()) {
        String suffix = proto.getSuffix();
        if (suffix.isEmpty()) {
          throw new IllegalArgumentException(
              "StringMatcher suffix (match_pattern) must be non-empty");
        }
        return Matchers.StringMatcher.forSuffix(suffix, proto.getIgnoreCase());
      }
      if (proto.hasContains()) {
        String contains = proto.getContains();
        if (contains.isEmpty()) {
          throw new IllegalArgumentException(
              "StringMatcher contains (match_pattern) must be non-empty");
        }
        return Matchers.StringMatcher.forContains(contains, proto.getIgnoreCase());
      }
      if (proto.hasSafeRegex()) {
        String regex = proto.getSafeRegex().getRegex();
        if (regex.isEmpty()) {
          throw new IllegalArgumentException(
              "StringMatcher regex (match_pattern) must be non-empty");
        }
        return Matchers.StringMatcher.forSafeRegEx(
            com.google.re2j.Pattern.compile(regex));
      }
      throw new IllegalArgumentException("Unknown StringMatcher match pattern");
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
