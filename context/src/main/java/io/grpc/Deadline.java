/*
 * Copyright 2016 The gRPC Authors
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

import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * An absolute point in time, generally for tracking when a task should be completed. A deadline is
 * immutable except for the passage of time causing it to expire.
 *
 * <p>Many systems use timeouts, which are relative to the start of the operation. However, being
 * relative causes them to be poorly suited for managing higher-level tasks where there are many
 * components and sub-operations that may not know the time of the initial "start of the operation."
 * However, a timeout can be converted to a {@code Deadline} at the start of the operation and then
 * passed to the various components unambiguously.
 */
public final class Deadline implements Comparable<Deadline> {
  private static final SystemTicker SYSTEM_TICKER = new SystemTicker();
  // nanoTime has a range of just under 300 years. Only allow up to 100 years in the past or future
  // to prevent wraparound as long as process runs for less than ~100 years.
  private static final long MAX_OFFSET = TimeUnit.DAYS.toNanos(100 * 365);
  private static final long MIN_OFFSET = -MAX_OFFSET;
  private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

  /**
   * Returns the ticker that's based on system clock.
   *
   * <p>This is <strong>EXPERIMENTAL</strong> API and may subject to change.  If you'd like it to be
   * stabilized or have any feedback, please
   * <href a="https://github.com/grpc/grpc-java/issues/6030">let us know</a>.
   *
   * @since 1.24.0
   */
  public static Ticker getSystemTicker() {
    return SYSTEM_TICKER;
  }

  /**
   * Create a deadline that will expire at the specified offset based on the {@link #getSystemTicker
   * system ticker}.
   *
   * <p>If the given offset is extraordinarily long, say 100 years, the actual deadline created
   * might saturate.
   *
   * @param duration A non-negative duration.
   * @param units The time unit for the duration.
   * @return A new deadline.
   */
  public static Deadline after(long duration, TimeUnit units) {
    return after(duration, units, SYSTEM_TICKER);
  }

  /**
   * Create a deadline that will expire at the specified offset based on the given {@link Ticker}.
   *
   * <p>If the given offset is extraordinarily long, say 100 years, the actual deadline created
   * might saturate.
   *
   * <p><strong>CAUTION</strong>: Only deadlines created with the same {@link Ticker} instance can
   * be compared by methods like {@link #minimum}, {@link #isBefore} and {@link #compareTo}.  Custom
   * Tickers should only be used in tests where you fake out the clock.  Always use the {@link
   * #getSystemTicker system ticker} in production, or serious errors may occur.
   *
   * <p>This is <strong>EXPERIMENTAL</strong> API and may subject to change.  If you'd like it to be
   * stabilized or have any feedback, please
   * <href a="https://github.com/grpc/grpc-java/issues/6030">let us know</a>.
   *
   * @param duration A non-negative duration.
   * @param units The time unit for the duration.
   * @param ticker Where this deadline refer the current time
   * @return A new deadline.
   *
   * @since 1.24.0
   */
  public static Deadline after(long duration, TimeUnit units, Ticker ticker) {
    checkNotNull(units, "units");
    return new Deadline(ticker, units.toNanos(duration), true);
  }

  private final Ticker ticker;
  private final long deadlineNanos;
  private volatile boolean expired;

  private Deadline(Ticker ticker, long offset, boolean baseInstantAlreadyExpired) {
    this(ticker, ticker.nanoTime(), offset, baseInstantAlreadyExpired);
  }

  private Deadline(Ticker ticker, long baseInstant, long offset,
      boolean baseInstantAlreadyExpired) {
    this.ticker = ticker;
    // Clamp to range [MIN_OFFSET, MAX_OFFSET]
    offset = Math.min(MAX_OFFSET, Math.max(MIN_OFFSET, offset));
    deadlineNanos = baseInstant + offset;
    expired = baseInstantAlreadyExpired && offset <= 0;
  }

  /**
   * Has this deadline expired
   * @return {@code true} if it has, otherwise {@code false}.
   */
  public boolean isExpired() {
    if (!expired) {
      if (deadlineNanos - ticker.nanoTime() <= 0) {
        expired = true;
      } else {
        return false;
      }
    }
    return true;
  }

  /**
   * Is {@code this} deadline before another.  Two deadlines must be created using the same {@link
   * Ticker}.
   */
  public boolean isBefore(Deadline other) {
    checkTicker(other);
    return this.deadlineNanos - other.deadlineNanos < 0;
  }

  /**
   * Return the minimum deadline of {@code this} or an other deadline.  They must be created using
   * the same {@link Ticker}.
   *
   * @param other deadline to compare with {@code this}.
   */
  public Deadline minimum(Deadline other) {
    checkTicker(other);
    return isBefore(other) ? this : other;
  }

