/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.stub;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.ClientCalls.ThreadSafeThreadlessExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents a bidirectional streaming call from a client.  Allows in a blocking manner, sending
 * over the stream and receiving from the stream.  Also supports terminating the call.
 * Wraps a ClientCall and converts from async communication to the public sync paradigm.
 *
 * <p>Supports separate threads for reads and writes, but only 1 of each</p>
 *
 * @param <ReqT> Type of the Request Message
 * @param <RespT> Type of the Response Message
 */
public final class BlockingClientCall<ReqT, RespT> {

  private static final Logger logger = Logger.getLogger(BlockingClientCall.class.getName());

  private final BlockingQueue<RespT> buffer;
  private final ClientCall<ReqT, RespT> call;

  private final ThreadSafeThreadlessExecutor executor;

  private boolean writeClosed;
  private Status closedStatus;

  BlockingClientCall(ClientCall<ReqT, RespT> call, ThreadSafeThreadlessExecutor executor) {
    this.call = call;
    this.executor = executor;
    buffer = new ArrayBlockingQueue<>(1);
  }

  /**
   * Wait if necessary for a value to be available from the server. If there is an available value
   * return it immediately, if the stream is closed return a null. Otherwise, wait for a value to be
   * available or the stream to be closed
   *
   * @return value from server or null if stream has been closed
   * @throws io.grpc.StatusRuntimeException If the stream has closed in an error state
   */
  public RespT read() throws InterruptedException {
    try {
      return read(true, Integer.MAX_VALUE, TimeUnit.DAYS);
    } catch (TimeoutException e) {
      throw new AssertionError("Timed out even though we wanted to wait forever."
              + "  This implies that time was advanced over 200 years.");
    }
  }

  /**
   * Wait with timeout, if necessary, for a value to be available from the server. If there is an
   * available value, return it immediately.  If the stream is closed return a null. Otherwise, wait
   * for a value to be available, the stream to be closed or the timeout to expire.
   *
   * @param timeout how long to wait before giving up.  Values &lt;= 0 are no wait
   * @param unit a TimeUnit determining how to interpret the timeout parameter
   * @return value from server or null (if stream has been closed)
   * @throws TimeoutException if no read becomes ready before the specified timeout expires
   * @throws io.grpc.StatusRuntimeException If the stream has closed in an error state
   */
  public RespT read(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
    return read(false, timeout, unit);
  }

  private RespT read(boolean waitForever, long timeout, TimeUnit unit)
      throws InterruptedException, TimeoutException {
    long start = System.nanoTime();
    long end = start + unit.toNanos(timeout);

    executor.drain();

    RespT bufferedValue;
    while ((bufferedValue = buffer.poll()) == null && closedStatus == null) {
      Predicate<BlockingClientCall<ReqT, RespT>> predicate = BlockingClientCall::skipWaitingForRead;
      waitAndDrainExecutorOrTimeout(waitForever, end, predicate, this);
    }

    if (logger.isLoggable(Level.FINER)) {
      logger.finer("Client Blocking read had value:  " + bufferedValue);
    }

    if (bufferedValue != null) {
      call.request(1);
    } else if (closedStatus != null && !closedStatus.isOk()) {
      throw closedStatus.asRuntimeException();
    }

    return bufferedValue;
  }

  boolean skipWaitingForRead() {
    return closedStatus != null || !buffer.isEmpty();
  }

  /**
   * Wait for a value to be available from the server. If there is an
   * available value, return true immediately.  If the stream was closed with Status.OK, return
   * false.  If the stream was closed with an error status, throw a StatusException. Otherwise, wait
   * for a value to be available or the stream to be closed.
   *
   * @return True when there is a value to read.  Return false if stream closed cleanly.
   * @throws io.grpc.StatusRuntimeException If the stream was closed in an error state
   */
  public boolean hasNext() throws InterruptedException {
    executor.drain();

    if (!buffer.isEmpty()) {
      return true;
    }

    if (closedStatus != null) {
      if (!closedStatus.isOk()) {
        throw closedStatus.asRuntimeException();
      }
      return false;
    }

    while (!isReadReady() && closedStatus == null) {
      executor.waitAndDrain();
    }


    if (closedStatus != null && !closedStatus.isOk()) {
      throw closedStatus.asRuntimeException();
    }

    return !buffer.isEmpty();
  }

