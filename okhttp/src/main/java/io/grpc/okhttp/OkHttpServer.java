/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.okhttp;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.InternalChannelz;
import io.grpc.InternalInstrumented;
import io.grpc.InternalLogId;
import io.grpc.ServerStreamTracer;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServerListener;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ServerSocketFactory;

final class OkHttpServer implements InternalServer {
  private static final Logger log = Logger.getLogger(OkHttpServer.class.getName());

  private final SocketAddress originalListenAddress;
  private final ServerSocketFactory socketFactory;
  private final ObjectPool<Executor> transportExecutorPool;
  private final ObjectPool<ScheduledExecutorService> scheduledExecutorServicePool;
  private final OkHttpServerTransport.Config transportConfig;
  private final InternalChannelz channelz;
  private ServerSocket serverSocket;
  private SocketAddress actualListenAddress;
  private InternalInstrumented<InternalChannelz.SocketStats> listenInstrumented;
  private Executor transportExecutor;
  private ScheduledExecutorService scheduledExecutorService;
  private ServerListener listener;
  private boolean shutdown;

  public OkHttpServer(
      OkHttpServerBuilder builder,
      List<? extends ServerStreamTracer.Factory> streamTracerFactories,
      InternalChannelz channelz) {
    this.originalListenAddress = Preconditions.checkNotNull(builder.listenAddress, "listenAddress");
    this.socketFactory = Preconditions.checkNotNull(builder.socketFactory, "socketFactory");
    this.transportExecutorPool =
        Preconditions.checkNotNull(builder.transportExecutorPool, "transportExecutorPool");
    this.scheduledExecutorServicePool =
        Preconditions.checkNotNull(
            builder.scheduledExecutorServicePool, "scheduledExecutorServicePool");
    this.transportConfig = new OkHttpServerTransport.Config(builder, streamTracerFactories);
    this.channelz = Preconditions.checkNotNull(channelz, "channelz");
  }

  @Override
  public void start(ServerListener listener) throws IOException {
    this.listener = Preconditions.checkNotNull(listener, "listener");
    ServerSocket serverSocket = socketFactory.createServerSocket();
    try {
      serverSocket.bind(originalListenAddress);
    } catch (IOException t) {
      serverSocket.close();
      throw t;
    }

    this.serverSocket = serverSocket;
    this.actualListenAddress = serverSocket.getLocalSocketAddress();
    this.listenInstrumented = new ListenSocket(serverSocket);
    this.transportExecutor = transportExecutorPool.getObject();
    // Keep reference alive to avoid frequent re-creation by server transports
    this.scheduledExecutorService = scheduledExecutorServicePool.getObject();
    channelz.addListenSocket(this.listenInstrumented);
    transportExecutor.execute(this::acceptConnections);
  }

  private void acceptConnections() {
    try {
      while (true) {
        Socket socket;
        try {
          socket = serverSocket.accept();
        } catch (IOException ex) {
          if (shutdown) {
            break;
          }
          throw ex;
        }
        OkHttpServerTransport transport = new OkHttpServerTransport(transportConfig, socket);
        transport.start(listener.transportCreated(transport));
      }
    } catch (Throwable t) {
      log.log(Level.SEVERE, "Accept loop failed", t);
    }
    listener.serverShutdown();
  }

  @Override
  public void shutdown() {
    if (shutdown) {
      return;
    }
    shutdown = true;

    if (serverSocket == null) {
      return;
    }
    channelz.removeListenSocket(this.listenInstrumented);
    try {
      serverSocket.close();
    } catch (IOException ex) {
      log.log(Level.WARNING, "Failed closing server socket", serverSocket);
    }
    transportExecutor = transportExecutorPool.returnObject(transportExecutor);
    scheduledExecutorService = scheduledExecutorServicePool.returnObject(scheduledExecutorService);
  }

  @Override
  public SocketAddress getListenSocketAddress() {
    return actualListenAddress;
  }

  @Override
  public InternalInstrumented<InternalChannelz.SocketStats> getListenSocketStats() {
    return listenInstrumented;
  }

  @Override
  public List<? extends SocketAddress> getListenSocketAddresses() {
    return Collections.singletonList(getListenSocketAddress());
  }

  @Override
  public List<InternalInstrumented<InternalChannelz.SocketStats>> getListenSocketStatsList() {
    return Collections.singletonList(getListenSocketStats());
  }

  private static final class ListenSocket
      implements InternalInstrumented<InternalChannelz.SocketStats> {
    private final InternalLogId id;
    private final ServerSocket socket;

    public ListenSocket(ServerSocket socket) {
      this.socket = socket;
      this.id = InternalLogId.allocate(getClass(), String.valueOf(socket.getLocalSocketAddress()));
    }

    @Override
    public ListenableFuture<InternalChannelz.SocketStats> getStats() {
      return Futures.immediateFuture(new InternalChannelz.SocketStats(
          /*data=*/ null,
          socket.getLocalSocketAddress(),
          /*remote=*/ null,
          new InternalChannelz.SocketOptions.Builder().build(),
          /*security=*/ null));
    }

    @Override
    public InternalLogId getLogId() {
      return id;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("logId", id.getId())
          .add("socket", socket)
          .toString();
    }
  }
}
