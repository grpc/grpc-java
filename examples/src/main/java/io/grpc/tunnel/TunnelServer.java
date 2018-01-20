/*
 * Copyright 2018, gRPC Authors All rights reserved.
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

package io.grpc.tunnel;

import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.HelloWorldClient;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

public class TunnelServer {
  public interface Listener {
    void newChannel(ManagedChannel channel, Metadata headers);
  }

  public static ServerServiceDefinition createService(String methodName, Listener listener) {
    MethodDescriptor<byte[], byte[]> method = MethodDescriptor
        .newBuilder(TunnelClient.BytesMarshaller.INSTANCE, TunnelClient.BytesMarshaller.INSTANCE)
        .setFullMethodName(methodName)
        .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
        .build();
    return ServerServiceDefinition.builder(methodName.split("/", 2)[0])
        .addMethod(method, new TunnelHandler(listener))
        .build();
  }

  private static class TunnelHandler implements ServerCallHandler<byte[], byte[]> {
    private final Listener tunnelListener;

    public TunnelHandler(Listener listener) {
      this.tunnelListener = listener;
    }

    @Override public ServerCall.Listener<byte[]> startCall(
        final ServerCall<byte[], byte[]> call, Metadata headers) {
      final EventLoopGroup eventLoopGroup = new DefaultEventLoop();
      final SettableFuture<io.netty.channel.Channel> nettyChannelFuture = SettableFuture.create();
      ChannelHandler handler = new ChannelInboundHandlerAdapter() {
        private boolean closed;

        @Override public void channelActive(ChannelHandlerContext ctx) {
          nettyChannelFuture.set(ctx.channel());
        }

        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
          ByteBuf buf = (ByteBuf) msg;
          byte[] bytes = ByteBufUtil.getBytes(buf);
          if (bytes.length != 0) {
            call.sendMessage(bytes);
          }
          buf.release();
        }

        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
          if (!closed) {
            closed = true;
            call.close(Status.INTERNAL.withDescription("exception caught"), new Metadata());
          }
          cause.printStackTrace();
          ctx.close();
        }

        @Override public void channelInactive(ChannelHandlerContext ctx) {
          if (!closed) {
            closed = true;
            call.close(Status.OK.withDescription("channel inactive"), new Metadata());
          }
        }

        @Override public void channelUnregistered(ChannelHandlerContext ctx) {
          eventLoopGroup.shutdownGracefully(1, 60, TimeUnit.SECONDS);
        }
      };

      LocalAddress address = new LocalAddress("" + Math.random());
      ChannelFuture bindFuture = new ServerBootstrap()
          .group(eventLoopGroup, eventLoopGroup)
          .channel(LocalServerChannel.class)
          .childHandler(handler)
          .bind(address);
      try {
        bindFuture.await();
      } catch (InterruptedException ex) {
        throw new RuntimeException("bind interrutped", ex);
      }
      if (!bindFuture.isSuccess()) {
        throw new RuntimeException("bind failed", bindFuture.cause());
      }
      io.netty.channel.Channel serverChannel = bindFuture.channel();

      final ManagedChannel channel = NettyChannelBuilder.forAddress(address)
          .channelType(LocalChannel.class)
          .usePlaintext(true)
          .build();

      channel.getState(true);
      final io.netty.channel.Channel nettyChannel;
      try {
        nettyChannel = nettyChannelFuture.get();
      } catch (Exception ex) {
        // TODO
        throw new RuntimeException(ex);
      }
      serverChannel.close();

      ServerCall.Listener<byte[]> listener = new ServerCall.Listener<byte[]>() {
        @Override public void onMessage(byte[] bytes) {
          nettyChannel.writeAndFlush(Unpooled.wrappedBuffer(bytes));
          call.request(1);
        }

        @Override public void onHalfClose() {
          done();
        }

        @Override public void onCancel() {
          done();
        }

        @Override public void onComplete() {
          done();
        }

        private void done() {
          // TODO?
          channel.shutdown();
          nettyChannel.close();
        }
      };

      call.sendHeaders(new Metadata());
      call.request(1);

      new Thread(new Runnable() {
        @Override public void run() {
          tunnelListener.newChannel(channel, headers);
        }
      }).start();
      return listener;
    }
  }

  public static void main(String[] args) throws Exception {
    Listener listener = new Listener() {
      @Override public void newChannel(final ManagedChannel channel, Metadata headers) {
        try {
          HelloWorldClient client = new HelloWorldClient(channel);
          client.greet("tunnel");
          client.shutdown();
        } catch (Exception ex) {
          ex.printStackTrace();
        } finally {
          channel.shutdownNow();
        }
      }
    };
    int port = 50051;
    final Server server = ServerBuilder.forPort(port)
        .addService(createService("io.grpc.Tunnel/new", listener))
        .build()
        .start();
    System.out.println("Server started, listening on " + port);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        server.shutdown();
        System.err.println("*** server shut down");
      }
    });
    server.awaitTermination();
  }
}
