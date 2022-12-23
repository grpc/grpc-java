/*
 * Copyright 2016 The gRPC Authors
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

import com.google.common.util.concurrent.SettableFuture;
import io.grpc.ChannelLogger;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.internal.AbstractTransportTest;
import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.FakeClock;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ManagedClientTransport;
import java.net.InetSocketAddress;
import java.nio.channels.UnresolvedAddressException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for Netty transport. */
@RunWith(JUnit4.class)
public class NettyTransportTest extends AbstractTransportTest {
  private final FakeClock fakeClock = new FakeClock();
  // Avoid LocalChannel for testing because LocalChannel can fail with
  // io.netty.channel.ChannelException instead of java.net.ConnectException which breaks
  // serverNotListening test.
  private final ClientTransportFactory clientFactory = NettyChannelBuilder
      // Although specified here, address is ignored because we never call build.
      .forAddress("localhost", 0)
      .flowControlWindow(AbstractTransportTest.TEST_FLOW_CONTROL_WINDOW)
      .negotiationType(NegotiationType.PLAINTEXT)
      .setTransportTracerFactory(fakeClockTransportTracer)
      .buildTransportFactory();

  @Override
  protected boolean haveTransportTracer() {
    return true;
  }

  @After
  public void releaseClientFactory() {
    clientFactory.close();
  }

  @Override
  protected InternalServer newServer(
      List<ServerStreamTracer.Factory> streamTracerFactories) {
    return NettyServerBuilder
        .forAddress(new InetSocketAddress("localhost", 0))
        .flowControlWindow(AbstractTransportTest.TEST_FLOW_CONTROL_WINDOW)
        .setTransportTracerFactory(fakeClockTransportTracer)
        .buildTransportServers(streamTracerFactories);
  }

  @Override
  protected InternalServer newServer(
      int port, List<ServerStreamTracer.Factory> streamTracerFactories) {
    return NettyServerBuilder
        .forAddress(new InetSocketAddress("localhost", port))
        .flowControlWindow(AbstractTransportTest.TEST_FLOW_CONTROL_WINDOW)
        .setTransportTracerFactory(fakeClockTransportTracer)
        .buildTransportServers(streamTracerFactories);
  }

  @Override
  protected String testAuthority(InternalServer server) {
    return "localhost:" + server.getListenSocketAddress();
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
  protected ManagedClientTransport newClientTransport(InternalServer server) {

    return clientFactory.newClientTransport(
        server.getListenSocketAddress(),
        new ClientTransportFactory.ClientTransportOptions()
            .setAuthority(testAuthority(server))
            .setEagAttributes(eagAttrs()),
        transportLogger());
  }

  @org.junit.Ignore
  @org.junit.Test
  @Override
  public void clientChecksInboundMetadataSize_trailer() throws Exception {
    // Server-side is flaky due to https://github.com/netty/netty/pull/8332
  }

  @Test
  public void channelHasUnresolvedHostname() throws Exception {
    server = null;
    final SettableFuture<Status> future = SettableFuture.create();
    ChannelLogger logger = transportLogger();
    ManagedClientTransport transport = clientFactory.newClientTransport(
        InetSocketAddress.createUnresolved("invalid", 1234),
        new ClientTransportFactory.ClientTransportOptions()
            .setChannelLogger(logger), logger);
    Runnable runnable = transport.start(new ManagedClientTransport.Listener() {
      @Override
      public void transportShutdown(Status s) {
        future.set(s);
      }

      @Override
      public void transportTerminated() {}

      @Override
      public void transportReady() {
        Throwable t = new Throwable("transport should have failed and shutdown but didnt");
        future.setException(t);
      }

      @Override
      public void transportInUse(boolean inUse) {
        Throwable t = new Throwable("transport should have failed and shutdown but didnt");
        future.setException(t);
      }
    });
    if (runnable != null) {
      runnable.run();
    }
    try {
      Status status = future.get();
      assertEquals(Status.Code.UNAVAILABLE, status.getCode());
      assertThat(status.getCause()).isInstanceOf(UnresolvedAddressException.class);
      assertEquals("unresolved address", status.getDescription());
    } finally {
      transport.shutdown(Status.UNAVAILABLE.withDescription("test shutdown"));
    }
  }
}
