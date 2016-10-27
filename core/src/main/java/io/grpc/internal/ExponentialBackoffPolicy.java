/*
 * Copyright 2015, Google Inc. All rights reserved.
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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.annotations.VisibleForTesting;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Retry Policy for Transport reconnection.  Initial parameters from
 * https://github.com/grpc/grpc/blob/master/doc/connection-backoff.md
 *
 * <p>TODO(carl-mastrangelo): add unit tests for this class
 */
final class ExponentialBackoffPolicy implements BackoffPolicy {
  static final class Provider implements BackoffPolicy.Provider {
    @Override
    public BackoffPolicy get() {
      return new ExponentialBackoffPolicy();
    }
  }

  @VisibleForTesting
  static final long MAX_BACKOFF_MILLIS = TimeUnit.SECONDS.toMillis(120L);
  @VisibleForTesting
  static final long INITIAL_BACKOFF_MILLIS = TimeUnit.SECONDS.toMillis(1L);
  @VisibleForTesting
  static final long MIN_CONNECT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(20L);
  @VisibleForTesting
  static final double MULTIPLIER = 1.6D;

  private final Random random = new Random();
  private double jitter = .2D;
  private long nextBackoffMillis = INITIAL_BACKOFF_MILLIS;


  @Override
  public long nextBackoffMillis() {
    long currentBackoffMillis = nextBackoffMillis;
    nextBackoffMillis = Math.min((long) (currentBackoffMillis * MULTIPLIER), MAX_BACKOFF_MILLIS);
    return currentBackoffMillis
        + uniformRandom(-jitter * currentBackoffMillis, jitter * currentBackoffMillis);
  }

  @Override
  public long currentTimeoutMills() {
    return Math.max(nextBackoffMillis, MIN_CONNECT_TIMEOUT_MILLIS);
  }

  private long uniformRandom(double low, double high) {
    checkArgument(high >= low);
    double mag = high - low;
    return (long) (random.nextDouble() * mag + low);
  }

  @VisibleForTesting
  ExponentialBackoffPolicy setJitter(double jitter) {
    this.jitter = jitter;
    return this;
  }

}

