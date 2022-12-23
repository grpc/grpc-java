/*
 * Copyright 2020 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.os.Parcel;
import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.binder.InboundParcelablePolicy;
import io.grpc.internal.ClientStreamListener;
import io.grpc.internal.ClientStreamListener.RpcProgress;
import io.grpc.internal.ServerStream;
import io.grpc.internal.ServerStreamListener;
import io.grpc.internal.StatsTraceContext;
import io.grpc.internal.StreamListener;
import java.io.InputStream;
import java.util.ArrayList;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Handles incoming binder transactions for a single stream, turning those transactions into calls
 * to the stream listener.
 *
 * <p>Out-of-order messages are reassembled into their correct order.
 */
abstract class Inbound<L extends StreamListener> implements StreamListener.MessageProducer {

  protected final BinderTransport transport;
  protected final Attributes attributes;
  final int callId;

  // ==========================
  // Values set when we're initialized.

  @Nullable
  @GuardedBy("this")
  protected Outbound outbound;

  @Nullable
  @GuardedBy("this")
  protected StatsTraceContext statsTraceContext;

  @Nullable
  @GuardedBy("this")
  protected L listener;

  // ==========================
  // State of inbound data.

  @Nullable
  @GuardedBy("this")
  private InputStream firstMessage;

  @GuardedBy("this")
  private int firstQueuedTransactionIndex;

  @GuardedBy("this")
  private int nextCompleteMessageEnd;

  @Nullable
  @GuardedBy("this")
  private ArrayList<TransactionData> queuedTransactionData;

  @GuardedBy("this")
  private boolean suffixAvailable;

  @GuardedBy("this")
  private int suffixTransactionIndex;

  @GuardedBy("this")
  private int inboundDataSize;

  // ==========================
  // State of what we've delivered to gRPC.

  /**
   * Each rpc transmits (or receives) a prefix (including headers and possibly a method name), the
   * data of zero or more request (or response) messages, and a suffix (possibly including a close
   * status and trailers).
   *
   * <p>This enum represents those stages, for both availability (what we've been given), and
   * delivery what we've sent.
   */
  enum State {
    // We aren't yet connected to a BinderStream instance and listener. Due to potentially
    // out-of-order messages, a server-side instance can remain in this state for multiple
    // transactions.
    UNINITIALIZED,

    // We're attached to a BinderStream instance and we have a listener we can report to.
    // On the client-side, this happens as soon as the start() method is called (almost
    // immediately), and on the server side, this happens as soon as we receive the prefix
    // (so we know which method is being called).
    INITIALIZED,

    // We've delivered the prefix data to the listener. On the client side, this means we've
    // delivered the response headers, and on the server side this state is effectively the same
    // as INITIALIZED (since we initialize only by delivering the prefix).
    PREFIX_DELIVERED,

    // All messages have been received, and delivered to the listener.
    ALL_MESSAGES_DELIVERED,

    // We've delivered the suffix.
    SUFFIX_DELIVERED,

    // The stream is closed.
    CLOSED
  }

  /*
   * Represents which data we've delivered to the gRPC listener.
   */
  @GuardedBy("this")
  private State deliveryState = State.UNINITIALIZED;

  @GuardedBy("this")
  private int numReceivedMessages;

  @GuardedBy("this")
  private int numRequestedMessages;

  @GuardedBy("this")
  private boolean delivering;

  @GuardedBy("this")
  private boolean producingMessages;

  private Inbound(BinderTransport transport, Attributes attributes, int callId) {
    this.transport = transport;
    this.attributes = attributes;
    this.callId = callId;
  }

  @GuardedBy("this")
  final void init(Outbound outbound, L listener) {
    this.outbound = outbound;
    this.statsTraceContext = outbound.getStatsTraceContext();
    this.listener = listener;
    if (!isClosed()) {
      onDeliveryState(State.INITIALIZED);
    }
  }

  final void unregister() {
    transport.unregisterInbound(this);
  }

  boolean countsForInUse() {
    return false;
  }

  // =====================
  // Updates to delivery.

  @GuardedBy("this")
  protected final void onDeliveryState(State deliveryState) {
    checkTransition(this.deliveryState, deliveryState);
    this.deliveryState = deliveryState;
  }

