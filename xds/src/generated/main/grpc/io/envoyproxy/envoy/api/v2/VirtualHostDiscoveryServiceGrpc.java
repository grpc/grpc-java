package io.envoyproxy.envoy.api.v2;

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
 * Virtual Host Discovery Service (VHDS) is used to dynamically update the list of virtual hosts for
 * a given RouteConfiguration. If VHDS is configured a virtual host list update will be triggered
 * during the processing of an HTTP request if a route for the request cannot be resolved. The
 * :ref:`resource_names_subscribe &lt;envoy_api_field_DeltaDiscoveryRequest.resource_names_subscribe&gt;`
 * field contains a list of virtual host names or aliases to track. The contents of an alias would
 * be the contents of a *host* or *authority* header used to make an http request. An xDS server
 * will match an alias to a virtual host based on the content of :ref:`domains'
 * &lt;envoy_api_field_route.VirtualHost.domains&gt;` field. The *resource_names_unsubscribe* field
 * contains a list of virtual host names that have been :ref:`unsubscribed
 * &lt;xds_protocol_unsubscribe&gt;` from the routing table associated with the RouteConfiguration.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: envoy/api/v2/rds.proto")
public final class VirtualHostDiscoveryServiceGrpc {

  private VirtualHostDiscoveryServiceGrpc() {}

