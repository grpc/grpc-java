/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.autosharding;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.cloud.autosharding.v1.AssignmentAck;
import com.google.cloud.autosharding.v1.AssignmentChunk;
import com.google.cloud.autosharding.v1.AutoshardingServiceGrpc;
import com.google.cloud.autosharding.v1.InitialClientConfig;
import com.google.cloud.autosharding.v1.WatchShardingAssignmentRequest;
import com.google.cloud.autosharding.v1.WatchShardingAssignmentResponse;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Streams assignments from the sharding service over {@code WatchShardingAssignment}: reassembles,
 * validates and acknowledges them, and hands valid {@link Assignment}s to an
 * {@link AssignmentWatcher}. See gRFC A119, "AutoshardingClient".
 *
 * <p>Not thread-safe: every method must be called on the {@link SynchronizationContext} given to
 * the constructor, which is also where the watcher is called.
 */
@NotThreadSafe
final class AutoshardingClient {
  private static final Logger logger = Logger.getLogger(AutoshardingClient.class.getName());

  /** The limit {@code autosharding.proto} places on {@code AssignmentAck.error_message}. */
  private static final int MAX_ERROR_MESSAGE_CODE_POINTS = 512;

  /**
   * Receives the state this client reports: valid assignments, or an error while it has none.
   * Both callbacks are invoked on the {@link SynchronizationContext}.
   */
  interface AssignmentWatcher {
    /** Called with a newly accepted assignment. */
    void onAssignment(Assignment assignment);

    /**
     * Called when this client has no valid assignment to offer: its stream failed, the sharding
     * service sent an assignment with no usable slice, or the initial assignment timer fired.
     * Never called once this client has reported an assignment, since the load balancer keeps
     * using that one.
     */
    void onError(Status error);
  }

  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timerService;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final Stopwatch retryStopwatch;
  private final AssignmentWatcher watcher;
  private final String clientUuid;
  private final AutoshardingServiceGrpc.AutoshardingServiceStub stub;
  private final String target;
  private final long initialAssignmentTimeoutNanos;

  /**
   * Generation of the most recent accepted assignment, sent to the server so that it can skip
   * resending one the client already has. Zero, which the proto reads as unset, until an
   * assignment is accepted; only a greater generation is accepted.
   */
  private long latestGeneration;

  @Nullable private BackoffPolicy retryBackoffPolicy;
  @Nullable private ScheduledHandle retryTimer;
  @Nullable private ScheduledHandle initialAssignmentTimer;
  @Nullable private AutoshardingStream stream;
  private boolean shutdown;

  /**
   * Constructs an {@link AutoshardingClient}. No stream is created until {@link #start()}.
   *
   * @param clientUuid a UUID generated once by the parent load balancer and reused across all
   *     stream restarts
   * @param syncContext the context on which all state is mutated and callbacks are delivered
   * @param timerService used to schedule stream retries and the initial assignment timer
   * @param backoffPolicyProvider supplies the exponential backoff sequence for stream retries
   * @param stopwatchSupplier supplies the stopwatch measuring time spent in a stream attempt
   * @param channel the channel to the sharding service, created via the "Channel Factory" and
   *     owned by the parent load balancer
   * @param target the autosharding target, with any {@code %s} token already substituted
   * @param initialAssignmentTimeoutNanos how long to wait for the first assignment before
   *     reporting an error
   * @param watcher receives validated assignments and errors
   */
  AutoshardingClient(
      String clientUuid,
      SynchronizationContext syncContext,
      ScheduledExecutorService timerService,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier,
      Channel channel,
      String target,
      long initialAssignmentTimeoutNanos,
      AssignmentWatcher watcher) {
    this.clientUuid = checkNotNull(clientUuid, "clientUuid");
    this.syncContext = checkNotNull(syncContext, "syncContext");
    this.timerService = checkNotNull(timerService, "timerService");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    this.retryStopwatch = checkNotNull(stopwatchSupplier, "stopwatchSupplier").get();
    // wait_for_ready keeps the stream pending through transient connectivity failures instead
    // of failing it, which recovers faster than applying backoff around stream creation.
    this.stub =
        AutoshardingServiceGrpc.newStub(checkNotNull(channel, "channel")).withWaitForReady();
    this.target = checkNotNull(target, "target");
    this.initialAssignmentTimeoutNanos = initialAssignmentTimeoutNanos;
    this.watcher = checkNotNull(watcher, "watcher");
  }

  /**
   * Starts the initial assignment timer and opens the {@code WatchShardingAssignment} stream.
   * Call once, on the sync context.
   */
  void start() {
    syncContext.throwIfNotInThisSynchronizationContext();
    checkState(stream == null, "already started");
    // Armed before the stream opens, so that an assignment delivered right away cancels it.
    initialAssignmentTimer =
        syncContext.schedule(
            this::onInitialAssignmentTimeout,
            initialAssignmentTimeoutNanos,
            TimeUnit.NANOSECONDS,
            timerService);
    startStream();
  }

