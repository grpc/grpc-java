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
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelProtoAbstractSyntaxTree;
import dev.cel.runtime.CelEvaluationException;

/**
 * Matcher for CEL expressions handling xDS CEL Matcher extension.
 */
final class CelStateMatcher implements Matcher {
  private final CelMatcher compiledEndpoint;

  CelStateMatcher(CelMatcher compiledEndpoint) {
    this.compiledEndpoint = compiledEndpoint;
  }

  @Override
  public boolean match(Object value) {
    try {
      return compiledEndpoint.match(value);
    } catch (CelEvaluationException e) {
      return false;
    }
  }

  @Override
  public Class<?> inputType() {
    return GrpcCelEnvironment.class;
  }

  static final class Provider implements MatcherProvider {
    @Override
    public Matcher getMatcher(TypedExtensionConfig config) {
      try {
        com.github.xds.type.matcher.v3.CelMatcher celProto = config.getTypedConfig()
            .unpack(com.github.xds.type.matcher.v3.CelMatcher.class);
        if (!celProto.hasExprMatch()) {
          throw new IllegalArgumentException("CelMatcher must have expr_match");
        }
        com.github.xds.type.v3.CelExpression expr = celProto.getExprMatch();
        if (!expr.hasCelExprChecked()) {
          throw new IllegalArgumentException("CelMatcher must have cel_expr_checked");
        }
        CelAbstractSyntaxTree ast = 
            CelProtoAbstractSyntaxTree.fromCheckedExpr(
                expr.getCelExprChecked()).getAst();
        CelMatcher compiled = CelMatcher.compile(ast);
        
        return new CelStateMatcher(compiled);
      } catch (Exception e) {
        throw new IllegalArgumentException("Invalid CelMatcher config", e);
      }
    }

    @Override
    public String typeUrl() {
      return "type.googleapis.com/xds.type.matcher.v3.CelMatcher";
    }
  }
}
