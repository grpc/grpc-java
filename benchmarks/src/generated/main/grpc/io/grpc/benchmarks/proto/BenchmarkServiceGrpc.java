package io.grpc.benchmarks.proto;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: grpc/testing/services.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class BenchmarkServiceGrpc {

  private BenchmarkServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "grpc.testing.BenchmarkService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Messages.SimpleRequest,
      io.grpc.benchmarks.proto.Messages.SimpleResponse> getUnaryCallMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UnaryCall",
      requestType = io.grpc.benchmarks.proto.Messages.SimpleRequest.class,
      responseType = io.grpc.benchmarks.proto.Messages.SimpleResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Messages.SimpleRequest,
      io.grpc.benchmarks.proto.Messages.SimpleResponse> getUnaryCallMethod() {
    io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Messages.SimpleRequest, io.grpc.benchmarks.proto.Messages.SimpleResponse> getUnaryCallMethod;
    if ((getUnaryCallMethod = BenchmarkServiceGrpc.getUnaryCallMethod) == null) {
      synchronized (BenchmarkServiceGrpc.class) {
        if ((getUnaryCallMethod = BenchmarkServiceGrpc.getUnaryCallMethod) == null) {
          BenchmarkServiceGrpc.getUnaryCallMethod = getUnaryCallMethod =
              io.grpc.MethodDescriptor.<io.grpc.benchmarks.proto.Messages.SimpleRequest, io.grpc.benchmarks.proto.Messages.SimpleResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UnaryCall"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.benchmarks.proto.Messages.SimpleRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.benchmarks.proto.Messages.SimpleResponse.getDefaultInstance()))
              .setSchemaDescriptor(new BenchmarkServiceMethodDescriptorSupplier("UnaryCall"))
              .build();
        }
      }
    }
    return getUnaryCallMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Messages.SimpleRequest,
      io.grpc.benchmarks.proto.Messages.SimpleResponse> getStreamingCallMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StreamingCall",
      requestType = io.grpc.benchmarks.proto.Messages.SimpleRequest.class,
      responseType = io.grpc.benchmarks.proto.Messages.SimpleResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Messages.SimpleRequest,
      io.grpc.benchmarks.proto.Messages.SimpleResponse> getStreamingCallMethod() {
    io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Messages.SimpleRequest, io.grpc.benchmarks.proto.Messages.SimpleResponse> getStreamingCallMethod;
    if ((getStreamingCallMethod = BenchmarkServiceGrpc.getStreamingCallMethod) == null) {
      synchronized (BenchmarkServiceGrpc.class) {
        if ((getStreamingCallMethod = BenchmarkServiceGrpc.getStreamingCallMethod) == null) {
          BenchmarkServiceGrpc.getStreamingCallMethod = getStreamingCallMethod =
              io.grpc.MethodDescriptor.<io.grpc.benchmarks.proto.Messages.SimpleRequest, io.grpc.benchmarks.proto.Messages.SimpleResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StreamingCall"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.benchmarks.proto.Messages.SimpleRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.benchmarks.proto.Messages.SimpleResponse.getDefaultInstance()))
              .setSchemaDescriptor(new BenchmarkServiceMethodDescriptorSupplier("StreamingCall"))
              .build();
        }
      }
    }
    return getStreamingCallMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Messages.SimpleRequest,
      io.grpc.benchmarks.proto.Messages.SimpleResponse> getStreamingFromClientMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StreamingFromClient",
      requestType = io.grpc.benchmarks.proto.Messages.SimpleRequest.class,
      responseType = io.grpc.benchmarks.proto.Messages.SimpleResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
  public static io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Messages.SimpleRequest,
      io.grpc.benchmarks.proto.Messages.SimpleResponse> getStreamingFromClientMethod() {
    io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Messages.SimpleRequest, io.grpc.benchmarks.proto.Messages.SimpleResponse> getStreamingFromClientMethod;
    if ((getStreamingFromClientMethod = BenchmarkServiceGrpc.getStreamingFromClientMethod) == null) {
      synchronized (BenchmarkServiceGrpc.class) {
        if ((getStreamingFromClientMethod = BenchmarkServiceGrpc.getStreamingFromClientMethod) == null) {
          BenchmarkServiceGrpc.getStreamingFromClientMethod = getStreamingFromClientMethod =
              io.grpc.MethodDescriptor.<io.grpc.benchmarks.proto.Messages.SimpleRequest, io.grpc.benchmarks.proto.Messages.SimpleResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StreamingFromClient"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.benchmarks.proto.Messages.SimpleRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.benchmarks.proto.Messages.SimpleResponse.getDefaultInstance()))
              .setSchemaDescriptor(new BenchmarkServiceMethodDescriptorSupplier("StreamingFromClient"))
              .build();
        }
      }
    }
    return getStreamingFromClientMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Messages.SimpleRequest,
      io.grpc.benchmarks.proto.Messages.SimpleResponse> getStreamingFromServerMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StreamingFromServer",
      requestType = io.grpc.benchmarks.proto.Messages.SimpleRequest.class,
      responseType = io.grpc.benchmarks.proto.Messages.SimpleResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Messages.SimpleRequest,
      io.grpc.benchmarks.proto.Messages.SimpleResponse> getStreamingFromServerMethod() {
    io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Messages.SimpleRequest, io.grpc.benchmarks.proto.Messages.SimpleResponse> getStreamingFromServerMethod;
    if ((getStreamingFromServerMethod = BenchmarkServiceGrpc.getStreamingFromServerMethod) == null) {
      synchronized (BenchmarkServiceGrpc.class) {
        if ((getStreamingFromServerMethod = BenchmarkServiceGrpc.getStreamingFromServerMethod) == null) {
          BenchmarkServiceGrpc.getStreamingFromServerMethod = getStreamingFromServerMethod =
              io.grpc.MethodDescriptor.<io.grpc.benchmarks.proto.Messages.SimpleRequest, io.grpc.benchmarks.proto.Messages.SimpleResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StreamingFromServer"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.benchmarks.proto.Messages.SimpleRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.benchmarks.proto.Messages.SimpleResponse.getDefaultInstance()))
              .setSchemaDescriptor(new BenchmarkServiceMethodDescriptorSupplier("StreamingFromServer"))
              .build();
        }
      }
    }
    return getStreamingFromServerMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Messages.SimpleRequest,
      io.grpc.benchmarks.proto.Messages.SimpleResponse> getStreamingBothWaysMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StreamingBothWays",
      requestType = io.grpc.benchmarks.proto.Messages.SimpleRequest.class,
      responseType = io.grpc.benchmarks.proto.Messages.SimpleResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Messages.SimpleRequest,
      io.grpc.benchmarks.proto.Messages.SimpleResponse> getStreamingBothWaysMethod() {
    io.grpc.MethodDescriptor<io.grpc.benchmarks.proto.Messages.SimpleRequest, io.grpc.benchmarks.proto.Messages.SimpleResponse> getStreamingBothWaysMethod;
    if ((getStreamingBothWaysMethod = BenchmarkServiceGrpc.getStreamingBothWaysMethod) == null) {
      synchronized (BenchmarkServiceGrpc.class) {
        if ((getStreamingBothWaysMethod = BenchmarkServiceGrpc.getStreamingBothWaysMethod) == null) {
          BenchmarkServiceGrpc.getStreamingBothWaysMethod = getStreamingBothWaysMethod =
              io.grpc.MethodDescriptor.<io.grpc.benchmarks.proto.Messages.SimpleRequest, io.grpc.benchmarks.proto.Messages.SimpleResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StreamingBothWays"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.benchmarks.proto.Messages.SimpleRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.benchmarks.proto.Messages.SimpleResponse.getDefaultInstance()))
              .setSchemaDescriptor(new BenchmarkServiceMethodDescriptorSupplier("StreamingBothWays"))
              .build();
        }
      }
    }
    return getStreamingBothWaysMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static BenchmarkServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<BenchmarkServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<BenchmarkServiceStub>() {
        @java.lang.Override
        public BenchmarkServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new BenchmarkServiceStub(channel, callOptions);
        }
      };
    return BenchmarkServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static BenchmarkServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<BenchmarkServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<BenchmarkServiceBlockingV2Stub>() {
        @java.lang.Override
        public BenchmarkServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new BenchmarkServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return BenchmarkServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static BenchmarkServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<BenchmarkServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<BenchmarkServiceBlockingStub>() {
        @java.lang.Override
        public BenchmarkServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new BenchmarkServiceBlockingStub(channel, callOptions);
        }
      };
    return BenchmarkServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static BenchmarkServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<BenchmarkServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<BenchmarkServiceFutureStub>() {
        @java.lang.Override
        public BenchmarkServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new BenchmarkServiceFutureStub(channel, callOptions);
        }
      };
    return BenchmarkServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     * <pre>
     * One request followed by one response.
     * The server returns the client payload as-is.
     * </pre>
     */
    default void unaryCall(io.grpc.benchmarks.proto.Messages.SimpleRequest request,
        io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUnaryCallMethod(), responseObserver);
    }

    /**
     * <pre>
     * Repeated sequence of one request followed by one response.
     * Should be called streaming ping-pong
     * The server returns the client payload as-is on each response
     * </pre>
     */
    default io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleRequest> streamingCall(
        io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getStreamingCallMethod(), responseObserver);
    }

    /**
     * <pre>
     * Single-sided unbounded streaming from client to server
     * The server returns the client payload as-is once the client does WritesDone
     * </pre>
     */
    default io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleRequest> streamingFromClient(
        io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getStreamingFromClientMethod(), responseObserver);
    }

    /**
     * <pre>
     * Single-sided unbounded streaming from server to client
     * The server repeatedly returns the client payload as-is
     * </pre>
     */
    default void streamingFromServer(io.grpc.benchmarks.proto.Messages.SimpleRequest request,
        io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStreamingFromServerMethod(), responseObserver);
    }

    /**
     * <pre>
     * Two-sided unbounded streaming between server to client
     * Both sides send the content of their own choice to the other
     * </pre>
     */
    default io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleRequest> streamingBothWays(
        io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getStreamingBothWaysMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service BenchmarkService.
   */
  public static abstract class BenchmarkServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return BenchmarkServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service BenchmarkService.
   */
  public static final class BenchmarkServiceStub
      extends io.grpc.stub.AbstractAsyncStub<BenchmarkServiceStub> {
    private BenchmarkServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected BenchmarkServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new BenchmarkServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * One request followed by one response.
     * The server returns the client payload as-is.
     * </pre>
     */
    public void unaryCall(io.grpc.benchmarks.proto.Messages.SimpleRequest request,
        io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUnaryCallMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Repeated sequence of one request followed by one response.
     * Should be called streaming ping-pong
     * The server returns the client payload as-is on each response
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleRequest> streamingCall(
        io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getStreamingCallMethod(), getCallOptions()), responseObserver);
    }

    /**
     * <pre>
     * Single-sided unbounded streaming from client to server
     * The server returns the client payload as-is once the client does WritesDone
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleRequest> streamingFromClient(
        io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncClientStreamingCall(
          getChannel().newCall(getStreamingFromClientMethod(), getCallOptions()), responseObserver);
    }

    /**
     * <pre>
     * Single-sided unbounded streaming from server to client
     * The server repeatedly returns the client payload as-is
     * </pre>
     */
    public void streamingFromServer(io.grpc.benchmarks.proto.Messages.SimpleRequest request,
        io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getStreamingFromServerMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Two-sided unbounded streaming between server to client
     * Both sides send the content of their own choice to the other
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleRequest> streamingBothWays(
        io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getStreamingBothWaysMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service BenchmarkService.
   */
  public static final class BenchmarkServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<BenchmarkServiceBlockingV2Stub> {
    private BenchmarkServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected BenchmarkServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new BenchmarkServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * One request followed by one response.
     * The server returns the client payload as-is.
     * </pre>
     */
    public io.grpc.benchmarks.proto.Messages.SimpleResponse unaryCall(io.grpc.benchmarks.proto.Messages.SimpleRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUnaryCallMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Repeated sequence of one request followed by one response.
     * Should be called streaming ping-pong
     * The server returns the client payload as-is on each response
     * </pre>
     */
    public io.grpc.stub.BlockingClientCall<io.grpc.benchmarks.proto.Messages.SimpleRequest, io.grpc.benchmarks.proto.Messages.SimpleResponse>
        streamingCall() {
      return io.grpc.stub.ClientCalls.blockingBidiStreamingCall(
          getChannel(), getStreamingCallMethod(), getCallOptions());
    }

    /**
     * <pre>
     * Single-sided unbounded streaming from client to server
     * The server returns the client payload as-is once the client does WritesDone
     * </pre>
     */
    public io.grpc.stub.BlockingClientCall<io.grpc.benchmarks.proto.Messages.SimpleRequest, io.grpc.benchmarks.proto.Messages.SimpleResponse>
        streamingFromClient() {
      return io.grpc.stub.ClientCalls.blockingClientStreamingCall(
          getChannel(), getStreamingFromClientMethod(), getCallOptions());
    }

    /**
     * <pre>
     * Single-sided unbounded streaming from server to client
     * The server repeatedly returns the client payload as-is
     * </pre>
     */
    public io.grpc.stub.BlockingClientCall<?, io.grpc.benchmarks.proto.Messages.SimpleResponse>
        streamingFromServer(io.grpc.benchmarks.proto.Messages.SimpleRequest request) throws java.lang.InterruptedException,
            io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getStreamingFromServerMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Two-sided unbounded streaming between server to client
     * Both sides send the content of their own choice to the other
     * </pre>
     */
    public io.grpc.stub.BlockingClientCall<io.grpc.benchmarks.proto.Messages.SimpleRequest, io.grpc.benchmarks.proto.Messages.SimpleResponse>
        streamingBothWays() {
      return io.grpc.stub.ClientCalls.blockingBidiStreamingCall(
          getChannel(), getStreamingBothWaysMethod(), getCallOptions());
    }
  }

  /**
   * A stub to allow clients to do llimited synchronous rpc calls to service BenchmarkService.
   */
  public static final class BenchmarkServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<BenchmarkServiceBlockingStub> {
    private BenchmarkServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected BenchmarkServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new BenchmarkServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * One request followed by one response.
     * The server returns the client payload as-is.
     * </pre>
     */
    public io.grpc.benchmarks.proto.Messages.SimpleResponse unaryCall(io.grpc.benchmarks.proto.Messages.SimpleRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUnaryCallMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Single-sided unbounded streaming from server to client
     * The server repeatedly returns the client payload as-is
     * </pre>
     */
    public java.util.Iterator<io.grpc.benchmarks.proto.Messages.SimpleResponse> streamingFromServer(
        io.grpc.benchmarks.proto.Messages.SimpleRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getStreamingFromServerMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service BenchmarkService.
   */
  public static final class BenchmarkServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<BenchmarkServiceFutureStub> {
    private BenchmarkServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected BenchmarkServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new BenchmarkServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * One request followed by one response.
     * The server returns the client payload as-is.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.benchmarks.proto.Messages.SimpleResponse> unaryCall(
        io.grpc.benchmarks.proto.Messages.SimpleRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUnaryCallMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_UNARY_CALL = 0;
  private static final int METHODID_STREAMING_FROM_SERVER = 1;
  private static final int METHODID_STREAMING_CALL = 2;
  private static final int METHODID_STREAMING_FROM_CLIENT = 3;
  private static final int METHODID_STREAMING_BOTH_WAYS = 4;

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
        case METHODID_UNARY_CALL:
          serviceImpl.unaryCall((io.grpc.benchmarks.proto.Messages.SimpleRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleResponse>) responseObserver);
          break;
        case METHODID_STREAMING_FROM_SERVER:
          serviceImpl.streamingFromServer((io.grpc.benchmarks.proto.Messages.SimpleRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleResponse>) responseObserver);
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
        case METHODID_STREAMING_CALL:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.streamingCall(
              (io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleResponse>) responseObserver);
        case METHODID_STREAMING_FROM_CLIENT:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.streamingFromClient(
              (io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleResponse>) responseObserver);
        case METHODID_STREAMING_BOTH_WAYS:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.streamingBothWays(
              (io.grpc.stub.StreamObserver<io.grpc.benchmarks.proto.Messages.SimpleResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getUnaryCallMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.benchmarks.proto.Messages.SimpleRequest,
              io.grpc.benchmarks.proto.Messages.SimpleResponse>(
                service, METHODID_UNARY_CALL)))
        .addMethod(
          getStreamingCallMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              io.grpc.benchmarks.proto.Messages.SimpleRequest,
              io.grpc.benchmarks.proto.Messages.SimpleResponse>(
                service, METHODID_STREAMING_CALL)))
        .addMethod(
          getStreamingFromClientMethod(),
          io.grpc.stub.ServerCalls.asyncClientStreamingCall(
            new MethodHandlers<
              io.grpc.benchmarks.proto.Messages.SimpleRequest,
              io.grpc.benchmarks.proto.Messages.SimpleResponse>(
                service, METHODID_STREAMING_FROM_CLIENT)))
        .addMethod(
          getStreamingFromServerMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              io.grpc.benchmarks.proto.Messages.SimpleRequest,
              io.grpc.benchmarks.proto.Messages.SimpleResponse>(
                service, METHODID_STREAMING_FROM_SERVER)))
        .addMethod(
          getStreamingBothWaysMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              io.grpc.benchmarks.proto.Messages.SimpleRequest,
              io.grpc.benchmarks.proto.Messages.SimpleResponse>(
                service, METHODID_STREAMING_BOTH_WAYS)))
        .build();
  }

  private static abstract class BenchmarkServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    BenchmarkServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.grpc.benchmarks.proto.Services.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("BenchmarkService");
    }
  }

  private static final class BenchmarkServiceFileDescriptorSupplier
      extends BenchmarkServiceBaseDescriptorSupplier {
    BenchmarkServiceFileDescriptorSupplier() {}
  }

  private static final class BenchmarkServiceMethodDescriptorSupplier
      extends BenchmarkServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    BenchmarkServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (BenchmarkServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new BenchmarkServiceFileDescriptorSupplier())
              .addMethod(getUnaryCallMethod())
              .addMethod(getStreamingCallMethod())
              .addMethod(getStreamingFromClientMethod())
              .addMethod(getStreamingFromServerMethod())
              .addMethod(getStreamingBothWaysMethod())
              .build();
        }
      }
    }
    return result;
  }
}
