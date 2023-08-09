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
      Thread thread = Thread.currentThread();
      Context.CancellationListener cancelled = c -> {
        if (c.cancellationCause() == null) {
          return;
        }
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
      };
      context.addListener(cancelled, MoreExecutors.directExecutor());
      context.run(invocation);
      return true;
    }
  }

}
