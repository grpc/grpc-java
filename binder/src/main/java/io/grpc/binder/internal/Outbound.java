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
import static io.grpc.internal.GrpcUtil.TIMEOUT_KEY;
import static java.lang.Math.max;

import android.os.Parcel;
import io.grpc.Deadline;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.internal.StatsTraceContext;
import java.io.IOException;
import java.io.InputStream;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Sends the set of outbound transactions for a single BinderStream (rpc).
 *
 * <p>Handles buffering internally for flow control, and splitting large messages into multiple
 * transactions where necessary.
 *
 * <p>Also handles reporting to the {@link StatsTraceContext}.
 *
 * <p>A note on threading: All calls into this class are expected to hold this object as a lock.
 * However, since calls from gRPC are serialized already, the only reason we need to care about
 * threading is the onTransportReady() call (when flow-control unblocks us).
 *
 * <p>To reduce the cost of locking, BinderStream endeavors to make only a single call to this class
 * for single-message calls (the most common).
 *
 * <p><b>IMPORTANT:</b> To avoid potential deadlocks, this class may only call unsynchronized
 * methods of the BinderTransport class.
 */
abstract class Outbound {

  private final BinderTransport transport;
  private final int callId;
  private final StatsTraceContext statsTraceContext;

  enum State {
    INITIAL,
    PREFIX_SENT,
    ALL_MESSAGES_SENT,
    SUFFIX_SENT,
    CLOSED,
  }

  /*
   * Represents the state of data we've sent in binder transactions.
   */
  @GuardedBy("this")
  private State outboundState = State.INITIAL; // Represents what we've delivered.

  // ----------------------------------
  // For reporting to StatsTraceContext.
  /** Indicates we're ready to send the prefix. */
  private boolean prefixReady;

  @Nullable private InputStream firstMessage;

  @Nullable private Queue<InputStream> messageQueue;

  /**
   * Indicates we have everything ready to send the suffix. This implies we have all outgoing
   * messages, and any additional data which needs to be send after the last message. (e.g.
   * trailers).
   */
  private boolean suffixReady;

  /**
   * The index of the next transaction we'll send, allowing the receiver to re-assemble out-of-order
   * messages.
   */
  @GuardedBy("this")
  private int transactionIndex;

  // ----------------------------------
  // For reporting to StatsTraceContext.
  private int numDeliveredMessages;
  private int messageSize;

  private Outbound(BinderTransport transport, int callId, StatsTraceContext statsTraceContext) {
    this.transport = transport;
    this.callId = callId;
    this.statsTraceContext = statsTraceContext;
  }

  final StatsTraceContext getStatsTraceContext() {
    return statsTraceContext;
  }

  /** Call to add a message to be delivered. Implies onPrefixReady(). */
  @GuardedBy("this")
  final void addMessage(InputStream message) throws StatusException {
    onPrefixReady(); // This is implied.
    if (messageQueue != null) {
      messageQueue.add(message);
    } else if (firstMessage == null) {
      firstMessage = message;
    } else {
      messageQueue = new ConcurrentLinkedQueue<>();
      messageQueue.add(message);
    }
  }

  @GuardedBy("this")
  protected final void onPrefixReady() {
    this.prefixReady = true;
  }

  @GuardedBy("this")
  protected final void onSuffixReady() {
    this.suffixReady = true;
  }

  // =====================
  // Updates to delivery.
  @GuardedBy("this")
  private void onOutboundState(State outboundState) {
    checkTransition(this.outboundState, outboundState);
    this.outboundState = outboundState;
  }

  // ===================
  // Internals.
  @GuardedBy("this")
  protected final boolean messageAvailable() {
    if (messageQueue != null) {
      return !messageQueue.isEmpty();
    } else if (firstMessage != null) {
      return numDeliveredMessages == 0;
    } else {
      return false;
    }
  }

  @Nullable
  @GuardedBy("this")
  private final InputStream peekNextMessage() {
    if (numDeliveredMessages == 0) {
      return firstMessage;
    } else if (messageQueue != null) {
      return messageQueue.peek();
    }
    return null;
  }

  @GuardedBy("this")
  private final boolean canSend() {
    switch (outboundState) {
      case INITIAL:
        if (!prefixReady) {
          return false;
        }
        break;
      case PREFIX_SENT:
        // We can only send something if we have messages or the suffix.
        // Note that if we have the suffix but no messages in this state, it means we've been closed
        // early.
        if (!messageAvailable() && !suffixReady) {
          return false;
        }
        break;
      case ALL_MESSAGES_SENT:
        if (!suffixReady) {
          return false;
        }
        break;
      default:
        return false;
    }
    return isReady();
  }

