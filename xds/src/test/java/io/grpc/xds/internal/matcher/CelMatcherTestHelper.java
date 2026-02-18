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
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;

/** Helper for compiling CEL strings to CelMatcher in tests, removing compiler dep from prod. */
public final class CelMatcherTestHelper {
  private static final dev.cel.checker.CelStandardDeclarations DECLARATIONS = 
      dev.cel.checker.CelStandardDeclarations.newBuilder()
          .filterFunctions((func, over) -> {
            if (func == dev.cel.checker.CelStandardDeclarations.StandardFunction.STRING) {
              return false;
            }
            if (func == dev.cel.checker.CelStandardDeclarations.StandardFunction.ADD) {
              String id = over.celOverloadDecl().overloadId();
              return !id.equals("add_string") && !id.equals("add_list");
            }
            return true;
          })
          .build();

  private static final CelCompiler COMPILER = CelCompilerFactory.standardCelCompilerBuilder()
      .setStandardEnvironmentEnabled(false)
      .setStandardDeclarations(DECLARATIONS)
      .addVar("request", SimpleType.DYN)
      .setOptions(CelOptions.newBuilder()
          .enableComprehension(false)
          .build())
      .build();

  private CelMatcherTestHelper() {}

  public static CelAbstractSyntaxTree compileAst(String expression)
      throws CelValidationException {
    return COMPILER.compile(expression).getAst();
  }

  public static CelMatcher compile(String expression)
      throws Exception {
    CelAbstractSyntaxTree ast = COMPILER.compile(expression).getAst();
    return CelMatcher.compile(ast);
  }

  public static CelStringExtractor compileStringExtractor(String expression)
      throws Exception {
    CelAbstractSyntaxTree ast = COMPILER.compile(expression).getAst();
    return CelStringExtractor.compile(ast);
  }
}
