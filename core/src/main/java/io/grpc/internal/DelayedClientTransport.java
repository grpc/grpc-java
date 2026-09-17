/*
 * Copyright 2015 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import io.grpc.CallOptions;
import io.grpc.ClientStreamTracer;
import io.grpc.Context;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.ClientStreamListener.RpcProgress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A client transport that queues requests before a real transport is available. When {@link
 * #reprocess} is called, this class applies the provided {@link SubchannelPicker} to pick a
 * transport for each pending stream.
 *
 * <p>This transport owns every stream that it has created until a real transport has been picked
 * for that stream, at which point the ownership of the stream is transferred to the real transport,
 * thus the delayed transport stops owning the stream.
 */
final class DelayedClientTransport implements ManagedClientTransport {
  private static final String DELAY_TYPE_CONNECTING = "connecting";
  private static final String DELAY_TYPE_SUBCHANNEL_STATE_MISMATCH = "subchannel_state_mismatch";
  private static final String DELAY_TYPE_PICKER_FAILING_WITH_WAIT_FOR_READY =
      "picker_failing_with_wait_for_ready";
  private static final String DELAY_REASON_WAITING_FOR_PICKER =
      "client channel: waiting for picker";
  private static final String DELAY_REASON_SUBCHANNEL_STATE_MISMATCH =
      "subchannel returned by LB picker has no connected subchannel";
  private static final String DELAY_REASON_WAIT_FOR_READY_FAILED_PREFIX =
      "wait_for_ready RPC failed with status: ";

  // lazily allocated, since it is infrequently used.
  private final InternalLogId logId =
      InternalLogId.allocate(DelayedClientTransport.class, /*details=*/ null);

  private final Object lock = new Object();

  private final Executor defaultAppExecutor;
  private final SynchronizationContext syncContext;

  private Runnable reportTransportInUse;
  private Runnable reportTransportNotInUse;
  private Runnable reportTransportTerminated;
  private Listener listener;

  @Nonnull
  @GuardedBy("lock")
  private Collection<PendingStream> pendingStreams = new LinkedHashSet<>();

  /** Immutable state needed for picking. 'lock' must be held for writing. */
  private volatile PickerState pickerState = new PickerState(null, null);

  /**
   * Creates a new delayed transport.
   *
   * @param defaultAppExecutor pending streams will create real streams and run buffered operations
   *        in an application executor, which will be this executor, unless there is on provided in
   *        {@link CallOptions}.
   * @param syncContext all listener callbacks of the delayed transport will be run from this
   *        SynchronizationContext.
   */
  DelayedClientTransport(Executor defaultAppExecutor, SynchronizationContext syncContext) {
    this.defaultAppExecutor = defaultAppExecutor;
    this.syncContext = syncContext;
  }

  @Override
  public final Runnable start(final Listener listener) {
    this.listener = listener;
    reportTransportInUse = new Runnable() {
        @Override
        public void run() {
          listener.transportInUse(true);
        }
      };
    reportTransportNotInUse = new Runnable() {
        @Override
        public void run() {
          listener.transportInUse(false);
        }
      };
    reportTransportTerminated = new Runnable() {
        @Override
        public void run() {
          listener.transportTerminated();
        }
      };
    return null;
  }

