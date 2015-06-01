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

package io.grpc.transport;

import com.google.common.base.Preconditions;

import io.grpc.Status;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Deframer for GRPC frames.
 *
 * <p>This class is partially thread-safe. The transport thread may call any method except
 * {@link #request(int)} which is reserved for the application thread.
 *
 */
@NotThreadSafe
public class MessageDeframer implements Closeable, StreamListener.MessageProducer {
  private static final int HEADER_LENGTH = 5;
  private static final int COMPRESSED_FLAG_MASK = 1;
  private static final int RESERVED_MASK = 0xFE;

  private static final Frame END_OF_STREAM = new Frame(0);

  public enum Compression {
    NONE, GZIP
  }

  /**
   * A listener of deframing events.
   */
  public interface Listener {

    /**
     * Called when the given number of bytes has been read from the input source of the deframer.
     *
     * @param numBytes the number of bytes read from the deframer's input source.
     */
    void bytesRead(int numBytes);

    /**
     * Called to indicate that more messages may be available for read.
     */
    void messagesAvailable(StreamListener.MessageProducer messages);

    /**
     * Called when end-of-stream has not yet been reached but there are no complete messages
     * remaining to be delivered.
     */
    void deliveryStalled();

    /**
     * Called when the stream is complete and all messages have been successfully delivered.
     */
    void endOfStream();
  }

  private enum State {
    HEADER, BODY
  }

  private final Listener listener;
  private final Compression compression;
  private State state = State.HEADER;
  private int requiredLength = HEADER_LENGTH;
  private boolean compressedFlag;
  private boolean endOfStream;
  private CompositeReadableBuffer unprocessed = new CompositeReadableBuffer();
  private final AtomicReference<ArrayDeque<Frame>> queueRef =
      new AtomicReference<ArrayDeque<Frame>>();

  private boolean deliveryStalled = true;

  // The number of undelivered messages requested by the application.
  private long requestedMessageCount;
  private boolean messagesAvailable;
  // Used to track whether the application is making re-entrant calls to request.
  private boolean appInRequest;
  // The number of messages requested by the application observed by the transport thread
  // used to optimize return of flow-control.
  private AtomicLong transportCount = new AtomicLong(0);

  // Number of bytes received from the transport
  private long bytesReceived;
  // Number of bytes received from the transport that have been consumed.
  private long bytesConsumed;
  // Number of flow-control bytes that have been returned to the transport either immediately
  // or via the queue of events processed by the application.
  private long bytesReturned;

  /**
   * Creates a deframer. Compression will not be supported.
   *
   * @param listener listener for deframer events.
   */
  public MessageDeframer(Listener listener) {
    this(listener, Compression.NONE);
  }

  /**
   * Create a deframer.
   *
   * @param listener listener for deframer events.
   * @param compression the compression used if a compressed frame is encountered, with {@code NONE}
   *        meaning unsupported
   */
  public MessageDeframer(Listener listener, Compression compression) {
    this.listener = Preconditions.checkNotNull(listener, "sink");
    this.compression = Preconditions.checkNotNull(compression, "compression");
    queueRef.set(new ArrayDeque<Frame>(4));
  }

  /**
   * Requests up to the given number of messages from the call to be delivered to
   * {@link Listener#messagesAvailable}. No additional messages will be delivered.
   * Only called by the application thread.
   *
   * <p>If {@link #close()} has been called, this method will have no effect.
   *
   * @param numMessages the requested number of messages to be delivered to the listener.
   */
  void request(int numMessages) {
    Preconditions.checkArgument(numMessages > 0, "numMessages must be > 0");
    if (isClosed()) {
      return;
    }
    // Make the number of requested messages visible to the transport thread.
    transportCount.addAndGet(numMessages);
    requestedMessageCount += numMessages;
    if (messagesAvailable && !appInRequest) {
      appInRequest = true;
      try {
        listener.messagesAvailable(this);
      } finally {
        appInRequest = false;
      }
    }
  }

  /**
   * Called in the application thread to consume the available messages provided by the transport.
   * @param consumer which receives all available messages.
   */
  @Override
  public int drainTo(StreamListener.MessageConsumer consumer) {
    int count = 0;
    ArrayDeque<Frame> current;
    do {
      // Get the queue set by the transport, guaranteed to eventually be non-null
      while ((current = queueRef.getAndSet(null)) == null) {
        // Consider yielding if we are waiting too long for the transport to set a queue.
      }
      // Consume the queue
      count += drainTo(consumer, current);
      messagesAvailable = !current.isEmpty();

      if (messagesAvailable) {
        // We can't fully consume the queue yet so we have to accumulate anything produced
        // by the transport thread while we were executing and return the merged version.
        ArrayDeque<Frame> next = null;
        while (!queueRef.compareAndSet(next, current)) {
          next = queueRef.get();
          if (next != null) {
            for (Frame f = next.poll(); f != null; f = next.poll()) {
              current.add(f);
            }
          }
        }
        break;
      }

      // If we can return the queue to the transport thread we are done, if not then
      // we have another queue to try and consume.
    } while (!queueRef.compareAndSet(null, current));
    return count;
  }

  private int drainTo(StreamListener.MessageConsumer consumer, ArrayDeque<Frame> queue) {
    int count = 0;
    int consumedBytes = 0;
    boolean endOfStream = false;
    try {
      Frame frame;
      do {
        frame = queue.peek();
        if (frame == null) {
          // Nothing to read
          break;
        }
        if (frame == END_OF_STREAM) {
          // Transport enqueued EOS, so empty queue and report to the listener
          queue.clear();
          endOfStream = true;
          break;
        }
        // Can't deliver messages to the consumer or return flow control
        if (requestedMessageCount == 0) {
          break;
        }
        // Consume the message from the queue
        queue.poll();
        consumedBytes += frame.consumedBytes;
        if (frame.stream != null) {
          try {
            // Calls to consumer may trigger a call to request in the same thread, we prevent
            // the unnecessary call to notify again as this loop will pick up the additional
            // count.
            appInRequest = true;
            count++;
            consumer.accept(frame.stream);
          } catch (Throwable t) {
            // Decision point: Log / exit / drain the available messages
          } finally {
            try {
              frame.stream.close();
            } catch (IOException ioe) {
              // Decision point: Worth logging?
            }
            appInRequest = false;
            requestedMessageCount--;
          }
        }
      } while (true);
    } finally {
      if (consumedBytes > 0) {
        // Decision point: Returning flow-control bytes after all the messages rather than after
        // each one. This could be head-of-line blocking
        listener.bytesRead(consumedBytes);
      }
      if (endOfStream) {
        listener.endOfStream();
      }
    }
    return count;
  }

  /**
   * Adds the given data to this deframer and attempts delivery to the sink. Only called by
   * the transport thread.
   *
   * @param data the raw data read from the remote endpoint. Must be non-null.
   * @param endOfStream if {@code true}, indicates that {@code data} is the end of the stream from
   *        the remote endpoint.
   * @throws IllegalStateException if {@link #close()} has been called previously or if
   *         {@link #deframe(ReadableBuffer, boolean)} has previously been called with
   *         {@code endOfStream=true}.
   */
  public void deframe(ReadableBuffer data, boolean endOfStream) {
    bytesReceived += data.readableBytes();
    Preconditions.checkNotNull(data, "data");
    boolean needToCloseData = true;
    try {
      checkNotClosed();
      Preconditions.checkState(!this.endOfStream, "Past end of stream");

      needToCloseData = false;
      unprocessed.addBuffer(data);

      // Indicate that all of the data for this stream has been received.
      this.endOfStream = endOfStream;
      enqueueFrames();
    } finally {
      if (needToCloseData) {
        data.close();
      }
    }
  }

  /**
   * Add messages to the delivery queue. This is only called by the transport thread.
   */
  private void enqueueFrames() {
    ArrayDeque<Frame> queue = queueRef.getAndSet(null);
    if (queue == null) {
      // Application thread has the queue so create a new one and accumulate into it so we don't
      // block on the application thread.
      queue = new ArrayDeque<Frame>();
    }

    boolean readBody = false;
    boolean queuedEos = false;
    long unreturnedBytes = bytesReceived - bytesReturned;
    long bytesToReturnImmediate = 0;
    try {
      // Process the uncompressed bytes.
      CompositeReadableBuffer buffer = readRequiredBytes();
      while (buffer != null) {
        bytesConsumed += buffer.readableBytes();
        bytesToReturnImmediate += returnBytes(true, queue);
        switch (state) {
          case HEADER:
            processHeader(buffer);
            break;
          case BODY:
            // Read the body and enqueue the message.
            processBody(buffer, queue);
            readBody = true;
            break;
          default:
            throw new AssertionError("Invalid state: " + state);
        }
        buffer = readRequiredBytes();
      }
      if (endOfStream) {
        if (state == State.BODY || unprocessed.readableBytes() > 0) {
          // We've received the entire stream and have data available but we don't have
          // enough to read the next frame ... this is bad.
          throw Status.INTERNAL.withDescription("Encountered end-of-stream mid-frame")
              .asRuntimeException();
        } else {
          // Can't notify stream yet as there were not enough message requests available
          // to consume all the messages. Instead we have to enqueue signalling EOS on the
          // stream.
          if (transportCount.get() < 0) {
            queuedEos = true;
            queue.add(END_OF_STREAM);
          }
        }
      }
      // Can immediately return all bytes that have not been returned via the queue at this point.
      bytesToReturnImmediate += returnBytes(false, queue);
      if (bytesToReturnImmediate > 0) {
        listener.bytesRead((int) bytesToReturnImmediate);
      }
    } finally {
      ArrayDeque<Frame> prev = null;
      // Make the queue visible to the application thread.
      while (!queueRef.compareAndSet(prev, queue)) {
        // Can only be not null if the application thread is returning a queue that it
        // was processing when this method stared.
        prev = queueRef.get();
        if (prev != null && !prev.isEmpty()) {
          for (Frame f = prev.pollLast(); f != null; f = prev.pollLast()) {
            queue.addFirst(f);
          }
        }
      }
    }

    // We are stalled when there is insufficient data to read another frame.
    // This allows delivering errors as soon as the buffered input has been consumed,
    // independent of whether the application has requested another message.
    boolean stalled = unprocessed.readableBytes() == 0 || readBody;

    // Must notify if we've read a body or we had to return flow-control via the queue.
    if (readBody || unreturnedBytes > bytesToReturnImmediate) {
      // Notify the listener that messages may be available. It's possible that the messages are
      // already being consumed in another thread that is racing with this one and that the
      // listener will have nothing to process as a result of this call but we must do
      // it to guarantee that all payloads are consumed.
      listener.messagesAvailable(this);
    }

    // Can notify the listener immediately instead of via the queue as there we enough
    // request tokens visible to the transport when it enqueued messages to know that
    // the queue can be fully drained by the application.
    if (endOfStream && !queuedEos) {
      listener.endOfStream();
    }

    // Never indicate that we're stalled if we've received all the data for the stream.
    stalled &= !endOfStream;

    // If we're transitioning to the stalled state, notify the listener.
    boolean previouslyStalled = deliveryStalled;
    deliveryStalled = stalled;
    if (stalled && !previouslyStalled) {
      listener.deliveryStalled();
    }
  }

  private long returnBytes(boolean consumed, ArrayDeque<Frame> queue) {
    // Check if we need to return bytes that have been received but not consumed.
    long toReturn = (consumed ? bytesConsumed : bytesReceived) - bytesReturned;
    if (toReturn > 0) {
      bytesReturned += toReturn;
      // Have consumed bytes that need to be returned
      if (transportCount.get() <= 0) {
        Frame tail = queue.peekLast();
        if (tail != null && tail.stream == null) {
          tail.consumedBytes += toReturn;
        } else {
          queue.add(new Frame(toReturn));
        }
        return 0;
      }
    }
    return toReturn;
  }

  /**
   * Indicates whether delivery is currently stalled, pending receipt of more data.
   */
  public boolean isStalled() {
    return deliveryStalled;
  }

  /**
   * Closes this deframer and frees any resources. After this method is called, additional
   * calls will have no effect.
   */
  @Override
  public void close() {
    try {
      if (unprocessed != null) {
        unprocessed.close();
      }
    } finally {
      unprocessed = null;
    }
  }

  /**
   * Indicates whether or not this deframer has been closed.
   */
  public boolean isClosed() {
    return unprocessed == null;
  }

  /**
   * Throws if this deframer has already been closed.
   */
  private void checkNotClosed() {
    Preconditions.checkState(!isClosed(), "MessageDeframer is already closed");
  }

  /**
   * Attempts to read the required bytes into nextFrame.
   *
   * @return a buffer with the required length of bytes or {@code null} if insufficient bytes are
   *     available.
   */
  private CompositeReadableBuffer readRequiredBytes() {
    if (requiredLength == 0) {
      // Occurs when body is 0 length
      return unprocessed.readBytes(0);
    }
    if (unprocessed.readableBytes() == 0) {
      return null;
    }
    if (unprocessed.readableBytes() < requiredLength) {
      return null;
    } else {
      return unprocessed.readBytes(requiredLength);
    }
  }

  /**
   * Processes the GRPC compression header which is composed of the compression flag and the outer
   * frame length.
   */
  private void processHeader(CompositeReadableBuffer header) {
    int type = header.readUnsignedByte();
    if ((type & RESERVED_MASK) != 0) {
      throw Status.INTERNAL.withDescription("Frame header malformed: reserved bits not zero")
          .asRuntimeException();
    }
    compressedFlag = (type & COMPRESSED_FLAG_MASK) != 0;

    // Update the required length to include the length of the frame.
    requiredLength = header.readInt();

    // Continue reading the frame body.
    state = State.BODY;
  }

  /**
   * Processes the body of the GRPC frame.
   */
  private void processBody(CompositeReadableBuffer body, ArrayDeque<Frame> queue) {
    InputStream stream = compressedFlag ? getCompressedBody(body) : getUncompressedBody(body);
    // Add the body to the queue and consume a flow-control token
    transportCount.getAndDecrement();
    Frame last = queue.peekLast();
    if (last != null && last.stream == null) {
      // Can re-use the tail entry
      last.stream = stream;
    } else {
      queue.add(new Frame(stream, 0));
    }

    // Done with this frame, begin processing the next header.
    state = State.HEADER;
    requiredLength = HEADER_LENGTH;
  }

  private InputStream getUncompressedBody(CompositeReadableBuffer body) {
    return ReadableBuffers.openStream(body, true);
  }

  private InputStream getCompressedBody(CompositeReadableBuffer body) {
    if (compression == Compression.NONE) {
      throw Status.INTERNAL.withDescription(
          "Can't decode compressed frame as compression not configured.").asRuntimeException();
    }

    if (compression != Compression.GZIP) {
      throw new AssertionError("Unknown compression type");
    }

    try {
      return new GZIPInputStream(ReadableBuffers.openStream(body, true));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static class Frame {
    private InputStream stream;
    private long consumedBytes;

    /**
     * Construct a frame that is designed to simply return bytes to flow control and contains
     * no payload.
     */
    private Frame(long consumedBytes) {
      this.consumedBytes = consumedBytes;
    }

    /**
     * Construct a frame that delivers a message to the application layer and optionally asks
     * the application layer to return consumed bytes.
     */
    private Frame(InputStream stream, int consumedBytes) {
      this.stream = stream;
      this.consumedBytes = consumedBytes;
    }
  }
}
