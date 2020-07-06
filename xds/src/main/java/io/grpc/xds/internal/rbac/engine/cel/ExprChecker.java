/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.xds.internal;

import com.google.api.expr.v1alpha1.CheckedExpr;
import com.google.api.expr.v1alpha1.ParsedExpr;
import com.google.api.expr.v1alpha1.Type;
import com.google.common.base.Optional;

/** The expression type checker. */
public class ExprChecker {
  /**
   * Checks the parsed expression within the given environment and returns a checked expression.
   * Conditions for type checking and the result are described in checked.proto.
   */
  public static CheckedExpr check(Env env, String inContainer, ParsedExpr parsedExpr) {
    Optional<Type> expectedResultType = Optional.absent();
    return typecheck(env, inContainer, parsedExpr, expectedResultType);
  }

  /** 
   * Type-checks the parsed expression within the given environment 
   * and returns a checked expression.
   */
  public static CheckedExpr typecheck(
      Env env, String inContainer, ParsedExpr parsedExpr, Optional<Type> expectedResultType) {
    return CheckedExpr.newBuilder().build(); 
  }
}
