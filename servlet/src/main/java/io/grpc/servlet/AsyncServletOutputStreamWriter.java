/*
 * Copyright 2019 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkState;
import static io.grpc.servlet.ServletServerStream.toHexString;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINEST;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.InternalLogId;
import io.grpc.servlet.ServletServerStream.ServletTransportState;
import java.io.IOException;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;

/** Handles write actions from the container thread and the application thread. */
final class AsyncServletOutputStreamWriter {

  /**
   * Memory boundary for write actions.
   *
   * <pre>
   * WriteState curState = writeState.get();  // mark a boundary
   * doSomething();  // do something within the boundary
   * boolean successful = writeState.compareAndSet(curState, newState); // try to mark a boundary
   * if (successful) {
   *   // state has not changed since
   *   return;
   * } else {
   *   // state is changed by another thread while doSomething(), need recompute
   * }
   * </pre>
   *
   * <p>There are two threads, the container thread (calling {@code onWritePossible()}) and the
   * application thread (calling {@code runOrBuffer()}) that read and update the
   * writeState. Only onWritePossible() may turn {@code readyAndDrained} from false to true, and
   * only runOrBuffer() may turn it from true to false.
   */
  private final AtomicReference<WriteState> writeState = new AtomicReference<>(WriteState.DEFAULT);

  private final Log log;
  private final BiFunction<byte[], Integer, ActionItem> writeAction;
  private final ActionItem flushAction;
  private final ActionItem completeAction;
  private final BooleanSupplier isReady;

  /**
   * New write actions will be buffered into this queue if the servlet output stream is not ready or
   * the queue is not drained.
   */
  // SPSC queue would do
  private final Queue<ActionItem> writeChain = new ConcurrentLinkedQueue<>();
  // for a theoretical race condition that onWritePossible() is called immediately after isReady()
  // returns false and before writeState.compareAndSet()
  @Nullable
  private volatile Thread parkingThread;

  AsyncServletOutputStreamWriter(
      AsyncContext asyncContext,
      ServletTransportState transportState,
      InternalLogId logId) throws IOException {
    Logger logger = Logger.getLogger(AsyncServletOutputStreamWriter.class.getName());
    this.log = new Log() {
      @Override
      public void fine(String str, Object... params) {
        if (logger.isLoggable(FINE)) {
          logger.log(FINE, "[" + logId + "]" + str, params);
        }
      }

      @Override
      public void finest(String str, Object... params) {
        if (logger.isLoggable(FINEST)) {
          logger.log(FINEST, "[" + logId + "] " + str, params);
        }
      }
    };

    ServletOutputStream outputStream = asyncContext.getResponse().getOutputStream();
    this.writeAction = (byte[] bytes, Integer numBytes) -> () -> {
      outputStream.write(bytes, 0, numBytes);
      transportState.runOnTransportThread(() -> transportState.onSentBytes(numBytes));
      log.finest("outbound data: length={0}, bytes={1}", numBytes, toHexString(bytes, numBytes));
    };
    this.flushAction = () -> {
      log.finest("flushBuffer");
      asyncContext.getResponse().flushBuffer();
    };
    this.completeAction = () -> {
      log.fine("call is completing");
      transportState.runOnTransportThread(
          () -> {
            transportState.complete();
            asyncContext.complete();
            log.fine("call completed");
          });
    };
    this.isReady = () -> outputStream.isReady();
  }

  /**
   * Constructor without java.util.logging and javax.servlet.* dependency, so that Lincheck can run.
   *
   * @param writeAction Provides an {@link ActionItem} to write given bytes with specified length.
   * @param isReady Indicates whether the writer can write bytes at the moment (asynchronously).
   */
  @VisibleForTesting
  AsyncServletOutputStreamWriter(
      BiFunction<byte[], Integer, ActionItem> writeAction,
      ActionItem flushAction,
      ActionItem completeAction,
      BooleanSupplier isReady,
      Log log) {
    this.writeAction = writeAction;
    this.flushAction = flushAction;
    this.completeAction = completeAction;
    this.isReady = isReady;
    this.log = log;
  }

  /** Called from application thread. */
  void writeBytes(byte[] bytes, int numBytes) throws IOException {
    runOrBuffer(writeAction.apply(bytes, numBytes));
  }

  /** Called from application thread. */
  void flush() throws IOException {
    runOrBuffer(flushAction);
  }

