/*
 * Copyright 2014, Google Inc. All rights reserved.
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.grpc.Codec;
import io.grpc.Decompressor;
import io.grpc.Status;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Deframer for GRPC frames.
 *
 * <p>The operation of the deframer is divided between a {@link Sink} that is called from the
 * transport thread and a {@link Source} that produces gRPC messages.
 *
 * <p>This class is not thread-safe. All calls to {@link Sink} methods should be made in the
 * transport thread. It is thread-safe for a single thread to concurrently invoke {@link Source}
 * methods. Typically this will be the transport thread itself or the application thread.
 */
@NotThreadSafe
public class MessageDeframer {
  private static final int HEADER_LENGTH = 5;
  private static final int COMPRESSED_FLAG_MASK = 1;
  private static final int RESERVED_MASK = 0xFE;

  public enum CloseRequested {
    NONE,
    WHEN_COMPLETE,
    IMMEDIATELY
  }

  /**
   * A sink for use by the transport thread.
   *
   * <p>Calls to {@link Sink#request(int)} and {@link Sink#deframe(ReadableBuffer)} result in
   * callbacks to {@link Sink.Listener#scheduleDeframerSource(Source)}.
   */
  public interface Sink {
    void setMaxInboundMessageSize(int messageSize);

    /**
     * Sets the decompressor available to use. The message encoding for the stream comes later in
     * time, and thus will not be available at the time of construction. This should only be set
     * once, since the compression codec cannot change after the headers have been sent.
     *
     * @param decompressor the decompressing wrapper.
     */
    void setDecompressor(Decompressor decompressor);

    /**
     * Requests up to the given number of messages from the call to be delivered via calls to
     * {@link Source#next()}. No additional messages will be delivered.
     *
     * <p>Calls to this method will trigger the {@link Sink.Listener#scheduleDeframerSource(Source)}
     * callback.
     *
     * @param numMessages the requested number of messages to be delivered to the listener.
     * @throws IllegalStateException if {@link #scheduleClose(boolean stopDelivery)} has been
     *     called previously with {@code stopDelivery=true}.
     */
    void request(int numMessages);

    /**
     * Adds the given data to this deframer.
     *
     * <p>Calls to this method will trigger the {@link Sink.Listener#scheduleDeframerSource(Source)}
     * callback.
     *
     * @param data the raw data read from the remote endpoint. Must be non-null.
     * @throws IllegalStateException if {@link #scheduleClose(boolean)} has been called previously.
     */
    void deframe(ReadableBuffer data);

    /**
     * Schedule closing of the deframer. Since {@link Source} may be running in a separate thread,
     * the transport cannot assume the deframer closes immediately. When the close does occur,
     * {@link Source} will invoke {@link Source.Listener#deframerClosed(boolean)}.
     *
     * <p>If {@code stopDelivery} is false, {@link Source#next} will continue to return messages
     * until all complete queued messages have been delivered. Typically this is used when the
     * transport receives trailers or end-of-stream.
     *
     * <p>If {@code stopDelivery} is true, the next call to {@link Source#next} will trigger a
     * close of the deframer, regardless of any queued messages. Typically this is used upon a
     * client cancellation, as any pending messages will be discarded. There is an inherent race
     * condition when deframing is done outside of the transport thread, so it is possible for the
     * client to receive a message before close takes place even with {@code stopDelivery=true}.
     *
     * <p>Calls to this method will trigger the {@link Sink.Listener#scheduleDeframerSource(Source)}
     * callback.
     *
     * @param stopDelivery whether to close the deframer even if messages remain undelivered
     */
    void scheduleClose(boolean stopDelivery);

    /** Indicates {@link #scheduleClose(boolean)} has been called. */
    boolean isScheduledToClose();

    /**
     * Indicates whether {@link #scheduleClose(boolean stopDelivery)} has been called with
     * @{code stopDelivery=true}.
     */
    boolean isScheduledToCloseImmediately();