  /**
   * If a {@link SubchannelPicker} is being, or has been provided via {@link #reprocess}, the last
   * picker will be consulted.
   *
   * <p>Otherwise, if the delayed transport is not shutdown, then a {@link PendingStream} is
   * returned; if the transport is shutdown, then a {@link FailingClientStream} is returned.
   */
  @Override
  public final ClientStream newStream(
      MethodDescriptor<?, ?> method, Metadata headers, CallOptions callOptions,
      ClientStreamTracer[] tracers) {
    try {
      PickSubchannelArgs args = new PickSubchannelArgsImpl(
          method, headers, callOptions, new PickDetailsConsumerImpl(tracers));
      PickerState state = pickerState;
      PendingStream pendingStream;
      while (true) {
        if (state.shutdownStatus != null) {
          return new FailingClientStream(state.shutdownStatus, tracers);
        }
        PickResult pickResult = null;
        if (state.lastPicker != null) {
          pickResult = state.lastPicker.pickSubchannel(args);
          callOptions = args.getCallOptions();
          // User code provided authority takes precedence over the LB provided one.
          if (callOptions.getAuthority() == null
              && pickResult.getAuthorityOverride() != null) {
            callOptions = callOptions.withAuthority(pickResult.getAuthorityOverride());
          }
          ClientTransport transport = GrpcUtil.getTransportFromPickResult(pickResult,
              callOptions.isWaitForReady());
          if (transport != null) {
            ClientStream stream = transport.newStream(
                args.getMethodDescriptor(), args.getHeaders(), callOptions,
                tracers);
            // User code provided authority takes precedence over the LB provided one; this will be
            // overwritten by ClientCallImpl if the application sets an authority override
            if (pickResult.getAuthorityOverride() != null) {
              stream.setAuthority(pickResult.getAuthorityOverride());
            }
            return stream;
          }
        }
        // This picker's conclusion is "buffer".  If there hasn't been a newer picker set (possible
        // race with reprocess()), we will buffer the RPC.  Otherwise, will try with the new picker.
        synchronized (lock) {
          PickerState newerState = pickerState;
          if (state == newerState) {
            pendingStream = createPendingStream(args, tracers, pickResult);
            break;
          }
          state = newerState;
        }
      }
      // 'lock' has been released. Must not call the tracers while it is held, to prevent
      // deadlocks, so the delay that queued this stream is only delivered now.
      pendingStream.deliverDelayEvents();
      return pendingStream;
    } finally {
      syncContext.drain();
    }
  }

  /**
   * Caller must call {@code syncContext.drain()} outside of lock because this method may
   * schedule tasks on syncContext. Caller must also call {@link PendingStream#deliverDelayEvents}
   * outside of lock, to deliver the delay callback that this method queues.
   */
  @GuardedBy("lock")
  private PendingStream createPendingStream(
      PickSubchannelArgs args, ClientStreamTracer[] tracers, @Nullable PickResult pickResult) {
    PendingStream pendingStream = new PendingStream(args, tracers);
    if (args.getCallOptions().isWaitForReady() && pickResult != null && pickResult.hasResult()) {
      pendingStream.lastPickStatus = pickResult.getStatus();
    }
    pendingStream.startDelay(
        determineQueuingDelayType(pickResult), determineQueuingDelayReasonSource(pickResult));
    pendingStreams.add(pendingStream);
    if (getPendingStreamsCount() == 1) {
      syncContext.executeLater(reportTransportInUse);
    }
    for (ClientStreamTracer streamTracer : tracers) {
      streamTracer.createPendingStream();
    }
    return pendingStream;
  }

  @Override
  public final void ping(final PingCallback callback, Executor executor) {
    throw new UnsupportedOperationException("This method is not expected to be called");
  }

  @Override
  public ListenableFuture<SocketStats> getStats() {
    SettableFuture<SocketStats> ret = SettableFuture.create();
    ret.set(null);
    return ret;
  }

  /**
   * Prevents creating any new streams. Buffered streams are not failed and may still proceed
   * when {@link #reprocess} is called. The delayed transport will be terminated when there is no
   * more buffered streams.
   */
  @Override
  public final void shutdown(final Status status) {
    synchronized (lock) {
      if (pickerState.shutdownStatus != null) {
        return;
      }
      pickerState = pickerState.withShutdownStatus(status);
      syncContext.executeLater(new Runnable() {
          @Override
          public void run() {
            listener.transportShutdown(status, SimpleDisconnectError.SUBCHANNEL_SHUTDOWN);
          }
        });
      if (!hasPendingStreams() && reportTransportTerminated != null) {
        syncContext.executeLater(reportTransportTerminated);
        reportTransportTerminated = null;
      }
    }
    syncContext.drain();
  }

  /**
   * Shuts down this transport and cancels all streams that it owns, hence immediately terminates
   * this transport.
   */
  @Override
  public final void shutdownNow(Status status) {
    shutdown(status);
    Collection<PendingStream> savedPendingStreams;
    Runnable savedReportTransportTerminated;
    synchronized (lock) {
      savedPendingStreams = pendingStreams;
      savedReportTransportTerminated = reportTransportTerminated;
      reportTransportTerminated = null;
      if (!pendingStreams.isEmpty()) {
        pendingStreams = Collections.emptyList();
      }
    }
    if (savedReportTransportTerminated != null) {
      for (PendingStream stream : savedPendingStreams) {
        Runnable runnable = stream.setStreamAndEndDelay(
            new FailingClientStream(status, RpcProgress.REFUSED, stream.tracers));
        if (runnable != null) {
          // Drain in-line instead of using an executor as failing stream just throws everything
          // away. This is essentially the same behavior as DelayedStream.cancel() but can be done
          // before stream.start().
          runnable.run();
        }
      }
      syncContext.execute(savedReportTransportTerminated);
    }
    // If savedReportTransportTerminated == null, transportTerminated() has already been called in
    // shutdown().
  }

