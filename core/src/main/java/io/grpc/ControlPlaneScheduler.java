package io.grpc;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Defines operation scheduling services for control plane components. All control-plane operations
 * are serialized by some mechanism, thus this scheduler must guarantee that all runnables are run
 * in the synchronization context of a certain control plane component.
 */
@ThreadSafe
@ExperimentalApi("TODO")
public abstract class ControlPlaneScheduler {
  /**
   * Short-cut for {@code schedule(task, 0, TimeUnit.NANOSECONDS)}.
   */
  public final ScheduledContext scheduleNow(Runnable task) {
    return schedule(task, 0, TimeUnit.NANOSECONDS);
  }

  /**
   * Schedules a task to be run after a delay.
   *
   * <p>Non-reentrency is guaranteed, meaning that if this method is called within a control-plane
   * operation, the task will be queued and run after this operation has finished, rather than
   * immediately.
   */
  public abstract ScheduledContext schedule(Runnable task, long delay, TimeUnit unit);

  /**
   * Returns the current time in nanos from the same clock that {@link #schedule} uses.
   */
  public abstract long currentTimeNanos();

  public static abstract class ScheduledContext {
    /**
     * Cancel the task if it's not run yet.
     * 
     * <p>Must be called in the same synchronization context as the tasks. Will guarantee that
     * the task will not run if it has not run.
     */
    public abstract void cancel();

    /**
     * Returns true if the task has neither be run (task is considered run as soon as it's started
     * to run, not necessarily finished) nor cancelled.
     */
    public abstract boolean isPending();
  }
}