  /**
   * Cancels the stream, any pending retry and the initial assignment timer. The channel is not
   * touched: the parent load balancer holds its lease.
   */
  void shutdown() {
    syncContext.throwIfNotInThisSynchronizationContext();
    if (shutdown) {
      return;
    }
    shutdown = true;
    cancelRetryTimer();
    cancelInitialAssignmentTimer();
    if (stream != null) {
      stream.close(Status.CANCELLED.withDescription("AutoshardingClient shutdown"));
      stream = null;
    }
  }

  /** Returns the target this client asks the sharding service for. */
  String getTarget() {
    return target;
  }

  @VisibleForTesting
  long getLatestGeneration() {
    return latestGeneration;
  }

  private void onInitialAssignmentTimeout() {
    initialAssignmentTimer = null;
    reportError(
        Status.UNAVAILABLE.withDescription(
            "autosharding: timed out waiting for the initial assignment from the sharding"
                + " service"));
  }

  private void cancelInitialAssignmentTimer() {
    if (initialAssignmentTimer != null) {
      initialAssignmentTimer.cancel();
      initialAssignmentTimer = null;
    }
  }

  /**
   * Reports an error unless this client has already reported an assignment, which gRFC A119 has
   * the load balancer keep using in preference to any later error.
   */
  private void reportError(Status error) {
    if (shutdown || latestGeneration > 0) {
      return;
    }
    watcher.onError(error);
  }

  private void startStream() {
    if (shutdown) {
      return;
    }
    checkState(stream == null, "previous stream has not been cleared yet");
    retryStopwatch.reset().start();
    stream = new AutoshardingStream();
    stream.start();
  }

  private void cancelRetryTimer() {
    if (retryTimer != null) {
      if (retryTimer.isPending()) {
        retryTimer.cancel();
      }
      retryTimer = null;
    }
  }

  /**
   * Schedules the next stream attempt. Backoff only applies to streams that closed without
   * delivering a good logical assignment; one that did reconnects right away and the sequence
   * starts over on the next failure.
   */
  private void scheduleRetry(boolean receivedGoodAssignment) {
    if (shutdown) {
      return;
    }
    long delayNanos;
    if (receivedGoodAssignment) {
      retryBackoffPolicy = null;
      delayNanos = 0;
    } else {
      if (retryBackoffPolicy == null) {
        retryBackoffPolicy = backoffPolicyProvider.get();
      }
      // The backoff sequence bounds the interval between consecutive stream starts, so the actual
      // delay is reduced by however long the previous attempt lasted.
      delayNanos =
          Math.max(
              0,
              retryBackoffPolicy.nextBackoffNanos()
                  - retryStopwatch.elapsed(TimeUnit.NANOSECONDS));
    }
    // The retry always goes through the timer service, even when no delay remains, so that a
    // channel failing calls synchronously cannot drive unbounded recursion between startStream()
    // and handleStreamClosed().
    retryTimer =
        syncContext.schedule(this::startStream, delayNanos, TimeUnit.NANOSECONDS, timerService);
  }

  /**
   * A single {@code WatchShardingAssignment} stream. Everything here runs on the sync context:
   * the stream's events arrive on gRPC threads at {@link ResponseObserver}, which hands each one
   * over.
   */
  private final class AutoshardingStream {

    /**
     * Chunks received since the last {@code AssignmentMetadata}. A chunk's slices reference
     * endpoint indices into the list combined across all chunks, so chunks cannot be used until
     * the assignment is terminated by an {@code AssignmentMetadata} message.
     */
    private final List<AssignmentChunk> bufferedChunks = new ArrayList<>();

    /** The sending half of the stream, set by {@link ResponseObserver#beforeStart}. */
    private ClientCallStreamObserver<WatchShardingAssignmentRequest> requestStream;

    /**
     * Whether this stream delivered an assignment the load balancer could use, the only thing
     * that resets the retry backoff. Stale assignments and ones with no usable slice don't count.
     */
    private boolean receivedGoodAssignment;
    private boolean closed;

    void start() {
      stub.watchShardingAssignment(new ResponseObserver());
      // The call is started inside the above, so the config cannot go out from beforeStart().
      sendInitialClientConfig();
    }

    private void sendInitialClientConfig() {
      WatchShardingAssignmentRequest request =
          WatchShardingAssignmentRequest.newBuilder()
              .setInitialClientConfig(
                  InitialClientConfig.newBuilder()
                      .setTarget(target)
                      .setClientUuid(clientUuid)
                      .setLatestGeneration(latestGeneration))
              .build();
      requestStream.onNext(request);
    }

    private void handleResponse(WatchShardingAssignmentResponse response) {
      if (closed) {
        return;
      }
      if (response.hasChunk()) {
        bufferedChunks.add(response.getChunk());
      } else if (response.hasMetadata()) {
        handleAssignmentComplete(response.getMetadata().getGeneration());
      } else if (!response.hasConfig()) {
        logger.log(Level.FINE, "Ignoring autosharding response with no field set");
      }
      // LoadReportingConfig is intentionally ignored; load reporting is not yet supported.
    }

