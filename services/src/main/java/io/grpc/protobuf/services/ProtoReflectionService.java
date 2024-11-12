/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc.protobuf.services;

import io.grpc.BindableService;
import io.grpc.ExperimentalApi;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.reflection.v1.ServerReflectionGrpc;
import io.grpc.reflection.v1.ServerReflectionRequest;
import io.grpc.reflection.v1.ServerReflectionResponse;

/**
 * Provides a reflection service for Protobuf services (including the reflection service itself).
 *
 * <p>Separately tracks mutable and immutable services. Throws an exception if either group of
 * services contains multiple Protobuf files with declarations of the same service, method, type, or
 * extension.
 * Uses the deprecated v1alpha proto. New users should use {@link ProtoReflectionServiceV1} instead.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/2222")
public final class ProtoReflectionService implements BindableService {

  private ProtoReflectionService() {
  }

  @Deprecated
  public static BindableService newInstance() {
    return new ProtoReflectionService();
  }

  @Override
  @SuppressWarnings("deprecation")
  public ServerServiceDefinition bindService() {
    ServerServiceDefinition serverServiceDefinitionV1 = ProtoReflectionServiceV1.newInstance()
        .bindService();
    MethodDescriptor<ServerReflectionRequest, ServerReflectionResponse> methodDescriptorV1 =
        ServerReflectionGrpc.getServerReflectionInfoMethod();
    // Retain the v1 proto marshallers but change the method name and schema descriptor to v1alpha.
    MethodDescriptor<io.grpc.reflection.v1alpha.ServerReflectionRequest,
        io.grpc.reflection.v1alpha.ServerReflectionResponse> methodDescriptorV1AlphaGenerated =
        io.grpc.reflection.v1alpha.ServerReflectionGrpc.getServerReflectionInfoMethod();
    MethodDescriptor<ServerReflectionRequest, ServerReflectionResponse> methodDescriptorV1Alpha =
        methodDescriptorV1.toBuilder()
            .setFullMethodName(methodDescriptorV1AlphaGenerated.getFullMethodName())
            .setSchemaDescriptor(methodDescriptorV1AlphaGenerated.getSchemaDescriptor())
            .build();
    // Retain the v1 server call handler but change the service name schema descriptor in the
    // service descriptor to v1alpha.
    ServiceDescriptor serviceDescriptorV1AlphaGenerated =
        io.grpc.reflection.v1alpha.ServerReflectionGrpc.getServiceDescriptor();
    ServiceDescriptor serviceDescriptorV1Alpha =
        ServiceDescriptor.newBuilder(serviceDescriptorV1AlphaGenerated.getName())
            .setSchemaDescriptor(serviceDescriptorV1AlphaGenerated.getSchemaDescriptor())
            .addMethod(methodDescriptorV1Alpha)
            .build();
    return ServerServiceDefinition.builder(serviceDescriptorV1Alpha)
        .addMethod(methodDescriptorV1Alpha, createServerCallHandler(serverServiceDefinitionV1))
        .build();
  }

  @SuppressWarnings("unchecked")
  private ServerCallHandler<ServerReflectionRequest, ServerReflectionResponse>
      createServerCallHandler(
      ServerServiceDefinition serverServiceDefinition) {
    return (ServerCallHandler<ServerReflectionRequest, ServerReflectionResponse>)
        serverServiceDefinition.getMethod(
            ServerReflectionGrpc.getServerReflectionInfoMethod().getFullMethodName())
        .getServerCallHandler();
  }
}
