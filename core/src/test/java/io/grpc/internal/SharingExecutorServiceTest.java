package io.grpc.internal;

import static org.junit.Assert.assertTrue;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tests for {@link SharingExecutorService}.
 */
@RunWith(JUnit4.class)
public class SharingExecutorServiceTest {

  @SuppressWarnings("deprecation") // guava 14.0
  private final ExecutorService directExecutor = MoreExecutors.sameThreadExecutor();

  private final ExecutorService threadedExecutor = Executors.newFixedThreadPool(1);

  @Test
  public void workflow_directExecutor() throws Exception {
    SharingExecutorService executor = new SharingExecutorService(directExecutor);
    executor.submit(new Runnable() {
      @Override
      public void run() {
        // noop
      }
    });

    executor.shutdown();
    assertTrue(executor.awaitTermination(10, TimeUnit.MILLISECONDS));
  }

  @Test
  public void workflow_shutdownFinishesTasks() throws Exception {
    SharingExecutorService executor = new SharingExecutorService(threadedExecutor);
    final ReentrantLock lock = new ReentrantLock();
    lock.lock();
    executor.submit(new Runnable() {
      @Override
      public void run() {
        lock.lock();
        lock.unlock();
      }
    });

    executor.shutdown();
    // Wait until shutdown
    lock.unlock();
    assertTrue(executor.awaitTermination(10, TimeUnit.MILLISECONDS));
  }

  @Test
  public void workflow_cancelWorks() throws Exception {
    SharingExecutorService executor = new SharingExecutorService(threadedExecutor);
    final ReentrantLock lock = new ReentrantLock();
    lock.lock();
    Future<?> future = executor.submit(new Runnable() {
      @Override
      public void run() {
        try {
          lock.lockInterruptibly();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
        lock.unlock();
      }
    });
    executor.shutdown();

    future.cancel(true);
    assertTrue(executor.awaitTermination(10, TimeUnit.MILLISECONDS));
  }
}