    /** A listener of deframing sink events. */
    public interface Listener {
      /**
       * Called to schedule {@link Source} to run in the deframing thread. Invoked from transport
       * thread.
       */
      void scheduleDeframerSource(Source source);
    }
  }

  /** A source for deframed gRPC messages. */
  public interface Source {
    /**
     * Returns the next gRPC message, if the data has been received by {@link Source} and the
     * application has requested another message.
     *
     * <p>Calls to this method will also check if the deframer should be closed due to a previous
     * call to {@link Sink#scheduleClose(boolean)}.
     */
    @Nullable
    InputStream next();

    /**
     * A listener of deframing source events. If deframing occurs outside of the transport
     * thread, it is up to the implementation to ensure that these methods are thread-safe:
     * typically this means either scheduling a response on the transport thread itself or
     * obtaining the necessary lock.
     */
    interface Listener {
      /**
       * Called when the given number of bytes has been read from the input source of the deframer.
       * This is typically used to indicate to the underlying transport that more data can be
       * accepted.
       *
       * @param numBytes the number of bytes read from the deframer's input source.
       */
      void bytesRead(int numBytes);

      /**
       * Called when deframer closes in response to a close scheduled via
       * {@link Sink#scheduleClose(boolean)}. If invoked, no further callbacks will be sent and no
       * further calls may be made to the deframer.
       *
       * @param hasPartialMessage whether there is an incomplete message queued.
       */
      void deframerClosed(boolean hasPartialMessage);

      /**
       * Called when deframe fails. If invoked, it will be followed by
       * {@link #deframerClosed(boolean)}, as the deframer is in an unrecoverable state.
       */
      void deframeFailed(Throwable t);
    }
  }

  /** Used to track whether {@link Source} is processing a gRPC message header or body. */
  private enum State {
    HEADER,
    BODY
  }

  private final StatsTraceContext statsTraceCtx;
  private final String debugString;
  private final DeframerSource source;
  private final DeframerSink sink;

  private int maxInboundMessageSize;
  private Decompressor decompressor;

  private CompositeReadableBuffer unprocessed = new CompositeReadableBuffer();
  private final AtomicInteger pendingDeliveries = new AtomicInteger();

  /**
   * This enum allows the transport thread to schedule a close on the source thread.
   *
   * <p>CloseRequested.WHEN_COMPLETE indicates that any messages already queued by the deframer
   * should still be delivered before closing. This means, for example, that the deframer will not
   * close when there are queued messages that the client has not yet requested.
   * CloseRequested.WHEN_COMPLETE is appropriate when the stream has closed due to a network event,
   * such as trailers received or a RST_STREAM, and the client should first receive any earlier
   * messages before receiving notice of the closure.
   *
   * <p>CloseRequested.IMMEDIATELY will close the deframer without delivering all queued messages.
   * This is appropriate when the stream is closing due to an event such as a local cancellation,
   * where the client is purposefully discarding any messages that may have already been received
   * over the wire but not deframed.
   *
   * <p>{@code closeRequested} can only change from CloseRequested.NONE to
   * CloseRequested.WHEN_COMPLETE, from CloseRequested.NONE to CloseRequested.IMMEDIATELY, and from
   * CloseRequested.WHEN_COMPLETE to CloseRequested.IMMEDIATELY.
   *
   * <p>When set to CloseRequested.WHEN_COMPLETE, the transport thread is allowed to call {@link
   * Sink#request(int)} but further calls to {@link Sink#deframe(ReadableBuffer)} will throw an
   * exception.
   *
   * <p>When set to CloseRequested.IMMEDIATELY, the transport thread must not invoke either {@link
   * Sink#request(int)} or {@link Sink#deframe(ReadableBuffer)}.
   */
  private volatile CloseRequested closeRequested = CloseRequested.NONE;

