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

package io.grpc.s2a.channel;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.ClientCall;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCredentials;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.benchmarks.Utils;
import io.grpc.internal.SharedResourceHolder.Resource;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.s2a.channel.S2AHandshakerServiceChannel.EventLoopHoldingChannel;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.netty.channel.EventLoopGroup;
import java.io.File;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link S2AHandshakerServiceChannel}. */
@RunWith(JUnit4.class)
public final class S2AHandshakerServiceChannelTest {
  @ClassRule public static final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();
  private static final Duration CHANNEL_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
  private final EventLoopGroup mockEventLoopGroup = mock(EventLoopGroup.class);
  private Server mtlsServer;
  private Server plaintextServer;

  @Before
  public void setUp() throws Exception {
    mtlsServer = createMtlsServer();
    plaintextServer = createPlaintextServer();
    mtlsServer.start();
    plaintextServer.start();
  }

  /**
   * Creates a {@code Resource<Channel>} and verifies that it produces a {@code ChannelResource}
   * instance by using its {@code toString()} method.
   */
  @Test
  public void getChannelResource_success() {
    Resource<Channel> resource =
        S2AHandshakerServiceChannel.getChannelResource(
            "localhost:" + plaintextServer.getPort(),
            InsecureChannelCredentials.create());
    assertThat(resource.toString()).isEqualTo("grpc-s2a-channel");
  }

  /** Same as getChannelResource_success, but use mTLS. */
  @Test
  public void getChannelResource_mtlsSuccess() throws Exception {
    Resource<Channel> resource =
        S2AHandshakerServiceChannel.getChannelResource(
            "localhost:" + mtlsServer.getPort(), getTlsChannelCredentials());
    assertThat(resource.toString()).isEqualTo("grpc-s2a-channel");
  }

  /**
   * Creates two {@code Resoure<Channel>}s for the same target address and verifies that they are
   * equal.
   */
  @Test
  public void getChannelResource_twoEqualChannels() {
    Resource<Channel> resource =
        S2AHandshakerServiceChannel.getChannelResource(
            "localhost:" + plaintextServer.getPort(),
            InsecureChannelCredentials.create());
    Resource<Channel> resourceTwo =
        S2AHandshakerServiceChannel.getChannelResource(
            "localhost:" + plaintextServer.getPort(),
            InsecureChannelCredentials.create());
    assertThat(resource).isEqualTo(resourceTwo);
  }

  /** Same as getChannelResource_twoEqualChannels, but use mTLS. */
  @Test
  public void getChannelResource_mtlsTwoEqualChannels() throws Exception {
    Resource<Channel> resource =
        S2AHandshakerServiceChannel.getChannelResource(
            "localhost:" + mtlsServer.getPort(), getTlsChannelCredentials());
    Resource<Channel> resourceTwo =
        S2AHandshakerServiceChannel.getChannelResource(
            "localhost:" + mtlsServer.getPort(), getTlsChannelCredentials());
    assertThat(resource).isEqualTo(resourceTwo);
  }

  /**
   * Creates two {@code Resoure<Channel>}s for different target addresses and verifies that they are
   * distinct.
   */
  @Test
  public void getChannelResource_twoDistinctChannels() {
    Resource<Channel> resource =
        S2AHandshakerServiceChannel.getChannelResource(
            "localhost:" + plaintextServer.getPort(),
            InsecureChannelCredentials.create());
    Resource<Channel> resourceTwo =
        S2AHandshakerServiceChannel.getChannelResource(
            "localhost:" + Utils.pickUnusedPort(), InsecureChannelCredentials.create());
    assertThat(resourceTwo).isNotEqualTo(resource);
  }

  /** Same as getChannelResource_twoDistinctChannels, but use mTLS. */
  @Test
  public void getChannelResource_mtlsTwoDistinctChannels() throws Exception {
    Resource<Channel> resource =
        S2AHandshakerServiceChannel.getChannelResource(
            "localhost:" + mtlsServer.getPort(), getTlsChannelCredentials());
    Resource<Channel> resourceTwo =
        S2AHandshakerServiceChannel.getChannelResource(
            "localhost:" + Utils.pickUnusedPort(), getTlsChannelCredentials());
    assertThat(resourceTwo).isNotEqualTo(resource);
  }

