package com.google.cloud.autosharding.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * An auto-sharding service that assigns keys in an application's keyspace to
 * abstract "endpoints", and which uses load information from clients to update
 * that assignment over time. Assignments and load reports are scoped to an
 * abstract "slicing target."
 * Concrete examples of the concepts in this protocol:
 * Endpoints:
 * * Application servers
 * * Pods in a Kubernetes cluster
 * * Regions in a multi-regional service
 * Keys:
 * * User ids
 * * Tenant ids in a multi-tenant system
 * Load:
 * * Request count
 * * CPU cost of processing requests
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class AutoshardingServiceGrpc {

  private AutoshardingServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "google.cloud.autosharding.v1.AutoshardingService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.google.cloud.autosharding.v1.WatchShardingAssignmentRequest,
      com.google.cloud.autosharding.v1.WatchShardingAssignmentResponse> getWatchShardingAssignmentMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "WatchShardingAssignment",
      requestType = com.google.cloud.autosharding.v1.WatchShardingAssignmentRequest.class,
      responseType = com.google.cloud.autosharding.v1.WatchShardingAssignmentResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<com.google.cloud.autosharding.v1.WatchShardingAssignmentRequest,
      com.google.cloud.autosharding.v1.WatchShardingAssignmentResponse> getWatchShardingAssignmentMethod() {
    io.grpc.MethodDescriptor<com.google.cloud.autosharding.v1.WatchShardingAssignmentRequest, com.google.cloud.autosharding.v1.WatchShardingAssignmentResponse> getWatchShardingAssignmentMethod;
    if ((getWatchShardingAssignmentMethod = AutoshardingServiceGrpc.getWatchShardingAssignmentMethod) == null) {
      synchronized (AutoshardingServiceGrpc.class) {
        if ((getWatchShardingAssignmentMethod = AutoshardingServiceGrpc.getWatchShardingAssignmentMethod) == null) {
          AutoshardingServiceGrpc.getWatchShardingAssignmentMethod = getWatchShardingAssignmentMethod =
              io.grpc.MethodDescriptor.<com.google.cloud.autosharding.v1.WatchShardingAssignmentRequest, com.google.cloud.autosharding.v1.WatchShardingAssignmentResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "WatchShardingAssignment"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.cloud.autosharding.v1.WatchShardingAssignmentRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.cloud.autosharding.v1.WatchShardingAssignmentResponse.getDefaultInstance()))
              .setSchemaDescriptor(new AutoshardingServiceMethodDescriptorSupplier("WatchShardingAssignment"))
              .build();
        }
      }
    }
    return getWatchShardingAssignmentMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static AutoshardingServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AutoshardingServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AutoshardingServiceStub>() {
        @java.lang.Override
        public AutoshardingServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AutoshardingServiceStub(channel, callOptions);
        }
      };
    return AutoshardingServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static AutoshardingServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AutoshardingServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AutoshardingServiceBlockingV2Stub>() {
        @java.lang.Override
        public AutoshardingServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AutoshardingServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return AutoshardingServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static AutoshardingServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AutoshardingServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AutoshardingServiceBlockingStub>() {
        @java.lang.Override
        public AutoshardingServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AutoshardingServiceBlockingStub(channel, callOptions);
        }
      };
    return AutoshardingServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static AutoshardingServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AutoshardingServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AutoshardingServiceFutureStub>() {
        @java.lang.Override
        public AutoshardingServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AutoshardingServiceFutureStub(channel, callOptions);
        }
      };
    return AutoshardingServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * An auto-sharding service that assigns keys in an application's keyspace to
   * abstract "endpoints", and which uses load information from clients to update
   * that assignment over time. Assignments and load reports are scoped to an
   * abstract "slicing target."
   * Concrete examples of the concepts in this protocol:
   * Endpoints:
   * * Application servers
   * * Pods in a Kubernetes cluster
   * * Regions in a multi-regional service
   * Keys:
   * * User ids
   * * Tenant ids in a multi-tenant system
   * Load:
   * * Request count
   * * CPU cost of processing requests
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Opens a stream over which clients report load and the server delivers
     * sharding assignments.
     * A given client may use this stream just to receive assignments or also to
     * report load, and it may opt in or out of reporting load at any time. (An
     * implementation of the Autosharding service may impose restrictions on
     * which clients are allowed to report load, and it may respond to clients
     * improperly reporting load by ignoring their reports or terminating their
     * streams with an error.)
     * The client should keep this stream open at all times and reopen the stream
     * after it closes, with backoff if the stream closed without delivering any
     * data.
     * </pre>
     */
    default io.grpc.stub.StreamObserver<com.google.cloud.autosharding.v1.WatchShardingAssignmentRequest> watchShardingAssignment(
        io.grpc.stub.StreamObserver<com.google.cloud.autosharding.v1.WatchShardingAssignmentResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getWatchShardingAssignmentMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service AutoshardingService.
   * <pre>
   * An auto-sharding service that assigns keys in an application's keyspace to
   * abstract "endpoints", and which uses load information from clients to update
   * that assignment over time. Assignments and load reports are scoped to an
   * abstract "slicing target."
   * Concrete examples of the concepts in this protocol:
   * Endpoints:
   * * Application servers
   * * Pods in a Kubernetes cluster
   * * Regions in a multi-regional service
   * Keys:
   * * User ids
   * * Tenant ids in a multi-tenant system
   * Load:
   * * Request count
   * * CPU cost of processing requests
   * </pre>
   */
  public static abstract class AutoshardingServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return AutoshardingServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service AutoshardingService.
   * <pre>
   * An auto-sharding service that assigns keys in an application's keyspace to
   * abstract "endpoints", and which uses load information from clients to update
   * that assignment over time. Assignments and load reports are scoped to an
   * abstract "slicing target."
   * Concrete examples of the concepts in this protocol:
   * Endpoints:
   * * Application servers
   * * Pods in a Kubernetes cluster
   * * Regions in a multi-regional service
   * Keys:
   * * User ids
   * * Tenant ids in a multi-tenant system
   * Load:
   * * Request count
   * * CPU cost of processing requests
   * </pre>
   */
  public static final class AutoshardingServiceStub
      extends io.grpc.stub.AbstractAsyncStub<AutoshardingServiceStub> {
    private AutoshardingServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AutoshardingServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AutoshardingServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Opens a stream over which clients report load and the server delivers
     * sharding assignments.
     * A given client may use this stream just to receive assignments or also to
     * report load, and it may opt in or out of reporting load at any time. (An
     * implementation of the Autosharding service may impose restrictions on
     * which clients are allowed to report load, and it may respond to clients
     * improperly reporting load by ignoring their reports or terminating their
     * streams with an error.)
     * The client should keep this stream open at all times and reopen the stream
     * after it closes, with backoff if the stream closed without delivering any
     * data.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<com.google.cloud.autosharding.v1.WatchShardingAssignmentRequest> watchShardingAssignment(
        io.grpc.stub.StreamObserver<com.google.cloud.autosharding.v1.WatchShardingAssignmentResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getWatchShardingAssignmentMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service AutoshardingService.
   * <pre>
   * An auto-sharding service that assigns keys in an application's keyspace to
   * abstract "endpoints", and which uses load information from clients to update
   * that assignment over time. Assignments and load reports are scoped to an
   * abstract "slicing target."
   * Concrete examples of the concepts in this protocol:
   * Endpoints:
   * * Application servers
   * * Pods in a Kubernetes cluster
   * * Regions in a multi-regional service
   * Keys:
   * * User ids
   * * Tenant ids in a multi-tenant system
   * Load:
   * * Request count
   * * CPU cost of processing requests
   * </pre>
   */
  public static final class AutoshardingServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<AutoshardingServiceBlockingV2Stub> {
    private AutoshardingServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AutoshardingServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AutoshardingServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Opens a stream over which clients report load and the server delivers
     * sharding assignments.
     * A given client may use this stream just to receive assignments or also to
     * report load, and it may opt in or out of reporting load at any time. (An
     * implementation of the Autosharding service may impose restrictions on
     * which clients are allowed to report load, and it may respond to clients
     * improperly reporting load by ignoring their reports or terminating their
     * streams with an error.)
     * The client should keep this stream open at all times and reopen the stream
     * after it closes, with backoff if the stream closed without delivering any
     * data.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<com.google.cloud.autosharding.v1.WatchShardingAssignmentRequest, com.google.cloud.autosharding.v1.WatchShardingAssignmentResponse>
        watchShardingAssignment() {
      return io.grpc.stub.ClientCalls.blockingBidiStreamingCall(
          getChannel(), getWatchShardingAssignmentMethod(), getCallOptions());
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service AutoshardingService.
   * <pre>
   * An auto-sharding service that assigns keys in an application's keyspace to
   * abstract "endpoints", and which uses load information from clients to update
   * that assignment over time. Assignments and load reports are scoped to an
   * abstract "slicing target."
   * Concrete examples of the concepts in this protocol:
   * Endpoints:
   * * Application servers
   * * Pods in a Kubernetes cluster
   * * Regions in a multi-regional service
   * Keys:
   * * User ids
   * * Tenant ids in a multi-tenant system
   * Load:
   * * Request count
   * * CPU cost of processing requests
   * </pre>
   */
  public static final class AutoshardingServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<AutoshardingServiceBlockingStub> {
    private AutoshardingServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AutoshardingServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AutoshardingServiceBlockingStub(channel, callOptions);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service AutoshardingService.
   * <pre>
   * An auto-sharding service that assigns keys in an application's keyspace to
   * abstract "endpoints", and which uses load information from clients to update
   * that assignment over time. Assignments and load reports are scoped to an
   * abstract "slicing target."
   * Concrete examples of the concepts in this protocol:
   * Endpoints:
   * * Application servers
   * * Pods in a Kubernetes cluster
   * * Regions in a multi-regional service
   * Keys:
   * * User ids
   * * Tenant ids in a multi-tenant system
   * Load:
   * * Request count
   * * CPU cost of processing requests
   * </pre>
   */
  public static final class AutoshardingServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<AutoshardingServiceFutureStub> {
    private AutoshardingServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AutoshardingServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AutoshardingServiceFutureStub(channel, callOptions);
    }
  }

  private static final int METHODID_WATCH_SHARDING_ASSIGNMENT = 0;

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
        case METHODID_WATCH_SHARDING_ASSIGNMENT:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.watchShardingAssignment(
              (io.grpc.stub.StreamObserver<com.google.cloud.autosharding.v1.WatchShardingAssignmentResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getWatchShardingAssignmentMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              com.google.cloud.autosharding.v1.WatchShardingAssignmentRequest,
              com.google.cloud.autosharding.v1.WatchShardingAssignmentResponse>(
                service, METHODID_WATCH_SHARDING_ASSIGNMENT)))
        .build();
  }

  private static abstract class AutoshardingServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    AutoshardingServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.google.cloud.autosharding.v1.AutoshardingProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("AutoshardingService");
    }
  }

  private static final class AutoshardingServiceFileDescriptorSupplier
      extends AutoshardingServiceBaseDescriptorSupplier {
    AutoshardingServiceFileDescriptorSupplier() {}
  }

  private static final class AutoshardingServiceMethodDescriptorSupplier
      extends AutoshardingServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    AutoshardingServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (AutoshardingServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new AutoshardingServiceFileDescriptorSupplier())
              .addMethod(getWatchShardingAssignmentMethod())
              .build();
        }
      }
    }
    return result;
  }
}