  /**
   * Create a deframer.
   *
   * @param sinkListener listener for deframer sink events.
   * @param sourceListener listener for deframer source events
   * @param decompressor the compression used if a compressed frame is encountered, with {@code
   *     NONE} meaning unsupported
   * @param maxMessageSize the maximum allowed size for received messages.
   * @param debugString a string that will appear on errors statuses
   */
  public MessageDeframer(
      Sink.Listener sinkListener,
      Source.Listener sourceListener,
      Decompressor decompressor,
      int maxMessageSize,
      StatsTraceContext statsTraceCtx,
      String debugString) {
    this.decompressor = Preconditions.checkNotNull(decompressor, "decompressor");
    this.maxInboundMessageSize = maxMessageSize;
    this.statsTraceCtx = checkNotNull(statsTraceCtx, "statsTraceCtx");
    this.debugString = debugString;
    this.sink = new DeframerSink(Preconditions.checkNotNull(sinkListener, "sink listener"));
    this.source = new DeframerSource(checkNotNull(sourceListener, "source listener"));
  }

  public Sink sink() {
    return sink;
  }

  private class DeframerSink implements Sink {
    private final Sink.Listener sinkListener;

    /** Set to true when {@link #request(int)} is called. */
    private boolean requestCalled;
    /** Set to true when {@link #deframe(ReadableBuffer)} is called. */
    private boolean deframeCalled;

    private DeframerSink(Sink.Listener sinkListener) {
      this.sinkListener = sinkListener;
    }

    @Override
    public void setMaxInboundMessageSize(int messageSize) {
      Preconditions.checkState(
          !requestCalled, "already requested messages, too late to set max inbound message size");
      maxInboundMessageSize = messageSize;
    }

    @Override
    public void setDecompressor(Decompressor decompressor) {
      Preconditions.checkState(
          !deframeCalled, "already started to deframe, too late to set decompressor");
      MessageDeframer.this.decompressor =
          checkNotNull(decompressor, "Can't pass an empty decompressor");
    }

    @Override
    public void request(int numMessages) {
      Preconditions.checkState(
          closeRequested != CloseRequested.IMMEDIATELY, "immediate close requested");
      Preconditions.checkArgument(numMessages > 0, "numMessages must be > 0");
      requestCalled = true;
      pendingDeliveries.getAndAdd(numMessages);
      sinkListener.scheduleDeframerSource(source);
    }

    @Override
    public void deframe(ReadableBuffer data) {
      Preconditions.checkNotNull(data, "data");
      deframeCalled = true;
      boolean needToCloseData = true;
      try {
        Preconditions.checkState(!isScheduledToClose(), "close already scheduled");
        unprocessed.addBuffer(data);
        needToCloseData = false;
        sinkListener.scheduleDeframerSource(source);
      } finally {
        if (needToCloseData) {
          data.close();
        }
      }
    }

    @Override
    public void scheduleClose(boolean stopDelivery) {
      Preconditions.checkState(
          !isScheduledToClose() || (stopDelivery && !isScheduledToCloseImmediately()),
          "close already scheduled");
      if (stopDelivery) {
        closeRequested = CloseRequested.IMMEDIATELY;
      } else {
        closeRequested = CloseRequested.WHEN_COMPLETE;
      }
      // Ensure that the source will see the scheduled close.
      sinkListener.scheduleDeframerSource(source);
    }

    @Override
    public boolean isScheduledToClose() {
      return closeRequested != CloseRequested.NONE;
    }

    @Override
    public boolean isScheduledToCloseImmediately() {
      return closeRequested == CloseRequested.IMMEDIATELY;
    }
  }

  private class DeframerSource implements Source {
    private final Source.Listener sourceListener;
    private State state = State.HEADER;
    private int requiredLength = HEADER_LENGTH;
    private boolean compressedFlag;
    private boolean closed;
    private CompositeReadableBuffer nextFrame;

    /**
     * Indicates a deframing error occurred. When this is set to true, deframeFailed() must be
     * called on the listener and no further callbacks may be issued.
     */
    private boolean deframeFailed;

    private DeframerSource(Source.Listener sourceListener) {
      this.sourceListener = sourceListener;
    }

