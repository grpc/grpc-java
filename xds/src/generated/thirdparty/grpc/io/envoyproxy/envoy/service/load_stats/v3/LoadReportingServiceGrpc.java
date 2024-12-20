package io.envoyproxy.envoy.service.load_stats.v3;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: envoy/service/load_stats/v3/lrs.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class LoadReportingServiceGrpc {

  private LoadReportingServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "envoy.service.load_stats.v3.LoadReportingService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest,
      io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse> getStreamLoadStatsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StreamLoadStats",
      requestType = io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest.class,
      responseType = io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest,
      io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse> getStreamLoadStatsMethod() {
    io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest, io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse> getStreamLoadStatsMethod;
    if ((getStreamLoadStatsMethod = LoadReportingServiceGrpc.getStreamLoadStatsMethod) == null) {
      synchronized (LoadReportingServiceGrpc.class) {
        if ((getStreamLoadStatsMethod = LoadReportingServiceGrpc.getStreamLoadStatsMethod) == null) {
          LoadReportingServiceGrpc.getStreamLoadStatsMethod = getStreamLoadStatsMethod =
              io.grpc.MethodDescriptor.<io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest, io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StreamLoadStats"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new LoadReportingServiceMethodDescriptorSupplier("StreamLoadStats"))
              .build();
        }
      }
    }
    return getStreamLoadStatsMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static LoadReportingServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<LoadReportingServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<LoadReportingServiceStub>() {
        @java.lang.Override
        public LoadReportingServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new LoadReportingServiceStub(channel, callOptions);
        }
      };
    return LoadReportingServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static LoadReportingServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<LoadReportingServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<LoadReportingServiceBlockingV2Stub>() {
        @java.lang.Override
        public LoadReportingServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new LoadReportingServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return LoadReportingServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static LoadReportingServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<LoadReportingServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<LoadReportingServiceBlockingStub>() {
        @java.lang.Override
        public LoadReportingServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new LoadReportingServiceBlockingStub(channel, callOptions);
        }
      };
    return LoadReportingServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static LoadReportingServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<LoadReportingServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<LoadReportingServiceFutureStub>() {
        @java.lang.Override
        public LoadReportingServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new LoadReportingServiceFutureStub(channel, callOptions);
        }
      };
    return LoadReportingServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     * <pre>
     * Advanced API to allow for multi-dimensional load balancing by remote
     * server. For receiving LB assignments, the steps are:
     * 1, The management server is configured with per cluster/zone/load metric
     *    capacity configuration. The capacity configuration definition is
     *    outside of the scope of this document.
     * 2. Envoy issues a standard {Stream,Fetch}Endpoints request for the clusters
     *    to balance.
     * Independently, Envoy will initiate a StreamLoadStats bidi stream with a
     * management server:
     * 1. Once a connection establishes, the management server publishes a
     *    LoadStatsResponse for all clusters it is interested in learning load
     *    stats about.
     * 2. For each cluster, Envoy load balances incoming traffic to upstream hosts
     *    based on per-zone weights and/or per-instance weights (if specified)
     *    based on intra-zone LbPolicy. This information comes from the above
     *    {Stream,Fetch}Endpoints.
     * 3. When upstream hosts reply, they optionally add header &lt;define header
     *    name&gt; with ASCII representation of EndpointLoadMetricStats.
     * 4. Envoy aggregates load reports over the period of time given to it in
     *    LoadStatsResponse.load_reporting_interval. This includes aggregation
     *    stats Envoy maintains by itself (total_requests, rpc_errors etc.) as
     *    well as load metrics from upstream hosts.
     * 5. When the timer of load_reporting_interval expires, Envoy sends new
     *    LoadStatsRequest filled with load reports for each cluster.
     * 6. The management server uses the load reports from all reported Envoys
     *    from around the world, computes global assignment and prepares traffic
     *    assignment destined for each zone Envoys are located in. Goto 2.
     * </pre>
     */
    default io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest> streamLoadStats(
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getStreamLoadStatsMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service LoadReportingService.
   */
  public static abstract class LoadReportingServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return LoadReportingServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service LoadReportingService.
   */
  public static final class LoadReportingServiceStub
      extends io.grpc.stub.AbstractAsyncStub<LoadReportingServiceStub> {
    private LoadReportingServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LoadReportingServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new LoadReportingServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Advanced API to allow for multi-dimensional load balancing by remote
     * server. For receiving LB assignments, the steps are:
     * 1, The management server is configured with per cluster/zone/load metric
     *    capacity configuration. The capacity configuration definition is
     *    outside of the scope of this document.
     * 2. Envoy issues a standard {Stream,Fetch}Endpoints request for the clusters
     *    to balance.
     * Independently, Envoy will initiate a StreamLoadStats bidi stream with a
     * management server:
     * 1. Once a connection establishes, the management server publishes a
     *    LoadStatsResponse for all clusters it is interested in learning load
     *    stats about.
     * 2. For each cluster, Envoy load balances incoming traffic to upstream hosts
     *    based on per-zone weights and/or per-instance weights (if specified)
     *    based on intra-zone LbPolicy. This information comes from the above
     *    {Stream,Fetch}Endpoints.
     * 3. When upstream hosts reply, they optionally add header &lt;define header
     *    name&gt; with ASCII representation of EndpointLoadMetricStats.
     * 4. Envoy aggregates load reports over the period of time given to it in
     *    LoadStatsResponse.load_reporting_interval. This includes aggregation
     *    stats Envoy maintains by itself (total_requests, rpc_errors etc.) as
     *    well as load metrics from upstream hosts.
     * 5. When the timer of load_reporting_interval expires, Envoy sends new
     *    LoadStatsRequest filled with load reports for each cluster.
     * 6. The management server uses the load reports from all reported Envoys
     *    from around the world, computes global assignment and prepares traffic
     *    assignment destined for each zone Envoys are located in. Goto 2.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest> streamLoadStats(
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getStreamLoadStatsMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service LoadReportingService.
   */
  public static final class LoadReportingServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<LoadReportingServiceBlockingV2Stub> {
    private LoadReportingServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LoadReportingServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new LoadReportingServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Advanced API to allow for multi-dimensional load balancing by remote
     * server. For receiving LB assignments, the steps are:
     * 1, The management server is configured with per cluster/zone/load metric
     *    capacity configuration. The capacity configuration definition is
     *    outside of the scope of this document.
     * 2. Envoy issues a standard {Stream,Fetch}Endpoints request for the clusters
     *    to balance.
     * Independently, Envoy will initiate a StreamLoadStats bidi stream with a
     * management server:
     * 1. Once a connection establishes, the management server publishes a
     *    LoadStatsResponse for all clusters it is interested in learning load
     *    stats about.
     * 2. For each cluster, Envoy load balances incoming traffic to upstream hosts
     *    based on per-zone weights and/or per-instance weights (if specified)
     *    based on intra-zone LbPolicy. This information comes from the above
     *    {Stream,Fetch}Endpoints.
     * 3. When upstream hosts reply, they optionally add header &lt;define header
     *    name&gt; with ASCII representation of EndpointLoadMetricStats.
     * 4. Envoy aggregates load reports over the period of time given to it in
     *    LoadStatsResponse.load_reporting_interval. This includes aggregation
     *    stats Envoy maintains by itself (total_requests, rpc_errors etc.) as
     *    well as load metrics from upstream hosts.
     * 5. When the timer of load_reporting_interval expires, Envoy sends new
     *    LoadStatsRequest filled with load reports for each cluster.
     * 6. The management server uses the load reports from all reported Envoys
     *    from around the world, computes global assignment and prepares traffic
     *    assignment destined for each zone Envoys are located in. Goto 2.
     * </pre>
     */
    public io.grpc.stub.BlockingClientCall<io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest, io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse>
        streamLoadStats() {
      return io.grpc.stub.ClientCalls.blockingBidiStreamingCall(
          getChannel(), getStreamLoadStatsMethod(), getCallOptions());
    }
  }

  /**
   * A stub to allow clients to do llimited synchronous rpc calls to service LoadReportingService.
   */
  public static final class LoadReportingServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<LoadReportingServiceBlockingStub> {
    private LoadReportingServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LoadReportingServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new LoadReportingServiceBlockingStub(channel, callOptions);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service LoadReportingService.
   */
  public static final class LoadReportingServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<LoadReportingServiceFutureStub> {
    private LoadReportingServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LoadReportingServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new LoadReportingServiceFutureStub(channel, callOptions);
    }
  }

  private static final int METHODID_STREAM_LOAD_STATS = 0;

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
        case METHODID_STREAM_LOAD_STATS:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.streamLoadStats(
              (io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getStreamLoadStatsMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest,
              io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse>(
                service, METHODID_STREAM_LOAD_STATS)))
        .build();
  }

  private static abstract class LoadReportingServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    LoadReportingServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.envoyproxy.envoy.service.load_stats.v3.LrsProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("LoadReportingService");
    }
  }

  private static final class LoadReportingServiceFileDescriptorSupplier
      extends LoadReportingServiceBaseDescriptorSupplier {
    LoadReportingServiceFileDescriptorSupplier() {}
  }

  private static final class LoadReportingServiceMethodDescriptorSupplier
      extends LoadReportingServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    LoadReportingServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (LoadReportingServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new LoadReportingServiceFileDescriptorSupplier())
              .addMethod(getStreamLoadStatsMethod())
              .build();
        }
      }
    }
    return result;
  }
}
