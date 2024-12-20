package io.grpc.channelz.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * Channelz is a service exposed by gRPC servers that provides detailed debug
 * information.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler",
    comments = "Source: grpc/channelz/v1/channelz.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class ChannelzGrpc {

  private ChannelzGrpc() {}

  public static final java.lang.String SERVICE_NAME = "grpc.channelz.v1.Channelz";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetTopChannelsRequest,
      io.grpc.channelz.v1.GetTopChannelsResponse> getGetTopChannelsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTopChannels",
      requestType = io.grpc.channelz.v1.GetTopChannelsRequest.class,
      responseType = io.grpc.channelz.v1.GetTopChannelsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetTopChannelsRequest,
      io.grpc.channelz.v1.GetTopChannelsResponse> getGetTopChannelsMethod() {
    io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetTopChannelsRequest, io.grpc.channelz.v1.GetTopChannelsResponse> getGetTopChannelsMethod;
    if ((getGetTopChannelsMethod = ChannelzGrpc.getGetTopChannelsMethod) == null) {
      synchronized (ChannelzGrpc.class) {
        if ((getGetTopChannelsMethod = ChannelzGrpc.getGetTopChannelsMethod) == null) {
          ChannelzGrpc.getGetTopChannelsMethod = getGetTopChannelsMethod =
              io.grpc.MethodDescriptor.<io.grpc.channelz.v1.GetTopChannelsRequest, io.grpc.channelz.v1.GetTopChannelsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTopChannels"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.channelz.v1.GetTopChannelsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.channelz.v1.GetTopChannelsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ChannelzMethodDescriptorSupplier("GetTopChannels"))
              .build();
        }
      }
    }
    return getGetTopChannelsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetServersRequest,
      io.grpc.channelz.v1.GetServersResponse> getGetServersMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetServers",
      requestType = io.grpc.channelz.v1.GetServersRequest.class,
      responseType = io.grpc.channelz.v1.GetServersResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetServersRequest,
      io.grpc.channelz.v1.GetServersResponse> getGetServersMethod() {
    io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetServersRequest, io.grpc.channelz.v1.GetServersResponse> getGetServersMethod;
    if ((getGetServersMethod = ChannelzGrpc.getGetServersMethod) == null) {
      synchronized (ChannelzGrpc.class) {
        if ((getGetServersMethod = ChannelzGrpc.getGetServersMethod) == null) {
          ChannelzGrpc.getGetServersMethod = getGetServersMethod =
              io.grpc.MethodDescriptor.<io.grpc.channelz.v1.GetServersRequest, io.grpc.channelz.v1.GetServersResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetServers"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.channelz.v1.GetServersRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.channelz.v1.GetServersResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ChannelzMethodDescriptorSupplier("GetServers"))
              .build();
        }
      }
    }
    return getGetServersMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetServerRequest,
      io.grpc.channelz.v1.GetServerResponse> getGetServerMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetServer",
      requestType = io.grpc.channelz.v1.GetServerRequest.class,
      responseType = io.grpc.channelz.v1.GetServerResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetServerRequest,
      io.grpc.channelz.v1.GetServerResponse> getGetServerMethod() {
    io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetServerRequest, io.grpc.channelz.v1.GetServerResponse> getGetServerMethod;
    if ((getGetServerMethod = ChannelzGrpc.getGetServerMethod) == null) {
      synchronized (ChannelzGrpc.class) {
        if ((getGetServerMethod = ChannelzGrpc.getGetServerMethod) == null) {
          ChannelzGrpc.getGetServerMethod = getGetServerMethod =
              io.grpc.MethodDescriptor.<io.grpc.channelz.v1.GetServerRequest, io.grpc.channelz.v1.GetServerResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetServer"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.channelz.v1.GetServerRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.channelz.v1.GetServerResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ChannelzMethodDescriptorSupplier("GetServer"))
              .build();
        }
      }
    }
    return getGetServerMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetServerSocketsRequest,
      io.grpc.channelz.v1.GetServerSocketsResponse> getGetServerSocketsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetServerSockets",
      requestType = io.grpc.channelz.v1.GetServerSocketsRequest.class,
      responseType = io.grpc.channelz.v1.GetServerSocketsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetServerSocketsRequest,
      io.grpc.channelz.v1.GetServerSocketsResponse> getGetServerSocketsMethod() {
    io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetServerSocketsRequest, io.grpc.channelz.v1.GetServerSocketsResponse> getGetServerSocketsMethod;
    if ((getGetServerSocketsMethod = ChannelzGrpc.getGetServerSocketsMethod) == null) {
      synchronized (ChannelzGrpc.class) {
        if ((getGetServerSocketsMethod = ChannelzGrpc.getGetServerSocketsMethod) == null) {
          ChannelzGrpc.getGetServerSocketsMethod = getGetServerSocketsMethod =
              io.grpc.MethodDescriptor.<io.grpc.channelz.v1.GetServerSocketsRequest, io.grpc.channelz.v1.GetServerSocketsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetServerSockets"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.channelz.v1.GetServerSocketsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.channelz.v1.GetServerSocketsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ChannelzMethodDescriptorSupplier("GetServerSockets"))
              .build();
        }
      }
    }
    return getGetServerSocketsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetChannelRequest,
      io.grpc.channelz.v1.GetChannelResponse> getGetChannelMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetChannel",
      requestType = io.grpc.channelz.v1.GetChannelRequest.class,
      responseType = io.grpc.channelz.v1.GetChannelResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetChannelRequest,
      io.grpc.channelz.v1.GetChannelResponse> getGetChannelMethod() {
    io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetChannelRequest, io.grpc.channelz.v1.GetChannelResponse> getGetChannelMethod;
    if ((getGetChannelMethod = ChannelzGrpc.getGetChannelMethod) == null) {
      synchronized (ChannelzGrpc.class) {
        if ((getGetChannelMethod = ChannelzGrpc.getGetChannelMethod) == null) {
          ChannelzGrpc.getGetChannelMethod = getGetChannelMethod =
              io.grpc.MethodDescriptor.<io.grpc.channelz.v1.GetChannelRequest, io.grpc.channelz.v1.GetChannelResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetChannel"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.channelz.v1.GetChannelRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.channelz.v1.GetChannelResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ChannelzMethodDescriptorSupplier("GetChannel"))
              .build();
        }
      }
    }
    return getGetChannelMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetSubchannelRequest,
      io.grpc.channelz.v1.GetSubchannelResponse> getGetSubchannelMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetSubchannel",
      requestType = io.grpc.channelz.v1.GetSubchannelRequest.class,
      responseType = io.grpc.channelz.v1.GetSubchannelResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetSubchannelRequest,
      io.grpc.channelz.v1.GetSubchannelResponse> getGetSubchannelMethod() {
    io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetSubchannelRequest, io.grpc.channelz.v1.GetSubchannelResponse> getGetSubchannelMethod;
    if ((getGetSubchannelMethod = ChannelzGrpc.getGetSubchannelMethod) == null) {
      synchronized (ChannelzGrpc.class) {
        if ((getGetSubchannelMethod = ChannelzGrpc.getGetSubchannelMethod) == null) {
          ChannelzGrpc.getGetSubchannelMethod = getGetSubchannelMethod =
              io.grpc.MethodDescriptor.<io.grpc.channelz.v1.GetSubchannelRequest, io.grpc.channelz.v1.GetSubchannelResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetSubchannel"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.channelz.v1.GetSubchannelRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.channelz.v1.GetSubchannelResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ChannelzMethodDescriptorSupplier("GetSubchannel"))
              .build();
        }
      }
    }
    return getGetSubchannelMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetSocketRequest,
      io.grpc.channelz.v1.GetSocketResponse> getGetSocketMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetSocket",
      requestType = io.grpc.channelz.v1.GetSocketRequest.class,
      responseType = io.grpc.channelz.v1.GetSocketResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetSocketRequest,
      io.grpc.channelz.v1.GetSocketResponse> getGetSocketMethod() {
    io.grpc.MethodDescriptor<io.grpc.channelz.v1.GetSocketRequest, io.grpc.channelz.v1.GetSocketResponse> getGetSocketMethod;
    if ((getGetSocketMethod = ChannelzGrpc.getGetSocketMethod) == null) {
      synchronized (ChannelzGrpc.class) {
        if ((getGetSocketMethod = ChannelzGrpc.getGetSocketMethod) == null) {
          ChannelzGrpc.getGetSocketMethod = getGetSocketMethod =
              io.grpc.MethodDescriptor.<io.grpc.channelz.v1.GetSocketRequest, io.grpc.channelz.v1.GetSocketResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetSocket"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.channelz.v1.GetSocketRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.grpc.channelz.v1.GetSocketResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ChannelzMethodDescriptorSupplier("GetSocket"))
              .build();
        }
      }
    }
    return getGetSocketMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ChannelzStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ChannelzStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ChannelzStub>() {
        @java.lang.Override
        public ChannelzStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ChannelzStub(channel, callOptions);
        }
      };
    return ChannelzStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static ChannelzBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ChannelzBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ChannelzBlockingV2Stub>() {
        @java.lang.Override
        public ChannelzBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ChannelzBlockingV2Stub(channel, callOptions);
        }
      };
    return ChannelzBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ChannelzBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ChannelzBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ChannelzBlockingStub>() {
        @java.lang.Override
        public ChannelzBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ChannelzBlockingStub(channel, callOptions);
        }
      };
    return ChannelzBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ChannelzFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ChannelzFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ChannelzFutureStub>() {
        @java.lang.Override
        public ChannelzFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ChannelzFutureStub(channel, callOptions);
        }
      };
    return ChannelzFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * Channelz is a service exposed by gRPC servers that provides detailed debug
   * information.
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Gets all root channels (i.e. channels the application has directly
     * created). This does not include subchannels nor non-top level channels.
     * </pre>
     */
    default void getTopChannels(io.grpc.channelz.v1.GetTopChannelsRequest request,
        io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetTopChannelsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTopChannelsMethod(), responseObserver);
    }

    /**
     * <pre>
     * Gets all servers that exist in the process.
     * </pre>
     */
    default void getServers(io.grpc.channelz.v1.GetServersRequest request,
        io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetServersResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetServersMethod(), responseObserver);
    }

    /**
     * <pre>
     * Returns a single Server, or else a NOT_FOUND code.
     * </pre>
     */
    default void getServer(io.grpc.channelz.v1.GetServerRequest request,
        io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetServerResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetServerMethod(), responseObserver);
    }

    /**
     * <pre>
     * Gets all server sockets that exist in the process.
     * </pre>
     */
    default void getServerSockets(io.grpc.channelz.v1.GetServerSocketsRequest request,
        io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetServerSocketsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetServerSocketsMethod(), responseObserver);
    }

    /**
     * <pre>
     * Returns a single Channel, or else a NOT_FOUND code.
     * </pre>
     */
    default void getChannel(io.grpc.channelz.v1.GetChannelRequest request,
        io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetChannelResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetChannelMethod(), responseObserver);
    }

    /**
     * <pre>
     * Returns a single Subchannel, or else a NOT_FOUND code.
     * </pre>
     */
    default void getSubchannel(io.grpc.channelz.v1.GetSubchannelRequest request,
        io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetSubchannelResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetSubchannelMethod(), responseObserver);
    }

    /**
     * <pre>
     * Returns a single Socket or else a NOT_FOUND code.
     * </pre>
     */
    default void getSocket(io.grpc.channelz.v1.GetSocketRequest request,
        io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetSocketResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetSocketMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service Channelz.
   * <pre>
   * Channelz is a service exposed by gRPC servers that provides detailed debug
   * information.
   * </pre>
   */
  public static abstract class ChannelzImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ChannelzGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service Channelz.
   * <pre>
   * Channelz is a service exposed by gRPC servers that provides detailed debug
   * information.
   * </pre>
   */
  public static final class ChannelzStub
      extends io.grpc.stub.AbstractAsyncStub<ChannelzStub> {
    private ChannelzStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ChannelzStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ChannelzStub(channel, callOptions);
    }

    /**
     * <pre>
     * Gets all root channels (i.e. channels the application has directly
     * created). This does not include subchannels nor non-top level channels.
     * </pre>
     */
    public void getTopChannels(io.grpc.channelz.v1.GetTopChannelsRequest request,
        io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetTopChannelsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetTopChannelsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Gets all servers that exist in the process.
     * </pre>
     */
    public void getServers(io.grpc.channelz.v1.GetServersRequest request,
        io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetServersResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetServersMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Returns a single Server, or else a NOT_FOUND code.
     * </pre>
     */
    public void getServer(io.grpc.channelz.v1.GetServerRequest request,
        io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetServerResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetServerMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Gets all server sockets that exist in the process.
     * </pre>
     */
    public void getServerSockets(io.grpc.channelz.v1.GetServerSocketsRequest request,
        io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetServerSocketsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetServerSocketsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Returns a single Channel, or else a NOT_FOUND code.
     * </pre>
     */
    public void getChannel(io.grpc.channelz.v1.GetChannelRequest request,
        io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetChannelResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetChannelMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Returns a single Subchannel, or else a NOT_FOUND code.
     * </pre>
     */
    public void getSubchannel(io.grpc.channelz.v1.GetSubchannelRequest request,
        io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetSubchannelResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetSubchannelMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Returns a single Socket or else a NOT_FOUND code.
     * </pre>
     */
    public void getSocket(io.grpc.channelz.v1.GetSocketRequest request,
        io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetSocketResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetSocketMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service Channelz.
   * <pre>
   * Channelz is a service exposed by gRPC servers that provides detailed debug
   * information.
   * </pre>
   */
  public static final class ChannelzBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<ChannelzBlockingV2Stub> {
    private ChannelzBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ChannelzBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ChannelzBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Gets all root channels (i.e. channels the application has directly
     * created). This does not include subchannels nor non-top level channels.
     * </pre>
     */
    public io.grpc.channelz.v1.GetTopChannelsResponse getTopChannels(io.grpc.channelz.v1.GetTopChannelsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTopChannelsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Gets all servers that exist in the process.
     * </pre>
     */
    public io.grpc.channelz.v1.GetServersResponse getServers(io.grpc.channelz.v1.GetServersRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetServersMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Returns a single Server, or else a NOT_FOUND code.
     * </pre>
     */
    public io.grpc.channelz.v1.GetServerResponse getServer(io.grpc.channelz.v1.GetServerRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetServerMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Gets all server sockets that exist in the process.
     * </pre>
     */
    public io.grpc.channelz.v1.GetServerSocketsResponse getServerSockets(io.grpc.channelz.v1.GetServerSocketsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetServerSocketsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Returns a single Channel, or else a NOT_FOUND code.
     * </pre>
     */
    public io.grpc.channelz.v1.GetChannelResponse getChannel(io.grpc.channelz.v1.GetChannelRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetChannelMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Returns a single Subchannel, or else a NOT_FOUND code.
     * </pre>
     */
    public io.grpc.channelz.v1.GetSubchannelResponse getSubchannel(io.grpc.channelz.v1.GetSubchannelRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetSubchannelMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Returns a single Socket or else a NOT_FOUND code.
     * </pre>
     */
    public io.grpc.channelz.v1.GetSocketResponse getSocket(io.grpc.channelz.v1.GetSocketRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetSocketMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do llimited synchronous rpc calls to service Channelz.
   * <pre>
   * Channelz is a service exposed by gRPC servers that provides detailed debug
   * information.
   * </pre>
   */
  public static final class ChannelzBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ChannelzBlockingStub> {
    private ChannelzBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ChannelzBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ChannelzBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Gets all root channels (i.e. channels the application has directly
     * created). This does not include subchannels nor non-top level channels.
     * </pre>
     */
    public io.grpc.channelz.v1.GetTopChannelsResponse getTopChannels(io.grpc.channelz.v1.GetTopChannelsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTopChannelsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Gets all servers that exist in the process.
     * </pre>
     */
    public io.grpc.channelz.v1.GetServersResponse getServers(io.grpc.channelz.v1.GetServersRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetServersMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Returns a single Server, or else a NOT_FOUND code.
     * </pre>
     */
    public io.grpc.channelz.v1.GetServerResponse getServer(io.grpc.channelz.v1.GetServerRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetServerMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Gets all server sockets that exist in the process.
     * </pre>
     */
    public io.grpc.channelz.v1.GetServerSocketsResponse getServerSockets(io.grpc.channelz.v1.GetServerSocketsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetServerSocketsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Returns a single Channel, or else a NOT_FOUND code.
     * </pre>
     */
    public io.grpc.channelz.v1.GetChannelResponse getChannel(io.grpc.channelz.v1.GetChannelRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetChannelMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Returns a single Subchannel, or else a NOT_FOUND code.
     * </pre>
     */
    public io.grpc.channelz.v1.GetSubchannelResponse getSubchannel(io.grpc.channelz.v1.GetSubchannelRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetSubchannelMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Returns a single Socket or else a NOT_FOUND code.
     * </pre>
     */
    public io.grpc.channelz.v1.GetSocketResponse getSocket(io.grpc.channelz.v1.GetSocketRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetSocketMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service Channelz.
   * <pre>
   * Channelz is a service exposed by gRPC servers that provides detailed debug
   * information.
   * </pre>
   */
  public static final class ChannelzFutureStub
      extends io.grpc.stub.AbstractFutureStub<ChannelzFutureStub> {
    private ChannelzFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ChannelzFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ChannelzFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Gets all root channels (i.e. channels the application has directly
     * created). This does not include subchannels nor non-top level channels.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.channelz.v1.GetTopChannelsResponse> getTopChannels(
        io.grpc.channelz.v1.GetTopChannelsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetTopChannelsMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Gets all servers that exist in the process.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.channelz.v1.GetServersResponse> getServers(
        io.grpc.channelz.v1.GetServersRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetServersMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Returns a single Server, or else a NOT_FOUND code.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.channelz.v1.GetServerResponse> getServer(
        io.grpc.channelz.v1.GetServerRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetServerMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Gets all server sockets that exist in the process.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.channelz.v1.GetServerSocketsResponse> getServerSockets(
        io.grpc.channelz.v1.GetServerSocketsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetServerSocketsMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Returns a single Channel, or else a NOT_FOUND code.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.channelz.v1.GetChannelResponse> getChannel(
        io.grpc.channelz.v1.GetChannelRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetChannelMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Returns a single Subchannel, or else a NOT_FOUND code.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.channelz.v1.GetSubchannelResponse> getSubchannel(
        io.grpc.channelz.v1.GetSubchannelRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetSubchannelMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Returns a single Socket or else a NOT_FOUND code.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.channelz.v1.GetSocketResponse> getSocket(
        io.grpc.channelz.v1.GetSocketRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetSocketMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_TOP_CHANNELS = 0;
  private static final int METHODID_GET_SERVERS = 1;
  private static final int METHODID_GET_SERVER = 2;
  private static final int METHODID_GET_SERVER_SOCKETS = 3;
  private static final int METHODID_GET_CHANNEL = 4;
  private static final int METHODID_GET_SUBCHANNEL = 5;
  private static final int METHODID_GET_SOCKET = 6;

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
        case METHODID_GET_TOP_CHANNELS:
          serviceImpl.getTopChannels((io.grpc.channelz.v1.GetTopChannelsRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetTopChannelsResponse>) responseObserver);
          break;
        case METHODID_GET_SERVERS:
          serviceImpl.getServers((io.grpc.channelz.v1.GetServersRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetServersResponse>) responseObserver);
          break;
        case METHODID_GET_SERVER:
          serviceImpl.getServer((io.grpc.channelz.v1.GetServerRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetServerResponse>) responseObserver);
          break;
        case METHODID_GET_SERVER_SOCKETS:
          serviceImpl.getServerSockets((io.grpc.channelz.v1.GetServerSocketsRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetServerSocketsResponse>) responseObserver);
          break;
        case METHODID_GET_CHANNEL:
          serviceImpl.getChannel((io.grpc.channelz.v1.GetChannelRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetChannelResponse>) responseObserver);
          break;
        case METHODID_GET_SUBCHANNEL:
          serviceImpl.getSubchannel((io.grpc.channelz.v1.GetSubchannelRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetSubchannelResponse>) responseObserver);
          break;
        case METHODID_GET_SOCKET:
          serviceImpl.getSocket((io.grpc.channelz.v1.GetSocketRequest) request,
              (io.grpc.stub.StreamObserver<io.grpc.channelz.v1.GetSocketResponse>) responseObserver);
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
          getGetTopChannelsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.channelz.v1.GetTopChannelsRequest,
              io.grpc.channelz.v1.GetTopChannelsResponse>(
                service, METHODID_GET_TOP_CHANNELS)))
        .addMethod(
          getGetServersMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.channelz.v1.GetServersRequest,
              io.grpc.channelz.v1.GetServersResponse>(
                service, METHODID_GET_SERVERS)))
        .addMethod(
          getGetServerMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.channelz.v1.GetServerRequest,
              io.grpc.channelz.v1.GetServerResponse>(
                service, METHODID_GET_SERVER)))
        .addMethod(
          getGetServerSocketsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.channelz.v1.GetServerSocketsRequest,
              io.grpc.channelz.v1.GetServerSocketsResponse>(
                service, METHODID_GET_SERVER_SOCKETS)))
        .addMethod(
          getGetChannelMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.channelz.v1.GetChannelRequest,
              io.grpc.channelz.v1.GetChannelResponse>(
                service, METHODID_GET_CHANNEL)))
        .addMethod(
          getGetSubchannelMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.channelz.v1.GetSubchannelRequest,
              io.grpc.channelz.v1.GetSubchannelResponse>(
                service, METHODID_GET_SUBCHANNEL)))
        .addMethod(
          getGetSocketMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.grpc.channelz.v1.GetSocketRequest,
              io.grpc.channelz.v1.GetSocketResponse>(
                service, METHODID_GET_SOCKET)))
        .build();
  }

  private static abstract class ChannelzBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ChannelzBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.grpc.channelz.v1.ChannelzProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("Channelz");
    }
  }

  private static final class ChannelzFileDescriptorSupplier
      extends ChannelzBaseDescriptorSupplier {
    ChannelzFileDescriptorSupplier() {}
  }

  private static final class ChannelzMethodDescriptorSupplier
      extends ChannelzBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ChannelzMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ChannelzGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ChannelzFileDescriptorSupplier())
              .addMethod(getGetTopChannelsMethod())
              .addMethod(getGetServersMethod())
              .addMethod(getGetServerMethod())
              .addMethod(getGetServerSocketsMethod())
              .addMethod(getGetChannelMethod())
              .addMethod(getGetSubchannelMethod())
              .addMethod(getGetSocketMethod())
              .build();
        }
      }
    }
    return result;
  }
}
