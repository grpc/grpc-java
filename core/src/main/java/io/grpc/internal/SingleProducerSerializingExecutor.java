/*
 * Copyright 2016, Google Inc. All rights reserved.
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
import com.google.common.base.Throwables;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Executor ensuring that all {@link Runnable} tasks submitted are executed in order
 * using the provided {@link Executor}, and serially such that no two will ever be
 * running at the same time.
 *
 * <p>This executor is not thread-safe. It handles synchronization with running tasks, but {@link
 * #execute} may not be called concurrently by multiple threads.
 */
@NotThreadSafe
public final class SingleProducerSerializingExecutor implements Executor {
  private static final Logger log =
      Logger.getLogger(SerializingExecutor.class.getName());
  private static final
      AtomicReferenceFieldUpdater<SingleProducerSerializingExecutor, LinkedRunnable> tailUpdater =
      AtomicReferenceFieldUpdater.newUpdater(
          SingleProducerSerializingExecutor.class, LinkedRunnable.class, "tail");

  /** Underlying executor that all submitted Runnable objects are run on. */
  private final Executor executor;
  /**
   * If {@code null}, TaskRunner is not scheduled or running. This is the actual tail of the queue,
   * independent of whether {@link LinkedRunnable#next} is non-{@code null}.
   *
   * <p>Although it might seem like we could use a single-producer, single-consumer queue, it's a
   * bit more complex than that because we also have to manage starting and restarting the consumer.
   * So we still require the producer and consumer to write to a single shared location.
   */
  private volatile LinkedRunnable tail;
  /** The object that actually runs the Runnables submitted, reused. */
  private final TaskRunner taskRunner = new TaskRunner();

  /**
   * Creates a SerializingExecutor, running tasks using {@code executor}.
   *
   * @param executor Executor in which tasks should be run. Must not be null.
   */
  public SingleProducerSerializingExecutor(Executor executor) {
    this.executor = Preconditions.checkNotNull(executor, "executor");
  }

  private boolean tailCompareAndSet(LinkedRunnable expect, LinkedRunnable update) {
    return tailUpdater.compareAndSet(this, expect, update);
  }

  /**
   * Runs the given runnable strictly after all Runnables that were submitted before it, and using
   * the {@code executor} passed to the constructor.
   */
  @Override
  public void execute(Runnable r) {
    Preconditions.checkNotNull(r, "runnable");
    while (true) {
      LinkedRunnable cachedTail = tail;
      if (cachedTail == null) { // TaskRunnable not running.
        cachedTail = new LinkedRunnable(r);
        if (!tailCompareAndSet(null, cachedTail)) {
          // Impossible, since TaskRunnable isn't running and we assume single producer.
          throw new AssertionError();
        }
        taskRunner.schedule(cachedTail);
        return;
      } else {
        // Must set 'next' before changing 'tail', so that the volatile 'write' to tail publishes
        // the write to 'next'. It also makes the LinkedRunnable available to TaskRunner immediately
        // when it discovers tail has changed.
        cachedTail.next = new LinkedRunnable(r);
        if (!tailCompareAndSet(cachedTail, cachedTail.next)) {
          // 'next' is guaranteed not to be read, since tail hasn't been updated and TaskRunner does
          // not go past the current tail. Clearing the field has little practice use, other than
          // calling attention to the fact that the field is not read.
          cachedTail.next = null;
          // Try again. The only explanation for this failure is TaskRunner exited (or someone is
          // misusing the class via multiple producers).
          if (tail != null) {
            throw new AssertionError();
          }
          continue;
        }
        return;
      }
    }
  }

  private static class LinkedRunnable implements Runnable {
    private final Runnable runnable;
    /**
     * The next runnable in the queue. This value is only changed from null to non-null by
     * execute(), and from non-null to null by TaskRunner.
     *
     * <p>It does not need to be volatile because:
     * <ol>
     *   <li>{@code TaskRunner} never advances in the queue past 'tail'. Thus, this object is
     *   essentially published safely via the volatile 'tail'.
     *
     *   <li>Since this is a LinkedRunnable, the final {@link #runnable} field is safe to use from
     *   {@link #run()}. Final fields and the objects they reference are guaranteed to be properly
     *   published even without synchronization, if a reference to the containing object is
     *   observed. See JLS§17.5.
     * </ol>
     */
    LinkedRunnable next;

    public LinkedRunnable(Runnable runnable) {
      this.runnable = runnable;
    }

    public void run() {
      runnable.run();
    }
  }

  /**
   * Task that actually runs the Runnables. The head of the queue must be set before execution. This
   * is the only reference to the head of the queue, such that it is the only code that can iterate
   * over the full queue. It executes entries in the queue until it is empty, sets tail=null, and
   * then returns. This allows the current worker thread to return to the original pool.
   */
  private class TaskRunner implements Runnable {
    private LinkedRunnable head;

    void schedule(LinkedRunnable newHead) {
      if (head != null) {
        throw new AssertionError();
      }
      head = newHead;
      try {
        executor.execute(this);
      } catch (Throwable t) {
        // It is possible that 'newHead' is still queued and tail==newHead. But that will trick
        // execute() into thinking TaskRunner is running (we assume it isn't) which would prevent
        // all future Runnables from being run. So we clear all state and propagate the exception so
        // that if our caller deems it recoverable we won't be permanently stuck.
        tail = null;
        head = null; // Prevent retaining garbage
        Throwables.propagateIfPossible(t);
        throw new AssertionError(t);
      }
    }

    @Override
    public void run() {
      LinkedRunnable head = this.head;
      this.head = null; // Prevent retaining garbage after the method returns

      LinkedRunnable cachedTail = tail;
      while (true) {
        try {
          head.run();
        } catch (Throwable t) {
          if (head == tail && tailCompareAndSet(head, null)) {
            // Queue exhausted. No need to reschedule. Just let exception propagate.
          } else {
            head = Preconditions.checkNotNull(head.next, "head.next");
            try {
              schedule(head);
            } catch (Throwable t2) {
              log.log(Level.SEVERE, "Exception while re-scheduling due to exception", t2);
            }
          }
          Throwables.propagateIfPossible(t);
          throw new AssertionError(t);
        }
        // Do not advance in the queue past tail, even if head.next != null, otherwise we could win
        // a spectatular race by executing head.next before execute() has CAS tail. Then we would be
        // forced to spin-loop waiting for execute() to catch up; there would be no more work queued
        // and we couldn't set tail=null and return, because then execute() would reschedule without
        // knowing the runnable had already been executed.
        if (head == cachedTail && head == (cachedTail = tail)) {
          if (tailCompareAndSet(head, null)) {
            break;
          } else {
            cachedTail = tail;
          }
        }
        LinkedRunnable prevHead = head;
        head = Preconditions.checkNotNull(head.next, "head.next");
        prevHead.next = null; // Prevent GC nepotism
      }
    }
  }
}
