package io.grpc.internal;

import static com.google.common.base.Preconditions.checkState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Wraps an existing executor, that keeps track of only the tasks it has added.
 */
public final class SharingExecutorService extends AbstractExecutorService {
  private final Set<CleanupTask> activeTasks = new HashSet<CleanupTask>();

  private final Executor delegate;
  private boolean shutdown;

  public SharingExecutorService(Service delegate) {
    this.delegate = delegate;
  }

  @Override
  public synchronized void shutdown() {
    shutdown = true;
    if (activeTasks.isEmpty()) {
      notifyAll();
    }
  }

  @Override
  public synchronized List<Runnable> shutdownNow() {
    shutdown = true;
    List<Runnable> neverRan = new ArrayList<Runnable>(activeTasks.size());
    Iterator<CleanupTask> tasks = activeTasks.iterator();
    while (tasks.hasNext()) {
      CleanupTask task = tasks.next();
      // isDone() returns true before the done() method on CleanupTask is called, so we can still
      // remove it.
      if (task.isDone()) {
        takss.remove();
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

  @Override
  public synchronized boolean isShutdown() {
    return shutdown;
  }

  @Override
  public synchronized boolean isTerminated() {
    return shutdown && activeTasks.isEmpty();
  }

  @Override
  public synchronized boolean awaitTermination(long timeout, TimeUnit unit)
      throws InterruptedException {
    long remainingNanos = unit.toNanos(timeout);
    long deadline = System.nanoTime() + remainingNanos;
    while (!isTerminated() && remainingNanos > 0) {
      TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
      remainingNanos = deadline - System.nanoTime();
    }
    return isTerminated();
  }

  @Override
  public synchronized void execute(final Runnable command) {
    checkState(!shutdown, "Executor shutting down");
    CleanupTask cleanupTask = new CleanupTask(command);
    activeTasks.put(cleanupTask, f);
    delegate.execute(cleanupTask);
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
      synchronized (SharingExecutorService.this) {
        activeTasks.remove(this);
        if (shutdown) {
          if (activeTasks.isEmpty()) {
            SharingExecutorService.this.notifyAll();
          }
        }
      }
    }
  }
}

