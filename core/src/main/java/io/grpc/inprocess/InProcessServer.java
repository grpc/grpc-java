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

package io.grpc.inprocess;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalInstrumented;
import io.grpc.ServerStreamTracer;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServerListener;
import io.grpc.internal.ServerTransportListener;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
final class InProcessServer implements InternalServer {
  private static final ConcurrentMap<String, InProcessServer> registry
      = new ConcurrentHashMap<>();

  static InProcessServer findServer(SocketAddress addr) {
    if (addr instanceof InProcessSocketAddress) {
      InProcessSocketAddress inProcessAddress = (InProcessSocketAddress) addr;
      if (inProcessAddress.getServer() != null) {
        return inProcessAddress.getServer();
      } else {
        return registry.get(inProcessAddress.getName());
      }
    }
    return null;
  }

  private final String name;
  private final boolean anonymous;
  private final int maxInboundMetadataSize;
  private final List<ServerStreamTracer.Factory> streamTracerFactories;
  private ServerListener listener;
  private boolean shutdown;
  /** Defaults to be a SharedResourcePool. */
  private final ObjectPool<ScheduledExecutorService> schedulerPool;
  /**
   * Only used to make sure the scheduler has at least one reference. Since child transports can
   * outlive this server, they must get their own reference.
   */
  private ScheduledExecutorService scheduler;

  InProcessServer(
      InProcessServerBuilder builder,
      List<? extends ServerStreamTracer.Factory> streamTracerFactories) {
    this.name = builder.name;
    this.anonymous = builder.anonymous;
    this.schedulerPool = builder.schedulerPool;
    this.maxInboundMetadataSize = builder.maxInboundMetadataSize;
    this.streamTracerFactories =
        Collections.unmodifiableList(checkNotNull(streamTracerFactories, "streamTracerFactories"));
  }

  @Override
  public void start(ServerListener serverListener) throws IOException {
    this.listener = serverListener;
    this.scheduler = schedulerPool.getObject();
    if (!anonymous) {
      // Must be last, as channels can start connecting after this point.
      if (registry.putIfAbsent(name, this) != null) {
        throw new IOException("name already registered: " + name);
      }
    }
  }

  @Override
  public SocketAddress getListenSocketAddress() {
    return new InProcessSocketAddress(name, anonymous ? this : null);
  }

  @Override
  public List<? extends SocketAddress> getListenSocketAddresses() {
    return Collections.singletonList(getListenSocketAddress());
  }

  @Override
  public InternalInstrumented<SocketStats> getListenSocketStats() {
    return null;
  }

  @Override
  public List<InternalInstrumented<SocketStats>> getListenSocketStatsList() {
    return null;
  }

  @Override
  public void shutdown() {
    if (!registry.remove(name, this)) {
      throw new AssertionError();
    }
    scheduler = schedulerPool.returnObject(scheduler);
    synchronized (this) {
      shutdown = true;
      listener.serverShutdown();
    }
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("name", name).toString();
  }

  synchronized ServerTransportListener register(InProcessTransport transport) {
    if (shutdown) {
      return null;
    }
    return listener.transportCreated(transport);
  }

  ObjectPool<ScheduledExecutorService> getScheduledExecutorServicePool() {
    return schedulerPool;
  }

  int getMaxInboundMetadataSize() {
    return maxInboundMetadataSize;
  }

  List<ServerStreamTracer.Factory> getStreamTracerFactories() {
    return streamTracerFactories;
  }
}
