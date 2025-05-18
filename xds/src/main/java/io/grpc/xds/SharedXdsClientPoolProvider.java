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
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import io.grpc.CallCredentials;
import io.grpc.MetricRecorder;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.internal.TimeProvider;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.Bootstrapper.BootstrapInfo;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsClientImpl;
import io.grpc.xds.client.XdsInitializationException;
import io.grpc.xds.internal.security.TlsContextManagerImpl;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * The global factory for creating a singleton {@link XdsClient} instance to be used by all gRPC
 * clients in the process.
 */
@ThreadSafe
final class SharedXdsClientPoolProvider implements XdsClientPoolFactory {
  private static final boolean LOG_XDS_NODE_ID = Boolean.parseBoolean(
      System.getenv("GRPC_LOG_XDS_NODE_ID"));
  private static final Logger log = Logger.getLogger(XdsClientImpl.class.getName());
  private static final ExponentialBackoffPolicy.Provider BACKOFF_POLICY_PROVIDER =
      new ExponentialBackoffPolicy.Provider();

  private final Bootstrapper bootstrapper;
  private final Object lock = new Object();
  private final AtomicReference<Map<String, ?>> bootstrapOverride = new AtomicReference<>();
  private final Map<String, ObjectPool<XdsClient>> targetToXdsClientMap = new ConcurrentHashMap<>();

  SharedXdsClientPoolProvider() {
    this(new GrpcBootstrapperImpl());
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
  public ObjectPool<XdsClient> get(String target) {
    return targetToXdsClientMap.get(target);
  }

  @Override
  public ObjectPool<XdsClient> getOrCreate(String target, MetricRecorder metricRecorder)
      throws XdsInitializationException {
    return getOrCreate(target, metricRecorder, null);
  }

  public ObjectPool<XdsClient> getOrCreate(
      String target, MetricRecorder metricRecorder, CallCredentials transportCallCredentials)
      throws XdsInitializationException {
    ObjectPool<XdsClient> ref = targetToXdsClientMap.get(target);
    if (ref == null) {
      synchronized (lock) {
        ref = targetToXdsClientMap.get(target);
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
          ref =
              new RefCountedXdsClientObjectPool(
                  bootstrapInfo, target, metricRecorder, transportCallCredentials);
          targetToXdsClientMap.put(target, ref);
        }
      }
    }
    return ref;
  }

  @Override
  public ImmutableList<String> getTargets() {
    return ImmutableList.copyOf(targetToXdsClientMap.keySet());
  }

  private static class SharedXdsClientPoolProviderHolder {
    private static final SharedXdsClientPoolProvider instance = new SharedXdsClientPoolProvider();
  }

  @ThreadSafe
  @VisibleForTesting
  class RefCountedXdsClientObjectPool implements ObjectPool<XdsClient> {

    private final BootstrapInfo bootstrapInfo;
    private final String target; // The target associated with the xDS client.
    private final MetricRecorder metricRecorder;
    private final CallCredentials transportCallCredentials;
    private final Object lock = new Object();
    @GuardedBy("lock")
    private ScheduledExecutorService scheduler;
    @GuardedBy("lock")
    private XdsClient xdsClient;
    @GuardedBy("lock")
    private int refCount;
    @GuardedBy("lock")
    private XdsClientMetricReporterImpl metricReporter;

    @VisibleForTesting
    RefCountedXdsClientObjectPool(
        BootstrapInfo bootstrapInfo, String target, MetricRecorder metricRecorder) {
      this(bootstrapInfo, target, metricRecorder, null);
    }

    @VisibleForTesting
    RefCountedXdsClientObjectPool(
        BootstrapInfo bootstrapInfo,
        String target,
        MetricRecorder metricRecorder,
        CallCredentials transportCallCredentials) {
      this.bootstrapInfo = checkNotNull(bootstrapInfo);
      this.target = target;
      this.metricRecorder = metricRecorder;
      this.transportCallCredentials = transportCallCredentials;
    }

    @Override
    public XdsClient getObject() {
      synchronized (lock) {
        if (refCount == 0) {
          if (LOG_XDS_NODE_ID) {
            log.log(Level.INFO, "xDS node ID: {0}", bootstrapInfo.node().getId());
          }
          scheduler = SharedResourceHolder.get(GrpcUtil.TIMER_SERVICE);
          metricReporter = new XdsClientMetricReporterImpl(metricRecorder, target);
          GrpcXdsTransportFactory xdsTransportFactory =
              new GrpcXdsTransportFactory(transportCallCredentials);
          xdsClient =
              new XdsClientImpl(
                  xdsTransportFactory,
                  bootstrapInfo,
                  scheduler,
                  BACKOFF_POLICY_PROVIDER,
                  GrpcUtil.STOPWATCH_SUPPLIER,
                  TimeProvider.SYSTEM_TIME_PROVIDER,
                  MessagePrinter.INSTANCE,
                  new TlsContextManagerImpl(bootstrapInfo),
                  metricReporter);
          metricReporter.setXdsClient(xdsClient);
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
          metricReporter.close();
          metricReporter = null;
          targetToXdsClientMap.remove(target);
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

    public String getTarget() {
      return target;
    }
  }

}
