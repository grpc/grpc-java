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
import io.grpc.xds.InterpreterException;

/** Default implementation of {@link Interpreter}. */
public class DefaultInterpreter implements Interpreter {
  /**
  * Creates a new interpreter
  * @param typeProvider object which allows to construct and inspect messages.
  * @param dispatcher a method dispatcher.
  */
  public DefaultInterpreter(RuntimeTypeProvider typeProvider, Dispatcher dispatcher) {}  
  
  @Override
  public Interpretable createInterpretable(CheckedExpr checkedExpr) 
    throws InterpreterException {
    return new DefaultInterpretable(checkedExpr);
  }

  private static class DefaultInterpretable implements Interpretable {
    /**
    * Creates a new interpretable.
    * @param checkedExpr a Cel expression.
    */
    public DefaultInterpretable(CheckedExpr checkedExpr) {}

    @Override
    public Object eval(Activation activation) throws InterpreterException {
      return new Object();
    }
  }
}
