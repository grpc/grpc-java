package io.grpc.lookup.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: grpc/lookup/v1/rls.proto")
public final class RouteLookupServiceGrpc {

  private RouteLookupServiceGrpc() {}

  public static final String SERVICE_NAME = "grpc.lookup.v1.RouteLookupService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.lookup.v1.RouteLookupRequest,
      io.grpc.lookup.v1.RouteLookupResponse> getRouteLookupMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RouteLookup",
      requestType = io.grpc.lookup.v1.RouteLookupRequest.class,
      responseType = io.grpc.lookup.v1.RouteLookupResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.lookup.v1.RouteLookupRequest,
      io.grpc.lookup.v1.RouteLookupResponse> getRouteLookupMethod() {
    io.grpc.MethodDescriptor<io.grpc.lookup.v1.RouteLookupRequest, io.grpc.lookup.v1.RouteLookupResponse> getRouteLookupMethod;
    if ((getRouteLookupMethod = RouteLookupServiceGrpc.getRouteLookupMethod) == null) {
      synchronized (RouteLookupServiceGrpc.class) {
        if ((getRouteLookupMethod = RouteLookupServiceGrpc.getRouteLookupMethod) == null) {
          RouteLookupServiceGrpc.getRouteLookupMethod = getRouteLookupMethod =
              io.grpc.MethodDescriptor.<io.grpc.lookup.v1.RouteLookupRequest, io.grpc.lookup.v1.RouteLookupResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RouteLookup"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.lookup.v1.RouteLookupRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.lookup.v1.RouteLookupResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RouteLookupServiceMethodDescriptorSupplier("RouteLookup"))
              .build();
        }
      }
    }
    return getRouteLookupMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static RouteLookupServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RouteLookupServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RouteLookupServiceStub>() {
        @java.lang.Override
        public RouteLookupServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RouteLookupServiceStub(channel, callOptions);
        }
      };
    return RouteLookupServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static RouteLookupServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RouteLookupServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RouteLookupServiceBlockingStub>() {
        @java.lang.Override
        public RouteLookupServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RouteLookupServiceBlockingStub(channel, callOptions);
        }
      };
    return RouteLookupServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static RouteLookupServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RouteLookupServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RouteLookupServiceFutureStub>() {
        @java.lang.Override
        public RouteLookupServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RouteLookupServiceFutureStub(channel, callOptions);
        }
      };
    return RouteLookupServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public static abstract class RouteLookupServiceImplBase implements io.grpc.BindableService {

    /**
     * <pre>
     * Lookup returns a target for a single key.
     * </pre>
     */
    public void routeLookup(io.grpc.lookup.v1.RouteLookupRequest request,
        io.grpc.stub.StreamObserver<io.grpc.lookup.v1.RouteLookupResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRouteLookupMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getRouteLookupMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.grpc.lookup.v1.RouteLookupRequest,
                io.grpc.lookup.v1.RouteLookupResponse>(
                  this, METHODID_ROUTE_LOOKUP)))
          .build();
    }
  }

  /**
   */
  public static final class RouteLookupServiceStub extends io.grpc.stub.AbstractAsyncStub<RouteLookupServiceStub> {
    private RouteLookupServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RouteLookupServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RouteLookupServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Lookup returns a target for a single key.
     * </pre>
     */
    public void routeLookup(io.grpc.lookup.v1.RouteLookupRequest request,
        io.grpc.stub.StreamObserver<io.grpc.lookup.v1.RouteLookupResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRouteLookupMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   */
  public static final class RouteLookupServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<RouteLookupServiceBlockingStub> {
    private RouteLookupServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RouteLookupServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RouteLookupServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Lookup returns a target for a single key.
     * </pre>
     */
    public io.grpc.lookup.v1.RouteLookupResponse routeLookup(io.grpc.lookup.v1.RouteLookupRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRouteLookupMethod(), getCallOptions(), request);
    }
  }

  /**
   */
  public static final class RouteLookupServiceFutureStub extends io.grpc.stub.AbstractFutureStub<RouteLookupServiceFutureStub> {
    private RouteLookupServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RouteLookupServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RouteLookupServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Lookup returns a target for a single key.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.lookup.v1.RouteLookupResponse> routeLookup(
        io.grpc.lookup.v1.RouteLookupRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRouteLookupMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_ROUTE_LOOKUP = 0;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final RouteLookupServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(RouteLookupServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_ROUTE_LOOKUP:
          serviceImpl.routeLookup((io.grpc.lookup.v1.RouteLookupRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.lookup.v1.RouteLookupResponse>) responseObserver);
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

  private static abstract class RouteLookupServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    RouteLookupServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.grpc.lookup.v1.RlsProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("RouteLookupService");
    }
  }

  private static final class RouteLookupServiceFileDescriptorSupplier
      extends RouteLookupServiceBaseDescriptorSupplier {
    RouteLookupServiceFileDescriptorSupplier() {}
  }

  private static final class RouteLookupServiceMethodDescriptorSupplier
      extends RouteLookupServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    RouteLookupServiceMethodDescriptorSupplier(String methodName) {
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
      synchronized (RouteLookupServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new RouteLookupServiceFileDescriptorSupplier())
              .addMethod(getRouteLookupMethod())
              .build();
        }
      }
    }
    return result;
  }
}
