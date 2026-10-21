package io.envoyproxy.envoy.service.auth.v3;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * A generic interface for performing authorization check on incoming
 * requests to a networked service.
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class AuthorizationGrpc {

  private AuthorizationGrpc() {}

  public static final java.lang.String SERVICE_NAME = "envoy.service.auth.v3.Authorization";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.auth.v3.CheckRequest,
      io.envoyproxy.envoy.service.auth.v3.CheckResponse> getCheckMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Check",
      requestType = io.envoyproxy.envoy.service.auth.v3.CheckRequest.class,
      responseType = io.envoyproxy.envoy.service.auth.v3.CheckResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.auth.v3.CheckRequest,
      io.envoyproxy.envoy.service.auth.v3.CheckResponse> getCheckMethod() {
    io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.auth.v3.CheckRequest, io.envoyproxy.envoy.service.auth.v3.CheckResponse> getCheckMethod;
    if ((getCheckMethod = AuthorizationGrpc.getCheckMethod) == null) {
      synchronized (AuthorizationGrpc.class) {
        if ((getCheckMethod = AuthorizationGrpc.getCheckMethod) == null) {
          AuthorizationGrpc.getCheckMethod = getCheckMethod =
              io.grpc.MethodDescriptor.<io.envoyproxy.envoy.service.auth.v3.CheckRequest, io.envoyproxy.envoy.service.auth.v3.CheckResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Check"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.service.auth.v3.CheckRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.service.auth.v3.CheckResponse.getDefaultInstance()))
              .setSchemaDescriptor(new AuthorizationMethodDescriptorSupplier("Check"))
              .build();
        }
      }
    }
    return getCheckMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static AuthorizationStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AuthorizationStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AuthorizationStub>() {
        @java.lang.Override
        public AuthorizationStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AuthorizationStub(channel, callOptions);
        }
      };
    return AuthorizationStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static AuthorizationBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AuthorizationBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AuthorizationBlockingV2Stub>() {
        @java.lang.Override
        public AuthorizationBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AuthorizationBlockingV2Stub(channel, callOptions);
        }
      };
    return AuthorizationBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static AuthorizationBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AuthorizationBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AuthorizationBlockingStub>() {
        @java.lang.Override
        public AuthorizationBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AuthorizationBlockingStub(channel, callOptions);
        }
      };
    return AuthorizationBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static AuthorizationFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AuthorizationFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AuthorizationFutureStub>() {
        @java.lang.Override
        public AuthorizationFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AuthorizationFutureStub(channel, callOptions);
        }
      };
    return AuthorizationFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * A generic interface for performing authorization check on incoming
   * requests to a networked service.
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Performs authorization check based on the attributes associated with the
     * incoming request, and returns status `OK` or not `OK`.
     * </pre>
     */
    default void check(io.envoyproxy.envoy.service.auth.v3.CheckRequest request,
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.auth.v3.CheckResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCheckMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service Authorization.
   * <pre>
   * A generic interface for performing authorization check on incoming
   * requests to a networked service.
   * </pre>
   */
  public static abstract class AuthorizationImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return AuthorizationGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service Authorization.
   * <pre>
   * A generic interface for performing authorization check on incoming
   * requests to a networked service.
   * </pre>
   */
  public static final class AuthorizationStub
      extends io.grpc.stub.AbstractAsyncStub<AuthorizationStub> {
    private AuthorizationStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AuthorizationStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AuthorizationStub(channel, callOptions);
    }

    /**
     * <pre>
     * Performs authorization check based on the attributes associated with the
     * incoming request, and returns status `OK` or not `OK`.
     * </pre>
     */
    public void check(io.envoyproxy.envoy.service.auth.v3.CheckRequest request,
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.auth.v3.CheckResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCheckMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service Authorization.
   * <pre>
   * A generic interface for performing authorization check on incoming
   * requests to a networked service.
   * </pre>
   */
  public static final class AuthorizationBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<AuthorizationBlockingV2Stub> {
    private AuthorizationBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AuthorizationBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AuthorizationBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Performs authorization check based on the attributes associated with the
     * incoming request, and returns status `OK` or not `OK`.
     * </pre>
     */
    public io.envoyproxy.envoy.service.auth.v3.CheckResponse check(io.envoyproxy.envoy.service.auth.v3.CheckRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCheckMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service Authorization.
   * <pre>
   * A generic interface for performing authorization check on incoming
   * requests to a networked service.
   * </pre>
   */
  public static final class AuthorizationBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<AuthorizationBlockingStub> {
    private AuthorizationBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AuthorizationBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AuthorizationBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Performs authorization check based on the attributes associated with the
     * incoming request, and returns status `OK` or not `OK`.
     * </pre>
     */
    public io.envoyproxy.envoy.service.auth.v3.CheckResponse check(io.envoyproxy.envoy.service.auth.v3.CheckRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCheckMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service Authorization.
   * <pre>
   * A generic interface for performing authorization check on incoming
   * requests to a networked service.
   * </pre>
   */
  public static final class AuthorizationFutureStub
      extends io.grpc.stub.AbstractFutureStub<AuthorizationFutureStub> {
    private AuthorizationFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AuthorizationFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AuthorizationFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Performs authorization check based on the attributes associated with the
     * incoming request, and returns status `OK` or not `OK`.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.envoyproxy.envoy.service.auth.v3.CheckResponse> check(
        io.envoyproxy.envoy.service.auth.v3.CheckRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCheckMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_CHECK = 0;

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
        case METHODID_CHECK:
          serviceImpl.check((io.envoyproxy.envoy.service.auth.v3.CheckRequest) request,
              (io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.auth.v3.CheckResponse>) responseObserver);
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
          getCheckMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.envoyproxy.envoy.service.auth.v3.CheckRequest,
              io.envoyproxy.envoy.service.auth.v3.CheckResponse>(
                service, METHODID_CHECK)))
        .build();
  }

  private static abstract class AuthorizationBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    AuthorizationBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.envoyproxy.envoy.service.auth.v3.ExternalAuthProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("Authorization");
    }
  }

  private static final class AuthorizationFileDescriptorSupplier
      extends AuthorizationBaseDescriptorSupplier {
    AuthorizationFileDescriptorSupplier() {}
  }

  private static final class AuthorizationMethodDescriptorSupplier
      extends AuthorizationBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    AuthorizationMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (AuthorizationGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new AuthorizationFileDescriptorSupplier())
              .addMethod(getCheckMethod())
              .build();
        }
      }
    }
    return result;
  }
}
