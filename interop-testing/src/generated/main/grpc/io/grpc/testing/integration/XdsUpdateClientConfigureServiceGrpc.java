package io.grpc.testing.integration;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * A service to dynamically update the configuration of an xDS test client.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: grpc/testing/test.proto")
public final class XdsUpdateClientConfigureServiceGrpc {

  private XdsUpdateClientConfigureServiceGrpc() {}

  public static final String SERVICE_NAME = "grpc.testing.XdsUpdateClientConfigureService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.testing.integration.Messages.ClientConfigureRequest,
      io.grpc.testing.integration.Messages.ClientConfigureResponse> getConfigureMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Configure",
      requestType = io.grpc.testing.integration.Messages.ClientConfigureRequest.class,
      responseType = io.grpc.testing.integration.Messages.ClientConfigureResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.testing.integration.Messages.ClientConfigureRequest,
      io.grpc.testing.integration.Messages.ClientConfigureResponse> getConfigureMethod() {
    io.grpc.MethodDescriptor<io.grpc.testing.integration.Messages.ClientConfigureRequest, io.grpc.testing.integration.Messages.ClientConfigureResponse> getConfigureMethod;
    if ((getConfigureMethod = XdsUpdateClientConfigureServiceGrpc.getConfigureMethod) == null) {
      synchronized (XdsUpdateClientConfigureServiceGrpc.class) {
        if ((getConfigureMethod = XdsUpdateClientConfigureServiceGrpc.getConfigureMethod) == null) {
          XdsUpdateClientConfigureServiceGrpc.getConfigureMethod = getConfigureMethod =
              io.grpc.MethodDescriptor.<io.grpc.testing.integration.Messages.ClientConfigureRequest, io.grpc.testing.integration.Messages.ClientConfigureResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Configure"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.testing.integration.Messages.ClientConfigureRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.testing.integration.Messages.ClientConfigureResponse.getDefaultInstance()))
              .setSchemaDescriptor(new XdsUpdateClientConfigureServiceMethodDescriptorSupplier("Configure"))
              .build();
        }
      }
    }
    return getConfigureMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static XdsUpdateClientConfigureServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<XdsUpdateClientConfigureServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<XdsUpdateClientConfigureServiceStub>() {
        @java.lang.Override
        public XdsUpdateClientConfigureServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new XdsUpdateClientConfigureServiceStub(channel, callOptions);
        }
      };
    return XdsUpdateClientConfigureServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static XdsUpdateClientConfigureServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<XdsUpdateClientConfigureServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<XdsUpdateClientConfigureServiceBlockingStub>() {
        @java.lang.Override
        public XdsUpdateClientConfigureServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new XdsUpdateClientConfigureServiceBlockingStub(channel, callOptions);
        }
      };
    return XdsUpdateClientConfigureServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static XdsUpdateClientConfigureServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<XdsUpdateClientConfigureServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<XdsUpdateClientConfigureServiceFutureStub>() {
        @java.lang.Override
        public XdsUpdateClientConfigureServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new XdsUpdateClientConfigureServiceFutureStub(channel, callOptions);
        }
      };
    return XdsUpdateClientConfigureServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * A service to dynamically update the configuration of an xDS test client.
   * </pre>
   */
  public static abstract class XdsUpdateClientConfigureServiceImplBase implements io.grpc.BindableService {

    /**
     * <pre>
     * Update the tes client's configuration.
     * </pre>
     */
    public void configure(io.grpc.testing.integration.Messages.ClientConfigureRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.Messages.ClientConfigureResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getConfigureMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getConfigureMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                io.grpc.testing.integration.Messages.ClientConfigureRequest,
                io.grpc.testing.integration.Messages.ClientConfigureResponse>(
                  this, METHODID_CONFIGURE)))
          .build();
    }
  }

  /**
   * <pre>
   * A service to dynamically update the configuration of an xDS test client.
   * </pre>
   */
  public static final class XdsUpdateClientConfigureServiceStub extends io.grpc.stub.AbstractAsyncStub<XdsUpdateClientConfigureServiceStub> {
    private XdsUpdateClientConfigureServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected XdsUpdateClientConfigureServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new XdsUpdateClientConfigureServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Update the tes client's configuration.
     * </pre>
     */
    public void configure(io.grpc.testing.integration.Messages.ClientConfigureRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.Messages.ClientConfigureResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getConfigureMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * <pre>
   * A service to dynamically update the configuration of an xDS test client.
   * </pre>
   */
  public static final class XdsUpdateClientConfigureServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<XdsUpdateClientConfigureServiceBlockingStub> {
    private XdsUpdateClientConfigureServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected XdsUpdateClientConfigureServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new XdsUpdateClientConfigureServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Update the tes client's configuration.
     * </pre>
     */
    public io.grpc.testing.integration.Messages.ClientConfigureResponse configure(io.grpc.testing.integration.Messages.ClientConfigureRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getConfigureMethod(), getCallOptions(), request);
    }
  }

  /**
   * <pre>
   * A service to dynamically update the configuration of an xDS test client.
   * </pre>
   */
  public static final class XdsUpdateClientConfigureServiceFutureStub extends io.grpc.stub.AbstractFutureStub<XdsUpdateClientConfigureServiceFutureStub> {
    private XdsUpdateClientConfigureServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected XdsUpdateClientConfigureServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new XdsUpdateClientConfigureServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Update the tes client's configuration.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.testing.integration.Messages.ClientConfigureResponse> configure(
        io.grpc.testing.integration.Messages.ClientConfigureRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getConfigureMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_CONFIGURE = 0;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final XdsUpdateClientConfigureServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(XdsUpdateClientConfigureServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_CONFIGURE:
          serviceImpl.configure((io.grpc.testing.integration.Messages.ClientConfigureRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.testing.integration.Messages.ClientConfigureResponse>) responseObserver);
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

  private static abstract class XdsUpdateClientConfigureServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    XdsUpdateClientConfigureServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.grpc.testing.integration.Test.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("XdsUpdateClientConfigureService");
    }
  }

  private static final class XdsUpdateClientConfigureServiceFileDescriptorSupplier
      extends XdsUpdateClientConfigureServiceBaseDescriptorSupplier {
    XdsUpdateClientConfigureServiceFileDescriptorSupplier() {}
  }

  private static final class XdsUpdateClientConfigureServiceMethodDescriptorSupplier
      extends XdsUpdateClientConfigureServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    XdsUpdateClientConfigureServiceMethodDescriptorSupplier(String methodName) {
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
      synchronized (XdsUpdateClientConfigureServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new XdsUpdateClientConfigureServiceFileDescriptorSupplier())
              .addMethod(getConfigureMethod())
              .build();
        }
      }
    }
    return result;
  }
}
