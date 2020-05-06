/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.rls;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import io.grpc.internal.TimeProvider;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Implementation of {@link Throttler} that keeps track of recent history (the duration of which is
 * specified to the constructor) and throttles requests at the client side based on the number of
 * requests that the
 * backend has accepted and the total number of requests generated. A given request will be
 * throttled with a probability
 * <pre>
 *   throttleProbability = (requests - ratio_for_accepts * accepts) / (requests + requests_padding)
 * </pre>
 * where requests is the total number of requests, accepts is the total number of requests that the
 * backend has accepted and ratio_for_accepts is just a constant multiplier passed to the
 * constructor (see the description of ratio_for_accepts for more information).
 */
final class AdaptiveThrottler implements Throttler {

  private static final int DEFAULT_HISTORY_SECONDS = 30;
  private static final int DEFAULT_REQUEST_PADDING = 8;
  private static final float DEFAULT_RATIO_FOR_ACCEPT = 1.2f;

  /**
   * The duration of history of calls used by Adaptive Throttler.
   */
  private final int historySeconds;
  /**
   * A magic number to tune the aggressiveness of the throttling. High numbers throttle less. The
   * default is 8.
   */
  private final int requestsPadding;
  /**
   * The ratio by which the Adaptive Throttler will attempt to send requests above what the server
   * is currently accepting.
   */
  private final float ratioForAccepts;
  private final TimeProvider timeProvider;
  /**
   * The number of requests attempted by the client during the Adaptive Throttler instance's
   * history of calls. This includes requests throttled at the client. The history period defaults
   * to 30 seconds.
   */
  @VisibleForTesting
  final TimeBasedAccumulator requestStat;
  /**
   * Counter for the total number of requests that were throttled by either the client (this class)
   * or the backend in recent history.
   */
  @VisibleForTesting
  final TimeBasedAccumulator throttledStat;

  private AdaptiveThrottler(Builder builder) {
    this.historySeconds = builder.historySeconds;
    this.requestsPadding = builder.requestsPadding;
    this.ratioForAccepts = builder.ratioForAccepts;
    this.timeProvider = builder.timeProvider;
    long internalNanos = TimeUnit.SECONDS.toNanos(historySeconds);
    this.requestStat = new TimeBasedAccumulator(internalNanos, timeProvider);
    this.throttledStat = new TimeBasedAccumulator(internalNanos, timeProvider);
  }

  @Override
  public boolean shouldThrottle() {
    return shouldThrottle(randomFloat());
  }

  @VisibleForTesting
  boolean shouldThrottle(float random) {
    long nowNanos = timeProvider.currentTimeNanos();
    if (getThrottleProbability(nowNanos) <= random) {
      return false;
    }
    requestStat.increment(nowNanos);
    throttledStat.increment(nowNanos);
    return true;
  }

  /**
   * Calculates throttleProbability.
   * <pre>
   * throttleProbability = (requests - ratio_for_accepts * accepts) / (requests + requests_padding)
   * </pre>
   */
  @VisibleForTesting
  float getThrottleProbability(long nowNanos) {
    long requests = this.requestStat.get(nowNanos);
    long accepts = requests - throttledStat.get(nowNanos);
    // It's possible that this probability will be negative, which means that no throttling should
    // take place.
    return (requests - ratioForAccepts * accepts) / (requests + requestsPadding);
  }

  @Override
  public void registerBackendResponse(boolean throttled) {
    long now = timeProvider.currentTimeNanos();
    requestStat.increment(now);
    if (throttled) {
      throttledStat.increment(now);
    }
  }

  private static float randomFloat() {
    return ThreadLocalRandom.current().nextFloat();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("historySeconds", historySeconds)
        .add("requestsPadding", requestsPadding)
        .add("ratioForAccepts", ratioForAccepts)
        .add("requestStat", requestStat)
        .add("throttledStat", throttledStat)
        .toString();
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link AdaptiveThrottler}. */
  static final class Builder {

    private float ratioForAccepts = DEFAULT_RATIO_FOR_ACCEPT;
    private int historySeconds = DEFAULT_HISTORY_SECONDS;
    private int requestsPadding = DEFAULT_REQUEST_PADDING;
    private TimeProvider timeProvider = TimeProvider.SYSTEM_TIME_PROVIDER;

    public Builder setRatioForAccepts(float ratioForAccepts) {
      this.ratioForAccepts = ratioForAccepts;
      return this;
    }

    public Builder setHistorySeconds(int historySeconds) {
      this.historySeconds = historySeconds;
      return this;
    }

    public Builder setRequestsPadding(int requestsPadding) {
      this.requestsPadding = requestsPadding;
      return this;
    }

    public Builder setTimeProvider(TimeProvider timeProvider) {
      this.timeProvider = checkNotNull(timeProvider, "timeProvider");
      return this;
    }

