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

import io.grpc.Metadata;
import io.grpc.xds.InterpreterException;
import java.util.List;

/** An object which implements dispatching of function calls. */
public interface Dispatcher {
  /**
   * Invokes a function based on given parameters.
   *
   * @param metadata Metadata used for error reporting.
   * @param exprId Expression identifier which can be used together with {@code metadata} to get
   *     information about the dispatch target for error reporting.
   * @param functionName the logical name of the function being invoked.
   * @param overloadIds A list of function overload ids. The dispatcher selects the unique overload
   *     from this list with matching arguments.
   * @param args The arguments to pass to the function.
   * @return The result of the function call.
   * @throws InterpreterException if something goes wrong.
   */
  Object dispatch(
      Metadata metadata, long exprId, String functionName, List<String> overloadIds, Object[] args)
      throws InterpreterException;
}
