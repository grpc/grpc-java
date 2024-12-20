package io.grpc.testing.protobuf;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * A simple service for test.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: io/grpc/testing/protobuf/simpleservice.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class SimpleServiceGrpc {

  private SimpleServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "grpc.testing.SimpleService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.testing.protobuf.SimpleRequest,
      io.grpc.testing.protobuf.SimpleResponse> getUnaryRpcMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UnaryRpc",
      requestType = io.grpc.testing.protobuf.SimpleRequest.class,
      responseType = io.grpc.testing.protobuf.SimpleResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.testing.protobuf.SimpleRequest,
      io.grpc.testing.protobuf.SimpleResponse> getUnaryRpcMethod() {
    io.grpc.MethodDescriptor<io.grpc.testing.protobuf.SimpleRequest, io.grpc.testing.protobuf.SimpleResponse> getUnaryRpcMethod;
    if ((getUnaryRpcMethod = SimpleServiceGrpc.getUnaryRpcMethod) == null) {
      synchronized (SimpleServiceGrpc.class) {
        if ((getUnaryRpcMethod = SimpleServiceGrpc.getUnaryRpcMethod) == null) {
          SimpleServiceGrpc.getUnaryRpcMethod = getUnaryRpcMethod =
              io.grpc.MethodDescriptor.<io.grpc.testing.protobuf.SimpleRequest, io.grpc.testing.protobuf.SimpleResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UnaryRpc"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.testing.protobuf.SimpleRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.testing.protobuf.SimpleResponse.getDefaultInstance()))
              .setSchemaDescriptor(new SimpleServiceMethodDescriptorSupplier("UnaryRpc"))
              .build();
        }
      }
    }
    return getUnaryRpcMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.testing.protobuf.SimpleRequest,
      io.grpc.testing.protobuf.SimpleResponse> getClientStreamingRpcMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ClientStreamingRpc",
      requestType = io.grpc.testing.protobuf.SimpleRequest.class,
      responseType = io.grpc.testing.protobuf.SimpleResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
  public static io.grpc.MethodDescriptor<io.grpc.testing.protobuf.SimpleRequest,
      io.grpc.testing.protobuf.SimpleResponse> getClientStreamingRpcMethod() {
    io.grpc.MethodDescriptor<io.grpc.testing.protobuf.SimpleRequest, io.grpc.testing.protobuf.SimpleResponse> getClientStreamingRpcMethod;
    if ((getClientStreamingRpcMethod = SimpleServiceGrpc.getClientStreamingRpcMethod) == null) {
      synchronized (SimpleServiceGrpc.class) {
        if ((getClientStreamingRpcMethod = SimpleServiceGrpc.getClientStreamingRpcMethod) == null) {
          SimpleServiceGrpc.getClientStreamingRpcMethod = getClientStreamingRpcMethod =
              io.grpc.MethodDescriptor.<io.grpc.testing.protobuf.SimpleRequest, io.grpc.testing.protobuf.SimpleResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ClientStreamingRpc"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.testing.protobuf.SimpleRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.testing.protobuf.SimpleResponse.getDefaultInstance()))
              .setSchemaDescriptor(new SimpleServiceMethodDescriptorSupplier("ClientStreamingRpc"))
              .build();
        }
      }
    }
    return getClientStreamingRpcMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.testing.protobuf.SimpleRequest,
      io.grpc.testing.protobuf.SimpleResponse> getServerStreamingRpcMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ServerStreamingRpc",
      requestType = io.grpc.testing.protobuf.SimpleRequest.class,
      responseType = io.grpc.testing.protobuf.SimpleResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.grpc.testing.protobuf.SimpleRequest,
      io.grpc.testing.protobuf.SimpleResponse> getServerStreamingRpcMethod() {
    io.grpc.MethodDescriptor<io.grpc.testing.protobuf.SimpleRequest, io.grpc.testing.protobuf.SimpleResponse> getServerStreamingRpcMethod;
    if ((getServerStreamingRpcMethod = SimpleServiceGrpc.getServerStreamingRpcMethod) == null) {
      synchronized (SimpleServiceGrpc.class) {
        if ((getServerStreamingRpcMethod = SimpleServiceGrpc.getServerStreamingRpcMethod) == null) {
          SimpleServiceGrpc.getServerStreamingRpcMethod = getServerStreamingRpcMethod =
              io.grpc.MethodDescriptor.<io.grpc.testing.protobuf.SimpleRequest, io.grpc.testing.protobuf.SimpleResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ServerStreamingRpc"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.testing.protobuf.SimpleRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.testing.protobuf.SimpleResponse.getDefaultInstance()))
              .setSchemaDescriptor(new SimpleServiceMethodDescriptorSupplier("ServerStreamingRpc"))
              .build();
        }
      }
    }
    return getServerStreamingRpcMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.testing.protobuf.SimpleRequest,
      io.grpc.testing.protobuf.SimpleResponse> getBidiStreamingRpcMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "BidiStreamingRpc",
      requestType = io.grpc.testing.protobuf.SimpleRequest.class,
      responseType = io.grpc.testing.protobuf.SimpleResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<io.grpc.testing.protobuf.SimpleRequest,
      io.grpc.testing.protobuf.SimpleResponse> getBidiStreamingRpcMethod() {
    io.grpc.MethodDescriptor<io.grpc.testing.protobuf.SimpleRequest, io.grpc.testing.protobuf.SimpleResponse> getBidiStreamingRpcMethod;
    if ((getBidiStreamingRpcMethod = SimpleServiceGrpc.getBidiStreamingRpcMethod) == null) {
      synchronized (SimpleServiceGrpc.class) {
        if ((getBidiStreamingRpcMethod = SimpleServiceGrpc.getBidiStreamingRpcMethod) == null) {
          SimpleServiceGrpc.getBidiStreamingRpcMethod = getBidiStreamingRpcMethod =
              io.grpc.MethodDescriptor.<io.grpc.testing.protobuf.SimpleRequest, io.grpc.testing.protobuf.SimpleResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "BidiStreamingRpc"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.testing.protobuf.SimpleRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.testing.protobuf.SimpleResponse.getDefaultInstance()))
              .setSchemaDescriptor(new SimpleServiceMethodDescriptorSupplier("BidiStreamingRpc"))
              .build();
        }
      }
    }
    return getBidiStreamingRpcMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static SimpleServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<SimpleServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<SimpleServiceStub>() {
        @java.lang.Override
        public SimpleServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new SimpleServiceStub(channel, callOptions);
        }
      };
    return SimpleServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static SimpleServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<SimpleServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<SimpleServiceBlockingV2Stub>() {
        @java.lang.Override
        public SimpleServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new SimpleServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return SimpleServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static SimpleServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<SimpleServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<SimpleServiceBlockingStub>() {
        @java.lang.Override
        public SimpleServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new SimpleServiceBlockingStub(channel, callOptions);
        }
      };
    return SimpleServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static SimpleServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<SimpleServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<SimpleServiceFutureStub>() {
        @java.lang.Override
        public SimpleServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new SimpleServiceFutureStub(channel, callOptions);
        }
      };
    return SimpleServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * A simple service for test.
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Simple unary RPC.
     * </pre>
     */
    default void unaryRpc(io.grpc.testing.protobuf.SimpleRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.protobuf.SimpleResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUnaryRpcMethod(), responseObserver);
    }

    /**
     * <pre>
     * Simple client-to-server streaming RPC.
     * </pre>
     */
    default io.grpc.stub.StreamObserver<io.grpc.testing.protobuf.SimpleRequest> clientStreamingRpc(
        io.grpc.stub.StreamObserver<io.grpc.testing.protobuf.SimpleResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getClientStreamingRpcMethod(), responseObserver);
    }

    /**
     * <pre>
     * Simple server-to-client streaming RPC.
     * </pre>
     */
    default void serverStreamingRpc(io.grpc.testing.protobuf.SimpleRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.protobuf.SimpleResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getServerStreamingRpcMethod(), responseObserver);
    }

    /**
     * <pre>
     * Simple bidirectional streaming RPC.
     * </pre>
     */
    default io.grpc.stub.StreamObserver<io.grpc.testing.protobuf.SimpleRequest> bidiStreamingRpc(
        io.grpc.stub.StreamObserver<io.grpc.testing.protobuf.SimpleResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getBidiStreamingRpcMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service SimpleService.
   * <pre>
   * A simple service for test.
   * </pre>
   */
  public static abstract class SimpleServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return SimpleServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service SimpleService.
   * <pre>
   * A simple service for test.
   * </pre>
   */
  public static final class SimpleServiceStub
      extends io.grpc.stub.AbstractAsyncStub<SimpleServiceStub> {
    private SimpleServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected SimpleServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new SimpleServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Simple unary RPC.
     * </pre>
     */
    public void unaryRpc(io.grpc.testing.protobuf.SimpleRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.protobuf.SimpleResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUnaryRpcMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Simple client-to-server streaming RPC.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.grpc.testing.protobuf.SimpleRequest> clientStreamingRpc(
        io.grpc.stub.StreamObserver<io.grpc.testing.protobuf.SimpleResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncClientStreamingCall(
          getChannel().newCall(getClientStreamingRpcMethod(), getCallOptions()), responseObserver);
    }

    /**
     * <pre>
     * Simple server-to-client streaming RPC.
     * </pre>
     */
    public void serverStreamingRpc(io.grpc.testing.protobuf.SimpleRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.protobuf.SimpleResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getServerStreamingRpcMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Simple bidirectional streaming RPC.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.grpc.testing.protobuf.SimpleRequest> bidiStreamingRpc(
        io.grpc.stub.StreamObserver<io.grpc.testing.protobuf.SimpleResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getBidiStreamingRpcMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service SimpleService.
   * <pre>
   * A simple service for test.
   * </pre>
   */
  public static final class SimpleServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<SimpleServiceBlockingV2Stub> {
    private SimpleServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected SimpleServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new SimpleServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Simple unary RPC.
     * </pre>
     */
    public io.grpc.testing.protobuf.SimpleResponse unaryRpc(io.grpc.testing.protobuf.SimpleRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUnaryRpcMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Simple client-to-server streaming RPC.
     * </pre>
     */
    public io.grpc.stub.BlockingClientCall<io.grpc.testing.protobuf.SimpleRequest, io.grpc.testing.protobuf.SimpleResponse>
        clientStreamingRpc() {
      return io.grpc.stub.ClientCalls.blockingClientStreamingCall(
          getChannel(), getClientStreamingRpcMethod(), getCallOptions());
    }

    /**
     * <pre>
     * Simple server-to-client streaming RPC.
     * </pre>
     */
    public io.grpc.stub.BlockingClientCall<?, io.grpc.testing.protobuf.SimpleResponse>
        serverStreamingRpc(io.grpc.testing.protobuf.SimpleRequest request) throws java.lang.InterruptedException,
            io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getServerStreamingRpcMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Simple bidirectional streaming RPC.
     * </pre>
     */
    public io.grpc.stub.BlockingClientCall<io.grpc.testing.protobuf.SimpleRequest, io.grpc.testing.protobuf.SimpleResponse>
        bidiStreamingRpc() {
      return io.grpc.stub.ClientCalls.blockingBidiStreamingCall(
          getChannel(), getBidiStreamingRpcMethod(), getCallOptions());
    }
  }

  /**
   * A stub to allow clients to do llimited synchronous rpc calls to service SimpleService.
   * <pre>
   * A simple service for test.
   * </pre>
   */
  public static final class SimpleServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<SimpleServiceBlockingStub> {
    private SimpleServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected SimpleServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new SimpleServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Simple unary RPC.
     * </pre>
     */
    public io.grpc.testing.protobuf.SimpleResponse unaryRpc(io.grpc.testing.protobuf.SimpleRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUnaryRpcMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Simple server-to-client streaming RPC.
     * </pre>
     */
    public java.util.Iterator<io.grpc.testing.protobuf.SimpleResponse> serverStreamingRpc(
        io.grpc.testing.protobuf.SimpleRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getServerStreamingRpcMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service SimpleService.
   * <pre>
   * A simple service for test.
   * </pre>
   */
  public static final class SimpleServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<SimpleServiceFutureStub> {
    private SimpleServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected SimpleServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new SimpleServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Simple unary RPC.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.testing.protobuf.SimpleResponse> unaryRpc(
        io.grpc.testing.protobuf.SimpleRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUnaryRpcMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_UNARY_RPC = 0;
  private static final int METHODID_SERVER_STREAMING_RPC = 1;
  private static final int METHODID_CLIENT_STREAMING_RPC = 2;
  private static final int METHODID_BIDI_STREAMING_RPC = 3;

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
        case METHODID_UNARY_RPC:
          serviceImpl.unaryRpc((io.grpc.testing.protobuf.SimpleRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.testing.protobuf.SimpleResponse>) responseObserver);
          break;
        case METHODID_SERVER_STREAMING_RPC:
          serviceImpl.serverStreamingRpc((io.grpc.testing.protobuf.SimpleRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.testing.protobuf.SimpleResponse>) responseObserver);
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
        case METHODID_CLIENT_STREAMING_RPC:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.clientStreamingRpc(
              (io.grpc.stub.StreamObserver<io.grpc.testing.protobuf.SimpleResponse>) responseObserver);
        case METHODID_BIDI_STREAMING_RPC:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.bidiStreamingRpc(
              (io.grpc.stub.StreamObserver<io.grpc.testing.protobuf.SimpleResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getUnaryRpcMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.testing.protobuf.SimpleRequest,
              io.grpc.testing.protobuf.SimpleResponse>(
                service, METHODID_UNARY_RPC)))
        .addMethod(
          getClientStreamingRpcMethod(),
          io.grpc.stub.ServerCalls.asyncClientStreamingCall(
            new MethodHandlers<
              io.grpc.testing.protobuf.SimpleRequest,
              io.grpc.testing.protobuf.SimpleResponse>(
                service, METHODID_CLIENT_STREAMING_RPC)))
        .addMethod(
          getServerStreamingRpcMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              io.grpc.testing.protobuf.SimpleRequest,
              io.grpc.testing.protobuf.SimpleResponse>(
                service, METHODID_SERVER_STREAMING_RPC)))
        .addMethod(
          getBidiStreamingRpcMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              io.grpc.testing.protobuf.SimpleRequest,
              io.grpc.testing.protobuf.SimpleResponse>(
                service, METHODID_BIDI_STREAMING_RPC)))
        .build();
  }

  private static abstract class SimpleServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    SimpleServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.grpc.testing.protobuf.SimpleServiceProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("SimpleService");
    }
  }

  private static final class SimpleServiceFileDescriptorSupplier
      extends SimpleServiceBaseDescriptorSupplier {
    SimpleServiceFileDescriptorSupplier() {}
  }

  private static final class SimpleServiceMethodDescriptorSupplier
      extends SimpleServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    SimpleServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (SimpleServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new SimpleServiceFileDescriptorSupplier())
              .addMethod(getUnaryRpcMethod())
              .addMethod(getClientStreamingRpcMethod())
              .addMethod(getServerStreamingRpcMethod())
              .addMethod(getBidiStreamingRpcMethod())
              .build();
        }
      }
    }
    return result;
  }
}
