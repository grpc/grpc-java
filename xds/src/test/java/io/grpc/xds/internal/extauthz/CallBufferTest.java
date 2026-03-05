/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds.internal.extauthz;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;

@RunWith(JUnit4.class)
public class CallBufferTest {

  private CallBuffer callBuffer;

  @Before
  public void setUp() {
    callBuffer = new CallBuffer();
  }

  @Test
  public void runOrBuffer_beforeProcessed_buffersCall() {
    Runnable runnable = mock(Runnable.class);
    callBuffer.runOrBuffer(runnable);
    verify(runnable, never()).run();
    assertThat(callBuffer.isProcessed()).isFalse();
  }

  @Test
  public void runAndFlush_executesBufferedCallsInOrder() {
    Runnable runnable1 = mock(Runnable.class);
    Runnable runnable2 = mock(Runnable.class);
    callBuffer.runOrBuffer(runnable1);
    callBuffer.runOrBuffer(runnable2);

    InOrder inOrder = inOrder(runnable1, runnable2);
    verify(runnable1, never()).run();
    verify(runnable2, never()).run();

    callBuffer.runAndFlush();

    inOrder.verify(runnable1).run();
    inOrder.verify(runnable2).run();
    assertThat(callBuffer.isProcessed()).isTrue();
  }

  @Test
  public void runOrBuffer_afterRunAndFlush_runsImmediately() {
    callBuffer.runAndFlush();
    assertThat(callBuffer.isProcessed()).isTrue();

    Runnable runnable = mock(Runnable.class);
    callBuffer.runOrBuffer(runnable);
    verify(runnable).run();
  }

  @Test
  public void abandon_discardsBufferedCalls() {
    Runnable runnable = mock(Runnable.class);
    callBuffer.runOrBuffer(runnable);
    verify(runnable, never()).run();

    callBuffer.abandon();
    assertThat(callBuffer.isProcessed()).isTrue();

    // Another flush should not run the abandoned runnable
    callBuffer.runAndFlush();
    verify(runnable, never()).run();
  }

  @Test
  public void runOrBuffer_afterAbandon_runsImmediately() {
    callBuffer.abandon();
    assertThat(callBuffer.isProcessed()).isTrue();

    Runnable runnable = mock(Runnable.class);
    callBuffer.runOrBuffer(runnable);
    verify(runnable).run();
  }

  @Test
  public void runAndFlush_isIdempotent() {
    Runnable runnable = mock(Runnable.class);
    callBuffer.runOrBuffer(runnable);

    callBuffer.runAndFlush();
    callBuffer.runAndFlush();

    verify(runnable, times(1)).run();
  }

  @Test
  public void abandon_isIdempotent() {
    Runnable runnable = mock(Runnable.class);
    callBuffer.runOrBuffer(runnable);

    callBuffer.abandon();
    callBuffer.abandon();

    verify(runnable, never()).run();
  }

  @Test
  public void abandon_afterRunAndFlush_isNoOp() {
    Runnable runnable = mock(Runnable.class);
    callBuffer.runOrBuffer(runnable);

    callBuffer.runAndFlush();
    callBuffer.abandon();

    verify(runnable, times(1)).run();
  }

  @Test
  public void runAndFlush_afterAbandon_isNoOp() {
    Runnable runnable = mock(Runnable.class);
    callBuffer.runOrBuffer(runnable);

    callBuffer.abandon();
    callBuffer.runAndFlush();

    verify(runnable, never()).run();
  }

  // TODO(sauravzg): How to remove dependency on time using explicit synchronization?
  @Test
  public void concurrentRunOrBuffer_thenRunAndFlush() throws Exception {
    int numThreads = 10;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    List<Runnable> runnables = new ArrayList<>();
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < numThreads; i++) {
      runnables.add(mock(Runnable.class));
    }
    CountDownLatch latch = new CountDownLatch(numThreads);

    for (Runnable runnable : runnables) {
      futures.add(executor.submit(() -> {
        callBuffer.runOrBuffer(runnable);
        latch.countDown();
      }));
    }

    latch.await(5, TimeUnit.SECONDS);
    for (Runnable runnable : runnables) {
      verify(runnable, never()).run();
    }

    callBuffer.runAndFlush();

    for (Runnable runnable : runnables) {
      verify(runnable).run();
    }

    for (Future<?> future : futures) {
      future.get();
    }

    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);
  }
}
