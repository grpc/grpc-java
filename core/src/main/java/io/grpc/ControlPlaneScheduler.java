/*
 * Copyright 2018 The gRPC Authors
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

import java.util.concurrent.TimeUnit;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Defines operation scheduling services for control plane components.  Assuming all control-plane
 * operations are serialized by some mechanism (e.g., the "Channel Executor" mentioned in {@link
 * LoadBalancer} API, this scheduler guarantees that all runnables are run in the synchronization
 * context of a certain control plane component.
 */
@ThreadSafe
@ExperimentalApi("TODO")
public abstract class ControlPlaneScheduler {
  /**
   * Schedules a task to run as soon as poassible.
   *
   * <p>Non-reentrency is guaranteed.  Although task may run inline, but if this method is called
   * from within an operation in the synchronization context, the task will be queued and run after
   * the current operation has finished, rather than running inline.
   *
   * <p>Ordering is guaranteed.  Tasks are guaranteed to run in the same order as they are
   * submitted.
   */
  public abstract ScheduledContext scheduleNow(Runnable task);

  /**
   * Schedules a task to be run after a delay.  Unlike {@link #scheduleNow}, the task will typically
   * run from a different thread.
   */
  public abstract ScheduledContext schedule(Runnable task, long delay, TimeUnit unit);

  /**
   * Returns the current time in nanos from the same clock that {@link #schedule} uses.
   */
  public abstract long currentTimeNanos();

  public abstract static class ScheduledContext {
    /**
     * Cancel the task if it's not run yet.
     * 
     * <p>Must be called in the same synchronization context as the tasks are run. Will guarantee
     * that the task will not run if it has not started running.
     */
    public abstract void cancel();

    /**
     * Returns true if the task will eventually run, meaning that it has neither started running nor
     * been cancelled.
     */
    public abstract boolean isPending();
  }
}