  @GuardedBy("this")
  protected final boolean isClosed() {
    return deliveryState == State.CLOSED;
  }

  @GuardedBy("this")
  private final boolean messageAvailable() {
    return firstMessage != null || nextCompleteMessageEnd > 0;
  }

  @GuardedBy("this")
  private boolean receivedAllTransactions() {
    return suffixAvailable && firstQueuedTransactionIndex >= suffixTransactionIndex;
  }

  // ===================
  // Internals.

  @GuardedBy("this")
  final void deliver() {
    if (delivering) {
      // Don't re-enter.
      return;
    }
    delivering = true;
    while (canDeliver()) {
      deliverInternal();
    }
    delivering = false;
  }

  @GuardedBy("this")
  private final boolean canDeliver() {
    switch (deliveryState) {
      case PREFIX_DELIVERED:
        if (listener != null) {
          if (producingMessages) {
            // We're waiting for the listener to consume messages. Nothing to do.
            return false;
          } else if (messageAvailable()) {
            // There's a message. We can deliver if we've been asked for messages, and we haven't
            // already given the listener a MessageProducer.
            return numRequestedMessages != 0;
          } else {
            // There are no messages available. Return true if that's the last of them, because we
            // can send the suffix.
            return receivedAllTransactions();
          }
        }
        return false;
      case ALL_MESSAGES_DELIVERED:
        return listener != null && suffixAvailable;
      default:
        return false;
    }
  }

  @GuardedBy("this")
  @SuppressWarnings("fallthrough")
  private final void deliverInternal() {
    switch (deliveryState) {
      case PREFIX_DELIVERED:
        if (producingMessages) {
          break;
        } else if (messageAvailable()) {
          producingMessages = true;
          listener.messagesAvailable(this);
          break;
        } else if (!suffixAvailable) {
          break;
        }
        onDeliveryState(State.ALL_MESSAGES_DELIVERED);
        // Fall-through.
      case ALL_MESSAGES_DELIVERED:
        if (suffixAvailable) {
          onDeliveryState(State.SUFFIX_DELIVERED);
          deliverSuffix();
        }
        break;
      default:
        throw new AssertionError();
    }
  }

  /** Deliver the suffix to gRPC. */
  protected abstract void deliverSuffix();

  @GuardedBy("this")
  final void closeOnCancel(Status status) {
    closeAbnormal(Status.CANCELLED, status, false);
  }

  @GuardedBy("this")
  private final void closeOutOfBand(Status status) {
    closeAbnormal(status, status, true);
  }

  @GuardedBy("this")
  final void closeAbnormal(Status status) {
    closeAbnormal(status, status, false);
  }

  @GuardedBy("this")
  private final void closeAbnormal(
      Status outboundStatus, Status internalStatus, boolean isOobFromRemote) {
    if (!isClosed()) {
      boolean wasInitialized = (deliveryState != State.UNINITIALIZED);
      onDeliveryState(State.CLOSED);
      if (wasInitialized) {
        statsTraceContext.streamClosed(internalStatus);
      }
      if (!isOobFromRemote) {
        transport.sendOutOfBandClose(callId, outboundStatus);
      }
      if (wasInitialized) {
        deliverCloseAbnormal(internalStatus);
      }
      unregister();
    }
  }

  @GuardedBy("this")
  protected abstract void deliverCloseAbnormal(Status status);

  final void onTransportReady() {
    // Report transport readiness to the listener, and the outbound data.
    Outbound outbound = null;
    StreamListener listener = null;
    synchronized (this) {
      outbound = this.outbound;
      listener = this.listener;
    }
    if (listener != null) {
      listener.onReady();
    }
    if (outbound != null) {
      try {
        synchronized (outbound) {
          outbound.onTransportReady();
        }
      } catch (StatusException se) {
        synchronized (this) {
          closeAbnormal(se.getStatus());
        }
      }
    }
  }

  @GuardedBy("this")
  public void requestMessages(int num) {
    numRequestedMessages += num;
    deliver();
  }

