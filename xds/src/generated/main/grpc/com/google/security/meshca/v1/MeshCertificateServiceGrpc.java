package com.google.security.meshca.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * Service for managing certificates issued by the CSM CA.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: security/proto/providers/google/meshca.proto")
public final class MeshCertificateServiceGrpc {

  private MeshCertificateServiceGrpc() {}

  public static final String SERVICE_NAME = "google.security.meshca.v1.MeshCertificateService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.google.security.meshca.v1.MeshCertificateRequest,
      com.google.security.meshca.v1.MeshCertificateResponse> getCreateCertificateMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateCertificate",
      requestType = com.google.security.meshca.v1.MeshCertificateRequest.class,
      responseType = com.google.security.meshca.v1.MeshCertificateResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.google.security.meshca.v1.MeshCertificateRequest,
      com.google.security.meshca.v1.MeshCertificateResponse> getCreateCertificateMethod() {
    io.grpc.MethodDescriptor<com.google.security.meshca.v1.MeshCertificateRequest, com.google.security.meshca.v1.MeshCertificateResponse> getCreateCertificateMethod;
    if ((getCreateCertificateMethod = MeshCertificateServiceGrpc.getCreateCertificateMethod) == null) {
      synchronized (MeshCertificateServiceGrpc.class) {
        if ((getCreateCertificateMethod = MeshCertificateServiceGrpc.getCreateCertificateMethod) == null) {
          MeshCertificateServiceGrpc.getCreateCertificateMethod = getCreateCertificateMethod =
              io.grpc.MethodDescriptor.<com.google.security.meshca.v1.MeshCertificateRequest, com.google.security.meshca.v1.MeshCertificateResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateCertificate"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.security.meshca.v1.MeshCertificateRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.security.meshca.v1.MeshCertificateResponse.getDefaultInstance()))
              .setSchemaDescriptor(new MeshCertificateServiceMethodDescriptorSupplier("CreateCertificate"))
              .build();
        }
      }
    }
    return getCreateCertificateMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static MeshCertificateServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<MeshCertificateServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<MeshCertificateServiceStub>() {
        @java.lang.Override
        public MeshCertificateServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new MeshCertificateServiceStub(channel, callOptions);
        }
      };
    return MeshCertificateServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static MeshCertificateServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<MeshCertificateServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<MeshCertificateServiceBlockingStub>() {
        @java.lang.Override
        public MeshCertificateServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new MeshCertificateServiceBlockingStub(channel, callOptions);
        }
      };
    return MeshCertificateServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static MeshCertificateServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<MeshCertificateServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<MeshCertificateServiceFutureStub>() {
        @java.lang.Override
        public MeshCertificateServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new MeshCertificateServiceFutureStub(channel, callOptions);
        }
      };
    return MeshCertificateServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * Service for managing certificates issued by the CSM CA.
   * </pre>
   */
  public static abstract class MeshCertificateServiceImplBase implements io.grpc.BindableService {

    /**
     * <pre>
     * Using provided CSR, returns a signed certificate that represents a GCP
     * service account identity.
     * </pre>
     */
    public void createCertificate(com.google.security.meshca.v1.MeshCertificateRequest request,
        io.grpc.stub.StreamObserver<com.google.security.meshca.v1.MeshCertificateResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateCertificateMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getCreateCertificateMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                com.google.security.meshca.v1.MeshCertificateRequest,
                com.google.security.meshca.v1.MeshCertificateResponse>(
                  this, METHODID_CREATE_CERTIFICATE)))
          .build();
    }
  }

  /**
   * <pre>
   * Service for managing certificates issued by the CSM CA.
   * </pre>
   */
  public static final class MeshCertificateServiceStub extends io.grpc.stub.AbstractAsyncStub<MeshCertificateServiceStub> {
    private MeshCertificateServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected MeshCertificateServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new MeshCertificateServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Using provided CSR, returns a signed certificate that represents a GCP
     * service account identity.
     * </pre>
     */
    public void createCertificate(com.google.security.meshca.v1.MeshCertificateRequest request,
        io.grpc.stub.StreamObserver<com.google.security.meshca.v1.MeshCertificateResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateCertificateMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * <pre>
   * Service for managing certificates issued by the CSM CA.
   * </pre>
   */
  public static final class MeshCertificateServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<MeshCertificateServiceBlockingStub> {
    private MeshCertificateServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected MeshCertificateServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new MeshCertificateServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Using provided CSR, returns a signed certificate that represents a GCP
     * service account identity.
     * </pre>
     */
    public com.google.security.meshca.v1.MeshCertificateResponse createCertificate(com.google.security.meshca.v1.MeshCertificateRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateCertificateMethod(), getCallOptions(), request);
    }
  }

  /**
   * <pre>
   * Service for managing certificates issued by the CSM CA.
   * </pre>
   */
  public static final class MeshCertificateServiceFutureStub extends io.grpc.stub.AbstractFutureStub<MeshCertificateServiceFutureStub> {
    private MeshCertificateServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected MeshCertificateServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new MeshCertificateServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Using provided CSR, returns a signed certificate that represents a GCP
     * service account identity.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.google.security.meshca.v1.MeshCertificateResponse> createCertificate(
        com.google.security.meshca.v1.MeshCertificateRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateCertificateMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_CREATE_CERTIFICATE = 0;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final MeshCertificateServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(MeshCertificateServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_CREATE_CERTIFICATE:
          serviceImpl.createCertificate((com.google.security.meshca.v1.MeshCertificateRequest) request,
              (io.grpc.stub.StreamObserver<com.google.security.meshca.v1.MeshCertificateResponse>) responseObserver);
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

  private static abstract class MeshCertificateServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    MeshCertificateServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.google.security.meshca.v1.MeshCaProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("MeshCertificateService");
    }
  }

  private static final class MeshCertificateServiceFileDescriptorSupplier
      extends MeshCertificateServiceBaseDescriptorSupplier {
    MeshCertificateServiceFileDescriptorSupplier() {}
  }

  private static final class MeshCertificateServiceMethodDescriptorSupplier
      extends MeshCertificateServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    MeshCertificateServiceMethodDescriptorSupplier(String methodName) {
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
      synchronized (MeshCertificateServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new MeshCertificateServiceFileDescriptorSupplier())
              .addMethod(getCreateCertificateMethod())
              .build();
        }
      }
    }
    return result;
  }
}
