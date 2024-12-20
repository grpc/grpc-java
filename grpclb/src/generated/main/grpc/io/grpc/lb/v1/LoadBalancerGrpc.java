package io.grpc.lb.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: grpc/lb/v1/load_balancer.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class LoadBalancerGrpc {

  private LoadBalancerGrpc() {}

  public static final java.lang.String SERVICE_NAME = "grpc.lb.v1.LoadBalancer";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.lb.v1.LoadBalanceRequest,
      io.grpc.lb.v1.LoadBalanceResponse> getBalanceLoadMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "BalanceLoad",
      requestType = io.grpc.lb.v1.LoadBalanceRequest.class,
      responseType = io.grpc.lb.v1.LoadBalanceResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<io.grpc.lb.v1.LoadBalanceRequest,
      io.grpc.lb.v1.LoadBalanceResponse> getBalanceLoadMethod() {
    io.grpc.MethodDescriptor<io.grpc.lb.v1.LoadBalanceRequest, io.grpc.lb.v1.LoadBalanceResponse> getBalanceLoadMethod;
    if ((getBalanceLoadMethod = LoadBalancerGrpc.getBalanceLoadMethod) == null) {
      synchronized (LoadBalancerGrpc.class) {
        if ((getBalanceLoadMethod = LoadBalancerGrpc.getBalanceLoadMethod) == null) {
          LoadBalancerGrpc.getBalanceLoadMethod = getBalanceLoadMethod =
              io.grpc.MethodDescriptor.<io.grpc.lb.v1.LoadBalanceRequest, io.grpc.lb.v1.LoadBalanceResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "BalanceLoad"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.lb.v1.LoadBalanceRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.lb.v1.LoadBalanceResponse.getDefaultInstance()))
              .setSchemaDescriptor(new LoadBalancerMethodDescriptorSupplier("BalanceLoad"))
              .build();
        }
      }
    }
    return getBalanceLoadMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static LoadBalancerStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<LoadBalancerStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<LoadBalancerStub>() {
        @java.lang.Override
        public LoadBalancerStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new LoadBalancerStub(channel, callOptions);
        }
      };
    return LoadBalancerStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static LoadBalancerBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<LoadBalancerBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<LoadBalancerBlockingV2Stub>() {
        @java.lang.Override
        public LoadBalancerBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new LoadBalancerBlockingV2Stub(channel, callOptions);
        }
      };
    return LoadBalancerBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static LoadBalancerBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<LoadBalancerBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<LoadBalancerBlockingStub>() {
        @java.lang.Override
        public LoadBalancerBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new LoadBalancerBlockingStub(channel, callOptions);
        }
      };
    return LoadBalancerBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static LoadBalancerFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<LoadBalancerFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<LoadBalancerFutureStub>() {
        @java.lang.Override
        public LoadBalancerFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new LoadBalancerFutureStub(channel, callOptions);
        }
      };
    return LoadBalancerFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     * <pre>
     * Bidirectional rpc to get a list of servers.
     * </pre>
     */
    default io.grpc.stub.StreamObserver<io.grpc.lb.v1.LoadBalanceRequest> balanceLoad(
        io.grpc.stub.StreamObserver<io.grpc.lb.v1.LoadBalanceResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getBalanceLoadMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service LoadBalancer.
   */
  public static abstract class LoadBalancerImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return LoadBalancerGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service LoadBalancer.
   */
  public static final class LoadBalancerStub
      extends io.grpc.stub.AbstractAsyncStub<LoadBalancerStub> {
    private LoadBalancerStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LoadBalancerStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new LoadBalancerStub(channel, callOptions);
    }

    /**
     * <pre>
     * Bidirectional rpc to get a list of servers.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.grpc.lb.v1.LoadBalanceRequest> balanceLoad(
        io.grpc.stub.StreamObserver<io.grpc.lb.v1.LoadBalanceResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getBalanceLoadMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service LoadBalancer.
   */
  public static final class LoadBalancerBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<LoadBalancerBlockingV2Stub> {
    private LoadBalancerBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LoadBalancerBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new LoadBalancerBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Bidirectional rpc to get a list of servers.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<io.grpc.lb.v1.LoadBalanceRequest, io.grpc.lb.v1.LoadBalanceResponse>
        balanceLoad() {
      return io.grpc.stub.ClientCalls.blockingBidiStreamingCall(
          getChannel(), getBalanceLoadMethod(), getCallOptions());
    }
  }

  /**
   * A stub to allow clients to do llimited synchronous rpc calls to service LoadBalancer.
   */
  public static final class LoadBalancerBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<LoadBalancerBlockingStub> {
    private LoadBalancerBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LoadBalancerBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new LoadBalancerBlockingStub(channel, callOptions);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service LoadBalancer.
   */
  public static final class LoadBalancerFutureStub
      extends io.grpc.stub.AbstractFutureStub<LoadBalancerFutureStub> {
    private LoadBalancerFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LoadBalancerFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new LoadBalancerFutureStub(channel, callOptions);
    }
  }

  private static final int METHODID_BALANCE_LOAD = 0;

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
        case METHODID_BALANCE_LOAD:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.balanceLoad(
              (io.grpc.stub.StreamObserver<io.grpc.lb.v1.LoadBalanceResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getBalanceLoadMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              io.grpc.lb.v1.LoadBalanceRequest,
              io.grpc.lb.v1.LoadBalanceResponse>(
                service, METHODID_BALANCE_LOAD)))
        .build();
  }

  private static abstract class LoadBalancerBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    LoadBalancerBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.grpc.lb.v1.LoadBalancerProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("LoadBalancer");
    }
  }

  private static final class LoadBalancerFileDescriptorSupplier
      extends LoadBalancerBaseDescriptorSupplier {
    LoadBalancerFileDescriptorSupplier() {}
  }

  private static final class LoadBalancerMethodDescriptorSupplier
      extends LoadBalancerBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    LoadBalancerMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (LoadBalancerGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new LoadBalancerFileDescriptorSupplier())
              .addMethod(getBalanceLoadMethod())
              .build();
        }
      }
    }
    return result;
  }
}