  final synchronized void handleTransaction(Parcel parcel) {
    if (isClosed()) {
      return;
    }
    try {
      int flags = parcel.readInt();
      if (TransactionUtils.hasFlag(flags, TransactionUtils.FLAG_OUT_OF_BAND_CLOSE)) {
        closeOutOfBand(TransactionUtils.readStatus(flags, parcel));
        return;
      }
      int index = parcel.readInt();
      boolean hasPrefix = TransactionUtils.hasFlag(flags, TransactionUtils.FLAG_PREFIX);
      boolean hasMessageData =
          TransactionUtils.hasFlag(flags, TransactionUtils.FLAG_MESSAGE_DATA);
      boolean hasSuffix = TransactionUtils.hasFlag(flags, TransactionUtils.FLAG_SUFFIX);
      if (hasPrefix) {
        handlePrefix(flags, parcel);
        onDeliveryState(State.PREFIX_DELIVERED);
      }
      if (hasMessageData) {
        handleMessageData(flags, index, parcel);
      }
      if (hasSuffix) {
        handleSuffix(flags, parcel);
        suffixTransactionIndex = index;
        suffixAvailable = true;
      }
      if (index == firstQueuedTransactionIndex) {
        if (queuedTransactionData == null) {
          // This message was in order, and we haven't needed to queue anything yet.
          firstQueuedTransactionIndex += 1;
        } else if (!hasMessageData && !hasSuffix) {
          // The first transaction arrived, but it contained no message data.
          queuedTransactionData.remove(0);
          firstQueuedTransactionIndex += 1;
        }
      }
      reportInboundSize(parcel.dataSize());
      deliver();
    } catch (StatusException se) {
      closeAbnormal(se.getStatus());
    }
  }

  @GuardedBy("this")
  abstract void handlePrefix(int flags, Parcel parcel) throws StatusException;

  @GuardedBy("this")
  abstract void handleSuffix(int flags, Parcel parcel) throws StatusException;

  @GuardedBy("this")
  private void handleMessageData(int flags, int index, Parcel parcel) throws StatusException {
    InputStream stream = null;
    byte[] block = null;
    boolean lastBlockOfMessage = true;
    int numBytes = 0;
    if ((flags & TransactionUtils.FLAG_MESSAGE_DATA_IS_PARCELABLE) != 0) {
      InboundParcelablePolicy policy = attributes.get(BinderTransport.INBOUND_PARCELABLE_POLICY);
      if (policy == null || !policy.shouldAcceptParcelableMessages()) {
        throw Status.PERMISSION_DENIED
            .withDescription("Parcelable messages not allowed")
            .asException();
      }
      int startPos = parcel.dataPosition();
      stream = ParcelableInputStream.readFromParcel(parcel, getClass().getClassLoader());
      numBytes = parcel.dataPosition() - startPos;
    } else {
      numBytes = parcel.readInt();
      block = BlockPool.acquireBlock(numBytes);
      if (numBytes > 0) {
        parcel.readByteArray(block);
      }
      if ((flags & TransactionUtils.FLAG_MESSAGE_DATA_IS_PARTIAL) != 0) {
        // Partial message. Ensure we have a message assembler.
        lastBlockOfMessage = false;
      }
    }
    if (queuedTransactionData == null) {
      if (numReceivedMessages == 0 && lastBlockOfMessage && index == firstQueuedTransactionIndex) {
        // Shortcut for when we receive a single message in one transaction.
        checkState(firstMessage == null);
        firstMessage = (stream != null) ? stream : new BlockInputStream(block);
        reportInboundMessage(numBytes);
        return;
      }
      queuedTransactionData = new ArrayList<>(16);
    }
    enqueueTransactionData(index, new TransactionData(stream, block, numBytes, lastBlockOfMessage));
  }

  @GuardedBy("this")
  private void enqueueTransactionData(int index, TransactionData data) {
    int offset = index - firstQueuedTransactionIndex;
    if (offset < queuedTransactionData.size()) {
      queuedTransactionData.set(offset, data);
      lookForCompleteMessage();
    } else if (offset > queuedTransactionData.size()) {
      do {
        queuedTransactionData.add(null);
      } while (offset > queuedTransactionData.size());
      queuedTransactionData.add(data);
    } else {
      queuedTransactionData.add(data);
      lookForCompleteMessage();
    }
  }

