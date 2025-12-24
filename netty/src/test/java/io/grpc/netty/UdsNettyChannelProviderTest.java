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

package io.grpc.netty;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InternalServiceProviders;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.ManagedChannelProvider;
import io.grpc.ManagedChannelProvider.NewChannelBuilderResult;
import io.grpc.ManagedChannelRegistryAccessor;
import io.grpc.TlsChannelCredentials;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerDomainSocketChannel;
import io.netty.channel.unix.DomainSocketAddress;
import java.io.IOException;
import org.junit.After;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link UdsNettyChannelProvider}. */
@RunWith(JUnit4.class)
public class UdsNettyChannelProviderTest {

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();


  private UdsNettyChannelProvider provider = new UdsNettyChannelProvider();

  private EventLoopGroup elg;
  private EventLoopGroup boss;

  @After
  public void tearDown() {
    if (elg != null) {
      elg.shutdownGracefully();
    }
    if (boss != null) {
      boss.shutdownGracefully();
    }
  }

  @Test
  public void provided() {
    for (ManagedChannelProvider current
        : InternalServiceProviders.getCandidatesViaServiceLoader(
            ManagedChannelProvider.class, getClass().getClassLoader())) {
      if (current instanceof UdsNettyChannelProvider) {
        return;
      }
    }
    fail("ServiceLoader unable to load UdsNettyChannelProvider");
  }

  @Test
  public void providedHardCoded() {
    for (Class<?> current : ManagedChannelRegistryAccessor.getHardCodedClasses()) {
      if (current == UdsNettyChannelProvider.class) {
        return;
      }
    }
    fail("Hard coded unable to load UdsNettyChannelProvider");
  }

  @Test
  public void basicMethods() {
    Assume.assumeTrue(provider.isAvailable());
    assertEquals(3, provider.priority());
  }

  @Test
  public void newChannelBuilder_success() {
    Assume.assumeTrue(Utils.isEpollAvailable());
    NewChannelBuilderResult result =
        provider.newChannelBuilder("unix:sock.sock", TlsChannelCredentials.create());
    assertThat(result.getChannelBuilder()).isInstanceOf(NettyChannelBuilder.class);
  }

  @Test
  public void managedChannelRegistry_newChannelBuilder() {
    Assume.assumeTrue(Utils.isEpollAvailable());
    ManagedChannelBuilder<?> managedChannelBuilder
            = Grpc.newChannelBuilder("unix:///sock.sock", InsecureChannelCredentials.create());
    assertThat(managedChannelBuilder).isNotNull();
    ManagedChannel channel = managedChannelBuilder.build();
    assertThat(channel).isNotNull();
    assertThat(channel.authority()).isEqualTo("/sock.sock");
    channel.shutdownNow();
  }

  @Test
  public void udsClientServerTestUsingProvider() throws IOException {
    Assume.assumeTrue(Utils.isEpollAvailable());
    String socketPath = tempFolder.getRoot().getAbsolutePath() + "/test.socket";
    createUdsServer(socketPath);
    ManagedChannelBuilder<?> channelBuilder =
        Grpc.newChannelBuilder("unix://" + socketPath, InsecureChannelCredentials.create());
    SimpleServiceGrpc.SimpleServiceBlockingStub stub =
        SimpleServiceGrpc.newBlockingStub(cleanupRule.register(channelBuilder.build()));
    assertThat(unaryRpc("buddy", stub)).isEqualTo("Hello buddy");
  }

  /** Say hello to server. */
  private static String unaryRpc(
      String requestMessage, SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub) {
    SimpleRequest request = SimpleRequest.newBuilder().setRequestMessage(requestMessage).build();
    SimpleResponse response = blockingStub.unaryRpc(request);
    return response.getResponseMessage();
  }

  private void createUdsServer(String name) throws IOException {
    elg = new EpollEventLoopGroup();
    boss = new EpollEventLoopGroup(1);
    cleanupRule.register(
        NettyServerBuilder.forAddress(new DomainSocketAddress(name))
            .bossEventLoopGroup(boss)
            .workerEventLoopGroup(elg)
            .channelType(EpollServerDomainSocketChannel.class)
            .addService(new SimpleServiceImpl())
            .directExecutor()
            .build()
            .start());
  }

  private static class SimpleServiceImpl extends SimpleServiceGrpc.SimpleServiceImplBase {

    @Override
    public void unaryRpc(SimpleRequest req, StreamObserver<SimpleResponse> responseObserver) {
      SimpleResponse response =
          SimpleResponse.newBuilder()
              .setResponseMessage("Hello " + req.getRequestMessage())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }
}
