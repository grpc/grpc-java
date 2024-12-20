package io.grpc.reflection.v1alpha;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: grpc/reflection/v1alpha/reflection.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class ServerReflectionGrpc {

  private ServerReflectionGrpc() {}

  public static final java.lang.String SERVICE_NAME = "grpc.reflection.v1alpha.ServerReflection";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.reflection.v1alpha.ServerReflectionRequest,
      io.grpc.reflection.v1alpha.ServerReflectionResponse> getServerReflectionInfoMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ServerReflectionInfo",
      requestType = io.grpc.reflection.v1alpha.ServerReflectionRequest.class,
      responseType = io.grpc.reflection.v1alpha.ServerReflectionResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<io.grpc.reflection.v1alpha.ServerReflectionRequest,
      io.grpc.reflection.v1alpha.ServerReflectionResponse> getServerReflectionInfoMethod() {
    io.grpc.MethodDescriptor<io.grpc.reflection.v1alpha.ServerReflectionRequest, io.grpc.reflection.v1alpha.ServerReflectionResponse> getServerReflectionInfoMethod;
    if ((getServerReflectionInfoMethod = ServerReflectionGrpc.getServerReflectionInfoMethod) == null) {
      synchronized (ServerReflectionGrpc.class) {
        if ((getServerReflectionInfoMethod = ServerReflectionGrpc.getServerReflectionInfoMethod) == null) {
          ServerReflectionGrpc.getServerReflectionInfoMethod = getServerReflectionInfoMethod =
              io.grpc.MethodDescriptor.<io.grpc.reflection.v1alpha.ServerReflectionRequest, io.grpc.reflection.v1alpha.ServerReflectionResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ServerReflectionInfo"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.reflection.v1alpha.ServerReflectionRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.reflection.v1alpha.ServerReflectionResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServerReflectionMethodDescriptorSupplier("ServerReflectionInfo"))
              .build();
        }
      }
    }
    return getServerReflectionInfoMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServerReflectionStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerReflectionStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerReflectionStub>() {
        @java.lang.Override
        public ServerReflectionStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerReflectionStub(channel, callOptions);
        }
      };
    return ServerReflectionStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ServerReflectionBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerReflectionBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerReflectionBlockingV2Stub>() {
        @java.lang.Override
        public ServerReflectionBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerReflectionBlockingV2Stub(channel, callOptions);
        }
      };
    return ServerReflectionBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServerReflectionBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerReflectionBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerReflectionBlockingStub>() {
        @java.lang.Override
        public ServerReflectionBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerReflectionBlockingStub(channel, callOptions);
        }
      };
    return ServerReflectionBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServerReflectionFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServerReflectionFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServerReflectionFutureStub>() {
        @java.lang.Override
        public ServerReflectionFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServerReflectionFutureStub(channel, callOptions);
        }
      };
    return ServerReflectionFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     * <pre>
     * The reflection service is structured as a bidirectional stream, ensuring
     * all related requests go to a single server.
     * </pre>
     */
    default io.grpc.stub.StreamObserver<io.grpc.reflection.v1alpha.ServerReflectionRequest> serverReflectionInfo(
        io.grpc.stub.StreamObserver<io.grpc.reflection.v1alpha.ServerReflectionResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getServerReflectionInfoMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServerReflection.
   */
  public static abstract class ServerReflectionImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServerReflectionGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServerReflection.
   */
  public static final class ServerReflectionStub
      extends io.grpc.stub.AbstractAsyncStub<ServerReflectionStub> {
    private ServerReflectionStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerReflectionStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerReflectionStub(channel, callOptions);
    }

    /**
     * <pre>
     * The reflection service is structured as a bidirectional stream, ensuring
     * all related requests go to a single server.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.grpc.reflection.v1alpha.ServerReflectionRequest> serverReflectionInfo(
        io.grpc.stub.StreamObserver<io.grpc.reflection.v1alpha.ServerReflectionResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getServerReflectionInfoMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServerReflection.
   */
  public static final class ServerReflectionBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ServerReflectionBlockingV2Stub> {
    private ServerReflectionBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerReflectionBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerReflectionBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * The reflection service is structured as a bidirectional stream, ensuring
     * all related requests go to a single server.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<io.grpc.reflection.v1alpha.ServerReflectionRequest, io.grpc.reflection.v1alpha.ServerReflectionResponse>
        serverReflectionInfo() {
      return io.grpc.stub.ClientCalls.blockingBidiStreamingCall(
          getChannel(), getServerReflectionInfoMethod(), getCallOptions());
    }
  }

  /**
   * A stub to allow clients to do llimited synchronous rpc calls to service ServerReflection.
   */
  public static final class ServerReflectionBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServerReflectionBlockingStub> {
    private ServerReflectionBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerReflectionBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerReflectionBlockingStub(channel, callOptions);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServerReflection.
   */
  public static final class ServerReflectionFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServerReflectionFutureStub> {
    private ServerReflectionFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServerReflectionFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServerReflectionFutureStub(channel, callOptions);
    }
  }

  private static final int METHODID_SERVER_REFLECTION_INFO = 0;

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
        case METHODID_SERVER_REFLECTION_INFO:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.serverReflectionInfo(
              (io.grpc.stub.StreamObserver<io.grpc.reflection.v1alpha.ServerReflectionResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getServerReflectionInfoMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              io.grpc.reflection.v1alpha.ServerReflectionRequest,
              io.grpc.reflection.v1alpha.ServerReflectionResponse>(
                service, METHODID_SERVER_REFLECTION_INFO)))
        .build();
  }

  private static abstract class ServerReflectionBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServerReflectionBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.grpc.reflection.v1alpha.ServerReflectionProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServerReflection");
    }
  }

  private static final class ServerReflectionFileDescriptorSupplier
      extends ServerReflectionBaseDescriptorSupplier {
    ServerReflectionFileDescriptorSupplier() {}
  }

  private static final class ServerReflectionMethodDescriptorSupplier
      extends ServerReflectionBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServerReflectionMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServerReflectionGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServerReflectionFileDescriptorSupplier())
              .addMethod(getServerReflectionInfoMethod())
              .build();
        }
      }
    }
    return result;
  }
}
