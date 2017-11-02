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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkArgument;

import javax.annotation.concurrent.Immutable;

/**
 * Represents a retry/hedging RPC attempt, likely connecting to a different backend to what other
 * ones connect to.
 */
interface Substream {
  /** Gets the underlying {@link ClientStream}. */
  ClientStream clientStream();

  /** Starts the substream. */
  void start();

  /** True if the underlying stream is a {@link DelayedStream2} and is not pass-through. */
  // @GuadedBy("this")
  boolean isPending();

  /**
   * Whether the substream is currently in resuming. If in resuming, another {@link
   * RetryOrHedgingBuffer#resume} will return immediately.
   */
  // @GuadedBy("this")
  boolean isResuming();

  /** Sets whether the substream is currently in resuming. */
  // @GuadedBy("this")
  void setResuming(boolean resuming);

  /** Gets the replay {@link Progress}. */
  // @GuadedBy("this")
  Progress progress();

  /** Updates the replay {@link Progress}. */
  // @GuadedBy("this")
  void updateProgress(Progress progress);

  /** Marks the progress of the replay of a substream. */
  @Immutable
  final class Progress {
    static final Progress START = new Progress(0);
    static final Progress COMPLETED = new Progress(Integer.MAX_VALUE);
    static final Progress CANCELLED = new Progress(Integer.MAX_VALUE);

    private final int position;

    Progress(int position) {
      checkArgument(position >= 0, "position should not be negative");

      this.position = position;
    }

    /**
     * The current position in the {@link RetryOrHedgingBuffer} that the substream should resume the
     * replay.
     */
    int currentPosition() {
      return position;
    }
  }
}
