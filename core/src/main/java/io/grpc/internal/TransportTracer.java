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

import com.google.common.base.Preconditions;
import io.grpc.Status;
import io.grpc.StreamTracer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import javax.annotation.Nullable;

/**
 * A class for gathering statistics about a transport. This is an experimental feature.
 */
public final class TransportTracer {
  private static final AtomicLongFieldUpdater<TransportTracer> STREAMS_SUCCEEDED_UPDATER =
      AtomicLongFieldUpdater.newUpdater(TransportTracer.class, "streamsSucceeded");
  private static final AtomicLongFieldUpdater<TransportTracer> STREAMS_FAILED_UPDATER =
      AtomicLongFieldUpdater.newUpdater(TransportTracer.class, "streamsFailed");
  private static final AtomicLongFieldUpdater<TransportTracer> MESSAGES_SENT_UPDATER =
      AtomicLongFieldUpdater.newUpdater(TransportTracer.class, "messagesSent");
  private static final AtomicLongFieldUpdater<TransportTracer> MESSAGES_RECEIVED_UPDATER =
      AtomicLongFieldUpdater.newUpdater(TransportTracer.class, "messagesReceived");
  private static final AtomicLongFieldUpdater<TransportTracer> KEEPALIVES_SENT_UPDATER =
      AtomicLongFieldUpdater.newUpdater(TransportTracer.class, "keepAlivesSent");
  private static final AtomicLongFieldUpdater<TransportTracer> LAST_MESSAGE_SENT_TIME_UPDATER =
      AtomicLongFieldUpdater.newUpdater(TransportTracer.class, "lastMessageSentTimeNnaos");
  private static final AtomicLongFieldUpdater<TransportTracer> LAST_MESSAGE_RECEIVED_TIME_UPDATER =
      AtomicLongFieldUpdater.newUpdater(TransportTracer.class, "lastMessageReceivedTimeNanos");

  // streamsStarted happens serially, so a volatile is sufficient
  private volatile long streamsStarted;
  // Maintain a separate unsynchronized counter to avoid reading from the volatile
  private long streamsStartedInternal;

  private volatile long lastStreamCreatedTimeNanos;
  private volatile long streamsSucceeded;
  private volatile long streamsFailed;
  private volatile long messagesSent;
  private volatile long messagesReceived;
  private volatile long keepAlivesSent;
  private volatile long lastMessageSentTimeNnaos;
  private volatile long lastMessageReceivedTimeNanos;
  // Default implementation just returns nulls
  private volatile FlowControlReader flowControlWindowReader;

  private final StreamTracer streamTracer = new StreamTracer() {
    @Override
    public void streamClosed(Status status) {
      if (status.isOk()) {
        STREAMS_SUCCEEDED_UPDATER.getAndIncrement(TransportTracer.this);
      } else {
        STREAMS_FAILED_UPDATER.getAndIncrement(TransportTracer.this);
      }
    }

    @Override
    public void outboundMessage(int seqNo) {
      MESSAGES_SENT_UPDATER.getAndIncrement(TransportTracer.this);
      updateNanoTimestamp(LAST_MESSAGE_SENT_TIME_UPDATER);
    }

    @Override
    public void inboundMessage(int seqNo) {
      MESSAGES_RECEIVED_UPDATER.getAndIncrement(TransportTracer.this);
      updateNanoTimestamp(LAST_MESSAGE_RECEIVED_TIME_UPDATER);
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
   * is sent. For servers, this happens when a header is received. This method must be called from
   * only one thread, but the resulting stats may be read from any thread.
   */
  public void reportStreamStarted() {
    streamsStartedInternal++;
    streamsStarted = streamsStartedInternal;
    lastStreamCreatedTimeNanos = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());
  }

  /**
   * Reports that a keep alive message was sent.
   */
  public void reportKeepAliveSent() {
    KEEPALIVES_SENT_UPDATER.getAndIncrement(this);
  }

  /**
   * Registers a {@link FlowControlReader} that can be used to read the local and remote flow
   * control window sizes.
   */
  public void setFlowControlWindowReader(FlowControlReader flowControlWindowReader) {
    Preconditions.checkNotNull(flowControlWindowReader);
    this.flowControlWindowReader = flowControlWindowReader;
  }

  /**
   * Returns the number of streams started on the transport.
   */
  public long getStreamsStarted() {
    return streamsStarted;
  }

  /**
   * Returns the number of streams ended successfully with an OK status.
   */
  public long getStreamsSucceeded() {
    return streamsSucceeded;
  }

  /**
   * Returns the number of streams completed with a non-OK status.
   */
  public long getStreamsFailed() {
    return streamsFailed;
  }

  /**
   * Returns the number of messages sent on the transport.
   */
  public long getMessagesSent() {
    return messagesSent;
  }

  /**
   * Returns the number of messages received on the transport.
   */
  public long getMessagesReceived() {
    return messagesReceived;
  }

  /**
   * Returns the number of keep alive messages sent on the transport.
   */
  public long getKeepAlivesSent() {
    return keepAlivesSent;
  }

  /**
   * Returns the last time a stream was created as millis since Unix epoch.
   */
  public long getLastStreamCreatedTimeNanos() {
    return lastStreamCreatedTimeNanos;
  }

  /**
   * Returns the last time a message was sent as millis since Unix epoch.
   */
  public long getLastMessageSentTimeNanos() {
    return lastMessageSentTimeNnaos;
  }

  /**
   * Returns the last time a message was received as millis since Unix epoch.
   */
  public long getLastMessageReceivedTimeNanos() {
    return lastMessageReceivedTimeNanos;
  }

  /**
   * Returns the remote flow control window as reported by the callback of
   * {@link #setFlowControlWindowReader}. Returns null if no callback was registered.
   * This call may block.
   */
  @Nullable
  public FlowControlWindows getFlowControlWindows() {
    // Copy value to local variable to avoid unnecessary volatile reads
    FlowControlReader reader = this.flowControlWindowReader;
    if (reader == null) {
      return null;
    }
    return reader.read();
  }

  /**
   * Updates a field representing a nano timestamp. Avoids races and only allows the value
   * to increase.
   */
  private void updateNanoTimestamp(AtomicLongFieldUpdater<TransportTracer> tsFieldUpdater) {
    long now = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());
    long oldVal = tsFieldUpdater.get(this);
    while (oldVal < now && !tsFieldUpdater.compareAndSet(this, oldVal, now)) {
      // CAS failed, read new timestamp and maybe try again
      oldVal = tsFieldUpdater.get(this);
    }
  }

  /**
   * A container that holds the local and remote flow control window sizes. Typically readers
   * are interested in both values, so we return both at the same time to reduce overhead.
   */
  public static final class FlowControlWindows {
    public final int remoteBytes;
    public final int localBytes;

    public FlowControlWindows(int localBytes, int remoteBytes) {
      this.localBytes = localBytes;
      this.remoteBytes = remoteBytes;
    }
  }

  /**
   * An interface for reading the local and remote flow control windows of the transport.
   * Implementations may block.
   */
  public interface FlowControlReader {
    FlowControlWindows read();
  }
}
