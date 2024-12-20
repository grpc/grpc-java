package io.grpc.testing.integration;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * A service used to control reconnect server.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: grpc/testing/test.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class ReconnectServiceGrpc {

  private ReconnectServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "grpc.testing.ReconnectService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.testing.integration.Messages.ReconnectParams,
      io.grpc.testing.integration.EmptyProtos.Empty> getStartMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Start",
      requestType = io.grpc.testing.integration.Messages.ReconnectParams.class,
      responseType = io.grpc.testing.integration.EmptyProtos.Empty.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.testing.integration.Messages.ReconnectParams,
      io.grpc.testing.integration.EmptyProtos.Empty> getStartMethod() {
    io.grpc.MethodDescriptor<io.grpc.testing.integration.Messages.ReconnectParams, io.grpc.testing.integration.EmptyProtos.Empty> getStartMethod;
    if ((getStartMethod = ReconnectServiceGrpc.getStartMethod) == null) {
      synchronized (ReconnectServiceGrpc.class) {
        if ((getStartMethod = ReconnectServiceGrpc.getStartMethod) == null) {
          ReconnectServiceGrpc.getStartMethod = getStartMethod =
              io.grpc.MethodDescriptor.<io.grpc.testing.integration.Messages.ReconnectParams, io.grpc.testing.integration.EmptyProtos.Empty>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Start"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  io.grpc.testing.integration.Messages.ReconnectParams.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  io.grpc.testing.integration.EmptyProtos.Empty.getDefaultInstance()))
              .build();
        }
      }
    }
    return getStartMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.testing.integration.EmptyProtos.Empty,
      io.grpc.testing.integration.Messages.ReconnectInfo> getStopMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Stop",
      requestType = io.grpc.testing.integration.EmptyProtos.Empty.class,
      responseType = io.grpc.testing.integration.Messages.ReconnectInfo.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.testing.integration.EmptyProtos.Empty,
      io.grpc.testing.integration.Messages.ReconnectInfo> getStopMethod() {
    io.grpc.MethodDescriptor<io.grpc.testing.integration.EmptyProtos.Empty, io.grpc.testing.integration.Messages.ReconnectInfo> getStopMethod;
    if ((getStopMethod = ReconnectServiceGrpc.getStopMethod) == null) {
      synchronized (ReconnectServiceGrpc.class) {
        if ((getStopMethod = ReconnectServiceGrpc.getStopMethod) == null) {
          ReconnectServiceGrpc.getStopMethod = getStopMethod =
              io.grpc.MethodDescriptor.<io.grpc.testing.integration.EmptyProtos.Empty, io.grpc.testing.integration.Messages.ReconnectInfo>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Stop"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  io.grpc.testing.integration.EmptyProtos.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  io.grpc.testing.integration.Messages.ReconnectInfo.getDefaultInstance()))
              .build();
        }
      }
    }
    return getStopMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ReconnectServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ReconnectServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ReconnectServiceStub>() {
        @java.lang.Override
        public ReconnectServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ReconnectServiceStub(channel, callOptions);
        }
      };
    return ReconnectServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ReconnectServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ReconnectServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ReconnectServiceBlockingV2Stub>() {
        @java.lang.Override
        public ReconnectServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ReconnectServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ReconnectServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ReconnectServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ReconnectServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ReconnectServiceBlockingStub>() {
        @java.lang.Override
        public ReconnectServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ReconnectServiceBlockingStub(channel, callOptions);
        }
      };
    return ReconnectServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ReconnectServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ReconnectServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ReconnectServiceFutureStub>() {
        @java.lang.Override
        public ReconnectServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ReconnectServiceFutureStub(channel, callOptions);
        }
      };
    return ReconnectServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * A service used to control reconnect server.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void start(io.grpc.testing.integration.Messages.ReconnectParams request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.EmptyProtos.Empty> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStartMethod(), responseObserver);
    }

    /**
     */
    default void stop(io.grpc.testing.integration.EmptyProtos.Empty request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.Messages.ReconnectInfo> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStopMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ReconnectService.
   * <pre>
   * A service used to control reconnect server.
   * </pre>
   */
  public static abstract class ReconnectServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ReconnectServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ReconnectService.
   * <pre>
   * A service used to control reconnect server.
   * </pre>
   */
  public static final class ReconnectServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ReconnectServiceStub> {
    private ReconnectServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ReconnectServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ReconnectServiceStub(channel, callOptions);
    }

    /**
     */
    public void start(io.grpc.testing.integration.Messages.ReconnectParams request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.EmptyProtos.Empty> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getStartMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void stop(io.grpc.testing.integration.EmptyProtos.Empty request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.Messages.ReconnectInfo> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getStopMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ReconnectService.
   * <pre>
   * A service used to control reconnect server.
   * </pre>
   */
  public static final class ReconnectServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ReconnectServiceBlockingV2Stub> {
    private ReconnectServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ReconnectServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ReconnectServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public io.grpc.testing.integration.EmptyProtos.Empty start(io.grpc.testing.integration.Messages.ReconnectParams request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getStartMethod(), getCallOptions(), request);
    }

    /**
     */
    public io.grpc.testing.integration.Messages.ReconnectInfo stop(io.grpc.testing.integration.EmptyProtos.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getStopMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do llimited synchronous rpc calls to service ReconnectService.
   * <pre>
   * A service used to control reconnect server.
   * </pre>
   */
  public static final class ReconnectServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ReconnectServiceBlockingStub> {
    private ReconnectServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ReconnectServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ReconnectServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public io.grpc.testing.integration.EmptyProtos.Empty start(io.grpc.testing.integration.Messages.ReconnectParams request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getStartMethod(), getCallOptions(), request);
    }

    /**
     */
    public io.grpc.testing.integration.Messages.ReconnectInfo stop(io.grpc.testing.integration.EmptyProtos.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getStopMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ReconnectService.
   * <pre>
   * A service used to control reconnect server.
   * </pre>
   */
  public static final class ReconnectServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ReconnectServiceFutureStub> {
    private ReconnectServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ReconnectServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ReconnectServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.testing.integration.EmptyProtos.Empty> start(
        io.grpc.testing.integration.Messages.ReconnectParams request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getStartMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.testing.integration.Messages.ReconnectInfo> stop(
        io.grpc.testing.integration.EmptyProtos.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getStopMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_START = 0;
  private static final int METHODID_STOP = 1;

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
        case METHODID_START:
          serviceImpl.start((io.grpc.testing.integration.Messages.ReconnectParams) request,
              (io.grpc.stub.StreamObserver<io.grpc.testing.integration.EmptyProtos.Empty>) responseObserver);
          break;
        case METHODID_STOP:
          serviceImpl.stop((io.grpc.testing.integration.EmptyProtos.Empty) request,
              (io.grpc.stub.StreamObserver<io.grpc.testing.integration.Messages.ReconnectInfo>) responseObserver);
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
          getStartMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.testing.integration.Messages.ReconnectParams,
              io.grpc.testing.integration.EmptyProtos.Empty>(
                service, METHODID_START)))
        .addMethod(
          getStopMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.testing.integration.EmptyProtos.Empty,
              io.grpc.testing.integration.Messages.ReconnectInfo>(
                service, METHODID_STOP)))
        .build();
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (ReconnectServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .addMethod(getStartMethod())
              .addMethod(getStopMethod())
              .build();
        }
      }
    }
    return result;
  }
}
