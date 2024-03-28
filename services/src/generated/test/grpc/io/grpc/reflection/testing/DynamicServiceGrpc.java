package io.grpc.reflection.testing;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * A DynamicService
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: io/grpc/reflection/testing/dynamic_reflection_test.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class DynamicServiceGrpc {

  private DynamicServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "grpc.reflection.testing.DynamicService";

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
    if ((getMethodMethod = DynamicServiceGrpc.getMethodMethod) == null) {
      synchronized (DynamicServiceGrpc.class) {
        if ((getMethodMethod = DynamicServiceGrpc.getMethodMethod) == null) {
          DynamicServiceGrpc.getMethodMethod = getMethodMethod =
              io.grpc.MethodDescriptor.<io.grpc.reflection.testing.DynamicRequest, io.grpc.reflection.testing.DynamicReply>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Method"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.reflection.testing.DynamicRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.reflection.testing.DynamicReply.getDefaultInstance()))
              .setSchemaDescriptor(new DynamicServiceMethodDescriptorSupplier("Method"))
              .build();
        }
      }
    }
    return getMethodMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static DynamicServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<DynamicServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<DynamicServiceStub>() {
        @java.lang.Override
        public DynamicServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new DynamicServiceStub(channel, callOptions);
        }
      };
    return DynamicServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static DynamicServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<DynamicServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<DynamicServiceBlockingStub>() {
        @java.lang.Override
        public DynamicServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new DynamicServiceBlockingStub(channel, callOptions);
        }
      };
    return DynamicServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static DynamicServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<DynamicServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<DynamicServiceFutureStub>() {
        @java.lang.Override
        public DynamicServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new DynamicServiceFutureStub(channel, callOptions);
        }
      };
    return DynamicServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * A DynamicService
   * </pre>
   */
  public interface AsyncService {

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
   * Base class for the server implementation of the service DynamicService.
   * <pre>
   * A DynamicService
   * </pre>
   */
  public static abstract class DynamicServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return DynamicServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service DynamicService.
   * <pre>
   * A DynamicService
   * </pre>
   */
  public static final class DynamicServiceStub
      extends io.grpc.stub.AbstractAsyncStub<DynamicServiceStub> {
    private DynamicServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected DynamicServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new DynamicServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * A method
     * </pre>
     */
    public void method(io.grpc.reflection.testing.DynamicRequest request,
        io.grpc.stub.StreamObserver<io.grpc.reflection.testing.DynamicReply> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getMethodMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service DynamicService.
   * <pre>
   * A DynamicService
   * </pre>
   */
  public static final class DynamicServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<DynamicServiceBlockingStub> {
    private DynamicServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected DynamicServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new DynamicServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * A method
     * </pre>
     */
    public io.grpc.reflection.testing.DynamicReply method(io.grpc.reflection.testing.DynamicRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getMethodMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service DynamicService.
   * <pre>
   * A DynamicService
   * </pre>
   */
  public static final class DynamicServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<DynamicServiceFutureStub> {
    private DynamicServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected DynamicServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new DynamicServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * A method
     * </pre>
     */
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

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getMethodMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.reflection.testing.DynamicRequest,
              io.grpc.reflection.testing.DynamicReply>(
                service, METHODID_METHOD)))
        .build();
  }

  private static abstract class DynamicServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    DynamicServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.grpc.reflection.testing.DynamicReflectionTestProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("DynamicService");
    }
  }

  private static final class DynamicServiceFileDescriptorSupplier
      extends DynamicServiceBaseDescriptorSupplier {
    DynamicServiceFileDescriptorSupplier() {}
  }

  private static final class DynamicServiceMethodDescriptorSupplier
      extends DynamicServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    DynamicServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (DynamicServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new DynamicServiceFileDescriptorSupplier())
              .addMethod(getMethodMethod())
              .build();
        }
      }
    }
    return result;
  }
}
