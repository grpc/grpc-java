/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc.benchmarks;

import static io.grpc.benchmarks.Utils.pickUnusedPort;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.benchmarks.proto.BenchmarkServiceGrpc;
import io.grpc.benchmarks.proto.Messages.Payload;
import io.grpc.benchmarks.proto.Messages.SimpleRequest;
import io.grpc.benchmarks.proto.Messages.SimpleResponse;
import io.grpc.benchmarks.qps.AsyncServer;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.AbstractManagedChannelImplBuilder;
import io.grpc.internal.AbstractServerImplBuilder;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.Channel;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;

/** Some text. */
@State(Scope.Benchmark)
public class TransportBenchmark {
  public enum Transport {
    INPROCESS, NETTY, NETTY_LOCAL, NETTY_EPOLL, OKHTTP
  }

  @Param({"INPROCESS", "NETTY", "OKHTTP"})
  public Transport transport;
  @Param({"true", "false"})
  public boolean direct;

  private ManagedChannel channel;
  private Server server;
  private BenchmarkServiceGrpc.BenchmarkServiceBlockingStub stub;
  private BenchmarkServiceGrpc.BenchmarkServiceStub asyncStub;
  private EventLoopGroup groupToShutdown;

  @Setup
  public void setUp() throws Exception {
    AbstractServerImplBuilder<?> serverBuilder;
    AbstractManagedChannelImplBuilder<?> channelBuilder;
    switch (transport) {
      case INPROCESS:
      {
        String name = "bench" + Math.random();
        serverBuilder = InProcessServerBuilder.forName(name);
        channelBuilder = InProcessChannelBuilder.forName(name);
        break;
      }
      case NETTY:
      {
        InetSocketAddress address = new InetSocketAddress("localhost", pickUnusedPort());
        serverBuilder = NettyServerBuilder.forAddress(address);
        channelBuilder = NettyChannelBuilder.forAddress(address)
            .negotiationType(NegotiationType.PLAINTEXT);
        break;
      }
      case NETTY_LOCAL:
      {
        String name = "bench" + Math.random();
        LocalAddress address = new LocalAddress(name);
        EventLoopGroup group = new DefaultEventLoopGroup();
        serverBuilder = NettyServerBuilder.forAddress(address)
            .bossEventLoopGroup(group)
            .workerEventLoopGroup(group)
            .channelType(LocalServerChannel.class);
        channelBuilder = NettyChannelBuilder.forAddress(address)
            .eventLoopGroup(group)
            .channelType(LocalChannel.class)
            .negotiationType(NegotiationType.PLAINTEXT);
        groupToShutdown = group;
        break;
      }
      case NETTY_EPOLL:
      {
        InetSocketAddress address = new InetSocketAddress("localhost", pickUnusedPort());

        // Reflection used since they are only available on linux.
        Class<?> groupClass = Class.forName("io.netty.channel.epoll.EpollEventLoopGroup");
        EventLoopGroup group = (EventLoopGroup) groupClass.getConstructor().newInstance();

        Class<? extends ServerChannel> serverChannelClass =
            Class.forName("io.netty.channel.epoll.EpollServerSocketChannel")
              .asSubclass(ServerChannel.class);
        serverBuilder = NettyServerBuilder.forAddress(address)
            .bossEventLoopGroup(group)
            .workerEventLoopGroup(group)
            .channelType(serverChannelClass);
        Class<? extends Channel> channelClass =
            Class.forName("io.netty.channel.epoll.EpollSocketChannel")
              .asSubclass(Channel.class);
        channelBuilder = NettyChannelBuilder.forAddress(address)
            .eventLoopGroup(group)
            .channelType(channelClass)
            .negotiationType(NegotiationType.PLAINTEXT);
        groupToShutdown = group;
        break;
      }
      case OKHTTP:
      {
        int port = pickUnusedPort();
        InetSocketAddress address = new InetSocketAddress("localhost", port);
        serverBuilder = NettyServerBuilder.forAddress(address);
        channelBuilder = OkHttpChannelBuilder.forAddress("localhost", port).usePlaintext();
        break;
      }
      default:
        throw new Exception("Unknown transport: " + transport);
    }

    if (direct) {
      serverBuilder.directExecutor();
      // Because blocking stubs avoid the executor, this doesn't do much.
      channelBuilder.directExecutor();
    }

    server = serverBuilder
        .addService(new AsyncServer.BenchmarkServiceImpl())
        .build();
    server.start();
    channel = channelBuilder.build();
    stub = BenchmarkServiceGrpc.newBlockingStub(channel);
    asyncStub = BenchmarkServiceGrpc.newStub(channel);
    // Wait for channel to start
    stub.unaryCall(SimpleRequest.getDefaultInstance());
  }

