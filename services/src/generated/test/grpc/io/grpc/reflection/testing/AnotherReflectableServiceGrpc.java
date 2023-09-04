package io.grpc.reflection.testing;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: io/grpc/reflection/testing/reflection_test.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class AnotherReflectableServiceGrpc {

  private AnotherReflectableServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "grpc.reflection.testing.AnotherReflectableService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.reflection.testing.Request,
      io.grpc.reflection.testing.Reply> getMethodMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Method",
      requestType = io.grpc.reflection.testing.Request.class,
      responseType = io.grpc.reflection.testing.Reply.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.reflection.testing.Request,
      io.grpc.reflection.testing.Reply> getMethodMethod() {
    io.grpc.MethodDescriptor<io.grpc.reflection.testing.Request, io.grpc.reflection.testing.Reply> getMethodMethod;
    if ((getMethodMethod = AnotherReflectableServiceGrpc.getMethodMethod) == null) {
      synchronized (AnotherReflectableServiceGrpc.class) {
        if ((getMethodMethod = AnotherReflectableServiceGrpc.getMethodMethod) == null) {
          AnotherReflectableServiceGrpc.getMethodMethod = getMethodMethod =
              io.grpc.MethodDescriptor.<io.grpc.reflection.testing.Request, io.grpc.reflection.testing.Reply>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Method"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.reflection.testing.Request.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.reflection.testing.Reply.getDefaultInstance()))
              .setSchemaDescriptor(new AnotherReflectableServiceMethodDescriptorSupplier("Method"))
              .build();
        }
      }
    }
    return getMethodMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static AnotherReflectableServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AnotherReflectableServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AnotherReflectableServiceStub>() {
        @java.lang.Override
        public AnotherReflectableServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AnotherReflectableServiceStub(channel, callOptions);
        }
      };
    return AnotherReflectableServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static AnotherReflectableServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AnotherReflectableServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AnotherReflectableServiceBlockingStub>() {
        @java.lang.Override
        public AnotherReflectableServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AnotherReflectableServiceBlockingStub(channel, callOptions);
        }
      };
    return AnotherReflectableServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static AnotherReflectableServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AnotherReflectableServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AnotherReflectableServiceFutureStub>() {
        @java.lang.Override
        public AnotherReflectableServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AnotherReflectableServiceFutureStub(channel, callOptions);
        }
      };
    return AnotherReflectableServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void method(io.grpc.reflection.testing.Request request,
        io.grpc.stub.StreamObserver<io.grpc.reflection.testing.Reply> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getMethodMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service AnotherReflectableService.
   */
  public static abstract class AnotherReflectableServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return AnotherReflectableServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service AnotherReflectableService.
   */
  public static final class AnotherReflectableServiceStub
      extends io.grpc.stub.AbstractAsyncStub<AnotherReflectableServiceStub> {
    private AnotherReflectableServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AnotherReflectableServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AnotherReflectableServiceStub(channel, callOptions);
    }

    /**
     */
    public void method(io.grpc.reflection.testing.Request request,
        io.grpc.stub.StreamObserver<io.grpc.reflection.testing.Reply> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getMethodMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service AnotherReflectableService.
   */
  public static final class AnotherReflectableServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<AnotherReflectableServiceBlockingStub> {
    private AnotherReflectableServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AnotherReflectableServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AnotherReflectableServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public io.grpc.reflection.testing.Reply method(io.grpc.reflection.testing.Request request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getMethodMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service AnotherReflectableService.
   */
  public static final class AnotherReflectableServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<AnotherReflectableServiceFutureStub> {
    private AnotherReflectableServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AnotherReflectableServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AnotherReflectableServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.reflection.testing.Reply> method(
        io.grpc.reflection.testing.Request request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getMethodMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_METHOD = 0;

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
        case METHODID_METHOD:
          serviceImpl.method((io.grpc.reflection.testing.Request) request,
              (io.grpc.stub.StreamObserver<io.grpc.reflection.testing.Reply>) responseObserver);
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
          getMethodMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.reflection.testing.Request,
              io.grpc.reflection.testing.Reply>(
                service, METHODID_METHOD)))
        .build();
  }

  private static abstract class AnotherReflectableServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    AnotherReflectableServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.grpc.reflection.testing.ReflectionTestProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("AnotherReflectableService");
    }
  }

  private static final class AnotherReflectableServiceFileDescriptorSupplier
      extends AnotherReflectableServiceBaseDescriptorSupplier {
    AnotherReflectableServiceFileDescriptorSupplier() {}
  }

  private static final class AnotherReflectableServiceMethodDescriptorSupplier
      extends AnotherReflectableServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    AnotherReflectableServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (AnotherReflectableServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new AnotherReflectableServiceFileDescriptorSupplier())
              .addMethod(getMethodMethod())
              .build();
        }
      }
    }
    return result;
  }
}
