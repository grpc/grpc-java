package io.grpc.reflection.testing;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * AnotherDynamicService
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: io/grpc/reflection/testing/dynamic_reflection_test.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class AnotherDynamicServiceGrpc {

  private AnotherDynamicServiceGrpc() {}

  public static final String SERVICE_NAME = "grpc.reflection.testing.AnotherDynamicService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.reflection.testing.DynamicRequest,
      io.grpc.reflection.testing.DynamicReply> getMethodMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Method",
      requestType = io.grpc.reflection.testing.DynamicRequest.class,
      responseType = io.grpc.reflection.testing.DynamicReply.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.reflection.testing.DynamicRequest,
      io.grpc.reflection.testing.DynamicReply> getMethodMethod() {
    io.grpc.MethodDescriptor<io.grpc.reflection.testing.DynamicRequest, io.grpc.reflection.testing.DynamicReply> getMethodMethod;
    if ((getMethodMethod = AnotherDynamicServiceGrpc.getMethodMethod) == null) {
      synchronized (AnotherDynamicServiceGrpc.class) {
        if ((getMethodMethod = AnotherDynamicServiceGrpc.getMethodMethod) == null) {
          AnotherDynamicServiceGrpc.getMethodMethod = getMethodMethod =
              io.grpc.MethodDescriptor.<io.grpc.reflection.testing.DynamicRequest, io.grpc.reflection.testing.DynamicReply>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Method"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.reflection.testing.DynamicRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.reflection.testing.DynamicReply.getDefaultInstance()))
              .setSchemaDescriptor(new AnotherDynamicServiceMethodDescriptorSupplier("Method"))
              .build();
        }
      }
    }
    return getMethodMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static AnotherDynamicServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AnotherDynamicServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AnotherDynamicServiceStub>() {
        @java.lang.Override
        public AnotherDynamicServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AnotherDynamicServiceStub(channel, callOptions);
        }
      };
    return AnotherDynamicServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static AnotherDynamicServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AnotherDynamicServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AnotherDynamicServiceBlockingStub>() {
        @java.lang.Override
        public AnotherDynamicServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AnotherDynamicServiceBlockingStub(channel, callOptions);
        }
      };
    return AnotherDynamicServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static AnotherDynamicServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AnotherDynamicServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AnotherDynamicServiceFutureStub>() {
        @java.lang.Override
        public AnotherDynamicServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AnotherDynamicServiceFutureStub(channel, callOptions);
        }
      };
    return AnotherDynamicServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * AnotherDynamicService
   * </pre>
   */
  public interface AnotherDynamicServiceAsync {

    /**
     * <pre>
     * A method
     * </pre>
     */
    default void method(io.grpc.reflection.testing.DynamicRequest request,
        io.grpc.stub.StreamObserver<io.grpc.reflection.testing.DynamicReply> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getMethodMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service AnotherDynamicService
   * <pre>
   * AnotherDynamicService
   * </pre>
   */
  public static abstract class AnotherDynamicServiceImplBase
   implements io.grpc.BindableService, AnotherDynamicServiceAsync {

    /**
     * <pre>
     * A method
     * </pre>
     */
    @java.lang.Override
    public void method(io.grpc.reflection.testing.DynamicRequest request,
        io.grpc.stub.StreamObserver<io.grpc.reflection.testing.DynamicReply> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getMethodMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getMethodMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.grpc.reflection.testing.DynamicRequest,
                io.grpc.reflection.testing.DynamicReply>(
                  this, METHODID_METHOD)))
          .build();
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service AnotherDynamicService
   * <pre>
   * AnotherDynamicService
   * </pre>
   */
  public static final class AnotherDynamicServiceStub
   extends io.grpc.stub.AbstractAsyncStub<AnotherDynamicServiceStub>
   implements AnotherDynamicServiceAsync {
    private AnotherDynamicServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AnotherDynamicServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AnotherDynamicServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * A method
     * </pre>
     */
    @java.lang.Override
    public void method(io.grpc.reflection.testing.DynamicRequest request,
        io.grpc.stub.StreamObserver<io.grpc.reflection.testing.DynamicReply> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getMethodMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * <pre>
   * AnotherDynamicService
   * </pre>
   */
  public interface AnotherDynamicServiceBlocking {

    /**
     * <pre>
     * A method
     * </pre>
     */
    default io.grpc.reflection.testing.DynamicReply method(io.grpc.reflection.testing.DynamicRequest request) {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service AnotherDynamicService
   * <pre>
   * AnotherDynamicService
   * </pre>
   */
  public static final class AnotherDynamicServiceBlockingStub
   extends io.grpc.stub.AbstractBlockingStub<AnotherDynamicServiceBlockingStub>
   implements AnotherDynamicServiceBlocking {
    private AnotherDynamicServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AnotherDynamicServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AnotherDynamicServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * A method
     * </pre>
     */
    @java.lang.Override
    public io.grpc.reflection.testing.DynamicReply method(io.grpc.reflection.testing.DynamicRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getMethodMethod(), getCallOptions(), request);
    }
  }

  /**
   * <pre>
   * AnotherDynamicService
   * </pre>
   */
  public interface AnotherDynamicServiceFuture {

    /**
     * <pre>
     * A method
     * </pre>
     */
    default com.google.common.util.concurrent.ListenableFuture<io.grpc.reflection.testing.DynamicReply> method(
        io.grpc.reflection.testing.DynamicRequest request) {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service AnotherDynamicService
   * <pre>
   * AnotherDynamicService
   * </pre>
   */
  public static final class AnotherDynamicServiceFutureStub
   extends io.grpc.stub.AbstractFutureStub<AnotherDynamicServiceFutureStub>
   implements AnotherDynamicServiceFuture {
    private AnotherDynamicServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AnotherDynamicServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AnotherDynamicServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * A method
     * </pre>
     */
    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.reflection.testing.DynamicReply> method(
        io.grpc.reflection.testing.DynamicRequest request) {
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
    private final AnotherDynamicServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(AnotherDynamicServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_METHOD:
          serviceImpl.method((io.grpc.reflection.testing.DynamicRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.reflection.testing.DynamicReply>) responseObserver);
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

  private static abstract class AnotherDynamicServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    AnotherDynamicServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.grpc.reflection.testing.DynamicReflectionTestProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("AnotherDynamicService");
    }
  }

  private static final class AnotherDynamicServiceFileDescriptorSupplier
      extends AnotherDynamicServiceBaseDescriptorSupplier {
    AnotherDynamicServiceFileDescriptorSupplier() {}
  }

  private static final class AnotherDynamicServiceMethodDescriptorSupplier
      extends AnotherDynamicServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    AnotherDynamicServiceMethodDescriptorSupplier(String methodName) {
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
      synchronized (AnotherDynamicServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new AnotherDynamicServiceFileDescriptorSupplier())
              .addMethod(getMethodMethod())
              .build();
        }
      }
    }
    return result;
  }
}
