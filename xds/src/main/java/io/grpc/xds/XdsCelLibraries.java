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

package io.grpc.xds;

import com.google.api.expr.v1alpha1.Expr;
import com.google.common.collect.ImmutableMap;
import io.grpc.Metadata;
import io.grpc.xds.InterpreterException;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Represent a mock Cel libraries which contains functions needed for CEL evaluation engine. */
public interface XdsCelLibraries {

  /** Represent an expression which can be interpreted repeatedly using a given activation. */
  public interface Interpretable {

    /** Runs interpretation with the given activation which supplies name/value bindings. */
    Object eval(Activation activation) throws InterpreterException;
  }

  /** Interface to a CEL interpreter. */
  public interface Interpreter {
    
    /**
    * Creates an interpretable for the given expression.
    * 
    * <p>This method may run pre-processing and partial evaluation of the expression it gets passed.
    */
    Interpretable createInterpretable(Expr checkedExpr) throws InterpreterException;
  }

  /** An object which implements dispatching of function calls. */
  public interface Dispatcher {

    /**
     * Invokes a function based on given parameters.
     *
     * @param metadata Metadata used for error reporting.
     * @param exprId Expression identifier which can be used together with {@code metadata} to get
     *     information about the dispatch target for error reporting.
     * @param functionName the logical name of the function being invoked.
     * @param overloadIds A list of function overload ids. The dispatcher selects the unique 
     *     overload from this list with matching arguments.
     * @param args The arguments to pass to the function.
     * @return The result of the function call.
     * @throws InterpreterException if something goes wrong.
     */
    Object dispatch(Metadata metadata, long exprId, 
        String functionName, List<String> overloadIds, Object[] args)
        throws InterpreterException;
  }

  /** An object which allows to bind names to values. */
  public abstract class Activation {

    /** Resolves the given name to its value. Returns null if resolution fails. */
    @Nullable
    public abstract Object resolve(String name);

    /** Creates a binder backed up by a map. */
    public static Activation copyOf(Map<String, ?> map) {
      final ImmutableMap<String, Object> copy = ImmutableMap.copyOf(map);
      return new Activation() {
        @Nullable
        @Override
        public Object resolve(String name) {
          return copy.get(name);
        }
    
        @Override
        public String toString() {
          return copy.toString();
        }
      };
    }
  }
}