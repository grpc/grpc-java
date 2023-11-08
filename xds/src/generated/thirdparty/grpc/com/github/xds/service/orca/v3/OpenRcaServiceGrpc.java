package com.github.xds.service.orca.v3;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * Out-of-band (OOB) load reporting service for the additional load reporting
 * agent that does not sit in the request path. Reports are periodically sampled
 * with sufficient frequency to provide temporal association with requests.
 * OOB reporting compensates the limitation of in-band reporting in revealing
 * costs for backends that do not provide a steady stream of telemetry such as
 * long running stream operations and zero QPS services. This is a server
 * streaming service, client needs to terminate current RPC and initiate
 * a new call to change backend reporting frequency.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: xds/service/orca/v3/orca.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class OpenRcaServiceGrpc {

  private OpenRcaServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "xds.service.orca.v3.OpenRcaService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.github.xds.service.orca.v3.OrcaLoadReportRequest,
      com.github.xds.data.orca.v3.OrcaLoadReport> getStreamCoreMetricsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StreamCoreMetrics",
      requestType = com.github.xds.service.orca.v3.OrcaLoadReportRequest.class,
      responseType = com.github.xds.data.orca.v3.OrcaLoadReport.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<com.github.xds.service.orca.v3.OrcaLoadReportRequest,
      com.github.xds.data.orca.v3.OrcaLoadReport> getStreamCoreMetricsMethod() {
    io.grpc.MethodDescriptor<com.github.xds.service.orca.v3.OrcaLoadReportRequest, com.github.xds.data.orca.v3.OrcaLoadReport> getStreamCoreMetricsMethod;
    if ((getStreamCoreMetricsMethod = OpenRcaServiceGrpc.getStreamCoreMetricsMethod) == null) {
      synchronized (OpenRcaServiceGrpc.class) {
        if ((getStreamCoreMetricsMethod = OpenRcaServiceGrpc.getStreamCoreMetricsMethod) == null) {
          OpenRcaServiceGrpc.getStreamCoreMetricsMethod = getStreamCoreMetricsMethod =
              io.grpc.MethodDescriptor.<com.github.xds.service.orca.v3.OrcaLoadReportRequest, com.github.xds.data.orca.v3.OrcaLoadReport>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StreamCoreMetrics"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.github.xds.service.orca.v3.OrcaLoadReportRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.github.xds.data.orca.v3.OrcaLoadReport.getDefaultInstance()))
              .setSchemaDescriptor(new OpenRcaServiceMethodDescriptorSupplier("StreamCoreMetrics"))
              .build();
        }
      }
    }
    return getStreamCoreMetricsMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static OpenRcaServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OpenRcaServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OpenRcaServiceStub>() {
        @java.lang.Override
        public OpenRcaServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OpenRcaServiceStub(channel, callOptions);
        }
      };
    return OpenRcaServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static OpenRcaServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OpenRcaServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OpenRcaServiceBlockingStub>() {
        @java.lang.Override
        public OpenRcaServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OpenRcaServiceBlockingStub(channel, callOptions);
        }
      };
    return OpenRcaServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static OpenRcaServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<OpenRcaServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<OpenRcaServiceFutureStub>() {
        @java.lang.Override
        public OpenRcaServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new OpenRcaServiceFutureStub(channel, callOptions);
        }
      };
    return OpenRcaServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * Out-of-band (OOB) load reporting service for the additional load reporting
   * agent that does not sit in the request path. Reports are periodically sampled
   * with sufficient frequency to provide temporal association with requests.
   * OOB reporting compensates the limitation of in-band reporting in revealing
   * costs for backends that do not provide a steady stream of telemetry such as
   * long running stream operations and zero QPS services. This is a server
   * streaming service, client needs to terminate current RPC and initiate
   * a new call to change backend reporting frequency.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void streamCoreMetrics(com.github.xds.service.orca.v3.OrcaLoadReportRequest request,
        io.grpc.stub.StreamObserver<com.github.xds.data.orca.v3.OrcaLoadReport> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStreamCoreMetricsMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service OpenRcaService.
   * <pre>
   * Out-of-band (OOB) load reporting service for the additional load reporting
   * agent that does not sit in the request path. Reports are periodically sampled
   * with sufficient frequency to provide temporal association with requests.
   * OOB reporting compensates the limitation of in-band reporting in revealing
   * costs for backends that do not provide a steady stream of telemetry such as
   * long running stream operations and zero QPS services. This is a server
   * streaming service, client needs to terminate current RPC and initiate
   * a new call to change backend reporting frequency.
   * </pre>
   */
  public static abstract class OpenRcaServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return OpenRcaServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service OpenRcaService.
   * <pre>
   * Out-of-band (OOB) load reporting service for the additional load reporting
   * agent that does not sit in the request path. Reports are periodically sampled
   * with sufficient frequency to provide temporal association with requests.
   * OOB reporting compensates the limitation of in-band reporting in revealing
   * costs for backends that do not provide a steady stream of telemetry such as
   * long running stream operations and zero QPS services. This is a server
   * streaming service, client needs to terminate current RPC and initiate
   * a new call to change backend reporting frequency.
   * </pre>
   */
  public static final class OpenRcaServiceStub
      extends io.grpc.stub.AbstractAsyncStub<OpenRcaServiceStub> {
    private OpenRcaServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OpenRcaServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OpenRcaServiceStub(channel, callOptions);
    }

    /**
     */
    public void streamCoreMetrics(com.github.xds.service.orca.v3.OrcaLoadReportRequest request,
        io.grpc.stub.StreamObserver<com.github.xds.data.orca.v3.OrcaLoadReport> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getStreamCoreMetricsMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service OpenRcaService.
   * <pre>
   * Out-of-band (OOB) load reporting service for the additional load reporting
   * agent that does not sit in the request path. Reports are periodically sampled
   * with sufficient frequency to provide temporal association with requests.
   * OOB reporting compensates the limitation of in-band reporting in revealing
   * costs for backends that do not provide a steady stream of telemetry such as
   * long running stream operations and zero QPS services. This is a server
   * streaming service, client needs to terminate current RPC and initiate
   * a new call to change backend reporting frequency.
   * </pre>
   */
  public static final class OpenRcaServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<OpenRcaServiceBlockingStub> {
    private OpenRcaServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OpenRcaServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OpenRcaServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public java.util.Iterator<com.github.xds.data.orca.v3.OrcaLoadReport> streamCoreMetrics(
        com.github.xds.service.orca.v3.OrcaLoadReportRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getStreamCoreMetricsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service OpenRcaService.
   * <pre>
   * Out-of-band (OOB) load reporting service for the additional load reporting
   * agent that does not sit in the request path. Reports are periodically sampled
   * with sufficient frequency to provide temporal association with requests.
   * OOB reporting compensates the limitation of in-band reporting in revealing
   * costs for backends that do not provide a steady stream of telemetry such as
   * long running stream operations and zero QPS services. This is a server
   * streaming service, client needs to terminate current RPC and initiate
   * a new call to change backend reporting frequency.
   * </pre>
   */
  public static final class OpenRcaServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<OpenRcaServiceFutureStub> {
    private OpenRcaServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected OpenRcaServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new OpenRcaServiceFutureStub(channel, callOptions);
    }
  }

  private static final int METHODID_STREAM_CORE_METRICS = 0;

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
        case METHODID_STREAM_CORE_METRICS:
          serviceImpl.streamCoreMetrics((com.github.xds.service.orca.v3.OrcaLoadReportRequest) request,
              (io.grpc.stub.StreamObserver<com.github.xds.data.orca.v3.OrcaLoadReport>) responseObserver);
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
          getStreamCoreMetricsMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              com.github.xds.service.orca.v3.OrcaLoadReportRequest,
              com.github.xds.data.orca.v3.OrcaLoadReport>(
                service, METHODID_STREAM_CORE_METRICS)))
        .build();
  }

  private static abstract class OpenRcaServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    OpenRcaServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.github.xds.service.orca.v3.OrcaProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("OpenRcaService");
    }
  }

  private static final class OpenRcaServiceFileDescriptorSupplier
      extends OpenRcaServiceBaseDescriptorSupplier {
    OpenRcaServiceFileDescriptorSupplier() {}
  }

  private static final class OpenRcaServiceMethodDescriptorSupplier
      extends OpenRcaServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    OpenRcaServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (OpenRcaServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new OpenRcaServiceFileDescriptorSupplier())
              .addMethod(getStreamCoreMetricsMethod())
              .build();
        }
      }
    }
    return result;
  }
}
