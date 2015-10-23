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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.Compressor;
import io.grpc.Metadata;
import io.grpc.Status;

import java.io.InputStream;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * A class that manages the outbound state on behalf of a stream. Manages the sending of headers,
 * messages, and trailers, as well as outbound flow control.
 */
final class Sender {
  /**
   * The default number of queued bytes for a given stream, below which {@link
   * StreamListener#onReady()} will be called.
   */
  private static final int ONREADY_THRESHOLD = 32 * 1024;

  private enum Phase {
    HEADERS, MESSAGE, STATUS
  }

  /**
   * The handler responsible for writing gRPC frames to the wire as well as reacting to outbound
   * flow control events.
   */
  interface Handler {
    /**
     * Writes out the given headers to the remote endpoint. This is called in the channel thread
     * context as a result of a call to {@link Sender#sendHeaders(Metadata)}.
     */
    void writeHeadersFrame(Metadata headers);

    /**
     * Writes out the given message to the remote endpoint. This is called in the channel thread
     * context as a result from either a call to {@link Sender#sendMessage(InputStream)}, {@link
     * Sender#flush()} or {@link Sender#close(Status, boolean)}.
     *
     * @param frame       the message to be sent.
     * @param endOfStream if {@code true}, indicates that the frame should be marked as the end of
     *                    stream.
     * @param flush       if {@code true}, indicates that any buffered IO should be flushed to the
     *                    remote endpoint.
     */
    void writeMessageFrame(@Nullable WritableBuffer frame, boolean endOfStream, boolean flush);

    /**
     * Writes out the specified trailers to the remote endpoint. This is only called for server-side
     * handlers. This is called in the channel thread context as a result of calling {@link
     * Sender#close(Status, boolean)}.
     *
     * @param trailers    the trailers to be sent
     * @param headersSent if {@code true}, indicates that headers were previously sent.
     */
    void writeTrailersFrame(Metadata trailers, boolean headersSent);

    /**
     * This indicates that the transport is now capable of sending additional messages without
     * requiring excessive buffering internally. This event is just a suggestion and the application
     * is free to ignore it, however doing so may result in excessive buffering within the
     * transport.
     */
    void onReady();
  }

  private final boolean server;
  private final Handler handler;
  private final MessageFramer framer;
  private Phase phase = Phase.HEADERS;
  private boolean headersSent;
  private Metadata stashedTrailers;

  /**
   * The number of bytes currently queued, waiting to be sent. When this falls below
   * onReadyThreshold, {@link StreamListener#onReady()} will be called.
   */
  @GuardedBy("onReadyLock")
  private int numSentBytesQueued;

  private final Object onReadyLock = new Object();

  Sender(WritableBufferAllocator bufferAllocator, Handler handler, boolean server) {
    this.handler = checkNotNull(handler, "handler");
    this.server = server;
    MessageFramer.Sink frameHandler = new MessageFramer.Sink() {
      @Override
      public void deliverFrame(WritableBuffer frame, boolean endOfStream, boolean flush) {
        onMessageFrame(frame, endOfStream, flush);
      }
    };
    framer = new MessageFramer(frameHandler, bufferAllocator);
  }

  void setCompressor(Compressor c) {
    // TODO(carl-mastrangelo): check that headers haven't already been sent.  I can't find where
    // the client stream changes outbound phase correctly, so I am ignoring it.
    framer.setCompressor(c);
  }

  /**
   * Request the sending of headers. Must be called from the channel thread context. This will call
   * {@link Handler#writeHeadersFrame(Metadata)}.
   */
  void sendHeaders(Metadata headers) {
    checkNotNull(headers, "headers");
    phase(Phase.HEADERS);
    headersSent = true;
    handler.writeHeadersFrame(headers);
    phase(Phase.MESSAGE);
  }

  /**
   * Request that a message be sent. Must be called from the channel thread context. This may call
   * {@link Handler#writeMessageFrame(WritableBuffer, boolean, boolean)}.
   */
  void sendMessage(InputStream message) {
    checkNotNull(message);
    phase(Phase.MESSAGE);
    if (!framer.isClosed()) {
      framer.writePayload(message);
    }
  }

  /**
   * Request to flush any buffered messages. Must be called form the channel thread context. This
   * may call {@link Handler#writeMessageFrame(WritableBuffer, boolean, boolean)}.
   */
  void flush() {
    if (!framer.isClosed()) {
      framer.flush();
    }
  }