  /**
   * Send a value to the stream for sending to server, wait if necessary for the grpc stream to be
   * ready.
   * <br>
   * If write is not legal at the time of call, immediately returns false
   * <br><br>
   * <b>NOTE:  </b>This method will return as soon as it passes the request to the grpc stream
   * layer. It will not block while the message is being sent on the wire and returning true does
   * not guarantee that the server gets the message.
   * <p>
   * <b>WARNING:  </b>Doing only writes without reads can lead to deadlocks as a result of flow
   * control.  Furthermore, the server closing the stream will only be identified after the last
   * sent value is read.
   * </p>
   *
   * @param request Message to send to the server
   * @return true if the request is sent to stream, false if skipped
   * @throws io.grpc.StatusRuntimeException If the stream has closed in an error state
   */
  public boolean write(ReqT request) throws InterruptedException {
    try {
      return write(true, request, Integer.MAX_VALUE, TimeUnit.DAYS);
    } catch (TimeoutException e) {
      throw new RuntimeException(e); // should never happen
    }
  }

  /**
   * Send a value to the stream for sending to server, wait if necessary for the grpc stream to be
   * ready up to specified timeout.
   * <br>
   * If write is not legal at the time of call, immediately returns false
   * <br><br>
   * <p>
   * <b>NOTE:  </b>This method will return as soon as it passes the request to the grpc stream
   * layer. It will not block while the message is being sent on the wire and returning true does
   * not guarantee that the server gets the message.
   * </p>
   * <p>
   * <b>WARNING:  </b>Doing only writes without reads can lead to deadlocks as a result of flow
   * control.  Furthermore, the server closing the stream will only be identified after the last
   * sent value is read.
   * </p>
   *
   * @param request Message to send to the server
   * @param timeout How long to wait before giving up.  Values &lt;= 0 are no wait
   * @param unit A TimeUnit determining how to interpret the timeout parameter
   * @return true if the request is sent to stream, false if skipped
   * @throws TimeoutException if write does not become ready before the specified timeout expires
   * @throws io.grpc.StatusRuntimeException If the stream has closed in an error state
   */
  public boolean write(ReqT request, long timeout, TimeUnit unit)
      throws InterruptedException, TimeoutException {
    return write(false, request, timeout, unit);
  }

  public boolean write(boolean waitForever, ReqT request, long timeout, TimeUnit unit)
      throws InterruptedException, TimeoutException {

    if (writeClosed) {
      throw new IllegalStateException("Writes cannot be done after calling halfClose or cancel");
    }

    boolean writeDone = false;
    long start = System.nanoTime();
    long end = start + unit.toNanos(timeout);

    while (!writeDone && isWriteLegal()) {
      if (call.isReady()) {
        call.sendMessage(request);
        writeDone = true;
      } else {
        Predicate<ClientCall<ReqT, RespT>> predicate = ClientCall::isReady;
        waitAndDrainExecutorOrTimeout(waitForever, end, predicate, call);
      }
    }

    if (writeDone || getClosedStatus() == null) {
      return writeDone;
    }

    if (getClosedStatus().isOk()) {
      return false;
    } else {
      // Propagate any errors returned from the server
      throw getClosedStatus().asRuntimeException();
    }
  }

