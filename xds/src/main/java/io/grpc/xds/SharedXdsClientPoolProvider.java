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
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.XdsClient.XdsChannel;
import io.grpc.xds.XdsNameResolverProvider.XdsClientPoolFactory;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * The global factory for creating a singleton {@link XdsClient} instance to be used by all gRPC
 * clients in the process.
 */
@ThreadSafe
final class SharedXdsClientPoolProvider implements XdsClientPoolFactory {
  private final Bootstrapper bootstrapper;
  private final XdsChannelFactory channelFactory;
  private final Object lock = new Object();
  private volatile ObjectPool<XdsClient> xdsClientPool;

  private SharedXdsClientPoolProvider() {
    this(Bootstrapper.getInstance(), XdsChannelFactory.getInstance());
  }

  @VisibleForTesting
  SharedXdsClientPoolProvider(
      Bootstrapper bootstrapper, XdsChannelFactory channelFactory) {
    this.bootstrapper = checkNotNull(bootstrapper, "bootstrapper");
    this.channelFactory = checkNotNull(channelFactory, "channelFactory");
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
          BootstrapInfo bootstrapInfo = bootstrapper.readBootstrap();
          XdsChannel channel = channelFactory.createChannel(bootstrapInfo.getServers());
          ref = xdsClientPool = new RefCountedXdsClientObjectPool(channel, bootstrapInfo.getNode());
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
    private final XdsChannel channel;
    private final Node node;
    private final XdsClientFactory factory;
    private final Object lock = new Object();
    @GuardedBy("lock")
    private ScheduledExecutorService scheduler;
    @GuardedBy("lock")
    private XdsClient xdsClient;
    @GuardedBy("lock")
    private int refCount;

    RefCountedXdsClientObjectPool(XdsChannel channel, Node node) {
      this(channel, node, XdsClientFactory.INSTANCE);
    }

    @VisibleForTesting
    RefCountedXdsClientObjectPool(XdsChannel channel, Node node, XdsClientFactory factory) {
      this.channel = checkNotNull(channel, "channel");
      this.node = checkNotNull(node, "node");
      this.factory = checkNotNull(factory, "factory");
    }

    @Override
    public XdsClient getObject() {
      synchronized (lock) {
        if (xdsClient == null) {
          scheduler = SharedResourceHolder.get(GrpcUtil.TIMER_SERVICE);
          xdsClient = factory.newXdsClient(channel, node, scheduler);
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
          scheduler = SharedResourceHolder.release(GrpcUtil.TIMER_SERVICE, scheduler);
        }
        return null;
      }
    }

    // Introduced for testing.
    @VisibleForTesting
    abstract static class XdsClientFactory {
      private static final XdsClientFactory INSTANCE = new XdsClientFactory() {
        @Override
        XdsClient newXdsClient(XdsChannel channel, Node node,
            ScheduledExecutorService timeService) {
          return new ClientXdsClient(channel, node, timeService,
              new ExponentialBackoffPolicy.Provider(), GrpcUtil.STOPWATCH_SUPPLIER);
        }
      };

      abstract XdsClient newXdsClient(XdsChannel channel, Node node,
          ScheduledExecutorService timeService);
    }
  }
}
