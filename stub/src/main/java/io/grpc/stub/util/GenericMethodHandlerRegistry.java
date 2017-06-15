/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

package io.grpc.stub.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.BindableService;
import io.grpc.ExperimentalApi;
import io.grpc.HandlerRegistry;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.ServerCalls.BidiStreamingMethod;
import io.grpc.stub.ServerCalls.ClientStreamingMethod;
import io.grpc.stub.ServerCalls.ServerStreamingMethod;
import io.grpc.stub.ServerCalls.UnaryMethod;
import io.grpc.util.MutableHandlerRegistry;
import java.util.List;
import javax.annotation.Nullable;

/**
 * An implementation of {@link HandlerRegistry} which can add generic methods or services.
 *
 * @since 1.5.0
 */
@ExperimentalApi() // TODO(zdapeng): tracking url
public class GenericMethodHandlerRegistry extends HandlerRegistry {

  private MutableHandlerRegistry delegate = new MutableHandlerRegistry();

  /**
   * Registers a service with a generic unary method.
   *
   * @return the previously registered service with the same service descriptor name if exists,
   *         otherwise {@code null}.
   */
  @Nullable
  public <ReqT, RespT> ServerServiceDefinition addUnaryMethod(
      MethodDescriptor<ReqT, RespT> descriptor, UnaryMethod<ReqT, RespT> method) {
    checkNotNull(descriptor, "descriptor");
    checkNotNull(method, "method");
    checkArgument(descriptor.getType() == MethodType.UNARY, "MethodType");
    ServerServiceDefinition svcDef = ServerServiceDefinition
        .builder(MethodDescriptor.extractFullServiceName(descriptor.getFullMethodName()))
        .addMethod(descriptor, ServerCalls.asyncUnaryCall(method))
        .build();
    return addService(svcDef);
  }

  /**
   * Registers a service with a generic server streaming method.
   *
   * @return the previously registered service with the same service descriptor name if exists,
   *         otherwise {@code null}.
   */
  @Nullable
  public <ReqT, RespT> ServerServiceDefinition addServerStreamingMethod(
      MethodDescriptor<ReqT, RespT> descriptor, ServerStreamingMethod<ReqT, RespT> method) {
    checkNotNull(descriptor, "descriptor");
    checkNotNull(method, "method");
    checkArgument(descriptor.getType() == MethodType.SERVER_STREAMING, "MethodType");
    ServerServiceDefinition svcDef = ServerServiceDefinition
        .builder(MethodDescriptor.extractFullServiceName(descriptor.getFullMethodName()))
        .addMethod(descriptor, ServerCalls.asyncServerStreamingCall(method))
        .build();
    return addService(svcDef);
  }

  /**
   * Registers a service with a generic client streaming method.
   *
   * @return the previously registered service with the same service descriptor name if exists,
   *         otherwise {@code null}.
   */
  @Nullable
  public <ReqT, RespT> ServerServiceDefinition addClientStreamingMethod(
      MethodDescriptor<ReqT, RespT> descriptor, ClientStreamingMethod<ReqT, RespT> method) {
    checkNotNull(descriptor, "descriptor");
    checkNotNull(method, "method");
    checkArgument(descriptor.getType() == MethodType.CLIENT_STREAMING, "MethodType");
    ServerServiceDefinition svcDef = ServerServiceDefinition
        .builder(MethodDescriptor.extractFullServiceName(descriptor.getFullMethodName()))
        .addMethod(descriptor, ServerCalls.asyncClientStreamingCall(method))
        .build();
    return addService(svcDef);
  }

  /**
   * Registers a service with a generic bidirectional streaming method.
   *
   * @return the previously registered service with the same service descriptor name if exists,
   *         otherwise {@code null}.
   */
  @Nullable
  public <ReqT, RespT> ServerServiceDefinition addBidiStreamingMethod(
      MethodDescriptor<ReqT, RespT> descriptor, BidiStreamingMethod<ReqT, RespT> method) {
    checkNotNull(descriptor, "descriptor");
    checkNotNull(method, "method");
    checkArgument(descriptor.getType() == MethodType.BIDI_STREAMING, "MethodType");
    ServerServiceDefinition svcDef = ServerServiceDefinition
        .builder(MethodDescriptor.extractFullServiceName(descriptor.getFullMethodName()))
        .addMethod(descriptor, ServerCalls.asyncBidiStreamingCall(method))
        .build();
    return addService(svcDef);
  }

  /**
   * Registers a service.
   *
   * @return the previously registered service with the same service descriptor name if exists,
   *         otherwise {@code null}.
   */
  @Nullable
  public final ServerServiceDefinition addService(ServerServiceDefinition service) {
    return delegate.addService(service);
  }

  /**
   * Registers a service.
   *
   * @return the previously registered service with the same service descriptor name if exists,
   *         otherwise {@code null}.
   */
  @Nullable
  public final ServerServiceDefinition addService(BindableService bindableService) {
    return delegate.addService(bindableService);
  }

  /**
   * Removes a registered service
   *
   * @return true if the service was found to be removed.
   */
  public final boolean removeService(ServerServiceDefinition service) {
    return delegate.removeService(service);
  }

  /**
   *  Note: This does not necessarily return a consistent view of the map.
   */
  @Override
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2222")
  public final List<ServerServiceDefinition> getServices() {
    return delegate.getServices();
  }

  /**
   * Note: This does not actually honor the authority provided.  It will, eventually in the future.
   */
  @Override
  @Nullable
  public final ServerMethodDefinition<?, ?> lookupMethod(
      String methodName, @Nullable String authority) {
    return delegate.lookupMethod(methodName, authority);
  }
}
