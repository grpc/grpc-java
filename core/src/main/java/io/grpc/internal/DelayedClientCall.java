/*
 * Copyright 2020 The gRPC Authors
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
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import io.grpc.Attributes;
import io.grpc.ClientCall;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * A call that queues requests before the transport is available, and delegates to a real call
 * implementation when the transport is available.
 *
 * <p>{@code ClientCall} itself doesn't require thread-safety. However, the state of {@code
 * DelayedCall} may be internally altered by different threads, thus internal synchronization is
 * necessary.
 */
class DelayedClientCall<ReqT, RespT> extends ClientCall<ReqT, RespT> {
  private static final Logger logger = Logger.getLogger(DelayedClientCall.class.getName());
  /**
   * A timer to monitor the initial deadline. The timer must be cancelled on transition to the real
   * call.
   */
  @Nullable
  private final ScheduledFuture<?> initialDeadlineMonitor;
  private final Executor callExecutor;
  private final Context context;
  /** {@code true} once realCall is valid and all pending calls have been drained. */
  private volatile boolean passThrough;
  /**
   * Non-{@code null} iff start has been called. Used to assert methods are called in appropriate
   * order, but also used if an error occurs before {@code realCall} is set.
   */
  private Listener<RespT> listener;
  // Must hold {@code this} lock when setting.
  private ClientCall<ReqT, RespT> realCall;
  @GuardedBy("this")
  private Status error;
  @GuardedBy("this")
  private List<Runnable> pendingRunnables = new ArrayList<>();
  @GuardedBy("this")
  private DelayedListener<RespT> delayedListener;

  DelayedClientCall(
      Executor callExecutor, ScheduledExecutorService scheduler, @Nullable Deadline deadline) {
    this.callExecutor = checkNotNull(callExecutor, "callExecutor");
    checkNotNull(scheduler, "scheduler");
    context = Context.current();
    initialDeadlineMonitor = scheduleDeadlineIfNeeded(scheduler, deadline);
  }

  @Nullable
  private ScheduledFuture<?> scheduleDeadlineIfNeeded(
      ScheduledExecutorService scheduler, @Nullable Deadline deadline) {
    Deadline contextDeadline = context.getDeadline();
    if (deadline == null && contextDeadline == null) {
      return null;
    }
    long remainingNanos = Long.MAX_VALUE;
    if (deadline != null) {
      remainingNanos = Math.min(remainingNanos, deadline.timeRemaining(NANOSECONDS));
    }
    if (contextDeadline != null && contextDeadline.timeRemaining(NANOSECONDS) < remainingNanos) {
      remainingNanos = contextDeadline.timeRemaining(NANOSECONDS);
      if (logger.isLoggable(Level.FINE)) {
        StringBuilder builder =
            new StringBuilder(
                String.format(
                    "Call timeout set to '%d' ns, due to context deadline.", remainingNanos));
        if (deadline == null) {
          builder.append(" Explicit call timeout was not set.");
        } else {
          long callTimeout = deadline.timeRemaining(TimeUnit.NANOSECONDS);
          builder.append(String.format(" Explicit call timeout was '%d' ns.", callTimeout));
        }
        logger.fine(builder.toString());
      }
    }
    long seconds = Math.abs(remainingNanos) / TimeUnit.SECONDS.toNanos(1);
    long nanos = Math.abs(remainingNanos) % TimeUnit.SECONDS.toNanos(1);
    final StringBuilder buf = new StringBuilder();
    if (remainingNanos < 0) {
      buf.append("ClientCall started after deadline exceeded. Deadline exceeded after -");
    } else {
      buf.append("Deadline exceeded after ");
    }
    buf.append(seconds);
    buf.append(String.format(Locale.US, ".%09d", nanos));
    buf.append("s. ");
    /** Cancels the call if deadline exceeded prior to the real call being set. */
    class DeadlineExceededRunnable implements Runnable {
      @Override
      public void run() {
        cancel(
            Status.DEADLINE_EXCEEDED.withDescription(buf.toString()),
            // We should not cancel the call if the realCall is set because there could be a
            // race between cancel() and realCall.start(). The realCall will handle deadline by
            // itself.
            /* onlyCancelPendingCall= */ true);
      }
    }

    return scheduler.schedule(new DeadlineExceededRunnable(), remainingNanos, NANOSECONDS);
  }

  /**
   * Transfers all pending and future requests and mutations to the given call.
   *
   * <p>No-op if either this method or {@link #cancel} have already been called.
   */
  // When this method returns, passThrough is guaranteed to be true
  final void setCall(ClientCall<ReqT, RespT> call) {
    synchronized (this) {
      // If realCall != null, then either setCall() or cancel() has been called.
      if (realCall != null) {
        return;
      }
      setRealCall(checkNotNull(call, "call"));
    }
    drainPendingCalls();
  }