    private void closeAndNotify() {
      Preconditions.checkState(!closed, "source already closed");
      boolean hasPartialMessage = nextFrame != null && nextFrame.readableBytes() > 0;
      closed = true;
      try {
        if (unprocessed != null) {
          unprocessed.close();
        }
        if (nextFrame != null) {
          nextFrame.close();
        }
      } finally {
        unprocessed = null;
        nextFrame = null;
      }
      sourceListener.deframerClosed(hasPartialMessage);
    }

    /** Reads and delivers a messages to the listener, if possible. */
    @Override
    public InputStream next() {
      if (deframeFailed) {
        return null;
      }
      if (closed) {
        // May be invoked after close by previous calls to scheduleDeframerSource.
        return null;
      }

      while (closeRequested != CloseRequested.IMMEDIATELY
          && (pendingDeliveries.get() > 0 && readRequiredBytes())) {
        switch (state) {
          case HEADER:
            processHeader();
            if (deframeFailed) {
              // Error occurred
              return null;
            }
            break;
          case BODY:
            // Read the body and deliver the message.
            InputStream toReturn = processBody();
            if (deframeFailed) {
              // Error occurred
              return null;
            }
            // Since we are about to deliver a message, decrement the number of pending
            // deliveries remaining.
            pendingDeliveries.getAndDecrement();
            return toReturn;
          default:
            deframeFailed = true;
            AssertionError t = new AssertionError("Invalid state: " + state);
            sourceListener.deframeFailed(t);
            closeAndNotify();
            return null;
        }
      }

      CloseRequested closeRequestedSaved = closeRequested;
      /*
       * We are stalled when there are no more bytes to process. At this point in the function,
       * either all frames have been delivered, or unprocessed is empty.  If there is a partial
       * message, it will be inside nextFrame and not in unprocessed.  If there is extra data but
       * no pending deliveries, it will be in unprocessed.
       */
      boolean stalled = unprocessed.readableBytes() == 0;

      if (closeRequestedSaved == CloseRequested.IMMEDIATELY
          || (stalled && closeRequestedSaved == CloseRequested.WHEN_COMPLETE)) {
        closeAndNotify();
      }
      return null;
    }

    /**
     * Attempts to read the required bytes into nextFrame.
     *
     * @return {@code true} if all of the required bytes have been read.
     */
    private boolean readRequiredBytes() {
      int totalBytesRead = 0;
      try {
        if (nextFrame == null) {
          nextFrame = new CompositeReadableBuffer();
        }

        // Read until the buffer contains all the required bytes.
        int missingBytes;
        while ((missingBytes = requiredLength - nextFrame.readableBytes()) > 0) {
          if (unprocessed.readableBytes() == 0) {
            // No more data is available.
            return false;
          }
          int toRead = Math.min(missingBytes, unprocessed.readableBytes());
          totalBytesRead += toRead;
          nextFrame.addBuffer(unprocessed.readBytes(toRead));
        }
        return true;
      } finally {
        if (totalBytesRead > 0) {
          sourceListener.bytesRead(totalBytesRead);
          if (state == State.BODY) {
            statsTraceCtx.inboundWireSize(totalBytesRead);
          }
        }
      }
    }

    /**
     * Processes the GRPC compression header which is composed of the compression flag and the outer
     * frame length.
     */
    private void processHeader() {
      int type = nextFrame.readUnsignedByte();
      if ((type & RESERVED_MASK) != 0) {
        deframeFailed = true;
        sourceListener.deframeFailed(
            Status.INTERNAL
                .withDescription(debugString + ": Frame header malformed: reserved bits not zero")
                .asRuntimeException());
        closeAndNotify();
        return;
      }
      compressedFlag = (type & COMPRESSED_FLAG_MASK) != 0;

      // Update the required length to include the length of the frame.
      requiredLength = nextFrame.readInt();
      if (requiredLength < 0 || requiredLength > maxInboundMessageSize) {
        deframeFailed = true;
        sourceListener.deframeFailed(
            Status.RESOURCE_EXHAUSTED
                .withDescription(
                    String.format(
                        "%s: Frame size %d exceeds maximum: %d. ",
                        debugString, requiredLength, maxInboundMessageSize))
                .asRuntimeException());
        closeAndNotify();
        return;
      }
      statsTraceCtx.inboundMessage();
      // Continue reading the frame body.
      state = State.BODY;
    }

