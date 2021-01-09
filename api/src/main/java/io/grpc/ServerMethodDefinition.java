/*
 * Copyright 2014 The gRPC Authors
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

package io.grpc;

import java.util.Collections;
import java.util.List;

/**
 * Definition of a method exposed by a {@link Server}.
 *
 * @see ServerServiceDefinition
 */
public final class ServerMethodDefinition<ReqT, RespT> {
  private final MethodDescriptor<ReqT, RespT> method;
  private final ServerCallHandler<ReqT, RespT> handler;
  private final List<ServerStreamTracer.Factory> tracerFactories;

  private ServerMethodDefinition(
      MethodDescriptor<ReqT, RespT> method,
      ServerCallHandler<ReqT, RespT> handler,
      List<ServerStreamTracer.Factory> tracerFactories) {
    this.method = method;
    this.handler = handler;
    this.tracerFactories = Collections.unmodifiableList(tracerFactories);
  }

  /**
   * Create a new instance.
   *
   * @param method the {@link MethodDescriptor} for this method.
   * @param handler to dispatch calls to.
   * @return a new instance.
   */
  public static <ReqT, RespT> ServerMethodDefinition<ReqT, RespT> create(
      MethodDescriptor<ReqT, RespT> method, ServerCallHandler<ReqT, RespT> handler) {
    return new ServerMethodDefinition<>(
        method, handler, Collections.<ServerStreamTracer.Factory>emptyList());
  }

  /** The {@code MethodDescriptor} for this method. */
  public MethodDescriptor<ReqT, RespT> getMethodDescriptor() {
    return method;
  }

  /** Handler for incoming calls. */
  public ServerCallHandler<ReqT, RespT> getServerCallHandler() {
    return handler;
  }

  /**
   * Create a new method definition with a different call handler.
   *
   * @param handler to bind to a cloned instance of this.
   * @return a cloned instance of this with the new handler bound.
   */
  public ServerMethodDefinition<ReqT, RespT> withServerCallHandler(
      ServerCallHandler<ReqT, RespT> handler) {
    return new ServerMethodDefinition<>(method, handler, tracerFactories);
  }

  /**
   * Create a new method definition with a different list of tracer factories.
   *
   * @param tracerFactories to bind to a cloned instance of this
   * @return a cloned instance of this with an unmodifiable view of the new tracer factories
   * @since 1.36.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/????")
  public ServerMethodDefinition<ReqT, RespT> withStreamTracerFactories(
      List<ServerStreamTracer.Factory> tracerFactories) {
    return new ServerMethodDefinition<>(method, handler, tracerFactories);
  }

  /**
   * Returns the stream tracer factories bound to this definition.
   *
   * @since 1.36.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/????")
  public List<ServerStreamTracer.Factory> getStreamTracerFactories() {
    return tracerFactories;
  }
}
