/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.inprocess;

import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalInstrumented;
import io.grpc.ServerStreamTracer;
import io.grpc.internal.AbstractTransportTest;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServerListener;
import io.grpc.internal.ServerTransport;
import io.grpc.internal.ServerTransportListener;
import io.grpc.internal.SharedResourcePool;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link InProcessTransport} when used with a separate {@link InternalServer}. */
@RunWith(JUnit4.class)
public final class StandaloneInProcessTransportTest extends AbstractTransportTest {
  private static final String TRANSPORT_NAME = "perfect-for-testing";
  private static final String AUTHORITY = "a-testing-authority";
  private static final String USER_AGENT = "a-testing-user-agent";

  private final ObjectPool<ScheduledExecutorService> schedulerPool =
      SharedResourcePool.forResource(GrpcUtil.TIMER_SERVICE);

  private TestServer currentServer;

  @Override
  protected InternalServer newServer(
      List<ServerStreamTracer.Factory> streamTracerFactories) {
    return new TestServer(streamTracerFactories);
  }

  @Override
  protected InternalServer newServer(
      int port, List<ServerStreamTracer.Factory> streamTracerFactories) {
    return newServer(streamTracerFactories);
  }

  @Override
  protected String testAuthority(InternalServer server) {
    return AUTHORITY;
  }

  @Override
  protected ManagedClientTransport newClientTransport(InternalServer server) {
    TestServer testServer = (TestServer) server;
    return InternalInProcess.createInProcessTransport(
        TRANSPORT_NAME,
        GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE,
        testAuthority(server),
        USER_AGENT,
        eagAttrs(),
        schedulerPool,
        testServer.streamTracerFactories,
        testServer.serverListener);
  }

  @Override
  protected boolean sizesReported() {
    // TODO(zhangkun83): InProcessTransport doesn't record metrics for now
    // (https://github.com/grpc/grpc-java/issues/2284)
    return false;
  }

  @Test
  @Ignore
  @Override
  public void socketStats() throws Exception {
    // test does not apply to in-process
  }

  /** An internalserver just for this test. */
  private final class TestServer implements InternalServer {

    final List<ServerStreamTracer.Factory> streamTracerFactories;
    ServerListener serverListener;

    TestServer(List<ServerStreamTracer.Factory> streamTracerFactories) {
      this.streamTracerFactories = streamTracerFactories;
    }

    @Override
    public void start(ServerListener serverListener) throws IOException {
      if (currentServer != null) {
        throw new IOException("Server already present");
      }
      currentServer = this;
      this.serverListener = new ServerListenerWrapper(serverListener);
    }

    @Override
    public void shutdown() {
      currentServer = null;
      serverListener.serverShutdown();
    }

    @Override
    public SocketAddress getListenSocketAddress() {
      return new SocketAddress() {};
    }

    @Override
    public List<SocketAddress> getListenSocketAddresses() {
      return Collections.singletonList(getListenSocketAddress());
    }

    @Override
    @Nullable
    public InternalInstrumented<SocketStats> getListenSocketStats() {
      return null;
    }

    @Override
    @Nullable
    public List<InternalInstrumented<SocketStats>> getListenSocketStatsList() {
      return null;
    }
  }

  /** Wraps the server listener to ensure we don't accept new transports after shutdown. */
  private static final class ServerListenerWrapper implements ServerListener {
    private final ServerListener delegateListener;
    private boolean shutdown;

    ServerListenerWrapper(ServerListener delegateListener) {
      this.delegateListener = delegateListener;
    }

    @Override
    public ServerTransportListener transportCreated(ServerTransport transport) {
      if (shutdown) {
        return null;
      }
      return delegateListener.transportCreated(transport);
    }

    @Override
    public void serverShutdown() {
      shutdown = true;
      delegateListener.serverShutdown();
    }
  }
}
