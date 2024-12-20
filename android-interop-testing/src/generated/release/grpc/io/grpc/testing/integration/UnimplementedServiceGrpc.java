package io.grpc.testing.integration;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * A simple service NOT implemented at servers so clients can test for
 * that case.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: grpc/testing/test.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class UnimplementedServiceGrpc {

  private UnimplementedServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "grpc.testing.UnimplementedService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.testing.integration.EmptyProtos.Empty,
      io.grpc.testing.integration.EmptyProtos.Empty> getUnimplementedCallMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UnimplementedCall",
      requestType = io.grpc.testing.integration.EmptyProtos.Empty.class,
      responseType = io.grpc.testing.integration.EmptyProtos.Empty.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.testing.integration.EmptyProtos.Empty,
      io.grpc.testing.integration.EmptyProtos.Empty> getUnimplementedCallMethod() {
    io.grpc.MethodDescriptor<io.grpc.testing.integration.EmptyProtos.Empty, io.grpc.testing.integration.EmptyProtos.Empty> getUnimplementedCallMethod;
    if ((getUnimplementedCallMethod = UnimplementedServiceGrpc.getUnimplementedCallMethod) == null) {
      synchronized (UnimplementedServiceGrpc.class) {
        if ((getUnimplementedCallMethod = UnimplementedServiceGrpc.getUnimplementedCallMethod) == null) {
          UnimplementedServiceGrpc.getUnimplementedCallMethod = getUnimplementedCallMethod =
              io.grpc.MethodDescriptor.<io.grpc.testing.integration.EmptyProtos.Empty, io.grpc.testing.integration.EmptyProtos.Empty>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UnimplementedCall"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  io.grpc.testing.integration.EmptyProtos.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  io.grpc.testing.integration.EmptyProtos.Empty.getDefaultInstance()))
              .build();
        }
      }
    }
    return getUnimplementedCallMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static UnimplementedServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<UnimplementedServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<UnimplementedServiceStub>() {
        @java.lang.Override
        public UnimplementedServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new UnimplementedServiceStub(channel, callOptions);
        }
      };
    return UnimplementedServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static UnimplementedServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<UnimplementedServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<UnimplementedServiceBlockingV2Stub>() {
        @java.lang.Override
        public UnimplementedServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new UnimplementedServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return UnimplementedServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static UnimplementedServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<UnimplementedServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<UnimplementedServiceBlockingStub>() {
        @java.lang.Override
        public UnimplementedServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new UnimplementedServiceBlockingStub(channel, callOptions);
        }
      };
    return UnimplementedServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static UnimplementedServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<UnimplementedServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<UnimplementedServiceFutureStub>() {
        @java.lang.Override
        public UnimplementedServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new UnimplementedServiceFutureStub(channel, callOptions);
        }
      };
    return UnimplementedServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * A simple service NOT implemented at servers so clients can test for
   * that case.
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * A call that no server should implement
     * </pre>
     */
    default void unimplementedCall(io.grpc.testing.integration.EmptyProtos.Empty request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.EmptyProtos.Empty> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUnimplementedCallMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service UnimplementedService.
   * <pre>
   * A simple service NOT implemented at servers so clients can test for
   * that case.
   * </pre>
   */
  public static abstract class UnimplementedServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return UnimplementedServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service UnimplementedService.
   * <pre>
   * A simple service NOT implemented at servers so clients can test for
   * that case.
   * </pre>
   */
  public static final class UnimplementedServiceStub
      extends io.grpc.stub.AbstractAsyncStub<UnimplementedServiceStub> {
    private UnimplementedServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected UnimplementedServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new UnimplementedServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * A call that no server should implement
     * </pre>
     */
    public void unimplementedCall(io.grpc.testing.integration.EmptyProtos.Empty request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.EmptyProtos.Empty> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUnimplementedCallMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service UnimplementedService.
   * <pre>
   * A simple service NOT implemented at servers so clients can test for
   * that case.
   * </pre>
   */
  public static final class UnimplementedServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<UnimplementedServiceBlockingV2Stub> {
    private UnimplementedServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected UnimplementedServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new UnimplementedServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * A call that no server should implement
     * </pre>
     */
    public io.grpc.testing.integration.EmptyProtos.Empty unimplementedCall(io.grpc.testing.integration.EmptyProtos.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUnimplementedCallMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do llimited synchronous rpc calls to service UnimplementedService.
   * <pre>
   * A simple service NOT implemented at servers so clients can test for
   * that case.
   * </pre>
   */
  public static final class UnimplementedServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<UnimplementedServiceBlockingStub> {
    private UnimplementedServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected UnimplementedServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new UnimplementedServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * A call that no server should implement
     * </pre>
     */
    public io.grpc.testing.integration.EmptyProtos.Empty unimplementedCall(io.grpc.testing.integration.EmptyProtos.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUnimplementedCallMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service UnimplementedService.
   * <pre>
   * A simple service NOT implemented at servers so clients can test for
   * that case.
   * </pre>
   */
  public static final class UnimplementedServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<UnimplementedServiceFutureStub> {
    private UnimplementedServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected UnimplementedServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new UnimplementedServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * A call that no server should implement
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.testing.integration.EmptyProtos.Empty> unimplementedCall(
        io.grpc.testing.integration.EmptyProtos.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUnimplementedCallMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_UNIMPLEMENTED_CALL = 0;

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
        case METHODID_UNIMPLEMENTED_CALL:
          serviceImpl.unimplementedCall((io.grpc.testing.integration.EmptyProtos.Empty) request,
              (io.grpc.stub.StreamObserver<io.grpc.testing.integration.EmptyProtos.Empty>) responseObserver);
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
          getUnimplementedCallMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.testing.integration.EmptyProtos.Empty,
              io.grpc.testing.integration.EmptyProtos.Empty>(
                service, METHODID_UNIMPLEMENTED_CALL)))
        .build();
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (UnimplementedServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .addMethod(getUnimplementedCallMethod())
              .build();
        }
      }
    }
    return result;
  }
}
