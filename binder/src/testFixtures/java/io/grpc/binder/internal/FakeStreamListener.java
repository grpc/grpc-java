/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.binder.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import io.grpc.Status;
import io.grpc.internal.StreamListener;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import javax.annotation.Nullable;

/**
 * Fake {@link StreamListener} that eagerly reads and records incoming stream messages.
 *
 * <p>During {@link #messagesAvailable(MessageProducer)}, the listener reads up to its remaining
 * permit budget (default: unlimited) and records them in {@link #getReadMessages()}. If reading
 * stops because permits ran out, the {@link MessageProducer} is saved for retrieval via {@link
 * #pollMessageProducer()}.
 *
 * <p>This class is not thread-safe. Tests must synchronize their own state mutations and assertions
 * with callbacks dispatched from gRPC threads.
 */
public class FakeStreamListener implements StreamListener {
  private final List<String> readMessages = new ArrayList<>();
  private final Queue<MessageProducer> messageProducers = new ArrayDeque<>();
  private int readPermitsRemaining = Integer.MAX_VALUE;
  @Nullable protected Status closedStatus;

  /**
   * Sets the exact number of messages the listener is permitted to read in subsequent {@link
   * #messagesAvailable} callbacks.
   */
  public void setReadPermits(int permits) {
    checkArgument(permits >= 0, "permits must be non-negative");
    this.readPermitsRemaining = permits;
  }

  /** Adds additional message read permits to the listener's budget. */
  public void addReadPermits(int permits) {
    checkArgument(permits >= 0, "permits must be non-negative");
    checkState(
        Integer.MAX_VALUE - this.readPermitsRemaining >= permits, "readPermitsRemaining overflow");
    this.readPermitsRemaining += permits;
  }

  /**
   * Polls and removes the next {@link MessageProducer} whose reading was halted for lack of
   * permits, or {@code null} if none.
   *
   * <p>Note: The returned {@link MessageProducer} may be empty if the available permits were
   * exactly enough to drain it.
   */
  @Nullable
  public MessageProducer pollMessageProducer() {
    return messageProducers.poll();
  }

  @Override
  public void messagesAvailable(MessageProducer producer) {
    checkState(!isClosed(), "messagesAvailable invoked after closed");
    while (readPermitsRemaining > 0) {
      InputStream stream = producer.next();
      if (stream == null) {
        return;
      }
      readPermitsRemaining--;
      try {
        readMessages.add(readString(stream));
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }
    messageProducers.add(producer);
  }

  /** Decodes the entire contents of {@code stream} as a UTF-8 string and closes the stream. */
  public static String readString(InputStream stream) throws IOException {
    checkNotNull(stream, "stream");
    try (InputStream is = stream) {
      return new String(ByteStreams.toByteArray(is), UTF_8);
    }
  }

  /** Returns an immutable snapshot of all messages read by the listener in order. */
  public ImmutableList<String> getReadMessages() {
    return ImmutableList.copyOf(readMessages);
  }

  /** Returns the status passed to {@code closed()}, or {@code null} if not closed. */
  @Nullable
  public Status getClosedStatus() {
    return closedStatus;
  }

  /** Returns whether the stream has been closed. */
  public boolean isClosed() {
    return closedStatus != null;
  }

  @Override
  public void onReady() {
    checkState(!isClosed(), "onReady invoked after closed");
    // Could maintain an onReady counter here if needed.
  }
}