    public AdaptiveThrottler build() {
      return new AdaptiveThrottler(this);
    }
  }

  static final class TimeBasedAccumulator {
    /**
     * The number of slots. This value determines the accuracy of the get() method to interval /
     * NUM_SLOTS.
     */
    private static final int NUM_SLOTS = 50;

    /** Holds the data for each slot (amount and end timestamp). */
    private static final class Slot {
      static final AtomicLongFieldUpdater<Slot> ATOMIC_COUNT =
          AtomicLongFieldUpdater.newUpdater(Slot.class, "count");

      // The count of statistics for the time range represented by this slot.
      volatile long count;
      // The nearest 0 modulo slot boundary in nanoseconds. The slot boundary
      // is exclusive. [previous_slot.end, end)
      final long endNanos;

      Slot(long endNanos) {
        this.endNanos = endNanos;
        this.count = 0;
      }

      void increment() {
        ATOMIC_COUNT.incrementAndGet(this);
      }
    }

    // Represents a slot which is not initialized and is unusable.
    private static final Slot NULL_SLOT = new Slot(-1);

    /** The array of slots. */
    private final AtomicReferenceArray<Slot> slots = new AtomicReferenceArray<>(NUM_SLOTS);

    /** The time interval this statistic is concerned with. */
    private final long interval;

    /** The number of nanoseconds in each slot. */
    private final long slotNanos;

    /**
     * The current index into the slot array. {@code currentIndex} may be safely read without
     * synchronization, but all writes must be performed inside of a {@code synchronized(this){}}
     * block.
     */
    private volatile int currentIndex;

    private final TimeProvider timeProvider;

    /**
     * Interval constructor.
     *
     * @param internalNanos is the stat interval in nanoseconds
     * @throws IllegalArgumentException if the supplied interval is too small to be effective
     */
    TimeBasedAccumulator(long internalNanos, TimeProvider timeProvider) {
      checkArgument(
          internalNanos >= NUM_SLOTS,
          "Interval must be greater than %s",
          NUM_SLOTS);
      this.interval = internalNanos;
      this.slotNanos = internalNanos / NUM_SLOTS;
      this.currentIndex = 0;
      for (int i = 0; i < NUM_SLOTS; i++) {
        slots.set(i, NULL_SLOT);
      }
      this.timeProvider = checkNotNull(timeProvider, "ticker");
    }

    /** Gets the current slot. */
    private Slot getSlot(long now) {
      Slot currentSlot = slots.get(currentIndex);
      if (now < currentSlot.endNanos) {
        return currentSlot;
      } else {
        long slotBoundary = getSlotEndTime(now);
        synchronized (this) {
          int index = currentIndex;
          currentSlot = slots.get(index);
          if (now < currentSlot.endNanos) {
            return currentSlot;
          }
          int newIndex = (index == NUM_SLOTS - 1) ? 0 : index + 1;
          Slot nextSlot = new Slot(slotBoundary);
          slots.set(newIndex, nextSlot);
          // Set currentIndex only after assigning the new slot to slots, otherwise
          // racing readers will see NULL_SLOT or an old slot.
          currentIndex = newIndex;
          return nextSlot;
        }
      }
    }

    /**
     * Computes the end boundary since the last bucket can be partial size.
     *
     * @param time the time for which to find the nearest slot boundary
     * @return the nearest slot boundary in nanos
     */
    private long getSlotEndTime(long time) {
      return (time / slotNanos + 1) * slotNanos;
    }

    /**
     * Returns the interval used by this statistic.
     *
     * @return the interval
     */
    long getInterval() {
      return this.interval;
    }

    /**
     * Increments the count of the statistic by the specified amount for the specified time.
     *
     * @param now is the time used to increment the count
     */
    final void increment(long now) {
      getSlot(now).increment();
    }

    /**
     * Returns the count of the statistic using the specified time value as the current time.
     *
     * @param now the current time
     * @return the statistic count
     */
    final long get(long now) {
      long intervalEnd = getSlotEndTime(now);
      long intervalStart = intervalEnd - interval;
      // This is the point at which increments to new slots will be ignored.
      int index = currentIndex;

      long accumulated = 0L;
      long prevSlotEnd = Long.MAX_VALUE;
      for (int i = 0; i < NUM_SLOTS; i++) {
        if (index < 0) {
          index = NUM_SLOTS - 1;
        }
        Slot currentSlot = slots.get(index);
        index--;
        long currentSlotEnd = currentSlot.endNanos;

        if (currentSlotEnd <= intervalStart || currentSlotEnd > prevSlotEnd) {
          break;
        }
        prevSlotEnd = currentSlotEnd;

        if (currentSlotEnd > intervalEnd) {
          continue;
        }
        accumulated = accumulated + currentSlot.count;
      }
      return accumulated;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("interval", interval)
          .add("current_count", get(timeProvider.currentTimeNanos()))
          .toString();
    }
  }
}
