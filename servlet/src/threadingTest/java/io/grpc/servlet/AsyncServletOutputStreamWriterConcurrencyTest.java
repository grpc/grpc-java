/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.servlet;

import static org.jetbrains.lincheck.datastructures.ManagedStrategyGuaranteeKt.forClasses;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.grpc.servlet.AsyncServletOutputStreamWriter.ActionItem;
import io.grpc.servlet.AsyncServletOutputStreamWriter.Log;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.jetbrains.lincheck.datastructures.BooleanGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test concurrency correctness of {@link AsyncServletOutputStreamWriter} using model checking with
 * Lincheck.
 *
 * <p>This test should only call AsyncServletOutputStreamWriter's API surface and not rely on any
 * implementation detail such as whether it's using a lock-free approach or not.
 *
 * <p>The test executes two threads concurrently, one for write and flush, and the other for
 * onWritePossible up to {@link #OPERATIONS_PER_THREAD} operations on each thread. Lincheck will
 * test all possibly interleaves (on context switch) between the two threads, and then verify the
 * operations are linearizable in each interleave scenario.
 */
@ModelCheckingCTest
@Param(name = "keepReady", gen = BooleanGen.class)
@RunWith(JUnit4.class)
public class AsyncServletOutputStreamWriterConcurrencyTest {
  private static final int OPERATIONS_PER_THREAD = 6;

  private final AsyncServletOutputStreamWriter writer;
  private final boolean[] keepReadyArray = new boolean[OPERATIONS_PER_THREAD];

  private volatile boolean isReady;
  /**
   * The container initiates the first call shortly after {@code startAsync}.
   */
  private final AtomicBoolean initialOnWritePossible = new AtomicBoolean(true);
  // when isReadyReturnedFalse, writer.onWritePossible() will be called.
  private volatile boolean isReadyReturnedFalse;
  private int producerIndex;
  private int consumerIndex;
  private int bytesWritten;

  /** Public no-args constructor. */
  public AsyncServletOutputStreamWriterConcurrencyTest() {
    BiFunction<byte[], Integer, ActionItem> writeAction =
        (bytes, numBytes) -> () -> {
          assertTrue("write should only be called while isReady() is true", isReady);
          // The byte to be written must equal to consumerIndex, otherwise execution order is wrong
          assertEquals("write in wrong order", bytes[0], (byte) consumerIndex);
          bytesWritten++;
          writeOrFlush();
        };

    ActionItem flushAction = () -> {
      assertTrue("flush must only be called while isReady() is true", isReady);
      writeOrFlush();
    };

    writer = new AsyncServletOutputStreamWriter(
        writeAction,
        flushAction,
        () -> { },
        this::isReady,
        new Log() {});
  }

  private void writeOrFlush() {
    boolean keepReady = keepReadyArray[consumerIndex];
    if (!keepReady) {
      isReady = false;
    }
    consumerIndex++;
  }

  private boolean isReady() {
    boolean copyOfIsReady = isReady;
    if (!copyOfIsReady) {
      assertFalse("isReady() already returned false, onWritePossible() will be invoked",
          isReadyReturnedFalse);
      isReadyReturnedFalse = true;
    }
    return copyOfIsReady;
  }

  /**
   * Writes a single byte with value equal to {@link #producerIndex}.
   *
   * @param keepReady when the byte is written:
   *                  the ServletOutputStream should remain ready if keepReady == true;
   *                  the ServletOutputStream should become unready if keepReady == false.
   */
  // @com.google.errorprone.annotations.Keep
  @Operation(nonParallelGroup = "write")
  public void write(@Param(name = "keepReady") boolean keepReady) throws IOException {
    keepReadyArray[producerIndex] = keepReady;
    writer.writeBytes(new byte[]{(byte) producerIndex}, 1);
    producerIndex++;
  }

  /**
   * Flushes the writer.
   *
   * @param keepReady when flushing:
   *                  the ServletOutputStream should remain ready if keepReady == true;
   *                  the ServletOutputStream should become unready if keepReady == false.
   */
  // @com.google.errorprone.annotations.Keep // called by lincheck reflectively
  @Operation(nonParallelGroup = "write")
  public void flush(@Param(name = "keepReady") boolean keepReady) throws IOException {
    keepReadyArray[producerIndex] = keepReady;
    writer.flush();
    producerIndex++;
  }

  /** If the writer is not ready, let it turn ready and call writer.onWritePossible(). */
  // @com.google.errorprone.annotations.Keep // called by lincheck reflectively
  @Operation(nonParallelGroup = "update")
  public void maybeOnWritePossible() throws IOException {
    if (initialOnWritePossible.compareAndSet(true, false)) {
      isReady = true;
      writer.onWritePossible();
    } else if (isReadyReturnedFalse) {
      isReadyReturnedFalse = false;
      isReady = true;
      writer.onWritePossible();
    }
  }

  @Override
  public final boolean equals(Object o) {
    return o instanceof AsyncServletOutputStreamWriterConcurrencyTest
        && bytesWritten == ((AsyncServletOutputStreamWriterConcurrencyTest) o).bytesWritten;
  }

  @Override
  public int hashCode() {
    return bytesWritten;
  }

  @Test
  public void linCheck() {
    ModelCheckingOptions options = new ModelCheckingOptions()
        .actorsBefore(0)
        .threads(2)
        .actorsPerThread(OPERATIONS_PER_THREAD)
        .actorsAfter(0)
        .addGuarantee(
            forClasses(
                    ConcurrentLinkedQueue.class.getName(),
                    AtomicReference.class.getName())
                .allMethods()
                .treatAsAtomic());
    options.check(AsyncServletOutputStreamWriterConcurrencyTest.class);
  }
}