  final boolean isReady() {
    return transport.isReady();
  }

  @GuardedBy("this")
  final void onTransportReady() throws StatusException {
    // The transport has become ready, attempt sending.
    send();
  }

  @GuardedBy("this")
  final void send() throws StatusException {
    while (canSend()) {
      try {
        sendInternal();
      } catch (StatusException se) {
        // Ensure we don't send anything else and rethrow.
        onOutboundState(State.CLOSED);
        throw se;
      }
    }
  }

  @GuardedBy("this")
  @SuppressWarnings("fallthrough")
  protected final void sendInternal() throws StatusException {
    try (ParcelHolder parcel = ParcelHolder.obtain()) {
      int flags = 0;
      parcel.get().writeInt(0); // Placeholder for flags. Will be filled in below.
      parcel.get().writeInt(transactionIndex++);
      switch (outboundState) {
        case INITIAL:
          flags |= TransactionUtils.FLAG_PREFIX;
          flags |= writePrefix(parcel.get());
          onOutboundState(State.PREFIX_SENT);
          if (!messageAvailable() && !suffixReady) {
            break;
          }
          // Fall-through.
        case PREFIX_SENT:
          InputStream messageStream = peekNextMessage();
          if (messageStream != null) {
            flags |= TransactionUtils.FLAG_MESSAGE_DATA;
            flags |= writeMessageData(parcel.get(), messageStream);
          } else {
            checkState(suffixReady);
          }
          if (suffixReady && !messageAvailable()) {
            onOutboundState(State.ALL_MESSAGES_SENT);
          } else {
            // There's still more message data to deliver, break out.
            break;
          }
          // Fall-through.
        case ALL_MESSAGES_SENT:
          flags |= TransactionUtils.FLAG_SUFFIX;
          flags |= writeSuffix(parcel.get());
          onOutboundState(State.SUFFIX_SENT);
          break;
        default:
          throw new AssertionError();
      }
      TransactionUtils.fillInFlags(parcel.get(), flags);
      int dataSize = parcel.get().dataSize();
      transport.sendTransaction(callId, parcel);
      statsTraceContext.outboundWireSize(dataSize);
      statsTraceContext.outboundUncompressedSize(dataSize);
    } catch (IOException e) {
      throw Status.INTERNAL.withCause(e).asException();
    }
  }

  protected final void unregister() {
    transport.unregisterCall(callId);
  }

  @Override
  public synchronized String toString() {
    return getClass().getSimpleName()
        + "[S="
        + outboundState
        + "/NDM="
        + numDeliveredMessages
        + "]";
  }

  /**
   * Write prefix data to the given {@link Parcel}.
   *
   * @param parcel the transaction parcel to write to.
   * @return any additional flags to be set on the transaction.
   */
  @GuardedBy("this")
  protected abstract int writePrefix(Parcel parcel) throws IOException, StatusException;

  /**
   * Write suffix data to the given {@link Parcel}.
   *
   * @param parcel the transaction parcel to write to.
   * @return any additional flags to be set on the transaction.
   */
  @GuardedBy("this")
  protected abstract int writeSuffix(Parcel parcel) throws IOException, StatusException;

  @GuardedBy("this")
  private final int writeMessageData(Parcel parcel, InputStream stream) throws IOException {
    int flags = 0;
    boolean dataRemaining = false;
    if (stream instanceof ParcelableInputStream) {
      flags |= TransactionUtils.FLAG_MESSAGE_DATA_IS_PARCELABLE;
      messageSize = ((ParcelableInputStream) stream).writeToParcel(parcel);
    } else {
      byte[] block = BlockPool.acquireBlock();
      try {
        int size = stream.read(block);
        if (size <= 0) {
          parcel.writeInt(0);
        } else {
          parcel.writeInt(size);
          parcel.writeByteArray(block, 0, size);
          messageSize += size;
          if (size == block.length) {
            flags |= TransactionUtils.FLAG_MESSAGE_DATA_IS_PARTIAL;
            dataRemaining = true;
          }
        }
      } finally {
        BlockPool.releaseBlock(block);
      }
    }
    if (!dataRemaining) {
      stream.close();
      int index = numDeliveredMessages++;
      if (index > 0) {
        checkNotNull(messageQueue).poll();
      }
      statsTraceContext.outboundMessage(index);
      statsTraceContext.outboundMessageSent(index, messageSize, messageSize);
      messageSize = 0;
    }
    return flags;
  }

  // ======================================
  // Client-side outbound transactions.
  static final class ClientOutbound extends Outbound {

