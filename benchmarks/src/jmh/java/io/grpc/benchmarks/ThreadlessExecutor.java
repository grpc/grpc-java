/*
 * Copyright 2018, gRPC Authors All rights reserved.
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

package io.grpc.benchmarks;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;

final class ThreadlessExecutor implements Executor {
  private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();

  /**
   * Waits until there is a Runnable, then executes it and all queued Runnables after it.
   */
  public void waitAndDrain() throws InterruptedException {
    Runnable runnable = queue.take();
    while (runnable != null) {
      try {
        runnable.run();
      } catch (Throwable t) {
        throw new RuntimeException("Runnable threw exception", t);
      }
      runnable = queue.poll();
    }
  }

  @Override
  public void execute(Runnable runnable) {
    queue.add(runnable);
  }
}
