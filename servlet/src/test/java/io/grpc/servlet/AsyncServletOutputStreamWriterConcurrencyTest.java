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

import static com.google.common.truth.Truth.assertWithMessage;
import static org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategyGuaranteeKt.forClasses;

import io.grpc.servlet.AsyncServletOutputStreamWriter.ActionItem;
import io.grpc.servlet.AsyncServletOutputStreamWriter.Log;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.kotlinx.lincheck.annotations.OpGroupConfig;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.annotations.Param;
import org.jetbrains.kotlinx.lincheck.paramgen.BooleanGen;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTest;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions;
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test concurrency correctness of {@link AsyncServletOutputStreamWriter} using model checking with
 * Lincheck.
 *
 * <p>This test should only call AsyncServletOutputStreamWriter's API surface and not rely on any
 * implementation detail such as whether it's using a lock-free approach or not.
 */
@ModelCheckingCTest
@OpGroupConfig(name = "update", nonParallel = true)
@OpGroupConfig(name = "write", nonParallel = true)
@Param(name = "keepReady", gen = BooleanGen.class)
@RunWith(JUnit4.class)
public class AsyncServletOutputStreamWriterConcurrencyTest extends VerifierState {

  private final AsyncServletOutputStreamWriter writer;
  private final List<Boolean> keepReadyList = Arrays.asList(new Boolean[6]);

  private volatile boolean isReady;
  // when isReadyReturnedFalse, writer.onWritePossible() will be called.
  private volatile boolean isReadyReturnedFalse;
  private int producerIndex;
  private int consumerIndex;

  /** Public no-args constructor. */
  public AsyncServletOutputStreamWriterConcurrencyTest() {
    BiFunction<byte[], Integer, ActionItem> writeAction =
        (bytes, numBytes) -> () -> {
          assertWithMessage("write should only be called while isReady() is true")
              .that(isReady)
              .isTrue();
          // The byte to be written must equal to consumerIndex, otherwise execution order is wrong
          assertWithMessage("write in wrong order").that(bytes[0]).isEqualTo((byte) consumerIndex);
          writeOrFlush();
        };

    ActionItem flushAction = () -> {
      assertWithMessage("flush must only be called while isReady() is true").that(isReady).isTrue();
      writeOrFlush();
    };

    writer = new AsyncServletOutputStreamWriter(
        writeAction,
        flushAction,
        () -> {
        },
        this::isReady,
        new Log() {});
  }

  private void writeOrFlush() {
    boolean keepReady = keepReadyList.get(consumerIndex);
    if (!keepReady) {
      isReady = false;
    }
    consumerIndex++;
  }

  private boolean isReady() {
    if (!isReady) {
      assertWithMessage("isReady() already returned false, onWritePossible() will be invoked")
          .that(isReadyReturnedFalse).isFalse();
      isReadyReturnedFalse = true;
    }
    return isReady;
  }

  /**
   * Writes a single byte with value equal to {@link #producerIndex}.
   *
   * @param keepReady when the byte is written:
   *                  the ServletOutputStream should remain ready if keepReady == true;
   *                  the ServletOutputStream should become unready if keepReady == false.
   */
  // @com.google.errorprone.annotations.Keep
  @Operation(group = "write")
  public void write(@Param(name = "keepReady") boolean keepReady) throws IOException {
    keepReadyList.set(producerIndex, keepReady);
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
  @Operation(group = "write")
  public void flush(@Param(name = "keepReady") boolean keepReady) throws IOException {
    keepReadyList.set(producerIndex, keepReady);
    writer.flush();
    producerIndex++;
  }

  /** If the writer is not ready, let it turn ready and call writer.onWritePossible(). */
  // @com.google.errorprone.annotations.Keep // called by lincheck reflectively
  @Operation(group = "update", handleExceptionsAsResult = IOException.class)
  public void maybeOnWritePossible() throws IOException {
    if (isReadyReturnedFalse) {
      isReadyReturnedFalse = false;
      isReady = true;
      writer.onWritePossible();
    }
  }

  @Override
  protected Object extractState() {
    return keepReadyList;
  }

  @Test
  public void linCheck() {
    ModelCheckingOptions options = new ModelCheckingOptions()
        .actorsBefore(0)
        .threads(2)
        .actorsPerThread(6)
        .actorsAfter(0)
        .addGuarantee(
            forClasses(
                    ConcurrentLinkedQueue.class.getName(),
                    AtomicReference.class.getName())
                .allMethods()
                .treatAsAtomic());
    LinChecker.check(AsyncServletOutputStreamWriterConcurrencyTest.class, options);
  }
}