  @GuardedBy("this")
  private void lookForCompleteMessage() {
    int numBytes = 0;
    if (nextCompleteMessageEnd == 0) {
      for (int i = 0; i < queuedTransactionData.size(); i++) {
        TransactionData data = queuedTransactionData.get(i);
        if (data == null) {
          // Missing block.
          return;
        } else {
          numBytes += data.numBytes;
          if (data.lastBlockOfMessage) {
            // Found a complete message.
            nextCompleteMessageEnd = i + 1;
            reportInboundMessage(numBytes);
            return;
          }
        }
      }
    }
  }

  @Override
  @Nullable
  public final synchronized InputStream next() {
    InputStream stream = null;
    if (firstMessage != null) {
      stream = firstMessage;
      firstMessage = null;
    } else if (numRequestedMessages > 0 && messageAvailable()) {
      stream = assembleNextMessage();
    }
    if (stream != null) {
      numRequestedMessages -= 1;
    } else {
      producingMessages = false;
      if (receivedAllTransactions()) {
        // That's the last of the messages delivered.
        if (!isClosed()) {
          onDeliveryState(State.ALL_MESSAGES_DELIVERED);
          deliver();
        }
      }
    }
    return stream;
  }

  @GuardedBy("this")
  private InputStream assembleNextMessage() {
    InputStream message;
    int numBlocks = nextCompleteMessageEnd;
    nextCompleteMessageEnd = 0;
    int numBytes = 0;
    if (numBlocks == 1) {
      // Single block.
      TransactionData data = queuedTransactionData.remove(0);
      numBytes = data.numBytes;
      if (data.stream != null) {
        message = data.stream;
      } else {
        message = new BlockInputStream(data.block);
      }
    } else {
      byte[][] blocks = new byte[numBlocks][];
      for (int i = 0; i < numBlocks; i++) {
        TransactionData data = queuedTransactionData.remove(0);
        blocks[i] = checkNotNull(data.block);
        numBytes += blocks[i].length;
      }
      message = new BlockInputStream(blocks, numBytes);
    }
    firstQueuedTransactionIndex += numBlocks;
    lookForCompleteMessage();
    return message;
  }

  // ------------------------------------
  // stats collection.

  @GuardedBy("this")
  private void reportInboundSize(int size) {
    inboundDataSize += size;
    if (statsTraceContext != null && inboundDataSize != 0) {
      statsTraceContext.inboundWireSize(inboundDataSize);
      statsTraceContext.inboundUncompressedSize(inboundDataSize);
      inboundDataSize = 0;
    }
  }

  @GuardedBy("this")
  private void reportInboundMessage(int numBytes) {
    checkNotNull(statsTraceContext);
    statsTraceContext.inboundMessage(numReceivedMessages);
    statsTraceContext.inboundMessageRead(numReceivedMessages, numBytes, numBytes);
    numReceivedMessages += 1;
  }

  @Override
  public synchronized String toString() {
    return getClass().getSimpleName()
        + "[SfxA="
        + suffixAvailable
        + "/De="
        + deliveryState
        + "/Msg="
        + messageAvailable()
        + "/Lis="
        + (listener != null)
        + "]";
  }

  // ======================================
  // Client-side inbound transactions.
  static final class ClientInbound extends Inbound<ClientStreamListener> {

    private final boolean countsForInUse;

    @Nullable
    @GuardedBy("this")
    private Status closeStatus;

    @Nullable
    @GuardedBy("this")
    private Metadata trailers;

    ClientInbound(
        BinderTransport transport, Attributes attributes, int callId, boolean countsForInUse) {
      super(transport, attributes, callId);
      this.countsForInUse = countsForInUse;
    }

    @Override
    boolean countsForInUse() {
      return countsForInUse;
    }

    @Override
    @GuardedBy("this")
    protected void handlePrefix(int flags, Parcel parcel) throws StatusException {
      Metadata headers = MetadataHelper.readMetadata(parcel, attributes);
      statsTraceContext.clientInboundHeaders();
      listener.headersRead(headers);
    }

    @Override
    @GuardedBy("this")
    protected void handleSuffix(int flags, Parcel parcel) throws StatusException {
      closeStatus = TransactionUtils.readStatus(flags, parcel);
      trailers = MetadataHelper.readMetadata(parcel, attributes);
    }