  public final boolean hasPendingStreams() {
    synchronized (lock) {
      return !pendingStreams.isEmpty();
    }
  }

  @VisibleForTesting
  final int getPendingStreamsCount() {
    synchronized (lock) {
      return pendingStreams.size();
    }
  }

  /**
   * Use the picker to try picking a transport for every pending stream, proceed the stream if the
   * pick is successful, otherwise keep it pending.
   *
   * <p>This method may be called concurrently with {@code newStream()}, and it's safe.  All pending
   * streams will be served by the latest picker (if a same picker is given more than once, they are
   * considered different pickers) as soon as possible.
   *
   * <p>This method <strong>must not</strong> be called concurrently with itself.
   */
  final void reprocess(@Nullable SubchannelPicker picker) {
    ArrayList<PendingStream> toProcess;
    synchronized (lock) {
      pickerState = pickerState.withPicker(picker);
      if (picker == null || !hasPendingStreams()) {
        return;
      }
      toProcess = new ArrayList<>(pendingStreams);
    }
    ArrayList<PendingStream> toRemove = new ArrayList<>();

    for (final PendingStream stream : toProcess) {
      PickResult pickResult = picker.pickSubchannel(stream.args);
      CallOptions callOptions = stream.args.getCallOptions();
      if (callOptions.isWaitForReady() && pickResult.hasResult()) {
        stream.lastPickStatus = pickResult.getStatus();
      }
      final ClientTransport transport = GrpcUtil.getTransportFromPickResult(pickResult,
          callOptions.isWaitForReady());
      if (transport != null) {
        // The delay must end before the real stream is created: creating it calls the tracers
        // (streamCreated()), which must not be called before the delay has been reported.
        stream.endDelay();
        Executor executor = defaultAppExecutor;
        // createRealStream may be expensive. It will start real streams on the transport. If
        // there are pending requests, they will be serialized too, which may be expensive. Since
        // we are now on transport thread, we need to offload the work to an executor.
        if (callOptions.getExecutor() != null) {
          executor = callOptions.getExecutor();
        }
        Runnable runnable = stream.createRealStream(transport, pickResult.getAuthorityOverride());
        if (runnable != null) {
          executor.execute(runnable);
        }
        toRemove.add(stream);
      } else { // stay pending
        stream.updateDelay(determineQueuingDelayType(pickResult), pickResult);
      }
    }

    synchronized (lock) {
      // Between this synchronized and the previous one:
      //   - Streams may have been cancelled, which may turn pendingStreams into emptiness.
      //   - shutdown() may be called, which may turn pendingStreams into null.
      if (!hasPendingStreams()) {
        return;
      }
      // Avoid pendingStreams.removeAll() as it can degrade to calling toRemove.contains() for each
      // element in pendingStreams.
      for (PendingStream stream : toRemove) {
        pendingStreams.remove(stream);
      }
      // Because delayed transport is long-lived, we take this opportunity to down-size the
      // hashmap.
      if (pendingStreams.isEmpty()) {
        pendingStreams = new LinkedHashSet<>();
      }
      if (!hasPendingStreams()) {
        // There may be a brief gap between delayed transport clearing in-use state, and first real
        // transport starting streams and setting in-use state.  During the gap the whole channel's
        // in-use state may be false. However, it shouldn't cause spurious switching to idleness
        // (which would shutdown the transports and LoadBalancer) because the gap should be shorter
        // than IDLE_MODE_DEFAULT_TIMEOUT_MILLIS (1 second).
        syncContext.executeLater(reportTransportNotInUse);
        if (pickerState.shutdownStatus != null && reportTransportTerminated != null) {
          syncContext.executeLater(reportTransportTerminated);
          reportTransportTerminated = null;
        }
      }
    }
    syncContext.drain();
  }

  @Override
  public InternalLogId getLogId() {
    return logId;
  }

