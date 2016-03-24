/*
 * Copyright 2014, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package io.grpc.retry;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * Generic Retryer that encapsulates the retry mechanism for retrying operations asynchronously.  
 * A Retryer is immutable and stores state for the previous retry attempt.  Each retry attempt returns
 * a new Retryer that must be used for the next retry attempt.
 * 
 */
public final class Retryer {
    private static final ScheduledExecutorService SHARED_EXECUTOR = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder()
            .setNameFormat("retryer-%s")
            .setDaemon(true)
            .build());

    private final Backoff backoffPolicy;
    private final int maxRetrys;
    private final ScheduledExecutorService executor;
    private final Runnable beforeRetry;
    private final Future<?> future;
    private final int retryCount;
    
    public static  Retryer createDefault() {
        return new Retryer(Backoffs.immediate(), 1, new Runnable() {
            @Override
            public void run() {
            }
        }, SHARED_EXECUTOR);
    }
    
    private Retryer(Backoff backoffPolicy, int maxRetrys, Runnable beforeRetry, ScheduledExecutorService executor) {
        this.backoffPolicy = backoffPolicy;
        this.maxRetrys = maxRetrys;
        this.executor = executor;
        this.beforeRetry = beforeRetry;
        this.future = null;
        this.retryCount = 0;
    }
    
    private Retryer(Future<?> future, Retryer other) {
        this.backoffPolicy = other.backoffPolicy;
        this.maxRetrys = other.maxRetrys;
        this.executor = other.executor;
        this.beforeRetry = other.beforeRetry;
        this.retryCount = other.maxRetrys > 0 ? other.retryCount + 1 : other.retryCount;
        this.future = future;
    }

    /**
     * Policy for determining a delay based on the number of attempts
     * 
     * @param backoffPolicy The policy
     * @return The Builder
     * @see Backoffs
     */
    public Retryer backoffPolicy(Backoff backoffPolicy) {
        Preconditions.checkState(backoffPolicy != null, "Backoff policy may not be null");
        return new Retryer(backoffPolicy, maxRetrys, beforeRetry, executor);
    }

    /**
     * Maximum number of retries allowed for the call to proceed.  
     * 
     * @param maxRetrys Max retries or 0 for none and -1 for indefinite
     * @return The builder
     */
    public Retryer maxRetries(int maxRetrys) {
        return new Retryer(backoffPolicy, maxRetrys, beforeRetry, executor);
    }
    
    /**
     * Retry operations forever
     * @return The builder
     */
    public Retryer retryForever() {
        return new Retryer(backoffPolicy, -1, beforeRetry, executor);
    }
    
    /**
     * Executor to use for scheduling delayed retries. 
     * 
     * @param executor The executor
     * @return The builder
     */
    public Retryer executor(ScheduledExecutorService executor) {
        Preconditions.checkState(executor != null, "Executor must not be null");
        return new Retryer(backoffPolicy, maxRetrys, beforeRetry, executor);
    }
    
    /**
     * Provide a runnable to invoke in between retries
     * 
     * @param beforeRetry The runnable
     * @return The builder
     */
    public Retryer beforeRetry(Runnable beforeRetry) {
        Preconditions.checkState(beforeRetry == null, "Only one beforeRetry handler may be registered");
        return new Retryer(backoffPolicy, maxRetrys, beforeRetry, executor);
    }
    
    /**
     * Determine if a retry is allowed based on the number of attempts
     * @return True if retry() is allowed
     */
    public boolean canRetry() {
        return maxRetrys < 0 || retryCount < maxRetrys;
    }
    
    /**
     * Cancel any scheduled retry operation
     */
    public void cancel() {
        if (this.future != null) {
            this.future.cancel(true);
        }
    }
    
    /**
     * Retry the operation on the provided runnable
     * 
     * @param runnable The operation to retry
     * @return A new Retryer instance tracking the state of the retry operation.  
     */
    public Retryer retry(final Runnable runnable) {
        Preconditions.checkState(runnable != null, "Runnable must not be null");
        cancel();
        beforeRetry.run();
        long delay = backoffPolicy.getDelayMillis(retryCount);
        if (delay == 0) {
            return new Retryer(executor.submit(runnable), this);
        } else {
            return new Retryer(executor.schedule(runnable, delay, TimeUnit.MILLISECONDS), this);
        }
    }
}
