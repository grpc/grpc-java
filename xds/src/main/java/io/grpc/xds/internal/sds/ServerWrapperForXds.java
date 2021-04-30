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
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.xds.XdsClientWrapperForServerSds;
import io.grpc.xds.XdsInitializationException;
import io.grpc.xds.XdsServerBuilder;
import java.io.IOException;
import java.net.BindException;
import java.net.SocketAddress;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Wraps a {@link Server} delegate and {@link XdsClientWrapperForServerSds} and intercepts {@link
 * Server#shutdown()} and {@link Server#start()} to shut down and start the
 * {@link XdsClientWrapperForServerSds} object.
 */
@VisibleForTesting
public final class ServerWrapperForXds extends Server {
  private Server delegate;
  private final ServerBuilder<?> delegateBuilder;
  private final XdsClientWrapperForServerSds xdsClientWrapperForServerSds;
  private XdsServerBuilder.XdsServingStatusListener xdsServingStatusListener;
  @Nullable XdsClientWrapperForServerSds.ServerWatcher serverWatcher;
  private AtomicBoolean started = new AtomicBoolean();
  private volatile ServingState currentServingState;
  private final long delayForRetry;
  private final TimeUnit timeUnitForDelayForRetry;
  private StartRetryTask startRetryTask;

  @VisibleForTesting public enum ServingState {
    // during start() i.e. first start
    STARTING,

    // after start (1st or subsequent ones)
    STARTED,

    // not serving due to listener deletion
    NOT_SERVING,

    // enter serving mode after NOT_SERVING
    ENTER_SERVING,

    // shut down - could be due to failure
    SHUTDOWN
  }

  /** Creates the wrapper object using the delegate passed. */
  public ServerWrapperForXds(
      ServerBuilder<?> delegateBuilder,
      XdsClientWrapperForServerSds xdsClientWrapperForServerSds,
      XdsServerBuilder.XdsServingStatusListener xdsServingStatusListener) {
    this(
        delegateBuilder,
        xdsClientWrapperForServerSds,
        xdsServingStatusListener,
        1L,
        TimeUnit.MINUTES);
  }

  /** Creates the wrapper object using params passed - used for tests. */
  @VisibleForTesting
  public ServerWrapperForXds(ServerBuilder<?> delegateBuilder,
      XdsClientWrapperForServerSds xdsClientWrapperForServerSds,
      XdsServerBuilder.XdsServingStatusListener xdsServingStatusListener,
      long delayForRetry, TimeUnit timeUnitForDelayForRetry) {
    this.delegateBuilder = checkNotNull(delegateBuilder, "delegateBuilder");
    this.delegate = delegateBuilder.build();
    this.xdsClientWrapperForServerSds =
        checkNotNull(xdsClientWrapperForServerSds, "xdsClientWrapperForServerSds");
    this.xdsServingStatusListener =
        checkNotNull(xdsServingStatusListener, "xdsServingStatusListener");
    this.delayForRetry = delayForRetry;
    this.timeUnitForDelayForRetry =
        checkNotNull(timeUnitForDelayForRetry, "timeUnitForDelayForRetry");
  }

  @Override
  public Server start() throws IOException {
    checkState(started.compareAndSet(false, true), "Already started");
    currentServingState = ServingState.STARTING;
    SettableFuture<Throwable> future = addServerWatcher();
    xdsClientWrapperForServerSds.start();
    try {
      Throwable throwable = future.get();
      if (throwable != null) {
        removeServerWatcher();
        if (throwable instanceof IOException) {
          throw (IOException) throwable;
        }
        throw new IOException(throwable);
      }
    } catch (InterruptedException | ExecutionException ex) {
      removeServerWatcher();
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new RuntimeException(ex);
    }
    return this;
  }

  @VisibleForTesting public ServingState getCurrentServingState() {
    return currentServingState;
  }

  private SettableFuture<Throwable> addServerWatcher() {
    final SettableFuture<Throwable> future = SettableFuture.create();
    serverWatcher =
        new XdsClientWrapperForServerSds.ServerWatcher() {
          @Override
          public void onError(Throwable throwable, boolean isAbsent) {
            synchronized (ServerWrapperForXds.this) {
              if (currentServingState == ServingState.SHUTDOWN) {
                return;
              } else if (currentServingState == ServingState.STARTING) {
                // during start
                if (isPermanentErrorFromXds(throwable)) {
                  currentServingState = ServingState.SHUTDOWN;
                  future.set(throwable);
                  return;
                }
                xdsServingStatusListener.onNotServing(throwable);
              } else {
                // is one of STARTED, NOT_SERVING or ENTER_SERVING
                if (isAbsent) {
                  xdsServingStatusListener.onNotServing(throwable);
                  if (currentServingState == ServingState.STARTED) {
                    // shutdown the server
                    delegate.shutdown(); // let existing calls finish on delegate
                    currentServingState = ServingState.NOT_SERVING;
                  }
                }
              }
            }
          }

          @Override
          public void onListenerUpdate() {
            synchronized (ServerWrapperForXds.this) {
              if (currentServingState == ServingState.SHUTDOWN) {
                return;
              } else if (currentServingState == ServingState.STARTING) {
                // during start()
                try {
                  delegate.start();
                  currentServingState = ServingState.STARTED;
                  xdsServingStatusListener.onServing();
                  future.set(null);
                } catch (IOException ioe) {
                  future.set(ioe);
                }
              } else if (currentServingState == ServingState.NOT_SERVING) {
                // coming out of NOT_SERVING
                currentServingState = ServingState.ENTER_SERVING;
                startRetryTask = new StartRetryTask();
                startRetryTask.createTask(0L);
              }
            }
          }
        };
    xdsClientWrapperForServerSds.addServerWatcher(serverWatcher);
    return future;
  }

  private final class StartRetryTask implements Runnable {

    ScheduledExecutorService timerService;
    AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();

    private void createTask(long delay) {
      if (timerService == null) {
        timerService = SharedResourceHolder.get(GrpcUtil.TIMER_SERVICE);
      }
      future.set(timerService.schedule(this, delay, timeUnitForDelayForRetry));
    }

    private void rebuildAndRestartServer() {
      delegate = delegateBuilder.build();
      try {
        delegate = delegate.start();
        currentServingState = ServingState.STARTED;
        xdsServingStatusListener.onServing();
        cleanUpStartRetryTask();
      } catch (IOException ioe) {
        xdsServingStatusListener.onNotServing(ioe);
        if (isRetriableErrorInDelegateStart(ioe)) {
          createTask(delayForRetry);
        } else {
          // permanent failure
          currentServingState = ServingState.SHUTDOWN;
          cleanUpStartRetryTask();
        }
      }
    }

    @Override
    public void run() {
      if (currentServingState == ServingState.SHUTDOWN) {
        return;
      } else if (currentServingState != ServingState.ENTER_SERVING) {
        throw new AssertionError("Wrong state:" + currentServingState);
      }
      rebuildAndRestartServer();
    }

    private void cleanUpStartRetryTask() {
      synchronized (ServerWrapperForXds.this) {
        if (timerService != null) {
          timerService = SharedResourceHolder.release(GrpcUtil.TIMER_SERVICE, timerService);
        }
        startRetryTask = null;
      }
    }

    public void shutdownNow() {
      ScheduledFuture<?> oldValue = future.getAndSet(null);
      if (oldValue != null) {
        oldValue.cancel(true);
      }
      cleanUpStartRetryTask();
    }
  }

  private void removeServerWatcher() {
    synchronized (xdsClientWrapperForServerSds) {
      if (serverWatcher != null) {
        xdsClientWrapperForServerSds.removeServerWatcher(serverWatcher);
        serverWatcher = null;
      }
    }
  }

  // if the IOException indicates we can rebuild delegate and retry start...
  private static boolean isRetriableErrorInDelegateStart(IOException ioe) {
    if (ioe instanceof BindException) {
      return true;
    }
    Throwable cause = ioe.getCause();
    return cause instanceof BindException;
  }

  // if the Throwable indicates a permanent error in xDS processing
  private static boolean isPermanentErrorFromXds(Throwable throwable) {
    if (throwable instanceof XdsInitializationException) {
      return true;
    }
    Status.Code code = Status.fromThrowable(throwable).getCode();
    return EnumSet.of(
        Status.Code.INTERNAL,
        Status.Code.INVALID_ARGUMENT,
        Status.Code.FAILED_PRECONDITION,
        Status.Code.PERMISSION_DENIED,
        Status.Code.UNAUTHENTICATED)
        .contains(code);
  }

  private void cleanupStartRetryTaskAndShutdownDelegateAndXdsClient(boolean shutdownNow) {
    Server delegateCopy = null;
    synchronized (ServerWrapperForXds.this) {
      if (startRetryTask != null) {
        startRetryTask.shutdownNow();
      }
      currentServingState = ServingState.SHUTDOWN;
      if (delegate != null && !delegate.isShutdown()) {
        delegateCopy = delegate;
      }
    }
    if (delegateCopy != null) {
      if (shutdownNow) {
        delegateCopy.shutdownNow();
      } else {
        delegateCopy.shutdown();
      }
    }
    xdsClientWrapperForServerSds.shutdown();
  }

  @Override
  public Server shutdown() {
    cleanupStartRetryTaskAndShutdownDelegateAndXdsClient(false);
    return this;
  }

  @Override
  public Server shutdownNow() {
    cleanupStartRetryTaskAndShutdownDelegateAndXdsClient(true);
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
