/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.s2a.internal.handshaker;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Verify.verify;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.concurrent.NotThreadSafe;

/** Reads and writes messages to and from the S2A. */
@NotThreadSafe
public class S2AStub implements AutoCloseable {
  private static final Logger logger = Logger.getLogger(S2AStub.class.getName());
  private static final long HANDSHAKE_RPC_DEADLINE_SECS = 20;
  private final StreamObserver<SessionResp> reader = new Reader();
  private final BlockingQueue<Result> responses = new ArrayBlockingQueue<>(10);
  private S2AServiceGrpc.S2AServiceStub serviceStub;
  private StreamObserver<SessionReq> writer;
  private boolean doneReading = false;
  private boolean doneWriting = false;
  private boolean isClosed = false;

  static S2AStub newInstance(S2AServiceGrpc.S2AServiceStub serviceStub) {
    checkNotNull(serviceStub);
    return new S2AStub(serviceStub);
  }

  @VisibleForTesting
  static S2AStub newInstanceForTesting(StreamObserver<SessionReq> writer) {
    checkNotNull(writer);
    return new S2AStub(writer);
  }

  private S2AStub(S2AServiceGrpc.S2AServiceStub serviceStub) {
    this.serviceStub = serviceStub;
  }

  private S2AStub(StreamObserver<SessionReq> writer) {
    this.writer = writer;
  }

  @VisibleForTesting
  StreamObserver<SessionResp> getReader() {
    return reader;
  }

  @VisibleForTesting
  BlockingQueue<Result> getResponses() {
    return responses;
  }

  /**
   * Sends a request and returns the response. Caller must wait until this method executes prior to
   * calling it again. If this method throws {@code ConnectionClosedException}, then it should not
   * be called again, and both {@code reader} and {@code writer} are closed.
   *
   * @param req the {@code SessionReq} message to be sent to the S2A server.
   * @return the {@code SessionResp} message received from the S2A server.
   * @throws ConnectionClosedException if {@code reader} or {@code writer} calls their {@code
   *     onCompleted} method.
   * @throws IOException if an unexpected response is received, or if the {@code reader} or {@code
   *     writer} calls their {@code onError} method.
   */
  @SuppressWarnings("CheckReturnValue")
  public SessionResp send(SessionReq req) throws IOException, InterruptedException {
    if (doneWriting && doneReading) {
      logger.log(Level.INFO, "Stream to the S2A is closed.");
      throw new ConnectionClosedException("Stream to the S2A is closed.");
    }
    createWriterIfNull();
    if (!responses.isEmpty()) {
      IOException exception = null;
      try {
        responses.take().getResultOrThrow();
      } catch (IOException e) {
        exception = e;
      }
      responses.clear();
      if (exception != null) {
        throw new IOException(
            "Received an unexpected response from a host at the S2A's address. The S2A might be"
                + " unavailable.", exception);
      } else {
        throw new IOException("Received an unexpected response from a host at the S2A's address.");
      }
    }
    try {
      writer.onNext(req);
    } catch (RuntimeException e) {
      writer.onError(e);
      responses.add(Result.createWithThrowable(e));
    }
    try {
      return responses.take().getResultOrThrow();
    } catch (ConnectionClosedException e) {
      // A ConnectionClosedException is thrown by getResultOrThrow when reader calls its
      // onCompleted method. The close method is called to also close the writer, and then the
      // ConnectionClosedException is re-thrown in order to indicate to the caller that send
      // should not be called again.
      close();
      throw e;
    }
  }

  @Override
  public void close() {
    if (doneWriting && doneReading) {
      return;
    }
    verify(!doneWriting);
    doneReading = true;
    doneWriting = true;
    if (writer != null) {
      writer.onCompleted();
    }
    isClosed = true;
  }

  public boolean isClosed() {
    return isClosed;
  }

  /** Create a new writer if the writer is null. */
  private void createWriterIfNull() {
    if (writer == null) {
      writer =
          serviceStub
              .withWaitForReady()
              .withDeadlineAfter(HANDSHAKE_RPC_DEADLINE_SECS, SECONDS)
              .setUpSession(reader);
    }
  }

  private class Reader implements StreamObserver<SessionResp> {
    /**
     * Places a {@code SessionResp} message in the {@code responses} queue, or an {@code
     * IOException} if reading is complete.
     *
     * @param resp the {@code SessionResp} message received from the S2A handshaker module.
     */
    @Override
    public void onNext(SessionResp resp) {
      verify(!doneReading);
      responses.add(Result.createWithResponse(resp));
    }

    /**
     * Places a {@code Throwable} in the {@code responses} queue.
     *
     * @param t the {@code Throwable} caught when reading the stream to the S2A handshaker module.
     */
    @Override
    public void onError(Throwable t) {
      responses.add(Result.createWithThrowable(t));
    }

    /**
     * Sets {@code doneReading} to true, and places a {@code ConnectionClosedException} in the
     * {@code responses} queue.
     */
    @Override
    public void onCompleted() {
      logger.log(Level.INFO, "Reading from the S2A is complete.");
      doneReading = true;
      responses.add(
          Result.createWithThrowable(
              new ConnectionClosedException("Reading from the S2A is complete.")));
    }
  }

  private static final class Result {
    private final Optional<SessionResp> response;
    private final Optional<Throwable> throwable;

    static Result createWithResponse(SessionResp response) {
      return new Result(Optional.of(response), Optional.empty());
    }

    static Result createWithThrowable(Throwable throwable) {
      return new Result(Optional.empty(), Optional.of(throwable));
    }

    private Result(Optional<SessionResp> response, Optional<Throwable> throwable) {
      checkArgument(response.isPresent() != throwable.isPresent());
      this.response = response;
      this.throwable = throwable;
    }

    /** Throws {@code throwable} if present, and returns {@code response} otherwise. */
    SessionResp getResultOrThrow() throws IOException {
      if (throwable.isPresent()) {
        if (throwable.get() instanceof ConnectionClosedException) {
          ConnectionClosedException exception = (ConnectionClosedException) throwable.get();
          throw exception;
        } else {
          throw new IOException(throwable.get());
        }
      }
      verify(response.isPresent());
      return response.get();
    }
  }
}