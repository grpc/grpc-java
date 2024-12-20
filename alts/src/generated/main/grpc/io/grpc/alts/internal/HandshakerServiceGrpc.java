package io.grpc.alts.internal;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: grpc/gcp/handshaker.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class HandshakerServiceGrpc {

  private HandshakerServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "grpc.gcp.HandshakerService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.alts.internal.HandshakerReq,
      io.grpc.alts.internal.HandshakerResp> getDoHandshakeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DoHandshake",
      requestType = io.grpc.alts.internal.HandshakerReq.class,
      responseType = io.grpc.alts.internal.HandshakerResp.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<io.grpc.alts.internal.HandshakerReq,
      io.grpc.alts.internal.HandshakerResp> getDoHandshakeMethod() {
    io.grpc.MethodDescriptor<io.grpc.alts.internal.HandshakerReq, io.grpc.alts.internal.HandshakerResp> getDoHandshakeMethod;
    if ((getDoHandshakeMethod = HandshakerServiceGrpc.getDoHandshakeMethod) == null) {
      synchronized (HandshakerServiceGrpc.class) {
        if ((getDoHandshakeMethod = HandshakerServiceGrpc.getDoHandshakeMethod) == null) {
          HandshakerServiceGrpc.getDoHandshakeMethod = getDoHandshakeMethod =
              io.grpc.MethodDescriptor.<io.grpc.alts.internal.HandshakerReq, io.grpc.alts.internal.HandshakerResp>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DoHandshake"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.alts.internal.HandshakerReq.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.alts.internal.HandshakerResp.getDefaultInstance()))
              .setSchemaDescriptor(new HandshakerServiceMethodDescriptorSupplier("DoHandshake"))
              .build();
        }
      }
    }
    return getDoHandshakeMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static HandshakerServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<HandshakerServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<HandshakerServiceStub>() {
        @java.lang.Override
        public HandshakerServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new HandshakerServiceStub(channel, callOptions);
        }
      };
    return HandshakerServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static HandshakerServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<HandshakerServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<HandshakerServiceBlockingV2Stub>() {
        @java.lang.Override
        public HandshakerServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new HandshakerServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return HandshakerServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static HandshakerServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<HandshakerServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<HandshakerServiceBlockingStub>() {
        @java.lang.Override
        public HandshakerServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new HandshakerServiceBlockingStub(channel, callOptions);
        }
      };
    return HandshakerServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static HandshakerServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<HandshakerServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<HandshakerServiceFutureStub>() {
        @java.lang.Override
        public HandshakerServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new HandshakerServiceFutureStub(channel, callOptions);
        }
      };
    return HandshakerServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     * <pre>
     * Handshaker service accepts a stream of handshaker request, returning a
     * stream of handshaker response. Client is expected to send exactly one
     * message with either client_start or server_start followed by one or more
     * messages with next. Each time client sends a request, the handshaker
     * service expects to respond. Client does not have to wait for service's
     * response before sending next request.
     * </pre>
     */
    default io.grpc.stub.StreamObserver<io.grpc.alts.internal.HandshakerReq> doHandshake(
        io.grpc.stub.StreamObserver<io.grpc.alts.internal.HandshakerResp> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getDoHandshakeMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service HandshakerService.
   */
  public static abstract class HandshakerServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return HandshakerServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service HandshakerService.
   */
  public static final class HandshakerServiceStub
      extends io.grpc.stub.AbstractAsyncStub<HandshakerServiceStub> {
    private HandshakerServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected HandshakerServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new HandshakerServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Handshaker service accepts a stream of handshaker request, returning a
     * stream of handshaker response. Client is expected to send exactly one
     * message with either client_start or server_start followed by one or more
     * messages with next. Each time client sends a request, the handshaker
     * service expects to respond. Client does not have to wait for service's
     * response before sending next request.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.grpc.alts.internal.HandshakerReq> doHandshake(
        io.grpc.stub.StreamObserver<io.grpc.alts.internal.HandshakerResp> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getDoHandshakeMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service HandshakerService.
   */
  public static final class HandshakerServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<HandshakerServiceBlockingV2Stub> {
    private HandshakerServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected HandshakerServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new HandshakerServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Handshaker service accepts a stream of handshaker request, returning a
     * stream of handshaker response. Client is expected to send exactly one
     * message with either client_start or server_start followed by one or more
     * messages with next. Each time client sends a request, the handshaker
     * service expects to respond. Client does not have to wait for service's
     * response before sending next request.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<io.grpc.alts.internal.HandshakerReq, io.grpc.alts.internal.HandshakerResp>
        doHandshake() {
      return io.grpc.stub.ClientCalls.blockingBidiStreamingCall(
          getChannel(), getDoHandshakeMethod(), getCallOptions());
    }
  }

  /**
   * A stub to allow clients to do llimited synchronous rpc calls to service HandshakerService.
   */
  public static final class HandshakerServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<HandshakerServiceBlockingStub> {
    private HandshakerServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected HandshakerServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new HandshakerServiceBlockingStub(channel, callOptions);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service HandshakerService.
   */
  public static final class HandshakerServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<HandshakerServiceFutureStub> {
    private HandshakerServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected HandshakerServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new HandshakerServiceFutureStub(channel, callOptions);
    }
  }

  private static final int METHODID_DO_HANDSHAKE = 0;

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
        case METHODID_DO_HANDSHAKE:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.doHandshake(
              (io.grpc.stub.StreamObserver<io.grpc.alts.internal.HandshakerResp>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getDoHandshakeMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              io.grpc.alts.internal.HandshakerReq,
              io.grpc.alts.internal.HandshakerResp>(
                service, METHODID_DO_HANDSHAKE)))
        .build();
  }

  private static abstract class HandshakerServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    HandshakerServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.grpc.alts.internal.HandshakerProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("HandshakerService");
    }
  }

  private static final class HandshakerServiceFileDescriptorSupplier
      extends HandshakerServiceBaseDescriptorSupplier {
    HandshakerServiceFileDescriptorSupplier() {}
  }

  private static final class HandshakerServiceMethodDescriptorSupplier
      extends HandshakerServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    HandshakerServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (HandshakerServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new HandshakerServiceFileDescriptorSupplier())
              .addMethod(getDoHandshakeMethod())
              .build();
        }
      }
    }
    return result;
  }
}
