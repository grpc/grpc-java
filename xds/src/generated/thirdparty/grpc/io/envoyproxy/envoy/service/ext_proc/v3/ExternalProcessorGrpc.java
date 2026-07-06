package io.envoyproxy.envoy.service.ext_proc.v3;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * A service that can access and modify HTTP requests and responses
 * as part of a filter chain.
 * The overall external processing protocol works like this:
 * 1. The data plane sends to the service information about the HTTP request.
 * 2. The service sends back a ProcessingResponse message that directs
 *    the data plane to either stop processing, continue without it, or send
 *    it the next chunk of the message body.
 * 3. If so requested, the data plane sends the server the message body in
 *    chunks, or the entire body at once. In either case, the server may send
 *    back a ProcessingResponse for each message it receives, or wait for
 *    a certain amount of body chunks received before streaming back the
 *    ProcessingResponse messages.
 * 4. If so requested, the data plane sends the server the HTTP trailers,
 *    and the server sends back a ProcessingResponse.
 * 5. At this point, request processing is done, and we pick up again
 *    at step 1 when the data plane receives a response from the upstream
 *    server.
 * 6. At any point above, if the server closes the gRPC stream cleanly,
 *    then the data plane proceeds without consulting the server.
 * 7. At any point above, if the server closes the gRPC stream with an error,
 *    then the data plane returns a 500 error to the client, unless the filter
 *    was configured to ignore errors.
 * In other words, the process is a request/response conversation, but
 * using a gRPC stream to make it easier for the server to
 * maintain state.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class ExternalProcessorGrpc {

  private ExternalProcessorGrpc() {}

  public static final java.lang.String SERVICE_NAME = "envoy.service.ext_proc.v3.ExternalProcessor";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest,
      io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse> getProcessMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Process",
      requestType = io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest.class,
      responseType = io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest,
      io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse> getProcessMethod() {
    io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest, io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse> getProcessMethod;
    if ((getProcessMethod = ExternalProcessorGrpc.getProcessMethod) == null) {
      synchronized (ExternalProcessorGrpc.class) {
        if ((getProcessMethod = ExternalProcessorGrpc.getProcessMethod) == null) {
          ExternalProcessorGrpc.getProcessMethod = getProcessMethod =
              io.grpc.MethodDescriptor.<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest, io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Process"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ExternalProcessorMethodDescriptorSupplier("Process"))
              .build();
        }
      }
    }
    return getProcessMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ExternalProcessorStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ExternalProcessorStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ExternalProcessorStub>() {
        @java.lang.Override
        public ExternalProcessorStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ExternalProcessorStub(channel, callOptions);
        }
      };
    return ExternalProcessorStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ExternalProcessorBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ExternalProcessorBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ExternalProcessorBlockingV2Stub>() {
        @java.lang.Override
        public ExternalProcessorBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ExternalProcessorBlockingV2Stub(channel, callOptions);
        }
      };
    return ExternalProcessorBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ExternalProcessorBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ExternalProcessorBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ExternalProcessorBlockingStub>() {
        @java.lang.Override
        public ExternalProcessorBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ExternalProcessorBlockingStub(channel, callOptions);
        }
      };
    return ExternalProcessorBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ExternalProcessorFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ExternalProcessorFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ExternalProcessorFutureStub>() {
        @java.lang.Override
        public ExternalProcessorFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ExternalProcessorFutureStub(channel, callOptions);
        }
      };
    return ExternalProcessorFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * A service that can access and modify HTTP requests and responses
   * as part of a filter chain.
   * The overall external processing protocol works like this:
   * 1. The data plane sends to the service information about the HTTP request.
   * 2. The service sends back a ProcessingResponse message that directs
   *    the data plane to either stop processing, continue without it, or send
   *    it the next chunk of the message body.
   * 3. If so requested, the data plane sends the server the message body in
   *    chunks, or the entire body at once. In either case, the server may send
   *    back a ProcessingResponse for each message it receives, or wait for
   *    a certain amount of body chunks received before streaming back the
   *    ProcessingResponse messages.
   * 4. If so requested, the data plane sends the server the HTTP trailers,
   *    and the server sends back a ProcessingResponse.
   * 5. At this point, request processing is done, and we pick up again
   *    at step 1 when the data plane receives a response from the upstream
   *    server.
   * 6. At any point above, if the server closes the gRPC stream cleanly,
   *    then the data plane proceeds without consulting the server.
   * 7. At any point above, if the server closes the gRPC stream with an error,
   *    then the data plane returns a 500 error to the client, unless the filter
   *    was configured to ignore errors.
   * In other words, the process is a request/response conversation, but
   * using a gRPC stream to make it easier for the server to
   * maintain state.
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * This begins the bidirectional stream that the data plane will use to
     * give the server control over what the filter does. The actual
     * protocol is described by the ProcessingRequest and ProcessingResponse
     * messages below.
     * </pre>
     */
    default io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest> process(
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getProcessMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ExternalProcessor.
   * <pre>
   * A service that can access and modify HTTP requests and responses
   * as part of a filter chain.
   * The overall external processing protocol works like this:
   * 1. The data plane sends to the service information about the HTTP request.
   * 2. The service sends back a ProcessingResponse message that directs
   *    the data plane to either stop processing, continue without it, or send
   *    it the next chunk of the message body.
   * 3. If so requested, the data plane sends the server the message body in
   *    chunks, or the entire body at once. In either case, the server may send
   *    back a ProcessingResponse for each message it receives, or wait for
   *    a certain amount of body chunks received before streaming back the
   *    ProcessingResponse messages.
   * 4. If so requested, the data plane sends the server the HTTP trailers,
   *    and the server sends back a ProcessingResponse.
   * 5. At this point, request processing is done, and we pick up again
   *    at step 1 when the data plane receives a response from the upstream
   *    server.
   * 6. At any point above, if the server closes the gRPC stream cleanly,
   *    then the data plane proceeds without consulting the server.
   * 7. At any point above, if the server closes the gRPC stream with an error,
   *    then the data plane returns a 500 error to the client, unless the filter
   *    was configured to ignore errors.
   * In other words, the process is a request/response conversation, but
   * using a gRPC stream to make it easier for the server to
   * maintain state.
   * </pre>
   */
  public static abstract class ExternalProcessorImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ExternalProcessorGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ExternalProcessor.
   * <pre>
   * A service that can access and modify HTTP requests and responses
   * as part of a filter chain.
   * The overall external processing protocol works like this:
   * 1. The data plane sends to the service information about the HTTP request.
   * 2. The service sends back a ProcessingResponse message that directs
   *    the data plane to either stop processing, continue without it, or send
   *    it the next chunk of the message body.
   * 3. If so requested, the data plane sends the server the message body in
   *    chunks, or the entire body at once. In either case, the server may send
   *    back a ProcessingResponse for each message it receives, or wait for
   *    a certain amount of body chunks received before streaming back the
   *    ProcessingResponse messages.
   * 4. If so requested, the data plane sends the server the HTTP trailers,
   *    and the server sends back a ProcessingResponse.
   * 5. At this point, request processing is done, and we pick up again
   *    at step 1 when the data plane receives a response from the upstream
   *    server.
   * 6. At any point above, if the server closes the gRPC stream cleanly,
   *    then the data plane proceeds without consulting the server.
   * 7. At any point above, if the server closes the gRPC stream with an error,
   *    then the data plane returns a 500 error to the client, unless the filter
   *    was configured to ignore errors.
   * In other words, the process is a request/response conversation, but
   * using a gRPC stream to make it easier for the server to
   * maintain state.
   * </pre>
   */
  public static final class ExternalProcessorStub
      extends io.grpc.stub.AbstractAsyncStub<ExternalProcessorStub> {
    private ExternalProcessorStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ExternalProcessorStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ExternalProcessorStub(channel, callOptions);
    }

    /**
     * <pre>
     * This begins the bidirectional stream that the data plane will use to
     * give the server control over what the filter does. The actual
     * protocol is described by the ProcessingRequest and ProcessingResponse
     * messages below.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest> process(
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getProcessMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ExternalProcessor.
   * <pre>
   * A service that can access and modify HTTP requests and responses
   * as part of a filter chain.
   * The overall external processing protocol works like this:
   * 1. The data plane sends to the service information about the HTTP request.
   * 2. The service sends back a ProcessingResponse message that directs
   *    the data plane to either stop processing, continue without it, or send
   *    it the next chunk of the message body.
   * 3. If so requested, the data plane sends the server the message body in
   *    chunks, or the entire body at once. In either case, the server may send
   *    back a ProcessingResponse for each message it receives, or wait for
   *    a certain amount of body chunks received before streaming back the
   *    ProcessingResponse messages.
   * 4. If so requested, the data plane sends the server the HTTP trailers,
   *    and the server sends back a ProcessingResponse.
   * 5. At this point, request processing is done, and we pick up again
   *    at step 1 when the data plane receives a response from the upstream
   *    server.
   * 6. At any point above, if the server closes the gRPC stream cleanly,
   *    then the data plane proceeds without consulting the server.
   * 7. At any point above, if the server closes the gRPC stream with an error,
   *    then the data plane returns a 500 error to the client, unless the filter
   *    was configured to ignore errors.
   * In other words, the process is a request/response conversation, but
   * using a gRPC stream to make it easier for the server to
   * maintain state.
   * </pre>
   */
  public static final class ExternalProcessorBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ExternalProcessorBlockingV2Stub> {
    private ExternalProcessorBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ExternalProcessorBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ExternalProcessorBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * This begins the bidirectional stream that the data plane will use to
     * give the server control over what the filter does. The actual
     * protocol is described by the ProcessingRequest and ProcessingResponse
     * messages below.
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest, io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse>
        process() {
      return io.grpc.stub.ClientCalls.blockingBidiStreamingCall(
          getChannel(), getProcessMethod(), getCallOptions());
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service ExternalProcessor.
   * <pre>
   * A service that can access and modify HTTP requests and responses
   * as part of a filter chain.
   * The overall external processing protocol works like this:
   * 1. The data plane sends to the service information about the HTTP request.
   * 2. The service sends back a ProcessingResponse message that directs
   *    the data plane to either stop processing, continue without it, or send
   *    it the next chunk of the message body.
   * 3. If so requested, the data plane sends the server the message body in
   *    chunks, or the entire body at once. In either case, the server may send
   *    back a ProcessingResponse for each message it receives, or wait for
   *    a certain amount of body chunks received before streaming back the
   *    ProcessingResponse messages.
   * 4. If so requested, the data plane sends the server the HTTP trailers,
   *    and the server sends back a ProcessingResponse.
   * 5. At this point, request processing is done, and we pick up again
   *    at step 1 when the data plane receives a response from the upstream
   *    server.
   * 6. At any point above, if the server closes the gRPC stream cleanly,
   *    then the data plane proceeds without consulting the server.
   * 7. At any point above, if the server closes the gRPC stream with an error,
   *    then the data plane returns a 500 error to the client, unless the filter
   *    was configured to ignore errors.
   * In other words, the process is a request/response conversation, but
   * using a gRPC stream to make it easier for the server to
   * maintain state.
   * </pre>
   */
  public static final class ExternalProcessorBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ExternalProcessorBlockingStub> {
    private ExternalProcessorBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ExternalProcessorBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ExternalProcessorBlockingStub(channel, callOptions);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ExternalProcessor.
   * <pre>
   * A service that can access and modify HTTP requests and responses
   * as part of a filter chain.
   * The overall external processing protocol works like this:
   * 1. The data plane sends to the service information about the HTTP request.
   * 2. The service sends back a ProcessingResponse message that directs
   *    the data plane to either stop processing, continue without it, or send
   *    it the next chunk of the message body.
   * 3. If so requested, the data plane sends the server the message body in
   *    chunks, or the entire body at once. In either case, the server may send
   *    back a ProcessingResponse for each message it receives, or wait for
   *    a certain amount of body chunks received before streaming back the
   *    ProcessingResponse messages.
   * 4. If so requested, the data plane sends the server the HTTP trailers,
   *    and the server sends back a ProcessingResponse.
   * 5. At this point, request processing is done, and we pick up again
   *    at step 1 when the data plane receives a response from the upstream
   *    server.
   * 6. At any point above, if the server closes the gRPC stream cleanly,
   *    then the data plane proceeds without consulting the server.
   * 7. At any point above, if the server closes the gRPC stream with an error,
   *    then the data plane returns a 500 error to the client, unless the filter
   *    was configured to ignore errors.
   * In other words, the process is a request/response conversation, but
   * using a gRPC stream to make it easier for the server to
   * maintain state.
   * </pre>
   */
  public static final class ExternalProcessorFutureStub
      extends io.grpc.stub.AbstractFutureStub<ExternalProcessorFutureStub> {
    private ExternalProcessorFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ExternalProcessorFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ExternalProcessorFutureStub(channel, callOptions);
    }
  }

  private static final int METHODID_PROCESS = 0;

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
        case METHODID_PROCESS:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.process(
              (io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getProcessMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest,
              io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse>(
                service, METHODID_PROCESS)))
        .build();
  }

  private static abstract class ExternalProcessorBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ExternalProcessorBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ExternalProcessor");
    }
  }

  private static final class ExternalProcessorFileDescriptorSupplier
      extends ExternalProcessorBaseDescriptorSupplier {
    ExternalProcessorFileDescriptorSupplier() {}
  }

  private static final class ExternalProcessorMethodDescriptorSupplier
      extends ExternalProcessorBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ExternalProcessorMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ExternalProcessorGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ExternalProcessorFileDescriptorSupplier())
              .addMethod(getProcessMethod())
              .build();
        }
      }
    }
    return result;
  }
}
