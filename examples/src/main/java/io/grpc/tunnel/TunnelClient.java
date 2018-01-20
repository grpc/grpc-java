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
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.HandlerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.HelloWorldServer.GreeterImpl;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.util.MutableHandlerRegistry;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
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

public class TunnelClient {
  public static Server createServer(Channel channel, String methodName, CallOptions options,
      HandlerRegistry registry) throws IOException {
    LocalAddress address = new LocalAddress("" + Math.random());
    Server server = NettyServerBuilder.forAddress(address)
        .channelType(LocalServerChannel.class)
        .fallbackHandlerRegistry(registry)
        .build()
        .start();

    MethodDescriptor<byte[], byte[]> method = MethodDescriptor
        .newBuilder(BytesMarshaller.INSTANCE, BytesMarshaller.INSTANCE)
        .setFullMethodName(methodName)
        .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
        .build();
    final ClientCall<byte[], byte[]> call = channel.newCall(method, options);

    final EventLoopGroup eventLoopGroup = new DefaultEventLoop();
    ChannelHandler handler = new ChannelInboundHandlerAdapter() {
      @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf buf = (ByteBuf) msg;
        byte[] bytes = ByteBufUtil.getBytes(buf);
        if (bytes.length != 0) {
          call.sendMessage(bytes);
        }
        buf.release();
      }

      @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        call.cancel(null, cause);
        ctx.close();
      }

      @Override public void channelInactive(ChannelHandlerContext ctx) {
        call.cancel("channel inactive", null);
      }

      @Override public void channelUnregistered(ChannelHandlerContext ctx) {
        eventLoopGroup.shutdownGracefully(1, 60, TimeUnit.SECONDS);
      }
    };

    final io.netty.channel.Channel nettyChannel = new Bootstrap()
        .group(eventLoopGroup)
        .channel(LocalChannel.class)
        .handler(handler)
        .connect(address)
        .channel();

    ClientCall.Listener<byte[]> listener = new ClientCall.Listener<byte[]>() {
      @Override public void onMessage(byte[] bytes) {
        nettyChannel.writeAndFlush(Unpooled.wrappedBuffer(bytes));
        call.request(1);
      }

      @Override public void onClose(Status status, Metadata trailers) {
        // TODO?
        server.shutdown();
        nettyChannel.close();
        System.out.println("status: " + status);
      }
    };

    call.start(listener, new Metadata());
    call.request(1);
    return server;
  }

  public static void main(String[] args) throws Exception {
    ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 50051)
        .usePlaintext(true)
        .build();
    try {
      MutableHandlerRegistry registry = new MutableHandlerRegistry();
      registry.addService(new GreeterImpl());
      createServer(channel, "io.grpc.Tunnel/new", CallOptions.DEFAULT, registry).awaitTermination();
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  static class BytesMarshaller implements MethodDescriptor.Marshaller<byte[]> {
    public static BytesMarshaller INSTANCE = new BytesMarshaller();

    @Override public byte[] parse(InputStream stream) {
      try {
        return ByteStreams.toByteArray(stream);
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }

    @Override public InputStream stream(byte[] value) {
      return new ByteArrayInputStream(value);
    }
  }
}
