/*
 * Copyright 2019 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Listener for when deframing on the application thread, which calls the real listener on the
 * transport thread. May only be called on the application thread.
 *
 * <p>Does not call the delegate's {@code messagesAvailable()}. It's expected that
 * {@code messageReadQueuePoll()} is called on the application thread from within a message producer
 * managed elsewhere.
 */
final class ApplicationThreadDeframerListener implements MessageDeframer.Listener {
  public interface TransportExecutor {
    void runOnTransportThread(Runnable r);
  }

  private final TransportExecutor transportExecutor;
  private final MessageDeframer.Listener storedListener;
  /** Queue for messages returned by the deframer when deframing in the application thread. */
  private final Queue<InputStream> messageReadQueue = new ArrayDeque<>();

  public ApplicationThreadDeframerListener(
      MessageDeframer.Listener listener, TransportExecutor transportExecutor) {
    this.storedListener = checkNotNull(listener, "listener");
    this.transportExecutor = checkNotNull(transportExecutor, "transportExecutor");
  }

  @Override
  public void bytesRead(final int numBytes) {
    transportExecutor.runOnTransportThread(
        new Runnable() {
          @Override
          public void run() {
            storedListener.bytesRead(numBytes);
          }
        });
  }

  @Override
  public void messagesAvailable(StreamListener.MessageProducer producer) {
    InputStream message;
    while ((message = producer.next()) != null) {
      messageReadQueue.add(message);
    }
  }

  @Override
  public void deframerClosed(final boolean hasPartialMessage) {
    transportExecutor.runOnTransportThread(
        new Runnable() {
          @Override
          public void run() {
            storedListener.deframerClosed(hasPartialMessage);
          }
        });
  }

  @Override
  public void deframeFailed(final Throwable cause) {
    transportExecutor.runOnTransportThread(
        new Runnable() {
          @Override
          public void run() {
            storedListener.deframeFailed(cause);
          }
        });
  }

  public InputStream messageReadQueuePoll() {
    return messageReadQueue.poll();
  }
}
