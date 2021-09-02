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

package io.grpc.binder.internal;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import com.google.common.annotations.VisibleForTesting;
import io.grpc.Status;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Manages an Android binding that's restricted to at most one connection to the remote Service.
 *
 * <p>A note on synchronization & locking in this class. Clients of this class are likely to manage
 * their own internal state via synchronization. In order to avoid deadlocks, we must not hold any
 * locks while calling observer callbacks.
 *
 * <p>For this reason, while internal consistency is handled with synchronization (the state field),
 * consistency on our observer callbacks is ensured by doing everything on the application's main
 * thread.
 */
@ThreadSafe
final class ServiceBinding implements Bindable, ServiceConnection {

  private static final Logger logger = Logger.getLogger(ServiceBinding.class.getName());

  // States can only ever transition in one direction.
  private enum State {
    NOT_BINDING,
    BINDING,
    BOUND,
    UNBOUND,
  }

  private final Intent bindIntent;
  private final int bindFlags;
  private final Observer observer;
  private final Executor mainThreadExecutor;

  @GuardedBy("this")
  private State state;

  // The following fields are intentionally not guarded, since (aside from the constructor),
  // they're only modified in the main thread. The constructor contains a synchronized block
  // to ensure there's a write barrier when these fields are first written.

  @Nullable private Context sourceContext; // Only null in the unbound state.

  private State reportedState; // Only used on the main thread.

  @AnyThread
  ServiceBinding(
      Executor mainThreadExecutor,
      Context sourceContext,
      Intent bindIntent,
      int bindFlags,
      Observer observer) {
    // We need to synchronize here ensure other threads see all
    // non-final fields initialized after the constructor.
    synchronized (this) {
      this.bindIntent = bindIntent;
      this.bindFlags = bindFlags;
      this.observer = observer;
      this.sourceContext = sourceContext;
      this.mainThreadExecutor = mainThreadExecutor;
      state = State.NOT_BINDING;
      reportedState = State.NOT_BINDING;
    }
  }

  @MainThread
  private void notifyBound(IBinder binder) {
    if (reportedState == State.NOT_BINDING) {
      reportedState = State.BOUND;
      logger.log(Level.FINEST, "notify bound - notifying");
      observer.onBound(binder);
    }
  }

  @MainThread
  private void notifyUnbound(Status reason) {
    logger.log(Level.FINEST, "notify unbound ", reason);
    clearReferences();
    if (reportedState != State.UNBOUND) {
      reportedState = State.UNBOUND;
      logger.log(Level.FINEST, "notify unbound - notifying");
      observer.onUnbound(reason);
    }
  }

  @AnyThread
  @Override
  public synchronized void bind() {
    if (state == State.NOT_BINDING) {
      state = State.BINDING;
      Status bindResult = bindInternal(sourceContext, bindIntent, this, bindFlags);
      if (!bindResult.isOk()) {
        state = State.UNBOUND;
        mainThreadExecutor.execute(() -> notifyUnbound(bindResult));
      }
    }
  }

  private static Status bindInternal(
      Context context, Intent bindIntent, ServiceConnection conn, int flags) {
    try {
      if (!context.bindService(bindIntent, conn, flags)) {
        return Status.UNIMPLEMENTED.withDescription(
            "bindService(" + bindIntent + ") returned false");
      }
      return Status.OK;
    } catch (SecurityException e) {
      return Status.PERMISSION_DENIED.withCause(e).withDescription(
          "SecurityException from bindService");
    } catch (RuntimeException e) {
      return Status.INTERNAL.withCause(e).withDescription(
          "RuntimeException from bindService");
    }
  }

  @Override
  @AnyThread
  public void unbind() {
    unbindInternal(Status.CANCELLED);
  }

  @AnyThread
  void unbindInternal(Status reason) {
    Context unbindFrom = null;
    synchronized (this) {
      if (state == State.BINDING || state == State.BOUND) {
        unbindFrom = sourceContext;
      }
      state = State.UNBOUND;
    }
    mainThreadExecutor.execute(() -> notifyUnbound(reason));
    if (unbindFrom != null) {
      unbindFrom.unbindService(this);
    }
  }

  @MainThread
  private void clearReferences() {
    sourceContext = null;
  }

  @Override
  @MainThread
  public void onServiceConnected(ComponentName className, IBinder binder) {
    boolean bound = false;
    synchronized (this) {
      if (state == State.BINDING) {
        state = State.BOUND;
        bound = true;
      }
    }
    if (bound) {
      // We call notify directly because we know we're on the main thread already.
      // (every millisecond counts in this path).
      notifyBound(binder);
    }
  }

  @Override
  @MainThread
  public void onServiceDisconnected(ComponentName name) {
    unbindInternal(Status.UNAVAILABLE.withDescription("onServiceDisconnected: " + name));
  }

  @Override
  @MainThread
  public void onNullBinding(ComponentName name) {
    unbindInternal(Status.UNIMPLEMENTED.withDescription("onNullBinding: " + name));
  }

  @Override
  @MainThread
  public void onBindingDied(ComponentName name) {
    unbindInternal(Status.UNAVAILABLE.withDescription("onBindingDied: " + name));
  }

  @VisibleForTesting
  synchronized boolean isSourceContextCleared() {
    return sourceContext == null;
  }
}
