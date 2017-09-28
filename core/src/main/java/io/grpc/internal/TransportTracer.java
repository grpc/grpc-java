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
import java.util.concurrent.TimeUnit;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * A class for gathering statistics about a transport. This is an experimental feature.
 * Can only be called from the transport thread.
 */
@NotThreadSafe
public final class TransportTracer {
  private long streamsStarted;
  private long lastStreamCreatedTimeNanos;
  private long streamsSucceeded;
  private long streamsFailed;
  private long messagesSent;
  private long messagesReceived;
  private long keepAlivesSent;
  private long lastMessageSentTimeNanos;
  private long lastMessageReceivedTimeNanos;
  private FlowControlReader flowControlWindowReader;

  public Stats getStats() {
    return new Stats(
        streamsStarted,
        lastStreamCreatedTimeNanos,
        streamsSucceeded,
        streamsFailed,
        messagesSent,
        messagesReceived,
        keepAlivesSent,
        lastMessageSentTimeNanos,
        lastMessageReceivedTimeNanos,
        flowControlWindowReader);
  }

  /**
   * Called by the transport to report a stream has started. For clients, this happens when a header
   * is sent. For servers, this happens when a header is received. This method must be called from
   * only one thread, but the resulting stats may be read from any thread.
   */
  void reportStreamStarted() {
    streamsStarted++;
    lastStreamCreatedTimeNanos = currentTimeNanos();
  }

  /**
   * Reports that a stream closed with the specified Status.
   */
  void reportStreamClosed(Status status) {
    if (status.isOk()) {
        streamsSucceeded++;
      } else {
        streamsFailed++;
      }
  }

  /**
   * Reports that a message was successfully sent.
   */
  void reportMessageSent() {
    messagesSent++;
    lastMessageSentTimeNanos = currentTimeNanos();
  }

  /**
   * Reports that a message was successfully received.
   */
  void reportMessageReceived() {
      messagesReceived++;
      lastMessageReceivedTimeNanos = currentTimeNanos();
    }

  /**
   * Reports that a keep alive message was sent.
   */
  public void reportKeepAliveSent() {
    keepAlivesSent++;
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
   * A container that holds the local and remote flow control window sizes.
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
   */
  public interface FlowControlReader {
    FlowControlWindows read();
  }

  private static long currentTimeNanos() {
    return TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());
  }

  /**
   * A read only container of stats from the transport tracer.
   */
  public static final class Stats {
    public final long streamsStarted;
    public final long lastStreamCreatedTimeNanos;
    public final long streamsSucceeded;
    public final long streamsFailed;
    public final long messagesSent;
    public final long messagesReceived;
    public final long keepAlivesSent;
    public final long lastMessageSentTimeNanos;
    public final long lastMessageReceivedTimeNanos;
    public final int localFlowControlWindow;
    public final int remoteFlowControlWindow;

    private Stats(
        long streamsStarted,
        long lastStreamCreatedTimeNanos,
        long streamsSucceeded,
        long streamsFailed,
        long messagesSent,
        long messagesReceived,
        long keepAlivesSent,
        long lastMessageSentTimeNanos,
        long lastMessageReceivedTimeNanos,
        FlowControlReader flowControlReader) {
      this.streamsStarted = streamsStarted;
      this.lastStreamCreatedTimeNanos = lastStreamCreatedTimeNanos;
      this.streamsSucceeded = streamsSucceeded;
      this.streamsFailed = streamsFailed;
      this.messagesSent = messagesSent;
      this.messagesReceived = messagesReceived;
      this.keepAlivesSent = keepAlivesSent;
      this.lastMessageSentTimeNanos = lastMessageSentTimeNanos;
      this.lastMessageReceivedTimeNanos = lastMessageReceivedTimeNanos;
      if (flowControlReader == null) {
        this.localFlowControlWindow = -1;
      this.remoteFlowControlWindow = -1;
      } else {
        FlowControlWindows windows = flowControlReader.read();
        this.localFlowControlWindow = windows.localBytes;
        this.remoteFlowControlWindow = windows.remoteBytes;
      }
    }
  }
}
