package io.grpc.protobuf.services;

import io.grpc.BindableService;
import io.grpc.ExperimentalApi;
import io.grpc.protobuf.services.util.ReflectionServiceProtoAdapter;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import io.grpc.stub.StreamObserver;

/**
 * Provides a reflection service for Protobuf services (including the reflection service itself).
 * Uses the deprecated v1alpha proto. New users should use ProtoReflectionServiceV1 instead.
 *
 * <p>Separately tracks mutable and immutable services. Throws an exception if either group of
 * services contains multiple Protobuf files with declarations of the same service, method, type, or
 * extension.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/2222")
public class ProtoReflectionService extends ServerReflectionGrpc.ServerReflectionImplBase {
  private final ProtoReflectionServiceV1 protoReflectionServiceV1 = (ProtoReflectionServiceV1) ProtoReflectionServiceV1.newInstance();
  /**
   * Creates a instance of {@link ProtoReflectionServiceV1}.
   */
  public static BindableService newInstance() {
    return new ProtoReflectionService();
  }

  @Override
  public StreamObserver<ServerReflectionRequest> serverReflectionInfo(
      final StreamObserver<ServerReflectionResponse> responseObserver) {
    StreamObserver<io.grpc.reflection.v1.ServerReflectionRequest> v1RequestObserver = protoReflectionServiceV1.serverReflectionInfo(
        new StreamObserver<io.grpc.reflection.v1.ServerReflectionResponse>() {
          @Override
          public void onNext(
              io.grpc.reflection.v1.ServerReflectionResponse serverReflectionResponse) {
            responseObserver.onNext(
                ReflectionServiceProtoAdapter.toV1AlphaResponse(serverReflectionResponse));
          }

          @Override
          public void onError(Throwable t) {
            responseObserver.onError(t);
          }

          @Override
          public void onCompleted() {
            responseObserver.onCompleted();
          }
        });
    return new StreamObserver<ServerReflectionRequest>() {
      @Override
      public void onNext(ServerReflectionRequest serverReflectionRequest) {
        v1RequestObserver.onNext(ReflectionServiceProtoAdapter.toV1Request(serverReflectionRequest));
      }

      @Override
      public void onError(Throwable t) {
        v1RequestObserver.onError(t);
      }

      @Override
      public void onCompleted() {
        v1RequestObserver.onCompleted();
      }
    };
  }
}
