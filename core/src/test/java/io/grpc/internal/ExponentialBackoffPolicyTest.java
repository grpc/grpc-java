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

import static io.grpc.internal.ExponentialBackoffPolicy.INITIAL_BACKOFF_MILLIS;
import static io.grpc.internal.ExponentialBackoffPolicy.MAX_BACKOFF_MILLIS;
import static io.grpc.internal.ExponentialBackoffPolicy.MIN_CONNECT_TIMEOUT_MILLIS;
import static io.grpc.internal.ExponentialBackoffPolicy.MULTIPLIER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test for {@link ExponentialBackoffPolicy}.
 */
@RunWith(JUnit4.class)
public class ExponentialBackoffPolicyTest {

  private final ExponentialBackoffPolicy policy = new ExponentialBackoffPolicy().setJitter(0D);

  @Test
  public void maxDelayReached() {
    for (int i = 0; i < 50; i++) {
      if (MAX_BACKOFF_MILLIS == policy.nextBackoffMillis()) {
        assertEquals(
            (int) (Math.log((double) MAX_BACKOFF_MILLIS / (double) INITIAL_BACKOFF_MILLIS)
                / Math.log(MULTIPLIER)) + 1, i);
        return; // Max delay reached
      }
    }
    assertEquals("max delay not reached", MAX_BACKOFF_MILLIS, policy.nextBackoffMillis());
  }

  @Test
  public void canProvide() {
    assertNotNull(new ExponentialBackoffPolicy.Provider().get());
  }

  @Test
  public void minConnTimeoutExceeded() {
    for (int i = 0; i < 50; i++) {
      if (MIN_CONNECT_TIMEOUT_MILLIS != policy.currentTimeoutMills()) {
        assertEquals(
            (int) (Math.log((double) MIN_CONNECT_TIMEOUT_MILLIS / (double) INITIAL_BACKOFF_MILLIS)
                / Math.log(MULTIPLIER)) + 1, i);
        return; // Min connection timeout exceeded
      }
      policy.nextBackoffMillis();
    }
    assertNotEquals("min connection timeout not exceeded",
        MIN_CONNECT_TIMEOUT_MILLIS, policy.currentTimeoutMills());
  }
}