    @Override
    @GuardedBy("this")
    protected void deliverSuffix() {
      statsTraceContext.clientInboundTrailers(trailers);
      statsTraceContext.streamClosed(closeStatus);
      onDeliveryState(State.CLOSED);
      listener.closed(closeStatus, RpcProgress.PROCESSED, trailers);
      unregister();
    }

    @Override
    @GuardedBy("this")
    protected void deliverCloseAbnormal(Status status) {
      listener.closed(status, RpcProgress.PROCESSED, new Metadata());
    }
  }

  // ======================================
  // Server-side inbound transactions.
  static final class ServerInbound extends Inbound<ServerStreamListener> {

    private final BinderTransport.BinderServerTransport serverTransport;

    ServerInbound(
        BinderTransport.BinderServerTransport transport, Attributes attributes, int callId) {
      super(transport, attributes, callId);
      this.serverTransport = transport;
    }

    @GuardedBy("this")
    @Override
    protected void handlePrefix(int flags, Parcel parcel) throws StatusException {
      String methodName = parcel.readString();
      Metadata headers = MetadataHelper.readMetadata(parcel, attributes);

      StatsTraceContext statsTraceContext =
          serverTransport.createStatsTraceContext(methodName, headers);
      Outbound.ServerOutbound outbound =
          new Outbound.ServerOutbound(serverTransport, callId, statsTraceContext);
      ServerStream stream;
      if ((flags & TransactionUtils.FLAG_EXPECT_SINGLE_MESSAGE) != 0) {
        stream = new SingleMessageServerStream(this, outbound, attributes);
      } else {
        stream = new MultiMessageServerStream(this, outbound, attributes);
      }
      Status status = serverTransport.startStream(stream, methodName, headers);
      if (status.isOk()) {
        checkNotNull(listener); // Is it ok to assume this will happen synchronously?
        if (transport.isReady()) {
          listener.onReady();
        }
      } else {
        closeAbnormal(status);
      }
    }

    @GuardedBy("this")
    @Override
    protected void handleSuffix(int flags, Parcel parcel) {
      // Nothing to read.
    }

    @Override
    @GuardedBy("this")
    protected void deliverSuffix() {
      listener.halfClosed();
    }

    @Override
    @GuardedBy("this")
    protected void deliverCloseAbnormal(Status status) {
      listener.closed(status);
    }

    @GuardedBy("this")
    void onCloseSent(Status status) {
      if (!isClosed()) {
        onDeliveryState(State.CLOSED);
        statsTraceContext.streamClosed(status);
        listener.closed(Status.OK);
      }
    }
  }

  // ======================================
  // Helper methods.

  private static void checkTransition(State current, State next) {
    switch (next) {
      case INITIALIZED:
        checkState(current == State.UNINITIALIZED, "%s -> %s", current, next);
        break;
      case PREFIX_DELIVERED:
        checkState(
            current == State.INITIALIZED || current == State.UNINITIALIZED,
            "%s -> %s",
            current,
            next);
        break;
      case ALL_MESSAGES_DELIVERED:
        checkState(current == State.PREFIX_DELIVERED, "%s -> %s", current, next);
        break;
      case SUFFIX_DELIVERED:
        checkState(current == State.ALL_MESSAGES_DELIVERED, "%s -> %s", current, next);
        break;
      case CLOSED:
        break;
      default:
        throw new AssertionError();
    }
  }

  // ======================================
  // Message reassembly.

  /** Part of an unconsumed message. */
  private static final class TransactionData {
    @Nullable final InputStream stream;
    @Nullable final byte[] block;
    final int numBytes;
    final boolean lastBlockOfMessage;

    TransactionData(InputStream stream, byte[] block, int numBytes, boolean lastBlockOfMessage) {
      this.stream = stream;
      this.block = block;
      this.numBytes = numBytes;
      this.lastBlockOfMessage = lastBlockOfMessage;
    }

    @Override
    public String toString() {
      return "TransactionData["
          + numBytes
          + "b "
          + (stream != null ? "stream" : "array")
          + (lastBlockOfMessage ? "(last)]" : "]");
    }
  }
}
