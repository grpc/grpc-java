package io.grpc.testing.integration;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * A service used to obtain stats for verifying LB behavior.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: grpc/testing/test.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class LoadBalancerStatsServiceGrpc {

  private LoadBalancerStatsServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "grpc.testing.LoadBalancerStatsService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.testing.integration.Messages.LoadBalancerStatsRequest,
      io.grpc.testing.integration.Messages.LoadBalancerStatsResponse> getGetClientStatsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetClientStats",
      requestType = io.grpc.testing.integration.Messages.LoadBalancerStatsRequest.class,
      responseType = io.grpc.testing.integration.Messages.LoadBalancerStatsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.testing.integration.Messages.LoadBalancerStatsRequest,
      io.grpc.testing.integration.Messages.LoadBalancerStatsResponse> getGetClientStatsMethod() {
    io.grpc.MethodDescriptor<io.grpc.testing.integration.Messages.LoadBalancerStatsRequest, io.grpc.testing.integration.Messages.LoadBalancerStatsResponse> getGetClientStatsMethod;
    if ((getGetClientStatsMethod = LoadBalancerStatsServiceGrpc.getGetClientStatsMethod) == null) {
      synchronized (LoadBalancerStatsServiceGrpc.class) {
        if ((getGetClientStatsMethod = LoadBalancerStatsServiceGrpc.getGetClientStatsMethod) == null) {
          LoadBalancerStatsServiceGrpc.getGetClientStatsMethod = getGetClientStatsMethod =
              io.grpc.MethodDescriptor.<io.grpc.testing.integration.Messages.LoadBalancerStatsRequest, io.grpc.testing.integration.Messages.LoadBalancerStatsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetClientStats"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  io.grpc.testing.integration.Messages.LoadBalancerStatsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  io.grpc.testing.integration.Messages.LoadBalancerStatsResponse.getDefaultInstance()))
              .build();
        }
      }
    }
    return getGetClientStatsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsRequest,
      io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsResponse> getGetClientAccumulatedStatsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetClientAccumulatedStats",
      requestType = io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsRequest.class,
      responseType = io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsRequest,
      io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsResponse> getGetClientAccumulatedStatsMethod() {
    io.grpc.MethodDescriptor<io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsRequest, io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsResponse> getGetClientAccumulatedStatsMethod;
    if ((getGetClientAccumulatedStatsMethod = LoadBalancerStatsServiceGrpc.getGetClientAccumulatedStatsMethod) == null) {
      synchronized (LoadBalancerStatsServiceGrpc.class) {
        if ((getGetClientAccumulatedStatsMethod = LoadBalancerStatsServiceGrpc.getGetClientAccumulatedStatsMethod) == null) {
          LoadBalancerStatsServiceGrpc.getGetClientAccumulatedStatsMethod = getGetClientAccumulatedStatsMethod =
              io.grpc.MethodDescriptor.<io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsRequest, io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetClientAccumulatedStats"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsResponse.getDefaultInstance()))
              .build();
        }
      }
    }
    return getGetClientAccumulatedStatsMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static LoadBalancerStatsServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<LoadBalancerStatsServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<LoadBalancerStatsServiceStub>() {
        @java.lang.Override
        public LoadBalancerStatsServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new LoadBalancerStatsServiceStub(channel, callOptions);
        }
      };
    return LoadBalancerStatsServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static LoadBalancerStatsServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<LoadBalancerStatsServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<LoadBalancerStatsServiceBlockingV2Stub>() {
        @java.lang.Override
        public LoadBalancerStatsServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new LoadBalancerStatsServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return LoadBalancerStatsServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static LoadBalancerStatsServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<LoadBalancerStatsServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<LoadBalancerStatsServiceBlockingStub>() {
        @java.lang.Override
        public LoadBalancerStatsServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new LoadBalancerStatsServiceBlockingStub(channel, callOptions);
        }
      };
    return LoadBalancerStatsServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static LoadBalancerStatsServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<LoadBalancerStatsServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<LoadBalancerStatsServiceFutureStub>() {
        @java.lang.Override
        public LoadBalancerStatsServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new LoadBalancerStatsServiceFutureStub(channel, callOptions);
        }
      };
    return LoadBalancerStatsServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * A service used to obtain stats for verifying LB behavior.
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Gets the backend distribution for RPCs sent by a test client.
     * </pre>
     */
    default void getClientStats(io.grpc.testing.integration.Messages.LoadBalancerStatsRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.Messages.LoadBalancerStatsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetClientStatsMethod(), responseObserver);
    }

    /**
     * <pre>
     * Gets the accumulated stats for RPCs sent by a test client.
     * </pre>
     */
    default void getClientAccumulatedStats(io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetClientAccumulatedStatsMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service LoadBalancerStatsService.
   * <pre>
   * A service used to obtain stats for verifying LB behavior.
   * </pre>
   */
  public static abstract class LoadBalancerStatsServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return LoadBalancerStatsServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service LoadBalancerStatsService.
   * <pre>
   * A service used to obtain stats for verifying LB behavior.
   * </pre>
   */
  public static final class LoadBalancerStatsServiceStub
      extends io.grpc.stub.AbstractAsyncStub<LoadBalancerStatsServiceStub> {
    private LoadBalancerStatsServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LoadBalancerStatsServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new LoadBalancerStatsServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Gets the backend distribution for RPCs sent by a test client.
     * </pre>
     */
    public void getClientStats(io.grpc.testing.integration.Messages.LoadBalancerStatsRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.Messages.LoadBalancerStatsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetClientStatsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Gets the accumulated stats for RPCs sent by a test client.
     * </pre>
     */
    public void getClientAccumulatedStats(io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetClientAccumulatedStatsMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service LoadBalancerStatsService.
   * <pre>
   * A service used to obtain stats for verifying LB behavior.
   * </pre>
   */
  public static final class LoadBalancerStatsServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<LoadBalancerStatsServiceBlockingV2Stub> {
    private LoadBalancerStatsServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LoadBalancerStatsServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new LoadBalancerStatsServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Gets the backend distribution for RPCs sent by a test client.
     * </pre>
     */
    public io.grpc.testing.integration.Messages.LoadBalancerStatsResponse getClientStats(io.grpc.testing.integration.Messages.LoadBalancerStatsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetClientStatsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Gets the accumulated stats for RPCs sent by a test client.
     * </pre>
     */
    public io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsResponse getClientAccumulatedStats(io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetClientAccumulatedStatsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do llimited synchronous rpc calls to service LoadBalancerStatsService.
   * <pre>
   * A service used to obtain stats for verifying LB behavior.
   * </pre>
   */
  public static final class LoadBalancerStatsServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<LoadBalancerStatsServiceBlockingStub> {
    private LoadBalancerStatsServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LoadBalancerStatsServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new LoadBalancerStatsServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Gets the backend distribution for RPCs sent by a test client.
     * </pre>
     */
    public io.grpc.testing.integration.Messages.LoadBalancerStatsResponse getClientStats(io.grpc.testing.integration.Messages.LoadBalancerStatsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetClientStatsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Gets the accumulated stats for RPCs sent by a test client.
     * </pre>
     */
    public io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsResponse getClientAccumulatedStats(io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetClientAccumulatedStatsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service LoadBalancerStatsService.
   * <pre>
   * A service used to obtain stats for verifying LB behavior.
   * </pre>
   */
  public static final class LoadBalancerStatsServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<LoadBalancerStatsServiceFutureStub> {
    private LoadBalancerStatsServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LoadBalancerStatsServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new LoadBalancerStatsServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Gets the backend distribution for RPCs sent by a test client.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.testing.integration.Messages.LoadBalancerStatsResponse> getClientStats(
        io.grpc.testing.integration.Messages.LoadBalancerStatsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetClientStatsMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Gets the accumulated stats for RPCs sent by a test client.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsResponse> getClientAccumulatedStats(
        io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetClientAccumulatedStatsMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_CLIENT_STATS = 0;
  private static final int METHODID_GET_CLIENT_ACCUMULATED_STATS = 1;

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
        case METHODID_GET_CLIENT_STATS:
          serviceImpl.getClientStats((io.grpc.testing.integration.Messages.LoadBalancerStatsRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.testing.integration.Messages.LoadBalancerStatsResponse>) responseObserver);
          break;
        case METHODID_GET_CLIENT_ACCUMULATED_STATS:
          serviceImpl.getClientAccumulatedStats((io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsResponse>) responseObserver);
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
          getGetClientStatsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.testing.integration.Messages.LoadBalancerStatsRequest,
              io.grpc.testing.integration.Messages.LoadBalancerStatsResponse>(
                service, METHODID_GET_CLIENT_STATS)))
        .addMethod(
          getGetClientAccumulatedStatsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsRequest,
              io.grpc.testing.integration.Messages.LoadBalancerAccumulatedStatsResponse>(
                service, METHODID_GET_CLIENT_ACCUMULATED_STATS)))
        .build();
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (LoadBalancerStatsServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .addMethod(getGetClientStatsMethod())
              .addMethod(getGetClientAccumulatedStatsMethod())
              .build();
        }
      }
    }
    return result;
  }
}