  private static String determineQueuingDelayType(@Nullable PickResult pickResult) {
    if (pickResult == null) {
      return DELAY_TYPE_CONNECTING;
    }
    if (pickResult.getSubchannel() != null) {
      return DELAY_TYPE_SUBCHANNEL_STATE_MISMATCH;
    }
    if (!pickResult.getStatus().isOk()) {
      return DELAY_TYPE_PICKER_FAILING_WITH_WAIT_FOR_READY;
    }
    if (pickResult.getDelayType() != null) {
      return pickResult.getDelayType();
    }
    return DELAY_TYPE_CONNECTING;
  }

  /**
   * Returns the value that the delay reason of a queued pick is derived from, without
   * materializing the reason itself.
   *
   * <p>{@link #reprocess} re-evaluates every pending stream on every picker update, but the reason
   * is only delivered to the tracers when it changed. Since the reason is a pure function of this
   * source, comparing sources lets the common case skip building a reason string.
   */
  private static Object determineQueuingDelayReasonSource(@Nullable PickResult pickResult) {
    if (pickResult == null) {
      return DELAY_REASON_WAITING_FOR_PICKER;
    }
    if (pickResult.getSubchannel() != null) {
      return DELAY_REASON_SUBCHANNEL_STATE_MISMATCH;
    }
    if (!pickResult.getStatus().isOk()) {
      // Materializing the reason would call Status.toString(), so keep the Status itself.
      return pickResult.getStatus();
    }
    if (pickResult.getDelayReason() != null) {
      return pickResult.getDelayReason();
    }
    return DELAY_REASON_WAITING_FOR_PICKER;
  }

  /** Materializes a source returned by {@link #determineQueuingDelayReasonSource}. */
  private static String determineQueuingDelayReason(Object delayReasonSource) {
    if (delayReasonSource instanceof Status) {
      return DELAY_REASON_WAIT_FOR_READY_FAILED_PREFIX + delayReasonSource;
    }
    return (String) delayReasonSource;
  }

  private class PendingStream extends DelayedStream {
    private final PickSubchannelArgs args;
    private final Context context = Context.current();
    private final ClientStreamTracer[] tracers;
    private volatile Status lastPickStatus;
    /**
     * Guards the delay telemetry state below.
     *
     * <p>This is a leaf lock: no other lock is acquired while it is held, and, in particular, the
     * tracers are never called while it is held. A transition is decided under this lock and the
     * callbacks it implies are queued into {@code pendingDelayEvents}; {@link #deliverDelayEvents}
     * then delivers them, in the order they were decided, with no lock held.
     */
    private final Object delayLock = new Object();
    /** Type of the delay in progress. Non-{@code null} until {@code delayEnded}. */
    @GuardedBy("delayLock")
    @Nullable private String activeDelayType;
    @GuardedBy("delayLock")
    @Nullable private String activeDelayReason;
    /** See {@link DelayedClientTransport#determineQueuingDelayReasonSource}. */
    @GuardedBy("delayLock")
    @Nullable private Object activeDelayReasonSource;
    @GuardedBy("delayLock")
    private boolean delayEnded;
    /** Callbacks that have been decided, but not delivered yet, in the order decided. */
    @GuardedBy("delayLock")
    private final Queue<DelayEvent> pendingDelayEvents = new ArrayDeque<>(2);
    @GuardedBy("delayLock")
    private boolean deliveringDelayEvents;

    private PendingStream(PickSubchannelArgs args, ClientStreamTracer[] tracers) {
      super("connecting_and_lb");
      this.args = args;
      this.tracers = tracers;
    }

    /**
     * Records the delay that this stream is queued by. Must be called exactly once, before the
     * stream is visible to other threads. The callback is only queued, because the caller holds
     * {@code lock}; the caller must call {@link #deliverDelayEvents} after releasing it.
     */
    void startDelay(String delayType, Object delayReasonSource) {
      checkNotNull(delayType, "delayType");
      synchronized (delayLock) {
        activeDelayType = delayType;
        activeDelayReasonSource = delayReasonSource;
        activeDelayReason = determineQueuingDelayReason(delayReasonSource);
        pendingDelayEvents.add(DelayEvent.start(delayType, activeDelayReason));
      }
      // Not delivered here: 'lock' is held by the caller.
    }

