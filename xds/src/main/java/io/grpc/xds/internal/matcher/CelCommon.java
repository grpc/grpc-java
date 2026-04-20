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

import com.google.common.collect.ImmutableSet;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.ast.CelReference;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import dev.cel.runtime.CelStandardFunctions;
import dev.cel.runtime.CelStandardFunctions.StandardFunction;
import dev.cel.runtime.standard.AddOperator.AddOverload;
import java.util.Map;

/**
 * Shared utilities for CEL-based matchers and extractors.
 */
final class CelCommon {
  private static final CelOptions CEL_OPTIONS = CelOptions.newBuilder()
      .enableComprehension(false)
      .maxRegexProgramSize(100)
      .build();
  private static final String REQUEST_VARIABLE = "request";
  private static final CelStandardFunctions FUNCTIONS = 
      CelStandardFunctions.newBuilder()
          .filterFunctions((func, over) -> {
            if (func == StandardFunction.STRING) {
              return false;
            }
            if (func == StandardFunction.ADD) {
              return !over.equals(AddOverload.ADD_STRING)
                  && !over.equals(AddOverload.ADD_LIST);
            }
            return true;
          })
          .build();

  /**
   * Set of allowed function names based on gRFC A106.
   */
  private static final ImmutableSet<String> ALLOWED_FUNCTIONS = ImmutableSet.of(
      "size", "matches", "contains", "startsWith", "endsWith", "timestamp", "duration",
      "int", "uint", "double", "bytes", "bool", "==", "!=", ">", "<", ">=", "<=",
      "&&", "||", "!", "+", "-", "*", "/", "%", "in", "has", "or", "equals",
      "index_map", "divide_int64", "int64_to_int64", "uint64_to_int64",
      "double_to_int64", "string_to_int64", "timestamp_to_int64");

  static final CelRuntime RUNTIME = CelRuntimeFactory.standardCelRuntimeBuilder()
      .setStandardEnvironmentEnabled(false)
      .setStandardFunctions(FUNCTIONS)
      .setOptions(CEL_OPTIONS)
      .build();

  private CelCommon() {}

  /**
   * Validates that the AST only references the allowed variable ("request")
   * and supported functions as defined in gRFC A106.
   */
  static void checkAllowedReferences(CelAbstractSyntaxTree ast) {
    for (Map.Entry<Long, CelReference> entry : ast.getReferenceMap().entrySet()) {
      CelReference ref = entry.getValue();

      // Check for variables (where overloadIds is empty)
      if (!ref.value().isPresent() && ref.overloadIds().isEmpty()) {
        if (!REQUEST_VARIABLE.equals(ref.name())) {
          throw new IllegalArgumentException(
              "CEL expression references unknown variable: " + ref.name());
        }
      } else if (!ref.overloadIds().isEmpty()) {
        String name = ref.name();
        if (name.isEmpty()) {
          boolean allowed = false;
          for (String id : ref.overloadIds()) {
            if (ALLOWED_FUNCTIONS.contains(id)) {
              allowed = true;
              break;
            }
          }
          if (!allowed) {
            throw new IllegalArgumentException(
                "CEL expression references unknown function with overload IDs: "
                    + ref.overloadIds());
          }
        } else if (!ALLOWED_FUNCTIONS.contains(name)) {
          throw new IllegalArgumentException(
              "CEL expression references unknown function: " + name);
        }
      }
    }
  }
}
