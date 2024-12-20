package io.grpc.benchmarks.proto;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: grpc/testing/services.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class WorkerServiceGrpc {

  private WorkerServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "grpc.testing.WorkerService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Control.ServerArgs,
      io.grpc.benchmarks.proto.Control.ServerStatus> getRunServerMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RunServer",
      requestType = io.grpc.benchmarks.proto.Control.ServerArgs.class,
      responseType = io.grpc.benchmarks.proto.Control.ServerStatus.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Control.ServerArgs,
      io.grpc.benchmarks.proto.Control.ServerStatus> getRunServerMethod() {
    io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Control.ServerArgs, io.grpc.benchmarks.proto.Control.ServerStatus> getRunServerMethod;
    if ((getRunServerMethod = WorkerServiceGrpc.getRunServerMethod) == null) {
      synchronized (WorkerServiceGrpc.class) {
        if ((getRunServerMethod = WorkerServiceGrpc.getRunServerMethod) == null) {
          WorkerServiceGrpc.getRunServerMethod = getRunServerMethod =
              io.grpc.MethodDescriptor.<io.grpc.benchmarks.proto.Control.ServerArgs, io.grpc.benchmarks.proto.Control.ServerStatus>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RunServer"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.benchmarks.proto.Control.ServerArgs.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.benchmarks.proto.Control.ServerStatus.getDefaultInstance()))
              .setSchemaDescriptor(new WorkerServiceMethodDescriptorSupplier("RunServer"))
              .build();
        }
      }
    }
    return getRunServerMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Control.ClientArgs,
      io.grpc.benchmarks.proto.Control.ClientStatus> getRunClientMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RunClient",
      requestType = io.grpc.benchmarks.proto.Control.ClientArgs.class,
      responseType = io.grpc.benchmarks.proto.Control.ClientStatus.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Control.ClientArgs,
      io.grpc.benchmarks.proto.Control.ClientStatus> getRunClientMethod() {
    io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Control.ClientArgs, io.grpc.benchmarks.proto.Control.ClientStatus> getRunClientMethod;
    if ((getRunClientMethod = WorkerServiceGrpc.getRunClientMethod) == null) {
      synchronized (WorkerServiceGrpc.class) {
        if ((getRunClientMethod = WorkerServiceGrpc.getRunClientMethod) == null) {
          WorkerServiceGrpc.getRunClientMethod = getRunClientMethod =
              io.grpc.MethodDescriptor.<io.grpc.benchmarks.proto.Control.ClientArgs, io.grpc.benchmarks.proto.Control.ClientStatus>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RunClient"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.benchmarks.proto.Control.ClientArgs.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.benchmarks.proto.Control.ClientStatus.getDefaultInstance()))
              .setSchemaDescriptor(new WorkerServiceMethodDescriptorSupplier("RunClient"))
              .build();
        }
      }
    }
    return getRunClientMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Control.CoreRequest,
      io.grpc.benchmarks.proto.Control.CoreResponse> getCoreCountMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CoreCount",
      requestType = io.grpc.benchmarks.proto.Control.CoreRequest.class,
      responseType = io.grpc.benchmarks.proto.Control.CoreResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Control.CoreRequest,
      io.grpc.benchmarks.proto.Control.CoreResponse> getCoreCountMethod() {
    io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Control.CoreRequest, io.grpc.benchmarks.proto.Control.CoreResponse> getCoreCountMethod;
    if ((getCoreCountMethod = WorkerServiceGrpc.getCoreCountMethod) == null) {
      synchronized (WorkerServiceGrpc.class) {
        if ((getCoreCountMethod = WorkerServiceGrpc.getCoreCountMethod) == null) {
          WorkerServiceGrpc.getCoreCountMethod = getCoreCountMethod =
              io.grpc.MethodDescriptor.<io.grpc.benchmarks.proto.Control.CoreRequest, io.grpc.benchmarks.proto.Control.CoreResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CoreCount"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.benchmarks.proto.Control.CoreRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.benchmarks.proto.Control.CoreResponse.getDefaultInstance()))
              .setSchemaDescriptor(new WorkerServiceMethodDescriptorSupplier("CoreCount"))
              .build();
        }
      }
    }
    return getCoreCountMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Control.Void,
      io.grpc.benchmarks.proto.Control.Void> getQuitWorkerMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "QuitWorker",
      requestType = io.grpc.benchmarks.proto.Control.Void.class,
      responseType = io.grpc.benchmarks.proto.Control.Void.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Control.Void,
      io.grpc.benchmarks.proto.Control.Void> getQuitWorkerMethod() {
    io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Control.Void, io.grpc.benchmarks.proto.Control.Void> getQuitWorkerMethod;
    if ((getQuitWorkerMethod = WorkerServiceGrpc.getQuitWorkerMethod) == null) {
      synchronized (WorkerServiceGrpc.class) {
        if ((getQuitWorkerMethod = WorkerServiceGrpc.getQuitWorkerMethod) == null) {
          WorkerServiceGrpc.getQuitWorkerMethod = getQuitWorkerMethod =
              io.grpc.MethodDescriptor.<io.grpc.benchmarks.proto.Control.Void, io.grpc.benchmarks.proto.Control.Void>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "QuitWorker"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.benchmarks.proto.Control.Void.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.benchmarks.proto.Control.Void.getDefaultInstance()))
              .setSchemaDescriptor(new WorkerServiceMethodDescriptorSupplier("QuitWorker"))
              .build();
        }
      }
    }
    return getQuitWorkerMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static WorkerServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<WorkerServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<WorkerServiceStub>() {
        @java.lang.Override
        public WorkerServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new WorkerServiceStub(channel, callOptions);
        }
      };
    return WorkerServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static WorkerServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<WorkerServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<WorkerServiceBlockingV2Stub>() {
        @java.lang.Override
        public WorkerServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new WorkerServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return WorkerServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static WorkerServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<WorkerServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<WorkerServiceBlockingStub>() {
        @java.lang.Override
        public WorkerServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new WorkerServiceBlockingStub(channel, callOptions);
        }
      };
    return WorkerServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static WorkerServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<WorkerServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<WorkerServiceFutureStub>() {
        @java.lang.Override
        public WorkerServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new WorkerServiceFutureStub(channel, callOptions);
        }
      };
    return WorkerServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     * <pre>
     * Start server with specified workload.
     * First request sent specifies the ServerConfig followed by ServerStatus
     * response. After that, a "Mark" can be sent anytime to request the latest
     * stats. Closing the stream will initiate shutdown of the test server
     * and once the shutdown has finished, the OK status is sent to terminate
     * this RPC.
     * </pre>
     */
    default io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Control.ServerArgs> runServer(
        io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Control.ServerStatus> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getRunServerMethod(), responseObserver);
    }

    /**
     * <pre>
     * Start client with specified workload.
     * First request sent specifies the ClientConfig followed by ClientStatus
     * response. After that, a "Mark" can be sent anytime to request the latest
     * stats. Closing the stream will initiate shutdown of the test client
     * and once the shutdown has finished, the OK status is sent to terminate
     * this RPC.
     * </pre>
     */
    default io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Control.ClientArgs> runClient(
        io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Control.ClientStatus> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getRunClientMethod(), responseObserver);
    }

    /**
     * <pre>
     * Just return the core count - unary call
     * </pre>
     */
    default void coreCount(io.grpc.benchmarks.proto.Control.CoreRequest request,
        io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Control.CoreResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCoreCountMethod(), responseObserver);
    }

    /**
     * <pre>
     * Quit this worker
     * </pre>
     */
    default void quitWorker(io.grpc.benchmarks.proto.Control.Void request,
        io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Control.Void> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getQuitWorkerMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service WorkerService.
   */
  public static abstract class WorkerServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return WorkerServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service WorkerService.
   */
  public static final class WorkerServiceStub
      extends io.grpc.stub.AbstractAsyncStub<WorkerServiceStub> {
    private WorkerServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected WorkerServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new WorkerServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Start server with specified workload.
     * First request sent specifies the ServerConfig followed by ServerStatus
     * response. After that, a "Mark" can be sent anytime to request the latest
     * stats. Closing the stream will initiate shutdown of the test server
     * and once the shutdown has finished, the OK status is sent to terminate
     * this RPC.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Control.ServerArgs> runServer(
        io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Control.ServerStatus> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getRunServerMethod(), getCallOptions()), responseObserver);
    }

    /**
     * <pre>
     * Start client with specified workload.
     * First request sent specifies the ClientConfig followed by ClientStatus
     * response. After that, a "Mark" can be sent anytime to request the latest
     * stats. Closing the stream will initiate shutdown of the test client
     * and once the shutdown has finished, the OK status is sent to terminate
     * this RPC.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Control.ClientArgs> runClient(
        io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Control.ClientStatus> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getRunClientMethod(), getCallOptions()), responseObserver);
    }

    /**
     * <pre>
     * Just return the core count - unary call
     * </pre>
     */
    public void coreCount(io.grpc.benchmarks.proto.Control.CoreRequest request,
        io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Control.CoreResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCoreCountMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Quit this worker
     * </pre>
     */
    public void quitWorker(io.grpc.benchmarks.proto.Control.Void request,
        io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Control.Void> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getQuitWorkerMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service WorkerService.
   */
  public static final class WorkerServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<WorkerServiceBlockingV2Stub> {
    private WorkerServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected WorkerServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new WorkerServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Start server with specified workload.
     * First request sent specifies the ServerConfig followed by ServerStatus
     * response. After that, a "Mark" can be sent anytime to request the latest
     * stats. Closing the stream will initiate shutdown of the test server
     * and once the shutdown has finished, the OK status is sent to terminate
     * this RPC.
     * </pre>
     */
    public io.grpc.stub.BlockingClientCall<io.grpc.benchmarks.proto.Control.ServerArgs, io.grpc.benchmarks.proto.Control.ServerStatus>
        runServer() {
      return io.grpc.stub.ClientCalls.blockingBidiStreamingCall(
          getChannel(), getRunServerMethod(), getCallOptions());
    }

    /**
     * <pre>
     * Start client with specified workload.
     * First request sent specifies the ClientConfig followed by ClientStatus
     * response. After that, a "Mark" can be sent anytime to request the latest
     * stats. Closing the stream will initiate shutdown of the test client
     * and once the shutdown has finished, the OK status is sent to terminate
     * this RPC.
     * </pre>
     */
    public io.grpc.stub.BlockingClientCall<io.grpc.benchmarks.proto.Control.ClientArgs, io.grpc.benchmarks.proto.Control.ClientStatus>
        runClient() {
      return io.grpc.stub.ClientCalls.blockingBidiStreamingCall(
          getChannel(), getRunClientMethod(), getCallOptions());
    }

    /**
     * <pre>
     * Just return the core count - unary call
     * </pre>
     */
    public io.grpc.benchmarks.proto.Control.CoreResponse coreCount(io.grpc.benchmarks.proto.Control.CoreRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCoreCountMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Quit this worker
     * </pre>
     */
    public io.grpc.benchmarks.proto.Control.Void quitWorker(io.grpc.benchmarks.proto.Control.Void request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getQuitWorkerMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do llimited synchronous rpc calls to service WorkerService.
   */
  public static final class WorkerServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<WorkerServiceBlockingStub> {
    private WorkerServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected WorkerServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new WorkerServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Just return the core count - unary call
     * </pre>
     */
    public io.grpc.benchmarks.proto.Control.CoreResponse coreCount(io.grpc.benchmarks.proto.Control.CoreRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCoreCountMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Quit this worker
     * </pre>
     */
    public io.grpc.benchmarks.proto.Control.Void quitWorker(io.grpc.benchmarks.proto.Control.Void request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getQuitWorkerMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service WorkerService.
   */
  public static final class WorkerServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<WorkerServiceFutureStub> {
    private WorkerServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected WorkerServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new WorkerServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Just return the core count - unary call
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.benchmarks.proto.Control.CoreResponse> coreCount(
        io.grpc.benchmarks.proto.Control.CoreRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCoreCountMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Quit this worker
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.benchmarks.proto.Control.Void> quitWorker(
        io.grpc.benchmarks.proto.Control.Void request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getQuitWorkerMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_CORE_COUNT = 0;
  private static final int METHODID_QUIT_WORKER = 1;
  private static final int METHODID_RUN_SERVER = 2;
  private static final int METHODID_RUN_CLIENT = 3;

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
        case METHODID_CORE_COUNT:
          serviceImpl.coreCount((io.grpc.benchmarks.proto.Control.CoreRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Control.CoreResponse>) responseObserver);
          break;
        case METHODID_QUIT_WORKER:
          serviceImpl.quitWorker((io.grpc.benchmarks.proto.Control.Void) request,
              (io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Control.Void>) responseObserver);
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
        case METHODID_RUN_SERVER:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.runServer(
              (io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Control.ServerStatus>) responseObserver);
        case METHODID_RUN_CLIENT:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.runClient(
              (io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Control.ClientStatus>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getRunServerMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              io.grpc.benchmarks.proto.Control.ServerArgs,
              io.grpc.benchmarks.proto.Control.ServerStatus>(
                service, METHODID_RUN_SERVER)))
        .addMethod(
          getRunClientMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              io.grpc.benchmarks.proto.Control.ClientArgs,
              io.grpc.benchmarks.proto.Control.ClientStatus>(
                service, METHODID_RUN_CLIENT)))
        .addMethod(
          getCoreCountMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.benchmarks.proto.Control.CoreRequest,
              io.grpc.benchmarks.proto.Control.CoreResponse>(
                service, METHODID_CORE_COUNT)))
        .addMethod(
          getQuitWorkerMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.benchmarks.proto.Control.Void,
              io.grpc.benchmarks.proto.Control.Void>(
                service, METHODID_QUIT_WORKER)))
        .build();
  }

  private static abstract class WorkerServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    WorkerServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.grpc.benchmarks.proto.Services.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("WorkerService");
    }
  }

  private static final class WorkerServiceFileDescriptorSupplier
      extends WorkerServiceBaseDescriptorSupplier {
    WorkerServiceFileDescriptorSupplier() {}
  }

  private static final class WorkerServiceMethodDescriptorSupplier
      extends WorkerServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    WorkerServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (WorkerServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new WorkerServiceFileDescriptorSupplier())
              .addMethod(getRunServerMethod())
              .addMethod(getRunClientMethod())
              .addMethod(getCoreCountMethod())
              .addMethod(getQuitWorkerMethod())
              .build();
        }
      }
    }
    return result;
  }
}
