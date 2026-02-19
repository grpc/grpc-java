/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds.internal.extauthz;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A buffer for client calls that are pending an authorization decision.
 */
@ThreadSafe
final class CallBuffer {

  private final AtomicBoolean processed = new AtomicBoolean(false);
  private final List<Runnable> bufferedCalls = new ArrayList<>();
  private final Object lock = new Object();

  /**
   * Buffers a runnable to be executed later. If the buffer has already been processed, the
   * runnable is executed immediately.
   *
   * @param runnable the runnable to buffer.
   */
  public void runOrBuffer(Runnable runnable) {
    synchronized (lock) {
      if (processed.get()) {
        runnable.run();
      } else {
        bufferedCalls.add(runnable);
      }
    }
  }

  /**
   * Executes all buffered runnables and marks the buffer as processed.
   */
  public void runAndFlush() {
    List<Runnable> toRun;
    synchronized (lock) {
      if (processed.getAndSet(true)) {
        return;
      }
      toRun = new ArrayList<>(bufferedCalls);
      bufferedCalls.clear();
    }
    for (Runnable runnable : toRun) {
      runnable.run();
    }
  }

  /**
   * Abandons all buffered runnables and marks the buffer as processed.
   */
  public void abandon() {
    synchronized (lock) {
      if (processed.getAndSet(true)) {
        return;
      }
      bufferedCalls.clear();
    }
  }

  /**
   * Returns whether the buffer has been processed.
   *
   * @return true if the buffer has been processed, false otherwise.
   */
  public boolean isProcessed() {
    return processed.get();
  }
}
