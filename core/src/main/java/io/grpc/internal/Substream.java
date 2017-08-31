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

/**
 * Represents a retry/hedging RPC attempt, likely connecting to a different backend to what other
 * ones connect to.
 */
interface Substream {
  /**
   * Gets the underlying {@link ClientStream}.
   */
  ClientStream clientStream();

  /**
   * Starts the substream.
   */
  void start();

  /**
   * Gets the replay {@link Progress}.
   */
  Progress progress();

  /**
   * Updates the replay {@link Progress}.
   */
  void updateProgress(Progress progress);

  /**
   * Marks the progress of the replay of a substream.
   */
  final class Progress {
    static final Progress START = new Progress(0, 0, false);
    static final Progress COMPLETED = new Progress(Integer.MAX_VALUE, Integer.MAX_VALUE, true);
    static final Progress CANCELLED = new Progress(Integer.MAX_VALUE, Integer.MAX_VALUE, false);

    private final int messages;
    private final int requests;
    private final boolean halfCloseCalled;

    Progress(int messages, int requests, boolean halfCloseCalled) {
      checkArgument(messages >= 0, "messages should not be negative");
      checkArgument(requests >= 0, "requests should not be negative");

      this.messages = messages;
      this.requests = requests;
      this.halfCloseCalled = halfCloseCalled;
    }

    /**
     * Total number of times that {@link ClientCallImpl#sendMessage(Object)} is called.
     */
    int messagesSent() {
      return messages;
    }

    /**
     * Accumulation of {@link ClientCallImpl#request(int)} being called.
     */
    int requests() {
      return requests;
    }

    /**
     * Whether or not {@link ClientCallImpl#halfClose()} is called.
     */
    boolean halfCloseCalled() {
      return halfCloseCalled;
    }
  }
}