  /**
   * Cancel stream and stop any further writes.  Note that some reads that are in flight may still
   * happen after the cancel.
   *
   * @param message if not {@code null}, will appear as the description of the CANCELLED status
   * @param cause if not {@code null}, will appear as the cause of the CANCELLED status
   */
  public void cancel(String message, Throwable cause) {
    call.cancel(message, cause);
    writeClosed = true;
    executor.add(NoOpRunnable.INSTANCE);
  }

  /**
   * Indicate that no more writes will be done and the stream will be closed from the client side.
   * <br>
   * See {@link ClientCall#halfClose()}
   */
  public void halfClose() {
    boolean origWriteClosed = writeClosed;
    writeClosed = true;

    if (origWriteClosed) {
      throw new IllegalStateException("halfClose cannot be called after stream terminated");
    } else if (getClosedStatus() == null) {
      call.halfClose();
    }

    executor.add(NoOpRunnable.INSTANCE);
  }

  /**
   * Status that server sent when closing channel from its side.
   *
   * @return null if stream not closed by server, otherwise Status sent by server
   */
  Status getClosedStatus() {
    drainQuietly();
    return closedStatus;
  }

  /**
   * Check for whether some action is ready.
   *
   * @return True if legal to write and writeOrRead can run without blocking
   */
  @VisibleForTesting
  boolean isEitherReadOrWriteReady() {
    return (isWriteLegal() && isWriteReady()) || isReadReady();
  }

  /**
   * Check whether there are any values waiting to be read.
   *
   * @return true if read will not block
   */
  @VisibleForTesting
  boolean isReadReady() {
    drainQuietly();

    return !buffer.isEmpty();
  }

  /**
   * Check that write hasn't been marked complete and stream is ready to receive a write (so will
   * not block).
   *
   * @return true if legal to write and write will not block
   */
  @VisibleForTesting
  boolean isWriteReady() {
    drainQuietly();

    return isWriteLegal() && call.isReady();
  }

  /**
   * Check whether we'll ever be able to do writes or should terminate.
   * @return True if writes haven't been closed and the server hasn't closed the stream
   */
  private boolean isWriteLegal() {
    return !writeClosed && closedStatus == null;
  }

  /**
   * Calls this.executor's waitAndDrain or waitAndDrainWithTimeout.
   *
   * @param end The time in nanoseconds to trigger timeout
   * @throws TimeoutException If no events become available on the queue within the specified
   *     timeout or processing them exceeds the timeout
   */
  private <T> void waitAndDrainExecutorOrTimeout(boolean waitForever, long end,
      Predicate<T> predicate, T testTarget)
      throws InterruptedException, TimeoutException {
    if (!waitForever && (end - System.nanoTime() <= 0)) {
      throw new TimeoutException();
    }
    // Let threadless executor do stuff until there is something for us to check
    executor.waitAndDrainWithTimeout(waitForever, end, predicate, testTarget);

    if (!waitForever && end - System.nanoTime() <= 0) {
      throw new TimeoutException();
    }
  }

  ClientCall.Listener<RespT> getListener() {
    return new QueuingListener();
  }

  private void drainQuietly() {
    try {
      executor.drain();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * wake up write if it is blocked.
   */
  void handleReady() {
    executor.add(NoOpRunnable.INSTANCE);
  }

  private final class QueuingListener extends ClientCall.Listener<RespT> {
    @Override
    public void onMessage(RespT value) {
      Preconditions.checkState(closedStatus == null, "ClientCall already closed");
      buffer.add(value);
      executor.add(NoOpRunnable.INSTANCE);
    }

    @Override
    public void onClose(Status status, Metadata trailers) {
      Preconditions.checkState(closedStatus == null, "ClientCall already closed");
      closedStatus = status;
      executor.add(NoOpRunnable.INSTANCE);
    }

    @Override
    public void onReady() {
      handleReady();
    }
  }

  private static class NoOpRunnable implements Runnable {

    static NoOpRunnable INSTANCE = new NoOpRunnable();

    @Override
    public void run() {
      // do nothing
    }
  }

}
