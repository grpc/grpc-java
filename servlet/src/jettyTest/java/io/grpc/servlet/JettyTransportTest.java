/*
 * Copyright 2021 The gRPC Authors
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

import io.grpc.InternalChannelz;
import io.grpc.InternalInstrumented;
import io.grpc.ServerStreamTracer;
import io.grpc.internal.AbstractTransportTest;
import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.FakeClock;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.ServerListener;
import io.grpc.internal.ServerTransportListener;
import io.grpc.netty.InternalNettyChannelBuilder;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.eclipse.jetty.http2.parser.RateControl;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.Ignore;
import org.junit.Test;


public class JettyTransportTest extends AbstractTransportTest {
  private static final String MYAPP = "/service";

  private final FakeClock fakeClock = new FakeClock();
  private Server jettyServer;
  private int port;


  @Override
  protected InternalServer newServer(List<ServerStreamTracer.Factory> streamTracerFactories) {
    return new InternalServer() {
      final InternalServer delegate =
              new ServletServerBuilder().buildTransportServers(streamTracerFactories);

      @Override
      public void start(ServerListener listener) throws IOException {
        delegate.start(listener);
        ScheduledExecutorService scheduler = fakeClock.getScheduledExecutorService();
        ServerTransportListener serverTransportListener =
                listener.transportCreated(new ServletServerBuilder.ServerTransportImpl(scheduler));
        ServletAdapter adapter =
                new ServletAdapter(serverTransportListener, streamTracerFactories,
                        Integer.MAX_VALUE);
        GrpcServlet grpcServlet = new GrpcServlet(adapter);

        jettyServer = new Server(0);
        ServerConnector sc = (ServerConnector) jettyServer.getConnectors()[0];
        HttpConfiguration httpConfiguration = new HttpConfiguration();

        // Must be set for several tests to pass, so that the request handling can begin before
        // content arrives.
        httpConfiguration.setDelayDispatchUntilContent(false);

        HTTP2CServerConnectionFactory factory =
                new HTTP2CServerConnectionFactory(httpConfiguration);
        factory.setRateControlFactory(new RateControl.Factory() {
        });
        sc.addConnectionFactory(factory);
        ServletContextHandler context =
                new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath(MYAPP);
        context.addServlet(new ServletHolder(grpcServlet), "/*");
        jettyServer.setHandler(context);

        try {
          jettyServer.start();
        } catch (Exception e) {
          throw new AssertionError(e);
        }

        port = sc.getLocalPort();
      }

      @Override
      public void shutdown() {
        delegate.shutdown();
      }

      @Override
      public SocketAddress getListenSocketAddress() {
        return delegate.getListenSocketAddress();
      }

      @Override
      public InternalInstrumented<InternalChannelz.SocketStats> getListenSocketStats() {
        return delegate.getListenSocketStats();
      }

      @Override
      public List<? extends SocketAddress> getListenSocketAddresses() {
        return delegate.getListenSocketAddresses();
      }

      @Nullable
      @Override
      public List<InternalInstrumented<InternalChannelz.SocketStats>> getListenSocketStatsList() {
        return delegate.getListenSocketStatsList();
      }
    };
  }

  @Override
  protected InternalServer newServer(int port,
                                     List<ServerStreamTracer.Factory> streamTracerFactories) {
    return newServer(streamTracerFactories);
  }

  @Override
  protected ManagedClientTransport newClientTransport(InternalServer server) {
    NettyChannelBuilder nettyChannelBuilder = NettyChannelBuilder
            // Although specified here, address is ignored because we never call build.
            .forAddress("localhost", 0)
            .flowControlWindow(65 * 1024)
            .negotiationType(NegotiationType.PLAINTEXT);
    InternalNettyChannelBuilder
            .setTransportTracerFactory(nettyChannelBuilder, fakeClockTransportTracer);
    ClientTransportFactory clientFactory =
            InternalNettyChannelBuilder.buildTransportFactory(nettyChannelBuilder);
    return clientFactory.newClientTransport(
            new InetSocketAddress("localhost", port),
            new ClientTransportFactory.ClientTransportOptions()
                    .setAuthority(testAuthority(server))
                    .setEagAttributes(eagAttrs()),
            transportLogger());
  }

  @Override
  protected String testAuthority(InternalServer server) {
    return "localhost:" + port;
  }

  @Override
  protected void advanceClock(long offset, TimeUnit unit) {
    fakeClock.forwardTime(offset, unit);
  }

  @Override
  protected long fakeCurrentTimeNanos() {
    return fakeClock.getTicker().read();
  }

  @Override
  @Ignore("Skip the test, server lifecycle is managed by the container")
  @Test
  public void serverAlreadyListening() {
  }

  @Override
  @Ignore("Skip the test, server lifecycle is managed by the container")
  @Test
  public void openStreamPreventsTermination() {
  }

  @Override
  @Ignore("Skip the test, server lifecycle is managed by the container")
  @Test
  public void shutdownNowKillsServerStream() {
  }

  @Override
  @Ignore("Skip the test, server lifecycle is managed by the container")
  @Test
  public void serverNotListening() {
  }

  // FIXME
  @Override
  @Ignore("Servlet flow control not implemented yet")
  @Test
  public void flowControlPushBack() {
  }

  // FIXME
  @Override
  @Ignore("Jetty is broken on client RST_STREAM")
  @Test
  public void shutdownNowKillsClientStream() {
  }

  @Override
  @Ignore("Server side sockets are managed by the servlet container")
  @Test
  public void socketStats() {
  }

  @Override
  @Ignore("serverTransportListener will not terminate")
  @Test
  public void clientStartAndStopOnceConnected() {
  }

  @Override
  @Ignore("clientStreamTracer1.getInboundTrailers() is not null; listeners.poll() doesn't apply")
  @Test
  public void serverCancel() {
  }

  @Override
  @Ignore("This doesn't apply: Ensure that for a closed ServerStream, interactions are noops")
  @Test
  public void interactionsAfterServerStreamCloseAreNoops() {
  }

  @Override
  @Ignore("listeners.poll() doesn't apply")
  @Test
  public void interactionsAfterClientStreamCancelAreNoops() {
  }

  @Override
  @Ignore("assertNull(serverStatus.getCause()) isn't true")
  @Test
  public void clientCancel() {
  }

  @Override
  @Ignore("regression since bumping grpc v1.46 to v1.53")
  @Test
  public void messageProducerOnlyProducesRequestedMessages() {}
}
