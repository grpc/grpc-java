/*
 * Copyright 2024 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.ChannelCredentials;
import io.grpc.ClientCall;
import io.grpc.Context;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ResourceAllocatingChannelCredentials;
import io.grpc.Status;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.JsonUtil;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.XdsInitializationException;
import io.grpc.xds.client.XdsTransportFactory;
import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class GrpcXdsTransportFactory implements XdsTransportFactory {

  private final CallCredentials callCredentials;

  GrpcXdsTransportFactory(CallCredentials callCredentials) {
    this.callCredentials = callCredentials;
  }

  @Override
  public XdsTransport create(Bootstrapper.ServerInfo serverInfo) throws XdsInitializationException {
    return new GrpcXdsTransport(serverInfo, callCredentials);
  }

  @VisibleForTesting
  public XdsTransport createForTest(ManagedChannel channel) {
    return new GrpcXdsTransport(channel, callCredentials);
  }

  @VisibleForTesting
  static class GrpcXdsTransport implements XdsTransport {

    private final ManagedChannel channel;
    private final CallCredentials callCredentials;
    private final ImmutableList<Closeable> resources;

    public GrpcXdsTransport(Bootstrapper.ServerInfo serverInfo) throws XdsInitializationException {
      this(serverInfo, null);
    }

    @VisibleForTesting
    public GrpcXdsTransport(ManagedChannel channel) {
      this(channel, null);
    }

    public GrpcXdsTransport(Bootstrapper.ServerInfo serverInfo, CallCredentials callCredentials)
        throws XdsInitializationException {
      String target = serverInfo.target();
      Map<String, ?> implSpecificConfig = serverInfo.implSpecificConfig();
      String type = JsonUtil.getString(implSpecificConfig, "type");
      XdsCredentialsProvider provider =  XdsCredentialsRegistry.getDefaultRegistry()
          .getProvider(type);
      Map<String, ?> config = JsonUtil.getObject(implSpecificConfig, "config");
      if (config == null) {
        config = ImmutableMap.of();
      }
      ChannelCredentials channelCredentials = provider.newChannelCredentials(config);
      if (channelCredentials == null) {
        throw new XdsInitializationException(
            "Cannot create channel credentials of type " + type + " for target " + target);
      }
      // if {@code ChannelCredentials} instance has allocated resource of any type, save them to be
      // released once the channel is shutdown
      this.resources = (channelCredentials instanceof ResourceAllocatingChannelCredentials)
          ? ((ResourceAllocatingChannelCredentials) channelCredentials).getAllocatedResources()
          : ImmutableList.<Closeable>of();
      this.channel = Grpc.newChannelBuilder(target, channelCredentials)
          .keepAliveTime(5, TimeUnit.MINUTES)
          .build();
      this.callCredentials = callCredentials;
    }

    @VisibleForTesting
    public GrpcXdsTransport(ManagedChannel channel, CallCredentials callCredentials) {
      this.channel = checkNotNull(channel, "channel");
      this.callCredentials = callCredentials;
      this.resources = ImmutableList.<Closeable>of();
    }

    @Override
    public <ReqT, RespT> StreamingCall<ReqT, RespT> createStreamingCall(
        String fullMethodName,
        MethodDescriptor.Marshaller<ReqT> reqMarshaller,
        MethodDescriptor.Marshaller<RespT> respMarshaller) {
      Context prevContext = Context.ROOT.attach();
      try {
        return new XdsStreamingCall<>(
            fullMethodName, reqMarshaller, respMarshaller, callCredentials);
      } finally {
        Context.ROOT.detach(prevContext);
      }

    }

    @Override
    public void shutdown() {
      channel.shutdown();
      for (Closeable resource : resources) {
        GrpcUtil.closeQuietly(resource);
      }
    }

    private class XdsStreamingCall<ReqT, RespT> implements
        XdsTransportFactory.StreamingCall<ReqT, RespT> {

      private final ClientCall<ReqT, RespT> call;

      public XdsStreamingCall(
          String methodName,
          MethodDescriptor.Marshaller<ReqT> reqMarshaller,
          MethodDescriptor.Marshaller<RespT> respMarshaller,
          CallCredentials callCredentials) {
        this.call =
            channel.newCall(
                MethodDescriptor.<ReqT, RespT>newBuilder()
                    .setFullMethodName(methodName)
                    .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                    .setRequestMarshaller(reqMarshaller)
                    .setResponseMarshaller(respMarshaller)
                    .build(),
                CallOptions.DEFAULT.withCallCredentials(
                    callCredentials)); // TODO(zivy): support waitForReady
      }

      @Override
      public void start(EventHandler<RespT> eventHandler) {
        call.start(new EventHandlerToCallListenerAdapter<>(eventHandler), new Metadata());
        call.request(1);
      }

      @Override
      public void sendMessage(ReqT message) {
        call.sendMessage(message);
      }

      @Override
      public void startRecvMessage() {
        call.request(1);
      }

      @Override
      public void sendError(Exception e) {
        call.cancel("Cancelled by XdsClientImpl", e);
      }

      @Override
      public boolean isReady() {
        return call.isReady();
      }
    }
  }

  private static class EventHandlerToCallListenerAdapter<T> extends ClientCall.Listener<T> {
    private final EventHandler<T> handler;

    EventHandlerToCallListenerAdapter(EventHandler<T> eventHandler) {
      this.handler = checkNotNull(eventHandler, "eventHandler");
    }

    @Override
    public void onHeaders(Metadata headers) {}

    @Override
    public void onMessage(T message) {
      handler.onRecvMessage(message);
    }

    @Override
    public void onClose(Status status, Metadata trailers) {
      handler.onStatusReceived(status);
    }

    @Override
    public void onReady() {
      handler.onReady();
    }
  }
}
