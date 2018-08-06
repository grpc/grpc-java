package io.grpc.testing.compiler.nano;

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

import java.io.IOException;

/**
 * <pre>
 * Test service that supports all call types.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.9.0)",
    comments = "Source: test.proto")
public final class TestServiceGrpc_1_9_0 {

  private TestServiceGrpc_1_9_0() {}

  public static final String SERVICE_NAME = "grpc.testing.TestService";

  // Static method descriptors that strictly reflect the proto.
  private static final int ARG_IN_METHOD_UNARY_CALL = 0;
  private static final int ARG_OUT_METHOD_UNARY_CALL = 1;
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  @java.lang.Deprecated // Use {@link #getUnaryCallMethod()} instead. 
  public static final io.grpc.MethodDescriptor<io.grpc.testing.compiler.nano.Test.SimpleRequest,
      io.grpc.testing.compiler.nano.Test.SimpleResponse> METHOD_UNARY_CALL = getUnaryCallMethod();

  private static volatile io.grpc.MethodDescriptor<io.grpc.testing.compiler.nano.Test.SimpleRequest,
      io.grpc.testing.compiler.nano.Test.SimpleResponse> getUnaryCallMethod;

  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static io.grpc.MethodDescriptor<io.grpc.testing.compiler.nano.Test.SimpleRequest,
      io.grpc.testing.compiler.nano.Test.SimpleResponse> getUnaryCallMethod() {
    io.grpc.MethodDescriptor<io.grpc.testing.compiler.nano.Test.SimpleRequest, io.grpc.testing.compiler.nano.Test.SimpleResponse> getUnaryCallMethod;
    if ((getUnaryCallMethod = TestServiceGrpc_1_9_0.getUnaryCallMethod) == null) {
      synchronized (TestServiceGrpc_1_9_0.class) {
        if ((getUnaryCallMethod = TestServiceGrpc_1_9_0.getUnaryCallMethod) == null) {
          TestServiceGrpc_1_9_0.getUnaryCallMethod = getUnaryCallMethod = 
              io.grpc.MethodDescriptor.<io.grpc.testing.compiler.nano.Test.SimpleRequest, io.grpc.testing.compiler.nano.Test.SimpleResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(
                  "grpc.testing.TestService", "UnaryCall"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.nano.NanoUtils.<io.grpc.testing.compiler.nano.Test.SimpleRequest>marshaller(
                  new NanoFactory<io.grpc.testing.compiler.nano.Test.SimpleRequest>(ARG_IN_METHOD_UNARY_CALL)))
              .setResponseMarshaller(io.grpc.protobuf.nano.NanoUtils.<io.grpc.testing.compiler.nano.Test.SimpleResponse>marshaller(
                  new NanoFactory<io.grpc.testing.compiler.nano.Test.SimpleResponse>(ARG_OUT_METHOD_UNARY_CALL)))
              .build();
        }
      }
    }
    return getUnaryCallMethod;
  }
  private static final int ARG_IN_METHOD_STREAMING_OUTPUT_CALL = 2;
  private static final int ARG_OUT_METHOD_STREAMING_OUTPUT_CALL = 3;
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  @java.lang.Deprecated // Use {@link #getStreamingOutputCallMethod()} instead. 
  public static final io.grpc.MethodDescriptor<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest,
      io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse> METHOD_STREAMING_OUTPUT_CALL = getStreamingOutputCallMethod();

  private static volatile io.grpc.MethodDescriptor<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest,
      io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse> getStreamingOutputCallMethod;

  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static io.grpc.MethodDescriptor<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest,
      io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse> getStreamingOutputCallMethod() {
    io.grpc.MethodDescriptor<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest, io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse> getStreamingOutputCallMethod;
    if ((getStreamingOutputCallMethod = TestServiceGrpc_1_9_0.getStreamingOutputCallMethod) == null) {
      synchronized (TestServiceGrpc_1_9_0.class) {
        if ((getStreamingOutputCallMethod = TestServiceGrpc_1_9_0.getStreamingOutputCallMethod) == null) {
          TestServiceGrpc_1_9_0.getStreamingOutputCallMethod = getStreamingOutputCallMethod = 
              io.grpc.MethodDescriptor.<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest, io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(
                  "grpc.testing.TestService", "StreamingOutputCall"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.nano.NanoUtils.<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest>marshaller(
                  new NanoFactory<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest>(ARG_IN_METHOD_STREAMING_OUTPUT_CALL)))
              .setResponseMarshaller(io.grpc.protobuf.nano.NanoUtils.<io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse>marshaller(
                  new NanoFactory<io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse>(ARG_OUT_METHOD_STREAMING_OUTPUT_CALL)))
              .build();
        }
      }
    }
    return getStreamingOutputCallMethod;
  }
  private static final int ARG_IN_METHOD_STREAMING_INPUT_CALL = 4;
  private static final int ARG_OUT_METHOD_STREAMING_INPUT_CALL = 5;
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  @java.lang.Deprecated // Use {@link #getStreamingInputCallMethod()} instead. 
  public static final io.grpc.MethodDescriptor<io.grpc.testing.compiler.nano.Test.StreamingInputCallRequest,
      io.grpc.testing.compiler.nano.Test.StreamingInputCallResponse> METHOD_STREAMING_INPUT_CALL = getStreamingInputCallMethod();

  private static volatile io.grpc.MethodDescriptor<io.grpc.testing.compiler.nano.Test.StreamingInputCallRequest,
      io.grpc.testing.compiler.nano.Test.StreamingInputCallResponse> getStreamingInputCallMethod;

  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static io.grpc.MethodDescriptor<io.grpc.testing.compiler.nano.Test.StreamingInputCallRequest,
      io.grpc.testing.compiler.nano.Test.StreamingInputCallResponse> getStreamingInputCallMethod() {
    io.grpc.MethodDescriptor<io.grpc.testing.compiler.nano.Test.StreamingInputCallRequest, io.grpc.testing.compiler.nano.Test.StreamingInputCallResponse> getStreamingInputCallMethod;
    if ((getStreamingInputCallMethod = TestServiceGrpc_1_9_0.getStreamingInputCallMethod) == null) {
      synchronized (TestServiceGrpc_1_9_0.class) {
        if ((getStreamingInputCallMethod = TestServiceGrpc_1_9_0.getStreamingInputCallMethod) == null) {
          TestServiceGrpc_1_9_0.getStreamingInputCallMethod = getStreamingInputCallMethod = 
              io.grpc.MethodDescriptor.<io.grpc.testing.compiler.nano.Test.StreamingInputCallRequest, io.grpc.testing.compiler.nano.Test.StreamingInputCallResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
              .setFullMethodName(generateFullMethodName(
                  "grpc.testing.TestService", "StreamingInputCall"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.nano.NanoUtils.<io.grpc.testing.compiler.nano.Test.StreamingInputCallRequest>marshaller(
                  new NanoFactory<io.grpc.testing.compiler.nano.Test.StreamingInputCallRequest>(ARG_IN_METHOD_STREAMING_INPUT_CALL)))
              .setResponseMarshaller(io.grpc.protobuf.nano.NanoUtils.<io.grpc.testing.compiler.nano.Test.StreamingInputCallResponse>marshaller(
                  new NanoFactory<io.grpc.testing.compiler.nano.Test.StreamingInputCallResponse>(ARG_OUT_METHOD_STREAMING_INPUT_CALL)))
              .build();
        }
      }
    }
    return getStreamingInputCallMethod;
  }
  private static final int ARG_IN_METHOD_FULL_BIDI_CALL = 6;
  private static final int ARG_OUT_METHOD_FULL_BIDI_CALL = 7;
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  @java.lang.Deprecated // Use {@link #getFullBidiCallMethod()} instead. 
  public static final io.grpc.MethodDescriptor<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest,
      io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse> METHOD_FULL_BIDI_CALL = getFullBidiCallMethod();

  private static volatile io.grpc.MethodDescriptor<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest,
      io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse> getFullBidiCallMethod;

  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static io.grpc.MethodDescriptor<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest,
      io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse> getFullBidiCallMethod() {
    io.grpc.MethodDescriptor<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest, io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse> getFullBidiCallMethod;
    if ((getFullBidiCallMethod = TestServiceGrpc_1_9_0.getFullBidiCallMethod) == null) {
      synchronized (TestServiceGrpc_1_9_0.class) {
        if ((getFullBidiCallMethod = TestServiceGrpc_1_9_0.getFullBidiCallMethod) == null) {
          TestServiceGrpc_1_9_0.getFullBidiCallMethod = getFullBidiCallMethod = 
              io.grpc.MethodDescriptor.<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest, io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(
                  "grpc.testing.TestService", "FullBidiCall"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.nano.NanoUtils.<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest>marshaller(
                  new NanoFactory<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest>(ARG_IN_METHOD_FULL_BIDI_CALL)))
              .setResponseMarshaller(io.grpc.protobuf.nano.NanoUtils.<io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse>marshaller(
                  new NanoFactory<io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse>(ARG_OUT_METHOD_FULL_BIDI_CALL)))
              .build();
        }
      }
    }
    return getFullBidiCallMethod;
  }
  private static final int ARG_IN_METHOD_HALF_BIDI_CALL = 8;
  private static final int ARG_OUT_METHOD_HALF_BIDI_CALL = 9;
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  @java.lang.Deprecated // Use {@link #getHalfBidiCallMethod()} instead. 
  public static final io.grpc.MethodDescriptor<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest,
      io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse> METHOD_HALF_BIDI_CALL = getHalfBidiCallMethod();

  private static volatile io.grpc.MethodDescriptor<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest,
      io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse> getHalfBidiCallMethod;

  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static io.grpc.MethodDescriptor<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest,
      io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse> getHalfBidiCallMethod() {
    io.grpc.MethodDescriptor<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest, io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse> getHalfBidiCallMethod;
    if ((getHalfBidiCallMethod = TestServiceGrpc_1_9_0.getHalfBidiCallMethod) == null) {
      synchronized (TestServiceGrpc_1_9_0.class) {
        if ((getHalfBidiCallMethod = TestServiceGrpc_1_9_0.getHalfBidiCallMethod) == null) {
          TestServiceGrpc_1_9_0.getHalfBidiCallMethod = getHalfBidiCallMethod = 
              io.grpc.MethodDescriptor.<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest, io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(
                  "grpc.testing.TestService", "HalfBidiCall"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.nano.NanoUtils.<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest>marshaller(
                  new NanoFactory<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest>(ARG_IN_METHOD_HALF_BIDI_CALL)))
              .setResponseMarshaller(io.grpc.protobuf.nano.NanoUtils.<io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse>marshaller(
                  new NanoFactory<io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse>(ARG_OUT_METHOD_HALF_BIDI_CALL)))
              .build();
        }
      }
    }
    return getHalfBidiCallMethod;
  }

  private static final class NanoFactory<T extends com.google.protobuf.nano.MessageNano>
      implements io.grpc.protobuf.nano.MessageNanoFactory<T> {
    private final int id;

    NanoFactory(int id) {
      this.id = id;
    }

    @java.lang.Override
    public T newInstance() {
      Object o;
      switch (id) {
      case ARG_IN_METHOD_UNARY_CALL:
        o = new io.grpc.testing.compiler.nano.Test.SimpleRequest();
        break;
      case ARG_OUT_METHOD_UNARY_CALL:
        o = new io.grpc.testing.compiler.nano.Test.SimpleResponse();
        break;
      case ARG_IN_METHOD_STREAMING_OUTPUT_CALL:
        o = new io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest();
        break;
      case ARG_OUT_METHOD_STREAMING_OUTPUT_CALL:
        o = new io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse();
        break;
      case ARG_IN_METHOD_STREAMING_INPUT_CALL:
        o = new io.grpc.testing.compiler.nano.Test.StreamingInputCallRequest();
        break;
      case ARG_OUT_METHOD_STREAMING_INPUT_CALL:
        o = new io.grpc.testing.compiler.nano.Test.StreamingInputCallResponse();
        break;
      case ARG_IN_METHOD_FULL_BIDI_CALL:
        o = new io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest();
        break;
      case ARG_OUT_METHOD_FULL_BIDI_CALL:
        o = new io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse();
        break;
      case ARG_IN_METHOD_HALF_BIDI_CALL:
        o = new io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest();
        break;
      case ARG_OUT_METHOD_HALF_BIDI_CALL:
        o = new io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse();
        break;
      default:
        throw new AssertionError();
      }
      @java.lang.SuppressWarnings("unchecked")
      T t = (T) o;
      return t;
    }
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static TestServiceStub newStub(io.grpc.Channel channel) {
    return new TestServiceStub(channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static TestServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    return new TestServiceBlockingStub(channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static TestServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    return new TestServiceFutureStub(channel);
  }

  /**
   * <pre>
   * Test service that supports all call types.
   * </pre>
   */
  public static abstract class TestServiceImplBase implements io.grpc.BindableService {

    /**
     * <pre>
     * One request followed by one response.
     * The server returns the client payload as-is.
     * </pre>
     */
    public void unaryCall(io.grpc.testing.compiler.nano.Test.SimpleRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.SimpleResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getUnaryCallMethod(), responseObserver);
    }

    /**
     * <pre>
     * One request followed by a sequence of responses (streamed download).
     * The server returns the payload with client desired type and sizes.
     * </pre>
     */
    public void streamingOutputCall(io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getStreamingOutputCallMethod(), responseObserver);
    }

    /**
     * <pre>
     * A sequence of requests followed by one response (streamed upload).
     * The server returns the aggregated size of client payload as the result.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.StreamingInputCallRequest> streamingInputCall(
        io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.StreamingInputCallResponse> responseObserver) {
      return asyncUnimplementedStreamingCall(getStreamingInputCallMethod(), responseObserver);
    }

    /**
     * <pre>
     * A sequence of requests with each request served by the server immediately.
     * As one request could lead to multiple responses, this interface
     * demonstrates the idea of full bidirectionality.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest> fullBidiCall(
        io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse> responseObserver) {
      return asyncUnimplementedStreamingCall(getFullBidiCallMethod(), responseObserver);
    }

    /**
     * <pre>
     * A sequence of requests followed by a sequence of responses.
     * The server buffers all the client requests and then serves them in order. A
     * stream of responses are returned to the client when the server starts with
     * first request.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest> halfBidiCall(
        io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse> responseObserver) {
      return asyncUnimplementedStreamingCall(getHalfBidiCallMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getUnaryCallMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.grpc.testing.compiler.nano.Test.SimpleRequest,
                io.grpc.testing.compiler.nano.Test.SimpleResponse>(
                  this, METHODID_UNARY_CALL)))
          .addMethod(
            getStreamingOutputCallMethod(),
            asyncServerStreamingCall(
              new MethodHandlers<
                io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest,
                io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse>(
                  this, METHODID_STREAMING_OUTPUT_CALL)))
          .addMethod(
            getStreamingInputCallMethod(),
            asyncClientStreamingCall(
              new MethodHandlers<
                io.grpc.testing.compiler.nano.Test.StreamingInputCallRequest,
                io.grpc.testing.compiler.nano.Test.StreamingInputCallResponse>(
                  this, METHODID_STREAMING_INPUT_CALL)))
          .addMethod(
            getFullBidiCallMethod(),
            asyncBidiStreamingCall(
              new MethodHandlers<
                io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest,
                io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse>(
                  this, METHODID_FULL_BIDI_CALL)))
          .addMethod(
            getHalfBidiCallMethod(),
            asyncBidiStreamingCall(
              new MethodHandlers<
                io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest,
                io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse>(
                  this, METHODID_HALF_BIDI_CALL)))
          .build();
    }
  }

  /**
   * <pre>
   * Test service that supports all call types.
   * </pre>
   */
  public static final class TestServiceStub extends io.grpc.stub.AbstractStub<TestServiceStub> {
    private TestServiceStub(io.grpc.Channel channel) {
      super(channel);
    }

    private TestServiceStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected TestServiceStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new TestServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * One request followed by one response.
     * The server returns the client payload as-is.
     * </pre>
     */
    public void unaryCall(io.grpc.testing.compiler.nano.Test.SimpleRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.SimpleResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getUnaryCallMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * One request followed by a sequence of responses (streamed download).
     * The server returns the payload with client desired type and sizes.
     * </pre>
     */
    public void streamingOutputCall(io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse> responseObserver) {
      asyncServerStreamingCall(
          getChannel().newCall(getStreamingOutputCallMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * A sequence of requests followed by one response (streamed upload).
     * The server returns the aggregated size of client payload as the result.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.StreamingInputCallRequest> streamingInputCall(
        io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.StreamingInputCallResponse> responseObserver) {
      return asyncClientStreamingCall(
          getChannel().newCall(getStreamingInputCallMethod(), getCallOptions()), responseObserver);
    }

    /**
     * <pre>
     * A sequence of requests with each request served by the server immediately.
     * As one request could lead to multiple responses, this interface
     * demonstrates the idea of full bidirectionality.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest> fullBidiCall(
        io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse> responseObserver) {
      return asyncBidiStreamingCall(
          getChannel().newCall(getFullBidiCallMethod(), getCallOptions()), responseObserver);
    }

    /**
     * <pre>
     * A sequence of requests followed by a sequence of responses.
     * The server buffers all the client requests and then serves them in order. A
     * stream of responses are returned to the client when the server starts with
     * first request.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest> halfBidiCall(
        io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse> responseObserver) {
      return asyncBidiStreamingCall(
          getChannel().newCall(getHalfBidiCallMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   * <pre>
   * Test service that supports all call types.
   * </pre>
   */
  public static final class TestServiceBlockingStub extends io.grpc.stub.AbstractStub<TestServiceBlockingStub> {
    private TestServiceBlockingStub(io.grpc.Channel channel) {
      super(channel);
    }

    private TestServiceBlockingStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected TestServiceBlockingStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new TestServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * One request followed by one response.
     * The server returns the client payload as-is.
     * </pre>
     */
    public io.grpc.testing.compiler.nano.Test.SimpleResponse unaryCall(io.grpc.testing.compiler.nano.Test.SimpleRequest request) {
      return blockingUnaryCall(
          getChannel(), getUnaryCallMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * One request followed by a sequence of responses (streamed download).
     * The server returns the payload with client desired type and sizes.
     * </pre>
     */
    public java.util.Iterator<io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse> streamingOutputCall(
        io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest request) {
      return blockingServerStreamingCall(
          getChannel(), getStreamingOutputCallMethod(), getCallOptions(), request);
    }
  }

  /**
   * <pre>
   * Test service that supports all call types.
   * </pre>
   */
  public static final class TestServiceFutureStub extends io.grpc.stub.AbstractStub<TestServiceFutureStub> {
    private TestServiceFutureStub(io.grpc.Channel channel) {
      super(channel);
    }

    private TestServiceFutureStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected TestServiceFutureStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new TestServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * One request followed by one response.
     * The server returns the client payload as-is.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.testing.compiler.nano.Test.SimpleResponse> unaryCall(
        io.grpc.testing.compiler.nano.Test.SimpleRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getUnaryCallMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_UNARY_CALL = 0;
  private static final int METHODID_STREAMING_OUTPUT_CALL = 1;
  private static final int METHODID_STREAMING_INPUT_CALL = 2;
  private static final int METHODID_FULL_BIDI_CALL = 3;
  private static final int METHODID_HALF_BIDI_CALL = 4;

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
        case METHODID_UNARY_CALL:
          serviceImpl.unaryCall((io.grpc.testing.compiler.nano.Test.SimpleRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.SimpleResponse>) responseObserver);
          break;
        case METHODID_STREAMING_OUTPUT_CALL:
          serviceImpl.streamingOutputCall((io.grpc.testing.compiler.nano.Test.StreamingOutputCallRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse>) responseObserver);
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
              (io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.StreamingInputCallResponse>) responseObserver);
        case METHODID_FULL_BIDI_CALL:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.fullBidiCall(
              (io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse>) responseObserver);
        case METHODID_HALF_BIDI_CALL:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.halfBidiCall(
              (io.grpc.stub.StreamObserver<io.grpc.testing.compiler.nano.Test.StreamingOutputCallResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (TestServiceGrpc_1_9_0.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .addMethod(getUnaryCallMethod())
              .addMethod(getStreamingOutputCallMethod())
              .addMethod(getStreamingInputCallMethod())
              .addMethod(getFullBidiCallMethod())
              .addMethod(getHalfBidiCallMethod())
              .build();
        }
      }
    }
    return result;
  }
}
