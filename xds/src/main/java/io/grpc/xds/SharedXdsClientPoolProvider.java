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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.XdsNameResolverProvider.XdsClientPoolFactory;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * The global factory for creating a singleton {@link XdsClient} instance to be used by all gRPC
 * clients in the process.
 */
@ThreadSafe
final class SharedXdsClientPoolProvider implements XdsClientPoolFactory {

  private final Bootstrapper bootstrapper;
  private final Object lock = new Object();
  private volatile ObjectPool<XdsClient> xdsClientPool;

  private SharedXdsClientPoolProvider() {
    this(new BootstrapperImpl());
  }

  @VisibleForTesting
  SharedXdsClientPoolProvider(Bootstrapper bootstrapper) {
    this.bootstrapper = checkNotNull(bootstrapper, "bootstrapper");
  }

  static SharedXdsClientPoolProvider getDefaultProvider() {
    return SharedXdsClientPoolProviderHolder.instance;
  }

  @Override
  public ObjectPool<XdsClient> getXdsClientPool() throws XdsInitializationException {
    ObjectPool<XdsClient> ref = xdsClientPool;
    if (ref == null) {
      synchronized (lock) {
        ref = xdsClientPool;
        if (ref == null) {
          BootstrapInfo bootstrapInfo = bootstrapper.bootstrap();
          if (bootstrapInfo.getServers().isEmpty()) {
            throw new XdsInitializationException("No xDS server provided");
          }
          ServerInfo serverInfo = bootstrapInfo.getServers().get(0);  // use first server
          ref = xdsClientPool = new RefCountedXdsClientObjectPool(serverInfo.getTarget(),
              serverInfo.getChannelCredentials(), serverInfo.isUseProtocolV3(),
              bootstrapInfo.getNode());
        }
      }
    }
    return ref;
  }

  private static class SharedXdsClientPoolProviderHolder {
    private static final SharedXdsClientPoolProvider instance = new SharedXdsClientPoolProvider();
  }

  @ThreadSafe
  @VisibleForTesting
  static class RefCountedXdsClientObjectPool implements ObjectPool<XdsClient> {
    private final String target;
    private final ChannelCredentials channelCredentials;
    private final Node node;
    private final boolean useProtocolV3;
    private final Object lock = new Object();
    @GuardedBy("lock")
    private ScheduledExecutorService scheduler;
    @GuardedBy("lock")
    private ManagedChannel channel;
    @GuardedBy("lock")
    private XdsClient xdsClient;
    @GuardedBy("lock")
    private int refCount;

    @VisibleForTesting
    RefCountedXdsClientObjectPool(String target, ChannelCredentials channelCredentials,
        boolean useProtocolV3, Node node) {
      this.target = checkNotNull(target, "target");
      this.channelCredentials = checkNotNull(channelCredentials, "channelCredentials");
      this.useProtocolV3 = useProtocolV3;
      this.node = checkNotNull(node, "node");
    }

    @Override
    public XdsClient getObject() {
      synchronized (lock) {
        if (refCount == 0) {
          channel = Grpc.newChannelBuilder(target, channelCredentials)
              .keepAliveTime(5, TimeUnit.MINUTES)
              .build();
          scheduler = SharedResourceHolder.get(GrpcUtil.TIMER_SERVICE);
          xdsClient = new ClientXdsClient(channel, useProtocolV3, node, scheduler,
              new ExponentialBackoffPolicy.Provider(), GrpcUtil.STOPWATCH_SUPPLIER);
        }
        refCount++;
        return xdsClient;
      }
    }

    @Override
    public XdsClient returnObject(Object object) {
      synchronized (lock) {
        refCount--;
        if (refCount == 0) {
          xdsClient.shutdown();
          xdsClient = null;
          channel.shutdown();
          scheduler = SharedResourceHolder.release(GrpcUtil.TIMER_SERVICE, scheduler);
        }
        return null;
      }
    }

    @VisibleForTesting
    @Nullable
    ManagedChannel getChannelForTest() {
      synchronized (lock) {
        return channel;
      }
    }

    @VisibleForTesting
    @Nullable
    XdsClient getXdsClientForTest() {
      synchronized (lock) {
        return xdsClient;
      }
    }
  }
}
