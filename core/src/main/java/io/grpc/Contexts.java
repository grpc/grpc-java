/*
 * Copyright 2015, gRPC Authors All rights reserved.
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

package io.grpc;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

/**
 * Utility methods for working with {@link Context}s in GRPC.
 */
public final class Contexts {

  private Contexts() {
  }

  /**
   * Make the provided {@link Context} {@link Context#current()} for the creation of a listener
   * to a received call and for all events received by that listener.
   *
   * <p>This utility is expected to be used by {@link ServerInterceptor} implementations that need
   * to augment the {@link Context} in which the application does work when receiving events from
   * the client.
   *
   * @param context to make {@link Context#current()}.
   * @param call used to send responses to client.
   * @param headers received from client.
   * @param next handler used to create the listener to be wrapped.
   * @return listener that will receive events in the scope of the provided context.
   */
  public static <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        Context context,
        ServerCall<ReqT, RespT> call,
        Metadata headers,
        ServerCallHandler<ReqT, RespT> next) {
    Context previous = context.attach();
    try {
      return new ContextualizedServerCallListener<ReqT>(
          next.startCall(call, headers),
          context);
    } finally {
      context.detach(previous);
    }
  }

  /**
   * Implementation of {@link io.grpc.ForwardingServerCallListener} that attaches a context before
   * dispatching calls to the delegate and detaches them after the call completes.
   */
  private static class ContextualizedServerCallListener<ReqT> extends
      ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {
    private final Context context;

    public ContextualizedServerCallListener(ServerCall.Listener<ReqT> delegate, Context context) {
      super(delegate);
      this.context = context;
    }

    @Override
    public void onMessage(ReqT message) {
      Context previous = context.attach();
      try {
        super.onMessage(message);
      } finally {
        context.detach(previous);
      }
    }

    @Override
    public void onHalfClose() {
      Context previous = context.attach();
      try {
        super.onHalfClose();
      } finally {
        context.detach(previous);
      }
    }

    @Override
    public void onCancel() {
      Context previous = context.attach();
      try {
        super.onCancel();
      } finally {
        context.detach(previous);
      }
    }

    @Override
    public void onComplete() {
      Context previous = context.attach();
      try {
        super.onComplete();
      } finally {
        context.detach(previous);
      }
    }

    @Override
    public void onReady() {
      Context previous = context.attach();
      try {
        super.onReady();
      } finally {
        context.detach(previous);
      }
    }
  }

  /**
   * Returns the {@link Status} of a cancelled context or {@code null} if the context
   * is not cancelled.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1975")
  public static Status statusFromCancelled(Context context) {
    Preconditions.checkNotNull(context, "context must not be null");
    if (!context.isCancelled()) {
      return null;
    }

    Throwable cancellationCause = context.cancellationCause();
    if (cancellationCause == null) {
      return Status.CANCELLED;
    }
    if (cancellationCause instanceof TimeoutException) {
      return Status.DEADLINE_EXCEEDED
          .withDescription(cancellationCause.getMessage())
          .withCause(cancellationCause);
    }
    Status status = Status.fromThrowable(cancellationCause);
    if (Status.Code.UNKNOWN.equals(status.getCode())
        && status.getCause() == cancellationCause) {
      // If fromThrowable could not determine a status, then
      // just return CANCELLED.
      return Status.CANCELLED.withCause(cancellationCause);
    }
    return status.withCause(cancellationCause);
  }

  /**
   * A utility for performing an operation inside of a
   * {@link io.grpc.Context.CancellableContext CancellableContext}, and then cancelling the context
   * after it is done.
   *
   * <p>Note: Because gRPC must be compatible with Java 1.6, this class does not support the
   * try-with-resources idiom.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/3506")
  public static CancellableContextApplier cancellableContextApplier(
      Context.CancellableContext cancellableContext) {
    return new CancellableContextApplier(cancellableContext);
  }

  public static class CancellableContextApplier {
    private final Context.CancellableContext cancellableContext;

    private CancellableContextApplier(Context.CancellableContext cancellableContext) {
      this.cancellableContext = cancellableContext;
    }

    /**
     * Runs the specified {@code runnable} in the specified {@code cancellableContext}, and
     * cancels the context when the function returns or throws an exception.
     */
    public void run(Runnable runnable) {
      try {
        cancellableContext.run(runnable);
      } finally {
        cancellableContext.cancel(null);
      }
    }

    /**
     * Calls the specified {@code callable} in the specified {@code cancellableContext}, and
     * cancels the context when the function returns or throws an exception.
     */
    public <T> T call(Callable<T> callable) throws Exception {
      try {
        return cancellableContext.call(callable);
      } finally {
        cancellableContext.cancel(null);
      }
    }

    /**
     * Calls the specified async callable in the specified {@code cancellableContext}. The
     * context is cancelled when the asyncCallable's future completes or if the asyncCallable
     * throws an exception. If asyncCallable threw an exception, it will be thrown from
     * {@link ListenableFuture#get()}.
     *
     * @return A ListenableFuture whose listeners are notified after the context is cancelled. If
     *     the returned ListenableFuture is cancelled, then {@code cancellableContext} will be
     *     cancelled.
     */
    public <T> ListenableFuture<T> callAsync(final Callable<ListenableFuture<T>> asyncCallable) {
      final SettableFuture<T> proxyFuture = SettableFuture.create();
      try {
        ListenableFuture<T> originalFuture = cancellableContext.call(asyncCallable);

        // Ensure that the CancellableContext is cancelled before notifying proxyFuture
        Futures.addCallback(
            originalFuture,
            new FutureCallback<T>() {
              @Override
              public void onSuccess(@Nullable T t) {
                try {
                  cancellableContext.cancel(null);
                } finally {
                  proxyFuture.set(t);
                }
              }

              @Override
              public void onFailure(Throwable throwable) {
                try {
                  cancellableContext.cancel(throwable);
                } finally {
                  // This will cause proxyFuture's onFailure to fire, and we will cancel the
                  // CancellableContext a second time. This is fine because only the first call
                  // has any effect.
                  proxyFuture.setException(throwable);
                }
              }
            },
            MoreExecutors.directExecutor());

        // If the user cancels proxyFuture, then cancel the CancellableContext
        addCallback(
            proxyFuture,
            new FutureCallback<T>() {
              @Override
              public void onSuccess(@Nullable T result) {
                // We should only enter here when originalFuture's onSuccess callback has fired
                assert cancellableContext.isCancelled();
              }

              @Override
              public void onFailure(Throwable t) {
                // Fired when originalFuture fails, or if the user cancels proxyFuture
                cancellableContext.cancel(t);
              }
            },
            MoreExecutors.directExecutor());
      } catch (Exception e) {
        cancellableContext.cancel(e);
        proxyFuture.setException(e);
      }
      return proxyFuture;
    }
  }

  // com.google.common.util.concurrent.Futures is @Beta, so we can't use it in gRPC.
  // copy-paste from guava
  private static <V> void addCallback(final ListenableFuture<V> future,
      final FutureCallback<? super V> callback, Executor executor) {
    Preconditions.checkNotNull(callback);
    Runnable callbackListener = new Runnable() {
      @Override
      public void run() {
        final V value;
        try {
          value = getUninterruptibly(future);
        } catch (ExecutionException e) {
          callback.onFailure(e.getCause());
          return;
        } catch (RuntimeException e) {
          callback.onFailure(e);
          return;
        } catch (Error e) {
          callback.onFailure(e);
          return;
        }
        callback.onSuccess(value);
      }
    };
    future.addListener(callbackListener, executor);
  }

  // com.google.common.util.concurrent.Uninterruptables is @Beta, so we can't use it in gRPC.
  // copy-paste from guava.
  private static <V> V getUninterruptibly(Future<V> future)
      throws ExecutionException {
    boolean interrupted = false;
    try {
      while (true) {
        try {
          return future.get();
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
