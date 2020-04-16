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

import static io.grpc.servlet.ServletServerStream.toHexString;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINEST;

import io.grpc.InternalLogId;
import io.grpc.Status;
import io.grpc.servlet.ServletServerStream.ServletTransportState;
import java.io.IOException;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Logger;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;

/** Handles write actions from the container thread and the application thread. */
final class AsyncServletOutputStreamWriter {

  private static final Logger logger =
      Logger.getLogger(AsyncServletOutputStreamWriter.class.getName());

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
   * application thread (calling {@code runOrBufferActionItem()}) that read and update the
   * writeState. Only onWritePossible() may turn readyAndEmpty from false to true, and only
   * runOrBufferActionItem() may turn it from true to false.
   */
  private final AtomicReference<WriteState> writeState = new AtomicReference<>(WriteState.DEFAULT);

  private final ServletOutputStream outputStream;
  private final ServletTransportState transportState;
  private final InternalLogId logId;
  private final ActionItem flushAction;
  private final ActionItem completeAction;

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
      ServletOutputStream outputStream,
      ServletTransportState transportState,
      InternalLogId logId) {
    this.outputStream = outputStream;
    this.transportState = transportState;
    this.logId = logId;
    this.flushAction = () -> {
      logger.log(FINEST, "[{0}] flushBuffer", logId);
      asyncContext.getResponse().flushBuffer();
    };
    this.completeAction = () -> {
      logger.log(FINE, "[{0}] call is completing", logId);
      transportState.runOnTransportThread(
          () -> {
            transportState.complete();
            asyncContext.complete();
            logger.log(FINE, "[{0}] call completed", logId);
          });
    };
  }

  /** Called from application thread. */
  void writeBytes(byte[] bytes, int numBytes) throws IOException {
    runOrBufferActionItem(
        // write bytes action
        () -> {
          outputStream.write(bytes, 0, numBytes);
          transportState.runOnTransportThread(() -> transportState.onSentBytes(numBytes));
          if (logger.isLoggable(FINEST)) {
            logger.log(
                FINEST,
                "[{0}] outbound data: length = {1}, bytes = {2}",
                new Object[]{logId, numBytes, toHexString(bytes, numBytes)});
          }
        });
  }

  /** Called from application thread. */
  void flush() throws IOException {
    runOrBufferActionItem(flushAction);
  }

  /** Called from application thread. */
  void complete() {
    try {
      runOrBufferActionItem(completeAction);
    } catch (IOException e) {
      // actually completeAction does not throw
      throw Status.fromThrowable(e).asRuntimeException();
    }
  }

  /** Called from the container thread {@link javax.servlet.WriteListener#onWritePossible()}. */
  void onWritePossible() throws IOException {
    logger.log(
        FINEST, "[{0}] onWritePossible: ENTRY. The servlet output stream becomes ready", logId);
    assureReadyAndEmptyFalse();
    while (outputStream.isReady()) {
      WriteState curState = writeState.get();

      ActionItem actionItem = writeChain.poll();
      if (actionItem != null) {
        actionItem.run();
        continue;
      }

      if (writeState.compareAndSet(curState, curState.withReadyAndEmpty(true))) {
        // state has not changed since.
        logger.log(
            FINEST,
            "[{0}] onWritePossible: EXIT. All data available now is sent out and the servlet output"
                + " stream is still ready",
            logId);
        return;
      }
      // else, state changed by another thread (runOrBufferActionItem), need to drain the writeChain
      // again
    }
    logger.log(
        FINEST, "[{0}] onWritePossible: EXIT. The servlet output stream becomes not ready", logId);
  }

  private void runOrBufferActionItem(ActionItem actionItem) throws IOException {
    WriteState curState = writeState.get();
    if (curState.readyAndEmpty) { // write to the outputStream directly
      actionItem.run();
      if (!outputStream.isReady()) {
        logger.log(FINEST, "[{0}] the servlet output stream becomes not ready", logId);
        boolean successful = writeState.compareAndSet(curState, curState.withReadyAndEmpty(false));
        assert successful;
        LockSupport.unpark(parkingThread);
      }
    } else { // buffer to the writeChain
      writeChain.offer(actionItem);
      if (!writeState.compareAndSet(curState, curState.newItemBuffered())) {
        // state changed by another thread (onWritePossible)
        assert writeState.get().readyAndEmpty;
        ActionItem lastItem = writeChain.poll();
        if (lastItem != null) {
          assert lastItem == actionItem;
          runOrBufferActionItem(lastItem);
        }
      } // state has not changed since
    }
  }

  private void assureReadyAndEmptyFalse() {
    // readyAndEmpty should have been set to false already or right now
    // It's very very unlikely readyAndEmpty is still true due to a race condition
    while (writeState.get().readyAndEmpty) {
      parkingThread = Thread.currentThread();
      LockSupport.parkNanos(Duration.ofSeconds(1).toNanos());
    }
    parkingThread = null;
  }

  /** Write actions, e.g. writeBytes, flush, complete. */
  @FunctionalInterface
  private interface ActionItem {
    void run() throws IOException;
  }

  private static final class WriteState {

    static final WriteState DEFAULT = new WriteState(false);

    /**
     * The servlet output stream is ready and the writeChain is empty.
     *
     * <p>readyAndEmpty turns from false to true when:
     * {@code onWritePossible()} exits while currently there is no more data to write, but the last
     * check of {@link javax.servlet.ServletOutputStream#isReady()} is true.
     *
     * <p>readyAndEmpty turns from false to true when:
     * {@code runOrBufferActionItem()} exits while either the action item is written directly to the
     * servlet output stream and the check of {@link javax.servlet.ServletOutputStream#isReady()}
     * right after that returns false, or the action item is buffered into the writeChain.
     */
    final boolean readyAndEmpty;

    WriteState(boolean readyAndEmpty) {
      this.readyAndEmpty = readyAndEmpty;
    }

    /**
     * Only {@code onWritePossible()} can set readyAndEmpty to true, and only {@code
     * runOrBufferActionItem()} can set it to false.
     */
    @CheckReturnValue
    WriteState withReadyAndEmpty(boolean readyAndEmpty) {
      return new WriteState(readyAndEmpty);
    }

    /** Only {@code runOrBufferActionItem()} can call it, and will set readyAndEmpty to false. */
    @CheckReturnValue
    WriteState newItemBuffered() {
      return new WriteState(false);
    }
  }
}
