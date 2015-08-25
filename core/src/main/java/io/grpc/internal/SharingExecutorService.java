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
import java.util.concurrent.TimeUnit;

/**
 * Wraps an existing executor, that keeps track of only the tasks it has added.
 */
public final class SharingExecutorService extends AbstractExecutorService {
  private final Map<CleanupRunnable, Future<?>> activeTasks =
      new HashMap<CleanupRunnable, Future<?>>();

  private final ExecutorService delegate;
  private boolean shutdown;
  private boolean terminated;

  public SharingExecutorService(ExecutorService delegate) {
    this.delegate = delegate;
  }

  @Override
  public synchronized void shutdown() {
    shutdown = true;
    if (activeTasks.isEmpty()) {
      terminated = true;
      notifyAll();
    }
  }

  @Override
  public synchronized List<Runnable> shutdownNow() {
    shutdown();
    List<Runnable> neverRan = new ArrayList<Runnable>(activeTasks.size());
    Iterator<Entry<CleanupRunnable, Future<?>>> entries = activeTasks.entrySet().iterator();
    while (entries.hasNext()) {
      Entry<CleanupRunnable, Future<?>> entry = entries.next();
      if (entry.getValue().isDone()) {
        entries.remove();
        continue;
      }
      // Since the underlying executor may have started running this, this is best effort.
      boolean success = entry.getValue().cancel(true);
      if (success) {
        neverRan.add(entry.getKey().r);
      }
    }

    return neverRan;
  }

  @Override
  public synchronized boolean isShutdown() {
    return shutdown;
  }

  @Override
  public synchronized boolean isTerminated() {
    return terminated;
  }

  @Override
  public synchronized boolean awaitTermination(long timeout, TimeUnit unit)
      throws InterruptedException {
    long remainingNanos = unit.toNanos(timeout);
    long deadline = System.nanoTime() + remainingNanos;
    while (!terminated && remainingNanos > 0) {
      TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
      remainingNanos = deadline - System.nanoTime();
    }
    return terminated;
  }

  @Override
  public synchronized void execute(final Runnable command) {
    checkState(!shutdown, "Executor shutting down");
    CleanupRunnable cleanupRunnable = new CleanupRunnable(command);
    Future<?> f = delegate.submit(cleanupRunnable);

    // If f is already completed (because of a direct executor, or a Caller runs overflow policy,
    // don't add it to the list of tasks.  If not run on this thread, the instrinsic lock prevents
    // the future from being completed.
    if (!f.isDone()) {
      activeTasks.put(cleanupRunnable, f);
    }
  }

  private final class CleanupRunnable implements Runnable {
    private final Runnable r;

    private CleanupRunnable(Runnable r) {
      this.r = r;
    }

    @Override
    public void run() {
      try {
        r.run();
      } finally {
        synchronized (SharingExecutorService.this) {
          activeTasks.remove(this);
          if (shutdown) {
            if (activeTasks.isEmpty()) {
              terminated = true;
              SharingExecutorService.this.notifyAll();
            }
          }
        }
      }
    }
  }
}

