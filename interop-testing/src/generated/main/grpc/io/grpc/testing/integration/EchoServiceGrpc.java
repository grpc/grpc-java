package io.grpc.testing.integration;

import static io.grpc.stub.ClientCalls.asyncUnaryCall;
import static io.grpc.stub.ClientCalls.asyncServerStreamingCall;
import static io.grpc.stub.ClientCalls.asyncClientStreamingCall;
import static io.grpc.stub.ClientCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ClientCalls.blockingUnaryCall;
import static io.grpc.stub.ClientCalls.blockingServerStreamingCall;
import static io.grpc.stub.ClientCalls.futureUnaryCall;
import static io.grpc.MethodDescriptor.generateFullMethodName;
import static io.grpc.stub.ServerCalls.asyncUnaryCall;
import static io.grpc.stub.ServerCalls.asyncServerStreamingCall;
import static io.grpc.stub.ServerCalls.asyncClientStreamingCall;
import static io.grpc.stub.ServerCalls.asyncBidiStreamingCall;

@javax.annotation.Generated("by gRPC proto compiler")
public class EchoServiceGrpc {

  private EchoServiceGrpc() {}

  public static final String SERVICE_NAME = "grpc.testing.EchoService";

  // Static method descriptors that strictly reflect the proto.
  @io.grpc.ExperimentalApi
  public static final io.grpc.MethodDescriptor<io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest,
      io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse> METHOD_ECHO =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "grpc.testing.EchoService", "Echo"),
          io.grpc.protobuf.ProtoUtils.marshaller(io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse.getDefaultInstance()));

  public static EchoServiceStub newStub(io.grpc.Channel channel) {
    return new EchoServiceStub(channel);
  }

  public static EchoServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    return new EchoServiceBlockingStub(channel);
  }

  public static EchoServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    return new EchoServiceFutureStub(channel);
  }

  public static interface EchoService {

    public void echo(io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse> responseObserver);
  }

  public static interface EchoServiceBlockingClient {

    public io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse echo(io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest request);
  }

  public static interface EchoServiceFutureClient {

    public com.google.common.util.concurrent.ListenableFuture<io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse> echo(
        io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest request);
  }

  public static class EchoServiceStub extends io.grpc.stub.AbstractStub<EchoServiceStub>
      implements EchoService {
    private EchoServiceStub(io.grpc.Channel channel) {
      super(channel);
    }

    private EchoServiceStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EchoServiceStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new EchoServiceStub(channel, callOptions);
    }

    @java.lang.Override
    public void echo(io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_ECHO, getCallOptions()), request, responseObserver);
    }
  }

  public static class EchoServiceBlockingStub extends io.grpc.stub.AbstractStub<EchoServiceBlockingStub>
      implements EchoServiceBlockingClient {
    private EchoServiceBlockingStub(io.grpc.Channel channel) {
      super(channel);
    }

    private EchoServiceBlockingStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EchoServiceBlockingStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new EchoServiceBlockingStub(channel, callOptions);
    }

    @java.lang.Override
    public io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse echo(io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest request) {
      return blockingUnaryCall(
          getChannel().newCall(METHOD_ECHO, getCallOptions()), request);
    }
  }

  public static class EchoServiceFutureStub extends io.grpc.stub.AbstractStub<EchoServiceFutureStub>
      implements EchoServiceFutureClient {
    private EchoServiceFutureStub(io.grpc.Channel channel) {
      super(channel);
    }

    private EchoServiceFutureStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EchoServiceFutureStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new EchoServiceFutureStub(channel, callOptions);
    }

    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse> echo(
        io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_ECHO, getCallOptions()), request);
    }
  }

  public static io.grpc.ServerServiceDefinition bindService(
      final EchoService serviceImpl) {
    return io.grpc.ServerServiceDefinition.builder(SERVICE_NAME)
      .addMethod(
        METHOD_ECHO,
        asyncUnaryCall(
          new io.grpc.stub.ServerCalls.UnaryMethod<
              io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest,
              io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse>() {
            @java.lang.Override
            public void invoke(
                io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest request,
                io.grpc.stub.StreamObserver<io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse> responseObserver) {
              serviceImpl.echo(request, responseObserver);
            }
          })).build();
  }
}