  /**
   * Create a new deadline that is offset from {@code this}.
   *
   * <p>If the given offset is extraordinarily long, say 100 years, the actual deadline created
   * might saturate.
   */
  // TODO(ejona): This method can cause deadlines to grow too far apart. For example:
  // Deadline.after(100 * 365, DAYS).offset(100 * 365, DAYS) would be less than
  // Deadline.after(-100 * 365, DAYS)
  public Deadline offset(long offset, TimeUnit units) {
    // May already be expired
    if (offset == 0) {
      return this;
    }
    return new Deadline(ticker, deadlineNanos, units.toNanos(offset), isExpired());
  }

  /**
   * How much time is remaining in the specified time unit. Internal units are maintained as
   * nanoseconds and conversions are subject to the constraints documented for
   * {@link TimeUnit#convert}. If there is no time remaining, the returned duration is how
   * long ago the deadline expired.
   */
  public long timeRemaining(TimeUnit unit) {
    final long nowNanos = ticker.nanoTime();
    if (!expired && deadlineNanos - nowNanos <= 0) {
      expired = true;
    }
    return unit.convert(deadlineNanos - nowNanos, TimeUnit.NANOSECONDS);
  }

  /**
   * Schedule a task to be run when the deadline expires.
   *
   * <p>Note if this deadline was created with a custom {@link Ticker}, the {@code scheduler}'s
   * underlying clock should be synchronized with that Ticker.  Otherwise the task won't be run at
   * the expected point of time.
   *
   * @param task to run on expiration
   * @param scheduler used to execute the task
   * @return {@link ScheduledFuture} which can be used to cancel execution of the task
   */
  public ScheduledFuture<?> runOnExpiration(Runnable task, ScheduledExecutorService scheduler) {
    checkNotNull(task, "task");
    checkNotNull(scheduler, "scheduler");
    return scheduler.schedule(task, deadlineNanos - ticker.nanoTime(), TimeUnit.NANOSECONDS);
  }

  @Override
  public String toString() {
    long remainingNanos = timeRemaining(TimeUnit.NANOSECONDS);
    long seconds = Math.abs(remainingNanos) / NANOS_PER_SECOND;
    long nanos = Math.abs(remainingNanos) % NANOS_PER_SECOND;

    StringBuilder buf = new StringBuilder();
    if (remainingNanos < 0) {
      buf.append('-');
    }
    buf.append(seconds);
    if (nanos > 0) {
      buf.append(String.format(".%09d", nanos));
    }
    buf.append("s from now");
    if (ticker != SYSTEM_TICKER) {
      buf.append(" (ticker=" + ticker + ")");
    }
    return buf.toString();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Both deadlines must be created with the same {@link Ticker}.
   */
  @Override
  public int compareTo(Deadline that) {
    checkTicker(that);
    long diff = this.deadlineNanos - that.deadlineNanos;
    if (diff < 0) {
      return -1;
    } else if (diff > 0) {
      return 1;
    }
    return 0;
  }

  @Override
  public int hashCode() {
    return Arrays.asList(this.ticker, this.deadlineNanos).hashCode();
  }

  @Override
  public boolean equals(final Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof Deadline)) {
      return false;
    }

    final Deadline other = (Deadline) o;
    if (this.ticker == null ? other.ticker != null : this.ticker != other.ticker) {
      return false;
    }
    if (this.deadlineNanos != other.deadlineNanos) {
      return false;
    }
    return true;
  }

  /**
   * Time source representing nanoseconds since fixed but arbitrary point in time.
   *
   * <p>DO NOT use custom {@link Ticker} implementations in production, because deadlines created
   * with custom tickers are incompatible with those created with the system ticker.  Always use
   * the {@link #getSystemTicker system ticker} whenever you need to provide one in production code.
   *
   * <p>This is <strong>EXPERIMENTAL</strong> API and may subject to change.  If you'd like it to be
   * stabilized or have any feedback, please
   * <href a="https://github.com/grpc/grpc-java/issues/6030">let us know</a>.
   *
   * <p>In general implementations should be thread-safe, unless it's implemented and used in a
   * localized environment (like unit tests) where you are sure the usages are synchronized.
   *
   * @since 1.24.0
   */
  public abstract static class Ticker {
    /** Returns the number of nanoseconds since this source's epoch. */
    public abstract long nanoTime();
  }

  private static class SystemTicker extends Ticker {
    @Override
    public long nanoTime() {
      return System.nanoTime();
    }
  }

  private static <T> T checkNotNull(T reference, Object errorMessage) {
    if (reference == null) {
      throw new NullPointerException(String.valueOf(errorMessage));
    }
    return reference;
  }

  private void checkTicker(Deadline other) {
    if (ticker != other.ticker) {
      throw new AssertionError(
          "Tickers (" + ticker + " and " + other.ticker + ") don't match."
          + " Custom Ticker should only be used in tests!");
    }
  }
}
