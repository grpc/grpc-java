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

import com.google.common.collect.ImmutableMap;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import java.util.function.Predicate;

/** Unified Matcher API: xds.type.matcher.v3.CelMatcher. */
public class CelMatcher implements Predicate<HttpMatchInput> {
  private static final CelRuntime CEL_RUNTIME =
      CelRuntimeFactory.standardCelRuntimeBuilder().build();

  private final CelRuntime.Program program;
  private final String description;

  private CelMatcher(CelAbstractSyntaxTree ast, String description) throws CelEvaluationException {
    this.program = CEL_RUNTIME.createProgram(checkNotNull(ast));
    this.description = description != null ? description : "";
  }

  public static CelMatcher create(CelAbstractSyntaxTree ast) throws CelEvaluationException {
    return new CelMatcher(ast, null);
  }

  public static CelMatcher create(CelAbstractSyntaxTree ast, String description)
      throws CelEvaluationException {
    return new CelMatcher(ast, description);
  }

  public String description() {
    return description;
  }

  @Override
  public boolean test(HttpMatchInput httpMatchInput) {
    // if (httpMatchInput.headers().keys().isEmpty()) {
    //   return false;
    // }
    // TODO(sergiitk): [IMPL] convert headers to cel args
    try {
      return (boolean) program.eval(ImmutableMap.of("my_var", "Hello World"));
    } catch (CelEvaluationException e) {
      // TODO(sergiitk): [IMPL] log cel error?
      return false;
    }
  }
}
