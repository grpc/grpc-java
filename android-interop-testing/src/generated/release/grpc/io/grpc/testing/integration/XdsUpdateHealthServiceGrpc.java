package io.grpc.testing.integration;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * A service to remotely control health status of an xDS test server.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: grpc/testing/test.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class XdsUpdateHealthServiceGrpc {

  private XdsUpdateHealthServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "grpc.testing.XdsUpdateHealthService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.testing.integration.EmptyProtos.Empty,
      io.grpc.testing.integration.EmptyProtos.Empty> getSetServingMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SetServing",
      requestType = io.grpc.testing.integration.EmptyProtos.Empty.class,
      responseType = io.grpc.testing.integration.EmptyProtos.Empty.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.testing.integration.EmptyProtos.Empty,
      io.grpc.testing.integration.EmptyProtos.Empty> getSetServingMethod() {
    io.grpc.MethodDescriptor<io.grpc.testing.integration.EmptyProtos.Empty, io.grpc.testing.integration.EmptyProtos.Empty> getSetServingMethod;
    if ((getSetServingMethod = XdsUpdateHealthServiceGrpc.getSetServingMethod) == null) {
      synchronized (XdsUpdateHealthServiceGrpc.class) {
        if ((getSetServingMethod = XdsUpdateHealthServiceGrpc.getSetServingMethod) == null) {
          XdsUpdateHealthServiceGrpc.getSetServingMethod = getSetServingMethod =
              io.grpc.MethodDescriptor.<io.grpc.testing.integration.EmptyProtos.Empty, io.grpc.testing.integration.EmptyProtos.Empty>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SetServing"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  io.grpc.testing.integration.EmptyProtos.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  io.grpc.testing.integration.EmptyProtos.Empty.getDefaultInstance()))
              .build();
        }
      }
    }
    return getSetServingMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.testing.integration.EmptyProtos.Empty,
      io.grpc.testing.integration.EmptyProtos.Empty> getSetNotServingMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SetNotServing",
      requestType = io.grpc.testing.integration.EmptyProtos.Empty.class,
      responseType = io.grpc.testing.integration.EmptyProtos.Empty.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.testing.integration.EmptyProtos.Empty,
      io.grpc.testing.integration.EmptyProtos.Empty> getSetNotServingMethod() {
    io.grpc.MethodDescriptor<io.grpc.testing.integration.EmptyProtos.Empty, io.grpc.testing.integration.EmptyProtos.Empty> getSetNotServingMethod;
    if ((getSetNotServingMethod = XdsUpdateHealthServiceGrpc.getSetNotServingMethod) == null) {
      synchronized (XdsUpdateHealthServiceGrpc.class) {
        if ((getSetNotServingMethod = XdsUpdateHealthServiceGrpc.getSetNotServingMethod) == null) {
          XdsUpdateHealthServiceGrpc.getSetNotServingMethod = getSetNotServingMethod =
              io.grpc.MethodDescriptor.<io.grpc.testing.integration.EmptyProtos.Empty, io.grpc.testing.integration.EmptyProtos.Empty>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SetNotServing"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  io.grpc.testing.integration.EmptyProtos.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  io.grpc.testing.integration.EmptyProtos.Empty.getDefaultInstance()))
              .build();
        }
      }
    }
    return getSetNotServingMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static XdsUpdateHealthServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<XdsUpdateHealthServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<XdsUpdateHealthServiceStub>() {
        @java.lang.Override
        public XdsUpdateHealthServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new XdsUpdateHealthServiceStub(channel, callOptions);
        }
      };
    return XdsUpdateHealthServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static XdsUpdateHealthServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<XdsUpdateHealthServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<XdsUpdateHealthServiceBlockingV2Stub>() {
        @java.lang.Override
        public XdsUpdateHealthServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new XdsUpdateHealthServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return XdsUpdateHealthServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static XdsUpdateHealthServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<XdsUpdateHealthServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<XdsUpdateHealthServiceBlockingStub>() {
        @java.lang.Override
        public XdsUpdateHealthServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new XdsUpdateHealthServiceBlockingStub(channel, callOptions);
        }
      };
    return XdsUpdateHealthServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static XdsUpdateHealthServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<XdsUpdateHealthServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<XdsUpdateHealthServiceFutureStub>() {
        @java.lang.Override
        public XdsUpdateHealthServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new XdsUpdateHealthServiceFutureStub(channel, callOptions);
        }
      };
    return XdsUpdateHealthServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * A service to remotely control health status of an xDS test server.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void setServing(io.grpc.testing.integration.EmptyProtos.Empty request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.EmptyProtos.Empty> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSetServingMethod(), responseObserver);
    }

    /**
     */
    default void setNotServing(io.grpc.testing.integration.EmptyProtos.Empty request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.EmptyProtos.Empty> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSetNotServingMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service XdsUpdateHealthService.
   * <pre>
   * A service to remotely control health status of an xDS test server.
   * </pre>
   */
  public static abstract class XdsUpdateHealthServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return XdsUpdateHealthServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service XdsUpdateHealthService.
   * <pre>
   * A service to remotely control health status of an xDS test server.
   * </pre>
   */
  public static final class XdsUpdateHealthServiceStub
      extends io.grpc.stub.AbstractAsyncStub<XdsUpdateHealthServiceStub> {
    private XdsUpdateHealthServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected XdsUpdateHealthServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new XdsUpdateHealthServiceStub(channel, callOptions);
    }

    /**
     */
    public void setServing(io.grpc.testing.integration.EmptyProtos.Empty request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.EmptyProtos.Empty> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSetServingMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void setNotServing(io.grpc.testing.integration.EmptyProtos.Empty request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.EmptyProtos.Empty> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSetNotServingMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service XdsUpdateHealthService.
   * <pre>
   * A service to remotely control health status of an xDS test server.
   * </pre>
   */
  public static final class XdsUpdateHealthServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<XdsUpdateHealthServiceBlockingV2Stub> {
    private XdsUpdateHealthServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected XdsUpdateHealthServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new XdsUpdateHealthServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public io.grpc.testing.integration.EmptyProtos.Empty setServing(io.grpc.testing.integration.EmptyProtos.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSetServingMethod(), getCallOptions(), request);
    }

    /**
     */
    public io.grpc.testing.integration.EmptyProtos.Empty setNotServing(io.grpc.testing.integration.EmptyProtos.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSetNotServingMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do llimited synchronous rpc calls to service XdsUpdateHealthService.
   * <pre>
   * A service to remotely control health status of an xDS test server.
   * </pre>
   */
  public static final class XdsUpdateHealthServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<XdsUpdateHealthServiceBlockingStub> {
    private XdsUpdateHealthServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected XdsUpdateHealthServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new XdsUpdateHealthServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public io.grpc.testing.integration.EmptyProtos.Empty setServing(io.grpc.testing.integration.EmptyProtos.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSetServingMethod(), getCallOptions(), request);
    }

    /**
     */
    public io.grpc.testing.integration.EmptyProtos.Empty setNotServing(io.grpc.testing.integration.EmptyProtos.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSetNotServingMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service XdsUpdateHealthService.
   * <pre>
   * A service to remotely control health status of an xDS test server.
   * </pre>
   */
  public static final class XdsUpdateHealthServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<XdsUpdateHealthServiceFutureStub> {
    private XdsUpdateHealthServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected XdsUpdateHealthServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new XdsUpdateHealthServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.testing.integration.EmptyProtos.Empty> setServing(
        io.grpc.testing.integration.EmptyProtos.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSetServingMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.testing.integration.EmptyProtos.Empty> setNotServing(
        io.grpc.testing.integration.EmptyProtos.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSetNotServingMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_SET_SERVING = 0;
  private static final int METHODID_SET_NOT_SERVING = 1;

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
        case METHODID_SET_SERVING:
          serviceImpl.setServing((io.grpc.testing.integration.EmptyProtos.Empty) request,
              (io.grpc.stub.StreamObserver<io.grpc.testing.integration.EmptyProtos.Empty>) responseObserver);
          break;
        case METHODID_SET_NOT_SERVING:
          serviceImpl.setNotServing((io.grpc.testing.integration.EmptyProtos.Empty) request,
              (io.grpc.stub.StreamObserver<io.grpc.testing.integration.EmptyProtos.Empty>) responseObserver);
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
          getSetServingMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.testing.integration.EmptyProtos.Empty,
              io.grpc.testing.integration.EmptyProtos.Empty>(
                service, METHODID_SET_SERVING)))
        .addMethod(
          getSetNotServingMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.testing.integration.EmptyProtos.Empty,
              io.grpc.testing.integration.EmptyProtos.Empty>(
                service, METHODID_SET_NOT_SERVING)))
        .build();
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (XdsUpdateHealthServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .addMethod(getSetServingMethod())
              .addMethod(getSetNotServingMethod())
              .build();
        }
      }
    }
    return result;
  }
}
