/*
 * Copyright 2021 The gRPC Authors
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
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.xds.EnvoyServerProtoData.FilterChain;
import io.grpc.xds.FilterChainMatchingProtocolNegotiators.FilterChainMatchingHandler.FilterChainSelector;
import io.grpc.xds.XdsClient.LdsResourceWatcher;
import io.grpc.xds.XdsClient.LdsUpdate;
import io.grpc.xds.XdsNameResolverProvider.XdsClientPoolFactory;
import io.grpc.xds.XdsServerBuilder.XdsServingStatusListener;
import io.grpc.xds.internal.sds.SslContextProviderSupplier;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

final class XdsServerWrapper extends Server {
  private static final Logger logger = Logger.getLogger(XdsServerWrapper.class.getName());

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          logger.log(Level.SEVERE, "Exception!" + e);
          // TODO(chengyuanzhang): implement cleanup.
        }
      });

  @VisibleForTesting
  static final long RETRY_DELAY_NANOS = TimeUnit.MINUTES.toNanos(1);
  private final String listenerAddress;
  private final ServerBuilder<?> delegateBuilder;
  private boolean sharedTimeService;
  private final ScheduledExecutorService timeService;
  private final XdsClientPoolFactory xdsClientPoolFactory;
  private final XdsServingStatusListener listener;
  private final AtomicReference<FilterChainSelector> filterChainSelectorRef;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean shutdown = new AtomicBoolean(false);
  private boolean isServing;
  private final CountDownLatch internalTerminationLatch = new CountDownLatch(1);
  private final SettableFuture<Exception> initialStartFuture = SettableFuture.create();
  private boolean initialStarted;
  private ScheduledHandle restartTimer;
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private DiscoveryState discoveryState;
  private volatile Server delegate;

  XdsServerWrapper(
      String listenerAddress,
      ServerBuilder<?> delegateBuilder,
      XdsServingStatusListener listener,
      AtomicReference<FilterChainSelector> filterChainSelectorRef,
      XdsClientPoolFactory xdsClientPoolFactory) {
    this(listenerAddress, delegateBuilder, listener, filterChainSelectorRef, xdsClientPoolFactory,
            SharedResourceHolder.get(GrpcUtil.TIMER_SERVICE));
    sharedTimeService = true;
  }

  @VisibleForTesting
  XdsServerWrapper(
          String listenerAddress,
          ServerBuilder<?> delegateBuilder,
          XdsServingStatusListener listener,
          AtomicReference<FilterChainSelector> filterChainSelectorRef,
          XdsClientPoolFactory xdsClientPoolFactory,
          ScheduledExecutorService timeService) {
    this.listenerAddress = checkNotNull(listenerAddress, "listenerAddress");
    this.delegateBuilder = checkNotNull(delegateBuilder, "delegateBuilder");
    this.listener = checkNotNull(listener, "listener");
    this.filterChainSelectorRef = checkNotNull(filterChainSelectorRef, "filterChainSelectorRef");
    this.xdsClientPoolFactory = checkNotNull(xdsClientPoolFactory, "xdsClientPoolFactory");
    this.timeService = checkNotNull(timeService, "timeService");
    this.delegate = delegateBuilder.build();
  }

  @Override
  public Server start() throws IOException {
    checkState(started.compareAndSet(false, true), "Already started");
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        internalStart();
      }
    });
    Exception exception;
    try {
      exception = initialStartFuture.get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
    if (exception != null) {
      throw (exception instanceof IOException) ? (IOException) exception :
              new IOException(exception);
    }
    return this;
  }

  private void internalStart() {
    try {
      xdsClientPool = xdsClientPoolFactory.getOrCreate();
    } catch (Exception e) {
      StatusException statusException = Status.UNAVAILABLE.withDescription(
              "Failed to initialize xDS").withCause(e).asException();
      listener.onNotServing(statusException);
      initialStartFuture.set(statusException);
      return;
    }
    xdsClient = xdsClientPool.getObject();
    // TODO(chengyuanzhang): add an API on XdsClient indicating if it is using v3, don't get
    //  from bootstrap.
    boolean useProtocolV3 = xdsClient.getBootstrapInfo().getServers().get(0).isUseProtocolV3();
    String listenerTemplate = xdsClient.getBootstrapInfo().getServerListenerResourceNameTemplate();
    if (!useProtocolV3 || listenerTemplate == null) {
      StatusException statusException =
          Status.UNAVAILABLE.withDescription(
              "Can only support xDS v3 with listener resource name template").asException();
      listener.onNotServing(statusException);
      initialStartFuture.set(statusException);
      xdsClient = xdsClientPool.returnObject(xdsClient);
      return;
    }
    discoveryState = new DiscoveryState(listenerTemplate.replaceAll("%s", listenerAddress));
  }

  @Override
  public Server shutdown() {
    if (!shutdown.compareAndSet(false, true)) {
      return this;
    }
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        if (!delegate.isShutdown()) {
          delegate.shutdown();
        }
        internalShutdown();
      }
    });
    return this;
  }

  @Override
  public Server shutdownNow() {
    if (!shutdown.compareAndSet(false, true)) {
      return this;
    }
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        if (!delegate.isShutdown()) {
          delegate.shutdownNow();
        }
        internalShutdown();
      }
    });
    return this;
  }

  // Must run in SynchronizationContext
  private void internalShutdown() {
    logger.log(Level.FINER, "Shutting down XdsServerWrapper");
    if (discoveryState != null) {
      discoveryState.shutdown();
    }
    if (xdsClient != null) {
      xdsClient = xdsClientPool.returnObject(xdsClient);
    }
    if (restartTimer != null) {
      restartTimer.cancel();
    }
    if (sharedTimeService) {
      SharedResourceHolder.release(GrpcUtil.TIMER_SERVICE, timeService);
    }
    isServing = false;
    internalTerminationLatch.countDown();
  }

  @Override
  public boolean isShutdown() {
    return shutdown.get();
  }

  @Override
  public boolean isTerminated() {
    return internalTerminationLatch.getCount() == 0 && delegate.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    long startTime = System.nanoTime();
    if (!internalTerminationLatch.await(timeout, unit)) {
      return false;
    }
    long remainingTime = unit.toNanos(timeout) - (System.nanoTime() - startTime);
    return delegate.awaitTermination(remainingTime, TimeUnit.NANOSECONDS);
  }

  @Override
  public void awaitTermination() throws InterruptedException {
    internalTerminationLatch.await();
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

  // Must run in SynchronizationContext
  private void startDelegateServer() {
    if (restartTimer != null && restartTimer.isPending()) {
      return;
    }
    if (isServing) {
      return;
    }
    if (delegate.isShutdown()) {
      delegate = delegateBuilder.build();
    }
    try {
      delegate.start();
      listener.onServing();
      isServing = true;
      if (!initialStarted) {
        initialStarted = true;
        initialStartFuture.set(null);
      }
    } catch (IOException e) {
      logger.log(Level.FINE, "Fail to start delegate server: {0}", e);
      if (!initialStarted) {
        initialStarted = true;
        initialStartFuture.set(e);
      }
      restartTimer = syncContext.schedule(
        new RestartTask(), RETRY_DELAY_NANOS, TimeUnit.NANOSECONDS, timeService);
    }
  }

  private final class RestartTask implements Runnable {
    @Override
    public void run() {
      startDelegateServer();
    }
  }

  private final class DiscoveryState implements LdsResourceWatcher {
    private final String resourceName;
    // Most recently discovered filter chains.
    private List<FilterChain> filterChains = new ArrayList<>();
    // Most recently discovered default filter chain.
    @Nullable
    private FilterChain defaultFilterChain;
    private boolean stopped;

    private DiscoveryState(String resourceName) {
      this.resourceName = checkNotNull(resourceName, "resourceName");
      xdsClient.watchLdsResource(resourceName, this);
    }

    @Override
    public void onChanged(final LdsUpdate update) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped) {
            return;
          }
          checkNotNull(update.listener(), "update");
          filterChains = update.listener().getFilterChains();
          defaultFilterChain = update.listener().getDefaultFilterChain();
          updateSelector();
        }
      });
    }

    @Override
    public void onResourceDoesNotExist(final String resourceName) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped) {
            return;
          }
          StatusException statusException = Status.UNAVAILABLE.withDescription(
                  "Listener " + resourceName + " unavailable").asException();
          handleConfigNotFound(statusException);
        }
      });
    }

    @Override
    public void onError(final Status error) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped) {
            return;
          }
          boolean isPermanentError = isPermanentError(error);
          logger.log(Level.FINE, "{0} error from XdsClient: {1}",
                  new Object[]{isPermanentError ? "Permanent" : "Transient", error});
          if (isPermanentError) {
            handleConfigNotFound(error.asException());
          } else if (!isServing) {
            listener.onNotServing(error.asException());
          }
        }
      });
    }

    private void shutdown() {
      stopped = true;
      logger.log(Level.FINE, "Stop watching LDS resource {0}", resourceName);
      xdsClient.cancelLdsResourceWatch(resourceName, this);
      List<SslContextProviderSupplier> toRelease = collectSslContextProviderSuppliers();
      filterChainSelectorRef.set(FilterChainSelector.NO_FILTER_CHAIN);
      for (SslContextProviderSupplier s: toRelease) {
        s.close();
      }
    }

    private List<SslContextProviderSupplier> collectSslContextProviderSuppliers() {
      List<SslContextProviderSupplier> toRelease = new ArrayList<>();
      FilterChainSelector selector = filterChainSelectorRef.get();
      if (selector != null) {
        for (FilterChain f: selector.getFilterChains()) {
          if (f.getSslContextProviderSupplier() != null) {
            toRelease.add(f.getSslContextProviderSupplier());
          }
        }
        SslContextProviderSupplier defaultSupplier =
                selector.getDefaultSslContextProviderSupplier();
        if (defaultSupplier != null) {
          toRelease.add(defaultSupplier);
        }
      }
      return toRelease;
    }

    private void updateSelector() {
      List<SslContextProviderSupplier> toRelease = collectSslContextProviderSuppliers();
      FilterChainSelector selector = new FilterChainSelector(
          Collections.unmodifiableList(filterChains),
          defaultFilterChain == null ? null : defaultFilterChain.getSslContextProviderSupplier());
      filterChainSelectorRef.set(selector);
      for (SslContextProviderSupplier s: toRelease) {
        s.close();
      }
      startDelegateServer();
    }

    private void handleConfigNotFound(StatusException exception) {
      List<SslContextProviderSupplier> toRelease = collectSslContextProviderSuppliers();
      filterChainSelectorRef.set(FilterChainSelector.NO_FILTER_CHAIN);
      for (SslContextProviderSupplier s: toRelease) {
        s.close();
      }
      if (!initialStarted) {
        initialStarted = true;
        initialStartFuture.set(exception);
      }
      if (restartTimer != null) {
        restartTimer.cancel();
      }
      if (!delegate.isShutdown()) {
        delegate.shutdown();  // let in-progress calls finish
      }
      isServing = false;
      listener.onNotServing(exception);
    }
  }

  private static boolean isPermanentError(Status error) {
    return EnumSet.of(
            Status.Code.INTERNAL,
            Status.Code.INVALID_ARGUMENT,
            Status.Code.FAILED_PRECONDITION,
            Status.Code.PERMISSION_DENIED,
            Status.Code.UNAUTHENTICATED)
            .contains(error.getCode());
  }
}
