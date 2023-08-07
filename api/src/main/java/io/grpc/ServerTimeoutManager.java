/*
 * Copyright 2014 The gRPC Authors
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

import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** A global instance that schedules the timeout tasks. */
public class ServerTimeoutManager {
  private final int timeout;
  private final TimeUnit unit;

  private final Consumer<String> logFunction;

  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  /**
   * Creates a manager. Please make it a singleton and remember to shut it down.
   *
   * @param timeout Configurable timeout threshold. A value less than 0 (e.g. 0 or -1) means not to
   *     check timeout.
   * @param unit The unit of the timeout.
   * @param logFunction An optional function that can log (e.g. Logger::warn). Through this,
   *     we avoid depending on a specific logger library.
   */
  public ServerTimeoutManager(int timeout, TimeUnit unit, Consumer<String> logFunction) {
    this.timeout = timeout;
    this.unit = unit;
    this.logFunction = logFunction;
  }

  /** Please call shutdown() when the application exits. */
  public void shutdown() {
    scheduler.shutdownNow();
  }

  /**
   * Schedules a timeout and calls the RPC method invocation.
   * Invalidates the timeout if the invocation completes in time.
   *
   * @param invocation The RPC method invocation that processes a request.
   * @return true if a timeout is scheduled
   */
  public boolean withTimeout(Runnable invocation) {
    if (timeout <= 0) {
      invocation.run();
      return false;
    }

    Future<?> timeoutFuture = schedule(Thread.currentThread());
    try {
      invocation.run();
      return true;
    } finally {
      // If it completes in time, cancel the timeout.
      if (timeoutFuture != null) {
        timeoutFuture.cancel(false);
      }
    }
  }

  private Future<?> schedule(Thread thread) {
    TimeoutTask timeoutTask = new TimeoutTask(thread);
    if (scheduler.isShutdown()) {
      return null;
    }
    return scheduler.schedule(timeoutTask, timeout, unit);
  }

  private class TimeoutTask implements Runnable {
    private final AtomicReference<Thread> threadReference = new AtomicReference<>();

    private TimeoutTask(Thread thread) {
      threadReference.set(thread);
    }

    @Override
    public void run() {
      // Ensure the reference is consumed only once.
      Thread thread = threadReference.getAndSet(null);
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
  }
}
