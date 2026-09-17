/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import io.grpc.ChannelCredentials;
import io.grpc.ChannelLogger;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A wrapper for {@link ClientTransportFactory} that reference-counts calls to {@link #retain()} and
 * {@link #close()}, ensuring the delegate factory is closed only when all references are released.
 */
final class RefCountedClientTransportFactory implements ClientTransportFactory {
  private final ClientTransportFactory delegate;
  private final AtomicInteger refCount = new AtomicInteger(1);

  public RefCountedClientTransportFactory(ClientTransportFactory delegate) {
    this.delegate = checkNotNull(delegate, "delegate");
  }

  public RefCountedClientTransportFactory retain() {
    refCount.incrementAndGet();
    return this;
  }

  @Override
  public ConnectionClientTransport newClientTransport(
      SocketAddress serverAddress, ClientTransportOptions options, ChannelLogger channelLogger) {
    return delegate.newClientTransport(serverAddress, options, channelLogger);
  }

  @Override
  public ScheduledExecutorService getScheduledExecutorService() {
    return delegate.getScheduledExecutorService();
  }

  @Override
  public Collection<Class<? extends SocketAddress>> getSupportedSocketAddressTypes() {
    return delegate.getSupportedSocketAddressTypes();
  }

  @Override
  public SwapChannelCredentialsResult swapChannelCredentials(ChannelCredentials channelCreds) {
    return delegate.swapChannelCredentials(channelCreds);
  }

  @Override
  public void close() {
    int count = refCount.decrementAndGet();
    checkState(count >= 0, "Reference count has gone negative: %s", count);
    if (count == 0) {
      delegate.close();
    }
  }
}
