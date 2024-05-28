package io.grpc.protobuf.services.util;


import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.reflection.v1.ServerReflectionRequest;
import io.grpc.reflection.v1.ServerReflectionResponse;

public class ReflectionServiceProtoAdapter {
  public static ServerReflectionRequest toV1Request(
      io.grpc.reflection.v1alpha.ServerReflectionRequest serverReflectionRequest)
      throws InvalidProtocolBufferException {
    return ServerReflectionRequest.parseFrom(serverReflectionRequest.toByteArray());
  }

  public static io.grpc.reflection.v1alpha.ServerReflectionResponse toV1AlphaResponse(
      ServerReflectionResponse serverReflectionResponse) throws InvalidProtocolBufferException {
    return io.grpc.reflection.v1alpha.ServerReflectionResponse.parseFrom(serverReflectionResponse.toByteArray());
  }
}
