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

package io.grpc.internal;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.SynchronizationContext;

/**
 * This wrapper class can add retry capability to any polling {@link NameResolver} implementation
 * that supports calling {@link ResolutionResultListener}s with the outcome of each resolution.
 *
 * <p>The {@link NameResolver} used with this
 */
public final class RetryingNameResolver extends ForwardingNameResolver {
  public static NameResolver wrap(NameResolver retriedNameResolver, Args args) {
    // For migration, this might become conditional
    return new RetryingNameResolver(
        retriedNameResolver,
        new BackoffPolicyRetryScheduler(
            new ExponentialBackoffPolicy.Provider(),
            args.getScheduledExecutorService(),
            args.getSynchronizationContext()),
        args.getSynchronizationContext());
  }

  private final NameResolver retriedNameResolver;
  private final RetryScheduler retryScheduler;
  private final SynchronizationContext syncContext;

  /**
   * Creates a new {@link RetryingNameResolver}.
   *
   * @param retriedNameResolver A {@link NameResolver} that will have failed attempt retried.
   * @param retryScheduler Used to schedule the retry attempts.
   */
  RetryingNameResolver(NameResolver retriedNameResolver, RetryScheduler retryScheduler,
      SynchronizationContext syncContext) {
    super(retriedNameResolver);
    this.retriedNameResolver = retriedNameResolver;
    this.retryScheduler = retryScheduler;
    this.syncContext = syncContext;
  }

  @Override
  public void start(Listener2 listener) {
    super.start(new RetryingListener(listener));
  }

  @Override
  public void shutdown() {
    super.shutdown();
    retryScheduler.reset();
  }

  /**
   * Used to get the underlying {@link NameResolver} that is getting its failed attempts retried.
   */
  @VisibleForTesting
  NameResolver getRetriedNameResolver() {
    return retriedNameResolver;
  }

  @VisibleForTesting
  class DelayedNameResolverRefresh implements Runnable {
    @Override
    public void run() {
      refresh();
    }
  }

  private class RetryingListener extends Listener2 {
    private Listener2 delegateListener;

    RetryingListener(Listener2 delegateListener) {
      this.delegateListener = delegateListener;
    }

    @Override
    public void onResult(ResolutionResult resolutionResult) {
      syncContext.execute(() -> onResult2(resolutionResult));
    }

    @Override
    public Status onResult2(ResolutionResult resolutionResult) {
      Status status = delegateListener.onResult2(resolutionResult);
      if (status.isOk()) {
        retryScheduler.reset();
      } else {
        retryScheduler.schedule(new DelayedNameResolverRefresh());
      }
      return status;
    }

    @Override
    public void onError(Status error) {
      delegateListener.onError(error);
      syncContext.execute(() -> retryScheduler.schedule(new DelayedNameResolverRefresh()));
    }
  }
}
