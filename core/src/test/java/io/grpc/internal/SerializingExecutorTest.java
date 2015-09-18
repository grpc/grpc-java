/*
 * Copyright 2015, Google Inc. All rights reserved.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for {@link io.grpc.internal.SerializingExecutor}.
 */
@RunWith(JUnit4.class)
public class SerializingExecutorTest {
  private static class FakeExecutor implements Executor {
    Queue<Runnable> tasks = Queues.newArrayDeque();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    boolean hasNext() {
      return !tasks.isEmpty();
    }

    void runNext() {
      assertTrue("expected at least one task to run", hasNext());
      tasks.remove().run();
    }

    void runAll() {
      while (hasNext()) {
        runNext();
      }
    }
  }

  private FakeExecutor fakePool;
  private SerializingExecutor serializingExecutor;

  @Before
  public void setUp() {
    fakePool = new FakeExecutor();
    serializingExecutor = new SerializingExecutor(fakePool);
  }

  @Test
  public void testSerializingNullExecutor_fails() {
    try {
      new SerializingExecutor(null);
      fail("Should have failed with NullPointerException.");
    } catch (NullPointerException expected) {
      // No-op
    }
  }

  @Test
  public void testBasics() {
    final AtomicInteger totalCalls = new AtomicInteger();
    Runnable intCounter = new Runnable() {
      @Override
      public void run() {
        totalCalls.incrementAndGet();
        // Make sure that no other tasks are scheduled to run while this is running.
        assertFalse(fakePool.hasNext());
      }
    };

    assertFalse(fakePool.hasNext());
    serializingExecutor.execute(intCounter);
    // A task should have been scheduled
    assertTrue(fakePool.hasNext());
    serializingExecutor.execute(intCounter);
    // Our executor hasn't run any tasks yet.
    assertEquals(0, totalCalls.get());
    fakePool.runAll();
    assertEquals(2, totalCalls.get());
    // Queue is empty so no runner should be scheduled.
    assertFalse(fakePool.hasNext());

    // Check that execute can be safely repeated
    serializingExecutor.execute(intCounter);
    serializingExecutor.execute(intCounter);
    serializingExecutor.execute(intCounter);
    // No change yet.
    assertEquals(2, totalCalls.get());
    fakePool.runAll();
    assertEquals(5, totalCalls.get());
    assertFalse(fakePool.hasNext());
  }

  @Test
  public void testOrdering() {
    final List<Integer> callOrder = Lists.newArrayList();

    class FakeOp implements Runnable {
      final int op;

      FakeOp(int op) {
        this.op = op;
      }

      @Override
      public void run() {
        callOrder.add(op);
      }
    }

    serializingExecutor.execute(new FakeOp(0));
    serializingExecutor.execute(new FakeOp(1));
    serializingExecutor.execute(new FakeOp(2));
    fakePool.runAll();

    assertEquals(ImmutableList.of(0, 1, 2), callOrder);
  }

  @Test
  public void testPrependContinuation() {
    final List<Integer> callOrder = Lists.newArrayList();

    class FakeOp implements Runnable {
      final int op;

      FakeOp(int op) {
        this.op = op;
      }

      @Override
      public void run() {
        callOrder.add(op);
      }
    }

    serializingExecutor.execute(new FakeOp(1));
    serializingExecutor.execute(new FakeOp(2));
    fakePool.runAll();

    assertEquals(ImmutableList.of(1, 2), callOrder);
  }

  @Test
  public void testRuntimeException_doesNotStopExecution() {

    final AtomicInteger numCalls = new AtomicInteger();

    Runnable runMe = new Runnable() {
      @Override
      public void run() {
        numCalls.incrementAndGet();
        throw new RuntimeException("FAKE EXCEPTION!");
      }
    };

    serializingExecutor.execute(runMe);
    serializingExecutor.execute(runMe);
    fakePool.runAll();

    assertEquals(2, numCalls.get());
  }

  @Test
  public void testInterrupt_doesNotStopExecution() {

    final AtomicInteger numCalls = new AtomicInteger();

    Runnable runMe = new Runnable() {
      @Override
      public void run() {
        numCalls.incrementAndGet();
      }
    };

    Thread.currentThread().interrupt();

    serializingExecutor.execute(runMe);
    serializingExecutor.execute(runMe);
    fakePool.runAll();

    assertEquals(2, numCalls.get());
    assertTrue(Thread.interrupted());
  }

  @Test
  public void testDelegateRejection() {
    final AtomicInteger numCalls = new AtomicInteger();
    final AtomicBoolean reject = new AtomicBoolean(true);
    final SerializingExecutor executor = new SerializingExecutor(
        new Executor() {
          @Override public void execute(Runnable r) {
            if (reject.get()) {
              throw new RejectedExecutionException();
            }
            r.run();
          }
        });
    Runnable task = new Runnable() {
      @Override
      public void run() {
        numCalls.incrementAndGet();
      }
    };
    try {
      executor.execute(task);
      fail();
    } catch (RejectedExecutionException expected) {
      // No-op
    }
    assertEquals(0, numCalls.get());
    reject.set(false);
    executor.execute(task);
    assertEquals(2, numCalls.get());
  }

  @Test
  public void testTaskThrowsError() throws Exception {
    class MyError extends Error {}

    final CyclicBarrier barrier = new CyclicBarrier(2);
    // we need to make sure the error gets thrown on a different thread.
    ExecutorService service = Executors.newSingleThreadExecutor();
    try {
      final SerializingExecutor executor = new SerializingExecutor(service);
      Runnable errorTask = new Runnable() {
        @Override
        public void run() {
          throw new MyError();
        }
      };
      Runnable barrierTask = new Runnable() {
        @Override
        public void run() {
          try {
            barrier.await();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      };
      executor.execute(errorTask);
      service.execute(barrierTask);  // submit directly to the service
      // the barrier task runs after the error task so we know that the error has been observed by
      // SerializingExecutor by the time the barrier is satisfied
      barrier.await(10, TimeUnit.SECONDS);
      executor.execute(barrierTask);
      // timeout means the second task wasn't even tried
      barrier.await(10, TimeUnit.SECONDS);
    } finally {
      service.shutdown();
    }
  }
}
