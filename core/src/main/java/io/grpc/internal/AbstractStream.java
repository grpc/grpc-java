/*
 * Copyright 2014 The gRPC Authors
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
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Codec;
import io.grpc.Compressor;
import io.grpc.Decompressor;
import io.perfmark.Link;
import io.perfmark.PerfMark;
import io.perfmark.TaskCloseable;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.concurrent.GuardedBy;

/**
 * The stream and stream state as used by the application. Must only be called from the sending
 * application thread.
 */
public abstract class AbstractStream implements Stream {
  private static final Logger log = Logger.getLogger(AbstractStream.class.getName());

  /** The framer to use for sending messages. */
  protected abstract Framer framer();

  /**
   * Obtain the transport state corresponding to this stream. Each stream must have its own unique
   * transport state.
   */
  protected abstract TransportState transportState();

  @Override
  public void optimizeForDirectExecutor() {
    transportState().optimizeForDirectExecutor();
  }

  @Override
  public final void setMessageCompression(boolean enable) {
    framer().setMessageCompression(enable);
  }

  @Override
  public final void request(int numMessages) {
    transportState().requestMessagesFromDeframer(numMessages);
  }

  @Override
  public final void writeMessage(InputStream message) {
    checkNotNull(message, "message");
    try {
      if (!framer().isClosed()) {
        framer().writePayload(message);
      }
    } finally {
      GrpcUtil.closeQuietly(message);
    }
  }

  @Override
  public final void flush() {
    if (!framer().isClosed()) {
      framer().flush();
    }
  }

  /**
   * A hint to the stream that specifies how many bytes must be queued before
   * {@link #isReady()} will return false. A stream may ignore this property if
   * unsupported. This may only be set during stream initialization before
   * any messages are set.
   *
   * @param numBytes The number of bytes that must be queued. Must be a
   *                 positive integer.
   */
  protected void setOnReadyThreshold(int numBytes) {
    transportState().setOnReadyThreshold(numBytes);
  }

  /**
   * Closes the underlying framer. Should be called when the outgoing stream is gracefully closed
   * (half closure on client; closure on server).
   */
  protected final void endOfMessages() {
    framer().close();
  }

  @Override
  public final void setCompressor(Compressor compressor) {
    framer().setCompressor(checkNotNull(compressor, "compressor"));
  }

  @Override
  public boolean isReady() {
    return transportState().isReady();
  }

  /**
   * Event handler to be called by the subclass when a number of bytes are being queued for sending
   * to the remote endpoint.
   *
   * @param numBytes the number of bytes being sent.
   */
  protected final void onSendingBytes(int numBytes) {
    transportState().onSendingBytes(numBytes);
  }

