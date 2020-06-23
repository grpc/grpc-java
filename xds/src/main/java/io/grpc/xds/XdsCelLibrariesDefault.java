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
import io.grpc.xds.XdsCelLibraries;
import java.lang.String;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of the Cel libraries.
 * 
 */
public class XdsCelLibrariesDefault implements XdsCelLibraries {

  /** Main launches the server from the command line. */
  public static void main(String[] args) throws InterpreterException {
    XdsCelLibraries.Dispatcher dispatcher = DefaultDispatcher.create();
    XdsCelLibraries.Interpreter interpreter = new DefaultInterpreter();
    Expr.Builder exprBuilder = Expr.newBuilder();
    Expr checkedResult = exprBuilder.build();

    Map<String, Object> map = new HashMap<>();
    map.put("requestUrlPath", new Object());
    map.put("requestHost", new Object());
    map.put("requestMethod", new Object());
    map.put("requestHeaders", new Object());
    map.put("sourceAddress", new Object());
    map.put("sourcePort", new Object());
    map.put("destinationAddress", new Object());

    // ImmutableMap<String, Object> apiAttributes = ImmutableMap.builder()
    //     .put("requestUrlPath", new Object())
    //     .put("requestHost", new Object())
    //     .put("requestMethod", new Object())
    //     .put("requestHeaders", new Object())
    //     .put("sourceAddress", new Object())
    //     .put("sourcePort", new Object())
    //     .build();

    ImmutableMap<String, Object> apiAttributes = ImmutableMap.copyOf(map);

    XdsCelLibraries.Activation activation = XdsCelLibraries.Activation.copyOf(apiAttributes);
    Object result = interpreter.createInterpretable(checkedResult).eval(activation);
  }

  public static class DefaultInterpreter implements XdsCelLibraries.Interpreter {
    @Override
    public XdsCelLibraries.Interpretable createInterpretable(Expr expr) 
    throws InterpreterException {
      return new DefaultInterpretable(expr);
    }

    private class DefaultInterpretable implements XdsCelLibraries.Interpretable {
      private final Expr expr;
      private final Metadata metadata;

      public DefaultInterpretable(Expr expr) {
        this.metadata = new Metadata();
        this.expr = expr;
      }

      @Override
      public Object eval(XdsCelLibraries.Activation activation) throws InterpreterException {
        return new Object();
      }
    }
  }

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
}

