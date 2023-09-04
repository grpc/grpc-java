package io.istio.test;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: test/echo/proto/echo.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class EchoTestServiceGrpc {

  private EchoTestServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "proto.EchoTestService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.istio.test.Echo.EchoRequest,
      io.istio.test.Echo.EchoResponse> getEchoMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Echo",
      requestType = io.istio.test.Echo.EchoRequest.class,
      responseType = io.istio.test.Echo.EchoResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.istio.test.Echo.EchoRequest,
      io.istio.test.Echo.EchoResponse> getEchoMethod() {
    io.grpc.MethodDescriptor<io.istio.test.Echo.EchoRequest, io.istio.test.Echo.EchoResponse> getEchoMethod;
    if ((getEchoMethod = EchoTestServiceGrpc.getEchoMethod) == null) {
      synchronized (EchoTestServiceGrpc.class) {
        if ((getEchoMethod = EchoTestServiceGrpc.getEchoMethod) == null) {
          EchoTestServiceGrpc.getEchoMethod = getEchoMethod =
              io.grpc.MethodDescriptor.<io.istio.test.Echo.EchoRequest, io.istio.test.Echo.EchoResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Echo"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.istio.test.Echo.EchoRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.istio.test.Echo.EchoResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EchoTestServiceMethodDescriptorSupplier("Echo"))
              .build();
        }
      }
    }
    return getEchoMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.istio.test.Echo.ForwardEchoRequest,
      io.istio.test.Echo.ForwardEchoResponse> getForwardEchoMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ForwardEcho",
      requestType = io.istio.test.Echo.ForwardEchoRequest.class,
      responseType = io.istio.test.Echo.ForwardEchoResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.istio.test.Echo.ForwardEchoRequest,
      io.istio.test.Echo.ForwardEchoResponse> getForwardEchoMethod() {
    io.grpc.MethodDescriptor<io.istio.test.Echo.ForwardEchoRequest, io.istio.test.Echo.ForwardEchoResponse> getForwardEchoMethod;
    if ((getForwardEchoMethod = EchoTestServiceGrpc.getForwardEchoMethod) == null) {
      synchronized (EchoTestServiceGrpc.class) {
        if ((getForwardEchoMethod = EchoTestServiceGrpc.getForwardEchoMethod) == null) {
          EchoTestServiceGrpc.getForwardEchoMethod = getForwardEchoMethod =
              io.grpc.MethodDescriptor.<io.istio.test.Echo.ForwardEchoRequest, io.istio.test.Echo.ForwardEchoResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ForwardEcho"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.istio.test.Echo.ForwardEchoRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.istio.test.Echo.ForwardEchoResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EchoTestServiceMethodDescriptorSupplier("ForwardEcho"))
              .build();
        }
      }
    }
    return getForwardEchoMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static EchoTestServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EchoTestServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EchoTestServiceStub>() {
        @java.lang.Override
        public EchoTestServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EchoTestServiceStub(channel, callOptions);
        }
      };
    return EchoTestServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static EchoTestServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EchoTestServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EchoTestServiceBlockingStub>() {
        @java.lang.Override
        public EchoTestServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EchoTestServiceBlockingStub(channel, callOptions);
        }
      };
    return EchoTestServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static EchoTestServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EchoTestServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EchoTestServiceFutureStub>() {
        @java.lang.Override
        public EchoTestServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EchoTestServiceFutureStub(channel, callOptions);
        }
      };
    return EchoTestServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void echo(io.istio.test.Echo.EchoRequest request,
        io.grpc.stub.StreamObserver<io.istio.test.Echo.EchoResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getEchoMethod(), responseObserver);
    }

    /**
     */
    default void forwardEcho(io.istio.test.Echo.ForwardEchoRequest request,
        io.grpc.stub.StreamObserver<io.istio.test.Echo.ForwardEchoResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getForwardEchoMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service EchoTestService.
   */
  public static abstract class EchoTestServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return EchoTestServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service EchoTestService.
   */
  public static final class EchoTestServiceStub
      extends io.grpc.stub.AbstractAsyncStub<EchoTestServiceStub> {
    private EchoTestServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EchoTestServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EchoTestServiceStub(channel, callOptions);
    }

    /**
     */
    public void echo(io.istio.test.Echo.EchoRequest request,
        io.grpc.stub.StreamObserver<io.istio.test.Echo.EchoResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getEchoMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void forwardEcho(io.istio.test.Echo.ForwardEchoRequest request,
        io.grpc.stub.StreamObserver<io.istio.test.Echo.ForwardEchoResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getForwardEchoMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service EchoTestService.
   */
  public static final class EchoTestServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<EchoTestServiceBlockingStub> {
    private EchoTestServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EchoTestServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EchoTestServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public io.istio.test.Echo.EchoResponse echo(io.istio.test.Echo.EchoRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getEchoMethod(), getCallOptions(), request);
    }

    /**
     */
    public io.istio.test.Echo.ForwardEchoResponse forwardEcho(io.istio.test.Echo.ForwardEchoRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getForwardEchoMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service EchoTestService.
   */
  public static final class EchoTestServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<EchoTestServiceFutureStub> {
    private EchoTestServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EchoTestServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EchoTestServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.istio.test.Echo.EchoResponse> echo(
        io.istio.test.Echo.EchoRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getEchoMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.istio.test.Echo.ForwardEchoResponse> forwardEcho(
        io.istio.test.Echo.ForwardEchoRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getForwardEchoMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_ECHO = 0;
  private static final int METHODID_FORWARD_ECHO = 1;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_ECHO:
          serviceImpl.echo((io.istio.test.Echo.EchoRequest) request,
              (io.grpc.stub.StreamObserver<io.istio.test.Echo.EchoResponse>) responseObserver);
          break;
        case METHODID_FORWARD_ECHO:
          serviceImpl.forwardEcho((io.istio.test.Echo.ForwardEchoRequest) request,
              (io.grpc.stub.StreamObserver<io.istio.test.Echo.ForwardEchoResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getEchoMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.istio.test.Echo.EchoRequest,
              io.istio.test.Echo.EchoResponse>(
                service, METHODID_ECHO)))
        .addMethod(
          getForwardEchoMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.istio.test.Echo.ForwardEchoRequest,
              io.istio.test.Echo.ForwardEchoResponse>(
                service, METHODID_FORWARD_ECHO)))
        .build();
  }

  private static abstract class EchoTestServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    EchoTestServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.istio.test.Echo.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("EchoTestService");
    }
  }

  private static final class EchoTestServiceFileDescriptorSupplier
      extends EchoTestServiceBaseDescriptorSupplier {
    EchoTestServiceFileDescriptorSupplier() {}
  }

  private static final class EchoTestServiceMethodDescriptorSupplier
      extends EchoTestServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    EchoTestServiceMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (EchoTestServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new EchoTestServiceFileDescriptorSupplier())
              .addMethod(getEchoMethod())
              .addMethod(getForwardEchoMethod())
              .build();
        }
      }
    }
    return result;
  }
}
