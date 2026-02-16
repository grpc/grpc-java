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
import dev.cel.runtime.CelEvaluationException;

/** Helper for compiling CEL strings to CelMatcher in tests, removing compiler dep from prod. */
public final class CelMatcherTestHelper {
  private CelMatcherTestHelper() {}

  public static CelMatcher compile(String expression)
      throws CelValidationException, CelEvaluationException {
    CelAbstractSyntaxTree ast = CelCommon.COMPILER.compile(expression).getAst();
    return CelMatcher.compile(ast);
  }
}
