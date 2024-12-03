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

package io.grpc.s2a.internal.channel;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static org.junit.Assert.assertThrows;

import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCredentials;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.internal.SharedResourceHolder.Resource;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import java.io.InputStream;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link S2AHandshakerServiceChannel}. */
@RunWith(JUnit4.class)
public final class S2AHandshakerServiceChannelTest {
  @ClassRule public static final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();
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
   * distinct.
   */
  @Test
  public void getChannelResource_twoUnEqualChannels() {
    Resource<Channel> resource =
        S2AHandshakerServiceChannel.getChannelResource(
            "localhost:" + plaintextServer.getPort(),
            InsecureChannelCredentials.create());
    Resource<Channel> resourceTwo =
        S2AHandshakerServiceChannel.getChannelResource(
            "localhost:" + plaintextServer.getPort(),
            InsecureChannelCredentials.create());
    assertThat(resource).isNotEqualTo(resourceTwo);
  }

  /** Same as getChannelResource_twoUnEqualChannels, but use mTLS. */
  @Test
  public void getChannelResource_mtlsTwoUnEqualChannels() throws Exception {
    Resource<Channel> resource =
        S2AHandshakerServiceChannel.getChannelResource(
            "localhost:" + mtlsServer.getPort(), getTlsChannelCredentials());
    Resource<Channel> resourceTwo =
        S2AHandshakerServiceChannel.getChannelResource(
            "localhost:" + mtlsServer.getPort(), getTlsChannelCredentials());
    assertThat(resource).isNotEqualTo(resourceTwo);
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
            "localhost:" + plaintextServer.getPort() + 1, InsecureChannelCredentials.create());
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
            "localhost:" + mtlsServer.getPort() + 1, getTlsChannelCredentials());
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
   * Creates and closes a {@code ManagedChannel}, creates a new channel from the same
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
    assertThat(channelTwo).isInstanceOf(ManagedChannel.class);
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
    assertThat(channelTwo).isInstanceOf(ManagedChannel.class);
    assertThat(
            SimpleServiceGrpc.newBlockingStub(channelTwo)
                .unaryRpc(SimpleRequest.getDefaultInstance()))
        .isEqualToDefaultInstance();
    resource.close(channelTwo);
  }

  private static Server createMtlsServer() throws Exception {
    SimpleServiceImpl service = new SimpleServiceImpl();
    ClassLoader classLoader = S2AHandshakerServiceChannelTest.class.getClassLoader();
    InputStream serverCert = classLoader.getResourceAsStream("server_cert.pem");
    InputStream serverKey = classLoader.getResourceAsStream("server_key.pem");
    InputStream rootCert = classLoader.getResourceAsStream("root_cert.pem");
    ServerCredentials creds =
        TlsServerCredentials.newBuilder()
            .keyManager(serverCert, serverKey)
            .trustManager(rootCert)
            .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
            .build();
    return grpcCleanup.register(
        NettyServerBuilder.forPort(0, creds).addService(service).build());
  }

  private static Server createPlaintextServer() {
    SimpleServiceImpl service = new SimpleServiceImpl();
    return grpcCleanup.register(
        ServerBuilder.forPort(0).addService(service).build());
  }

  private static ChannelCredentials getTlsChannelCredentials() throws Exception {
    ClassLoader classLoader = S2AHandshakerServiceChannelTest.class.getClassLoader();
    InputStream clientCert = classLoader.getResourceAsStream("client_cert.pem");
    InputStream clientKey = classLoader.getResourceAsStream("client_key.pem");
    InputStream rootCert = classLoader.getResourceAsStream("root_cert.pem");
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
}
