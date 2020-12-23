package io.envoyproxy.envoy.service.discovery.v3;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * See https://github.com/lyft/envoy-api#apis for a description of the role of
 * ADS and how it is intended to be used by a management server. ADS requests
 * have the same structure as their singleton xDS counterparts, but can
 * multiplex many resource types on a single stream. The type_url in the
 * DiscoveryRequest/DiscoveryResponse provides sufficient information to recover
 * the multiplexed singleton APIs at the Envoy instance and management server.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: envoy/service/discovery/v3/ads.proto")
public final class AggregatedDiscoveryServiceGrpc {

  private AggregatedDiscoveryServiceGrpc() {}

  public static final String SERVICE_NAME = "envoy.service.discovery.v3.AggregatedDiscoveryService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest,
      io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse> getStreamAggregatedResourcesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StreamAggregatedResources",
      requestType = io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest.class,
      responseType = io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest,
      io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse> getStreamAggregatedResourcesMethod() {
    io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest, io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse> getStreamAggregatedResourcesMethod;
    if ((getStreamAggregatedResourcesMethod = AggregatedDiscoveryServiceGrpc.getStreamAggregatedResourcesMethod) == null) {
      synchronized (AggregatedDiscoveryServiceGrpc.class) {
        if ((getStreamAggregatedResourcesMethod = AggregatedDiscoveryServiceGrpc.getStreamAggregatedResourcesMethod) == null) {
          AggregatedDiscoveryServiceGrpc.getStreamAggregatedResourcesMethod = getStreamAggregatedResourcesMethod =
              io.grpc.MethodDescriptor.<io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest, io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StreamAggregatedResources"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse.getDefaultInstance()))
              .setSchemaDescriptor(new AggregatedDiscoveryServiceMethodDescriptorSupplier("StreamAggregatedResources"))
              .build();
        }
      }
    }
    return getStreamAggregatedResourcesMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest,
      io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse> getDeltaAggregatedResourcesMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeltaAggregatedResources",
      requestType = io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest.class,
      responseType = io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest,
      io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse> getDeltaAggregatedResourcesMethod() {
    io.grpc.MethodDescriptor<io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest, io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse> getDeltaAggregatedResourcesMethod;
    if ((getDeltaAggregatedResourcesMethod = AggregatedDiscoveryServiceGrpc.getDeltaAggregatedResourcesMethod) == null) {
      synchronized (AggregatedDiscoveryServiceGrpc.class) {
        if ((getDeltaAggregatedResourcesMethod = AggregatedDiscoveryServiceGrpc.getDeltaAggregatedResourcesMethod) == null) {
          AggregatedDiscoveryServiceGrpc.getDeltaAggregatedResourcesMethod = getDeltaAggregatedResourcesMethod =
              io.grpc.MethodDescriptor.<io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest, io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeltaAggregatedResources"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse.getDefaultInstance()))
              .setSchemaDescriptor(new AggregatedDiscoveryServiceMethodDescriptorSupplier("DeltaAggregatedResources"))
              .build();
        }
      }
    }
    return getDeltaAggregatedResourcesMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static AggregatedDiscoveryServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AggregatedDiscoveryServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AggregatedDiscoveryServiceStub>() {
        @java.lang.Override
        public AggregatedDiscoveryServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AggregatedDiscoveryServiceStub(channel, callOptions);
        }
      };
    return AggregatedDiscoveryServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static AggregatedDiscoveryServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AggregatedDiscoveryServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AggregatedDiscoveryServiceBlockingStub>() {
        @java.lang.Override
        public AggregatedDiscoveryServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AggregatedDiscoveryServiceBlockingStub(channel, callOptions);
        }
      };
    return AggregatedDiscoveryServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static AggregatedDiscoveryServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<AggregatedDiscoveryServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<AggregatedDiscoveryServiceFutureStub>() {
        @java.lang.Override
        public AggregatedDiscoveryServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new AggregatedDiscoveryServiceFutureStub(channel, callOptions);
        }
      };
    return AggregatedDiscoveryServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * See https://github.com/lyft/envoy-api#apis for a description of the role of
   * ADS and how it is intended to be used by a management server. ADS requests
   * have the same structure as their singleton xDS counterparts, but can
   * multiplex many resource types on a single stream. The type_url in the
   * DiscoveryRequest/DiscoveryResponse provides sufficient information to recover
   * the multiplexed singleton APIs at the Envoy instance and management server.
   * </pre>
   */
  public static abstract class AggregatedDiscoveryServiceImplBase implements io.grpc.BindableService {

    /**
     * <pre>
     * This is a gRPC-only API.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest> streamAggregatedResources(
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getStreamAggregatedResourcesMethod(), responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest> deltaAggregatedResources(
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getDeltaAggregatedResourcesMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getStreamAggregatedResourcesMethod(),
            io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
              new MethodHandlers<
                io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest,
                io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse>(
                  this, METHODID_STREAM_AGGREGATED_RESOURCES)))
          .addMethod(
            getDeltaAggregatedResourcesMethod(),
            io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
              new MethodHandlers<
                io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest,
                io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse>(
                  this, METHODID_DELTA_AGGREGATED_RESOURCES)))
          .build();
    }
  }

  /**
   * <pre>
   * See https://github.com/lyft/envoy-api#apis for a description of the role of
   * ADS and how it is intended to be used by a management server. ADS requests
   * have the same structure as their singleton xDS counterparts, but can
   * multiplex many resource types on a single stream. The type_url in the
   * DiscoveryRequest/DiscoveryResponse provides sufficient information to recover
   * the multiplexed singleton APIs at the Envoy instance and management server.
   * </pre>
   */
  public static final class AggregatedDiscoveryServiceStub extends io.grpc.stub.AbstractAsyncStub<AggregatedDiscoveryServiceStub> {
    private AggregatedDiscoveryServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AggregatedDiscoveryServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AggregatedDiscoveryServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * This is a gRPC-only API.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest> streamAggregatedResources(
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getStreamAggregatedResourcesMethod(), getCallOptions()), responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest> deltaAggregatedResources(
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getDeltaAggregatedResourcesMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   * <pre>
   * See https://github.com/lyft/envoy-api#apis for a description of the role of
   * ADS and how it is intended to be used by a management server. ADS requests
   * have the same structure as their singleton xDS counterparts, but can
   * multiplex many resource types on a single stream. The type_url in the
   * DiscoveryRequest/DiscoveryResponse provides sufficient information to recover
   * the multiplexed singleton APIs at the Envoy instance and management server.
   * </pre>
   */
  public static final class AggregatedDiscoveryServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<AggregatedDiscoveryServiceBlockingStub> {
    private AggregatedDiscoveryServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AggregatedDiscoveryServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AggregatedDiscoveryServiceBlockingStub(channel, callOptions);
    }
  }

  /**
   * <pre>
   * See https://github.com/lyft/envoy-api#apis for a description of the role of
   * ADS and how it is intended to be used by a management server. ADS requests
   * have the same structure as their singleton xDS counterparts, but can
   * multiplex many resource types on a single stream. The type_url in the
   * DiscoveryRequest/DiscoveryResponse provides sufficient information to recover
   * the multiplexed singleton APIs at the Envoy instance and management server.
   * </pre>
   */
  public static final class AggregatedDiscoveryServiceFutureStub extends io.grpc.stub.AbstractFutureStub<AggregatedDiscoveryServiceFutureStub> {
    private AggregatedDiscoveryServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AggregatedDiscoveryServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new AggregatedDiscoveryServiceFutureStub(channel, callOptions);
    }
  }

  private static final int METHODID_STREAM_AGGREGATED_RESOURCES = 0;
  private static final int METHODID_DELTA_AGGREGATED_RESOURCES = 1;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AggregatedDiscoveryServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(AggregatedDiscoveryServiceImplBase serviceImpl, int methodId) {
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
        case METHODID_STREAM_AGGREGATED_RESOURCES:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.streamAggregatedResources(
              (io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse>) responseObserver);
        case METHODID_DELTA_AGGREGATED_RESOURCES:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.deltaAggregatedResources(
              (io.grpc.stub.StreamObserver<io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class AggregatedDiscoveryServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    AggregatedDiscoveryServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.envoyproxy.envoy.service.discovery.v3.AdsProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("AggregatedDiscoveryService");
    }
  }

  private static final class AggregatedDiscoveryServiceFileDescriptorSupplier
      extends AggregatedDiscoveryServiceBaseDescriptorSupplier {
    AggregatedDiscoveryServiceFileDescriptorSupplier() {}
  }

  private static final class AggregatedDiscoveryServiceMethodDescriptorSupplier
      extends AggregatedDiscoveryServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    AggregatedDiscoveryServiceMethodDescriptorSupplier(String methodName) {
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
      synchronized (AggregatedDiscoveryServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new AggregatedDiscoveryServiceFileDescriptorSupplier())
              .addMethod(getStreamAggregatedResourcesMethod())
              .addMethod(getDeltaAggregatedResourcesMethod())
              .build();
        }
      }
    }
    return result;
  }
}
