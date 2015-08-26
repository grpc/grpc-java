package io.grpc.internal;

import static com.google.common.base.Preconditions.checkState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Wraps an existing executor, that keeps track of only the tasks it has added.
 */
public final class SharingExecutorService extends AbstractExecutorService {
  private final Map<CleanupTask, Future<?>> activeTasks = new HashMap<CleanupTask, Future<?>>();

  private final ExecutorService delegate;
  private boolean shutdown;

  public SharingExecutorService(ExecutorService delegate) {
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
    Iterator<Entry<CleanupTask, Future<?>>> entries = activeTasks.entrySet().iterator();
    while (entries.hasNext()) {
      Entry<CleanupTask, Future<?>> entry = entries.next();
      // isDone() returns true before the done() method on CleanupTask is called, so we can still
      // remove it.
      if (entry.getValue().isDone()) {
        entries.remove();
        continue;
      }
      // Since the underlying executor may have started running this, this is best effort.
      boolean cancelled = entry.getValue().cancel(true);
      if (cancelled) {
        // This will not be the same runnable submitted by the user.
        neverRan.add(entry.getKey());
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
    Future<?> f = delegate.submit(cleanupTask);

    // If f is already completed (because of a direct executor, or a Caller runs overflow policy,
    // don't add it to the list of tasks.  If not run on this thread, the instrinsic lock prevents
    // the future from being completed.
    if (!f.isDone()) {
      activeTasks.put(cleanupTask, f);
    }
  }

  private final class CleanupTask extends FutureTask<Void> {
    CleanupTask(Runnable runnable) {
      super(runnable, null);
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

