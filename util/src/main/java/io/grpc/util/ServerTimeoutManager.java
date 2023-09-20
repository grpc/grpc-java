/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.util;

import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.Context;
import io.grpc.ExperimentalApi;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.Status;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * A global manager that schedules the timeout tasks for the gRPC server.
 * Please make it a singleton and shut it down when the server is shutdown.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/10361")
public class ServerTimeoutManager {
  /**
   * Creates a builder.
   *
   * @param timeout Configurable timeout threshold. A non-positive value (e.g. 0 or -1) means not to
   *     check timeout.
   * @param unit The unit of the timeout.
   */
  public static Builder newBuilder(int timeout, TimeUnit unit) {
    return new Builder(timeout, unit);
  }

  private final int timeout;
  private final TimeUnit unit;
  private final boolean shouldInterrupt;
  @Nullable
  private final Consumer<String> logFunction;
  private final ScheduledExecutorService scheduler;

  private ServerTimeoutManager(int timeout, TimeUnit unit,
                               boolean shouldInterrupt,
                               @Nullable Consumer<String> logFunction,
                               @Nullable ScheduledExecutorService scheduler) {
    this.timeout = timeout;
    this.unit = unit;
    this.shouldInterrupt = shouldInterrupt;
    this.logFunction = logFunction;
    this.scheduler = scheduler != null ? scheduler : Executors.newSingleThreadScheduledExecutor();
  }

  /**
   *  Please call shutdown() when the application exits.
   *  You can add a JVM shutdown hook to call it.
   */
  public void shutdown() {
    scheduler.shutdownNow();
  }

  /**
   * Creates a context with the timeout limit.
   * @param serverCall Should pass in a SerializingServerCall that can be closed thread-safely.
   * @return null if not to set a timeout for it
   */
  @Nullable
  public Context.CancellableContext startTimeoutContext(ServerCall<?, ?> serverCall) {
    if (timeout <= 0 || scheduler.isShutdown()) {
      return null;
    }
    Context.CancellationListener callCloser = c -> {
      if (c.cancellationCause() == null) {
        return;
      }
      if (logFunction != null) {
        logFunction.accept("server call timeout for "
            + serverCall.getMethodDescriptor().getFullMethodName());
      }
      serverCall.close(Status.CANCELLED.withDescription("server call timeout"), new Metadata());
    };
    Context.CancellableContext context = Context.current()
        .withDeadlineAfter(timeout, unit, scheduler);
    context.addListener(callCloser, MoreExecutors.directExecutor());
    return context;
  }

  /**
   * Executes the application invocation in the timeout context.
   * Skips execution if context has been cancelled.
   *
   * @param context The timeout context.
   * @param invocation The application invocation that processes a request.
   */
  public void runWithContext(Context.CancellableContext context, Runnable invocation) {
    if (context.isCancelled()) {
      return;
    }
    context.run(invocation);
  }

  /**
   * Executes the application invocation in the timeout context, may interrupt the current thread.
   * Skips execution if context has been cancelled.
   *
   * <p>When the timeout is reached: It cancels the context around the RPC invocation. And
   * if shouldInterrupt is {@code true}, it also interrupts the current worker thread.
   *
   * @param context The timeout context.
   * @param invocation The application invocation that processes a request.
   */
  public void runWithContextInterruptibly(Context.CancellableContext context, Runnable invocation) {
    if (context.isCancelled()) {
      return;
    }
    AtomicReference<Thread> threadRef =
            shouldInterrupt ? new AtomicReference<>(Thread.currentThread()) : null;
    Context.CancellationListener interruption = c -> {
      if (c.cancellationCause() == null) {
        return;
      }
      if (threadRef != null) {
        Thread thread = threadRef.getAndSet(null);
        if (thread != null) {
          thread.interrupt();
          if (logFunction != null) {
            logFunction.accept(
                    "Interrupted RPC thread "
                            + thread.getName()
                            + " for timeout at "
                            + timeout
                            + " "
                            + unit);
          }
        }
      }
    };
    context.addListener(interruption, MoreExecutors.directExecutor());
    try {
      context.run(invocation);
    } finally {
      context.removeListener(interruption);
      // Clear the interruption state if this context previously caused an interruption,
      // allowing the worker thread to be safely reused for the next task in a ForkJoinPool.
      // For more information, refer to https://bugs.openjdk.org/browse/JDK-8223430
      if (threadRef != null && threadRef.get() == null) {
        Thread.interrupted();
      }
    }
  }

  /** Builder for constructing ServerTimeoutManager instances. */
  public static class Builder {
    private final int timeout;
    private final TimeUnit unit;

    private boolean shouldInterrupt;
    private Consumer<String> logFunction;
    private ScheduledExecutorService scheduler;

    private Builder(int timeout, TimeUnit unit) {
      this.timeout = timeout;
      this.unit = unit;
    }

    /**
     * Sets shouldInterrupt. Defaults to {@code false}.
     *
     * @param shouldInterrupt If {@code true}, interrupts the RPC worker thread.
     */
    public Builder setShouldInterrupt(boolean shouldInterrupt) {
      this.shouldInterrupt = shouldInterrupt;
      return this;
    }

    /**
     * Sets the logFunction. Through this, we avoid depending on a specific logger library.
     *
     * @param logFunction An optional function that can make server logs (e.g. Logger::warn).
     */
    public Builder setLogFunction(Consumer<String> logFunction) {
      this.logFunction = logFunction;
      return this;
    }

    /**
     * Sets a custom scheduler instance. If not set, a default scheduler is used.
     *
     * @param scheduler An custom scheduler.
     */
    public Builder setScheduler(ScheduledExecutorService scheduler) {
      this.scheduler = scheduler;
      return this;
    }

    /** Construct new ServerTimeoutManager. */
    public ServerTimeoutManager build() {
      return new ServerTimeoutManager(timeout, unit, shouldInterrupt, logFunction, scheduler);
    }
  }
}