  /**
   * Uses a {@code Resource<Channel>} to create a channel, closes the channel, and verifies that the
   * channel is closed by attempting to make a simple RPC.
   */
  @Test
  public void close_success() {
    Resource<Channel> resource =
        S2AHandshakerServiceChannel.getChannelResource(
            "localhost:" + plaintextServer.getPort(),
            InsecureChannelCredentials.create());
    Channel channel = resource.create();
    resource.close(channel);
    StatusRuntimeException expected =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                SimpleServiceGrpc.newBlockingStub(channel)
                    .unaryRpc(SimpleRequest.getDefaultInstance()));
    assertThat(expected).hasMessageThat().isEqualTo("UNAVAILABLE: Channel shutdown invoked");
  }

  /** Same as close_success, but use mTLS. */
  @Test
  public void close_mtlsSuccess() throws Exception {
    Resource<Channel> resource =
        S2AHandshakerServiceChannel.getChannelResource(
            "localhost:" + mtlsServer.getPort(), getTlsChannelCredentials());
    Channel channel = resource.create();
    resource.close(channel);
    StatusRuntimeException expected =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                SimpleServiceGrpc.newBlockingStub(channel)
                    .unaryRpc(SimpleRequest.getDefaultInstance()));
    assertThat(expected).hasMessageThat().isEqualTo("UNAVAILABLE: Channel shutdown invoked");
  }

  /**
   * Verifies that an {@code EventLoopHoldingChannel}'s {@code newCall} method can be used to
   * perform a simple RPC.
   */
  @Test
  public void newCall_performSimpleRpcSuccess() {
    Resource<Channel> resource =
        S2AHandshakerServiceChannel.getChannelResource(
            "localhost:" + plaintextServer.getPort(),
            InsecureChannelCredentials.create());
    Channel channel = resource.create();
    assertThat(channel).isInstanceOf(EventLoopHoldingChannel.class);
    assertThat(
            SimpleServiceGrpc.newBlockingStub(channel).unaryRpc(SimpleRequest.getDefaultInstance()))
        .isEqualToDefaultInstance();
  }

  /** Same as newCall_performSimpleRpcSuccess, but use mTLS. */
  @Test
  public void newCall_mtlsPerformSimpleRpcSuccess() throws Exception {
    Resource<Channel> resource =
        S2AHandshakerServiceChannel.getChannelResource(
            "localhost:" + mtlsServer.getPort(), getTlsChannelCredentials());
    Channel channel = resource.create();
    assertThat(channel).isInstanceOf(EventLoopHoldingChannel.class);
    assertThat(
            SimpleServiceGrpc.newBlockingStub(channel).unaryRpc(SimpleRequest.getDefaultInstance()))
        .isEqualToDefaultInstance();
  }

  /** Creates a {@code EventLoopHoldingChannel} instance and verifies its authority. */
  @Test
  public void authority_success() throws Exception {
    ManagedChannel channel = new FakeManagedChannel(true);
    EventLoopHoldingChannel eventLoopHoldingChannel =
        EventLoopHoldingChannel.create(channel, mockEventLoopGroup);
    assertThat(eventLoopHoldingChannel.authority()).isEqualTo("FakeManagedChannel");
  }

  /**
   * Creates and closes a {@code EventLoopHoldingChannel} when its {@code ManagedChannel} terminates
   * successfully.
   */
  @Test
  public void close_withDelegateTerminatedSuccess() throws Exception {
    ManagedChannel channel = new FakeManagedChannel(true);
    EventLoopHoldingChannel eventLoopHoldingChannel =
        EventLoopHoldingChannel.create(channel, mockEventLoopGroup);
    eventLoopHoldingChannel.close();
    assertThat(channel.isShutdown()).isTrue();
    verify(mockEventLoopGroup, times(1))
        .shutdownGracefully(0, CHANNEL_SHUTDOWN_TIMEOUT.getSeconds(), SECONDS);
  }

  /**
   * Creates and closes a {@code EventLoopHoldingChannel} when its {@code ManagedChannel} does not
   * terminate successfully.
   */
  @Test
  public void close_withDelegateTerminatedFailure() throws Exception {
    ManagedChannel channel = new FakeManagedChannel(false);
    EventLoopHoldingChannel eventLoopHoldingChannel =
        EventLoopHoldingChannel.create(channel, mockEventLoopGroup);
    eventLoopHoldingChannel.close();
    assertThat(channel.isShutdown()).isTrue();
    verify(mockEventLoopGroup, times(1))
        .shutdownGracefully(1, CHANNEL_SHUTDOWN_TIMEOUT.getSeconds(), SECONDS);
  }

  /**
   * Creates and closes a {@code EventLoopHoldingChannel}, creates a new channel from the same
   * resource, and verifies that this second channel is useable.
   */
  @Test
  public void create_succeedsAfterCloseIsCalledOnce() throws Exception {
    Resource<Channel> resource =
        S2AHandshakerServiceChannel.getChannelResource(
            "localhost:" + plaintextServer.getPort(),
            InsecureChannelCredentials.create());
    Channel channelOne = resource.create();
    resource.close(channelOne);

    Channel channelTwo = resource.create();
    assertThat(channelTwo).isInstanceOf(EventLoopHoldingChannel.class);
    assertThat(
            SimpleServiceGrpc.newBlockingStub(channelTwo)
                .unaryRpc(SimpleRequest.getDefaultInstance()))
        .isEqualToDefaultInstance();
    resource.close(channelTwo);
  }

  /** Same as create_succeedsAfterCloseIsCalledOnce, but use mTLS. */
  @Test
  public void create_mtlsSucceedsAfterCloseIsCalledOnce() throws Exception {
    Resource<Channel> resource =
        S2AHandshakerServiceChannel.getChannelResource(
            "localhost:" + mtlsServer.getPort(), getTlsChannelCredentials());
    Channel channelOne = resource.create();
    resource.close(channelOne);

    Channel channelTwo = resource.create();
    assertThat(channelTwo).isInstanceOf(EventLoopHoldingChannel.class);
    assertThat(
            SimpleServiceGrpc.newBlockingStub(channelTwo)
                .unaryRpc(SimpleRequest.getDefaultInstance()))
        .isEqualToDefaultInstance();
    resource.close(channelTwo);
  }

  private static Server createMtlsServer() throws Exception {
    SimpleServiceImpl service = new SimpleServiceImpl();
    File serverCert = new File("src/test/resources/server_cert.pem");
    File serverKey = new File("src/test/resources/server_key.pem");
    File rootCert = new File("src/test/resources/root_cert.pem");
    ServerCredentials creds =
        TlsServerCredentials.newBuilder()
            .keyManager(serverCert, serverKey)
            .trustManager(rootCert)
            .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
            .build();
    return grpcCleanup.register(
        NettyServerBuilder.forPort(Utils.pickUnusedPort(), creds).addService(service).build());
  }

  private static Server createPlaintextServer() {
    SimpleServiceImpl service = new SimpleServiceImpl();
    return grpcCleanup.register(
        ServerBuilder.forPort(Utils.pickUnusedPort()).addService(service).build());
  }

  private static ChannelCredentials getTlsChannelCredentials() throws Exception {
    File clientCert = new File("src/test/resources/client_cert.pem");
    File clientKey = new File("src/test/resources/client_key.pem");
    File rootCert = new File("src/test/resources/root_cert.pem");
    return TlsChannelCredentials.newBuilder()
            .keyManager(clientCert, clientKey)
            .trustManager(rootCert)
            .build();
  }

  private static class SimpleServiceImpl extends SimpleServiceGrpc.SimpleServiceImplBase {
    @Override
    public void unaryRpc(SimpleRequest request, StreamObserver<SimpleResponse> streamObserver) {
      streamObserver.onNext(SimpleResponse.getDefaultInstance());
      streamObserver.onCompleted();
    }
  }

  private static class FakeManagedChannel extends ManagedChannel {
    private final boolean isDelegateTerminatedSuccess;
    private boolean isShutdown = false;

    FakeManagedChannel(boolean isDelegateTerminatedSuccess) {
      this.isDelegateTerminatedSuccess = isDelegateTerminatedSuccess;
    }

    @Override
    public String authority() {
      return "FakeManagedChannel";
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions options) {
      throw new UnsupportedOperationException("This method should not be called.");
    }

    @Override
    public ManagedChannel shutdown() {
      throw new UnsupportedOperationException("This method should not be called.");
    }

    @Override
    public boolean isShutdown() {
      return isShutdown;
    }

    @Override
    public boolean isTerminated() {
      throw new UnsupportedOperationException("This method should not be called.");
    }

    @Override
    public ManagedChannel shutdownNow() {
      isShutdown = true;
      return null;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      if (isDelegateTerminatedSuccess) {
        return true;
      }
      throw new InterruptedException("Await termination was interrupted.");
    }
  }
}
