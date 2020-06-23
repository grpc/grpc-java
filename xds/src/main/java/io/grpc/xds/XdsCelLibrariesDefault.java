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
import com.google.common.base.Preconditions;
import com.google.protobuf.Descriptors.Descriptor;
import io.grpc.Metadata;
import io.grpc.xds.InterpreterException;
import io.grpc.xds.XdsCelLibraries;
import java.lang.String;
import java.util.List;

/** Default implementation of {@link XdsCelLibraries}. */
public class XdsCelLibrariesDefault implements XdsCelLibraries {
  /** Default implementation of {@link XdsCelLibraries.Interpreter}. */
  public static class DefaultInterpreter implements XdsCelLibraries.Interpreter {
    private final RuntimeTypeProvider typeProvider;
    private final Dispatcher dispatcher;

    /**
    * Creates a new interpreter
    * @param typeProvider object which allows to construct and inspect messages.
    * @param dispatcher a method dispatcher.
    */
    public DefaultInterpreter(RuntimeTypeProvider typeProvider, Dispatcher dispatcher) {
      this.typeProvider = Preconditions.checkNotNull(typeProvider);
      this.dispatcher = Preconditions.checkNotNull(dispatcher);
    }

    @Override
    public XdsCelLibraries.Interpretable createInterpretable(Expr expr) 
    throws InterpreterException {
      return new DefaultInterpretable(expr);
    }

    private class DefaultInterpretable implements XdsCelLibraries.Interpretable {
      private final Expr expr;

      /**
      * Creates a new interpretable.
      * @param expr a Cel expression.
      */
      public DefaultInterpretable(Expr expr) {
        this.expr = Preconditions.checkNotNull(expr);
      }

      @Override
      public Object eval(XdsCelLibraries.Activation activation) throws InterpreterException {
        return new Object();
      }
    }
  }

  /** Default implementation of {@link XdsCelLibraries.Dispatcher}. */
  public static class DefaultDispatcher implements XdsCelLibraries.Dispatcher {
    /** Creates a new dispatcher with all standard functions. */
    public static DefaultDispatcher create() {
      return new DefaultDispatcher();
    }

    @Override
    public Object dispatch(Metadata metadata, long exprId, 
        String functionName, List<String> overloadIds, Object[] args) 
        throws InterpreterException {
      return new Object();
    }
  }

  /** Default implementation of {@link XdsCelLibraries.RuntimeTypeProvider} */
  public static class DescriptorMessageProvider implements XdsCelLibraries.RuntimeTypeProvider {
    /**
     * Creates a new message provider that provides only {@link DynamicMessage DynamicMessages} for
     * the specified descriptors.
     */
    public static DescriptorMessageProvider dynamicMessages(Iterable<Descriptor> descriptors) {
      return new DescriptorMessageProvider();
    }
  }
}