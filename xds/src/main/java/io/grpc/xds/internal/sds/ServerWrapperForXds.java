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

package io.grpc.xds.internal.sds;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.xds.EnvoyServerProtoData;
import io.grpc.xds.XdsClientWrapperForServerSds;
import io.grpc.xds.XdsServerBuilder;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * Wraps a {@link Server} delegate and {@link XdsClientWrapperForServerSds} and intercepts {@link
 * Server#shutdown()} and {@link Server#start()} to shut down and start the
 * {@link XdsClientWrapperForServerSds} object.
 */
@VisibleForTesting
public final class ServerWrapperForXds extends Server {
  private final Server delegate;
  private final XdsClientWrapperForServerSds xdsClientWrapperForServerSds;
  @Nullable XdsServerBuilder.ErrorNotifier errorNotifier;
  @Nullable XdsClientWrapperForServerSds.ServerWatcher serverWatcher;
  private AtomicBoolean started = new AtomicBoolean();

  /** Creates the wrapper object using the delegate passed. */
  public ServerWrapperForXds(
      Server delegate,
      XdsClientWrapperForServerSds xdsClientWrapperForServerSds,
      @Nullable XdsServerBuilder.ErrorNotifier errorNotifier) {
    this.delegate = checkNotNull(delegate, "delegate");
    this.xdsClientWrapperForServerSds =
        checkNotNull(xdsClientWrapperForServerSds, "xdsClientWrapperForServerSds");
    this.errorNotifier = errorNotifier;
  }

  @Override
  public Server start() throws IOException {
    checkState(started.compareAndSet(false, true), "Already started");
    Future<EnvoyServerProtoData.DownstreamTlsContext> future = addServerWatcher();
    if (!xdsClientWrapperForServerSds.hasXdsClient()) {
      xdsClientWrapperForServerSds.createXdsClientAndStart();
    }
    try {
      future.get();
    } catch (InterruptedException | ExecutionException ex) {
      removeServerWatcher();
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new RuntimeException(ex);
    }
    delegate.start();
    return this;
  }

  private Future<EnvoyServerProtoData.DownstreamTlsContext> addServerWatcher() {
    final SettableFuture<EnvoyServerProtoData.DownstreamTlsContext> settableFuture =
        SettableFuture.create();
    serverWatcher =
        new XdsClientWrapperForServerSds.ServerWatcher() {
          @Override
          public void onError(Status error) {
            if (errorNotifier != null) {
              errorNotifier.onError(error);
            }
          }

          @Override
          public void onSuccess(EnvoyServerProtoData.DownstreamTlsContext downstreamTlsContext) {
            removeServerWatcher();
            settableFuture.set(downstreamTlsContext);
          }
        };
    xdsClientWrapperForServerSds.addServerWatcher(serverWatcher);
    return settableFuture;
  }

  private void removeServerWatcher() {
    synchronized (xdsClientWrapperForServerSds) {
      if (serverWatcher != null) {
        xdsClientWrapperForServerSds.removeServerWatcher(serverWatcher);
        serverWatcher = null;
      }
    }
  }

  @Override
  public Server shutdown() {
    delegate.shutdown();
    xdsClientWrapperForServerSds.shutdown();
    return this;
  }

  @Override
  public Server shutdownNow() {
    delegate.shutdownNow();
    xdsClientWrapperForServerSds.shutdown();
    return this;
  }

  @Override
  public boolean isShutdown() {
    return delegate.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return delegate.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return delegate.awaitTermination(timeout, unit);
  }

  @Override
  public void awaitTermination() throws InterruptedException {
    delegate.awaitTermination();
  }

  @Override
  public int getPort() {
    return delegate.getPort();
  }

  @Override
  public List<? extends SocketAddress> getListenSockets() {
    return delegate.getListenSockets();
  }

  @Override
  public List<ServerServiceDefinition> getServices() {
    return delegate.getServices();
  }

  @Override
  public List<ServerServiceDefinition> getImmutableServices() {
    return delegate.getImmutableServices();
  }

  @Override
  public List<ServerServiceDefinition> getMutableServices() {
    return delegate.getMutableServices();
  }
}
