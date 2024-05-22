package io.grpc.protobuf.services.util;


import io.grpc.reflection.v1.ServerReflectionRequest;
import io.grpc.reflection.v1.ServerReflectionResponse;

public class ReflectionServiceProtoAdapter {
  public static ServerReflectionRequest toV1Request(
      io.grpc.reflection.v1alpha.ServerReflectionRequest serverReflectionRequest) {
    return null;
  }

  public static io.grpc.reflection.v1alpha.ServerReflectionResponse toV1AlphaResponse(
      ServerReflectionResponse serverReflectionResponse) {
    return null;
  }
}