  @TearDown
  public void tearDown() throws Exception {
    channel.shutdown();
    server.shutdown();
    channel.awaitTermination(1, TimeUnit.SECONDS);
    server.awaitTermination(1, TimeUnit.SECONDS);
    if (!channel.isTerminated()) {
      throw new Exception("failed to shut down channel");
    }
    if (!server.isTerminated()) {
      throw new Exception("failed to shut down server");
    }
    if (groupToShutdown != null) {
      Future<?> unused = groupToShutdown.shutdownGracefully(0, 1, TimeUnit.SECONDS);
      groupToShutdown.awaitTermination(1, TimeUnit.SECONDS);
      if (!groupToShutdown.isTerminated()) {
        throw new Exception("failed to shut down event loop group.");
      }
    }
  }

  private static final SimpleRequest UNARY_CALL_1024_REQUEST = SimpleRequest.newBuilder()
      .setResponseSize(1024)
      .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[1024])))
      .build();

  @Benchmark
  @BenchmarkMode(Mode.SampleTime)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public SimpleResponse unaryCall1024Latency() {
    return stub.unaryCall(UNARY_CALL_1024_REQUEST);
  }

  private static final int BYTE_THROUGHPUT_RESPONSE_SIZE = 1048576;
  private static final SimpleRequest BYTE_THROUGHPUT_REQUEST = SimpleRequest.newBuilder()
      .setResponseSize(BYTE_THROUGHPUT_RESPONSE_SIZE)
      .build();

  @Benchmark
  @BenchmarkMode(Mode.Throughput)
  @OperationsPerInvocation(BYTE_THROUGHPUT_RESPONSE_SIZE)
  @Threads(10)
  public SimpleResponse unaryCallsByteThroughput() {
    return stub.unaryCall(BYTE_THROUGHPUT_REQUEST);
  }

  private static final Throwable OK_THROWABLE = new RuntimeException("OK");

  @State(Scope.Thread)
  public static class PingPongStreamState {
    private final ThreadlessExecutor executor = new ThreadlessExecutor();
    private StreamObserver<SimpleRequest> requestObserver;
    private SimpleResponse response;
    private Throwable status;

    @Setup
    public void setUp(TransportBenchmark bench) {
      requestObserver = bench.asyncStub
          .withExecutor(executor)
          .streamingCall(new StreamObserver<SimpleResponse>() {
            @Override public void onNext(SimpleResponse next) {
              assert response == null;
              response = next;
            }

            @Override public void onError(Throwable t) {
              status = t;
            }

            @Override public void onCompleted() {
              status = OK_THROWABLE;
            }
          });
    }

    /** Issues request and waits for response. */
    public SimpleResponse pingPong(SimpleRequest request) throws InterruptedException {
      requestObserver.onNext(request);
      while (true) {
        executor.waitAndDrain();
        if (response != null) {
          SimpleResponse savedResponse = response;
          response = null;
          return savedResponse;
        }
        if (status != null) {
          throw new RuntimeException("Unexpected stream termination", status);
        }
      }
    }

    @TearDown
    public void tearDown() throws InterruptedException {
      requestObserver.onCompleted();
      while (status == null) {
        executor.waitAndDrain();
      }
      if (status != OK_THROWABLE) {
        throw new RuntimeException("Non-graceful stream shutdown", status);
      }
    }
  }

  @Benchmark
  @BenchmarkMode(Mode.Throughput)
  @OperationsPerInvocation(BYTE_THROUGHPUT_RESPONSE_SIZE)
  @Threads(10)
  public SimpleResponse streamingCallsByteThroughput(PingPongStreamState state)
      throws InterruptedException {
    return state.pingPong(BYTE_THROUGHPUT_REQUEST);
  }

  @State(Scope.Thread)
  public static class InfiniteStreamState {
    private final CancellableInterceptor cancellableInterceptor = new CancellableInterceptor();
    private Iterator<SimpleResponse> iter;

    @Setup
    public void setUp(TransportBenchmark bench) {
      iter = bench.stub
          .withInterceptors(cancellableInterceptor)
          .streamingFromServer(SimpleRequest.getDefaultInstance());
    }

    public SimpleResponse recv() throws InterruptedException {
      return iter.next();
    }

    @TearDown
    public void tearDown() throws InterruptedException {
      cancellableInterceptor.cancel("Normal tear-down", null);
      try {
        // Need to drain the queue
        while (iter.hasNext()) {
          iter.next();
        }
      } catch (StatusRuntimeException ex) {
        if (!Status.Code.CANCELLED.equals(ex.getStatus().getCode())) {
          throw ex;
        }
      }
    }
  }

  // NOTE: Causes OOM with NETTY_LOCAL. Probably a flow control problem in NETTY_LOCAL, but we
  // aren't too concerned.
  @Benchmark
  @BenchmarkMode(Mode.Throughput)
  @Threads(10)
  public SimpleResponse streamingCallsMessageThroughput(InfiniteStreamState state)
      throws InterruptedException {
    return state.recv();
  }
}
