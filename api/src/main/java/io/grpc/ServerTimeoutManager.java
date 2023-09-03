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

package io.grpc;

import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A global manager that schedules the timeout tasks for the gRPC server.
 * Please make it a singleton and shut it down when the server is shutdown.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/10361")
public class ServerTimeoutManager {
  /**
   * Creates a builder.
   *
   * @param timeout Configurable timeout threshold. A value less than 0 (e.g. 0 or -1) means not to
   *     check timeout.
   * @param unit The unit of the timeout.
   */
  public static Builder newBuilder(int timeout, TimeUnit unit) {
    return new Builder(timeout, unit);
  }

  private final int timeout;
  private final TimeUnit unit;
  private final boolean shouldInterrupt;
  private final Consumer<String> logFunction;

  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  protected ServerTimeoutManager(int timeout, TimeUnit unit,
                              boolean shouldInterrupt, Consumer<String> logFunction) {
    this.timeout = timeout;
    this.unit = unit;
    this.shouldInterrupt = shouldInterrupt;
    this.logFunction = logFunction;
  }

  /**
   *  Please call shutdown() when the application exits.
   *  You can add a JVM shutdown hook.
   */
  public void shutdown() {
    scheduler.shutdownNow();
  }

  /**
   * Calls the RPC method invocation with a timeout scheduled.
   * Invalidates the timeout if the invocation completes in time.
   *
   * <p>When the timeout is reached: It cancels the context around the RPC method invocation. And
   * if shouldInterrupt is {@code true}, it also interrupts the current worker thread.
   *
   * @param invocation The RPC method invocation that processes a request.
   * @return true if a timeout is scheduled
   */
  public boolean withTimeout(Runnable invocation) {
    if (timeout <= 0 || scheduler.isShutdown()) {
      invocation.run();
      return false;
    }

    try (Context.CancellableContext context = Context.current()
        .withDeadline(Deadline.after(timeout, unit), scheduler)) {
      AtomicReference<Thread> threadRef =
          shouldInterrupt ? new AtomicReference<>(Thread.currentThread()) : null;
      Context.CancellationListener cancelled = c -> {
        if (c.cancellationCause() == null) {
          return;
        }
        System.out.println("RPC cancelled");
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
      context.addListener(cancelled, MoreExecutors.directExecutor());
      context.run(invocation);
      // Invocation is done, should ensure the interruption state is cleared so
      // the worker thread can be safely reused for the next task. Doing this
      // mainly for ForkJoinPool https://bugs.openjdk.org/browse/JDK-8223430.
      if (threadRef != null && threadRef.get() == null) {
        Thread.interrupted();
      }
      return true;
    }
  }

  /** Builder for constructing ServerTimeoutManager instances. */
  public static class Builder {
    private final int timeout;
    private final TimeUnit unit;

    private boolean shouldInterrupt;
    private Consumer<String> logFunction;

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

    /** Construct new ServerTimeoutManager. */
    public ServerTimeoutManager build() {
      return new ServerTimeoutManager(timeout, unit, shouldInterrupt, logFunction);
    }
  }
}