    /**
     * Updates the delay telemetry state of a stream whose pick stayed queued, and delivers the
     * resulting callbacks.
     *
     * <p>If {@code newType} differs from the type of the delay in progress, that delay is ended
     * and a new one is started. If only the reason changed, the delay in progress is kept and the
     * new reason is reported on it.
     *
     * <p>The reason is derived from {@code pickResult} lazily: {@link #reprocess} calls this for
     * every pending stream on every picker update, and in the common case nothing changed.
     */
    void updateDelay(String newType, @Nullable PickResult pickResult) {
      checkNotNull(newType, "newType");
      synchronized (delayLock) {
        if (delayEnded) {
          // The stream is no longer queued: it has been cancelled, or it has been handed a real
          // stream. Both end the delay before the stream changes hands, so there is nothing left
          // to update.
          return;
        }
        Object newReasonSource = determineQueuingDelayReasonSource(pickResult);
        boolean typeChanged = !newType.equals(activeDelayType);
        if (!typeChanged && Objects.equals(activeDelayReasonSource, newReasonSource)) {
          // Nothing changed since the last picker update; don't materialize the reason.
          return;
        }
        String newReason = determineQueuingDelayReason(newReasonSource);
        activeDelayReasonSource = newReasonSource;
        if (typeChanged) {
          // Delay type changed (e.g., from RLS lookup to connecting). End the previous delay.
          pendingDelayEvents.add(DelayEvent.end(activeDelayType));
          pendingDelayEvents.add(DelayEvent.start(newType, newReason));
          activeDelayType = newType;
          activeDelayReason = newReason;
        } else if (newReason.equals(activeDelayReason)) {
          // Different source, same reason (e.g., an equal but distinct pick failure Status).
          return;
        } else {
          // Delay type is unchanged, but the reason changed (e.g., connection status detail
          // updated).
          activeDelayReason = newReason;
          pendingDelayEvents.add(DelayEvent.reasonChanged(newType, newReason));
        }
      }
      deliverDelayEvents();
    }

    /**
     * Ends the delay in progress, if it has not ended already, and delivers the callback. This
     * must happen before the stream is handed a real stream or is cancelled, so that the delay is
     * reported before any terminal callback.
     */
    void endDelay() {
      synchronized (delayLock) {
        if (delayEnded) {
          return;
        }
        delayEnded = true;
        // activeDelayType is set when the stream is created and only cleared here, so it is
        // non-null.
        pendingDelayEvents.add(DelayEvent.end(activeDelayType));
        activeDelayType = null;
        activeDelayReason = null;
        activeDelayReasonSource = null;
      }
      deliverDelayEvents();
      synchronized (delayLock) {
        boolean interrupted = false;
        while (deliveringDelayEvents) {
          try {
            delayLock.wait();
          } catch (InterruptedException e) {
            interrupted = true;
          }
        }
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }

    /**
     * Delivers the delay callbacks that have been decided but not delivered yet, in the order they
     * were decided, without holding any lock. If another thread is already delivering, this
     * returns immediately and that thread delivers what has just been queued.
     */
    void deliverDelayEvents() {
      synchronized (delayLock) {
        if (deliveringDelayEvents) {
          return;
        }
        deliveringDelayEvents = true;
      }
      boolean stillDelivering = true;
      try {
        while (true) {
          DelayEvent event;
          synchronized (delayLock) {
            event = pendingDelayEvents.poll();
            if (event == null) {
              // The flag must be cleared in the same critical section that found the queue empty,
              // otherwise an event queued by another thread could be left undelivered.
              deliveringDelayEvents = false;
              delayLock.notifyAll();
              stillDelivering = false;
              return;
            }
          }
          // Must not call the tracers while a lock is held, to prevent deadlocks.
          event.deliver(tracers);
        }
      } finally {
        if (stillDelivering) {
          // A tracer threw. Let a later transition deliver the rest instead of never delivering.
          synchronized (delayLock) {
            deliveringDelayEvents = false;
            delayLock.notifyAll();
          }
        }
      }
    }

    /**
     * Ends the delay and then hands this stream over to {@code stream}.
     *
     * <p>The delay must end first: {@link DelayedStream#setStream} starts {@code stream} inline if
     * this stream has been started, and a {@link FailingClientStream} closes the tracers as soon
     * as it is started, which must not happen before the delay has been reported. It also makes
     * {@code getRealStream() != null}, after which the delay state must no longer change.
     *
     * <p>This method must not be synchronized: {@link DelayedStream#setStream} deliberately
     * releases the stream monitor before calling into the real stream.
     */
    Runnable setStreamAndEndDelay(ClientStream stream) {
      endDelay();
      return setStream(stream);
    }

    /** Runnable may be null. */
    private Runnable createRealStream(ClientTransport transport, String authorityOverride) {
      ClientStream realStream;
      Context origContext = context.attach();
      try {
        realStream = transport.newStream(
            args.getMethodDescriptor(), args.getHeaders(), args.getCallOptions(),
            tracers);
      } finally {
        context.detach(origContext);
      }
      if (authorityOverride != null) {
        // User code provided authority takes precedence over the LB provided one; this will be
        // overwritten by an enqueud call from ClientCallImpl if the application sets an authority
        // override. We must call the real stream directly because stream.start() has likely already
        // been called on the delayed stream.
        realStream.setAuthority(authorityOverride);
      }
      return setStreamAndEndDelay(realStream);
    }

    @Override
    public void cancel(Status reason) {
      // The delay must end before the stream is cancelled: cancel() makes getRealStream() !=
      // null, after which the delay state must no longer change, and it may close the tracers
      // inline (see onEarlyCancellation()).
      endDelay();
      super.cancel(reason);
      synchronized (lock) {
        if (reportTransportTerminated != null) {
          boolean justRemovedAnElement = pendingStreams.remove(this);
          if (!hasPendingStreams() && justRemovedAnElement) {
            syncContext.executeLater(reportTransportNotInUse);
            if (pickerState.shutdownStatus != null) {
              syncContext.executeLater(reportTransportTerminated);
              reportTransportTerminated = null;
            }
          }
        }
      }
      syncContext.drain();
    }

    @Override
    protected void onEarlyCancellation(Status reason) {
      // The delay has already been ended by cancel(), which is the only caller of this method,
      // so the delay is always reported before the stream is closed.
      for (ClientStreamTracer tracer : tracers) {
        tracer.streamClosed(reason);
      }
    }

    @Override
    public void appendTimeoutInsight(InsightBuilder insight) {
      if (args.getCallOptions().isWaitForReady()) {
        insight.append("wait_for_ready");
        Status status = lastPickStatus;
        if (status != null && !status.isOk()) {
          insight.appendKeyValue("Last Pick Failure", status);
        }
      }
      super.appendTimeoutInsight(insight);
    }
  }