  /** Called from application thread. */
  void complete() {
    try {
      runOrBuffer(completeAction);
    } catch (IOException ignore) {
      // actually completeAction does not throw IOException
    }
  }

  /** Called from the container thread {@link javax.servlet.WriteListener#onWritePossible()}. */
  void onWritePossible() throws IOException {
    log.finest("onWritePossible: ENTRY. The servlet output stream becomes ready");
    assureReadyAndDrainedTurnsFalse();
    while (isReady.getAsBoolean()) {
      WriteState curState = writeState.get();

      ActionItem actionItem = writeChain.poll();
      if (actionItem != null) {
        actionItem.run();
        continue;
      }

      if (writeState.compareAndSet(curState, curState.withReadyAndDrained(true))) {
        // state has not changed since.
        log.finest(
            "onWritePossible: EXIT. All data available now is sent out and the servlet output"
                + " stream is still ready");
        return;
      }
      // else, state changed by another thread (runOrBuffer()), need to drain the writeChain
      // again
    }
    log.finest("onWritePossible: EXIT. The servlet output stream becomes not ready");
  }

  private void assureReadyAndDrainedTurnsFalse() {
    // readyAndDrained should have been set to false already.
    // Just in case due to a race condition readyAndDrained is still true at this moment and is
    // being set to false by runOrBuffer() concurrently.
    while (writeState.get().readyAndDrained) {
      parkingThread = Thread.currentThread();
      // Try to sleep for an extremely long time to avoid writeState being changed at exactly
      // the time when sleep time expires (in extreme scenario, such as #9917).
      LockSupport.parkNanos(Duration.ofHours(1).toNanos()); // should return immediately
    }
    parkingThread = null;
  }

  /**
   * Either execute the write action directly, or buffer the action and let the container thread
   * drain it.
   *
   * <p>Called from application thread.
   */
  private void runOrBuffer(ActionItem actionItem) throws IOException {
    WriteState curState = writeState.get();
    if (curState.readyAndDrained) { // write to the outputStream directly
      actionItem.run();
      if (actionItem == completeAction) {
        return;
      }
      if (!isReady.getAsBoolean()) {
        boolean successful =
            writeState.compareAndSet(curState, curState.withReadyAndDrained(false));
        LockSupport.unpark(parkingThread);
        checkState(successful, "Bug: curState is unexpectedly changed by another thread");
        log.finest("the servlet output stream becomes not ready");
      }
    } else { // buffer to the writeChain
      writeChain.offer(actionItem);
      if (!writeState.compareAndSet(curState, curState.withReadyAndDrained(false))) {
        checkState(
            writeState.get().readyAndDrained,
            "Bug: onWritePossible() should have changed readyAndDrained to true, but not");
        ActionItem lastItem = writeChain.poll();
        if (lastItem != null) {
          checkState(lastItem == actionItem, "Bug: lastItem != actionItem");
          runOrBuffer(lastItem);
        }
      } // state has not changed since
    }
  }

  /** Write actions, e.g. writeBytes, flush, complete. */
  @FunctionalInterface
  @VisibleForTesting
  interface ActionItem {
    void run() throws IOException;
  }

  @VisibleForTesting // Lincheck test can not run with java.util.logging dependency.
  interface Log {
    default void fine(String str, Object...params) {}

    default void finest(String str, Object...params) {}
  }

  private static final class WriteState {

    static final WriteState DEFAULT = new WriteState(false);

    /**
     * The servlet output stream is ready and the writeChain is empty.
     *
     * <p>readyAndDrained turns from false to true when:
     * {@code onWritePossible()} exits while currently there is no more data to write, but the last
     * check of {@link javax.servlet.ServletOutputStream#isReady()} is true.
     *
     * <p>readyAndDrained turns from true to false when:
     * {@code runOrBuffer()} exits while either the action item is written directly to the
     * servlet output stream and the check of {@link javax.servlet.ServletOutputStream#isReady()}
     * right after that returns false, or the action item is buffered into the writeChain.
     */
    final boolean readyAndDrained;

    WriteState(boolean readyAndDrained) {
      this.readyAndDrained = readyAndDrained;
    }

    /**
     * Only {@code onWritePossible()} can set readyAndDrained to true, and only {@code
     * runOrBuffer()} can set it to false.
     */
    @CheckReturnValue
    WriteState withReadyAndDrained(boolean readyAndDrained) {
      return new WriteState(readyAndDrained);
    }
  }
}