  @Override
  public final void start(Listener<RespT> listener, final Metadata headers) {
    checkState(this.listener == null, "already started");
    Status savedError;
    boolean savedPassThrough;
    synchronized (this) {
      this.listener = checkNotNull(listener, "listener");
      // If error != null, then cancel() has been called and was unable to close the listener
      savedError = error;
      savedPassThrough = passThrough;
      if (!savedPassThrough) {
        listener = delayedListener = new DelayedListener<>(listener);
      }
    }
    if (savedError != null) {
      callExecutor.execute(new CloseListenerRunnable(listener, savedError));
      return;
    }
    if (savedPassThrough) {
      realCall.start(listener, headers);
    } else {
      final Listener<RespT> finalListener = listener;
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          realCall.start(finalListener, headers);
        }
      });
    }
  }

  // When this method returns, passThrough is guaranteed to be true
  @Override
  public final void cancel(@Nullable final String message, @Nullable final Throwable cause) {
    Status status = Status.CANCELLED;
    if (message != null) {
      status = status.withDescription(message);
    } else {
      status = status.withDescription("Call cancelled without message");
    }
    if (cause != null) {
      status = status.withCause(cause);
    }
    cancel(status, false);
  }

  /**
   * Cancels the call unless {@code realCall} is set and {@code onlyCancelPendingCall} is true.
   */
  private void cancel(final Status status, boolean onlyCancelPendingCall) {
    boolean delegateToRealCall = true;
    Listener<RespT> listenerToClose = null;
    synchronized (this) {
      // If realCall != null, then either setCall() or cancel() has been called
      if (realCall == null) {
        @SuppressWarnings("unchecked")
        ClientCall<ReqT, RespT> noopCall = (ClientCall<ReqT, RespT>) NOOP_CALL;
        setRealCall(noopCall);
        delegateToRealCall = false;
        // If listener == null, then start() will later call listener with 'error'
        listenerToClose = listener;
        error = status;
      } else if (onlyCancelPendingCall) {
        return;
      }
    }
    if (delegateToRealCall) {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          realCall.cancel(status.getDescription(), status.getCause());
        }
      });
    } else {
      if (listenerToClose != null) {
        callExecutor.execute(new CloseListenerRunnable(listenerToClose, status));
      }
      drainPendingCalls();
    }
    callCancelled();
  }

  protected void callCancelled() {
  }

  private void delayOrExecute(Runnable runnable) {
    synchronized (this) {
      if (!passThrough) {
        pendingRunnables.add(runnable);
        return;
      }
    }
    runnable.run();
  }

  /**
   * Called to transition {@code passThrough} to {@code true}. This method is not safe to be called
   * multiple times; the caller must ensure it will only be called once, ever. {@code this} lock
   * should not be held when calling this method.
   */
  private void drainPendingCalls() {
    assert realCall != null;
    assert !passThrough;
    List<Runnable> toRun = new ArrayList<>();
    DelayedListener<RespT> delayedListener ;
    while (true) {
      synchronized (this) {
        if (pendingRunnables.isEmpty()) {
          pendingRunnables = null;
          passThrough = true;
          delayedListener = this.delayedListener;
          break;
        }
        // Since there were pendingCalls, we need to process them. To maintain ordering we can't set
        // passThrough=true until we run all pendingCalls, but new Runnables may be added after we
        // drop the lock. So we will have to re-check pendingCalls.
        List<Runnable> tmp = toRun;
        toRun = pendingRunnables;
        pendingRunnables = tmp;
      }
      for (Runnable runnable : toRun) {
        // Must not call transport while lock is held to prevent deadlocks.
        // TODO(ejona): exception handling
        runnable.run();
      }
      toRun.clear();
    }
    if (delayedListener != null) {
      final DelayedListener<RespT> listener = delayedListener;
      class DrainListenerRunnable extends ContextRunnable {
        DrainListenerRunnable() {
          super(context);
        }

        @Override
        public void runInContext() {
          listener.drainPendingCallbacks();
        }
      }

      callExecutor.execute(new DrainListenerRunnable());
    }
  }

  @GuardedBy("this")
  private void setRealCall(ClientCall<ReqT, RespT> realCall) {
    checkState(this.realCall == null, "realCall already set to %s", this.realCall);
    if (initialDeadlineMonitor != null) {
      initialDeadlineMonitor.cancel(false);
    }
    this.realCall = realCall;
  }

  @VisibleForTesting
  final ClientCall<ReqT, RespT> getRealCall() {
    return realCall;
  }

  @Override
  public final void sendMessage(final ReqT message) {
    if (passThrough) {
      realCall.sendMessage(message);
    } else {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          realCall.sendMessage(message);
        }
      });
    }
  }

  @Override
  public final void setMessageCompression(final boolean enable) {
    if (passThrough) {
      realCall.setMessageCompression(enable);
    } else {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          realCall.setMessageCompression(enable);
        }
      });
    }
  }

  @Override
  public final void request(final int numMessages) {
    if (passThrough) {
      realCall.request(numMessages);
    } else {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          realCall.request(numMessages);
        }
      });
    }
  }

  @Override
  public final void halfClose() {
    delayOrExecute(new Runnable() {
      @Override
      public void run() {
        realCall.halfClose();
      }
    });
  }

  @Override
  public final boolean isReady() {
    if (passThrough) {
      return realCall.isReady();
    } else {
      return false;
    }
  }

  @Override
  public final Attributes getAttributes() {
    ClientCall<ReqT, RespT> savedRealCall;
    synchronized (this) {
      savedRealCall = realCall;
    }
    if (savedRealCall != null) {
      return savedRealCall.getAttributes();
    } else {
      return Attributes.EMPTY;
    }
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("realCall", realCall)
        .toString();
  }

  private final class CloseListenerRunnable extends ContextRunnable {
    final Listener<RespT> listener;
    final Status status;

    CloseListenerRunnable(Listener<RespT> listener, Status status) {
      super(context);
      this.listener = listener;
      this.status = status;
    }

    @Override
    public void runInContext() {
      listener.onClose(status, new Metadata());
    }
  }

  private static final class DelayedListener<RespT> extends Listener<RespT> {
    private final Listener<RespT> realListener;
    private volatile boolean passThrough;
    @GuardedBy("this")
    private List<Runnable> pendingCallbacks = new ArrayList<>();

    public DelayedListener(Listener<RespT> listener) {
      this.realListener = listener;
    }

    private void delayOrExecute(Runnable runnable) {
      synchronized (this) {
        if (!passThrough) {
          pendingCallbacks.add(runnable);
          return;
        }
      }
      runnable.run();
    }

    @Override
    public void onHeaders(final Metadata headers) {
      if (passThrough) {
        realListener.onHeaders(headers);
      } else {
        delayOrExecute(new Runnable() {
          @Override
          public void run() {
            realListener.onHeaders(headers);
          }
        });
      }
    }

    @Override
    public void onMessage(final RespT message) {
      if (passThrough) {
        realListener.onMessage(message);
      } else {
        delayOrExecute(new Runnable() {
          @Override
          public void run() {
            realListener.onMessage(message);
          }
        });
      }
    }

    @Override
    public void onClose(final Status status, final Metadata trailers) {
      delayOrExecute(new Runnable() {
        @Override
        public void run() {
          realListener.onClose(status, trailers);
        }
      });
    }

    @Override
    public void onReady() {
      if (passThrough) {
        realListener.onReady();
      } else {
        delayOrExecute(new Runnable() {
          @Override
          public void run() {
            realListener.onReady();
          }
        });
      }
    }

    void drainPendingCallbacks() {
      assert !passThrough;
      List<Runnable> toRun = new ArrayList<>();
      while (true) {
        synchronized (this) {
          if (pendingCallbacks.isEmpty()) {
            pendingCallbacks = null;
            passThrough = true;
            break;
          }
          // Since there were pendingCallbacks, we need to process them. To maintain ordering we
          // can't set passThrough=true until we run all pendingCallbacks, but new Runnables may be
          // added after we drop the lock. So we will have to re-check pendingCallbacks.
          List<Runnable> tmp = toRun;
          toRun = pendingCallbacks;
          pendingCallbacks = tmp;
        }
        for (Runnable runnable : toRun) {
          // Avoid calling listener while lock is held to prevent deadlocks.
          // TODO(ejona): exception handling
          runnable.run();
        }
        toRun.clear();
      }
    }
  }

  private static final ClientCall<Object, Object> NOOP_CALL = new ClientCall<Object, Object>() {
    @Override
    public void start(Listener<Object> responseListener, Metadata headers) {}

    @Override
    public void request(int numMessages) {}

    @Override
    public void cancel(String message, Throwable cause) {}

    @Override
    public void halfClose() {}

    @Override
    public void sendMessage(Object message) {}

    // Always returns {@code false}, since this is only used when the startup of the call fails.
    @Override
    public boolean isReady() {
      return false;
    }
  };
}
