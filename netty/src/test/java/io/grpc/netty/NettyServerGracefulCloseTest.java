/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.netty;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.io.ByteStreams;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.testing.GrpcCleanupRule;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Regression test for https://github.com/grpc/grpc-java/issues/9566: a unary RPC that the server
 * completed must not fail because the server closed the connection after a max-age GOAWAY.
 *
 * <p>The client's event loop is stalled while the server sends the response, so the response is
 * still unread in the server's kernel send buffer when the server would close the connection. If
 * the server closes it then, the client's next frame (a BDP PING or WINDOW_UPDATE) makes the
 * server's kernel answer with RST, which discards the unsent trailers. This depends on the kernel:
 * without the fix it fails on Linux, while macOS loopback does not lose the data.
 */
@RunWith(JUnit4.class)
public class NettyServerGracefulCloseTest {
  private static final int RESPONSE_BYTES = 256 * 1024;
  private static final int ITERATIONS = 3;
  private static final long CLIENT_STALL_MILLIS = 300;

  private static final MethodDescriptor.Marshaller<byte[]> BYTES =
      new MethodDescriptor.Marshaller<byte[]>() {
        @Override
        public InputStream stream(byte[] value) {
          return new ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(InputStream stream) {
          try {
            return ByteStreams.toByteArray(stream);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
      };

  private static final MethodDescriptor<byte[], byte[]> METHOD =
      MethodDescriptor.<byte[], byte[]>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName("test.Test/Get")
          .setRequestMarshaller(BYTES)
          .setResponseMarshaller(BYTES)
          .build();

  @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private final AtomicReference<EventLoopGroup> clientGroup = new AtomicReference<>();

  @Test
  public void maxConnectionAge_unaryCallInFlight_succeeds() throws Exception {
    ServerServiceDefinition service = ServerServiceDefinition.builder("test.Test")
        .addMethod(METHOD, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
          // Outlive the max age (1 second plus up to 10% jitter), so both GOAWAYs are sent while
          // the call is in flight.
          sleep(1500);
          clientGroup.get().execute(() -> sleep(CLIENT_STALL_MILLIS));
          responseObserver.onNext(new byte[RESPONSE_BYTES]);
          responseObserver.onCompleted();
        }))
        .build();
    Server server = grpcCleanup.register(
        NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            .maxConnectionAge(1, TimeUnit.SECONDS)
            .maxConnectionAgeGrace(30, TimeUnit.SECONDS)
            .addService(service)
            .build()
            .start());

    for (int i = 0; i < ITERATIONS; i++) {
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      clientGroup.set(group);
      ManagedChannel channel = NettyChannelBuilder
          .forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()))
          .channelType(NioSocketChannel.class)
          .eventLoopGroup(group)
          .usePlaintext()
          .build();
      try {
        byte[] response =
            ClientCalls.blockingUnaryCall(channel, METHOD, CallOptions.DEFAULT, new byte[16]);
        assertThat(response).hasLength(RESPONSE_BYTES);
      } finally {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
      }
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