    private final MethodDescriptor<?, ?> method;
    private final Metadata headers;
    private final StatsTraceContext statsTraceContext;

    ClientOutbound(
        BinderTransport transport,
        int callId,
        MethodDescriptor<?, ?> method,
        Metadata headers,
        StatsTraceContext statsTraceContext) {
      super(transport, callId, statsTraceContext);
      this.method = method;
      this.headers = headers;
      this.statsTraceContext = statsTraceContext;
    }

    @Override
    @GuardedBy("this")
    protected int writePrefix(Parcel parcel) throws IOException, StatusException {
      parcel.writeString(method.getFullMethodName());
      MetadataHelper.writeMetadata(parcel, headers);
      statsTraceContext.clientOutboundHeaders();
      if (method.getType().serverSendsOneMessage()) {
        return TransactionUtils.FLAG_EXPECT_SINGLE_MESSAGE;
      }
      return 0;
    }

    // Implies onPrefixReady() and onSuffixReady().
    @GuardedBy("this")
    void sendSingleMessageAndHalfClose(@Nullable InputStream singleMessage) throws StatusException {
      if (singleMessage != null) {
        addMessage(singleMessage);
      }
      onSuffixReady();
      send();
    }

    @GuardedBy("this")
    void sendHalfClose() throws StatusException {
      onSuffixReady();
      send();
    }

    @Override
    @GuardedBy("this")
    protected int writeSuffix(Parcel parcel) throws IOException {
      // Client doesn't include anything in the suffix.
      return 0;
    }

    // Must not be called after onPrefixReady() (explicitly or via another method that implies it).
    @GuardedBy("this")
    void setDeadline(Deadline deadline) {
      headers.discardAll(TIMEOUT_KEY);
      long effectiveTimeoutNanos = max(0, deadline.timeRemaining(TimeUnit.NANOSECONDS));
      headers.put(TIMEOUT_KEY, effectiveTimeoutNanos);
    }
  }

  // ======================================
  // Server-side outbound transactions.
  static final class ServerOutbound extends Outbound {
    @GuardedBy("this")
    @Nullable
    private Metadata headers;

    @GuardedBy("this")
    @Nullable
    private Status closeStatus;

    @GuardedBy("this")
    @Nullable
    private Metadata trailers;

    ServerOutbound(BinderTransport transport, int callId, StatsTraceContext statsTraceContext) {
      super(transport, callId, statsTraceContext);
    }

    @GuardedBy("this")
    void sendHeaders(Metadata headers) throws StatusException {
      this.headers = headers;
      onPrefixReady();
      send();
    }

    @Override
    @GuardedBy("this")
    protected int writePrefix(Parcel parcel) throws IOException, StatusException {
      MetadataHelper.writeMetadata(parcel, headers);
      return 0;
    }

    @GuardedBy("this")
    void sendSingleMessageAndClose(
        @Nullable Metadata pendingHeaders,
        @Nullable InputStream pendingSingleMessage,
        Status closeStatus,
        Metadata trailers)
        throws StatusException {
      if (this.closeStatus != null) {
        return;
      }
      if (pendingHeaders != null) {
        this.headers = pendingHeaders;
      }
      onPrefixReady();
      if (pendingSingleMessage != null) {
        addMessage(pendingSingleMessage);
      }
      checkState(this.trailers == null);
      this.closeStatus = closeStatus;
      this.trailers = trailers;
      onSuffixReady();
      send();
    }

    @GuardedBy("this")
    void sendClose(Status closeStatus, Metadata trailers) throws StatusException {
      if (this.closeStatus != null) {
        return;
      }
      checkState(this.trailers == null);
      this.closeStatus = closeStatus;
      this.trailers = trailers;
      onPrefixReady();
      onSuffixReady();
      send();
    }

    @Override
    @GuardedBy("this")
    protected int writeSuffix(Parcel parcel) throws IOException, StatusException {
      int flags = TransactionUtils.writeStatus(parcel, closeStatus);
      MetadataHelper.writeMetadata(parcel, trailers);
      // TODO: This is an ugly place for this side-effect.
      unregister();
      return flags;
    }
  }

  // ======================================
  // Helper methods.
  private static void checkTransition(State current, State next) {
    switch (next) {
      case PREFIX_SENT:
        checkState(current == State.INITIAL);
        break;
      case ALL_MESSAGES_SENT:
        checkState(current == State.PREFIX_SENT);
        break;
      case SUFFIX_SENT:
        checkState(current == State.ALL_MESSAGES_SENT);
        break;
      case CLOSED: // hah.
        break;
      default:
        throw new AssertionError();
    }
  }
}
