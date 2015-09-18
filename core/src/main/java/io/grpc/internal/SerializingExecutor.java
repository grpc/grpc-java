/*
 * Copyright 2014, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.internal;

import com.google.common.base.Preconditions;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.concurrent.GuardedBy;

/**
 * Executor ensuring that all {@link Runnable} tasks submitted are executed in order
 * using the provided {@link Executor}, and serially such that no two will ever be
 * running at the same time.
 */
// TODO(madongfly): figure out a way to not expose it or move it to transport package.
public final class SerializingExecutor implements Executor {
  private static final Logger log =
      Logger.getLogger(SerializingExecutor.class.getName());

  /** Underlying executor that all submitted Runnable objects are run on. */
  private final Executor executor;

  /** The list of {@link Runnable} instances produced by the application to be executed. */
  // Initial size set to 4 because it is a nice number and at least the size necessary for handling
  // a unary response: onHeaders + onPayload + onClose
  @GuardedBy("internalLock")
  private ArrayDeque<Runnable> producerQueue = new ArrayDeque<Runnable>(4);

  /**
   * The list of @link Runnable} instances being actively processed by the executor.
   */
  private ArrayDeque<Runnable> consumerQueue = new ArrayDeque<Runnable>(4);

  /**
   * We explicitly keep track of if the TaskRunner is currently scheduled to
   * run.  If it isn't, we start it.  We can't just use
   * producerQueue.isEmpty() as a proxy because we need to ensure that only one
   * Runnable submitted is running at a time so even if producerQueue is empty
   * the isThreadScheduled isn't set to false until after the Runnable is
   * finished.
   */
  @GuardedBy("internalLock")
  private boolean isThreadScheduled = false;

  /** The object that actually runs the Runnables submitted, reused. */
  private final TaskRunner taskRunner = new TaskRunner();

  /**
   * Creates a SerializingExecutor, running tasks using {@code executor}.
   *
   * @param executor Executor in which tasks should be run. Must not be null.
   */
  public SerializingExecutor(Executor executor) {
    Preconditions.checkNotNull(executor, "'executor' must not be null.");
    this.executor = executor;
  }

  private final Object internalLock = new Object() {
    @Override public String toString() {
      return "SerializingExecutor lock: " + super.toString();
    }
  };

  /**
   * Runs the given runnable strictly after all Runnables that were submitted
   * before it, and using the {@code executor} passed to the constructor.     .
   */
  @Override
  public void execute(Runnable r) {
    boolean scheduleTaskRunner = false;
    synchronized (internalLock) {
      producerQueue.add(r);

      if (!isThreadScheduled) {
        isThreadScheduled = true;
        scheduleTaskRunner = true;
      }
    }
    if (scheduleTaskRunner) {
      boolean threw = true;
      try {
        executor.execute(taskRunner);
        threw = false;
      } finally {
        if (threw) {
          synchronized (internalLock) {
            // It is possible that at this point there are still tasks in
            // the queue, it would be nice to keep trying but the error may not
            // be recoverable.  So we update our state and propagate so that if
            // our caller deems it recoverable we won't be stuck.
            isThreadScheduled = false;
          }
        }
      }
    }
  }

  /**
   * Runs the given runnable strictly after all Runnables that were submitted
   * before it, and using the {@code executor} passed to the constructor.     .
   */
  public void execute(Runnable... r) {
    boolean scheduleTaskRunner = false;
    synchronized (internalLock) {
      for (Runnable tmp : r) {
        producerQueue.add(tmp);
      }

      if (!isThreadScheduled) {
        isThreadScheduled = true;
        scheduleTaskRunner = true;
      }
    }
    if (scheduleTaskRunner) {
      boolean threw = true;
      try {
        executor.execute(taskRunner);
        threw = false;
      } finally {
        if (threw) {
          synchronized (internalLock) {
            // It is possible that at this point there are still tasks in
            // the queue, it would be nice to keep trying but the error may not
            // be recoverable.  So we update our state and propagate so that if
            // our caller deems it recoverable we won't be stuck.
            isThreadScheduled = false;
          }
        }
      }
    }
  }

  /**
   * Task that actually runs the Runnables.  It takes the Runnables off of the
   * queue one by one and runs them.  After it is done with all Runnables and
   * there are no more to run, puts the SerializingExecutor in the state where
   * isThreadScheduled = false and returns.  This allows the current worker
   * thread to return to the original pool.
   */
  private class TaskRunner implements Runnable {
    @Override
    public void run() {
      Runnable nextToRun;
      ArrayDeque<Runnable> tmpConsumerQueue;
      do {
        if (consumerQueue.isEmpty()) {
          synchronized (internalLock) {
            if (producerQueue.isEmpty()) {
              isThreadScheduled = false;
              return;
            }
            // Swap the queues rather than copying.
            tmpConsumerQueue = producerQueue;
            producerQueue = consumerQueue;
          }
          consumerQueue = tmpConsumerQueue;
        }
        while ((nextToRun = consumerQueue.poll()) != null) {
          try {
            nextToRun.run();
          } catch (RuntimeException e) {
            // Log it and keep going.
            log.log(Level.SEVERE, "Exception while executing runnable " + nextToRun, e);
          } catch (Error e) {
            // If the application can recover from the error then execution can restart
            // when more work is scheduled.
            synchronized (internalLock) {
              isThreadScheduled = false;
            }
            // Propagate the error
            throw e;
          }
        }
      } while (true);
    }
  }
}
