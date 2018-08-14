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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.Server;
import io.grpc.ServerStreamTracer.Factory;
import io.grpc.Status;
import io.grpc.internal.AbstractServerImplBuilder;
import io.grpc.internal.Channelz.SocketStats;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.Instrumented;
import io.grpc.internal.InternalServer;
import io.grpc.internal.LogId;
import io.grpc.internal.ServerListener;
import io.grpc.internal.ServerTransport;
import io.grpc.internal.ServerTransportListener;
import io.grpc.internal.SharedResourceHolder;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Builder to build a gRPC server that can run as a servlet.
 */
@NotThreadSafe
public final class ServletServerBuilder extends AbstractServerImplBuilder<ServletServerBuilder> {

  private ScheduledExecutorService scheduler;
  private boolean internalCaller;
  private boolean usingCustomScheduler;
  private ServerListener serverListener;

  /**
   * Builds a gRPC server that can run as a servlet.
   *
   * <p>The returned server will not been started or be bound a port.
   *
   * <p>Users should not call this method directly. Instead users should call {@link
   * ServletAdapter.Factory#create(ServletServerBuilder)}, which internally will call {@code
   * build()} and {@code start()} appropriately.
   *
   * @throws IllegalStateException if this method is called by users directly
   */
  @Override
  public Server build() {
    checkState(internalCaller, "method should not be called by the user");
    return super.build();
  }

  ServerTransportListener buildAndStart() {
    try {
      internalCaller = true;
      build().start();
      internalCaller = false;
    } catch (IOException e) {
      // actually this should never happen
      throw new RuntimeException(e);
    }

    if (!usingCustomScheduler) {
      scheduler = SharedResourceHolder.get(GrpcUtil.TIMER_SERVICE);
    }

    // Create only one "transport" for all requests because it has no knowledge of which request is
    // associated with which client socket. This "transport" does not do socket connection, the
    // container does.
    ServerTransportImpl serverTransport =
        new ServerTransportImpl(scheduler, usingCustomScheduler);
    return serverListener.transportCreated(serverTransport);
  }

  @Override
  protected InternalServer buildTransportServer(List<Factory> streamTracerFactories) {
    InternalServerImpl internalServer = new InternalServerImpl();
    serverListener = internalServer.serverListener;
    return internalServer;
  }

  @Override
  public ServletServerBuilder useTransportSecurity(File certChain, File privateKey) {
    throw new UnsupportedOperationException("TLS should be configured by the servlet container");
  }

  @Override
  public ServletServerBuilder maxInboundMessageSize(int bytes) {
    // TODO
    return this;
  }

  /**
   * Provides a custom scheduled executor service to the server builder.
   *
   * @return this
   */
  public ServletServerBuilder scheduledExecutorService(ScheduledExecutorService scheduler) {
    this.scheduler = checkNotNull(scheduler, "scheduler");
    usingCustomScheduler = true;
    return this;
  }

  ScheduledExecutorService getScheduledExecutorService() {
    return scheduler;
  }

  private static final class InternalServerImpl implements InternalServer {

    ServerListener serverListener;

    InternalServerImpl() {}

    @Override
    public void start(ServerListener listener) {
      serverListener = listener;
    }

    @Override
    public void shutdown() {
      if (serverListener != null) {
        serverListener.serverShutdown();
      }
    }

    @Override
    public int getPort() {
      // port is managed by the servlet container, grpc is ignorant of that
      return -1;
    }

    @Override
    public List<Instrumented<SocketStats>> getListenSockets() {
      // sockets are managed by the servlet container, grpc is ignorant of that
      return Collections.emptyList();
    }
  }

  private static final class ServerTransportImpl implements ServerTransport {

    private final LogId logId = LogId.allocate(getClass().getName());
    private final ScheduledExecutorService scheduler;
    private final boolean usingCustomScheduler;

    ServerTransportImpl(
        ScheduledExecutorService scheduler, boolean usingCustomScheduler) {
      this.scheduler = checkNotNull(scheduler, "scheduler");
      this.usingCustomScheduler = usingCustomScheduler;
    }

    @Override
    public void shutdown() {
      if (!usingCustomScheduler) {
        SharedResourceHolder.release(GrpcUtil.TIMER_SERVICE, scheduler);
      }
    }

    @Override
    public void shutdownNow(Status reason) {
      // TODO:
    }

    @Override
    public ScheduledExecutorService getScheduledExecutorService() {
      return scheduler;
    }

    @Override
    public ListenableFuture<SocketStats> getStats() {
      // does not support instrumentation
      return null;
    }

    @Override
    public LogId getLogId() {
      return logId;
    }
  }
}
