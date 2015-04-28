/*
 * Copyright 2014, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.benchmarks.netty;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import io.grpc.Call;
import io.grpc.ChannelImpl;
import io.grpc.DeferredInputStream;
import io.grpc.Marshaller;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodType;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerImpl;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.stub.Calls;
import io.grpc.transport.netty.NegotiationType;
import io.grpc.transport.netty.NettyChannelBuilder;
import io.grpc.transport.netty.NettyServerBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.EmptyByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

/**
 * Benchmark the Netty Channel and Server implementation end-to-end. Uses ByteBuf as the
 * payload to avoid noise from object serialization.
 */
@State(Scope.Benchmark)
public class EndToEnd {

  public static enum PayloadSize {
    SMALL(10), MEDIUM(1024), LARGE(65536), JUMBO(16777216);

    final int bytes;
    PayloadSize(int bytes) {
      this.bytes = bytes;
    }

    public int bytes() {
      return bytes;
    }
  }

  public static enum ExecutorType {
    DEFAULT, DIRECT;
  }

  public static enum ChannelType {
    NIO, LOCAL;
  }

  @Param
  public ExecutorType clientExecutor = ExecutorType.DEFAULT;

  @Param
  public ExecutorType serverExecutor = ExecutorType.DEFAULT;

  @Param
  public PayloadSize requestSize = PayloadSize.SMALL;

  @Param
  public PayloadSize responseSize = PayloadSize.SMALL;

  @Param
  public ChannelType channelType = ChannelType.NIO;

  private ServerImpl server;
  private ChannelImpl channel;
  private ByteBuf request;
  private ByteBuf response;
  private MethodDescriptor unaryMethod;

  @Before
  @Setup(Level.Trial)
  public void setup() throws Exception {

    NettyServerBuilder serverBuilder;
    NettyChannelBuilder channelBuilder;
    if (channelType == ChannelType.LOCAL) {
      LocalAddress address = new LocalAddress("netty-e2e-benchmark");
      serverBuilder = NettyServerBuilder.forAddress(address);
      serverBuilder.channelType(LocalServerChannel.class);
      channelBuilder = NettyChannelBuilder.forAddress(address);
      channelBuilder.channelType(LocalChannel.class);
    } else {
      // Pick a port using an ephemeral socket.
      ServerSocket sock = new ServerSocket();
      sock.bind(new InetSocketAddress("localhost", 0));
      SocketAddress address = sock.getLocalSocketAddress();
      sock.close();
      serverBuilder = NettyServerBuilder.forAddress(address);
      channelBuilder = NettyChannelBuilder.forAddress(address);
    }

    if (serverExecutor == ExecutorType.DIRECT) {
      serverBuilder.executor(MoreExecutors.newDirectExecutorService());
    }
    if (clientExecutor == ExecutorType.DIRECT) {
      channelBuilder.executor(MoreExecutors.newDirectExecutorService());
    }
    channelBuilder.negotiationType(NegotiationType.PLAINTEXT);

    PooledByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;
    request = alloc.buffer(requestSize.bytes());
    request.writerIndex(request.capacity() - 1);
    response = alloc.buffer(responseSize.bytes());
    response.writerIndex(response.capacity() - 1);

    // Simple method that sends and receives NettyByteBuf
    unaryMethod = MethodDescriptor.create(MethodType.UNARY,
        "benchmark/unary",
        5,
        TimeUnit.SECONDS,
        new ByteBufOutputMarshaller(),
        new ByteBufOutputMarshaller());

    // Server implementation of same method
    serverBuilder.addService(
        ServerServiceDefinition.builder("benchmark")
        .addMethod("unary",
            new ByteBufOutputMarshaller(),
            new ByteBufOutputMarshaller(),
            new ServerCallHandler<ByteBuf, ByteBuf>() {
              @Override
              public ServerCall.Listener<ByteBuf> startCall(String fullMethodName,
                                                            final ServerCall<ByteBuf> call,
                                                            Metadata.Headers headers) {
                call.request(1);
                return new ServerCall.Listener<ByteBuf>() {
                  @Override
                  public void onPayload(ByteBuf payload) {
                    // no-op
                    payload.release();
                    call.sendPayload(response.slice());
                  }

                  @Override
                  public void onHalfClose() {
                    call.close(Status.OK, new Metadata.Trailers());
                  }

                  @Override
                  public void onCancel() {

                  }

                  @Override
                  public void onComplete() {
                  }
                };
              }
            }).build());

    server = serverBuilder.build();
    server.start();
    channel = channelBuilder.build();
  }

  @After
  @TearDown(Level.Trial)
  public void teardown() throws Exception {
    server.shutdownNow();
    channel.shutdownNow();
  }

  @Test
  @Benchmark
  // Use JUnit annotations to allow for easy execution as a single-pass test.
  public void blockingUnary() throws Exception {
    Call call = channel.newCall(unaryMethod);
    ByteBuf slice = request.slice();
    Calls.blockingUnaryCall(call, slice);
  }

  /**
   * Simple marshaller for Netty ByteBuf.
   */
  private static class ByteBufOutputMarshaller implements Marshaller<ByteBuf> {

    public static final EmptyByteBuf EMPTY_BYTE_BUF =
        new EmptyByteBuf(PooledByteBufAllocator.DEFAULT);

    private ByteBufOutputMarshaller() {
    }

    @Override
    public InputStream stream(ByteBuf value) {
      return new DeferredByteBufInputStream(value);
    }

    @Override
    public ByteBuf parse(InputStream stream) {
      try {
        // We don't do anything with the payload and it's already been read into buffers
        // so just skip copying it.
         stream.skip(stream.available());
         return EMPTY_BYTE_BUF;
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    }
  }

  /**
   * Implementation of {@link io.grpc.DeferredInputStream} for {@link io.netty.buffer.ByteBuf}.
   */
  private static class DeferredByteBufInputStream extends DeferredInputStream<ByteBuf> {

    private ByteBuf buf;

    private DeferredByteBufInputStream(ByteBuf buf) {
      this.buf = buf;
    }

    @Override
    public int flushTo(OutputStream target) throws IOException {
      int readbableBytes = buf.readableBytes();
      buf.readBytes(target, readbableBytes);
      buf = null;
      return readbableBytes;
    }

    @Nullable
    @Override
    public ByteBuf getDeferred() {
      return buf;
    }

    @Override
    public int available() throws IOException {
      if (buf != null) {
        return buf.readableBytes();
      }
      return 0;
    }

    @Override
    public int read() throws IOException {
      throw new UnsupportedOperationException();
    }
  }
}
