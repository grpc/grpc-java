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

package io.grpc;

import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;

import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * The collection of runtime options for a new RPC call.
 *
 * <p>A field that is not set is {@code null}.
 */
@Immutable
public final class CallOptions {
  /**
   * A blank {@code CallOptions} that all fields are not set.
   */
  public static final CallOptions DEFAULT = CallOptions.newBuilder().build();

  private final Long deadlineNanoTime;

  /**
   * Returns a new {@code CallOptions} with the given absolute deadline in nanoseconds in the clock
   * as per {@link System#nanoTime()}.
   *
   * <p>This is mostly used for propagating an existing deadline. {@link #withDeadlineAfter} is the
   * recommended way of setting a new deadline,
   *
   * <p>TODO(carl-mastrangelo): maybe remove this in favor of the Builder approach.
   *
   * @param deadlineNanoTime the deadline in the clock as per {@link System#nanoTime()}.
   *                         {@code null} for unsetting the deadline.
   */
  public CallOptions withDeadlineNanoTime(@Nullable Long deadlineNanoTime) {
    return toBuilder().setDeadlineNanoTime(deadlineNanoTime).build();
  }

  /**
   * Returns a new {@code CallOptions} with a deadline that is after the given {@code duration} from
   * now.
   *
   * <p>TODO(carl-mastrangelo): maybe remove this in favor of the Builder approach.
   */
  public CallOptions withDeadlineAfter(long duration, TimeUnit unit) {
    return withDeadlineNanoTime(System.nanoTime() + unit.toNanos(duration));
  }

  /**
   * Returns the deadline in nanoseconds in the clock as per {@link System#nanoTime()}. {@code null}
   * if the deadline is not set.
   */
  @Nullable
  public Long getDeadlineNanoTime() {
    return deadlineNanoTime;
  }

  private CallOptions(Builder b) {
    deadlineNanoTime = b.deadlineNanoTime;
  }

  /**
   * Creates a builder from this set of options.
   */
  public Builder toBuilder() {
    Builder b = newBuilder();
    b.deadlineNanoTime = deadlineNanoTime;
    return b;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof CallOptions)) {
      return false;
    }
    CallOptions that = (CallOptions) other;
    return Objects.equal(this.deadlineNanoTime, that.deadlineNanoTime);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(deadlineNanoTime);
  }

  @SuppressWarnings("deprecation") // Guava 14.0
  @Override
  public String toString() {
    ToStringHelper builder = Objects.toStringHelper(this)
        .add("hasDeadline", deadlineNanoTime != null);
    if (deadlineNanoTime != null) {
      long durationNanos = deadlineNanoTime - System.nanoTime();
      builder.add("deadlineNanoTime", durationNanos + "ns from now");
    }
    return builder.toString();
  }

  public static final class Builder {
    private Long deadlineNanoTime;

    private Builder() {}

    public CallOptions build() {
      return new CallOptions(this);
    }

    public Builder setDeadlineNanoTime(Long deadlineNanoTime) {
      this.deadlineNanoTime = deadlineNanoTime;
      return this;
    }
  }
}