  /**
   * A delay callback that has been decided by a {@link PendingStream}, but has not been delivered
   * to the stream tracers yet. Callbacks are queued while the delay state lock is held and
   * delivered once it has been released, so that the tracers are never called under a lock.
   */
  private static final class DelayEvent {
    private enum Kind {
      START,
      REASON_CHANGED,
      END,
    }

    private final Kind kind;
    private final String delayType;
    @Nullable private final String delayReason;

    static DelayEvent start(String delayType, String delayReason) {
      return new DelayEvent(Kind.START, delayType, delayReason);
    }

    static DelayEvent reasonChanged(String delayType, String delayReason) {
      return new DelayEvent(Kind.REASON_CHANGED, delayType, delayReason);
    }

    static DelayEvent end(String delayType) {
      return new DelayEvent(Kind.END, delayType, null);
    }

    private DelayEvent(Kind kind, String delayType, @Nullable String delayReason) {
      this.kind = kind;
      this.delayType = delayType;
      this.delayReason = delayReason;
    }

    void deliver(ClientStreamTracer[] tracers) {
      for (ClientStreamTracer tracer : tracers) {
        if (kind == Kind.START) {
          tracer.recordDelayStart(delayType, delayReason);
        } else if (kind == Kind.REASON_CHANGED) {
          tracer.recordDelayReasonChanged(delayType, delayReason);
        } else {
          tracer.recordDelayEnd(delayType);
        }
      }
    }
  }

  static final class PickerState {
    /**
     * The last picker that {@link #reprocess} has used. May be set to null when the channel has
     * moved to idle.
     */
    @Nullable
    final SubchannelPicker lastPicker;
    /**
     * When {@code shutdownStatus != null && !hasPendingStreams()}, then the transport is considered
     * terminated.
     */
    @Nullable
    final Status shutdownStatus;

    private PickerState(SubchannelPicker lastPicker, Status shutdownStatus) {
      this.lastPicker = lastPicker;
      this.shutdownStatus = shutdownStatus;
    }

    public PickerState withPicker(SubchannelPicker newPicker) {
      return new PickerState(newPicker, this.shutdownStatus);
    }

    public PickerState withShutdownStatus(Status newShutdownStatus) {
      return new PickerState(this.lastPicker, newShutdownStatus);
    }
  }
}
