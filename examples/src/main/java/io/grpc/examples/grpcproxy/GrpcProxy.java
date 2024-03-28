/*
 * Copyright 2023, gRPC Authors All rights reserved.
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

package io.grpc.examples.grpcproxy;

import com.google.common.io.ByteStreams;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Grpc;
import io.grpc.HandlerRegistry;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerMethodDefinition;
import io.grpc.Status;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * A grpc-level proxy. GrpcProxy itself can be used unmodified to proxy any service for both unary
 * and streaming. It doesn't care what type of messages are being used. The Registry class causes it
 * to be called for any inbound RPC, and uses plain bytes for messages which avoids marshalling
 * messages and the need for Protobuf schema information.
 *
 * <p>Route guide has unary and streaming RPCs which makes it a nice showcase. To test with route
 * guide, run each in a separate terminal window:
 * <pre>{@code
 *   ./build/install/examples/bin/route-guide-server
 *   ./build/install/examples/bin/grpc-proxy
 *   ./build/install/examples/bin/route-guide-client localhost:8981
 * }<pre>
 *
 * <p>You can verify the proxy is being used by shutting down the proxy and seeing the client fail.
 */
public final class GrpcProxy<ReqT, RespT> implements ServerCallHandler<ReqT, RespT> {
  private static final Logger logger = Logger.getLogger(GrpcProxy.class.getName());

  private final Channel channel;

  public GrpcProxy(Channel channel) {
    this.channel = channel;
  }

  @Override
  public ServerCall.Listener<ReqT> startCall(
      ServerCall<ReqT, RespT> serverCall, Metadata headers) {
    ClientCall<ReqT, RespT> clientCall
        = channel.newCall(serverCall.getMethodDescriptor(), CallOptions.DEFAULT);
    CallProxy<ReqT, RespT> proxy = new CallProxy<ReqT, RespT>(serverCall, clientCall);
    clientCall.start(proxy.clientCallListener, headers);
    serverCall.request(1);
    clientCall.request(1);
    return proxy.serverCallListener;
  }

  private static class CallProxy<ReqT, RespT> {
    final RequestProxy serverCallListener;
    final ResponseProxy clientCallListener;

    public CallProxy(ServerCall<ReqT, RespT> serverCall, ClientCall<ReqT, RespT> clientCall) {
      serverCallListener = new RequestProxy(clientCall);
      clientCallListener = new ResponseProxy(serverCall);
    }

    private class RequestProxy extends ServerCall.Listener<ReqT> {
      private final ClientCall<ReqT, ?> clientCall;
      // Hold 'this' lock when accessing
      private boolean needToRequest;

      public RequestProxy(ClientCall<ReqT, ?> clientCall) {
        this.clientCall = clientCall;
      }

      @Override public void onCancel() {
        clientCall.cancel("Server cancelled", null);
      }

      @Override public void onHalfClose() {
        clientCall.halfClose();
      }

      @Override public void onMessage(ReqT message) {
        clientCall.sendMessage(message);
        synchronized (this) {
          if (clientCall.isReady()) {
            clientCallListener.serverCall.request(1);
          } else {
            // The outgoing call is not ready for more requests. Stop requesting additional data and
            // wait for it to catch up.
            needToRequest = true;
          }
        }
      }

      @Override public void onReady() {
        clientCallListener.onServerReady();
      }

      // Called from ResponseProxy, which is a different thread than the ServerCall.Listener
      // callbacks.
      synchronized void onClientReady() {
        if (needToRequest) {
          clientCallListener.serverCall.request(1);
          needToRequest = false;
        }
      }
    }

    private class ResponseProxy extends ClientCall.Listener<RespT> {
      private final ServerCall<?, RespT> serverCall;
      // Hold 'this' lock when accessing
      private boolean needToRequest;

      public ResponseProxy(ServerCall<?, RespT> serverCall) {
        this.serverCall = serverCall;
      }

      @Override public void onClose(Status status, Metadata trailers) {
        serverCall.close(status, trailers);
      }

      @Override public void onHeaders(Metadata headers) {
        serverCall.sendHeaders(headers);
      }

      @Override public void onMessage(RespT message) {
        serverCall.sendMessage(message);
        synchronized (this) {
          if (serverCall.isReady()) {
            serverCallListener.clientCall.request(1);
          } else {
            // The incoming call is not ready for more responses. Stop requesting additional data
            // and wait for it to catch up.
            needToRequest = true;
          }
        }
      }

      @Override public void onReady() {
        serverCallListener.onClientReady();
      }

      // Called from RequestProxy, which is a different thread than the ClientCall.Listener
      // callbacks.
      synchronized void onServerReady() {
        if (needToRequest) {
          serverCallListener.clientCall.request(1);
          needToRequest = false;
        }
      }
    }
  }

  private static class ByteMarshaller implements MethodDescriptor.Marshaller<byte[]> {
    @Override public byte[] parse(InputStream stream) {
      try {
        return ByteStreams.toByteArray(stream);
      } catch (IOException ex) {
        throw new RuntimeException();
      }
    }

    @Override public InputStream stream(byte[] value) {
      return new ByteArrayInputStream(value);
    }
  };

  public static class Registry extends HandlerRegistry {
    private final MethodDescriptor.Marshaller<byte[]> byteMarshaller = new ByteMarshaller();
    private final ServerCallHandler<byte[], byte[]> handler;

    public Registry(ServerCallHandler<byte[], byte[]> handler) {
      this.handler = handler;
    }

    @Override
    public ServerMethodDefinition<?,?> lookupMethod(String methodName, String authority) {
      MethodDescriptor<byte[], byte[]> methodDescriptor
          = MethodDescriptor.newBuilder(byteMarshaller, byteMarshaller)
          .setFullMethodName(methodName)
          .setType(MethodDescriptor.MethodType.UNKNOWN)
          .build();
      return ServerMethodDefinition.create(methodDescriptor, handler);
    }
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    String target = "localhost:8980";
    ManagedChannel channel = Grpc.newChannelBuilder(target, InsecureChannelCredentials.create())
        .build();
    logger.info("Proxy will connect to " + target);
    GrpcProxy<byte[], byte[]> proxy = new GrpcProxy<byte[], byte[]>(channel);
    int port = 8981;
    Server server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
        .fallbackHandlerRegistry(new Registry(proxy))
        .build()
        .start();
    logger.info("Proxy started, listening on " + port);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        server.shutdown();
        try {
          server.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
        server.shutdownNow();
        channel.shutdownNow();
      }
    });
    server.awaitTermination();
    if (!channel.awaitTermination(1, TimeUnit.SECONDS)) {
      System.out.println("Channel didn't shut down promptly");
    }
  }
}
