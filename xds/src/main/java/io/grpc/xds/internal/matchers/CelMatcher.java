/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.xds.internal.matchers;

import static com.google.common.base.Preconditions.checkNotNull;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelProtoAbstractSyntaxTree;
import dev.cel.expr.CheckedExpr;
import dev.cel.runtime.CelEvaluationException;
import io.grpc.xds.client.XdsResourceType.ResourceInvalidException;
import java.util.function.Predicate;

/** Unified Matcher API: xds.type.matcher.v3.CelMatcher. */
public class CelMatcher implements Predicate<HttpMatchInput> {

  private final GrpcCelEnvironment program;
  private final String description;

  private CelMatcher(CelAbstractSyntaxTree ast, String description) throws CelEvaluationException {
    this.program = new GrpcCelEnvironment(checkNotNull(ast));
    this.description = description != null ? description : "";
  }

  public static CelMatcher create(CelAbstractSyntaxTree ast) throws CelEvaluationException {
    return new CelMatcher(ast, null);
  }

  public static CelMatcher create(CelAbstractSyntaxTree ast, String description)
      throws CelEvaluationException {
    return new CelMatcher(ast, description);
  }

  public static CelMatcher fromEnvoyProto(com.github.xds.type.matcher.v3.CelMatcher proto)
      throws ResourceInvalidException {
    com.github.xds.type.v3.CelExpression exprMatch = proto.getExprMatch();
    // TODO(sergiitk): do i need this?
    // checkNotNull(exprMatch);

    if (!exprMatch.hasCelExprChecked()) {
      throw ResourceInvalidException.ofResource(proto, "cel_expr_checked is required");
    }

    // Canonical CEL.
    CheckedExpr celExprChecked = exprMatch.getCelExprChecked();

    // TODO(sergiitk): catch tree build errors?
    CelAbstractSyntaxTree ast = CelProtoAbstractSyntaxTree.fromCheckedExpr(celExprChecked).getAst();

    try {
      return new CelMatcher(ast, proto.getDescription());
    } catch (CelEvaluationException e) {
      throw ResourceInvalidException.ofResource(exprMatch,
          "Error Building CEL Program cel_expr_checked: " + e.getErrorCode() + " "
              + e.getMessage());
    }
  }

  public String description() {
    return description;
  }

  @Override
  public boolean test(HttpMatchInput httpMatchInput) {
    return program.eval(httpMatchInput);
  }
}