  /**
   * If {@code true}, indicates that the transport is capable of sending additional messages without
   * requiring excessive buffering internally. Otherwise, {@link Handler#onReady()} will be called
   * when it turns {@code true}. This method is thread-safe.
   *
   * <p>This is just a suggestion and the application is free to ignore it, however doing so may
   * result in excessive buffering within the transport.
   */
  boolean isReady() {
    if (phase != Phase.STATUS) {
      synchronized (onReadyLock) {
        return numSentBytesQueued < ONREADY_THRESHOLD;
      }
    }
    return false;
  }

  /**
   * Closes this outbound context. Must be called from the channel thread context. If {@code send}
   * is {@code true}, this may call {@link Handler#writeMessageFrame(WritableBuffer, boolean,
   * boolean)}. If server-side, this will call {@link Handler#writeTrailersFrame(Metadata,
   * boolean)}.
   */
  void close(Status status, boolean send) {
    checkNotNull(status, "status");
    if (phase(Phase.STATUS) == Phase.STATUS) {
      // Already closed.
      return;
    }

    if (!send) {
      // Close the framer, but do not flush any remaining data.
      framer.dispose();
      return;
    }

    if (server) {
      // TODO(louiscryan): Remove
      if (stashedTrailers == null) {
        stashedTrailers = new Metadata();
      }
      writeStatusToTrailers(status);
    }

    // Close the framer and flush any remaining data. This will call onMessageFrame, which
    // will send trailers if we're a server.
    framer.close();
  }

  /**
   * Notifies this context that the given number of bytes has actually been sent to the remote
   * endpoint. May call {@link Handler#onReady()} if appropriate. This should be called from the
   * transport thread.
   *
   * @param numBytes the number of bytes that were sent by the transport.
   */
  void bytesSent(int numBytes) {
    boolean doNotify;
    synchronized (onReadyLock) {
      boolean belowThresholdBefore = numSentBytesQueued < ONREADY_THRESHOLD;
      numSentBytesQueued -= numBytes;
      boolean belowThresholdAfter = numSentBytesQueued < ONREADY_THRESHOLD;
      doNotify = !belowThresholdBefore && belowThresholdAfter;
    }
    if (doNotify) {
      // Notify the handler that we're ready to accept more data.
      handler.onReady();
    }
  }

  /**
   * Updates the number of bytes are being queued for sending to the remote endpoint.
   *
   * @param numBytes the number of bytes being sent.
   */
  private void bytesQueued(int numBytes) {
    if (numBytes > 0) {
      synchronized (onReadyLock) {
        numSentBytesQueued += numBytes;
      }
    }
  }

  /**
   * Handles the output of the {@link MessageFramer}.
   */
  private void onMessageFrame(WritableBuffer frame, boolean endOfStream, boolean flush) {
    // Update the number of queued bytes.
    bytesQueued(frame == null ? 0 : frame.readableBytes());

    if (endOfStream && server) {
      // Sending the last frame from a server.

      // Don't bother flushing since we're sending trailers.
      handler.writeMessageFrame(frame, false, false);

      // Send the trailers.
      Metadata trailers = stashedTrailers;
      boolean previouslySentHeaders = this.headersSent;
      stashedTrailers = null;
      this.headersSent = true;
      handler.writeTrailersFrame(trailers, previouslySentHeaders);
    } else {
      // Just send the message.
      handler.writeMessageFrame(frame, endOfStream, flush);
    }
  }

  private void writeStatusToTrailers(Status status) {
    stashedTrailers.removeAll(Status.CODE_KEY);
    stashedTrailers.removeAll(Status.MESSAGE_KEY);
    stashedTrailers.put(Status.CODE_KEY, status);
    if (status.getDescription() != null) {
      stashedTrailers.put(Status.MESSAGE_KEY, status.getDescription());
    }
  }

  /**
   * Transitions the outbound phase to the given phase and returns the previous phase.
   *
   * @throws IllegalStateException if the transition is disallowed
   */
  private Phase phase(Phase nextPhase) {
    Phase tmp = phase;
    phase = verifyNextPhase(phase, nextPhase);
    return tmp;
  }

  private Phase verifyNextPhase(Phase currentPhase, Phase nextPhase) {
    if (nextPhase.ordinal() < currentPhase.ordinal()) {
      throw new IllegalStateException(
              String.format("Cannot transition phase from %s to %s", currentPhase, nextPhase));
    }
    return nextPhase;
  }
}
