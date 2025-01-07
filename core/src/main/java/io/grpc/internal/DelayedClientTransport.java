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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.concurrent.Executor;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

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
      while (true) {
        if (state.shutdownStatus != null) {
          return new FailingClientStream(state.shutdownStatus, tracers);
        }
        if (state.lastPicker != null) {
          PickResult pickResult = state.lastPicker.pickSubchannel(args);
          callOptions = args.getCallOptions();
          // User code provided authority takes precedence over the LB provided one.
          if (callOptions.getAuthority() == null
              && pickResult.getAuthorityOverride() != null) {
            callOptions = callOptions.withAuthority(pickResult.getAuthorityOverride());
          }
          ClientTransport transport = GrpcUtil.getTransportFromPickResult(pickResult,
              callOptions.isWaitForReady());
          if (transport != null) {
            return transport.newStream(
                args.getMethodDescriptor(), args.getHeaders(), callOptions,
                tracers);
          }
        }
        // This picker's conclusion is "buffer".  If there hasn't been a newer picker set (possible
        // race with reprocess()), we will buffer the RPC.  Otherwise, will try with the new picker.
        synchronized (lock) {
          PickerState newerState = pickerState;
          if (state == newerState) {
            return createPendingStream(args, tracers);
          }
          state = newerState;
        }
      }
    } finally {
      syncContext.drain();
    }
  }

  /**
   * Caller must call {@code syncContext.drain()} outside of lock because this method may
   * schedule tasks on syncContext.
   */
  @GuardedBy("lock")
  private PendingStream createPendingStream(
      PickSubchannelArgs args, ClientStreamTracer[] tracers) {
    PendingStream pendingStream = new PendingStream(args, tracers);
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
   * Prevents creating any new streams.  Buffered streams are not failed and may still proceed
   * when {@link #reprocess} is called.  The delayed transport will be terminated when there is no
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
            listener.transportShutdown(status);
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
        Runnable runnable = stream.setStream(
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
      // User code provided authority takes precedence over the LB provided one.
      if (callOptions.getAuthority() == null && pickResult.getAuthorityOverride() != null) {
        stream.setAuthority(pickResult.getAuthorityOverride());
      }
      final ClientTransport transport = GrpcUtil.getTransportFromPickResult(pickResult,
          callOptions.isWaitForReady());
      if (transport != null) {
        Executor executor = defaultAppExecutor;
        // createRealStream may be expensive. It will start real streams on the transport. If
        // there are pending requests, they will be serialized too, which may be expensive. Since
        // we are now on transport thread, we need to offload the work to an executor.
        if (callOptions.getExecutor() != null) {
          executor = callOptions.getExecutor();
        }
        Runnable runnable = stream.createRealStream(transport);
        if (runnable != null) {
          executor.execute(runnable);
        }
        toRemove.add(stream);
      }  // else: stay pending
    }

    synchronized (lock) {
      // Between this synchronized and the previous one:
      //   - Streams may have been cancelled, which may turn pendingStreams into emptiness.
      //   - shutdown() may be called, which may turn pendingStreams into null.
      if (!hasPendingStreams()) {
        return;
      }
      pendingStreams.removeAll(toRemove);
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

  private class PendingStream extends DelayedStream {
    private final PickSubchannelArgs args;
    private final Context context = Context.current();
    private final ClientStreamTracer[] tracers;

    private PendingStream(PickSubchannelArgs args, ClientStreamTracer[] tracers) {
      this.args = args;
      this.tracers = tracers;
    }

    /** Runnable may be null. */
    private Runnable createRealStream(ClientTransport transport) {
      ClientStream realStream;
      Context origContext = context.attach();
      try {
        realStream = transport.newStream(
            args.getMethodDescriptor(), args.getHeaders(), args.getCallOptions(),
            tracers);
      } finally {
        context.detach(origContext);
      }
      return setStream(realStream);
    }

    @Override
    public void cancel(Status reason) {
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
      for (ClientStreamTracer tracer : tracers) {
        tracer.streamClosed(reason);
      }
    }

    @Override
    public void appendTimeoutInsight(InsightBuilder insight) {
      if (args.getCallOptions().isWaitForReady()) {
        insight.append("wait_for_ready");
      }
      super.appendTimeoutInsight(insight);
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
