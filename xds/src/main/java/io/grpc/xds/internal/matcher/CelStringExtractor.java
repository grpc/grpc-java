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
import dev.cel.common.CelValidationException;
import dev.cel.common.types.SimpleType;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;


/**
 * Executes compiled CEL expressions that extract a string.
 */
public final class CelStringExtractor {


  private final CelRuntime.Program program;

  private CelStringExtractor(CelRuntime.Program program) {
    this.program = program;
  }

  /**
   * Compiles the AST into a CelStringExtractor.
   */
  public static CelStringExtractor compile(CelAbstractSyntaxTree ast) 
      throws CelValidationException, CelEvaluationException {
    if (ast.getResultType() != SimpleType.STRING && ast.getResultType() != SimpleType.DYN) {
      throw new IllegalArgumentException(
          "CEL expression must evaluate to string, got: " + ast.getResultType());
    }
    CelCommon.checkAllowedVariables(ast);
    CelRuntime.Program program = CelCommon.RUNTIME.createProgram(ast);
    return new CelStringExtractor(program);
  }

  /**
   * Compiles the CEL expression string into a CelStringExtractor.
   */
  public static CelStringExtractor compile(String expression)
      throws CelValidationException, CelEvaluationException {
    CelAbstractSyntaxTree ast = CelCommon.COMPILER.compile(expression).getAst();
    return compile(ast);
  }

  /**
   * Evaluates the CEL expression against the input activation and returns the string result.
   * Returns null if the result is not a string.
   */
  public String extract(Object input) throws CelEvaluationException {
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
    
    if (result instanceof String) {
      return (String) result;
    }
    // Return null key for non-string results (which will likely match nothing or be handled)
    return null; 
  }
}
