/*
 * Copyright 2017, gRPC Authors All rights reserved.
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
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.errorprone.annotations.MustBeClosed;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import javax.annotation.Nullable;

/**
 * Scoped context management. Facilitates installing and removing a particular {@link
 * Context.CancellableContext} as current, and supports the try-with-resources idiom in Java 7
 * via Closeable.
 * This class automatically closes the CancellableContext when the unit of work is complete.
 *
 * <p>When more flexibility is needed, {@link #close()} can be invoked explicitly.
 */
public class WithCancellableContext implements Closeable {
  private final Context.CancellableContext cancellableContext;
  private Context toRestore;

  private WithCancellableContext(Context.CancellableContext cancellableContext) {
    this.cancellableContext = cancellableContext;
    toRestore = cancellableContext.attach();
  }

  @MustBeClosed
  public static WithCancellableContext enter(Context.CancellableContext cancellableContext) {
    return new WithCancellableContext(cancellableContext);
  }

  @Override
  public void close() throws IOException {
    cancellableContext.detach(toRestore);
    cancellableContext.cancel(null);
  }

  /**
   * Gets a {@link CancellableContextApplier}.
   */
  public static CancellableContextApplier with(Context.CancellableContext c) {
    return new CancellableContextApplier(c);
  }

  /**
   * An wrapper for the supplied context, which can be used
   * to apply a unit of work inside the CancellableContext, and cancelling the context when done.
   *
   * <p>Usage:
   * <pre>{@code
   * WithCancellableContext.with(cancellableContext).run(
   *     new Runnable() {
   *       public void run() {
   *         // do something in context
   *       }
   *     });
   * // original context automatically restored and cancellableContext automatically cancelled
   * }</pre>
   */
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
        addCallback(
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
