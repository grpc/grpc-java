/*
 * Copyright 2016, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.services;

import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.InvalidProtocolBufferException;

import io.grpc.ProtoServiceDescriptor;
import io.grpc.Status;
import io.grpc.reflection.v1alpha.ErrorResponse;
import io.grpc.reflection.v1alpha.ExtensionNumberResponse;
import io.grpc.reflection.v1alpha.ExtensionRequest;
import io.grpc.reflection.v1alpha.FileDescriptorResponse;
import io.grpc.reflection.v1alpha.ListServiceResponse;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.reflection.v1alpha.ServiceResponse;
import io.grpc.stub.StreamObserver;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/*
 * Server reflection implementation.
 */
public class ServerReflectionImpl extends ServerReflectionGrpc.ServerReflectionImplBase {
  private final Map<String, FileDescriptor> files = new HashMap<String, FileDescriptor>();

  /** Takes all the registered service. */
  public ServerReflectionImpl(Collection<ProtoServiceDescriptor> services) {
    for (ProtoServiceDescriptor service : services) {
      try {
        if (files.get(service.getFilename()) == null) {
          ByteString descriptorBytes = ByteString.copyFromUtf8(service.getFileDescriptor());
          files.put(service.getFilename(),
              new FileDescriptor(descriptorBytes, FileDescriptorProto.parseFrom(descriptorBytes)));
        }
      } catch (InvalidProtocolBufferException e) {
        // TODO(zsurocking): log the exception?
      }
    }
  }

  @Override
  public StreamObserver<ServerReflectionRequest> serverReflectionInfo(
      final StreamObserver<ServerReflectionResponse> responseObserver) {
    return new StreamObserver<ServerReflectionRequest>() {
      @Override
      public void onNext(ServerReflectionRequest request) {
        switch (request.getMessageRequestCase()) {
          case FILE_BY_FILENAME:
            getFileByName(request);
            break;
          case FILE_CONTAINING_SYMBOL:
            getFileBySymbol(request);
            break;
          case FILE_CONTAINING_EXTENSION:
            getFileByExtension(request);
            break;
          case ALL_EXTENSION_NUMBERS_OF_TYPE:
            getAllExtensions(request);
            break;
          case LIST_SERVICES:
            listServices(request);
            break;
          default:
            sendErrorResponse(Status.INVALID_ARGUMENT,
                "invalid MessageRequest: " + request.getMessageRequestCase());
        }
      }

      @Override
      public void onError(Throwable t) {
      }

      @Override
      public void onCompleted() {
        responseObserver.onCompleted();
      }

      private void getFileByName(ServerReflectionRequest request) {
        String name = request.getFileByFilename();
        FileDescriptor file = files.get(name);
        if (file != null) {
          responseObserver.onNext(createServerReflectionResponse(request, file.bytes));
        } else {
          sendErrorResponse(request, Status.NOT_FOUND, "unknown filename: " + name);
        }
      }

      private void getFileBySymbol(ServerReflectionRequest request) {
        String symbol = request.getFileContainingSymbol();
        // TODO(zsurocking): verify whether all the getName() calls below return full name.
        // If not, construct the full name ourselves.
        for (FileDescriptor file : files.values()) {
          // Check all messages.
          // TODO(zsurocking): check nested messages.
          for (DescriptorProto message : file.proto.getMessageTypeList()) {
            if (message.getName().equals(symbol))  {
              createServerReflectionResponse(request, file.bytes);
              return;
            }
          }

          // Check all services and methods.
          for (ServiceDescriptorProto service : file.proto.getServiceList()) {
            if (service.getName().equals(symbol)) {
              createServerReflectionResponse(request, file.bytes);
              return;
            }
            for (MethodDescriptorProto method: service.getMethodList()) {
              if (method.getName().equals(symbol)) {
                createServerReflectionResponse(request, file.bytes);
                return;
              }
            }
          }
        }

        sendErrorResponse(request, Status.NOT_FOUND, "unknown symbol: " + symbol);
      }

      private void getFileByExtension(ServerReflectionRequest request) {
        ExtensionRequest ext = request.getFileContainingExtension();
        // TODO(zsurocking): verify whether field.getExtendee() returns full name.
        for (FileDescriptor file : files.values()) {
          for (FieldDescriptorProto field : file.proto.getExtensionList()) {
            if (field.getExtendee().equals(ext.getContainingType())
                && field.getNumber() == ext.getExtensionNumber()) {
              createServerReflectionResponse(request, file.bytes);
              return;
            }
          }
        }
        sendErrorResponse(request, Status.NOT_FOUND,
            "unknown type name: " + ext.getContainingType() + " ,or extension number: "
            + ext.getExtensionNumber());
      }

      private void getAllExtensions(ServerReflectionRequest request) {
        String type = request.getAllExtensionNumbersOfType();
        ExtensionNumberResponse.Builder builder =
            ExtensionNumberResponse.newBuilder().setBaseTypeName(type);
        // TODO(zsurocking): verify whether field.getExtendee() returns full name.
        for (FileDescriptor file : files.values()) {
          for (FieldDescriptorProto field : file.proto.getExtensionList()) {
            if (field.getExtendee().equals(type)) {
              builder.addExtensionNumber(field.getNumber());
            }
          }
        }
        responseObserver.onNext(
            ServerReflectionResponse.newBuilder()
                .setValidHost(request.getHost())
                .setOriginalRequest(request)
                .setAllExtensionNumbersResponse(builder)
                .build());
      }

      private void listServices(ServerReflectionRequest request) {
        // Somehow the string field list_service is not used.
        ListServiceResponse.Builder builder = ListServiceResponse.newBuilder();
        for (FileDescriptor file : files.values()) {
          for (ServiceDescriptorProto service : file.proto.getServiceList()) {
            builder.addService(ServiceResponse.newBuilder().setName(service.getName()));
          }
        }
        responseObserver.onNext(
            ServerReflectionResponse.newBuilder()
                .setValidHost(request.getHost())
                .setOriginalRequest(request)
                .setListServicesResponse(builder)
                .build());
      }

      private void sendErrorResponse(ServerReflectionRequest request, Status status,
          String message) {
        ServerReflectionResponse response = ServerReflectionResponse.newBuilder()
            .setValidHost(request.getHost())
            .setOriginalRequest(request)
            .setErrorResponse(
                ErrorResponse.newBuilder()
                    .setErrorCode(status.getCode().value())
                    .setErrorMessage(message))
            .build();
        responseObserver.onNext(response);
      }

      private ServerReflectionResponse createServerReflectionResponse(
          ServerReflectionRequest request, ByteString name) {
        return ServerReflectionResponse.newBuilder()
            .setValidHost(request.getHost())
            .setOriginalRequest(request)
            .setFileDescriptorResponse(FileDescriptorResponse.newBuilder()
                .addFileDescriptorProto(name))
            .build();
      }
    };
  }

  private class FileDescriptor {
    ByteString bytes;
    FileDescriptorProto proto;

    FileDescriptor(ByteString bytes, FileDescriptorProto proto) {
      this.bytes = Preconditions.checkNotNull(bytes, "bytes");
      this.proto = Preconditions.checkNotNull(proto, "proto");
    }
  }
}
