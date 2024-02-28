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
import io.grpc.ExperimentalApi;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
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
 * Wraps a ClientCall and converts from async communication to the sync paradigm used by the
 * various blocking stream methods in {@link ClientCalls} which are used by the generated stubs.
 *
 * <p>Supports separate threads for reads and writes, but only 1 of each
 *
 * <p>Read methods consist of:
 * <ul>
 *   <li>{@link #read()}
 *   <li>{@link #read(long timeout, TimeUnit unit)}
 *   <li>{@link #hasNext()}
 *   <li>{@link #cancel(String, Throwable)}
 * </ul>
 *
 * <p>Write methods consist of:
 * <ul>
 *   <li>{@link #write(Object)}
 *   <li>{@link #write(Object, long timeout, TimeUnit unit)}
 *   <li>{@link #halfClose()}
 * </ul>
 *
 * @param <ReqT> Type of the Request Message
 * @param <RespT> Type of the Response Message
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
public final class BlockingClientCall<ReqT, RespT> {

  private static final Logger logger = Logger.getLogger(BlockingClientCall.class.getName());

  private final BlockingQueue<RespT> buffer;
  private final ClientCall<ReqT, RespT> call;

  private final ThreadSafeThreadlessExecutor executor;

  private boolean writeClosed;
  private volatile Status closedStatus; // null if not closed

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
   * @throws StatusException If the stream has closed in an error state
   */
  public RespT read() throws InterruptedException, StatusException {
    try {
      return read(true, 0, TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      throw new AssertionError("should never happen", e);
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
   * @throws StatusException If the stream has closed in an error state
   */
  public RespT read(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException,
      StatusException {
    return read(false, timeout, unit);
  }

  private RespT read(boolean waitForever, long timeout, TimeUnit unit)
      throws InterruptedException, TimeoutException, StatusException {
    long start = System.nanoTime();
    long end = start + unit.toNanos(timeout);

    Predicate<BlockingClientCall<ReqT, RespT>> predicate = BlockingClientCall::skipWaitingForRead;
    executor.waitAndDrainWithTimeout(waitForever, end, predicate, this);
    RespT bufferedValue = buffer.poll();

    if (logger.isLoggable(Level.FINER)) {
      logger.finer("Client Blocking read had value:  " + bufferedValue);
    }

    Status currentClosedStatus;
    if (bufferedValue != null) {
      call.request(1);
      return bufferedValue;
    } else if ((currentClosedStatus = closedStatus) == null) {
      throw new IllegalStateException(
          "The message disappeared... are you reading from multiple threads?");
    } else if (!currentClosedStatus.isOk()) {
      throw currentClosedStatus.asException();
    } else {
      return null;
    }
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
   * @throws StatusException If the stream was closed in an error state
   */
  public boolean hasNext() throws InterruptedException, StatusException {
    executor.waitAndDrain((x) -> !x.buffer.isEmpty() || x.closedStatus != null, this);

    Status currentClosedStatus = closedStatus;
    if (currentClosedStatus != null && !currentClosedStatus.isOk()) {
      throw currentClosedStatus.asException();
    }

    return !buffer.isEmpty();
  }

  /**
   * Send a value to the stream for sending to server, wait if necessary for the grpc stream to be
   * ready.
   *
   * <p>If write is not legal at the time of call, immediately returns false
   *
   * <p><br><b>NOTE: </b>This method will return as soon as it passes the request to the grpc stream
   * layer. It will not block while the message is being sent on the wire and returning true does
   * not guarantee that the server gets the message.
   *
   * <p><br><b>WARNING: </b>Doing only writes without reads can lead to deadlocks.  This is because
   * flow control, imposed by networks to protect intermediary routers and endpoints that are
   * operating under resource constraints, requires reads to be done in order to progress writes.
   * Furthermore, the server closing the stream will only be identified after
   * the last sent value is read.
   *
   * @param request Message to send to the server
   * @return true if the request is sent to stream, false if skipped
   * @throws StatusException If the stream has closed in an error state
   */
  public boolean write(ReqT request) throws InterruptedException, StatusException {
    try {
      return write(true, request, Integer.MAX_VALUE, TimeUnit.DAYS);
    } catch (TimeoutException e) {
      throw new RuntimeException(e); // should never happen
    }
  }

  /**
   * Send a value to the stream for sending to server, wait if necessary for the grpc stream to be
   * ready up to specified timeout.
   *
   * <p>If write is not legal at the time of call, immediately returns false
   *
   * <p><br><b>NOTE: </b>This method will return as soon as it passes the request to the grpc stream
   * layer. It will not block while the message is being sent on the wire and returning true does
   * not guarantee that the server gets the message.
   *
   * <p><br><b>WARNING: </b>Doing only writes without reads can lead to deadlocks as a result of
   * flow control.  Furthermore, the server closing the stream will only be identified after the
   * last sent value is read.
   *
   * @param request Message to send to the server
   * @param timeout How long to wait before giving up.  Values &lt;= 0 are no wait
   * @param unit A TimeUnit determining how to interpret the timeout parameter
   * @return true if the request is sent to stream, false if skipped
   * @throws TimeoutException if write does not become ready before the specified timeout expires
   * @throws StatusException If the stream has closed in an error state
   */
  public boolean write(ReqT request, long timeout, TimeUnit unit)
      throws InterruptedException, TimeoutException, StatusException {
    return write(false, request, timeout, unit);
  }

  private boolean write(boolean waitForever, ReqT request, long timeout, TimeUnit unit)
      throws InterruptedException, TimeoutException, StatusException {

    if (writeClosed) {
      throw new IllegalStateException("Writes cannot be done after calling halfClose or cancel");
    }

    long end = System.nanoTime() + unit.toNanos(timeout);

    Predicate<BlockingClientCall<ReqT, RespT>> predicate =
        (x) -> x.call.isReady() || x.closedStatus != null;
    executor.waitAndDrainWithTimeout(waitForever, end, predicate, this);
    Status savedClosedStatus = closedStatus;
    if (savedClosedStatus == null) {
      call.sendMessage(request);
      return true;
    } else if (savedClosedStatus.isOk()) {
      return false;
    } else {
      // Propagate any errors returned from the server
      throw savedClosedStatus.asException();
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
  }

  /**
   * Indicate that no more writes will be done and the stream will be closed from the client side.
   *
   * @see ClientCall#halfClose()
   */
  public void halfClose() {
    if (writeClosed) {
      throw new IllegalStateException(
          "halfClose cannot be called after already half closed or cancelled");
    }

    writeClosed = true;
    call.halfClose();
  }

  /**
   * Status that server sent when closing channel from its side.
   *
   * @return null if stream not closed by server, otherwise Status sent by server
   */
  @VisibleForTesting
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

  private final class QueuingListener extends ClientCall.Listener<RespT> {
    @Override
    public void onMessage(RespT value) {
      Preconditions.checkState(closedStatus == null, "ClientCall already closed");
      buffer.add(value);
    }

    @Override
    public void onClose(Status status, Metadata trailers) {
      Preconditions.checkState(closedStatus == null, "ClientCall already closed");
      closedStatus = status;
    }
  }

}
