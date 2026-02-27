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
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;

/**
 * Shared utilities for CEL-based matchers and extractors.
 */
final class CelCommon {
  private static final CelOptions CEL_OPTIONS = CelOptions.newBuilder()
      .enableComprehension(false)
      .maxRegexProgramSize(100)
      .build();


  private static final dev.cel.runtime.CelStandardFunctions FUNCTIONS = 
      dev.cel.runtime.CelStandardFunctions.newBuilder()
          .filterFunctions((func, over) -> {
            if (func == dev.cel.runtime.CelStandardFunctions.StandardFunction.STRING) {
              return false;
            }
            if (func == dev.cel.runtime.CelStandardFunctions.StandardFunction.ADD) {
              return !over.equals(
                      (Object) dev.cel.runtime.standard.AddOperator.AddOverload.ADD_STRING)
                  && !over.equals(
                      (Object) dev.cel.runtime.standard.AddOperator.AddOverload.ADD_LIST);
            }
            return true;
          })
          .build();

  static final CelRuntime RUNTIME = CelRuntimeFactory.standardCelRuntimeBuilder()
      .setStandardEnvironmentEnabled(false)
      .setStandardFunctions(FUNCTIONS)
      .setOptions(CEL_OPTIONS)
      .build();

  private CelCommon() {}

  static void checkAllowedVariables(CelAbstractSyntaxTree ast) {
    for (java.util.Map.Entry<Long, dev.cel.common.ast.CelReference> entry : 
        ast.getReferenceMap().entrySet()) {
      dev.cel.common.ast.CelReference ref = entry.getValue();
      // If overload_id is empty, it's a variable reference or type name.
      // We only support "request".
      if (!ref.value().isPresent() && ref.overloadIds().isEmpty()) {
        if (!"request".equals(ref.name())) {
          throw new IllegalArgumentException(
              "CEL expression references unknown variable: " + ref.name());
        }
      }
    }
  }
}
