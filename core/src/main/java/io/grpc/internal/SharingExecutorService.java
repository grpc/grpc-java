package io.grpc.internal;

import static com.google.common.base.Preconditions.checkState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Wraps an existing executor, that keeps track of only the tasks it has added.
 */
public final class SharingExecutorService extends AbstractExecutorService {
  private final Set<CleanupTask> activeTasks = new HashSet<CleanupTask>();

  private final Executor delegate;
  private boolean shutdown;

  public SharingExecutorService(Executor delegate) {
    this.delegate = delegate;
  }

  @Override
  public void shutdown() {
    synchronized (activeTasks) {
      shutdown = true;
      if (activeTasks.isEmpty()) {
        activeTasks.notifyAll();
      }
    }
  }

  @Override
  public synchronized List<Runnable> shutdownNow() {
    synchronized (activeTasks) {
      shutdown = true;
      List<Runnable> neverRan = new ArrayList<Runnable>(activeTasks.size());
      Iterator<CleanupTask> tasks = activeTasks.iterator();
      while (tasks.hasNext()) {
        CleanupTask task = tasks.next();
        // isDone() returns true before the done() method on CleanupTask is called, so we can still
        // remove it.
        if (task.isDone()) {
          tasks.remove();
          continue;
        }
        // Since the underlying executor may have started running this, this is best effort.
        task.cancel(true);
        if (!task.started) {
          // This will not be the same runnable submitted by the user.
          neverRan.add(task);
        }
      }
      if (activeTasks.isEmpty()) {
        notifyAll();
      }

      return neverRan;
    }
  }

  @Override
  public boolean isShutdown() {
    synchronized (activeTasks) {
      return shutdown;
    }
  }

  @Override
  public boolean isTerminated() {
    synchronized (activeTasks) {
      return shutdown && activeTasks.isEmpty();
    }
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    long remainingNanos = unit.toNanos(timeout);
    long deadline = System.nanoTime() + remainingNanos;
    synchronized (activeTasks) {
      while (!isTerminated() && remainingNanos > 0) {
        TimeUnit.NANOSECONDS.timedWait(activeTasks, remainingNanos);
        remainingNanos = deadline - System.nanoTime();
      }
    }

    return isTerminated();
  }

  @Override
  public void execute(final Runnable command) {
    CleanupTask task = new CleanupTask(command);
    synchronized (activeTasks) {
      checkState(!shutdown, "Executor shutting down");
      activeTasks.add(task);
    }

    try {
      delegate.execute(task);
    } catch (RuntimeException e) {
      synchronized (activeTasks) {
        activeTasks.remove(task);
      }
      throw e;
    }
  }

  private final class CleanupTask extends FutureTask<Void> {
    private volatile boolean started;

    CleanupTask(Runnable runnable) {
      super(runnable, null);
    }

    @Override
    public void run() {
      started = true;
      super.run();
    }

    @Override
    protected void done() {
      synchronized (activeTasks) {
        activeTasks.remove(this);
        if (isTerminated()) {
          activeTasks.notifyAll();
        }
      }
    }
  }
}

