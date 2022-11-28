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
import io.grpc.Context;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.internal.TimeProvider;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.XdsClientImpl.XdsChannelFactory;
import io.grpc.xds.XdsNameResolverProvider.XdsClientPoolFactory;
import io.grpc.xds.internal.security.TlsContextManagerImpl;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
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
  private final AtomicReference<Map<String, ?>> bootstrapOverride = new AtomicReference<>();
  private volatile ObjectPool<XdsClient> xdsClientPool;

  SharedXdsClientPoolProvider() {
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
  public void setBootstrapOverride(Map<String, ?> bootstrap) {
    bootstrapOverride.set(bootstrap);
  }

  @Override
  @Nullable
  public ObjectPool<XdsClient> get() {
    return xdsClientPool;
  }

  @Override
  public ObjectPool<XdsClient> getOrCreate() throws XdsInitializationException {
    ObjectPool<XdsClient> ref = xdsClientPool;
    if (ref == null) {
      synchronized (lock) {
        ref = xdsClientPool;
        if (ref == null) {
          BootstrapInfo bootstrapInfo;
          Map<String, ?> rawBootstrap = bootstrapOverride.get();
          if (rawBootstrap != null) {
            bootstrapInfo = bootstrapper.bootstrap(rawBootstrap);
          } else {
            bootstrapInfo = bootstrapper.bootstrap();
          }
          if (bootstrapInfo.servers().isEmpty()) {
            throw new XdsInitializationException("No xDS server provided");
          }
          ref = xdsClientPool = new RefCountedXdsClientObjectPool(bootstrapInfo);
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
    private final Context context = Context.ROOT;
    private final BootstrapInfo bootstrapInfo;
    private final Object lock = new Object();
    @GuardedBy("lock")
    private ScheduledExecutorService scheduler;
    @GuardedBy("lock")
    private XdsClient xdsClient;
    @GuardedBy("lock")
    private int refCount;

    @VisibleForTesting
    RefCountedXdsClientObjectPool(BootstrapInfo bootstrapInfo) {
      this.bootstrapInfo = checkNotNull(bootstrapInfo);
    }

    @Override
    public XdsClient getObject() {
      synchronized (lock) {
        if (refCount == 0) {
          scheduler = SharedResourceHolder.get(GrpcUtil.TIMER_SERVICE);
          xdsClient = new XdsClientImpl(
              XdsChannelFactory.DEFAULT_XDS_CHANNEL_FACTORY,
              bootstrapInfo,
              context,
              scheduler,
              new ExponentialBackoffPolicy.Provider(),
              GrpcUtil.STOPWATCH_SUPPLIER,
              TimeProvider.SYSTEM_TIME_PROVIDER,
              new TlsContextManagerImpl(bootstrapInfo));
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

    @VisibleForTesting
    @Nullable
    XdsClient getXdsClientForTest() {
      synchronized (lock) {
        return xdsClient;
      }
    }
  }
}
