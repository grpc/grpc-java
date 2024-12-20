package io.grpc.benchmarks.proto;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: grpc/testing/services.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class ReportQpsScenarioServiceGrpc {

  private ReportQpsScenarioServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "grpc.testing.ReportQpsScenarioService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Control.ScenarioResult,
      io.grpc.benchmarks.proto.Control.Void> getReportScenarioMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ReportScenario",
      requestType = io.grpc.benchmarks.proto.Control.ScenarioResult.class,
      responseType = io.grpc.benchmarks.proto.Control.Void.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Control.ScenarioResult,
      io.grpc.benchmarks.proto.Control.Void> getReportScenarioMethod() {
    io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Control.ScenarioResult, io.grpc.benchmarks.proto.Control.Void> getReportScenarioMethod;
    if ((getReportScenarioMethod = ReportQpsScenarioServiceGrpc.getReportScenarioMethod) == null) {
      synchronized (ReportQpsScenarioServiceGrpc.class) {
        if ((getReportScenarioMethod = ReportQpsScenarioServiceGrpc.getReportScenarioMethod) == null) {
          ReportQpsScenarioServiceGrpc.getReportScenarioMethod = getReportScenarioMethod =
              io.grpc.MethodDescriptor.<io.grpc.benchmarks.proto.Control.ScenarioResult, io.grpc.benchmarks.proto.Control.Void>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ReportScenario"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.benchmarks.proto.Control.ScenarioResult.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.benchmarks.proto.Control.Void.getDefaultInstance()))
              .setSchemaDescriptor(new ReportQpsScenarioServiceMethodDescriptorSupplier("ReportScenario"))
              .build();
        }
      }
    }
    return getReportScenarioMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ReportQpsScenarioServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ReportQpsScenarioServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ReportQpsScenarioServiceStub>() {
        @java.lang.Override
        public ReportQpsScenarioServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ReportQpsScenarioServiceStub(channel, callOptions);
        }
      };
    return ReportQpsScenarioServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ReportQpsScenarioServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ReportQpsScenarioServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ReportQpsScenarioServiceBlockingV2Stub>() {
        @java.lang.Override
        public ReportQpsScenarioServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ReportQpsScenarioServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return ReportQpsScenarioServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ReportQpsScenarioServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ReportQpsScenarioServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ReportQpsScenarioServiceBlockingStub>() {
        @java.lang.Override
        public ReportQpsScenarioServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ReportQpsScenarioServiceBlockingStub(channel, callOptions);
        }
      };
    return ReportQpsScenarioServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ReportQpsScenarioServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ReportQpsScenarioServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ReportQpsScenarioServiceFutureStub>() {
        @java.lang.Override
        public ReportQpsScenarioServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ReportQpsScenarioServiceFutureStub(channel, callOptions);
        }
      };
    return ReportQpsScenarioServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     * <pre>
     * Report results of a QPS test benchmark scenario.
     * </pre>
     */
    default void reportScenario(io.grpc.benchmarks.proto.Control.ScenarioResult request,
        io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Control.Void> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReportScenarioMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ReportQpsScenarioService.
   */
  public static abstract class ReportQpsScenarioServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ReportQpsScenarioServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ReportQpsScenarioService.
   */
  public static final class ReportQpsScenarioServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ReportQpsScenarioServiceStub> {
    private ReportQpsScenarioServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ReportQpsScenarioServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ReportQpsScenarioServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Report results of a QPS test benchmark scenario.
     * </pre>
     */
    public void reportScenario(io.grpc.benchmarks.proto.Control.ScenarioResult request,
        io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Control.Void> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getReportScenarioMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ReportQpsScenarioService.
   */
  public static final class ReportQpsScenarioServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ReportQpsScenarioServiceBlockingV2Stub> {
    private ReportQpsScenarioServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ReportQpsScenarioServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ReportQpsScenarioServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Report results of a QPS test benchmark scenario.
     * </pre>
     */
    public io.grpc.benchmarks.proto.Control.Void reportScenario(io.grpc.benchmarks.proto.Control.ScenarioResult request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReportScenarioMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do llimited synchronous rpc calls to service ReportQpsScenarioService.
   */
  public static final class ReportQpsScenarioServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ReportQpsScenarioServiceBlockingStub> {
    private ReportQpsScenarioServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ReportQpsScenarioServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ReportQpsScenarioServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Report results of a QPS test benchmark scenario.
     * </pre>
     */
    public io.grpc.benchmarks.proto.Control.Void reportScenario(io.grpc.benchmarks.proto.Control.ScenarioResult request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReportScenarioMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ReportQpsScenarioService.
   */
  public static final class ReportQpsScenarioServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ReportQpsScenarioServiceFutureStub> {
    private ReportQpsScenarioServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ReportQpsScenarioServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ReportQpsScenarioServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Report results of a QPS test benchmark scenario.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.benchmarks.proto.Control.Void> reportScenario(
        io.grpc.benchmarks.proto.Control.ScenarioResult request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getReportScenarioMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_REPORT_SCENARIO = 0;

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
        case METHODID_REPORT_SCENARIO:
          serviceImpl.reportScenario((io.grpc.benchmarks.proto.Control.ScenarioResult) request,
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
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getReportScenarioMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.benchmarks.proto.Control.ScenarioResult,
              io.grpc.benchmarks.proto.Control.Void>(
                service, METHODID_REPORT_SCENARIO)))
        .build();
  }

  private static abstract class ReportQpsScenarioServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ReportQpsScenarioServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.grpc.benchmarks.proto.Services.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ReportQpsScenarioService");
    }
  }

  private static final class ReportQpsScenarioServiceFileDescriptorSupplier
      extends ReportQpsScenarioServiceBaseDescriptorSupplier {
    ReportQpsScenarioServiceFileDescriptorSupplier() {}
  }

  private static final class ReportQpsScenarioServiceMethodDescriptorSupplier
      extends ReportQpsScenarioServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ReportQpsScenarioServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ReportQpsScenarioServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ReportQpsScenarioServiceFileDescriptorSupplier())
              .addMethod(getReportScenarioMethod())
              .build();
        }
      }
    }
    return result;
  }
}
