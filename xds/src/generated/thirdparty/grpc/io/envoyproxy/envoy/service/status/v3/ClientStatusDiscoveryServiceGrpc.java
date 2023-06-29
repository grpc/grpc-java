package io.envoyproxy.envoy.service.status.v3;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * CSDS is Client Status Discovery Service. It can be used to get the status of
 * an xDS-compliant client from the management server's point of view. It can
 * also be used to get the current xDS states directly from the client.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: envoy/service/status/v3/csds.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class ClientStatusDiscoveryServiceGrpc {

  private ClientStatusDiscoveryServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "envoy.service.status.v3.ClientStatusDiscoveryService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.status.v3.ClientStatusRequest,
      io.envoyproxy.envoy.service.status.v3.ClientStatusResponse> getStreamClientStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StreamClientStatus",
      requestType = io.envoyproxy.envoy.service.status.v3.ClientStatusRequest.class,
      responseType = io.envoyproxy.envoy.service.status.v3.ClientStatusResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.status.v3.ClientStatusRequest,
      io.envoyproxy.envoy.service.status.v3.ClientStatusResponse> getStreamClientStatusMethod() {
    io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.status.v3.ClientStatusRequest, io.envoyproxy.envoy.service.status.v3.ClientStatusResponse> getStreamClientStatusMethod;
    if ((getStreamClientStatusMethod = ClientStatusDiscoveryServiceGrpc.getStreamClientStatusMethod) == null) {
      synchronized (ClientStatusDiscoveryServiceGrpc.class) {
        if ((getStreamClientStatusMethod = ClientStatusDiscoveryServiceGrpc.getStreamClientStatusMethod) == null) {
          ClientStatusDiscoveryServiceGrpc.getStreamClientStatusMethod = getStreamClientStatusMethod =
              io.grpc.MethodDescriptor.<io.envoyproxy.envoy.service.status.v3.ClientStatusRequest, io.envoyproxy.envoy.service.status.v3.ClientStatusResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StreamClientStatus"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.service.status.v3.ClientStatusRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.service.status.v3.ClientStatusResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ClientStatusDiscoveryServiceMethodDescriptorSupplier("StreamClientStatus"))
              .build();
        }
      }
    }
    return getStreamClientStatusMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.status.v3.ClientStatusRequest,
      io.envoyproxy.envoy.service.status.v3.ClientStatusResponse> getFetchClientStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "FetchClientStatus",
      requestType = io.envoyproxy.envoy.service.status.v3.ClientStatusRequest.class,
      responseType = io.envoyproxy.envoy.service.status.v3.ClientStatusResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.status.v3.ClientStatusRequest,
      io.envoyproxy.envoy.service.status.v3.ClientStatusResponse> getFetchClientStatusMethod() {
    io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.status.v3.ClientStatusRequest, io.envoyproxy.envoy.service.status.v3.ClientStatusResponse> getFetchClientStatusMethod;
    if ((getFetchClientStatusMethod = ClientStatusDiscoveryServiceGrpc.getFetchClientStatusMethod) == null) {
      synchronized (ClientStatusDiscoveryServiceGrpc.class) {
        if ((getFetchClientStatusMethod = ClientStatusDiscoveryServiceGrpc.getFetchClientStatusMethod) == null) {
          ClientStatusDiscoveryServiceGrpc.getFetchClientStatusMethod = getFetchClientStatusMethod =
              io.grpc.MethodDescriptor.<io.envoyproxy.envoy.service.status.v3.ClientStatusRequest, io.envoyproxy.envoy.service.status.v3.ClientStatusResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "FetchClientStatus"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.service.status.v3.ClientStatusRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.service.status.v3.ClientStatusResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ClientStatusDiscoveryServiceMethodDescriptorSupplier("FetchClientStatus"))
              .build();
        }
      }
    }
    return getFetchClientStatusMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ClientStatusDiscoveryServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ClientStatusDiscoveryServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ClientStatusDiscoveryServiceStub>() {
        @java.lang.Override
        public ClientStatusDiscoveryServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ClientStatusDiscoveryServiceStub(channel, callOptions);
        }
      };
    return ClientStatusDiscoveryServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ClientStatusDiscoveryServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ClientStatusDiscoveryServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ClientStatusDiscoveryServiceBlockingStub>() {
        @java.lang.Override
        public ClientStatusDiscoveryServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ClientStatusDiscoveryServiceBlockingStub(channel, callOptions);
        }
      };
    return ClientStatusDiscoveryServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ClientStatusDiscoveryServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ClientStatusDiscoveryServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ClientStatusDiscoveryServiceFutureStub>() {
        @java.lang.Override
        public ClientStatusDiscoveryServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ClientStatusDiscoveryServiceFutureStub(channel, callOptions);
        }
      };
    return ClientStatusDiscoveryServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * CSDS is Client Status Discovery Service. It can be used to get the status of
   * an xDS-compliant client from the management server's point of view. It can
   * also be used to get the current xDS states directly from the client.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.status.v3.ClientStatusRequest> streamClientStatus(
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.status.v3.ClientStatusResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getStreamClientStatusMethod(), responseObserver);
    }

    /**
     */
    default void fetchClientStatus(io.envoyproxy.envoy.service.status.v3.ClientStatusRequest request,
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.status.v3.ClientStatusResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getFetchClientStatusMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ClientStatusDiscoveryService.
   * <pre>
   * CSDS is Client Status Discovery Service. It can be used to get the status of
   * an xDS-compliant client from the management server's point of view. It can
   * also be used to get the current xDS states directly from the client.
   * </pre>
   */
  public static abstract class ClientStatusDiscoveryServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ClientStatusDiscoveryServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ClientStatusDiscoveryService.
   * <pre>
   * CSDS is Client Status Discovery Service. It can be used to get the status of
   * an xDS-compliant client from the management server's point of view. It can
   * also be used to get the current xDS states directly from the client.
   * </pre>
   */
  public static final class ClientStatusDiscoveryServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ClientStatusDiscoveryServiceStub> {
    private ClientStatusDiscoveryServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ClientStatusDiscoveryServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ClientStatusDiscoveryServiceStub(channel, callOptions);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.status.v3.ClientStatusRequest> streamClientStatus(
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.status.v3.ClientStatusResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getStreamClientStatusMethod(), getCallOptions()), responseObserver);
    }

    /**
     */
    public void fetchClientStatus(io.envoyproxy.envoy.service.status.v3.ClientStatusRequest request,
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.status.v3.ClientStatusResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getFetchClientStatusMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ClientStatusDiscoveryService.
   * <pre>
   * CSDS is Client Status Discovery Service. It can be used to get the status of
   * an xDS-compliant client from the management server's point of view. It can
   * also be used to get the current xDS states directly from the client.
   * </pre>
   */
  public static final class ClientStatusDiscoveryServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ClientStatusDiscoveryServiceBlockingStub> {
    private ClientStatusDiscoveryServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ClientStatusDiscoveryServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ClientStatusDiscoveryServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public io.envoyproxy.envoy.service.status.v3.ClientStatusResponse fetchClientStatus(io.envoyproxy.envoy.service.status.v3.ClientStatusRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getFetchClientStatusMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ClientStatusDiscoveryService.
   * <pre>
   * CSDS is Client Status Discovery Service. It can be used to get the status of
   * an xDS-compliant client from the management server's point of view. It can
   * also be used to get the current xDS states directly from the client.
   * </pre>
   */
  public static final class ClientStatusDiscoveryServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ClientStatusDiscoveryServiceFutureStub> {
    private ClientStatusDiscoveryServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ClientStatusDiscoveryServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ClientStatusDiscoveryServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.envoyproxy.envoy.service.status.v3.ClientStatusResponse> fetchClientStatus(
        io.envoyproxy.envoy.service.status.v3.ClientStatusRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getFetchClientStatusMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_FETCH_CLIENT_STATUS = 0;
  private static final int METHODID_STREAM_CLIENT_STATUS = 1;

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
        case METHODID_FETCH_CLIENT_STATUS:
          serviceImpl.fetchClientStatus((io.envoyproxy.envoy.service.status.v3.ClientStatusRequest) request,
              (io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.status.v3.ClientStatusResponse>) responseObserver);
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
        case METHODID_STREAM_CLIENT_STATUS:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.streamClientStatus(
              (io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.status.v3.ClientStatusResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getStreamClientStatusMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              io.envoyproxy.envoy.service.status.v3.ClientStatusRequest,
              io.envoyproxy.envoy.service.status.v3.ClientStatusResponse>(
                service, METHODID_STREAM_CLIENT_STATUS)))
        .addMethod(
          getFetchClientStatusMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.envoyproxy.envoy.service.status.v3.ClientStatusRequest,
              io.envoyproxy.envoy.service.status.v3.ClientStatusResponse>(
                service, METHODID_FETCH_CLIENT_STATUS)))
        .build();
  }

  private static abstract class ClientStatusDiscoveryServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ClientStatusDiscoveryServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.envoyproxy.envoy.service.status.v3.CsdsProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ClientStatusDiscoveryService");
    }
  }

  private static final class ClientStatusDiscoveryServiceFileDescriptorSupplier
      extends ClientStatusDiscoveryServiceBaseDescriptorSupplier {
    ClientStatusDiscoveryServiceFileDescriptorSupplier() {}
  }

  private static final class ClientStatusDiscoveryServiceMethodDescriptorSupplier
      extends ClientStatusDiscoveryServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ClientStatusDiscoveryServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ClientStatusDiscoveryServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ClientStatusDiscoveryServiceFileDescriptorSupplier())
              .addMethod(getStreamClientStatusMethod())
              .addMethod(getFetchClientStatusMethod())
              .build();
        }
      }
    }
    return result;
  }
}
