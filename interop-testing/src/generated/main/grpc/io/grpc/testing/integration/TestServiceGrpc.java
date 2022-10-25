package io.grpc.testing.integration;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * A simple service to test the various types of RPCs and experiment with
 * performance with various types of payload.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: grpc/testing/test.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class TestServiceGrpc {

  private TestServiceGrpc() {}

  public static final String SERVICE_NAME = "grpc.testing.TestService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<EmptyProtos.Empty,
      EmptyProtos.Empty> getEmptyCallMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "EmptyCall",
      requestType = EmptyProtos.Empty.class,
      responseType = EmptyProtos.Empty.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<EmptyProtos.Empty,
      EmptyProtos.Empty> getEmptyCallMethod() {
    io.grpc.MethodDescriptor<EmptyProtos.Empty, EmptyProtos.Empty> getEmptyCallMethod;
    if ((getEmptyCallMethod = TestServiceGrpc.getEmptyCallMethod) == null) {
      synchronized (TestServiceGrpc.class) {
        if ((getEmptyCallMethod = TestServiceGrpc.getEmptyCallMethod) == null) {
          TestServiceGrpc.getEmptyCallMethod = getEmptyCallMethod =
              io.grpc.MethodDescriptor.<EmptyProtos.Empty, EmptyProtos.Empty>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "EmptyCall"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  EmptyProtos.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  EmptyProtos.Empty.getDefaultInstance()))
              .setSchemaDescriptor(new TestServiceMethodDescriptorSupplier("EmptyCall"))
              .build();
        }
      }
    }
    return getEmptyCallMethod;
  }

  private static volatile io.grpc.MethodDescriptor<Messages.SimpleRequest,
      Messages.SimpleResponse> getUnaryCallMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UnaryCall",
      requestType = Messages.SimpleRequest.class,
      responseType = Messages.SimpleResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<Messages.SimpleRequest,
      Messages.SimpleResponse> getUnaryCallMethod() {
    io.grpc.MethodDescriptor<Messages.SimpleRequest, Messages.SimpleResponse> getUnaryCallMethod;
    if ((getUnaryCallMethod = TestServiceGrpc.getUnaryCallMethod) == null) {
      synchronized (TestServiceGrpc.class) {
        if ((getUnaryCallMethod = TestServiceGrpc.getUnaryCallMethod) == null) {
          TestServiceGrpc.getUnaryCallMethod = getUnaryCallMethod =
              io.grpc.MethodDescriptor.<Messages.SimpleRequest, Messages.SimpleResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UnaryCall"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  Messages.SimpleRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  Messages.SimpleResponse.getDefaultInstance()))
              .setSchemaDescriptor(new TestServiceMethodDescriptorSupplier("UnaryCall"))
              .build();
        }
      }
    }
    return getUnaryCallMethod;
  }

  private static volatile io.grpc.MethodDescriptor<Messages.SimpleRequest,
      Messages.SimpleResponse> getCacheableUnaryCallMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CacheableUnaryCall",
      requestType = Messages.SimpleRequest.class,
      responseType = Messages.SimpleResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<Messages.SimpleRequest,
      Messages.SimpleResponse> getCacheableUnaryCallMethod() {
    io.grpc.MethodDescriptor<Messages.SimpleRequest, Messages.SimpleResponse> getCacheableUnaryCallMethod;
    if ((getCacheableUnaryCallMethod = TestServiceGrpc.getCacheableUnaryCallMethod) == null) {
      synchronized (TestServiceGrpc.class) {
        if ((getCacheableUnaryCallMethod = TestServiceGrpc.getCacheableUnaryCallMethod) == null) {
          TestServiceGrpc.getCacheableUnaryCallMethod = getCacheableUnaryCallMethod =
              io.grpc.MethodDescriptor.<Messages.SimpleRequest, Messages.SimpleResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CacheableUnaryCall"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  Messages.SimpleRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  Messages.SimpleResponse.getDefaultInstance()))
              .setSchemaDescriptor(new TestServiceMethodDescriptorSupplier("CacheableUnaryCall"))
              .build();
        }
      }
    }
    return getCacheableUnaryCallMethod;
  }

  private static volatile io.grpc.MethodDescriptor<Messages.StreamingOutputCallRequest,
      Messages.StreamingOutputCallResponse> getStreamingOutputCallMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StreamingOutputCall",
      requestType = Messages.StreamingOutputCallRequest.class,
      responseType = Messages.StreamingOutputCallResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<Messages.StreamingOutputCallRequest,
      Messages.StreamingOutputCallResponse> getStreamingOutputCallMethod() {
    io.grpc.MethodDescriptor<Messages.StreamingOutputCallRequest, Messages.StreamingOutputCallResponse> getStreamingOutputCallMethod;
    if ((getStreamingOutputCallMethod = TestServiceGrpc.getStreamingOutputCallMethod) == null) {
      synchronized (TestServiceGrpc.class) {
        if ((getStreamingOutputCallMethod = TestServiceGrpc.getStreamingOutputCallMethod) == null) {
          TestServiceGrpc.getStreamingOutputCallMethod = getStreamingOutputCallMethod =
              io.grpc.MethodDescriptor.<Messages.StreamingOutputCallRequest, Messages.StreamingOutputCallResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StreamingOutputCall"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  Messages.StreamingOutputCallRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  Messages.StreamingOutputCallResponse.getDefaultInstance()))
              .setSchemaDescriptor(new TestServiceMethodDescriptorSupplier("StreamingOutputCall"))
              .build();
        }
      }
    }
    return getStreamingOutputCallMethod;
  }

  private static volatile io.grpc.MethodDescriptor<Messages.StreamingInputCallRequest,
      Messages.StreamingInputCallResponse> getStreamingInputCallMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StreamingInputCall",
      requestType = Messages.StreamingInputCallRequest.class,
      responseType = Messages.StreamingInputCallResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
  public static io.grpc.MethodDescriptor<Messages.StreamingInputCallRequest,
      Messages.StreamingInputCallResponse> getStreamingInputCallMethod() {
    io.grpc.MethodDescriptor<Messages.StreamingInputCallRequest, Messages.StreamingInputCallResponse> getStreamingInputCallMethod;
    if ((getStreamingInputCallMethod = TestServiceGrpc.getStreamingInputCallMethod) == null) {
      synchronized (TestServiceGrpc.class) {
        if ((getStreamingInputCallMethod = TestServiceGrpc.getStreamingInputCallMethod) == null) {
          TestServiceGrpc.getStreamingInputCallMethod = getStreamingInputCallMethod =
              io.grpc.MethodDescriptor.<Messages.StreamingInputCallRequest, Messages.StreamingInputCallResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StreamingInputCall"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  Messages.StreamingInputCallRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  Messages.StreamingInputCallResponse.getDefaultInstance()))
              .setSchemaDescriptor(new TestServiceMethodDescriptorSupplier("StreamingInputCall"))
              .build();
        }
      }
    }
    return getStreamingInputCallMethod;
  }

  private static volatile io.grpc.MethodDescriptor<Messages.StreamingOutputCallRequest,
      Messages.StreamingOutputCallResponse> getFullDuplexCallMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "FullDuplexCall",
      requestType = Messages.StreamingOutputCallRequest.class,
      responseType = Messages.StreamingOutputCallResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<Messages.StreamingOutputCallRequest,
      Messages.StreamingOutputCallResponse> getFullDuplexCallMethod() {
    io.grpc.MethodDescriptor<Messages.StreamingOutputCallRequest, Messages.StreamingOutputCallResponse> getFullDuplexCallMethod;
    if ((getFullDuplexCallMethod = TestServiceGrpc.getFullDuplexCallMethod) == null) {
      synchronized (TestServiceGrpc.class) {
        if ((getFullDuplexCallMethod = TestServiceGrpc.getFullDuplexCallMethod) == null) {
          TestServiceGrpc.getFullDuplexCallMethod = getFullDuplexCallMethod =
              io.grpc.MethodDescriptor.<Messages.StreamingOutputCallRequest, Messages.StreamingOutputCallResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "FullDuplexCall"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  Messages.StreamingOutputCallRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  Messages.StreamingOutputCallResponse.getDefaultInstance()))
              .setSchemaDescriptor(new TestServiceMethodDescriptorSupplier("FullDuplexCall"))
              .build();
        }
      }
    }
    return getFullDuplexCallMethod;
  }

  private static volatile io.grpc.MethodDescriptor<Messages.StreamingOutputCallRequest,
      Messages.StreamingOutputCallResponse> getHalfDuplexCallMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "HalfDuplexCall",
      requestType = Messages.StreamingOutputCallRequest.class,
      responseType = Messages.StreamingOutputCallResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<Messages.StreamingOutputCallRequest,
      Messages.StreamingOutputCallResponse> getHalfDuplexCallMethod() {
    io.grpc.MethodDescriptor<Messages.StreamingOutputCallRequest, Messages.StreamingOutputCallResponse> getHalfDuplexCallMethod;
    if ((getHalfDuplexCallMethod = TestServiceGrpc.getHalfDuplexCallMethod) == null) {
      synchronized (TestServiceGrpc.class) {
        if ((getHalfDuplexCallMethod = TestServiceGrpc.getHalfDuplexCallMethod) == null) {
          TestServiceGrpc.getHalfDuplexCallMethod = getHalfDuplexCallMethod =
              io.grpc.MethodDescriptor.<Messages.StreamingOutputCallRequest, Messages.StreamingOutputCallResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "HalfDuplexCall"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  Messages.StreamingOutputCallRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  Messages.StreamingOutputCallResponse.getDefaultInstance()))
              .setSchemaDescriptor(new TestServiceMethodDescriptorSupplier("HalfDuplexCall"))
              .build();
        }
      }
    }
    return getHalfDuplexCallMethod;
  }

  private static volatile io.grpc.MethodDescriptor<EmptyProtos.Empty,
      EmptyProtos.Empty> getUnimplementedCallMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UnimplementedCall",
      requestType = EmptyProtos.Empty.class,
      responseType = EmptyProtos.Empty.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<EmptyProtos.Empty,
      EmptyProtos.Empty> getUnimplementedCallMethod() {
    io.grpc.MethodDescriptor<EmptyProtos.Empty, EmptyProtos.Empty> getUnimplementedCallMethod;
    if ((getUnimplementedCallMethod = TestServiceGrpc.getUnimplementedCallMethod) == null) {
      synchronized (TestServiceGrpc.class) {
        if ((getUnimplementedCallMethod = TestServiceGrpc.getUnimplementedCallMethod) == null) {
          TestServiceGrpc.getUnimplementedCallMethod = getUnimplementedCallMethod =
              io.grpc.MethodDescriptor.<EmptyProtos.Empty, EmptyProtos.Empty>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UnimplementedCall"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  EmptyProtos.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  EmptyProtos.Empty.getDefaultInstance()))
              .setSchemaDescriptor(new TestServiceMethodDescriptorSupplier("UnimplementedCall"))
              .build();
        }
      }
    }
    return getUnimplementedCallMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static TestServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<TestServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<TestServiceStub>() {
        @java.lang.Override
        public TestServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new TestServiceStub(channel, callOptions);
        }
      };
    return TestServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static TestServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<TestServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<TestServiceBlockingStub>() {
        @java.lang.Override
        public TestServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new TestServiceBlockingStub(channel, callOptions);
        }
      };
    return TestServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static TestServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<TestServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<TestServiceFutureStub>() {
        @java.lang.Override
        public TestServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new TestServiceFutureStub(channel, callOptions);
        }
      };
    return TestServiceFutureStub.newStub(factory, channel);
  }

  public interface TestServiceAsync {
    /**
     * <pre>
     * One empty request followed by one empty response.
     * </pre>
     */
    default void emptyCall(EmptyProtos.Empty request,
                           io.grpc.stub.StreamObserver<EmptyProtos.Empty> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getEmptyCallMethod(), responseObserver);
    }

    /**
     * <pre>
     * One request followed by one response.
     * </pre>
     */
    default void unaryCall(Messages.SimpleRequest request,
                           io.grpc.stub.StreamObserver<Messages.SimpleResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUnaryCallMethod(), responseObserver);
    }

    /**
     * <pre>
     * One request followed by one response. Response has cache control
     * headers set such that a caching HTTP proxy (such as GFE) can
     * satisfy subsequent requests.
     * </pre>
     */
    default void cacheableUnaryCall(Messages.SimpleRequest request,
                                    io.grpc.stub.StreamObserver<Messages.SimpleResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCacheableUnaryCallMethod(), responseObserver);
    }

    /**
     * <pre>
     * One request followed by a sequence of responses (streamed download).
     * The server returns the payload with client desired type and sizes.
     * </pre>
     */
    default void streamingOutputCall(Messages.StreamingOutputCallRequest request,
                                     io.grpc.stub.StreamObserver<Messages.StreamingOutputCallResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStreamingOutputCallMethod(), responseObserver);
    }

    /**
     * <pre>
     * A sequence of requests followed by one response (streamed upload).
     * The server returns the aggregated size of client payload as the result.
     * </pre>
     */
    default io.grpc.stub.StreamObserver<Messages.StreamingInputCallRequest> streamingInputCall(
        io.grpc.stub.StreamObserver<Messages.StreamingInputCallResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getStreamingInputCallMethod(), responseObserver);
    }

    /**
     * <pre>
     * A sequence of requests with each request served by the server immediately.
     * As one request could lead to multiple responses, this interface
     * demonstrates the idea of full duplexing.
     * </pre>
     */
    default io.grpc.stub.StreamObserver<Messages.StreamingOutputCallRequest> fullDuplexCall(
        io.grpc.stub.StreamObserver<Messages.StreamingOutputCallResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getFullDuplexCallMethod(), responseObserver);
    }

    /**
     * <pre>
     * A sequence of requests followed by a sequence of responses.
     * The server buffers all the client requests and then serves them in order. A
     * stream of responses are returned to the client when the server starts with
     * first request.
     * </pre>
     */
    default io.grpc.stub.StreamObserver<Messages.StreamingOutputCallRequest> halfDuplexCall(
        io.grpc.stub.StreamObserver<Messages.StreamingOutputCallResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getHalfDuplexCallMethod(), responseObserver);
    }

    /**
     * <pre>
     * The test server will not implement this method. It will be used
     * to test the behavior when clients call unimplemented methods.
     * </pre>
     */
    default void unimplementedCall(EmptyProtos.Empty request,
                                   io.grpc.stub.StreamObserver<EmptyProtos.Empty> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUnimplementedCallMethod(), responseObserver);
    }
  }

  /**
   * <pre>
   * A simple service to test the various types of RPCs and experiment with
   * performance with various types of payload.
   * </pre>
   */
  public static abstract class TestServiceImplBase
      implements io.grpc.BindableService, TestServiceAsync {

    /**
     * <pre>
     * One empty request followed by one empty response.
     * </pre>
     */
    @Override
    public void emptyCall(EmptyProtos.Empty request,
                          io.grpc.stub.StreamObserver<EmptyProtos.Empty> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getEmptyCallMethod(), responseObserver);
    }

    /**
     * <pre>
     * One request followed by one response.
     * </pre>
     */
    @Override
    public void unaryCall(Messages.SimpleRequest request,
                          io.grpc.stub.StreamObserver<Messages.SimpleResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUnaryCallMethod(), responseObserver);
    }

    /**
     * <pre>
     * One request followed by one response. Response has cache control
     * headers set such that a caching HTTP proxy (such as GFE) can
     * satisfy subsequent requests.
     * </pre>
     */
    @Override
    public void cacheableUnaryCall(Messages.SimpleRequest request,
                                   io.grpc.stub.StreamObserver<Messages.SimpleResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCacheableUnaryCallMethod(), responseObserver);
    }

    /**
     * <pre>
     * One request followed by a sequence of responses (streamed download).
     * The server returns the payload with client desired type and sizes.
     * </pre>
     */
    @Override
    public void streamingOutputCall(Messages.StreamingOutputCallRequest request,
                                    io.grpc.stub.StreamObserver<Messages.StreamingOutputCallResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStreamingOutputCallMethod(), responseObserver);
    }

    /**
     * <pre>
     * A sequence of requests followed by one response (streamed upload).
     * The server returns the aggregated size of client payload as the result.
     * </pre>
     */
    @Override
    public io.grpc.stub.StreamObserver<Messages.StreamingInputCallRequest> streamingInputCall(
        io.grpc.stub.StreamObserver<Messages.StreamingInputCallResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getStreamingInputCallMethod(), responseObserver);
    }

    /**
     * <pre>
     * A sequence of requests with each request served by the server immediately.
     * As one request could lead to multiple responses, this interface
     * demonstrates the idea of full duplexing.
     * </pre>
     */
    @Override
    public io.grpc.stub.StreamObserver<Messages.StreamingOutputCallRequest> fullDuplexCall(
        io.grpc.stub.StreamObserver<Messages.StreamingOutputCallResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getFullDuplexCallMethod(), responseObserver);
    }

    /**
     * <pre>
     * A sequence of requests followed by a sequence of responses.
     * The server buffers all the client requests and then serves them in order. A
     * stream of responses are returned to the client when the server starts with
     * first request.
     * </pre>
     */
    @Override
    public io.grpc.stub.StreamObserver<Messages.StreamingOutputCallRequest> halfDuplexCall(
        io.grpc.stub.StreamObserver<Messages.StreamingOutputCallResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getHalfDuplexCallMethod(), responseObserver);
    }

    /**
     * <pre>
     * The test server will not implement this method. It will be used
     * to test the behavior when clients call unimplemented methods.
     * </pre>
     */
    @Override
    public void unimplementedCall(EmptyProtos.Empty request,
                                  io.grpc.stub.StreamObserver<EmptyProtos.Empty> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUnimplementedCallMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getEmptyCallMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                EmptyProtos.Empty,
                EmptyProtos.Empty>(
                  this, METHODID_EMPTY_CALL)))
          .addMethod(
            getUnaryCallMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                Messages.SimpleRequest,
                Messages.SimpleResponse>(
                  this, METHODID_UNARY_CALL)))
          .addMethod(
            getCacheableUnaryCallMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                Messages.SimpleRequest,
                Messages.SimpleResponse>(
                  this, METHODID_CACHEABLE_UNARY_CALL)))
          .addMethod(
            getStreamingOutputCallMethod(),
            io.grpc.stub.ServerCalls.asyncServerStreamingCall(
              new MethodHandlers<
                Messages.StreamingOutputCallRequest,
                Messages.StreamingOutputCallResponse>(
                  this, METHODID_STREAMING_OUTPUT_CALL)))
          .addMethod(
            getStreamingInputCallMethod(),
            io.grpc.stub.ServerCalls.asyncClientStreamingCall(
              new MethodHandlers<
                Messages.StreamingInputCallRequest,
                Messages.StreamingInputCallResponse>(
                  this, METHODID_STREAMING_INPUT_CALL)))
          .addMethod(
            getFullDuplexCallMethod(),
            io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
              new MethodHandlers<
                Messages.StreamingOutputCallRequest,
                Messages.StreamingOutputCallResponse>(
                  this, METHODID_FULL_DUPLEX_CALL)))
          .addMethod(
            getHalfDuplexCallMethod(),
            io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
              new MethodHandlers<
                Messages.StreamingOutputCallRequest,
                Messages.StreamingOutputCallResponse>(
                  this, METHODID_HALF_DUPLEX_CALL)))
          .addMethod(
            getUnimplementedCallMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                EmptyProtos.Empty,
                EmptyProtos.Empty>(
                  this, METHODID_UNIMPLEMENTED_CALL)))
          .build();
    }
  }

  /**
   * <pre>
   * A simple service to test the various types of RPCs and experiment with
   * performance with various types of payload.
   * </pre>
   */
  public static final class TestServiceStub extends io.grpc.stub.AbstractAsyncStub<TestServiceStub>
      implements TestServiceAsync {
    private TestServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected TestServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new TestServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * One empty request followed by one empty response.
     * </pre>
     */
    public void emptyCall(EmptyProtos.Empty request,
        io.grpc.stub.StreamObserver<EmptyProtos.Empty> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getEmptyCallMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * One request followed by one response.
     * </pre>
     */
    public void unaryCall(Messages.SimpleRequest request,
                          io.grpc.stub.StreamObserver<Messages.SimpleResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUnaryCallMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * One request followed by one response. Response has cache control
     * headers set such that a caching HTTP proxy (such as GFE) can
     * satisfy subsequent requests.
     * </pre>
     */
    public void cacheableUnaryCall(Messages.SimpleRequest request,
                                   io.grpc.stub.StreamObserver<Messages.SimpleResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCacheableUnaryCallMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * One request followed by a sequence of responses (streamed download).
     * The server returns the payload with client desired type and sizes.
     * </pre>
     */
    public void streamingOutputCall(Messages.StreamingOutputCallRequest request,
                                    io.grpc.stub.StreamObserver<Messages.StreamingOutputCallResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getStreamingOutputCallMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * A sequence of requests followed by one response (streamed upload).
     * The server returns the aggregated size of client payload as the result.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<Messages.StreamingInputCallRequest> streamingInputCall(
        io.grpc.stub.StreamObserver<Messages.StreamingInputCallResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncClientStreamingCall(
          getChannel().newCall(getStreamingInputCallMethod(), getCallOptions()), responseObserver);
    }

    /**
     * <pre>
     * A sequence of requests with each request served by the server immediately.
     * As one request could lead to multiple responses, this interface
     * demonstrates the idea of full duplexing.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<Messages.StreamingOutputCallRequest> fullDuplexCall(
        io.grpc.stub.StreamObserver<Messages.StreamingOutputCallResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getFullDuplexCallMethod(), getCallOptions()), responseObserver);
    }

    /**
     * <pre>
     * A sequence of requests followed by a sequence of responses.
     * The server buffers all the client requests and then serves them in order. A
     * stream of responses are returned to the client when the server starts with
     * first request.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<Messages.StreamingOutputCallRequest> halfDuplexCall(
        io.grpc.stub.StreamObserver<Messages.StreamingOutputCallResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getHalfDuplexCallMethod(), getCallOptions()), responseObserver);
    }

    /**
     * <pre>
     * The test server will not implement this method. It will be used
     * to test the behavior when clients call unimplemented methods.
     * </pre>
     */
    public void unimplementedCall(EmptyProtos.Empty request,
        io.grpc.stub.StreamObserver<EmptyProtos.Empty> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUnimplementedCallMethod(), getCallOptions()), request, responseObserver);
    }
  }

  public interface TestServiceBlocking {
    /**
     * <pre>
     * One empty request followed by one empty response.
     * </pre>
     */
    default EmptyProtos.Empty emptyCall(EmptyProtos.Empty request) {
      throw new UnsupportedOperationException();
    }

    /**
     * <pre>
     * One request followed by one response.
     * </pre>
     */
    default Messages.SimpleResponse unaryCall(Messages.SimpleRequest request) {
      throw new UnsupportedOperationException();
    }

    /**
     * <pre>
     * One request followed by one response. Response has cache control
     * headers set such that a caching HTTP proxy (such as GFE) can
     * satisfy subsequent requests.
     * </pre>
     */
    default Messages.SimpleResponse cacheableUnaryCall(Messages.SimpleRequest request) {
      throw new UnsupportedOperationException();
    }

    /**
     * <pre>
     * One request followed by a sequence of responses (streamed download).
     * The server returns the payload with client desired type and sizes.
     * </pre>
     */
    default java.util.Iterator<Messages.StreamingOutputCallResponse> streamingOutputCall(
        Messages.StreamingOutputCallRequest request) {
      throw new UnsupportedOperationException();
    }

    /**
     * <pre>
     * The test server will not implement this method. It will be used
     * to test the behavior when clients call unimplemented methods.
     * </pre>
     */
    default EmptyProtos.Empty unimplementedCall(EmptyProtos.Empty request) {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * <pre>
   * A simple service to test the various types of RPCs and experiment with
   * performance with various types of payload.
   * </pre>
   */
  public static final class TestServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<TestServiceBlockingStub>
      implements TestServiceBlocking {
    private TestServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected TestServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new TestServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * One empty request followed by one empty response.
     * </pre>
     */
    @Override
    public EmptyProtos.Empty emptyCall(EmptyProtos.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getEmptyCallMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * One request followed by one response.
     * </pre>
     */
    @Override
    public Messages.SimpleResponse unaryCall(Messages.SimpleRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUnaryCallMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * One request followed by one response. Response has cache control
     * headers set such that a caching HTTP proxy (such as GFE) can
     * satisfy subsequent requests.
     * </pre>
     */
    @Override
    public Messages.SimpleResponse cacheableUnaryCall(Messages.SimpleRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCacheableUnaryCallMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * One request followed by a sequence of responses (streamed download).
     * The server returns the payload with client desired type and sizes.
     * </pre>
     */
    @Override
    public java.util.Iterator<Messages.StreamingOutputCallResponse> streamingOutputCall(
        Messages.StreamingOutputCallRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getStreamingOutputCallMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * The test server will not implement this method. It will be used
     * to test the behavior when clients call unimplemented methods.
     * </pre>
     */
    @Override
    public EmptyProtos.Empty unimplementedCall(EmptyProtos.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUnimplementedCallMethod(), getCallOptions(), request);
    }
  }

  public interface TestServiceFuture {
    /**
     * <pre>
     * One empty request followed by one empty response.
     * </pre>
     */
    default com.google.common.util.concurrent.ListenableFuture<EmptyProtos.Empty> emptyCall(
        EmptyProtos.Empty request) {
      throw new UnsupportedOperationException();
    }

    /**
     * <pre>
     * One request followed by one response.
     * </pre>
     */
    default com.google.common.util.concurrent.ListenableFuture<Messages.SimpleResponse> unaryCall(
        Messages.SimpleRequest request) {
      throw new UnsupportedOperationException();
    }

    /**
     * <pre>
     * One request followed by one response. Response has cache control
     * headers set such that a caching HTTP proxy (such as GFE) can
     * satisfy subsequent requests.
     * </pre>
     */
    default com.google.common.util.concurrent.ListenableFuture<Messages.SimpleResponse> cacheableUnaryCall(
        Messages.SimpleRequest request) {
      throw new UnsupportedOperationException();
    }

    /**
     * <pre>
     * The test server will not implement this method. It will be used
     * to test the behavior when clients call unimplemented methods.
     * </pre>
     */
    default com.google.common.util.concurrent.ListenableFuture<EmptyProtos.Empty> unimplementedCall(
        EmptyProtos.Empty request) {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * <pre>
   * A simple service to test the various types of RPCs and experiment with
   * performance with various types of payload.
   * </pre>
   */
  public static final class TestServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<TestServiceFutureStub>
      implements TestServiceFuture {
    private TestServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected TestServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new TestServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * One empty request followed by one empty response.
     * </pre>
     */
    @Override
    public com.google.common.util.concurrent.ListenableFuture<EmptyProtos.Empty> emptyCall(
        EmptyProtos.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getEmptyCallMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * One request followed by one response.
     * </pre>
     */
    @Override
    public com.google.common.util.concurrent.ListenableFuture<Messages.SimpleResponse> unaryCall(
        Messages.SimpleRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUnaryCallMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * One request followed by one response. Response has cache control
     * headers set such that a caching HTTP proxy (such as GFE) can
     * satisfy subsequent requests.
     * </pre>
     */
    @Override
    public com.google.common.util.concurrent.ListenableFuture<Messages.SimpleResponse> cacheableUnaryCall(
        Messages.SimpleRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCacheableUnaryCallMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * The test server will not implement this method. It will be used
     * to test the behavior when clients call unimplemented methods.
     * </pre>
     */
    @Override
    public com.google.common.util.concurrent.ListenableFuture<EmptyProtos.Empty> unimplementedCall(
        EmptyProtos.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUnimplementedCallMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_EMPTY_CALL = 0;
  private static final int METHODID_UNARY_CALL = 1;
  private static final int METHODID_CACHEABLE_UNARY_CALL = 2;
  private static final int METHODID_STREAMING_OUTPUT_CALL = 3;
  private static final int METHODID_UNIMPLEMENTED_CALL = 4;
  private static final int METHODID_STREAMING_INPUT_CALL = 5;
  private static final int METHODID_FULL_DUPLEX_CALL = 6;
  private static final int METHODID_HALF_DUPLEX_CALL = 7;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final TestServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(TestServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_EMPTY_CALL:
          serviceImpl.emptyCall((EmptyProtos.Empty) request,
              (io.grpc.stub.StreamObserver<EmptyProtos.Empty>) responseObserver);
          break;
        case METHODID_UNARY_CALL:
          serviceImpl.unaryCall((Messages.SimpleRequest) request,
              (io.grpc.stub.StreamObserver<Messages.SimpleResponse>) responseObserver);
          break;
        case METHODID_CACHEABLE_UNARY_CALL:
          serviceImpl.cacheableUnaryCall((Messages.SimpleRequest) request,
              (io.grpc.stub.StreamObserver<Messages.SimpleResponse>) responseObserver);
          break;
        case METHODID_STREAMING_OUTPUT_CALL:
          serviceImpl.streamingOutputCall((Messages.StreamingOutputCallRequest) request,
              (io.grpc.stub.StreamObserver<Messages.StreamingOutputCallResponse>) responseObserver);
          break;
        case METHODID_UNIMPLEMENTED_CALL:
          serviceImpl.unimplementedCall((EmptyProtos.Empty) request,
              (io.grpc.stub.StreamObserver<EmptyProtos.Empty>) responseObserver);
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
        case METHODID_STREAMING_INPUT_CALL:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.streamingInputCall(
              (io.grpc.stub.StreamObserver<Messages.StreamingInputCallResponse>) responseObserver);
        case METHODID_FULL_DUPLEX_CALL:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.fullDuplexCall(
              (io.grpc.stub.StreamObserver<Messages.StreamingOutputCallResponse>) responseObserver);
        case METHODID_HALF_DUPLEX_CALL:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.halfDuplexCall(
              (io.grpc.stub.StreamObserver<Messages.StreamingOutputCallResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class TestServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    TestServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return Test.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("TestService");
    }
  }

  private static final class TestServiceFileDescriptorSupplier
      extends TestServiceBaseDescriptorSupplier {
    TestServiceFileDescriptorSupplier() {}
  }

  private static final class TestServiceMethodDescriptorSupplier
      extends TestServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    TestServiceMethodDescriptorSupplier(String methodName) {
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
      synchronized (TestServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new TestServiceFileDescriptorSupplier())
              .addMethod(getEmptyCallMethod())
              .addMethod(getUnaryCallMethod())
              .addMethod(getCacheableUnaryCallMethod())
              .addMethod(getStreamingOutputCallMethod())
              .addMethod(getStreamingInputCallMethod())
              .addMethod(getFullDuplexCallMethod())
              .addMethod(getHalfDuplexCallMethod())
              .addMethod(getUnimplementedCallMethod())
              .build();
        }
      }
    }
    return result;
  }
}
