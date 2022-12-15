/*
 * Copyright 2022 The gRPC Authors
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
import io.grpc.SynchronizationContext;
import java.util.concurrent.ScheduledExecutorService;

/**
 * This wrapper class can add retry capability to any polling {@link NameResolver} implementation
 * that supports calling {@link ResolutionResultListener}s with the outcome of each resolution.
 *
 * <p>The {@link NameResolver} used with this
 */
final class RetryingNameResolver extends ForwardingNameResolver {

  private final NameResolver retriedNameResolver;
  private final RetryScheduler retryScheduler;

  /**
   * Creates a new {@link RetryingNameResolver}.
   *
   * @param retriedNameResolver A {@link NameResolver} that will have failed attempt retried.
   * @param backoffPolicyProvider Provides the policy used to backoff from retry attempts
   * @param scheduledExecutorService Executes any retry attempts
   * @param syncContext All retries happen within the given {@code SyncContext}
   */
  RetryingNameResolver(NameResolver retriedNameResolver,
      BackoffPolicy.Provider backoffPolicyProvider,
      ScheduledExecutorService scheduledExecutorService,
      SynchronizationContext syncContext) {
    super(retriedNameResolver);
    this.retriedNameResolver = retriedNameResolver;
    this.retriedNameResolver.addResolutionResultListener(new RetryResolutionResultListener());
    this.retryScheduler = new RetryScheduler(new DelayedNameResolverRefresh(),
        scheduledExecutorService, syncContext, backoffPolicyProvider);
  }

  @Override
  public void shutdown() {
    super.shutdown();
    retryScheduler.reset();
  }

  /**
   * @return The {@link NameResolver} that is getting its failed attempts retried.
   */
  public NameResolver getRetriedNameResolver() {
    return retriedNameResolver;
  }

  @VisibleForTesting
  class DelayedNameResolverRefresh implements Runnable {
    @Override
    public void run() {
      refresh();
    }
  }

  private class RetryResolutionResultListener implements ResolutionResultListener {

    @Override
    public void resolutionAttempted(boolean successful) {
      if (successful) {
        retryScheduler.reset();
      } else {
        retryScheduler.schedule();
      }
    }
  }
}
