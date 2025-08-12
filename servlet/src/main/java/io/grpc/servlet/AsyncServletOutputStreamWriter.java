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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.InternalLogId;
import io.grpc.servlet.ServletServerStream.ServletTransportState;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.StampedLock;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;

/** Handles write actions from the container thread and the application thread. */
final class AsyncServletOutputStreamWriter {

  private final StampedLock writeLock = new StampedLock();

  /**
   * The servlet output stream is ready and the writeQueue is empty.
   *
   * <p>There are two threads, the container thread (calling {@code onWritePossible()}) and the
   * application thread (calling {@code runOrBuffer()}) that read and update this field.
   * Only onWritePossible() may turn {@code readyAndDrained} from false to true, and
   * only runOrBuffer() may turn it from true to false.
   *
   * <p>readyAndDrained turns from false to true when:
   * {@code onWritePossible()} exits while currently there is no more data to write, but the last
   * check of {@link javax.servlet.ServletOutputStream#isReady()} is true.
   *
   * <p>readyAndDrained turns from true to false when:
   * {@code runOrBuffer()} exits while either the action item is written directly to the
   * servlet output stream and the check of {@link javax.servlet.ServletOutputStream#isReady()}
   * right after that returns false, or the action item is buffered into the writeQueue.
   */
  // @GuardedBy("writeLock")
  private boolean readyAndDrained;

  private final Log log;
  private final BiFunction<byte[], Integer, ActionItem> writeAction;
  private final ActionItem flushAction;
  private final ActionItem completeAction;
  private final BooleanSupplier isReady;

  /**
   * New write actions will be buffered into this queue.
   */
  // SPSC queue would do
  private final Queue<ActionItem> writeQueue = new ConcurrentLinkedQueue<>();

  AsyncServletOutputStreamWriter(
      AsyncContext asyncContext,
      ServletTransportState transportState,
      InternalLogId logId) throws IOException {
    Logger logger = Logger.getLogger(AsyncServletOutputStreamWriter.class.getName());
    this.log = new Log() {
      @Override
      public boolean isLoggable(Level level) {
        return logger.isLoggable(level);
      }

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
      if (log.isLoggable(Level.FINEST)) {
        log.finest("outbound data: length={0}, bytes={1}", numBytes, toHexString(bytes, numBytes));
      }
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
    this.isReady = outputStream::isReady;
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
    long stamp = writeLock.writeLock();
    try {
      while (isReady.getAsBoolean()) {
        ActionItem actionItem = writeQueue.poll();
        if (actionItem == null) {
          readyAndDrained = true;
          log.finest("onWritePossible: EXIT. Queue drained");
          return;
        }
        actionItem.run();
      }
      log.finest("onWritePossible: EXIT. The servlet output stream becomes not ready");
    } finally {
      writeLock.unlockWrite(stamp);
    }
  }

  /**
   * Either execute the write action directly, or buffer the action and let the container thread
   * drain it.
   *
   * <p>Called from application thread.
   */
  private void runOrBuffer(ActionItem actionItem) throws IOException {
    writeQueue.offer(actionItem);
    long stamp = writeLock.tryWriteLock();
    if (stamp == 0L) {
      return;
    }
    try {
      if (readyAndDrained) { // write to the outputStream directly
        ActionItem toWrite = writeQueue.poll();
        if (toWrite != null) {
          toWrite.run();
          if (toWrite == completeAction) {
            return;
          }
          if (!isReady.getAsBoolean()) {
            readyAndDrained = false;
          }
        }
      }
    } finally {
      writeLock.unlockWrite(stamp);
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
    default boolean isLoggable(Level level) {
      return false;
    }

    default void fine(String str, Object...params) {}

    default void finest(String str, Object...params) {}
  }
}
