package io.envoyproxy.envoy.service.rate_limit_quota.v3;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * Defines the Rate Limit Quota Service (RLQS).
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: envoy/service/rate_limit_quota/v3/rlqs.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class RateLimitQuotaServiceGrpc {

  private RateLimitQuotaServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "envoy.service.rate_limit_quota.v3.RateLimitQuotaService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaUsageReports,
      io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse> getStreamRateLimitQuotasMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StreamRateLimitQuotas",
      requestType = io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaUsageReports.class,
      responseType = io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaUsageReports,
      io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse> getStreamRateLimitQuotasMethod() {
    io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaUsageReports, io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse> getStreamRateLimitQuotasMethod;
    if ((getStreamRateLimitQuotasMethod = RateLimitQuotaServiceGrpc.getStreamRateLimitQuotasMethod) == null) {
      synchronized (RateLimitQuotaServiceGrpc.class) {
        if ((getStreamRateLimitQuotasMethod = RateLimitQuotaServiceGrpc.getStreamRateLimitQuotasMethod) == null) {
          RateLimitQuotaServiceGrpc.getStreamRateLimitQuotasMethod = getStreamRateLimitQuotasMethod =
              io.grpc.MethodDescriptor.<io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaUsageReports, io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StreamRateLimitQuotas"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaUsageReports.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RateLimitQuotaServiceMethodDescriptorSupplier("StreamRateLimitQuotas"))
              .build();
        }
      }
    }
    return getStreamRateLimitQuotasMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static RateLimitQuotaServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RateLimitQuotaServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RateLimitQuotaServiceStub>() {
        @java.lang.Override
        public RateLimitQuotaServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RateLimitQuotaServiceStub(channel, callOptions);
        }
      };
    return RateLimitQuotaServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static RateLimitQuotaServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RateLimitQuotaServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RateLimitQuotaServiceBlockingV2Stub>() {
        @java.lang.Override
        public RateLimitQuotaServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RateLimitQuotaServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return RateLimitQuotaServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static RateLimitQuotaServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RateLimitQuotaServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RateLimitQuotaServiceBlockingStub>() {
        @java.lang.Override
        public RateLimitQuotaServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RateLimitQuotaServiceBlockingStub(channel, callOptions);
        }
      };
    return RateLimitQuotaServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static RateLimitQuotaServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RateLimitQuotaServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RateLimitQuotaServiceFutureStub>() {
        @java.lang.Override
        public RateLimitQuotaServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RateLimitQuotaServiceFutureStub(channel, callOptions);
        }
      };
    return RateLimitQuotaServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * Defines the Rate Limit Quota Service (RLQS).
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Main communication channel: the data plane sends usage reports to the RLQS server,
     * and the server asynchronously responding with the assignments.
     * </pre>
     */
    default io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaUsageReports> streamRateLimitQuotas(
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getStreamRateLimitQuotasMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service RateLimitQuotaService.
   * <pre>
   * Defines the Rate Limit Quota Service (RLQS).
   * </pre>
   */
  public static abstract class RateLimitQuotaServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return RateLimitQuotaServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service RateLimitQuotaService.
   * <pre>
   * Defines the Rate Limit Quota Service (RLQS).
   * </pre>
   */
  public static final class RateLimitQuotaServiceStub
      extends io.grpc.stub.AbstractAsyncStub<RateLimitQuotaServiceStub> {
    private RateLimitQuotaServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RateLimitQuotaServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RateLimitQuotaServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Main communication channel: the data plane sends usage reports to the RLQS server,
     * and the server asynchronously responding with the assignments.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaUsageReports> streamRateLimitQuotas(
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getStreamRateLimitQuotasMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service RateLimitQuotaService.
   * <pre>
   * Defines the Rate Limit Quota Service (RLQS).
   * </pre>
   */
  public static final class RateLimitQuotaServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<RateLimitQuotaServiceBlockingV2Stub> {
    private RateLimitQuotaServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RateLimitQuotaServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RateLimitQuotaServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Main communication channel: the data plane sends usage reports to the RLQS server,
     * and the server asynchronously responding with the assignments.
     * </pre>
     */
    public io.grpc.stub.BlockingClientCall<io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaUsageReports, io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse>
        streamRateLimitQuotas() {
      return io.grpc.stub.ClientCalls.blockingBidiStreamingCall(
          getChannel(), getStreamRateLimitQuotasMethod(), getCallOptions());
    }
  }

  /**
   * A stub to allow clients to do llimited synchronous rpc calls to service RateLimitQuotaService.
   * <pre>
   * Defines the Rate Limit Quota Service (RLQS).
   * </pre>
   */
  public static final class RateLimitQuotaServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<RateLimitQuotaServiceBlockingStub> {
    private RateLimitQuotaServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RateLimitQuotaServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RateLimitQuotaServiceBlockingStub(channel, callOptions);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service RateLimitQuotaService.
   * <pre>
   * Defines the Rate Limit Quota Service (RLQS).
   * </pre>
   */
  public static final class RateLimitQuotaServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<RateLimitQuotaServiceFutureStub> {
    private RateLimitQuotaServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RateLimitQuotaServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RateLimitQuotaServiceFutureStub(channel, callOptions);
    }
  }

  private static final int METHODID_STREAM_RATE_LIMIT_QUOTAS = 0;

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
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_STREAM_RATE_LIMIT_QUOTAS:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.streamRateLimitQuotas(
              (io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getStreamRateLimitQuotasMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaUsageReports,
              io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse>(
                service, METHODID_STREAM_RATE_LIMIT_QUOTAS)))
        .build();
  }

  private static abstract class RateLimitQuotaServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    RateLimitQuotaServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.envoyproxy.envoy.service.rate_limit_quota.v3.RlqsProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("RateLimitQuotaService");
    }
  }

  private static final class RateLimitQuotaServiceFileDescriptorSupplier
      extends RateLimitQuotaServiceBaseDescriptorSupplier {
    RateLimitQuotaServiceFileDescriptorSupplier() {}
  }

  private static final class RateLimitQuotaServiceMethodDescriptorSupplier
      extends RateLimitQuotaServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    RateLimitQuotaServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (RateLimitQuotaServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new RateLimitQuotaServiceFileDescriptorSupplier())
              .addMethod(getStreamRateLimitQuotasMethod())
              .build();
        }
      }
    }
    return result;
  }
}
