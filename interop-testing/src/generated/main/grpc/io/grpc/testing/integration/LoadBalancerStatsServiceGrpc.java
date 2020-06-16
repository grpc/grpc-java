package io.grpc.testing.integration;

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
 * A service used to obtain stats for verifying LB behavior.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: grpc/testing/test.proto")
public final class LoadBalancerStatsServiceGrpc {

  private LoadBalancerStatsServiceGrpc() {}

  public static final String SERVICE_NAME = "grpc.testing.LoadBalancerStatsService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.testing.integration.Messages.LoadBalancerStatsRequest,
      io.grpc.testing.integration.Messages.LoadBalancerStatsResponse> getGetClientStatsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetClientStats",
      requestType = io.grpc.testing.integration.Messages.LoadBalancerStatsRequest.class,
      responseType = io.grpc.testing.integration.Messages.LoadBalancerStatsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.testing.integration.Messages.LoadBalancerStatsRequest,
      io.grpc.testing.integration.Messages.LoadBalancerStatsResponse> getGetClientStatsMethod() {
    io.grpc.MethodDescriptor<io.grpc.testing.integration.Messages.LoadBalancerStatsRequest, io.grpc.testing.integration.Messages.LoadBalancerStatsResponse> getGetClientStatsMethod;
    if ((getGetClientStatsMethod = LoadBalancerStatsServiceGrpc.getGetClientStatsMethod) == null) {
      synchronized (LoadBalancerStatsServiceGrpc.class) {
        if ((getGetClientStatsMethod = LoadBalancerStatsServiceGrpc.getGetClientStatsMethod) == null) {
          LoadBalancerStatsServiceGrpc.getGetClientStatsMethod = getGetClientStatsMethod =
              io.grpc.MethodDescriptor.<io.grpc.testing.integration.Messages.LoadBalancerStatsRequest, io.grpc.testing.integration.Messages.LoadBalancerStatsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetClientStats"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.testing.integration.Messages.LoadBalancerStatsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.testing.integration.Messages.LoadBalancerStatsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new LoadBalancerStatsServiceMethodDescriptorSupplier("GetClientStats"))
              .build();
        }
      }
    }
    return getGetClientStatsMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static LoadBalancerStatsServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<LoadBalancerStatsServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<LoadBalancerStatsServiceStub>() {
        @java.lang.Override
        public LoadBalancerStatsServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new LoadBalancerStatsServiceStub(channel, callOptions);
        }
      };
    return LoadBalancerStatsServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static LoadBalancerStatsServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<LoadBalancerStatsServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<LoadBalancerStatsServiceBlockingStub>() {
        @java.lang.Override
        public LoadBalancerStatsServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new LoadBalancerStatsServiceBlockingStub(channel, callOptions);
        }
      };
    return LoadBalancerStatsServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static LoadBalancerStatsServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<LoadBalancerStatsServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<LoadBalancerStatsServiceFutureStub>() {
        @java.lang.Override
        public LoadBalancerStatsServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new LoadBalancerStatsServiceFutureStub(channel, callOptions);
        }
      };
    return LoadBalancerStatsServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * A service used to obtain stats for verifying LB behavior.
   * </pre>
   */
  public static abstract class LoadBalancerStatsServiceImplBase implements io.grpc.BindableService {

    /**
     * <pre>
     * Gets the backend distribution for RPCs sent by a test client.
     * </pre>
     */
    public void getClientStats(io.grpc.testing.integration.Messages.LoadBalancerStatsRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.Messages.LoadBalancerStatsResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getGetClientStatsMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getGetClientStatsMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                io.grpc.testing.integration.Messages.LoadBalancerStatsRequest,
                io.grpc.testing.integration.Messages.LoadBalancerStatsResponse>(
                  this, METHODID_GET_CLIENT_STATS)))
          .build();
    }
  }

  /**
   * <pre>
   * A service used to obtain stats for verifying LB behavior.
   * </pre>
   */
  public static final class LoadBalancerStatsServiceStub extends io.grpc.stub.AbstractAsyncStub<LoadBalancerStatsServiceStub> {
    private LoadBalancerStatsServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LoadBalancerStatsServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new LoadBalancerStatsServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Gets the backend distribution for RPCs sent by a test client.
     * </pre>
     */
    public void getClientStats(io.grpc.testing.integration.Messages.LoadBalancerStatsRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.integration.Messages.LoadBalancerStatsResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getGetClientStatsMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * <pre>
   * A service used to obtain stats for verifying LB behavior.
   * </pre>
   */
  public static final class LoadBalancerStatsServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<LoadBalancerStatsServiceBlockingStub> {
    private LoadBalancerStatsServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LoadBalancerStatsServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new LoadBalancerStatsServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Gets the backend distribution for RPCs sent by a test client.
     * </pre>
     */
    public io.grpc.testing.integration.Messages.LoadBalancerStatsResponse getClientStats(io.grpc.testing.integration.Messages.LoadBalancerStatsRequest request) {
      return blockingUnaryCall(
          getChannel(), getGetClientStatsMethod(), getCallOptions(), request);
    }
  }

  /**
   * <pre>
   * A service used to obtain stats for verifying LB behavior.
   * </pre>
   */
  public static final class LoadBalancerStatsServiceFutureStub extends io.grpc.stub.AbstractFutureStub<LoadBalancerStatsServiceFutureStub> {
    private LoadBalancerStatsServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected LoadBalancerStatsServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new LoadBalancerStatsServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Gets the backend distribution for RPCs sent by a test client.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.testing.integration.Messages.LoadBalancerStatsResponse> getClientStats(
        io.grpc.testing.integration.Messages.LoadBalancerStatsRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getGetClientStatsMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_CLIENT_STATS = 0;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final LoadBalancerStatsServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(LoadBalancerStatsServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_GET_CLIENT_STATS:
          serviceImpl.getClientStats((io.grpc.testing.integration.Messages.LoadBalancerStatsRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.testing.integration.Messages.LoadBalancerStatsResponse>) responseObserver);
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

  private static abstract class LoadBalancerStatsServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    LoadBalancerStatsServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.grpc.testing.integration.Test.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("LoadBalancerStatsService");
    }
  }

  private static final class LoadBalancerStatsServiceFileDescriptorSupplier
      extends LoadBalancerStatsServiceBaseDescriptorSupplier {
    LoadBalancerStatsServiceFileDescriptorSupplier() {}
  }

  private static final class LoadBalancerStatsServiceMethodDescriptorSupplier
      extends LoadBalancerStatsServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    LoadBalancerStatsServiceMethodDescriptorSupplier(String methodName) {
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
      synchronized (LoadBalancerStatsServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new LoadBalancerStatsServiceFileDescriptorSupplier())
              .addMethod(getGetClientStatsMethod())
              .build();
        }
      }
    }
    return result;
  }
}