  public static final String SERVICE_NAME = "envoy.api.v2.VirtualHostDiscoveryService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest,
      io.envoyproxy.envoy.api.v2.DeltaDiscoveryResponse> getDeltaVirtualHostsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeltaVirtualHosts",
      requestType = io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest.class,
      responseType = io.envoyproxy.envoy.api.v2.DeltaDiscoveryResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest,
      io.envoyproxy.envoy.api.v2.DeltaDiscoveryResponse> getDeltaVirtualHostsMethod() {
    io.grpc.MethodDescriptor<io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest, io.envoyproxy.envoy.api.v2.DeltaDiscoveryResponse> getDeltaVirtualHostsMethod;
    if ((getDeltaVirtualHostsMethod = VirtualHostDiscoveryServiceGrpc.getDeltaVirtualHostsMethod) == null) {
      synchronized (VirtualHostDiscoveryServiceGrpc.class) {
        if ((getDeltaVirtualHostsMethod = VirtualHostDiscoveryServiceGrpc.getDeltaVirtualHostsMethod) == null) {
          VirtualHostDiscoveryServiceGrpc.getDeltaVirtualHostsMethod = getDeltaVirtualHostsMethod =
              io.grpc.MethodDescriptor.<io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest, io.envoyproxy.envoy.api.v2.DeltaDiscoveryResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeltaVirtualHosts"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.envoyproxy.envoy.api.v2.DeltaDiscoveryResponse.getDefaultInstance()))
              .setSchemaDescriptor(new VirtualHostDiscoveryServiceMethodDescriptorSupplier("DeltaVirtualHosts"))
              .build();
        }
      }
    }
    return getDeltaVirtualHostsMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static VirtualHostDiscoveryServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<VirtualHostDiscoveryServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<VirtualHostDiscoveryServiceStub>() {
        @java.lang.Override
        public VirtualHostDiscoveryServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new VirtualHostDiscoveryServiceStub(channel, callOptions);
        }
      };
    return VirtualHostDiscoveryServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static VirtualHostDiscoveryServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<VirtualHostDiscoveryServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<VirtualHostDiscoveryServiceBlockingStub>() {
        @java.lang.Override
        public VirtualHostDiscoveryServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new VirtualHostDiscoveryServiceBlockingStub(channel, callOptions);
        }
      };
    return VirtualHostDiscoveryServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static VirtualHostDiscoveryServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<VirtualHostDiscoveryServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<VirtualHostDiscoveryServiceFutureStub>() {
        @java.lang.Override
        public VirtualHostDiscoveryServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new VirtualHostDiscoveryServiceFutureStub(channel, callOptions);
        }
      };
    return VirtualHostDiscoveryServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * Virtual Host Discovery Service (VHDS) is used to dynamically update the list of virtual hosts for
   * a given RouteConfiguration. If VHDS is configured a virtual host list update will be triggered
   * during the processing of an HTTP request if a route for the request cannot be resolved. The
   * :ref:`resource_names_subscribe &lt;envoy_api_field_DeltaDiscoveryRequest.resource_names_subscribe&gt;`
   * field contains a list of virtual host names or aliases to track. The contents of an alias would
   * be the contents of a *host* or *authority* header used to make an http request. An xDS server
   * will match an alias to a virtual host based on the content of :ref:`domains'
   * &lt;envoy_api_field_route.VirtualHost.domains&gt;` field. The *resource_names_unsubscribe* field
   * contains a list of virtual host names that have been :ref:`unsubscribed
   * &lt;xds_protocol_unsubscribe&gt;` from the routing table associated with the RouteConfiguration.
   * </pre>
   */
  public static abstract class VirtualHostDiscoveryServiceImplBase implements io.grpc.BindableService {

    /**
     */
    public io.grpc.stub.StreamObserver<io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest> deltaVirtualHosts(
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.api.v2.DeltaDiscoveryResponse> responseObserver) {
      return asyncUnimplementedStreamingCall(getDeltaVirtualHostsMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getDeltaVirtualHostsMethod(),
            asyncBidiStreamingCall(
              new MethodHandlers<
                io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest,
                io.envoyproxy.envoy.api.v2.DeltaDiscoveryResponse>(
                  this, METHODID_DELTA_VIRTUAL_HOSTS)))
          .build();
    }
  }

  /**
   * <pre>
   * Virtual Host Discovery Service (VHDS) is used to dynamically update the list of virtual hosts for
   * a given RouteConfiguration. If VHDS is configured a virtual host list update will be triggered
   * during the processing of an HTTP request if a route for the request cannot be resolved. The
   * :ref:`resource_names_subscribe &lt;envoy_api_field_DeltaDiscoveryRequest.resource_names_subscribe&gt;`
   * field contains a list of virtual host names or aliases to track. The contents of an alias would
   * be the contents of a *host* or *authority* header used to make an http request. An xDS server
   * will match an alias to a virtual host based on the content of :ref:`domains'
   * &lt;envoy_api_field_route.VirtualHost.domains&gt;` field. The *resource_names_unsubscribe* field
   * contains a list of virtual host names that have been :ref:`unsubscribed
   * &lt;xds_protocol_unsubscribe&gt;` from the routing table associated with the RouteConfiguration.
   * </pre>
   */
  public static final class VirtualHostDiscoveryServiceStub extends io.grpc.stub.AbstractAsyncStub<VirtualHostDiscoveryServiceStub> {
    private VirtualHostDiscoveryServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected VirtualHostDiscoveryServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new VirtualHostDiscoveryServiceStub(channel, callOptions);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest> deltaVirtualHosts(
        io.grpc.stub.StreamObserver<io.envoyproxy.envoy.api.v2.DeltaDiscoveryResponse> responseObserver) {
      return asyncBidiStreamingCall(
          getChannel().newCall(getDeltaVirtualHostsMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   * <pre>
   * Virtual Host Discovery Service (VHDS) is used to dynamically update the list of virtual hosts for
   * a given RouteConfiguration. If VHDS is configured a virtual host list update will be triggered
   * during the processing of an HTTP request if a route for the request cannot be resolved. The
   * :ref:`resource_names_subscribe &lt;envoy_api_field_DeltaDiscoveryRequest.resource_names_subscribe&gt;`
   * field contains a list of virtual host names or aliases to track. The contents of an alias would
   * be the contents of a *host* or *authority* header used to make an http request. An xDS server
   * will match an alias to a virtual host based on the content of :ref:`domains'
   * &lt;envoy_api_field_route.VirtualHost.domains&gt;` field. The *resource_names_unsubscribe* field
   * contains a list of virtual host names that have been :ref:`unsubscribed
   * &lt;xds_protocol_unsubscribe&gt;` from the routing table associated with the RouteConfiguration.
   * </pre>
   */
  public static final class VirtualHostDiscoveryServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<VirtualHostDiscoveryServiceBlockingStub> {
    private VirtualHostDiscoveryServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected VirtualHostDiscoveryServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new VirtualHostDiscoveryServiceBlockingStub(channel, callOptions);
    }
  }

  /**
   * <pre>
   * Virtual Host Discovery Service (VHDS) is used to dynamically update the list of virtual hosts for
   * a given RouteConfiguration. If VHDS is configured a virtual host list update will be triggered
   * during the processing of an HTTP request if a route for the request cannot be resolved. The
   * :ref:`resource_names_subscribe &lt;envoy_api_field_DeltaDiscoveryRequest.resource_names_subscribe&gt;`
   * field contains a list of virtual host names or aliases to track. The contents of an alias would
   * be the contents of a *host* or *authority* header used to make an http request. An xDS server
   * will match an alias to a virtual host based on the content of :ref:`domains'
   * &lt;envoy_api_field_route.VirtualHost.domains&gt;` field. The *resource_names_unsubscribe* field
   * contains a list of virtual host names that have been :ref:`unsubscribed
   * &lt;xds_protocol_unsubscribe&gt;` from the routing table associated with the RouteConfiguration.
   * </pre>
   */
  public static final class VirtualHostDiscoveryServiceFutureStub extends io.grpc.stub.AbstractFutureStub<VirtualHostDiscoveryServiceFutureStub> {
    private VirtualHostDiscoveryServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected VirtualHostDiscoveryServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new VirtualHostDiscoveryServiceFutureStub(channel, callOptions);
    }
  }

  private static final int METHODID_DELTA_VIRTUAL_HOSTS = 0;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final VirtualHostDiscoveryServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(VirtualHostDiscoveryServiceImplBase serviceImpl, int methodId) {
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
        case METHODID_DELTA_VIRTUAL_HOSTS:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.deltaVirtualHosts(
              (io.grpc.stub.StreamObserver<io.envoyproxy.envoy.api.v2.DeltaDiscoveryResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class VirtualHostDiscoveryServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    VirtualHostDiscoveryServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.envoyproxy.envoy.api.v2.RdsProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("VirtualHostDiscoveryService");
    }
  }

  private static final class VirtualHostDiscoveryServiceFileDescriptorSupplier
      extends VirtualHostDiscoveryServiceBaseDescriptorSupplier {
    VirtualHostDiscoveryServiceFileDescriptorSupplier() {}
  }

  private static final class VirtualHostDiscoveryServiceMethodDescriptorSupplier
      extends VirtualHostDiscoveryServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    VirtualHostDiscoveryServiceMethodDescriptorSupplier(String methodName) {
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
      synchronized (VirtualHostDiscoveryServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new VirtualHostDiscoveryServiceFileDescriptorSupplier())
              .addMethod(getDeltaVirtualHostsMethod())
              .build();
        }
      }
    }
    return result;
  }
}