    /**
     * Validates and acknowledges the buffered chunks terminated by an {@code AssignmentMetadata}
     * message, following the outcome table in gRFC A119, "Handling assignments from the
     * Autosharding server". Every assignment is acknowledged, but only a newer one with at least
     * one usable slice reaches the load balancer.
     */
    private void handleAssignmentComplete(long generation) {
      List<AssignmentChunk> chunks = new ArrayList<>(bufferedChunks);
      bufferedChunks.clear();

      // Generations are monotonically increasing, so anything not above the latest accepted one
      // is stale; before anything is accepted that means zero or below. It is
      // still acknowledged, so that the server does not wait on a reply that never comes.
      if (generation <= latestGeneration) {
        String error =
            String.format(
                "stale generation %s: not greater than the latest generation %s",
                generation, latestGeneration);
        logger.log(Level.FINE, "Dropping autosharding assignment: {0}", error);
        sendAck(generation, false, error);
        return;
      }

      AssignmentParser.Result result = AssignmentParser.parse(chunks);
      if (result.assignment == null) {
        logger.log(
            Level.WARNING,
            "Rejecting autosharding assignment with generation {0}, no usable slices: {1}",
            new Object[] {generation, result.errorMessage});
        sendAck(generation, false, result.errorMessage);
        reportError(
            Status.UNAVAILABLE.withDescription(
                "autosharding: no usable slices in assignment with generation "
                    + generation
                    + ": "
                    + result.errorMessage));
        return;
      }

      if (result.errorMessage != null) {
        logger.log(
            Level.WARNING,
            "Accepting autosharding assignment with generation {0} after dropping slices: {1}",
            new Object[] {generation, result.errorMessage});
      }
      sendAck(generation, true, result.errorMessage);
      latestGeneration = generation;
      receivedGoodAssignment = true;
      cancelInitialAssignmentTimer();
      watcher.onAssignment(result.assignment);
    }

    private void sendAck(long generation, boolean accepted, @Nullable String errorMessage) {
      AssignmentAck.Builder ack =
          AssignmentAck.newBuilder().setGeneration(generation).setAccepted(accepted);
      if (errorMessage != null) {
        ack.setErrorMessage(truncateErrorMessage(errorMessage));
      }
      requestStream.onNext(
          WatchShardingAssignmentRequest.newBuilder().setAssignmentAck(ack).build());
    }

    private void handleStreamClosed(Status status) {
      if (closed) {
        return;
      }
      closed = true;
      logger.log(
          Level.FINE,
          "Autosharding stream closed with status {0}: {1}",
          new Object[] {status.getCode(), status.getDescription()});
      bufferedChunks.clear();
      if (stream == this) {
        stream = null;
        reportError(
            Status.UNAVAILABLE
                .withDescription(
                    "autosharding: sharding service stream failed: "
                        + status.getCode()
                        + ": "
                        + status.getDescription())
                .withCause(status.getCause()));
        scheduleRetry(receivedGoodAssignment);
      }
    }

    /** Cancels the stream without scheduling a retry. Used when the client shuts down. */
    void close(Status status) {
      if (closed) {
        return;
      }
      closed = true;
      bufferedChunks.clear();
      requestStream.cancel(status.getDescription(), status.getCause());
    }

    /** Receives the stream's events on gRPC threads and hands each one to the sync context. */
    private final class ResponseObserver
        implements ClientResponseObserver<
            WatchShardingAssignmentRequest, WatchShardingAssignmentResponse> {
      @Override
      public void beforeStart(
          ClientCallStreamObserver<WatchShardingAssignmentRequest> requestStream) {
        // Called from inside start(), so still on the sync context.
        AutoshardingStream.this.requestStream = requestStream;
      }

      @Override
      public void onNext(WatchShardingAssignmentResponse response) {
        syncContext.execute(() -> handleResponse(response));
      }

      @Override
      public void onError(Throwable t) {
        syncContext.execute(() -> handleStreamClosed(Status.fromThrowable(t)));
      }

      @Override
      public void onCompleted() {
        syncContext.execute(
            () ->
                handleStreamClosed(
                    Status.UNAVAILABLE.withDescription("autosharding stream closed by server")));
      }
    }
  }

  /**
   * Cuts {@code message} to {@link #MAX_ERROR_MESSAGE_CODE_POINTS} code points. A backstop only:
   * {@link AssignmentParser} already sizes its messages to fit.
   */
  private static String truncateErrorMessage(String message) {
    // A string never has more code points than chars, so this settles the common case without
    // walking it.
    if (message.length() <= MAX_ERROR_MESSAGE_CODE_POINTS) {
      return message;
    }
    if (message.codePointCount(0, message.length()) <= MAX_ERROR_MESSAGE_CODE_POINTS) {
      return message;
    }
    // Cutting on a code point boundary rather than a char boundary keeps a surrogate pair from
    // being split into an unpaired surrogate, which does not survive UTF-8 encoding.
    return message.substring(0, message.offsetByCodePoints(0, MAX_ERROR_MESSAGE_CODE_POINTS));
  }
}
