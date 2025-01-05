/*
 * Copyright 2017 The gRPC Authors
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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.Iterables;
import io.grpc.Attributes;
import io.grpc.ClientStreamTracer;
import io.grpc.Compressor;
import io.grpc.Deadline;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.ClientStreamListener.RpcProgress;
import java.io.InputStream;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.CheckForNull;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/** A logical {@link ClientStream} that is retriable. */
abstract class RetriableStream<ReqT> implements ClientStream {
  @VisibleForTesting
  static final Metadata.Key<String> GRPC_PREVIOUS_RPC_ATTEMPTS =
      Metadata.Key.of("grpc-previous-rpc-attempts", Metadata.ASCII_STRING_MARSHALLER);

  @VisibleForTesting
  static final Metadata.Key<String> GRPC_RETRY_PUSHBACK_MS =
      Metadata.Key.of("grpc-retry-pushback-ms", Metadata.ASCII_STRING_MARSHALLER);

  private static final Status CANCELLED_BECAUSE_COMMITTED =
      Status.CANCELLED.withDescription("Stream thrown away because RetriableStream committed");

  private final MethodDescriptor<ReqT, ?> method;
  private final Executor callExecutor;
  private final Executor listenerSerializeExecutor = new SynchronizationContext(
      new UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw Status.fromThrowable(e)
              .withDescription("Uncaught exception in the SynchronizationContext. Re-thrown.")
              .asRuntimeException();
        }
      }
  );
  private final ScheduledExecutorService scheduledExecutorService;
  // Must not modify it.
  private final Metadata headers;
  @Nullable
  private final RetryPolicy retryPolicy;
  @Nullable
  private final HedgingPolicy hedgingPolicy;
  private final boolean isHedging;

  /** Must be held when updating state, accessing state.buffer, or certain substream attributes. */
  private final Object lock = new Object();

  private final ChannelBufferMeter channelBufferUsed;
  private final long perRpcBufferLimit;
  private final long channelBufferLimit;
  @Nullable
  private final Throttle throttle;
  @GuardedBy("lock")
  private final InsightBuilder closedSubstreamsInsight = new InsightBuilder();

  private volatile State state = new State(
      new ArrayList<BufferEntry>(8), Collections.<Substream>emptyList(), null, null, false, false,
      false, 0);

  /**
   * Either non-local transparent retry happened or reached server's application logic.
   *
   * <p>Note that local-only transparent retries are unlimited.
   */
  private final AtomicBoolean noMoreTransparentRetry = new AtomicBoolean();
  private final AtomicInteger localOnlyTransparentRetries = new AtomicInteger();
  private final AtomicInteger inFlightSubStreams = new AtomicInteger();
  private SavedCloseMasterListenerReason savedCloseMasterListenerReason;

  // Used for recording the share of buffer used for the current call out of the channel buffer.
  // This field would not be necessary if there is no channel buffer limit.
  @GuardedBy("lock")
  private long perRpcBufferUsed;

  private ClientStreamListener masterListener;
  @GuardedBy("lock")
  private FutureCanceller scheduledRetry;
  @GuardedBy("lock")
  private FutureCanceller scheduledHedging;
  private long nextBackoffIntervalNanos;
  private Status cancellationStatus;
  private boolean isClosed;

  RetriableStream(
      MethodDescriptor<ReqT, ?> method, Metadata headers,
      ChannelBufferMeter channelBufferUsed, long perRpcBufferLimit, long channelBufferLimit,
      Executor callExecutor, ScheduledExecutorService scheduledExecutorService,
      @Nullable RetryPolicy retryPolicy, @Nullable HedgingPolicy hedgingPolicy,
      @Nullable Throttle throttle) {
    this.method = method;
    this.channelBufferUsed = channelBufferUsed;
    this.perRpcBufferLimit = perRpcBufferLimit;
    this.channelBufferLimit = channelBufferLimit;
    this.callExecutor = callExecutor;
    this.scheduledExecutorService = scheduledExecutorService;
    this.headers = headers;
    this.retryPolicy = retryPolicy;
    if (retryPolicy != null) {
      this.nextBackoffIntervalNanos = retryPolicy.initialBackoffNanos;
    }
    this.hedgingPolicy = hedgingPolicy;
    checkArgument(
        retryPolicy == null || hedgingPolicy == null,
        "Should not provide both retryPolicy and hedgingPolicy");
    this.isHedging = hedgingPolicy != null;
    this.throttle = throttle;
  }

  @SuppressWarnings("GuardedBy")  // TODO(b/145386688) this.lock==ScheduledCancellor.lock so ok
  @Nullable // null if already committed
  @CheckReturnValue
  private Runnable commit(final Substream winningSubstream) {
    synchronized (lock) {
      if (state.winningSubstream != null) {
        return null;
      }
      final Collection<Substream> savedDrainedSubstreams = state.drainedSubstreams;

      state = state.committed(winningSubstream);

      // subtract the share of this RPC from channelBufferUsed.
      channelBufferUsed.addAndGet(-perRpcBufferUsed);

      final boolean wasCancelled = (scheduledRetry != null) ? scheduledRetry.isCancelled() : false;
      final Future<?> retryFuture;
      if (scheduledRetry != null) {
        retryFuture = scheduledRetry.markCancelled();
        scheduledRetry = null;
      } else {
        retryFuture = null;
      }
      // cancel the scheduled hedging if it is scheduled prior to the commitment
      final Future<?> hedgingFuture;
      if (scheduledHedging != null) {
        hedgingFuture = scheduledHedging.markCancelled();
        scheduledHedging = null;
      } else {
        hedgingFuture = null;
      }

      class CommitTask implements Runnable {
        @Override
        public void run() {
          // For hedging only, not needed for normal retry
          for (Substream substream : savedDrainedSubstreams) {
            if (substream != winningSubstream) {
              substream.stream.flush();
              substream.stream.cancel(CANCELLED_BECAUSE_COMMITTED);
            }
          }
          if (retryFuture != null) {
            retryFuture.cancel(false);
            if (!wasCancelled && inFlightSubStreams.decrementAndGet() == Integer.MIN_VALUE) {
              assert savedCloseMasterListenerReason != null;
              listenerSerializeExecutor.execute(
                  new Runnable() {
                    @Override
                    public void run() {
                      isClosed = true;
                      masterListener.closed(savedCloseMasterListenerReason.status,
                          savedCloseMasterListenerReason.progress,
                          savedCloseMasterListenerReason.metadata);
                    }
                  });
            }
          }

          if (hedgingFuture != null) {
            hedgingFuture.cancel(false);
          }

          postCommit();
        }
      }

      return new CommitTask();
    }
  }

  abstract void postCommit();

  /**
   * Calls commit() and if successful runs the post commit task. Post commit task will be non-null
   * for only once. The post commit task cancels other non-winning streams on separate transport
   * threads, thus it must be run on the callExecutor to prevent deadlocks between multiple stream
   * transports.(issues/10314)
   * This method should be called only in subListener callbacks. This guarantees callExecutor
   * schedules tasks before master listener closes, which is protected by the inFlightSubStreams
   * decorative. That is because:
   * For a successful winning stream, other streams won't attempt to close master listener.
   * For a cancelled winning stream (noop), other stream won't attempt to close master listener.
   * For a failed/closed winning stream, the last closed stream closes the master listener, and
   * callExecutor scheduling happens-before that.
   */
  private void commitAndRun(Substream winningSubstream) {
    Runnable postCommitTask = commit(winningSubstream);

    if (postCommitTask != null) {
      callExecutor.execute(postCommitTask);
    }
  }

  // returns null means we should not create new sub streams, e.g. cancelled or
  // other close condition is met for retriableStream.
  @Nullable
  private Substream createSubstream(int previousAttemptCount, boolean isTransparentRetry) {
    int inFlight;
    do {
      inFlight = inFlightSubStreams.get();
      if (inFlight < 0) {
        return null;
      }
    } while (!inFlightSubStreams.compareAndSet(inFlight, inFlight + 1));
    Substream sub = new Substream(previousAttemptCount);
    // one tracer per substream
    final ClientStreamTracer bufferSizeTracer = new BufferSizeTracer(sub);
    ClientStreamTracer.Factory tracerFactory = new ClientStreamTracer.Factory() {
      @Override
      public ClientStreamTracer newClientStreamTracer(
          ClientStreamTracer.StreamInfo info, Metadata headers) {
        return bufferSizeTracer;
      }
    };

    Metadata newHeaders = updateHeaders(headers, previousAttemptCount);
    // NOTICE: This set _must_ be done before stream.start() and it actually is.
    sub.stream = newSubstream(newHeaders, tracerFactory, previousAttemptCount, isTransparentRetry);
    return sub;
  }

  /**
   * Creates a new physical ClientStream that represents a retry/hedging attempt. The returned
   * Client stream is not yet started.
   */
  abstract ClientStream newSubstream(
      Metadata headers, ClientStreamTracer.Factory tracerFactory, int previousAttempts,
      boolean isTransparentRetry);

  /** Adds grpc-previous-rpc-attempts in the headers of a retry/hedging RPC. */
  @VisibleForTesting
  final Metadata updateHeaders(
      Metadata originalHeaders, int previousAttemptCount) {
    Metadata newHeaders = new Metadata();
    newHeaders.merge(originalHeaders);
    if (previousAttemptCount > 0) {
      newHeaders.put(GRPC_PREVIOUS_RPC_ATTEMPTS, String.valueOf(previousAttemptCount));
    }
    return newHeaders;
  }

  private void drain(Substream substream) {
    int index = 0;
    int chunk = 0x80;
    List<BufferEntry> list = null;
    boolean streamStarted = false;
    boolean needsFlush = false;
    Runnable onReadyRunnable = null;

    while (true) {
      State savedState;

      synchronized (lock) {
        savedState = state;
        if (savedState.winningSubstream != null && savedState.winningSubstream != substream) {
          // committed but not me, to be cancelled
          break;
        }
        if (savedState.cancelled) {
          break;
        }
        if (index == savedState.buffer.size()) { // I'm drained
          state = savedState.substreamDrained(substream);
          substream.stream.flush();
          if (!isReady()) {
            return;
          }
          onReadyRunnable = new Runnable() {
            @Override
            public void run() {
              if (!isClosed) {
                masterListener.onReady();
              }
            }
          };
          break;
        }

        if (substream.closed) {
          substream.stream.flush();
//          if (needsFlush) {
//            substream.stream.flush();
//          }
          return;
        }

        int stop = Math.min(index + chunk, savedState.buffer.size());
        if (list == null) {
          list = new ArrayList<>(savedState.buffer.subList(index, stop));
        } else {
          list.clear();
          list.addAll(savedState.buffer.subList(index, stop));
        }
        index = stop;
      }

      for (BufferEntry bufferEntry : list) {
        bufferEntry.runWith(substream);
        if (bufferEntry instanceof RetriableStream.StartEntry) {
          streamStarted = true;
        }

        if (bufferEntry instanceof RetriableStream.SendMessageEntry) {
          needsFlush = true;
        }
        if (bufferEntry instanceof RetriableStream.FlushEntry) {
          needsFlush = false;
        }

        savedState = state;
        if (savedState.winningSubstream != null && savedState.winningSubstream != substream) {
          // committed but not me, to be cancelled
          break;
        }
        if (savedState.cancelled) {
          break;
        }
      }
    }

    substream.stream.flush();
//    if (needsFlush) {
//      substream.stream.flush();
//    }

    if (onReadyRunnable != null) {
      listenerSerializeExecutor.execute(onReadyRunnable);
      return;
    }

    if (!streamStarted) {
      // Start stream so inFlightSubStreams is decremented in Sublistener.closed()
      substream.stream.start(new Sublistener(substream));
    }

    substream.stream.cancel(
        state.winningSubstream == substream ? cancellationStatus : CANCELLED_BECAUSE_COMMITTED);
  }

  /**
   * Runs pre-start tasks. Returns the Status of shutdown if the channel is shutdown.
   */
  @CheckReturnValue
  @Nullable
  abstract Status prestart();

  class StartEntry implements BufferEntry {
    @Override
    public void runWith(Substream substream) {
      substream.stream.start(new Sublistener(substream));
    }
  }

  /** Starts the first PRC attempt. */
  @Override
  public final void start(ClientStreamListener listener) {
    masterListener = listener;

    Status shutdownStatus = prestart();

    if (shutdownStatus != null) {
      cancel(shutdownStatus);
      return;
    }

    synchronized (lock) {
      state.buffer.add(new StartEntry());
    }

    Substream substream = createSubstream(0, false);
    if (substream == null) {
      return;
    }
    if (isHedging) {
      FutureCanceller scheduledHedgingRef = null;

      synchronized (lock) {
        state = state.addActiveHedge(substream);
        if (hasPotentialHedging(state)
            && (throttle == null || throttle.isAboveThreshold())) {
          scheduledHedging = scheduledHedgingRef = new FutureCanceller(lock);
        }
      }

      if (scheduledHedgingRef != null) {
        scheduledHedgingRef.setFuture(
            scheduledExecutorService.schedule(
                new HedgingRunnable(scheduledHedgingRef),
                hedgingPolicy.hedgingDelayNanos,
                TimeUnit.NANOSECONDS));
      }
    }

    drain(substream);
  }

  @SuppressWarnings("GuardedBy")  // TODO(b/145386688) this.lock==ScheduledCancellor.lock so ok
  private void pushbackHedging(@Nullable Integer delayMillis) {
    if (delayMillis == null) {
      return;
    }
    if (delayMillis < 0) {
      freezeHedging();
      return;
    }

    // Cancels the current scheduledHedging and reschedules a new one.
    FutureCanceller future;
    Future<?> futureToBeCancelled;

    synchronized (lock) {
      if (scheduledHedging == null) {
        return;
      }

      futureToBeCancelled = scheduledHedging.markCancelled();
      scheduledHedging = future = new FutureCanceller(lock);
    }

    if (futureToBeCancelled != null) {
      futureToBeCancelled.cancel(false);
    }
    future.setFuture(scheduledExecutorService.schedule(
        new HedgingRunnable(future), delayMillis, TimeUnit.MILLISECONDS));
  }

  private final class HedgingRunnable implements Runnable {

    // Need to hold a ref to the FutureCanceller in case RetriableStrea.scheduledHedging is renewed
    // by a positive push-back just after newSubstream is instantiated, so that we can double check.
    final FutureCanceller scheduledHedgingRef;

    HedgingRunnable(FutureCanceller scheduledHedging) {
      scheduledHedgingRef = scheduledHedging;
    }

    @Override
    public void run() {
      // It's safe to read state.hedgingAttemptCount here.
      // If this run is not cancelled, the value of state.hedgingAttemptCount won't change
      // until state.addActiveHedge() is called subsequently, even the state could possibly
      // change.
      Substream newSubstream = createSubstream(state.hedgingAttemptCount, false);
      if (newSubstream == null) {
        return;
      }
      callExecutor.execute(
          new Runnable() {
            @SuppressWarnings("GuardedBy")  //TODO(b/145386688) lock==ScheduledCancellor.lock so ok
            @Override
            public void run() {
              boolean cancelled = false;
              FutureCanceller future = null;

              synchronized (lock) {
                if (scheduledHedgingRef.isCancelled()) {
                  cancelled = true;
                } else {
                  state = state.addActiveHedge(newSubstream);
                  if (hasPotentialHedging(state)
                      && (throttle == null || throttle.isAboveThreshold())) {
                    scheduledHedging = future = new FutureCanceller(lock);
                  } else {
                    state = state.freezeHedging();
                    scheduledHedging = null;
                  }
                }
              }

              if (cancelled) {
                // Start stream so inFlightSubStreams is decremented in Sublistener.closed()
                newSubstream.stream.start(new Sublistener(newSubstream));
                newSubstream.stream.cancel(Status.CANCELLED.withDescription("Unneeded hedging"));
                return;
              }
              if (future != null) {
                future.setFuture(
                    scheduledExecutorService.schedule(
                        new HedgingRunnable(future),
                        hedgingPolicy.hedgingDelayNanos,
                        TimeUnit.NANOSECONDS));
              }
              drain(newSubstream);
            }
          });
    }
  }

  @Override
  public final void cancel(final Status reason) {
    // Handle everything the same way, e.g. isHedging vs not.
    // If no stream or in flight not-drained stream, commit noop, safeCloseMasterListener, and return.
    // If drained stream, commit it and fall through.
    Substream drainedSubstreamToCommit;
    synchronized (lock) {
      drainedSubstreamToCommit = Iterables.getFirst(state.drainedSubstreams, null);
    }
    if (drainedSubstreamToCommit != null) {
      Runnable runnable = commit(drainedSubstreamToCommit);
      if (runnable != null) {
        runnable.run();
      } else {
      }
    } else { // No substream exists or no drained substreams exist; treat this case the same way.
      Substream noopSubstream = new Substream(0 /* previousAttempts doesn't matter here */);
      noopSubstream.stream = new NoopClientStream();
      Runnable runnable = commit(noopSubstream);

      if (runnable != null) {
        synchronized (lock) {
          state = state.substreamDrained(noopSubstream);
        }
        runnable.run();
        safeCloseMasterListener(reason, RpcProgress.PROCESSED, new Metadata());
        return;
      }
    }

    Substream winningSubstreamToCancel = null;
    synchronized (lock) {
      if (state.drainedSubstreams.contains(state.winningSubstream)) {
        winningSubstreamToCancel = state.winningSubstream;
      } else { // the winningSubstream will be cancelled while draining
        cancellationStatus = reason;
      }
      state = state.cancelled();
    }
    if (winningSubstreamToCancel != null) {
      winningSubstreamToCancel.stream.flush();
      winningSubstreamToCancel.stream.cancel(reason);
    }
  }

  private void delayOrExecute(BufferEntry bufferEntry) {
    Collection<Substream> savedDrainedSubstreams;
    synchronized (lock) {
      if (!state.passThrough) {
        state.buffer.add(bufferEntry);
      }
      savedDrainedSubstreams = state.drainedSubstreams;
    }

    for (Substream substream : savedDrainedSubstreams) {
      bufferEntry.runWith(substream);
    }
  }

  /**
   * Do not use it directly. Use {@link #sendMessage(Object)} instead because we don't use
   * InputStream for buffering.
   */
  @Override
  public final void writeMessage(InputStream message) {
    throw new IllegalStateException("RetriableStream.writeMessage() should not be called directly");
  }

  class SendMessageEntry implements BufferEntry {
    private final ReqT message;

    SendMessageEntry(ReqT message) {
      this.message = message;
    }

    @Override
    public void runWith(Substream substream) {
      substream.stream.writeMessage(method.streamRequest(message));
      if (isHedging) {
        // TODO(ejona): Workaround Netty memory leak. Message writes always need to be followed by
        // flushes (or half close), but retry appears to have a code path that the flushes may
        // not happen. The code needs to be fixed and this removed. See #9340.
        substream.stream.flush();
      }
    }
  }

  final void sendMessage(final ReqT message) {
    State savedState = state;
    if (savedState.passThrough) {
      savedState.winningSubstream.stream.writeMessage(method.streamRequest(message));
      return;
    }

    delayOrExecute(new SendMessageEntry(message));
  }

  @Override
  public final void request(final int numMessages) {
    State savedState = state;
    if (savedState.passThrough) {
      savedState.winningSubstream.stream.request(numMessages);
      return;
    }

    class RequestEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.request(numMessages);
      }
    }

    delayOrExecute(new RequestEntry());
  }

  static class FlushEntry implements BufferEntry {
    @Override
    public void runWith(Substream substream) {
      substream.stream.flush();
    }
  }

  @Override
  public final void flush() {
    State savedState = state;
    if (savedState.passThrough) {
      savedState.winningSubstream.stream.flush();
      return;
    }

    delayOrExecute(new FlushEntry());
  }

  @Override
  public final boolean isReady() {
    for (Substream substream : state.drainedSubstreams) {
      if (substream.stream.isReady()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void optimizeForDirectExecutor() {
    class OptimizeDirectEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.optimizeForDirectExecutor();
      }
    }

    delayOrExecute(new OptimizeDirectEntry());
  }

  @Override
  public final void setCompressor(final Compressor compressor) {
    class CompressorEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.setCompressor(compressor);
      }
    }

    delayOrExecute(new CompressorEntry());
  }

  @Override
  public final void setFullStreamDecompression(final boolean fullStreamDecompression) {
    class FullStreamDecompressionEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.setFullStreamDecompression(fullStreamDecompression);
      }
    }

    delayOrExecute(new FullStreamDecompressionEntry());
  }

  @Override
  public final void setMessageCompression(final boolean enable) {
    class MessageCompressionEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.setMessageCompression(enable);
      }
    }

    delayOrExecute(new MessageCompressionEntry());
  }

  @Override
  public final void halfClose() {
    class HalfCloseEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.halfClose();
      }
    }

    delayOrExecute(new HalfCloseEntry());
  }

  @Override
  public final void setAuthority(final String authority) {
    class AuthorityEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.setAuthority(authority);
      }
    }

    delayOrExecute(new AuthorityEntry());
  }

  @Override
  public final void setDecompressorRegistry(final DecompressorRegistry decompressorRegistry) {
    class DecompressorRegistryEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.setDecompressorRegistry(decompressorRegistry);
      }
    }

    delayOrExecute(new DecompressorRegistryEntry());
  }

  @Override
  public final void setMaxInboundMessageSize(final int maxSize) {
    class MaxInboundMessageSizeEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.setMaxInboundMessageSize(maxSize);
      }
    }

    delayOrExecute(new MaxInboundMessageSizeEntry());
  }

  @Override
  public final void setMaxOutboundMessageSize(final int maxSize) {
    class MaxOutboundMessageSizeEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.setMaxOutboundMessageSize(maxSize);
      }
    }

    delayOrExecute(new MaxOutboundMessageSizeEntry());
  }

  @Override
  public final void setDeadline(final Deadline deadline) {
    class DeadlineEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.setDeadline(deadline);
      }
    }

    delayOrExecute(new DeadlineEntry());
  }

  @Override
  public final Attributes getAttributes() {
    if (state.winningSubstream != null) {
      return state.winningSubstream.stream.getAttributes();
    }
    return Attributes.EMPTY;
  }

  @Override
  public void appendTimeoutInsight(InsightBuilder insight) {
    State currentState;
    synchronized (lock) {
      insight.appendKeyValue("closed", closedSubstreamsInsight);
      currentState = state;
    }
    if (currentState.winningSubstream != null) {
      // TODO(zhangkun83): in this case while other drained substreams have been cancelled in favor
      // of the winning substream, they may not have received closed() notifications yet, thus they
      // may be missing from closedSubstreamsInsight.  This may be a little confusing to the user.
      // We need to figure out how to include them.
      InsightBuilder substreamInsight = new InsightBuilder();
      currentState.winningSubstream.stream.appendTimeoutInsight(substreamInsight);
      insight.appendKeyValue("committed", substreamInsight);
    } else {
      InsightBuilder openSubstreamsInsight = new InsightBuilder();
      // drainedSubstreams doesn't include all open substreams.  Those which have just been created
      // and are still catching up with buffered requests (in other words, still draining) will not
      // show up.  We think this is benign, because the draining should be typically fast, and it'd
      // be indistinguishable from the case where those streams are to be created a little late due
      // to delays in the timer.
      for (Substream sub : currentState.drainedSubstreams) {
        InsightBuilder substreamInsight = new InsightBuilder();
        sub.stream.appendTimeoutInsight(substreamInsight);
        openSubstreamsInsight.append(substreamInsight);
      }
      insight.appendKeyValue("open", openSubstreamsInsight);
    }
  }

  private static Random random = new Random();

  @VisibleForTesting
  static void setRandom(Random random) {
    RetriableStream.random = random;
  }

  /**
   * Whether there is any potential hedge at the moment. A false return value implies there is
   * absolutely no potential hedge. At least one of the hedges will observe a false return value
   * when calling this method, unless otherwise the rpc is committed.
   */
  // only called when isHedging is true
  @GuardedBy("lock")
  private boolean hasPotentialHedging(State state) {
    return state.winningSubstream == null
        && state.hedgingAttemptCount < hedgingPolicy.maxAttempts
        && !state.hedgingFrozen;
  }

  @SuppressWarnings("GuardedBy")  // TODO(b/145386688) this.lock==ScheduledCancellor.lock so ok
  private void freezeHedging() {
    Future<?> futureToBeCancelled = null;
    synchronized (lock) {
      if (scheduledHedging != null) {
        futureToBeCancelled = scheduledHedging.markCancelled();
        scheduledHedging = null;
      }
      state = state.freezeHedging();
    }

    if (futureToBeCancelled != null) {
      futureToBeCancelled.cancel(false);
    }
  }

  private void safeCloseMasterListener(Status status, RpcProgress progress, Metadata metadata) {
    savedCloseMasterListenerReason = new SavedCloseMasterListenerReason(status, progress,
        metadata);
    if (inFlightSubStreams.addAndGet(Integer.MIN_VALUE) == Integer.MIN_VALUE) {
      listenerSerializeExecutor.execute(
          new Runnable() {
            @Override
            public void run() {
              isClosed = true;
              masterListener.closed(status, progress, metadata);
            }
          });
    }
  }

  private static final class SavedCloseMasterListenerReason {
    private final Status status;
    private final RpcProgress progress;
    private final Metadata metadata;

    SavedCloseMasterListenerReason(Status status, RpcProgress progress, Metadata metadata) {
      this.status = status;
      this.progress = progress;
      this.metadata = metadata;
    }
  }

  private interface BufferEntry {
    /** Replays the buffer entry with the given stream. */
    void runWith(Substream substream);
  }

  private final class Sublistener implements ClientStreamListener {
    final Substream substream;

    Sublistener(Substream substream) {
      this.substream = substream;
    }

    @Override
    public void headersRead(final Metadata headers) {
      if (substream.previousAttemptCount > 0) {
        headers.discardAll(GRPC_PREVIOUS_RPC_ATTEMPTS);
        headers.put(GRPC_PREVIOUS_RPC_ATTEMPTS, String.valueOf(substream.previousAttemptCount));
      }
      commitAndRun(substream);
      if (state.winningSubstream == substream) {
        if (throttle != null) {
          throttle.onSuccess();
        }
        listenerSerializeExecutor.execute(
            new Runnable() {
              @Override
              public void run() {
                masterListener.headersRead(headers);
              }
            });
      }
    }

    @Override
    public void closed(
        final Status status, final RpcProgress rpcProgress, final Metadata trailers) {
      synchronized (lock) {
        state = state.substreamClosed(substream);
        closedSubstreamsInsight.append(status.getCode());
      }

      if (inFlightSubStreams.decrementAndGet() == Integer.MIN_VALUE) {
        assert savedCloseMasterListenerReason != null;
        listenerSerializeExecutor.execute(
            new Runnable() {
              @Override
              public void run() {
                isClosed = true;
                masterListener.closed(savedCloseMasterListenerReason.status,
                    savedCloseMasterListenerReason.progress,
                    savedCloseMasterListenerReason.metadata);
              }
            });
        return;
      }

      // handle a race between buffer limit exceeded and closed, when setting
      // substream.bufferLimitExceeded = true happens before state.substreamClosed(substream).
      if (substream.bufferLimitExceeded) {
        commitAndRun(substream);
        if (state.winningSubstream == substream) {
          safeCloseMasterListener(status, rpcProgress, trailers);
        }
        return;
      }
      if (rpcProgress == RpcProgress.MISCARRIED
          && localOnlyTransparentRetries.incrementAndGet() > 1_000) {
        commitAndRun(substream);
        if (state.winningSubstream == substream) {
          Status tooManyTransparentRetries = Status.INTERNAL
              .withDescription("Too many transparent retries. Might be a bug in gRPC")
              .withCause(status.asRuntimeException());
          safeCloseMasterListener(tooManyTransparentRetries, rpcProgress, trailers);
        }
        return;
      }

      if (state.winningSubstream == null) {
        if (rpcProgress == RpcProgress.MISCARRIED
            || (rpcProgress == RpcProgress.REFUSED
                && noMoreTransparentRetry.compareAndSet(false, true))) {
          // transparent retry
          final Substream newSubstream = createSubstream(substream.previousAttemptCount, true);
          if (newSubstream == null) {
            return;
          }
          if (isHedging) {
            synchronized (lock) {
              // Although this operation is not done atomically with
              // noMoreTransparentRetry.compareAndSet(false, true), it does not change the size() of
              // activeHedges, so neither does it affect the commitment decision of other threads,
              // nor do the commitment decision making threads affect itself.
              state = state.replaceActiveHedge(substream, newSubstream);
            }
          }

          callExecutor.execute(new Runnable() {
            @Override
            public void run() {
              drain(newSubstream);
            }
          });
          return;
        } else if (rpcProgress == RpcProgress.DROPPED) {
          // For normal retry, nothing need be done here, will just commit.
          // For hedging, cancel scheduled hedge that is scheduled prior to the drop
          if (isHedging) {
            freezeHedging();
          }
        } else {
          noMoreTransparentRetry.set(true);

          if (isHedging) {
            HedgingPlan hedgingPlan = makeHedgingDecision(status, trailers);
            if (hedgingPlan.isHedgeable) {
              pushbackHedging(hedgingPlan.hedgingPushbackMillis);
            }
            synchronized (lock) {
              state = state.removeActiveHedge(substream);
              // The invariant is whether or not #(Potential Hedge + active hedges) > 0.
              // Once hasPotentialHedging(state) is false, it will always be false, and then
              // #(state.activeHedges) will be decreasing. This guarantees that even there may be
              // multiple concurrent hedges, one of the hedges will end up committed.
              if (hedgingPlan.isHedgeable) {
                if (hasPotentialHedging(state) || !state.activeHedges.isEmpty()) {
                  return;
                }
                // else, no activeHedges, no new hedges possible, try to commit
              } // else, isHedgeable is false, try to commit
            }
          } else {
            RetryPlan retryPlan = makeRetryDecision(status, trailers);
            if (retryPlan.shouldRetry) {
              // retry
              Substream newSubstream = createSubstream(substream.previousAttemptCount + 1, false);
              if (newSubstream == null) {
                return;
              }
              // The check state.winningSubstream == null, checking if is not already committed, is
              // racy, but is still safe b/c the retry will also handle committed/cancellation
              FutureCanceller scheduledRetryCopy;
              synchronized (lock) {
                scheduledRetry = scheduledRetryCopy = new FutureCanceller(lock);
              }

              class RetryBackoffRunnable implements Runnable {
                @Override
                @SuppressWarnings("FutureReturnValueIgnored")
                public void run() {
                  synchronized (scheduledRetryCopy.lock) {
                    if (scheduledRetryCopy.isCancelled()) {
                      return;
                    } else {
                      scheduledRetryCopy.markCancelled();
                    }
                  }

                  callExecutor.execute(
                      new Runnable() {
                        @Override
                        public void run() {
                          drain(newSubstream);
                        }
                      });
                }
              }

              scheduledRetryCopy.setFuture(
                  scheduledExecutorService.schedule(
                      new RetryBackoffRunnable(),
                      retryPlan.backoffNanos,
                      TimeUnit.NANOSECONDS));
              return;
            }
          }
        }
      }

      commitAndRun(substream);
      if (state.winningSubstream == substream) {
        safeCloseMasterListener(status, rpcProgress, trailers);
      }
    }

    /**
     * Decides in current situation whether or not the RPC should retry and if it should retry how
     * long the backoff should be. The decision does not take the commitment status into account, so
     * caller should check it separately. It also updates the throttle. It does not change state.
     */
    private RetryPlan makeRetryDecision(Status status, Metadata trailer) {
      if (retryPolicy == null) {
        return new RetryPlan(false, 0);
      }
      boolean shouldRetry = false;
      long backoffNanos = 0L;
      boolean isRetryableStatusCode = retryPolicy.retryableStatusCodes.contains(status.getCode());
      Integer pushbackMillis = getPushbackMills(trailer);
      boolean isThrottled = false;
      if (throttle != null) {
        if (isRetryableStatusCode || (pushbackMillis != null && pushbackMillis < 0)) {
          isThrottled = !throttle.onQualifiedFailureThenCheckIsAboveThreshold();
        }
      }

      if (retryPolicy.maxAttempts > substream.previousAttemptCount + 1 && !isThrottled) {
        if (pushbackMillis == null) {
          if (isRetryableStatusCode) {
            shouldRetry = true;
            backoffNanos = (long) (nextBackoffIntervalNanos * random.nextDouble());
            nextBackoffIntervalNanos = Math.min(
                (long) (nextBackoffIntervalNanos * retryPolicy.backoffMultiplier),
                retryPolicy.maxBackoffNanos);
          } // else no retry
        } else if (pushbackMillis >= 0) {
          shouldRetry = true;
          backoffNanos = TimeUnit.MILLISECONDS.toNanos(pushbackMillis);
          nextBackoffIntervalNanos = retryPolicy.initialBackoffNanos;
        } // else no retry
      } // else no retry

      return new RetryPlan(shouldRetry, backoffNanos);
    }

    private HedgingPlan makeHedgingDecision(Status status, Metadata trailer) {
      Integer pushbackMillis = getPushbackMills(trailer);
      boolean isFatal = !hedgingPolicy.nonFatalStatusCodes.contains(status.getCode());
      boolean isThrottled = false;
      if (throttle != null) {
        if (!isFatal || (pushbackMillis != null && pushbackMillis < 0)) {
          isThrottled = !throttle.onQualifiedFailureThenCheckIsAboveThreshold();
        }
      }
      if (!isFatal && !isThrottled && !status.isOk()
          && (pushbackMillis != null && pushbackMillis > 0)) {
        pushbackMillis = 0; // We want the retry after a nonfatal error to be immediate
      }
      return new HedgingPlan(!isFatal && !isThrottled, pushbackMillis);
    }

    @Nullable
    private Integer getPushbackMills(Metadata trailer) {
      String pushbackStr = trailer.get(GRPC_RETRY_PUSHBACK_MS);
      Integer pushbackMillis = null;
      if (pushbackStr != null) {
        try {
          pushbackMillis = Integer.valueOf(pushbackStr);
        } catch (NumberFormatException e) {
          pushbackMillis = -1;
        }
      }
      return pushbackMillis;
    }

    @Override
    public void messagesAvailable(final MessageProducer producer) {
      State savedState = state;
      checkState(
          savedState.winningSubstream != null, "Headers should be received prior to messages.");
      if (savedState.winningSubstream != substream) {
        GrpcUtil.closeQuietly(producer);
        return;
      }
      listenerSerializeExecutor.execute(
          new Runnable() {
            @Override
            public void run() {
              masterListener.messagesAvailable(producer);
            }
          });
    }

    @Override
    public void onReady() {
      // FIXME(#7089): hedging case is broken.
      if (!isReady()) {
        return;
      }
      listenerSerializeExecutor.execute(
          new Runnable() {
            @Override
            public void run() {
              if (!isClosed) {
                masterListener.onReady();
              }
            }
          });
    }
  }

  private static final class State {
    /** Committed and the winning substream drained. */
    final boolean passThrough;

    /** A list of buffered ClientStream runnables. Set to Null once passThrough. */
    @Nullable final List<BufferEntry> buffer;

    /**
     * Unmodifiable collection of all the open substreams that are drained. Singleton once
     * passThrough; Empty if committed but not passThrough.
     */
    final Collection<Substream> drainedSubstreams;

    /**
     * Unmodifiable collection of all the active hedging substreams.
     *
     * <p>A substream even with the attribute substream.closed being true may be considered still
     * "active" at the moment as long as it is in this collection.
     */
    final Collection<Substream> activeHedges; // not null once isHedging = true

    final int hedgingAttemptCount;

    /** Null until committed. */
    @Nullable final Substream winningSubstream;

    /** Not required to set to true when cancelled, but can short-circuit the draining process. */
    final boolean cancelled;

    /** No more hedging due to events like drop or pushback. */
    final boolean hedgingFrozen;

    State(
        @Nullable List<BufferEntry> buffer,
        Collection<Substream> drainedSubstreams,
        Collection<Substream> activeHedges,
        @Nullable Substream winningSubstream,
        boolean cancelled,
        boolean passThrough,
        boolean hedgingFrozen,
        int hedgingAttemptCount) {
      this.buffer = buffer;
      this.drainedSubstreams =
          checkNotNull(drainedSubstreams, "drainedSubstreams");
      this.winningSubstream = winningSubstream;
      this.activeHedges = activeHedges;
      this.cancelled = cancelled;
      this.passThrough = passThrough;
      this.hedgingFrozen = hedgingFrozen;
      this.hedgingAttemptCount = hedgingAttemptCount;

      checkState(!passThrough || buffer == null, "passThrough should imply buffer is null");
      checkState(
          !passThrough || winningSubstream != null,
          "passThrough should imply winningSubstream != null");
      checkState(
          !passThrough
              || (drainedSubstreams.size() == 1 && drainedSubstreams.contains(winningSubstream))
              || (drainedSubstreams.size() == 0 && winningSubstream.closed),
          "passThrough should imply winningSubstream is drained");
    }

    @CheckReturnValue
    // GuardedBy RetriableStream.lock
    State cancelled() {
      return new State(
          buffer, drainedSubstreams, activeHedges, winningSubstream, true, passThrough,
          hedgingFrozen, hedgingAttemptCount);
    }

    /** The given substream is drained. */
    @CheckReturnValue
    // GuardedBy RetriableStream.lock
    State substreamDrained(Substream substream) {
      checkState(!passThrough, "Already passThrough");

      Collection<Substream> drainedSubstreams;
      
      if (substream.closed) {
        drainedSubstreams = this.drainedSubstreams;
      } else if (this.drainedSubstreams.isEmpty()) {
        // optimize for 0-retry, which is most of the cases.
        drainedSubstreams = Collections.singletonList(substream);
      } else {
        drainedSubstreams = new ArrayList<>(this.drainedSubstreams);
        drainedSubstreams.add(substream);
        drainedSubstreams = Collections.unmodifiableCollection(drainedSubstreams);
      }

      boolean passThrough = winningSubstream != null;

      List<BufferEntry> buffer = this.buffer;
      if (passThrough) {
        checkState(
            winningSubstream == substream, "Another RPC attempt has already committed");
        buffer = null;
      }

      return new State(
          buffer, drainedSubstreams, activeHedges, winningSubstream, cancelled, passThrough,
          hedgingFrozen, hedgingAttemptCount);
    }

    /** The given substream is closed. */
    @CheckReturnValue
    // GuardedBy RetriableStream.lock
    State substreamClosed(Substream substream) {
      substream.closed = true;
      if (this.drainedSubstreams.contains(substream)) {
        Collection<Substream> drainedSubstreams = new ArrayList<>(this.drainedSubstreams);
        drainedSubstreams.remove(substream);
        drainedSubstreams = Collections.unmodifiableCollection(drainedSubstreams);
        return new State(
            buffer, drainedSubstreams, activeHedges, winningSubstream, cancelled, passThrough,
            hedgingFrozen, hedgingAttemptCount);
      } else {
        return this;
      }
    }

    @CheckReturnValue
    // GuardedBy RetriableStream.lock
    State committed(Substream winningSubstream) {
      checkState(this.winningSubstream == null, "Already committed");

      boolean passThrough = false;
      List<BufferEntry> buffer = this.buffer;
      Collection<Substream> drainedSubstreams;

      if (this.drainedSubstreams.contains(winningSubstream)) {
        passThrough = true;
        buffer = null;
        drainedSubstreams = Collections.singleton(winningSubstream);
      } else {
        drainedSubstreams = Collections.emptyList();
      }

      return new State(
          buffer, drainedSubstreams, activeHedges, winningSubstream, cancelled, passThrough,
          hedgingFrozen, hedgingAttemptCount);
    }

    @CheckReturnValue
    // GuardedBy RetriableStream.lock
    State freezeHedging() {
      if (hedgingFrozen) {
        return this;
      }
      return new State(
          buffer, drainedSubstreams, activeHedges, winningSubstream, cancelled, passThrough,
          true, hedgingAttemptCount);
    }

    @CheckReturnValue
    // GuardedBy RetriableStream.lock
    // state.hedgingAttemptCount is modified only here.
    // The method is only called in RetriableStream.start() and HedgingRunnable.run()
    State addActiveHedge(Substream substream) {
      // hasPotentialHedging must be true
      checkState(!hedgingFrozen, "hedging frozen");
      checkState(winningSubstream == null, "already committed");

      Collection<Substream> activeHedges;
      if (this.activeHedges == null) {
        activeHedges = Collections.singleton(substream);
      } else {
        activeHedges = new ArrayList<>(this.activeHedges);
        activeHedges.add(substream);
        activeHedges = Collections.unmodifiableCollection(activeHedges);
      }

      int hedgingAttemptCount = this.hedgingAttemptCount + 1;
      return new State(
          buffer, drainedSubstreams, activeHedges, winningSubstream, cancelled, passThrough,
          hedgingFrozen, hedgingAttemptCount);
    }

    @CheckReturnValue
    // GuardedBy RetriableStream.lock
    // The method is only called in Sublistener.closed()
    State removeActiveHedge(Substream substream) {
      Collection<Substream> activeHedges = new ArrayList<>(this.activeHedges);
      activeHedges.remove(substream);
      activeHedges = Collections.unmodifiableCollection(activeHedges);

      return new State(
          buffer, drainedSubstreams, activeHedges, winningSubstream, cancelled, passThrough,
          hedgingFrozen, hedgingAttemptCount);
    }

    @CheckReturnValue
    // GuardedBy RetriableStream.lock
    // The method is only called for transparent retry.
    State replaceActiveHedge(Substream oldOne, Substream newOne) {
      Collection<Substream> activeHedges = new ArrayList<>(this.activeHedges);
      activeHedges.remove(oldOne);
      activeHedges.add(newOne);
      activeHedges = Collections.unmodifiableCollection(activeHedges);

      return new State(
          buffer, drainedSubstreams, activeHedges, winningSubstream, cancelled, passThrough,
          hedgingFrozen, hedgingAttemptCount);
    }
  }

  /**
   * A wrapper of a physical stream of a retry/hedging attempt, that comes with some useful
   *  attributes.
   */
  private static final class Substream {
    ClientStream stream;

    // GuardedBy RetriableStream.lock
    boolean closed;

    // setting to true must be GuardedBy RetriableStream.lock
    boolean bufferLimitExceeded;

    final int previousAttemptCount;

    Substream(int previousAttemptCount) {
      this.previousAttemptCount = previousAttemptCount;
    }
  }


  /**
   * Traces the buffer used by a substream.
   */
  class BufferSizeTracer extends ClientStreamTracer {
    // Each buffer size tracer is dedicated to one specific substream.
    private final Substream substream;

    @GuardedBy("lock")
    long bufferNeeded;

    BufferSizeTracer(Substream substream) {
      this.substream = substream;
    }

    /**
     * A message is sent to the wire, so its reference would be released if no retry or
     * hedging were involved. So at this point we have to hold the reference of the message longer
     * for retry, and we need to increment {@code substream.bufferNeeded}.
     */
    @Override
    public void outboundWireSize(long bytes) {
      if (state.winningSubstream != null) {
        return;
      }

      Runnable postCommitTask = null;

      // TODO(zdapeng): avoid using the same lock for both in-bound and out-bound.
      synchronized (lock) {
        if (state.winningSubstream != null || substream.closed) {
          return;
        }
        bufferNeeded += bytes;
        if (bufferNeeded <= perRpcBufferUsed) {
          return;
        }

        if (bufferNeeded > perRpcBufferLimit) {
          substream.bufferLimitExceeded = true;
        } else {
          // Only update channelBufferUsed when perRpcBufferUsed is not exceeding perRpcBufferLimit.
          long savedChannelBufferUsed =
              channelBufferUsed.addAndGet(bufferNeeded - perRpcBufferUsed);
          perRpcBufferUsed = bufferNeeded;

          if (savedChannelBufferUsed > channelBufferLimit) {
            substream.bufferLimitExceeded = true;
          }
        }

        if (substream.bufferLimitExceeded) {
          postCommitTask = commit(substream);
        }
      }

      if (postCommitTask != null) {
        postCommitTask.run();
      }
    }
  }

  /**
   *  Used to keep track of the total amount of memory used to buffer retryable or hedged RPCs for
   *  the Channel. There should be a single instance of it for each channel.
   */
  static final class ChannelBufferMeter {
    private final AtomicLong bufferUsed = new AtomicLong();

    @VisibleForTesting
    long addAndGet(long newBytesUsed) {
      return bufferUsed.addAndGet(newBytesUsed);
    }
  }

  /**
   * Used for retry throttling.
   */
  static final class Throttle {

    private static final int THREE_DECIMAL_PLACES_SCALE_UP = 1000;

    /**
     * 1000 times the maxTokens field of the retryThrottling policy in service config.
     * The number of tokens starts at maxTokens. The token_count will always be between 0 and
     * maxTokens.
     */
    final int maxTokens;

    /**
     * Half of {@code maxTokens}.
     */
    final int threshold;

    /**
     * 1000 times the tokenRatio field of the retryThrottling policy in service config.
     */
    final int tokenRatio;

    final AtomicInteger tokenCount = new AtomicInteger();

    Throttle(float maxTokens, float tokenRatio) {
      // tokenRatio is up to 3 decimal places
      this.tokenRatio = (int) (tokenRatio * THREE_DECIMAL_PLACES_SCALE_UP);
      this.maxTokens = (int) (maxTokens * THREE_DECIMAL_PLACES_SCALE_UP);
      this.threshold = this.maxTokens / 2;
      tokenCount.set(this.maxTokens);
    }

    @VisibleForTesting
    boolean isAboveThreshold() {
      return tokenCount.get() > threshold;
    }

    /**
     * Counts down the token on qualified failure and checks if it is above the threshold
     * atomically. Qualified failure is a failure with a retryable or non-fatal status code or with
     * a not-to-retry pushback.
     */
    @VisibleForTesting
    boolean onQualifiedFailureThenCheckIsAboveThreshold() {
      while (true) {
        int currentCount = tokenCount.get();
        if (currentCount == 0) {
          return false;
        }
        int decremented = currentCount - (1 * THREE_DECIMAL_PLACES_SCALE_UP);
        boolean updated = tokenCount.compareAndSet(currentCount, Math.max(decremented, 0));
        if (updated) {
          return decremented > threshold;
        }
      }
    }

    @VisibleForTesting
    void onSuccess() {
      while (true) {
        int currentCount = tokenCount.get();
        if (currentCount == maxTokens) {
          break;
        }
        int incremented = currentCount + tokenRatio;
        boolean updated = tokenCount.compareAndSet(currentCount, Math.min(incremented, maxTokens));
        if (updated) {
          break;
        }
      }
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Throttle)) {
        return false;
      }
      Throttle that = (Throttle) o;
      return maxTokens == that.maxTokens && tokenRatio == that.tokenRatio;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(maxTokens, tokenRatio);
    }
  }

  private static final class RetryPlan {
    final boolean shouldRetry;
    final long backoffNanos;

    RetryPlan(boolean shouldRetry, long backoffNanos) {
      this.shouldRetry = shouldRetry;
      this.backoffNanos = backoffNanos;
    }
  }

  private static final class HedgingPlan {
    final boolean isHedgeable;
    @Nullable
    final Integer hedgingPushbackMillis;

    public HedgingPlan(
        boolean isHedgeable, @Nullable Integer hedgingPushbackMillis) {
      this.isHedgeable = isHedgeable;
      this.hedgingPushbackMillis = hedgingPushbackMillis;
    }
  }

  /** Allows cancelling a Future without racing with setting the future. */
  private static final class FutureCanceller {

    final Object lock;
    @GuardedBy("lock")
    Future<?> future;
    @GuardedBy("lock")
    boolean cancelled;

    FutureCanceller(Object lock) {
      this.lock = lock;
    }

    void setFuture(Future<?> future) {
      boolean wasCancelled;
      synchronized (lock) {
        wasCancelled = cancelled;
        if (!wasCancelled) {
          this.future = future;
        }
      }
      if (wasCancelled) {
        future.cancel(false);
      }
    }

    @GuardedBy("lock")
    @CheckForNull // Must cancel the returned future if not null.
    Future<?> markCancelled() {
      cancelled = true;
      return future;
    }

    @GuardedBy("lock")
    boolean isCancelled() {
      return cancelled;
    }
  }
}
