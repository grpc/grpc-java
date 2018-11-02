/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.servlet;

import static io.undertow.servlet.Servlets.defaultContainer;
import static io.undertow.servlet.Servlets.deployment;
import static io.undertow.servlet.Servlets.servlet;

import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalInstrumented;
import io.grpc.ServerStreamTracer.Factory;
import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.FakeClock;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.ServerListener;
import io.grpc.internal.ServerTransportListener;
import io.grpc.internal.testing.AbstractTransportTest;
import io.grpc.netty.InternalNettyTestAccessor;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.servlet.ServletServerBuilder.ServerTransportImpl;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.InstanceFactory;
import io.undertow.servlet.util.ImmediateInstanceHandle;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Transport test for Undertow server and Netty client.
 */
public class UndertowTransportTest extends AbstractTransportTest {
  private static final String HOST = "localhost";
  private static final String MYAPP = "/service";

  private final FakeClock fakeClock = new FakeClock();

  private Undertow server;
  private DeploymentManager manager;
  private int port;

  @After
  @Override
  public void tearDown() throws InterruptedException {
    super.tearDown();
    if (server != null) {
      server.stop();
    }
    if (manager != null) {
      try {
        manager.stop();
      } catch (ServletException e) {
        throw new AssertionError("failed to stop container", e);
      }
    }
  }

  @Override
  protected boolean isServletServer() {
    return true;
  }

  @Override
  protected InternalServer newServer(List<Factory> streamTracerFactories) {
    return new InternalServer() {
      final InternalServer delegate =
          new ServletServerBuilder().buildTransportServer(streamTracerFactories);

      @Override
      public void start(ServerListener listener) throws IOException {
        delegate.start(listener);
        ScheduledExecutorService scheduler = fakeClock.getScheduledExecutorService();
        ServerTransportListener serverTransportListener =
            listener.transportCreated(new ServerTransportImpl(scheduler, true));
        ServletAdapter adapter =
            new ServletAdapter(serverTransportListener, streamTracerFactories);
        GrpcServlet grpcServlet = new GrpcServlet(adapter);
        InstanceFactory<? extends Servlet> instanceFactory =
            () -> new ImmediateInstanceHandle<>(grpcServlet);
        DeploymentInfo servletBuilder =
            deployment()
                .setClassLoader(UndertowInteropTest.class.getClassLoader())
                .setContextPath(MYAPP)
                .setDeploymentName("UndertowTransportTest.war")
                .addServlets(
                    servlet("TransportTestServlet", GrpcServlet.class, instanceFactory)
                        .addMapping("/*")
                        .setAsyncSupported(true));

        manager = defaultContainer().addDeployment(servletBuilder);
        manager.deploy();

        HttpHandler servletHandler;
        try {
          servletHandler = manager.start();
        } catch (ServletException e) {
          throw new RuntimeException(e);
        }
        PathHandler path =
            Handlers.path(Handlers.redirect(MYAPP))
                .addPrefixPath("/", servletHandler); // for unimplementedService test
        server =
            Undertow.builder()
                .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
                .addHttpListener(0, HOST)
                .setHandler(path)
                .build();
        server.start();
        port = ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();
      }

      @Override
      public void shutdown() {
        delegate.shutdown();
      }

      @Override
      public int getPort() {
        return delegate.getPort();
      }

      @Override
      public List<InternalInstrumented<SocketStats>> getListenSockets() {
        return delegate.getListenSockets();
      }
    };
  }

  @Override
  protected InternalServer newServer(InternalServer server, List<Factory> streamTracerFactories) {
    return null;
  }

  @Override
  protected ManagedClientTransport newClientTransport(InternalServer server) {
    NettyChannelBuilder nettyChannelBuilder = NettyChannelBuilder
        // Although specified here, address is ignored because we never call build.
        .forAddress("localhost", 0)
        .flowControlWindow(65 * 1024)
        .negotiationType(NegotiationType.PLAINTEXT);
    InternalNettyTestAccessor
        .setTransportTracerFactory(nettyChannelBuilder, fakeClockTransportTracer);
    ClientTransportFactory clientFactory =
        InternalNettyTestAccessor.buildTransportFactory(nettyChannelBuilder);
    return clientFactory.newClientTransport(
        new InetSocketAddress("localhost", port),
        new ClientTransportFactory.ClientTransportOptions()
            .setAuthority(testAuthority(server)));
  }

  @Override
  protected String testAuthority(InternalServer server) {
    return "localhost:" + port;
  }

  @Override
  protected void advanceClock(long offset, TimeUnit unit) {
    fakeClock.forwardNanos(unit.toNanos(offset));
  }

  @Override
  protected long fakeCurrentTimeNanos() {
    return fakeClock.getTicker().read();
  }

  @Override
  @Ignore("Skip the test, server lifecycle is managed by the container")
  @Test
  public void serverAlreadyListening() {}

  @Override
  @Ignore("Skip the test, server lifecycle is managed by the container")
  @Test
  public void openStreamPreventsTermination() {}

  @Override
  @Ignore("Skip the test, server lifecycle is managed by the container")
  @Test
  public void shutdownNowKillsServerStream() {}

  @Override
  @Ignore("Skip the test, server lifecycle is managed by the container")
  @Test
  public void serverNotListening() {}

  @Override
  @Ignore("Skip the test, can not set HTTP/2 SETTINGS_MAX_HEADER_LIST_SIZE")
  @Test
  public void serverChecksInboundMetadataSize() {}

  // FIXME
  @Override
  @Ignore("Undertow is broken on client GOAWAY")
  @Test
  public void newStream_duringShutdown() {}

  // FIXME
  @Override
  @Ignore("Undertow is broken on client GOAWAY")
  @Test
  public void ping_duringShutdown() {}

  // FIXME
  @Override
  @Ignore("Undertow is broken on client RST_STREAM")
  @Test
  public void frameAfterRstStreamShouldNotBreakClientChannel() {}

  // FIXME
  @Override
  @Ignore("Undertow is broken on client RST_STREAM")
  @Test
  public void shutdownNowKillsClientStream() {}

  // FIXME
  @Override
  @Ignore("Undertow flow control non implemented yet")
  @Test
  public void flowControlPushBack() {}
}
