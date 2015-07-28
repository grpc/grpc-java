package io.grpc.examples.helloworld;

import static io.grpc.stub.ClientCalls.asyncUnaryCall;
import static io.grpc.stub.ClientCalls.asyncServerStreamingCall;
import static io.grpc.stub.ClientCalls.asyncClientStreamingCall;
import static io.grpc.stub.ClientCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ClientCalls.blockingUnaryCall;
import static io.grpc.stub.ClientCalls.blockingServerStreamingCall;
import static io.grpc.stub.ClientCalls.futureUnaryCall;
import static io.grpc.stub.ServerCalls.asyncUnaryCall;
import static io.grpc.stub.ServerCalls.asyncServerStreamingCall;
import static io.grpc.stub.ServerCalls.asyncClientStreamingCall;
import static io.grpc.stub.ServerCalls.asyncBidiStreamingCall;

@javax.annotation.Generated("by gRPC proto compiler")
public class GreeterGrpc {

  // Static method descriptors that strictly reflect the proto.
  public static final io.grpc.MethodDescriptor<io.grpc.examples.helloworld.HelloRequest,
      io.grpc.examples.helloworld.HelloResponse> METHOD_SAY_HELLO =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          "helloworld.Greeter", "SayHello",
          io.grpc.protobuf.ProtoUtils.marshaller(io.grpc.examples.helloworld.HelloRequest.parser()),
          io.grpc.protobuf.ProtoUtils.marshaller(io.grpc.examples.helloworld.HelloResponse.parser()));

  public static GreeterStub newStub(io.grpc.ClientCallFactory callFactory) {
    return new GreeterStub(callFactory);
  }

  public static GreeterBlockingStub newBlockingStub(
      io.grpc.ClientCallFactory callFactory) {
    return new GreeterBlockingStub(callFactory);
  }

  public static GreeterFutureStub newFutureStub(
      io.grpc.ClientCallFactory callFactory) {
    return new GreeterFutureStub(callFactory);
  }

  public static interface Greeter {

    public void sayHello(io.grpc.examples.helloworld.HelloRequest request,
        io.grpc.stub.StreamObserver<io.grpc.examples.helloworld.HelloResponse> responseObserver);
  }

  public static interface GreeterBlockingClient {

    public io.grpc.examples.helloworld.HelloResponse sayHello(io.grpc.examples.helloworld.HelloRequest request);
  }

  public static interface GreeterFutureClient {

    public com.google.common.util.concurrent.ListenableFuture<io.grpc.examples.helloworld.HelloResponse> sayHello(
        io.grpc.examples.helloworld.HelloRequest request);
  }

  public static class GreeterStub extends io.grpc.stub.AbstractStub<GreeterStub>
      implements Greeter {
    private GreeterStub(io.grpc.ClientCallFactory callFactory) {
      super(callFactory);
    }

    private GreeterStub(io.grpc.ClientCallFactory callFactory,
        io.grpc.CallOptions callOptions) {
      super(callFactory, callOptions);
    }

    @java.lang.Override
    protected GreeterStub build(io.grpc.ClientCallFactory callFactory,
        io.grpc.CallOptions callOptions) {
      return new GreeterStub(callFactory, callOptions);
    }

    @java.lang.Override
    public void sayHello(io.grpc.examples.helloworld.HelloRequest request,
        io.grpc.stub.StreamObserver<io.grpc.examples.helloworld.HelloResponse> responseObserver) {
      asyncUnaryCall(
          callFactory.newCall(METHOD_SAY_HELLO, callOptions), request, responseObserver);
    }
  }

  public static class GreeterBlockingStub extends io.grpc.stub.AbstractStub<GreeterBlockingStub>
      implements GreeterBlockingClient {
    private GreeterBlockingStub(io.grpc.ClientCallFactory callFactory) {
      super(callFactory);
    }

    private GreeterBlockingStub(io.grpc.ClientCallFactory callFactory,
        io.grpc.CallOptions callOptions) {
      super(callFactory, callOptions);
    }

    @java.lang.Override
    protected GreeterBlockingStub build(io.grpc.ClientCallFactory callFactory,
        io.grpc.CallOptions callOptions) {
      return new GreeterBlockingStub(callFactory, callOptions);
    }

    @java.lang.Override
    public io.grpc.examples.helloworld.HelloResponse sayHello(io.grpc.examples.helloworld.HelloRequest request) {
      return blockingUnaryCall(
          callFactory.newCall(METHOD_SAY_HELLO, callOptions), request);
    }
  }

  public static class GreeterFutureStub extends io.grpc.stub.AbstractStub<GreeterFutureStub>
      implements GreeterFutureClient {
    private GreeterFutureStub(io.grpc.ClientCallFactory callFactory) {
      super(callFactory);
    }

    private GreeterFutureStub(io.grpc.ClientCallFactory callFactory,
        io.grpc.CallOptions callOptions) {
      super(callFactory, callOptions);
    }

    @java.lang.Override
    protected GreeterFutureStub build(io.grpc.ClientCallFactory callFactory,
        io.grpc.CallOptions callOptions) {
      return new GreeterFutureStub(callFactory, callOptions);
    }

    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.examples.helloworld.HelloResponse> sayHello(
        io.grpc.examples.helloworld.HelloRequest request) {
      return futureUnaryCall(
          callFactory.newCall(METHOD_SAY_HELLO, callOptions), request);
    }
  }

  public static io.grpc.ServerServiceDefinition bindService(
      final Greeter serviceImpl) {
    return io.grpc.ServerServiceDefinition.builder("helloworld.Greeter")
      .addMethod(io.grpc.ServerMethodDefinition.create(
          METHOD_SAY_HELLO,
          asyncUnaryCall(
            new io.grpc.stub.ServerCalls.UnaryMethod<
                io.grpc.examples.helloworld.HelloRequest,
                io.grpc.examples.helloworld.HelloResponse>() {
              @java.lang.Override
              public void invoke(
                  io.grpc.examples.helloworld.HelloRequest request,
                  io.grpc.stub.StreamObserver<io.grpc.examples.helloworld.HelloResponse> responseObserver) {
                serviceImpl.sayHello(request, responseObserver);
              }
            }))).build();
  }
}
