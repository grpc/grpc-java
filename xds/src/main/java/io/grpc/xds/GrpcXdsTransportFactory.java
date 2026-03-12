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
import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.ChannelCredentials;
import io.grpc.ClientCall;
import io.grpc.Context;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.XdsTransportFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class GrpcXdsTransportFactory implements XdsTransportFactory {

  private final CallCredentials callCredentials;
  // The map of xDS server info to its corresponding gRPC xDS transport.
  // This enables reusing and sharing the same underlying gRPC channel.
  //
  // NOTE: ConcurrentHashMap is used as a per-entry lock and all reads and writes must be a mutation
  // via the ConcurrentHashMap APIs to acquire the per-entry lock in order to ensure thread safety
  // for reference counting of each GrpcXdsTransport instance.
  //
  // WARNING: ServerInfo includes ChannelCredentials, which is compared by reference equality.
  // This means every BootstrapInfo would have non-equal copies of ServerInfo, even if they all
  // represent the same xDS server configuration. For gRPC name resolution with the `xds` and
  // `google-c2p` scheme, this transport sharing works as expected as it internally reuses a single
  // BootstrapInfo instance. Otherwise, new transports would be created for each ServerInfo despite
  // them possibly representing the same xDS server configuration and defeating the purpose of
  // transport sharing.
  private static final Map<Bootstrapper.ServerInfo, GrpcXdsTransport> xdsServerInfoToTransportMap =
      new ConcurrentHashMap<>();

  GrpcXdsTransportFactory(CallCredentials callCredentials) {
    this.callCredentials = callCredentials;
  }

  @Override
  public XdsTransport create(Bootstrapper.ServerInfo serverInfo) {
    return xdsServerInfoToTransportMap.compute(
        serverInfo,
        (info, transport) -> {
          if (transport == null) {
            transport = new GrpcXdsTransport(serverInfo, callCredentials);
          }
          ++transport.refCount;
          return transport;
        });
  }

  @VisibleForTesting
  public XdsTransport createForTest(ManagedChannel channel) {
    return new GrpcXdsTransport(channel, callCredentials, null);
  }

  @VisibleForTesting
  static class GrpcXdsTransport implements XdsTransport {

    private final ManagedChannel channel;
    private final CallCredentials callCredentials;
    private final Bootstrapper.ServerInfo serverInfo;
    // Must only be accessed via the ConcurrentHashMap APIs which act as the locking methods.
    private int refCount = 0;

    public GrpcXdsTransport(Bootstrapper.ServerInfo serverInfo) {
      this(serverInfo, null);
    }

    @VisibleForTesting
    public GrpcXdsTransport(ManagedChannel channel) {
      this(channel, null, null);
    }

    public GrpcXdsTransport(Bootstrapper.ServerInfo serverInfo, CallCredentials callCredentials) {
      String target = serverInfo.target();
      ChannelCredentials channelCredentials = (ChannelCredentials) serverInfo.implSpecificConfig();
      this.channel = Grpc.newChannelBuilder(target, channelCredentials)
          .keepAliveTime(5, TimeUnit.MINUTES)
          .build();
      this.callCredentials = callCredentials;
      this.serverInfo = serverInfo;
    }

    @VisibleForTesting
    public GrpcXdsTransport(
        ManagedChannel channel,
        CallCredentials callCredentials,
        Bootstrapper.ServerInfo serverInfo) {
      this.channel = checkNotNull(channel, "channel");
      this.callCredentials = callCredentials;
      this.serverInfo = serverInfo;
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
      if (serverInfo == null) {
        channel.shutdown();
        return;
      }
      xdsServerInfoToTransportMap.computeIfPresent(
          serverInfo,
          (info, transport) -> {
            if (--transport.refCount == 0) { // Prefix decrement and return the updated value.
              transport.channel.shutdown();
              return null; // Remove mapping.
            }
            return transport;
          });
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
