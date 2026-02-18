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

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.types.SimpleType;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;

/**
 * Executes compiled CEL expressions.
 */
public final class CelMatcher {
  private final CelRuntime.Program program;

  private CelMatcher(CelRuntime.Program program) {
    this.program = program;
  }

  /**
   * Compiles the AST into a CelMatcher.
   * Throws an Exception if validation or evaluation fails during compilation setup.
   */
  public static CelMatcher compile(CelAbstractSyntaxTree ast)
      throws Exception { 
    // CelEvaluationException -> inside cel-runtime -> Allowed in production signatures
    // CelValidationException -> inside cel-compiler -> Forbidden in production signatures
    if (ast.getResultType() != SimpleType.BOOL) {
      throw new IllegalArgumentException(
          "CEL expression must evaluate to boolean, got: " + ast.getResultType());
    }
    CelCommon.checkAllowedVariables(ast);
    CelRuntime.Program program = CelCommon.RUNTIME.createProgram(ast);
    return new CelMatcher(program);
  }

  /**
   * Evaluates the CEL expression against the input activation.
   */
  public boolean match(Object input) throws CelEvaluationException {
    Object result;
    if (input instanceof dev.cel.runtime.CelVariableResolver) {
      result = program.eval((dev.cel.runtime.CelVariableResolver) input);
    } else if (input instanceof java.util.Map) {
      @SuppressWarnings("unchecked")
      java.util.Map<String, ?> mapInput = (java.util.Map<String, ?>) input;
      result = program.eval(mapInput);
    } else {
      throw new CelEvaluationException(
          "Unsupported input type for CEL evaluation: " + input.getClass().getName());
    }
    
    if (result instanceof Boolean) {
      return (Boolean) result;
    }
    throw new CelEvaluationException(
        "CEL expression must evaluate to boolean, got: " + result.getClass().getName());
  }
}
