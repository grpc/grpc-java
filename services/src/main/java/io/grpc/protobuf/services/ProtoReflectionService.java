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

import static io.grpc.MethodDescriptor.create;
import static io.grpc.MethodDescriptor.generateFullMethodName;
import static io.grpc.reflection.v1alpha.ServerReflectionGrpc.SERVICE_NAME;

import io.grpc.BindableService;
import io.grpc.ExperimentalApi;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCallHandler;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.reflection.v1.ServerReflectionGrpc;

/**
 * Provides a reflection service for Protobuf services (including the reflection service itself).
 * Uses the deprecated v1alpha proto. New users should use ProtoReflectionServiceV1 instead.
 *
 * <p>Separately tracks mutable and immutable services. Throws an exception if either group of
 * services contains multiple Protobuf files with declarations of the same service, method, type, or
 * extension.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/2222")
public final class ProtoReflectionService implements BindableService {

  private static final String METHOD_NAME = "ServerReflectionInfo";

  private ProtoReflectionService() {}

  public static BindableService newInstance() {
    return new ProtoReflectionService();
  }

  @Override
  public ServerServiceDefinition bindService() {
    return createServerServiceDefinition();
  }

  private <ReqT, RespT> ServerServiceDefinition createServerServiceDefinition() {
    ServerServiceDefinition serverServiceDefinitionV1 = ProtoReflectionServiceV1.newInstance()
        .bindService();
    MethodDescriptor<ReqT, RespT> methodDescriptorV1 = (MethodDescriptor<ReqT, RespT>) serverServiceDefinitionV1.getServiceDescriptor()
        .getMethods().iterator().next();
    MethodDescriptor<ReqT, RespT> methodDescriptorV1Alpha = methodDescriptorV1.toBuilder()
        .setFullMethodName(generateFullMethodName(SERVICE_NAME, METHOD_NAME))
        .setSchemaDescriptor(
            new ServerReflectionMethodDescriptorSupplier(METHOD_NAME))
        .build();
    ServiceDescriptor serviceDescriptorV1Alpha = ServiceDescriptor.newBuilder(SERVICE_NAME)
        .setSchemaDescriptor(new ServerReflectionFileDescriptorSupplier())
        .addMethod(methodDescriptorV1Alpha)
        .build();
    return ServerServiceDefinition.builder(serviceDescriptorV1Alpha)
        .addMethod(methodDescriptorV1Alpha, createServerCallHandler(serverServiceDefinitionV1))
        .build();
  }

  private ServerCallHandler createServerCallHandler(ServerServiceDefinition serverServiceDefinition) {
    return serverServiceDefinition.getMethod(
            generateFullMethodName(ServerReflectionGrpc.SERVICE_NAME, METHOD_NAME))
        .getServerCallHandler();
  }

  private static abstract class ServerReflectionBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerReflectionBaseDescriptorSupplier() {}

    @Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.grpc.reflection.v1.ServerReflectionProto.getDescriptor();
    }

    @Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerReflection");
    }
  }

  private static final class ServerReflectionFileDescriptorSupplier
      extends ServerReflectionBaseDescriptorSupplier {
    ServerReflectionFileDescriptorSupplier() {}
  }

  private static final class ServerReflectionMethodDescriptorSupplier
      extends ServerReflectionBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerReflectionMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }
}
