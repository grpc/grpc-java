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

import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Schedules a retry operation according to a {@link BackoffPolicy}. The retry is run within a
 * {@link SynchronizationContext}. At most one retry is scheduled at a time.
 */
final class BackoffPolicyRetryScheduler implements RetryScheduler {
  private final ScheduledExecutorService scheduledExecutorService;
  private final SynchronizationContext syncContext;
  private final BackoffPolicy.Provider policyProvider;

  private BackoffPolicy policy;
  private ScheduledHandle scheduledHandle;

  private static final Logger logger = Logger.getLogger(
      BackoffPolicyRetryScheduler.class.getName());

  BackoffPolicyRetryScheduler(BackoffPolicy.Provider policyProvider,
      ScheduledExecutorService scheduledExecutorService,
      SynchronizationContext syncContext) {
    this.policyProvider = policyProvider;
    this.scheduledExecutorService = scheduledExecutorService;
    this.syncContext = syncContext;
  }

  /**
   * Schedules a future retry operation. Only allows one retry to be scheduled at any given time.
   */
  @Override
  public void schedule(Runnable retryOperation) {
    syncContext.throwIfNotInThisSynchronizationContext();

    if (policy == null) {
      policy = policyProvider.get();
    }
    // If a retry is already scheduled, take no further action.
    if (scheduledHandle != null && scheduledHandle.isPending()) {
      return;
    }
    long delayNanos = policy.nextBackoffNanos();
    scheduledHandle = syncContext.schedule(retryOperation, delayNanos, TimeUnit.NANOSECONDS,
        scheduledExecutorService);
    logger.log(Level.FINE, "Scheduling DNS resolution backoff for {0}ns", delayNanos);
  }

  /**
   * Resets the {@link BackoffPolicyRetryScheduler} and cancels any pending retry task. The policy
   * will be cleared thus also resetting any state associated with it (e.g. a backoff multiplier).
   */
  @Override
  public void reset() {
    syncContext.throwIfNotInThisSynchronizationContext();

    syncContext.execute(() -> {
      if (scheduledHandle != null && scheduledHandle.isPending()) {
        scheduledHandle.cancel();
      }
      policy = null;
    });
  }

}
