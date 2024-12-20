package io.grpc.testing.integration;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: grpc/testing/metrics.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class MetricsServiceGrpc {

  private MetricsServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "grpc.testing.MetricsService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.testing.integration.Metrics.EmptyMessage,
      io.grpc.testing.integration.Metrics.GaugeResponse> getGetAllGaugesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetAllGauges",
      requestType = io.grpc.testing.integration.Metrics.EmptyMessage.class,
      responseType = io.grpc.testing.integration.Metrics.GaugeResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.grpc.testing.integration.Metrics.EmptyMessage,
      io.grpc.testing.integration.Metrics.GaugeResponse> getGetAllGaugesMethod() {
    io.grpc.MethodDescriptor<io.grpc.testing.integration.Metrics.EmptyMessage, io.grpc.testing.integration.Metrics.GaugeResponse> getGetAllGaugesMethod;
    if ((getGetAllGaugesMethod = MetricsServiceGrpc.getGetAllGaugesMethod) == null) {
      synchronized (MetricsServiceGrpc.class) {
        if ((getGetAllGaugesMethod = MetricsServiceGrpc.getGetAllGaugesMethod) == null) {
          MetricsServiceGrpc.getGetAllGaugesMethod = getGetAllGaugesMethod =
              io.grpc.MethodDescriptor.<io.grpc.testing.integration.Metrics.EmptyMessage, io.grpc.testing.integration.Metrics.GaugeResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetAllGauges"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  io.grpc.testing.integration.Metrics.EmptyMessage.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  io.grpc.testing.integration.Metrics.GaugeResponse.getDefaultInstance()))
              .build();
        }
      }
    }
    return getGetAllGaugesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.testing.integration.Metrics.GaugeRequest,
      io.grpc.testing.integration.Metrics.GaugeResponse> getGetGaugeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetGauge",
      requestType = io.grpc.testing.integration.Metrics.GaugeRequest.class,
      responseType = io.grpc.testing.integration.Metrics.GaugeResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.testing.integration.Metrics.GaugeRequest,
      io.grpc.testing.integration.Metrics.GaugeResponse> getGetGaugeMethod() {
    io.grpc.MethodDescriptor<io.grpc.testing.integration.Metrics.GaugeRequest, io.grpc.testing.integration.Metrics.GaugeResponse> getGetGaugeMethod;
    if ((getGetGaugeMethod = MetricsServiceGrpc.getGetGaugeMethod) == null) {
      synchronized (MetricsServiceGrpc.class) {
        if ((getGetGaugeMethod = MetricsServiceGrpc.getGetGaugeMethod) == null) {
          MetricsServiceGrpc.getGetGaugeMethod = getGetGaugeMethod =
              io.grpc.MethodDescriptor.<io.grpc.testing.integration.Metrics.GaugeRequest, io.grpc.testing.integration.Metrics.GaugeResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetGauge"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  io.grpc.testing.integration.Metrics.GaugeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  io.grpc.testing.integration.Metrics.GaugeResponse.getDefaultInstance()))
              .build();
        }
      }
    }
    return getGetGaugeMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static MetricsServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<MetricsServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<MetricsServiceStub>() {
        @java.lang.Override
        public MetricsServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new MetricsServiceStub(channel, callOptions);
        }
      };
    return MetricsServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static MetricsServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<MetricsServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<MetricsServiceBlockingV2Stub>() {
        @java.lang.Override
        public MetricsServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new MetricsServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return MetricsServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static MetricsServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<MetricsServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<MetricsServiceBlockingStub>() {
        @java.lang.Override
        public MetricsServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new MetricsServiceBlockingStub(channel, callOptions);
        }
      };
    return MetricsServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static MetricsServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<MetricsServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<MetricsServiceFutureStub>() {
        @java.lang.Override
        public MetricsServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new MetricsServiceFutureStub(channel, callOptions);
        }
      };
    return MetricsServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     * <pre>
     * Returns the values of all the gauges that are currently being maintained by
     * the service
     * </pre>
     */
    default void getAllGauges(io.grpc.testing.integration.Metrics.EmptyMessage request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.Metrics.GaugeResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetAllGaugesMethod(), responseObserver);
    }

    /**
     * <pre>
     * Returns the value of one gauge
     * </pre>
     */
    default void getGauge(io.grpc.testing.integration.Metrics.GaugeRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.Metrics.GaugeResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetGaugeMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service MetricsService.
   */
  public static abstract class MetricsServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return MetricsServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service MetricsService.
   */
  public static final class MetricsServiceStub
      extends io.grpc.stub.AbstractAsyncStub<MetricsServiceStub> {
    private MetricsServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected MetricsServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new MetricsServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Returns the values of all the gauges that are currently being maintained by
     * the service
     * </pre>
     */
    public void getAllGauges(io.grpc.testing.integration.Metrics.EmptyMessage request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.Metrics.GaugeResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getGetAllGaugesMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Returns the value of one gauge
     * </pre>
     */
    public void getGauge(io.grpc.testing.integration.Metrics.GaugeRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.Metrics.GaugeResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetGaugeMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service MetricsService.
   */
  public static final class MetricsServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<MetricsServiceBlockingV2Stub> {
    private MetricsServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected MetricsServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new MetricsServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Returns the values of all the gauges that are currently being maintained by
     * the service
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, io.grpc.testing.integration.Metrics.GaugeResponse>
        getAllGauges(io.grpc.testing.integration.Metrics.EmptyMessage request) throws java.lang.InterruptedException,
            io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getGetAllGaugesMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Returns the value of one gauge
     * </pre>
     */
    public io.grpc.testing.integration.Metrics.GaugeResponse getGauge(io.grpc.testing.integration.Metrics.GaugeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetGaugeMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do llimited synchronous rpc calls to service MetricsService.
   */
  public static final class MetricsServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<MetricsServiceBlockingStub> {
    private MetricsServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected MetricsServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new MetricsServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Returns the values of all the gauges that are currently being maintained by
     * the service
     * </pre>
     */
    public java.util.Iterator<io.grpc.testing.integration.Metrics.GaugeResponse> getAllGauges(
        io.grpc.testing.integration.Metrics.EmptyMessage request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getGetAllGaugesMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Returns the value of one gauge
     * </pre>
     */
    public io.grpc.testing.integration.Metrics.GaugeResponse getGauge(io.grpc.testing.integration.Metrics.GaugeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetGaugeMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service MetricsService.
   */
  public static final class MetricsServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<MetricsServiceFutureStub> {
    private MetricsServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected MetricsServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new MetricsServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Returns the value of one gauge
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.testing.integration.Metrics.GaugeResponse> getGauge(
        io.grpc.testing.integration.Metrics.GaugeRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetGaugeMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_ALL_GAUGES = 0;
  private static final int METHODID_GET_GAUGE = 1;

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
        case METHODID_GET_ALL_GAUGES:
          serviceImpl.getAllGauges((io.grpc.testing.integration.Metrics.EmptyMessage) request,
              (io.grpc.stub.StreamObserver<io.grpc.testing.integration.Metrics.GaugeResponse>) responseObserver);
          break;
        case METHODID_GET_GAUGE:
          serviceImpl.getGauge((io.grpc.testing.integration.Metrics.GaugeRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.testing.integration.Metrics.GaugeResponse>) responseObserver);
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
          getGetAllGaugesMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              io.grpc.testing.integration.Metrics.EmptyMessage,
              io.grpc.testing.integration.Metrics.GaugeResponse>(
                service, METHODID_GET_ALL_GAUGES)))
        .addMethod(
          getGetGaugeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.testing.integration.Metrics.GaugeRequest,
              io.grpc.testing.integration.Metrics.GaugeResponse>(
                service, METHODID_GET_GAUGE)))
        .build();
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (MetricsServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .addMethod(getGetAllGaugesMethod())
              .addMethod(getGetGaugeMethod())
              .build();
        }
      }
    }
    return result;
  }
}
