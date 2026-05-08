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
import java.util.regex.Pattern;

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



  private static final ImmutableSet<String> ALLOWED_EXACT_OVERLOAD_IDS = ImmutableSet.of(
      "equals", "not_equals", "logical_and", "logical_or", "logical_not");

  /**
   * Regular expression pattern to validate internal CEL overload IDs.
   *
   * <p>Standard CEL operators and conversion functions often have empty names in the
   * AST and are identified solely by their overload IDs (e.g., {@code equals} for
   * {@code ==}, {@code divide_int64} for {@code /}).
   *
   * <p>This pattern matches allowed overload IDs by their prefixes (e.g.,
   * {@code divide}, {@code size}), optionally followed by numeric types 
   * (e.g., {@code int64}) and type-specific suffixes (e.g., {@code _string}, 
   * {@code _int64}).
   */
  private static final Pattern ALLOWED_OVERLOAD_ID_PREFIX_PATTERN = Pattern.compile(
      "^(size|matches|contains|startsWith|endsWith|starts_with|ends_with|"
          + "timestamp|duration|in|index|has|int|uint|double|string|bytes|bool|"
          + "less|less_equals|greater|greater_equals|"
          + "add|subtract|multiply|divide|modulo|negate)"
          + "[0-9]*(_.*)?$");

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
            if (id.equals("add_string") || id.equals("add_list") || id.endsWith("_to_string")) {
              allowed = false;
              break;
            }
            if (ALLOWED_EXACT_OVERLOAD_IDS.contains(id)
                || ALLOWED_OVERLOAD_ID_PREFIX_PATTERN.matcher(id).matches()) {
              allowed = true;
              break;
            }
          }
          if (!allowed) {
            throw new IllegalArgumentException(
                "CEL expression references unknown function with overload IDs: "
                    + ref.overloadIds());
          }
        } else {
          // Standard conversion functions (like string(x)) are named in the AST.
          // We must explicitly reject 'string' here since it's disabled in the environment.
          if (name.equals("string")) {
            throw new IllegalArgumentException(
                "CEL expression references unknown function with overload IDs: "
                    + ref.overloadIds());
          }
          throw new IllegalArgumentException(
              "CEL expression references unsupported named function: " + name);
        }
      }
    }
  }
}