    /**
     * Processes the GRPC message body, which depending on frame header flags may be compressed.
     */
    @Nullable
    private InputStream processBody() {
      InputStream stream = compressedFlag ? getCompressedBody() : getUncompressedBody();
      nextFrame = null;

      // Done with this frame, begin processing the next header.
      state = State.HEADER;
      requiredLength = HEADER_LENGTH;

      return stream;
    }

    private InputStream getUncompressedBody() {
      statsTraceCtx.inboundUncompressedSize(nextFrame.readableBytes());
      return ReadableBuffers.openStream(nextFrame, true);
    }

    /**
     * Deframes the compressed body.
     *
     * @return null if error occurred
     */
    @Nullable
    private InputStream getCompressedBody() {
      if (decompressor == Codec.Identity.NONE) {
        deframeFailed = true;
        sourceListener.deframeFailed(
            Status.INTERNAL
                .withDescription(
                    debugString + ": Can't decode compressed frame as compression not configured.")
                .asRuntimeException());
        closeAndNotify();
        return null;
      }

      try {
        // Enforce the maxMessageSize limit on the returned stream.
        InputStream unlimitedStream =
            decompressor.decompress(ReadableBuffers.openStream(nextFrame, true));
        return new SizeEnforcingInputStream(
            unlimitedStream, maxInboundMessageSize, statsTraceCtx, debugString);
      } catch (IOException e) {
        deframeFailed = true;
        sourceListener.deframeFailed(new RuntimeException(e));
        closeAndNotify();
        return null;
      }
    }
  }

  /**
   * An {@link InputStream} that enforces the {@link #maxMessageSize} limit for compressed frames.
   */
  @VisibleForTesting
  static final class SizeEnforcingInputStream extends FilterInputStream {
    private final int maxMessageSize;
    private final StatsTraceContext statsTraceCtx;
    private final String debugString;
    private long maxCount;
    private long count;
    private long mark = -1;

    SizeEnforcingInputStream(InputStream in, int maxMessageSize, StatsTraceContext statsTraceCtx,
        String debugString) {
      super(in);
      this.maxMessageSize = maxMessageSize;
      this.statsTraceCtx = statsTraceCtx;
      this.debugString = debugString;
    }

    @Override
    public int read() throws IOException {
      int result = in.read();
      if (result != -1) {
        count++;
      }
      verifySize();
      reportCount();
      return result;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int result = in.read(b, off, len);
      if (result != -1) {
        count += result;
      }
      verifySize();
      reportCount();
      return result;
    }

    @Override
    public long skip(long n) throws IOException {
      long result = in.skip(n);
      count += result;
      verifySize();
      reportCount();
      return result;
    }

    @Override
    public synchronized void mark(int readlimit) {
      in.mark(readlimit);
      mark = count;
      // it's okay to mark even if mark isn't supported, as reset won't work
    }

    @Override
    public synchronized void reset() throws IOException {
      if (!in.markSupported()) {
        throw new IOException("Mark not supported");
      }
      if (mark == -1) {
        throw new IOException("Mark not set");
      }

      in.reset();
      count = mark;
    }

    private void reportCount() {
      if (count > maxCount) {
        statsTraceCtx.inboundUncompressedSize(count - maxCount);
        maxCount = count;
      }
    }

    private void verifySize() {
      if (count > maxMessageSize) {
        throw Status.RESOURCE_EXHAUSTED.withDescription(String.format(
                "%s: Compressed frame exceeds maximum frame size: %d. Bytes read: %d. ",
                debugString, maxMessageSize, count)).asRuntimeException();
      }
    }
  }
}
