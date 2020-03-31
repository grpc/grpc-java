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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.xds.XdsClientWrapperForServerSds;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Wraps a {@link Server} delegate and {@link XdsClientWrapperForServerSds} and intercepts
 * {@link Server#shutdown()} etc to properly shut down the {@link XdsClientWrapperForServerSds}
 * object.
 */
@VisibleForTesting
public final class XdsServer extends Server {
  private final Server delegate;
  @Nullable private final XdsClientWrapperForServerSds xdsClientWrapperForServerSds;

  XdsServer(Server delegate, XdsClientWrapperForServerSds xdsClientWrapperForServerSds) {
    checkNotNull(delegate, "delegate");
    this.delegate = delegate;
    this.xdsClientWrapperForServerSds = xdsClientWrapperForServerSds;
  }

  @Override
  public Server start() throws IOException {
    delegate.start();
    return this;
  }

  @Override
  public Server shutdown() {
    if (xdsClientWrapperForServerSds != null) {
      xdsClientWrapperForServerSds.shutdown();
    }
    delegate.shutdown();
    return this;
  }

  @Override
  public Server shutdownNow() {
    if (xdsClientWrapperForServerSds != null) {
      xdsClientWrapperForServerSds.shutdown();
    }
    delegate.shutdownNow();
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
