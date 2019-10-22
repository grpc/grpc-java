package io.envoyproxy.envoy.api.v2;

import static io.grpc.MethodDescriptor.generateFullMethodName;
import static io.grpc.stub.ClientCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ClientCalls.asyncClientStreamingCall;
import static io.grpc.stub.ClientCalls.asyncServerStreamingCall;
import static io.grpc.stub.ClientCalls.asyncUnaryCall;
import static io.grpc.stub.ClientCalls.blockingServerStreamingCall;
import static io.grpc.stub.ClientCalls.blockingUnaryCall;
import static io.grpc.stub.ClientCalls.futureUnaryCall;
import static io.grpc.stub.ServerCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ServerCalls.asyncClientStreamingCall;
import static io.grpc.stub.ServerCalls.asyncServerStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnaryCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall;

/**
 * <pre>
 * The Envoy instance initiates an RPC at startup to discover a list of
 * listeners. Updates are delivered via streaming from the LDS server and
 * consist of a complete update of all listeners. Existing connections will be
 * allowed to drain from listeners that are no longer present.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: envoy/api/v2/lds.proto")
public final class ListenerDiscoveryServiceGrpc {

  private ListenerDiscoveryServiceGrpc() {}

  public static final String SERVICE_NAME = "envoy.api.v2.ListenerDiscoveryService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest,
      io.envoyproxy.envoy.api.v2.DeltaDiscoveryResponse> getDeltaListenersMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeltaListeners",
      requestType = io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest.class,
      responseType = io.envoyproxy.envoy.api.v2.DeltaDiscoveryResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest,
      io.envoyproxy.envoy.api.v2.DeltaDiscoveryResponse> getDeltaListenersMethod() {
    io.grpc.MethodDescriptor<io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest, io.envoyproxy.envoy.api.v2.DeltaDiscoveryResponse> getDeltaListenersMethod;
    if ((getDeltaListenersMethod = ListenerDiscoveryServiceGrpc.getDeltaListenersMethod) == null) {
      synchronized (ListenerDiscoveryServiceGrpc.class) {
        if ((getDeltaListenersMethod = ListenerDiscoveryServiceGrpc.getDeltaListenersMethod) == null) {
          ListenerDiscoveryServiceGrpc.getDeltaListenersMethod = getDeltaListenersMethod =
              io.grpc.MethodDescriptor.<io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest, io.envoyproxy.envoy.api.v2.DeltaDiscoveryResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeltaListeners"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.api.v2.DeltaDiscoveryResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ListenerDiscoveryServiceMethodDescriptorSupplier("DeltaListeners"))
              .build();
        }
      }
    }
    return getDeltaListenersMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.envoyproxy.envoy.api.v2.DiscoveryRequest,
      io.envoyproxy.envoy.api.v2.DiscoveryResponse> getStreamListenersMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StreamListeners",
      requestType = io.envoyproxy.envoy.api.v2.DiscoveryRequest.class,
      responseType = io.envoyproxy.envoy.api.v2.DiscoveryResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<io.envoyproxy.envoy.api.v2.DiscoveryRequest,
      io.envoyproxy.envoy.api.v2.DiscoveryResponse> getStreamListenersMethod() {
    io.grpc.MethodDescriptor<io.envoyproxy.envoy.api.v2.DiscoveryRequest, io.envoyproxy.envoy.api.v2.DiscoveryResponse> getStreamListenersMethod;
    if ((getStreamListenersMethod = ListenerDiscoveryServiceGrpc.getStreamListenersMethod) == null) {
      synchronized (ListenerDiscoveryServiceGrpc.class) {
        if ((getStreamListenersMethod = ListenerDiscoveryServiceGrpc.getStreamListenersMethod) == null) {
          ListenerDiscoveryServiceGrpc.getStreamListenersMethod = getStreamListenersMethod =
              io.grpc.MethodDescriptor.<io.envoyproxy.envoy.api.v2.DiscoveryRequest, io.envoyproxy.envoy.api.v2.DiscoveryResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StreamListeners"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.api.v2.DiscoveryRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.api.v2.DiscoveryResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ListenerDiscoveryServiceMethodDescriptorSupplier("StreamListeners"))
              .build();
        }
      }
    }
    return getStreamListenersMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.envoyproxy.envoy.api.v2.DiscoveryRequest,
      io.envoyproxy.envoy.api.v2.DiscoveryResponse> getFetchListenersMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "FetchListeners",
      requestType = io.envoyproxy.envoy.api.v2.DiscoveryRequest.class,
      responseType = io.envoyproxy.envoy.api.v2.DiscoveryResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.envoyproxy.envoy.api.v2.DiscoveryRequest,
      io.envoyproxy.envoy.api.v2.DiscoveryResponse> getFetchListenersMethod() {
    io.grpc.MethodDescriptor<io.envoyproxy.envoy.api.v2.DiscoveryRequest, io.envoyproxy.envoy.api.v2.DiscoveryResponse> getFetchListenersMethod;
    if ((getFetchListenersMethod = ListenerDiscoveryServiceGrpc.getFetchListenersMethod) == null) {
      synchronized (ListenerDiscoveryServiceGrpc.class) {
        if ((getFetchListenersMethod = ListenerDiscoveryServiceGrpc.getFetchListenersMethod) == null) {
          ListenerDiscoveryServiceGrpc.getFetchListenersMethod = getFetchListenersMethod =
              io.grpc.MethodDescriptor.<io.envoyproxy.envoy.api.v2.DiscoveryRequest, io.envoyproxy.envoy.api.v2.DiscoveryResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "FetchListeners"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.api.v2.DiscoveryRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.api.v2.DiscoveryResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ListenerDiscoveryServiceMethodDescriptorSupplier("FetchListeners"))
              .build();
        }
      }
    }
    return getFetchListenersMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ListenerDiscoveryServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ListenerDiscoveryServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ListenerDiscoveryServiceStub>() {
        @java.lang.Override
        public ListenerDiscoveryServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ListenerDiscoveryServiceStub(channel, callOptions);
        }
      };
    return ListenerDiscoveryServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ListenerDiscoveryServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ListenerDiscoveryServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ListenerDiscoveryServiceBlockingStub>() {
        @java.lang.Override
        public ListenerDiscoveryServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ListenerDiscoveryServiceBlockingStub(channel, callOptions);
        }
      };
    return ListenerDiscoveryServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ListenerDiscoveryServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ListenerDiscoveryServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ListenerDiscoveryServiceFutureStub>() {
        @java.lang.Override
        public ListenerDiscoveryServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ListenerDiscoveryServiceFutureStub(channel, callOptions);
        }
      };
    return ListenerDiscoveryServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * The Envoy instance initiates an RPC at startup to discover a list of
   * listeners. Updates are delivered via streaming from the LDS server and
   * consist of a complete update of all listeners. Existing connections will be
   * allowed to drain from listeners that are no longer present.
   * </pre>
   */
  public static abstract class ListenerDiscoveryServiceImplBase implements io.grpc.BindableService {

    /**
     */
    public io.grpc.stub.StreamObserver<io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest> deltaListeners(
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.api.v2.DeltaDiscoveryResponse> responseObserver) {
      return asyncUnimplementedStreamingCall(getDeltaListenersMethod(), responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<io.envoyproxy.envoy.api.v2.DiscoveryRequest> streamListeners(
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.api.v2.DiscoveryResponse> responseObserver) {
      return asyncUnimplementedStreamingCall(getStreamListenersMethod(), responseObserver);
    }

    /**
     */
    public void fetchListeners(io.envoyproxy.envoy.api.v2.DiscoveryRequest request,
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.api.v2.DiscoveryResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getFetchListenersMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getDeltaListenersMethod(),
            asyncBidiStreamingCall(
              new MethodHandlers<
                io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest,
                io.envoyproxy.envoy.api.v2.DeltaDiscoveryResponse>(
                  this, METHODID_DELTA_LISTENERS)))
          .addMethod(
            getStreamListenersMethod(),
            asyncBidiStreamingCall(
              new MethodHandlers<
                io.envoyproxy.envoy.api.v2.DiscoveryRequest,
                io.envoyproxy.envoy.api.v2.DiscoveryResponse>(
                  this, METHODID_STREAM_LISTENERS)))
          .addMethod(
            getFetchListenersMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.envoyproxy.envoy.api.v2.DiscoveryRequest,
                io.envoyproxy.envoy.api.v2.DiscoveryResponse>(
                  this, METHODID_FETCH_LISTENERS)))
          .build();
    }
  }

  /**
   * <pre>
   * The Envoy instance initiates an RPC at startup to discover a list of
   * listeners. Updates are delivered via streaming from the LDS server and
   * consist of a complete update of all listeners. Existing connections will be
   * allowed to drain from listeners that are no longer present.
   * </pre>
   */
  public static final class ListenerDiscoveryServiceStub extends io.grpc.stub.AbstractAsyncStub<ListenerDiscoveryServiceStub> {
    private ListenerDiscoveryServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ListenerDiscoveryServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ListenerDiscoveryServiceStub(channel, callOptions);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest> deltaListeners(
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.api.v2.DeltaDiscoveryResponse> responseObserver) {
      return asyncBidiStreamingCall(
          getChannel().newCall(getDeltaListenersMethod(), getCallOptions()), responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<io.envoyproxy.envoy.api.v2.DiscoveryRequest> streamListeners(
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.api.v2.DiscoveryResponse> responseObserver) {
      return asyncBidiStreamingCall(
          getChannel().newCall(getStreamListenersMethod(), getCallOptions()), responseObserver);
    }

    /**
     */
    public void fetchListeners(io.envoyproxy.envoy.api.v2.DiscoveryRequest request,
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.api.v2.DiscoveryResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getFetchListenersMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * <pre>
   * The Envoy instance initiates an RPC at startup to discover a list of
   * listeners. Updates are delivered via streaming from the LDS server and
   * consist of a complete update of all listeners. Existing connections will be
   * allowed to drain from listeners that are no longer present.
   * </pre>
   */
  public static final class ListenerDiscoveryServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<ListenerDiscoveryServiceBlockingStub> {
    private ListenerDiscoveryServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ListenerDiscoveryServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ListenerDiscoveryServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public io.envoyproxy.envoy.api.v2.DiscoveryResponse fetchListeners(io.envoyproxy.envoy.api.v2.DiscoveryRequest request) {
      return blockingUnaryCall(
          getChannel(), getFetchListenersMethod(), getCallOptions(), request);
    }
  }

  /**
   * <pre>
   * The Envoy instance initiates an RPC at startup to discover a list of
   * listeners. Updates are delivered via streaming from the LDS server and
   * consist of a complete update of all listeners. Existing connections will be
   * allowed to drain from listeners that are no longer present.
   * </pre>
   */
  public static final class ListenerDiscoveryServiceFutureStub extends io.grpc.stub.AbstractFutureStub<ListenerDiscoveryServiceFutureStub> {
    private ListenerDiscoveryServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ListenerDiscoveryServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ListenerDiscoveryServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.envoyproxy.envoy.api.v2.DiscoveryResponse> fetchListeners(
        io.envoyproxy.envoy.api.v2.DiscoveryRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getFetchListenersMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_FETCH_LISTENERS = 0;
  private static final int METHODID_DELTA_LISTENERS = 1;
  private static final int METHODID_STREAM_LISTENERS = 2;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final ListenerDiscoveryServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(ListenerDiscoveryServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_FETCH_LISTENERS:
          serviceImpl.fetchListeners((io.envoyproxy.envoy.api.v2.DiscoveryRequest) request,
              (io.grpc.stub.StreamObserver<io.envoyproxy.envoy.api.v2.DiscoveryResponse>) responseObserver);
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
        case METHODID_DELTA_LISTENERS:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.deltaListeners(
              (io.grpc.stub.StreamObserver<io.envoyproxy.envoy.api.v2.DeltaDiscoveryResponse>) responseObserver);
        case METHODID_STREAM_LISTENERS:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.streamListeners(
              (io.grpc.stub.StreamObserver<io.envoyproxy.envoy.api.v2.DiscoveryResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class ListenerDiscoveryServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ListenerDiscoveryServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.envoyproxy.envoy.api.v2.LdsProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ListenerDiscoveryService");
    }
  }

  private static final class ListenerDiscoveryServiceFileDescriptorSupplier
      extends ListenerDiscoveryServiceBaseDescriptorSupplier {
    ListenerDiscoveryServiceFileDescriptorSupplier() {}
  }

  private static final class ListenerDiscoveryServiceMethodDescriptorSupplier
      extends ListenerDiscoveryServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    ListenerDiscoveryServiceMethodDescriptorSupplier(String methodName) {
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
      synchronized (ListenerDiscoveryServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ListenerDiscoveryServiceFileDescriptorSupplier())
              .addMethod(getDeltaListenersMethod())
              .addMethod(getStreamListenersMethod())
              .addMethod(getFetchListenersMethod())
              .build();
        }
      }
    }
    return result;
  }
}
