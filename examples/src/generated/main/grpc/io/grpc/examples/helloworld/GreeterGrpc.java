package io.grpc.examples.helloworld;

import static io.grpc.stub.ClientCalls.asyncUnaryCall;
import static io.grpc.stub.ClientCalls.asyncServerStreamingCall;
import static io.grpc.stub.ClientCalls.asyncClientStreamingCall;
import static io.grpc.stub.ClientCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ClientCalls.blockingUnaryCall;
import static io.grpc.stub.ClientCalls.blockingServerStreamingCall;
import static io.grpc.stub.ClientCalls.futureUnaryCall;
import static io.grpc.MethodDescriptor.generateFullMethodName;
import static io.grpc.stub.ServerCalls.asyncUnaryCall;
import static io.grpc.stub.ServerCalls.asyncServerStreamingCall;
import static io.grpc.stub.ServerCalls.asyncClientStreamingCall;
import static io.grpc.stub.ServerCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall;

/**
 * <pre>
 * The greeting service definition.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 0.15.0-SNAPSHOT)",
    comments = "Source: helloworld.proto")
public class GreeterGrpc {

  private GreeterGrpc() {}

  public static final String SERVICE_NAME = "helloworld.Greeter";

  // Static method descriptors that strictly reflect the proto.
  @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/1901")
  public static final io.grpc.MethodDescriptor<io.grpc.examples.helloworld.HelloRequest,
      io.grpc.examples.helloworld.HelloReply> METHOD_SAY_HELLO =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "helloworld.Greeter", "SayHello"),
          io.grpc.protobuf.ProtoUtils.marshaller(io.grpc.examples.helloworld.HelloRequest.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(io.grpc.examples.helloworld.HelloReply.getDefaultInstance()));

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static GreeterStub newStub(io.grpc.Channel channel) {
    return new GreeterStub(channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static GreeterBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    return new GreeterBlockingStub(channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary and streaming output calls on the service
   */
  public static GreeterFutureStub newFutureStub(
      io.grpc.Channel channel) {
    return new GreeterFutureStub(channel);
  }

  /**
   * <pre>
   * The greeting service definition.
   * </pre>
   */
  public static abstract class GreeterImplBase implements io.grpc.BindableService {

    /**
     * <pre>
     * Sends a greeting
     * </pre>
     */
    public void sayHello(io.grpc.examples.helloworld.HelloRequest request,
        io.grpc.stub.StreamObserver<io.grpc.examples.helloworld.HelloReply> responseObserver) {
      asyncUnimplementedUnaryCall(METHOD_SAY_HELLO, responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            METHOD_SAY_HELLO,
            asyncUnaryCall(
              new MethodHandlers<
                io.grpc.examples.helloworld.HelloRequest,
                io.grpc.examples.helloworld.HelloReply>(
                  this, METHODID_SAY_HELLO)))
          .build();
    }
  }

  /**
   * <pre>
   * The greeting service definition.
   * </pre>
   */
  public static final class GreeterStub extends io.grpc.stub.AbstractStub<GreeterStub> {
    private GreeterStub(io.grpc.Channel channel) {
      super(channel);
    }

    private GreeterStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected GreeterStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new GreeterStub(channel, callOptions);
    }

    /**
     * <pre>
     * Sends a greeting
     * </pre>
     */
    public void sayHello(io.grpc.examples.helloworld.HelloRequest request,
        io.grpc.stub.StreamObserver<io.grpc.examples.helloworld.HelloReply> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_SAY_HELLO, getCallOptions()), request, responseObserver);
    }
  }

  /**
   * <pre>
   * The greeting service definition.
   * </pre>
   */
  public static final class GreeterBlockingStub extends io.grpc.stub.AbstractStub<GreeterBlockingStub> {
    private GreeterBlockingStub(io.grpc.Channel channel) {
      super(channel);
    }

    private GreeterBlockingStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected GreeterBlockingStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new GreeterBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Sends a greeting
     * </pre>
     */
    public io.grpc.examples.helloworld.HelloReply sayHello(io.grpc.examples.helloworld.HelloRequest request) {
      return blockingUnaryCall(
          getChannel(), METHOD_SAY_HELLO, getCallOptions(), request);
    }
  }

  /**
   * <pre>
   * The greeting service definition.
   * </pre>
   */
  public static final class GreeterFutureStub extends io.grpc.stub.AbstractStub<GreeterFutureStub> {
    private GreeterFutureStub(io.grpc.Channel channel) {
      super(channel);
    }

    private GreeterFutureStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected GreeterFutureStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new GreeterFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Sends a greeting
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.examples.helloworld.HelloReply> sayHello(
        io.grpc.examples.helloworld.HelloRequest request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_SAY_HELLO, getCallOptions()), request);
    }
  }

  private static final int METHODID_SAY_HELLO = 0;

  private static class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final GreeterImplBase serviceImpl;
    private final int methodId;

    public MethodHandlers(GreeterImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_SAY_HELLO:
          serviceImpl.sayHello((io.grpc.examples.helloworld.HelloRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.examples.helloworld.HelloReply>) responseObserver);
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

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    return new io.grpc.ServiceDescriptor(SERVICE_NAME,
        METHOD_SAY_HELLO);
  }

  /**
   * This can not be used any more since v0.15.
   * If your code using earlier version of gRPC-java is breaking when upgrading to v0.15,
   * the following are suggested:
   * <ul>
   *   <li> replace {@code extends/implements Greeter} with {@code extends GreeterImplBase};</li>
   *   <li> replace usage of {@code Greeter} with {@code GreeterImplBase};</li>
   *   <li> replace usage of {@code AbstractGreeter} with {@link GreeterImplBase};</li>
   *   <li> replace {@code serverBuilder.addService(GreeterGrpc.bindService(serviceImpl))}
   *        with {@code serverBuilder.addService(serviceImpl)};</li>
   *   <li> if you are mocking stubs using mockito, please do not mock them. See the documentation
   *        on testing with gRPC-java;</li>
   *   <li> replace {@code GreeterBlockingClient} with {@link GreeterBlockingStub};</li>
   *   <li> replace {@code GreeterFutureClient} with {@link GreeterFutureStub}.</li>
   * </ul>
   */
  @Deprecated public static final class Greeter {}

  /**
   * This can not be used any more since v0.15.
   * If your code using earlier version of gRPC-java is breaking when upgrading to v0.15,
   * the following are suggested:
   * <ul>
   *   <li> replace {@code extends/implements Greeter} with {@code extends GreeterImplBase};</li>
   *   <li> replace usage of {@code Greeter} with {@code GreeterImplBase};</li>
   *   <li> replace usage of {@code AbstractGreeter} with {@link GreeterImplBase};</li>
   *   <li> replace {@code serverBuilder.addService(GreeterGrpc.bindService(serviceImpl))}
   *        with {@code serverBuilder.addService(serviceImpl)};</li>
   *   <li> if you are mocking stubs using mockito, please do not mock them. See the documentation
   *        on testing with gRPC-java;</li>
   *   <li> replace {@code GreeterBlockingClient} with {@link GreeterBlockingStub};</li>
   *   <li> replace {@code GreeterFutureClient} with {@link GreeterFutureStub}.</li>
   * </ul>
   */
  @Deprecated public static final class GreeterBlockingClient {}

  /**
   * This can not be used any more since v0.15.
   * If your code using earlier version of gRPC-java is breaking when upgrading to v0.15,
   * the following are suggested:
   * <ul>
   *   <li> replace {@code extends/implements Greeter} with {@code extends GreeterImplBase};</li>
   *   <li> replace usage of {@code Greeter} with {@code GreeterImplBase};</li>
   *   <li> replace usage of {@code AbstractGreeter} with {@link GreeterImplBase};</li>
   *   <li> replace {@code serverBuilder.addService(GreeterGrpc.bindService(serviceImpl))}
   *        with {@code serverBuilder.addService(serviceImpl)};</li>
   *   <li> if you are mocking stubs using mockito, please do not mock them. See the documentation
   *        on testing with gRPC-java;</li>
   *   <li> replace {@code GreeterBlockingClient} with {@link GreeterBlockingStub};</li>
   *   <li> replace {@code GreeterFutureClient} with {@link GreeterFutureStub}.</li>
   * </ul>
   */
  @Deprecated public static final class GreeterFutureClient {}

  /**
   * This can not be used any more since v0.15.
   * If your code using earlier version of gRPC-java is breaking when upgrading to v0.15,
   * the following are suggested:
   * <ul>
   *   <li> replace {@code extends/implements Greeter} with {@code extends GreeterImplBase};</li>
   *   <li> replace usage of {@code Greeter} with {@code GreeterImplBase};</li>
   *   <li> replace usage of {@code AbstractGreeter} with {@link GreeterImplBase};</li>
   *   <li> replace {@code serverBuilder.addService(GreeterGrpc.bindService(serviceImpl))}
   *        with {@code serverBuilder.addService(serviceImpl)};</li>
   *   <li> if you are mocking stubs using mockito, please do not mock them. See the documentation
   *        on testing with gRPC-java;</li>
   *   <li> replace {@code GreeterBlockingClient} with {@link GreeterBlockingStub};</li>
   *   <li> replace {@code GreeterFutureClient} with {@link GreeterFutureStub}.</li>
   * </ul>
   */
  @Deprecated public static final void bindService(Object o) {}

}
