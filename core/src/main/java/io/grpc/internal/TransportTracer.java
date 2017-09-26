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

import io.grpc.ExperimentalApi;
import io.grpc.Status;
import io.grpc.StreamTracer;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A class for gathering statistics about a transport. This is an experimental feature.
 */
@ThreadSafe
@ExperimentalApi
public final class TransportTracer {
  private final AtomicLong streamsStarted = new AtomicLong();
  private final AtomicLong streamsSucceeded = new AtomicLong();
  private final AtomicLong streamsFailed = new AtomicLong();
  private final AtomicLong messagesSent = new AtomicLong();
  private final AtomicLong messagesReceived = new AtomicLong();
  private final AtomicLong keepAlivesSent = new AtomicLong();
  private final AtomicLong lastStreamCreatedTimeMsec = new AtomicLong();
  private final AtomicLong lastMessageSentTimeMsec = new AtomicLong();
  private final AtomicLong lastMessageReceivedTimeMsec = new AtomicLong();
  private volatile Callable<Integer> localFlowControlPollable;
  private volatile Callable<Integer> remoteFlowControlPollable;

  private final StreamTracer streamTracer = new StreamTracer() {
    @Override
    public void streamClosed(Status status) {
      if (status.isOk()) {
        streamsSucceeded.incrementAndGet();
      } else {
        streamsFailed.incrementAndGet();
      }
    }

    @Override
    public void outboundMessage(int seqNo) {
      messagesSent.incrementAndGet();
      updateMsecTimestamp(lastMessageReceivedTimeMsec);
    }

    @Override
    public void inboundMessage(int seqNo) {
      messagesReceived.incrementAndGet();
      updateMsecTimestamp(lastMessageReceivedTimeMsec);
    }
  };

  /**
   * Returns a {@link StreamTracer} that can be installed on each stream created on the transport.
   * The stats of each stream will be aggregated together on this TransportTracer.
   */
  public StreamTracer getStreamTracer() {
    return streamTracer;
  }

  /**
   * Called by the transport to report a stream has started. For clients, this happens when a header
   * is sent. For servers, this happens when a header is received.
   */
  public void reportStreamStarted() {
    streamsStarted.incrementAndGet();
    updateMsecTimestamp(lastStreamCreatedTimeMsec);
  }

  /**
   * Reports that a keep alive message was sent.
   */
  public void reportKeepAliveSent() {
    keepAlivesSent.incrementAndGet();
  }

  /**
   * Registers a {@code Callable<Integer>} that can be used to poll for the remote flow control
   * window size.
   */
  public void setRemoteFlowControlWindowPollable(Callable<Integer> remoteFlowControllPollable) {
    this.remoteFlowControlPollable = remoteFlowControllPollable;
  }

  /**
   * Registers a {@code Callable<Integer>} that can be used to poll for the local flow control
   * window size.
   */
  public void setLocalFlowControlWindowPollable(Callable<Integer> localFlowControllPollable) {
    this.localFlowControlPollable = localFlowControllPollable;
  }

  /**
   * Returns the number of streams started on the transport.
   */
  public long getStreamsStarted() {
    return streamsStarted.get();
  }

  /**
   * Returns the number of streams ended successfully with an OK status.
   */
  public long getStreamsSucceeded() {
    return streamsSucceeded.get();
  }

  /**
   * Returns the number of streams completed with a non-OK status.
   */
  public long getStreamsFailed() {
    return streamsFailed.get();
  }

  /**
   * Returns the number of messages sent on the transport.
   */
  public long getMessagesSent() {
    return messagesSent.get();
  }

  /**
   * Returns the number of messages received on the transport.
   */
  public long getMessagesReceived() {
    return messagesReceived.get();
  }

  /**
   * Returns the number of keep alive messages sent on the transport.
   */
  public long getKeepAlivesSent() {
    return keepAlivesSent.get();
  }

  /**
   * Returns the last time a stream was created as millis since Unix epoch.
   */
  public long getLastStreamCreatedTimeMsec() {
    return lastStreamCreatedTimeMsec.get();
  }

  /**
   * Returns the last time a message was sent as millis since Unix epoch.
   */
  public long getLastMessageSentTimeMsec() {
    return lastMessageSentTimeMsec.get();
  }

  /**
   * Returns the last time a message was received as millis since Unix epoch.
   */
  public long getLastMessageReceivedTimeMsec() {
    return lastMessageReceivedTimeMsec.get();
  }

  /**
   * Returns the remote flow control window as reported by the callback of
   * {@link #setRemoteFlowControlWindowPollable}. Returns -1 if no callback was registered.
   */
  public int getRemoteFlowControlWindow() {
    if (remoteFlowControlPollable == null) {
      return -1;
    }
    try {
      return remoteFlowControlPollable.call();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns the local flow control window as reported by the callback of
   * {@link #setRemoteFlowControlWindowPollable}. Returns -1 if no callback was registered.
   */
  public int getLocalFlowControlWindow() {
    if (localFlowControlPollable == null) {
      return -1;
    }
    try {
      return localFlowControlPollable.call();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Updates an AtomicLong representing a msec timestamp. Avoids races and only allows the value
   * to increase.
   */
  private static void updateMsecTimestamp(AtomicLong timestamp) {
    long now = System.currentTimeMillis();
    long oldVal = timestamp.get();
    while (oldVal < now && !timestamp.compareAndSet(oldVal, now)) {
      // CAS failed, read new timestamp and maybe try again
      oldVal = timestamp.get();
    }
  }
}
