package io.grpc.s2a.handshaker;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: grpc/gcp/s2a/s2a.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class S2AServiceGrpc {

  private S2AServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "grpc.gcp.s2a.S2AService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.s2a.handshaker.SessionReq,
      io.grpc.s2a.handshaker.SessionResp> getSetUpSessionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SetUpSession",
      requestType = io.grpc.s2a.handshaker.SessionReq.class,
      responseType = io.grpc.s2a.handshaker.SessionResp.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<io.grpc.s2a.handshaker.SessionReq,
      io.grpc.s2a.handshaker.SessionResp> getSetUpSessionMethod() {
    io.grpc.MethodDescriptor<io.grpc.s2a.handshaker.SessionReq, io.grpc.s2a.handshaker.SessionResp> getSetUpSessionMethod;
    if ((getSetUpSessionMethod = S2AServiceGrpc.getSetUpSessionMethod) == null) {
      synchronized (S2AServiceGrpc.class) {
        if ((getSetUpSessionMethod = S2AServiceGrpc.getSetUpSessionMethod) == null) {
          S2AServiceGrpc.getSetUpSessionMethod = getSetUpSessionMethod =
              io.grpc.MethodDescriptor.<io.grpc.s2a.handshaker.SessionReq, io.grpc.s2a.handshaker.SessionResp>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SetUpSession"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.s2a.handshaker.SessionReq.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.s2a.handshaker.SessionResp.getDefaultInstance()))
              .setSchemaDescriptor(new S2AServiceMethodDescriptorSupplier("SetUpSession"))
              .build();
        }
      }
    }
    return getSetUpSessionMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static S2AServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<S2AServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<S2AServiceStub>() {
        @java.lang.Override
        public S2AServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new S2AServiceStub(channel, callOptions);
        }
      };
    return S2AServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static S2AServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<S2AServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<S2AServiceBlockingStub>() {
        @java.lang.Override
        public S2AServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new S2AServiceBlockingStub(channel, callOptions);
        }
      };
    return S2AServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static S2AServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<S2AServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<S2AServiceFutureStub>() {
        @java.lang.Override
        public S2AServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new S2AServiceFutureStub(channel, callOptions);
        }
      };
    return S2AServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     * <pre>
     * SetUpSession is a bidirectional stream used by applications to offload
     * operations from the TLS handshake.
     * </pre>
     */
    default io.grpc.stub.StreamObserver<io.grpc.s2a.handshaker.SessionReq> setUpSession(
        io.grpc.stub.StreamObserver<io.grpc.s2a.handshaker.SessionResp> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getSetUpSessionMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service S2AService.
   */
  public static abstract class S2AServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return S2AServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service S2AService.
   */
  public static final class S2AServiceStub
      extends io.grpc.stub.AbstractAsyncStub<S2AServiceStub> {
    private S2AServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected S2AServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new S2AServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * SetUpSession is a bidirectional stream used by applications to offload
     * operations from the TLS handshake.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.grpc.s2a.handshaker.SessionReq> setUpSession(
        io.grpc.stub.StreamObserver<io.grpc.s2a.handshaker.SessionResp> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getSetUpSessionMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service S2AService.
   */
  public static final class S2AServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<S2AServiceBlockingStub> {
    private S2AServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected S2AServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new S2AServiceBlockingStub(channel, callOptions);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service S2AService.
   */
  public static final class S2AServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<S2AServiceFutureStub> {
    private S2AServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected S2AServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new S2AServiceFutureStub(channel, callOptions);
    }
  }

  private static final int METHODID_SET_UP_SESSION = 0;

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
        case METHODID_SET_UP_SESSION:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.setUpSession(
              (io.grpc.stub.StreamObserver<io.grpc.s2a.handshaker.SessionResp>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getSetUpSessionMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              io.grpc.s2a.handshaker.SessionReq,
              io.grpc.s2a.handshaker.SessionResp>(
                service, METHODID_SET_UP_SESSION)))
        .build();
  }

  private static abstract class S2AServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    S2AServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.grpc.s2a.handshaker.S2AProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("S2AService");
    }
  }

  private static final class S2AServiceFileDescriptorSupplier
      extends S2AServiceBaseDescriptorSupplier {
    S2AServiceFileDescriptorSupplier() {}
  }

  private static final class S2AServiceMethodDescriptorSupplier
      extends S2AServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    S2AServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (S2AServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new S2AServiceFileDescriptorSupplier())
              .addMethod(getSetUpSessionMethod())
              .build();
        }
      }
    }
    return result;
  }
}