  /**
   * Stream state as used by the transport. This should only be called from the transport thread
   * (except for private interactions with {@code AbstractStream}).
   */
  public abstract static class TransportState
      implements ApplicationThreadDeframer.TransportExecutor, MessageDeframer.Listener {
    /**
     * The default number of queued bytes for a given stream, below which
     * {@link StreamListener#onReady()} will be called.
     */
    @VisibleForTesting
    public static final int DEFAULT_ONREADY_THRESHOLD = 32 * 1024;

    private Deframer deframer;
    private final Object onReadyLock = new Object();
    private final StatsTraceContext statsTraceCtx;
    private final TransportTracer transportTracer;
    private final MessageDeframer rawDeframer;

    /**
     * The number of bytes currently queued, waiting to be sent. When this falls below
     * DEFAULT_ONREADY_THRESHOLD, {@link StreamListener#onReady()} will be called.
     */
    @GuardedBy("onReadyLock")
    private int numSentBytesQueued;
    /**
     * Indicates the stream has been created on the connection. This implies that the stream is no
     * longer limited by MAX_CONCURRENT_STREAMS.
     */
    @GuardedBy("onReadyLock")
    private boolean allocated;
    /**
     * Indicates that the stream no longer exists for the transport. Implies that the application
     * should be discouraged from sending, because doing so would have no effect.
     */
    @GuardedBy("onReadyLock")
    private boolean deallocated;

    @GuardedBy("onReadyLock")
    private int onReadyThreshold;

    protected TransportState(
        int maxMessageSize,
        StatsTraceContext statsTraceCtx,
        TransportTracer transportTracer) {
      this.statsTraceCtx = checkNotNull(statsTraceCtx, "statsTraceCtx");
      this.transportTracer = checkNotNull(transportTracer, "transportTracer");
      rawDeframer = new MessageDeframer(
          this,
          Codec.Identity.NONE,
          maxMessageSize,
          statsTraceCtx,
          transportTracer);
      // TODO(#7168): use MigratingThreadDeframer when enabling retry doesn't break.
      deframer = rawDeframer;
      onReadyThreshold = DEFAULT_ONREADY_THRESHOLD;
    }

    final void optimizeForDirectExecutor() {
      rawDeframer.setListener(this);
      deframer = rawDeframer;
    }

    protected void setFullStreamDecompressor(GzipInflatingBuffer fullStreamDecompressor) {
      rawDeframer.setFullStreamDecompressor(fullStreamDecompressor);
      deframer = new ApplicationThreadDeframer(this, this, rawDeframer);
    }

    final void setMaxInboundMessageSize(int maxSize) {
      deframer.setMaxInboundMessageSize(maxSize);
    }

    /**
     * Override this method to provide a stream listener.
     */
    protected abstract StreamListener listener();

    /**
     * A hint to the stream that specifies how many bytes must be queued before
     * {@link #isReady()} will return false. A stream may ignore this property if
     * unsupported. This may only be set before any messages are sent.
     *
     * @param numBytes The number of bytes that must be queued. Must be a
     *                 positive integer.
     */
    void setOnReadyThreshold(int numBytes) {
      synchronized (onReadyLock) {
        this.onReadyThreshold = numBytes;
      }
    }

    @Override
    public void messagesAvailable(StreamListener.MessageProducer producer) {
      listener().messagesAvailable(producer);
    }

    /**
     * Closes the deframer and frees any resources. After this method is called, additional calls
     * will have no effect.
     *
     * <p>When {@code stopDelivery} is false, the deframer will wait to close until any already
     * queued messages have been delivered.
     *
     * <p>The deframer will invoke {@link #deframerClosed(boolean)} upon closing.
     *
     * @param stopDelivery interrupt pending deliveries and close immediately
     */
    protected final void closeDeframer(boolean stopDelivery) {
      if (stopDelivery) {
        deframer.close();
      } else {
        deframer.closeWhenComplete();
      }
    }

    /**
     * Called to parse a received frame and attempt delivery of any completed messages. Must be
     * called from the transport thread.
     */
    protected final void deframe(final ReadableBuffer frame) {
      try {
        deframer.deframe(frame);
      } catch (Throwable t) {
        deframeFailed(t);
      }
    }

    /**
     * Called to request the given number of messages from the deframer. May be called from any
     * thread.
     */
    private void requestMessagesFromDeframer(final int numMessages) {
      if (deframer instanceof ThreadOptimizedDeframer) {
        try (TaskCloseable ignore = PerfMark.traceTask("AbstractStream.request")) {
          deframer.request(numMessages);
        }
        return;
      }
      final Link link = PerfMark.linkOut();
      class RequestRunnable implements Runnable {
        @Override public void run() {
          try (TaskCloseable ignore = PerfMark.traceTask("AbstractStream.request")) {
            PerfMark.linkIn(link);
            deframer.request(numMessages);
          } catch (Throwable t) {
            deframeFailed(t);
          }
        }
      }

      runOnTransportThread(new RequestRunnable());
    }

    /**
     * Very rarely used. Prefer stream.request() instead of this; this method is only necessary if
     * a stream is not available.
     */
    @VisibleForTesting
    public final void requestMessagesFromDeframerForTesting(int numMessages) {
      requestMessagesFromDeframer(numMessages);
    }

    public final StatsTraceContext getStatsTraceContext() {
      return statsTraceCtx;
    }

    protected final void setDecompressor(Decompressor decompressor) {
      deframer.setDecompressor(decompressor);
    }

    private boolean isReady() {
      synchronized (onReadyLock) {
        return allocated && numSentBytesQueued < onReadyThreshold && !deallocated;
      }
    }

    /**
     * Event handler to be called by the subclass when the stream's headers have passed any
     * connection flow control (i.e., MAX_CONCURRENT_STREAMS). It may call the listener's {@link
     * StreamListener#onReady()} handler if appropriate. This must be called from the transport
     * thread, since the listener may be called back directly.
     */
    protected void onStreamAllocated() {
      checkState(listener() != null);
      synchronized (onReadyLock) {
        checkState(!allocated, "Already allocated");
        allocated = true;
      }
      notifyIfReady();
    }

    /**
     * Notify that the stream does not exist in a usable state any longer. This causes {@link
     * AbstractStream#isReady()} to return {@code false} from this point forward.
     *
     * <p>This does not generally need to be called explicitly by the transport, as it is handled
     * implicitly by {@link AbstractClientStream} and {@link AbstractServerStream}.
     */
    protected final void onStreamDeallocated() {
      synchronized (onReadyLock) {
        deallocated = true;
      }
    }

    protected boolean isStreamDeallocated() {
      synchronized (onReadyLock) {
        return deallocated;
      }
    }

    /**
     * Event handler to be called by the subclass when a number of bytes are being queued for
     * sending to the remote endpoint.
     *
     * @param numBytes the number of bytes being sent.
     */
    private void onSendingBytes(int numBytes) {
      synchronized (onReadyLock) {
        numSentBytesQueued += numBytes;
      }
    }

    /**
     * Event handler to be called by the subclass when a number of bytes has been sent to the remote
     * endpoint. May call back the listener's {@link StreamListener#onReady()} handler if
     * appropriate.  This must be called from the transport thread, since the listener may be called
     * back directly.
     *
     * @param numBytes the number of bytes that were sent.
     */
    public final void onSentBytes(int numBytes) {
      boolean doNotify;
      synchronized (onReadyLock) {
        checkState(allocated,
            "onStreamAllocated was not called, but it seems the stream is active");
        boolean belowThresholdBefore = numSentBytesQueued < onReadyThreshold;
        numSentBytesQueued -= numBytes;
        boolean belowThresholdAfter = numSentBytesQueued < onReadyThreshold;
        doNotify = !belowThresholdBefore && belowThresholdAfter;
      }
      if (doNotify) {
        notifyIfReady();
      }
    }

    protected TransportTracer getTransportTracer() {
      return transportTracer;
    }

    private void notifyIfReady() {
      boolean doNotify;
      synchronized (onReadyLock) {
        doNotify = isReady();
        if (!doNotify && log.isLoggable(Level.FINEST)) {
          log.log(Level.FINEST,
              "Stream not ready so skip notifying listener.\n"
                  + "details: allocated/deallocated:{0}/{3}, sent queued: {1}, ready thresh: {2}",
              new Object[] {allocated, numSentBytesQueued, onReadyThreshold, deallocated});
        }
      }
      if (doNotify) {
        listener().onReady();
      }
    }
  }
}
