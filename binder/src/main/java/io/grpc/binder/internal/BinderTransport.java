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
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static io.grpc.binder.internal.TransactionUtils.newCallerFilteringHandler;

import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.TransactionTooLargeException;
import androidx.annotation.BinderThread;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Verify;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Internal;
import io.grpc.InternalChannelz;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalLogId;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.binder.InboundParcelablePolicy;
import io.grpc.binder.internal.LeakSafeOneWayBinder.TransactionHandler;
import io.grpc.internal.ObjectPool;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Base class for binder-based gRPC transport.
 *
 * <p>This is used on both the client and service sides of the transport.
 *
 * <p>A note on synchronization. The nature of this class's interaction with each stream
 * (bi-directional communication between gRPC calls and binder transactions) means that acquiring
 * multiple locks in two different orders can happen easily. E.g. binder transactions will arrive in
 * this class, and need to passed to a stream instance, whereas gRPC calls on a stream instance will
 * need to call into this class to send a transaction (possibly waiting for the transport to become
 * ready).
 *
 * <p>The split between Outbound &amp; Inbound helps reduce this risk, but not entirely remove it.
 *
 * <p>For this reason, while most state within this class is guarded by this instance, methods
 * exposed to individual stream instances need to use atomic or volatile types, since those calls
 * will already be synchronized on the individual RPC objects.
 *
 * <p><b>IMPORTANT</b>: This implementation must comply with this published wire format.
 * https://github.com/grpc/proposal/blob/master/L73-java-binderchannel/wireformat.md
 */
@ThreadSafe
public abstract class BinderTransport implements IBinder.DeathRecipient {

  private static final Logger logger = Logger.getLogger(BinderTransport.class.getName());

  /**
   * Attribute used to store the Android UID of the remote app. This is guaranteed to be set on any
   * active transport.
   */
  @Internal
  public static final Attributes.Key<Integer> REMOTE_UID =
      Attributes.Key.create("internal:remote-uid");

  /** The authority of the server. */
  @Internal
  public static final Attributes.Key<String> SERVER_AUTHORITY =
      Attributes.Key.create("internal:server-authority");

  /** A transport attribute to hold the {@link InboundParcelablePolicy}. */
  @Internal
  public static final Attributes.Key<InboundParcelablePolicy> INBOUND_PARCELABLE_POLICY =
      Attributes.Key.create("internal:inbound-parcelable-policy");

  /**
   * Version code for this wire format.
   *
   * <p>Should this change, we should still endeavor to support earlier wire-format versions. If
   * that's not possible, {@link EARLIEST_SUPPORTED_WIRE_FORMAT_VERSION} should be updated below.
   */
  @Internal public static final int WIRE_FORMAT_VERSION = 1;

  /** The version code of the earliest wire format we support. */
  @Internal public static final int EARLIEST_SUPPORTED_WIRE_FORMAT_VERSION = 1;

  /** The max number of "in-flight" bytes before we start buffering transactions. */
  private static final int TRANSACTION_BYTES_WINDOW = 128 * 1024;

  /** The number of in-flight bytes we should receive between sendings acks to our peer. */
  private static final int TRANSACTION_BYTES_WINDOW_FORCE_ACK = 16 * 1024;

  /**
   * Sent from the client to host service binder to initiate a new transport, and from the host to
   * the binder. and from the host s Followed by: int wire_protocol_version IBinder
   * client_transports_callback_binder
   */
  @Internal public static final int SETUP_TRANSPORT = IBinder.FIRST_CALL_TRANSACTION;

  /** Send to shutdown the transport from either end. */
  @Internal public static final int SHUTDOWN_TRANSPORT = IBinder.FIRST_CALL_TRANSACTION + 1;

  /** Send to acknowledge receipt of rpc bytes, for flow control. */
  static final int ACKNOWLEDGE_BYTES = IBinder.FIRST_CALL_TRANSACTION + 2;

  /** A ping request. */
  private static final int PING = IBinder.FIRST_CALL_TRANSACTION + 3;

  /** A response to a ping. */
  private static final int PING_RESPONSE = IBinder.FIRST_CALL_TRANSACTION + 4;

  /** Reserved transaction IDs for any special events we might need. */
  private static final int RESERVED_TRANSACTIONS = 1000;

  /** The first call ID we can use. */
  static final int FIRST_CALL_ID = IBinder.FIRST_CALL_TRANSACTION + RESERVED_TRANSACTIONS;

  /** The last call ID we can use. */
  static final int LAST_CALL_ID = IBinder.LAST_CALL_TRANSACTION;

  /** The states of this transport. */
  protected enum TransportState {
    NOT_STARTED, // We haven't been started yet.
    SETUP, // We're setting up the connection.
    READY, // The transport is ready.
    SHUTDOWN, // We've been shutdown and won't accept any additional calls (thought existing calls
    // may continue).
    SHUTDOWN_TERMINATED // We've been shutdown completely (or we failed to start). We can't send or
    // receive any data.
  }

  private final ObjectPool<ScheduledExecutorService> executorServicePool;
  private final ScheduledExecutorService scheduledExecutorService;
  private final InternalLogId logId;
  @GuardedBy("this")
  private final LeakSafeOneWayBinder incomingBinder;

  protected final ConcurrentHashMap<Integer, Inbound<?>> ongoingCalls;
  protected final OneWayBinderProxy.Decorator binderDecorator;

  @GuardedBy("this")
  private final LinkedHashSet<Integer> callIdsToNotifyWhenReady = new LinkedHashSet<>();

  @GuardedBy("this")
  protected Attributes attributes;

  @GuardedBy("this")
  private TransportState transportState = TransportState.NOT_STARTED;

  @GuardedBy("this")
  @Nullable
  protected Status shutdownStatus;

  @Nullable private OneWayBinderProxy outgoingBinder;

  private final FlowController flowController;

  /** The number of incoming bytes we've received. */
  // Only read/written on @BinderThread.
  private long numIncomingBytes;

  /** The number of incoming bytes we've told our peer we've received. */
  // Only read/written on @BinderThread.
  private long acknowledgedIncomingBytes;

  protected BinderTransport(
      ObjectPool<ScheduledExecutorService> executorServicePool,
      Attributes attributes,
      OneWayBinderProxy.Decorator binderDecorator,
      InternalLogId logId) {
    this.binderDecorator = binderDecorator;
    this.executorServicePool = executorServicePool;
    this.attributes = attributes;
    this.logId = logId;
    scheduledExecutorService = executorServicePool.getObject();
    incomingBinder = new LeakSafeOneWayBinder(this::handleTransaction);
    ongoingCalls = new ConcurrentHashMap<>();
    flowController = new FlowController(TRANSACTION_BYTES_WINDOW);
  }

  // Override in child class.
  public final ScheduledExecutorService getScheduledExecutorService() {
    return scheduledExecutorService;
  }

  // Override in child class.
  public final ListenableFuture<SocketStats> getStats() {
    Attributes attributes = getAttributes();
    return immediateFuture(
        new InternalChannelz.SocketStats(
            /* data= */ null, // TODO: Keep track of these stats with TransportTracer or similar.
            /* local= */ attributes.get(Grpc.TRANSPORT_ATTR_LOCAL_ADDR),
            /* remote= */ attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR),
            // TODO: SocketOptions are meaningless for binder but we're still forced to provide one.
            new InternalChannelz.SocketOptions.Builder().build(),
            /* security= */ null));
  }

  // Override in child class.
  public final InternalLogId getLogId() {
    return logId;
  }

  // Override in child class.
  public final synchronized Attributes getAttributes() {
    return attributes;
  }

  /**
   * Returns whether this transport is able to send rpc transactions. Intentionally unsynchronized
   * since this will be called while Outbound is held.
   */
  final boolean isReady() {
    return !flowController.isTransmitWindowFull();
  }

  @GuardedBy("this")
  abstract void notifyShutdown(Status shutdownStatus);

  @GuardedBy("this")
  abstract void notifyTerminated();

  void releaseExecutors() {
    executorServicePool.returnObject(scheduledExecutorService);
  }

  @GuardedBy("this")
  boolean inState(TransportState transportState) {
    return this.transportState == transportState;
  }

  @GuardedBy("this")
  boolean isShutdown() {
    return inState(TransportState.SHUTDOWN) || inState(TransportState.SHUTDOWN_TERMINATED);
  }

  @GuardedBy("this")
  final void setState(TransportState newState) {
    checkTransition(transportState, newState);
    transportState = newState;
  }

  @GuardedBy("this")
  protected boolean setOutgoingBinder(OneWayBinderProxy binder) {
    binder = binderDecorator.decorate(binder);
    this.outgoingBinder = binder;
    try {
      binder.getDelegate().linkToDeath(this, 0);
      return true;
    } catch (RemoteException re) {
      return false;
    }
  }

  @Override
  public synchronized void binderDied() {
    shutdownInternal(
        Status.UNAVAILABLE.withDescription(
            "Peer process crashed, exited or was killed (binderDied)"),
        true);
  }

  @GuardedBy("this")
  final void shutdownInternal(Status shutdownStatus, boolean forceTerminate) {
    if (!isShutdown()) {
      this.shutdownStatus = shutdownStatus;
      setState(TransportState.SHUTDOWN);
      notifyShutdown(shutdownStatus);
    }
    if (!inState(TransportState.SHUTDOWN_TERMINATED)
        && (forceTerminate || ongoingCalls.isEmpty())) {
      incomingBinder.detach();
      setState(TransportState.SHUTDOWN_TERMINATED);
      sendShutdownTransaction();
      ArrayList<Inbound<?>> calls = new ArrayList<>(ongoingCalls.values());
      ongoingCalls.clear();
      scheduledExecutorService.execute(
          () -> {
            for (Inbound<?> inbound : calls) {
              synchronized (inbound) {
                inbound.closeAbnormal(shutdownStatus);
              }
            }
            synchronized (this) {
              notifyTerminated();
            }
            releaseExecutors();
          });
    }
  }

  @GuardedBy("this")
  final void sendSetupTransaction() {
    sendSetupTransaction(checkNotNull(outgoingBinder));
  }

  @GuardedBy("this")
  final void sendSetupTransaction(OneWayBinderProxy iBinder) {
    try (ParcelHolder parcel = ParcelHolder.obtain()) {
      parcel.get().writeInt(WIRE_FORMAT_VERSION);
      parcel.get().writeStrongBinder(incomingBinder);
      iBinder.transact(SETUP_TRANSPORT, parcel);
    } catch (RemoteException re) {
      shutdownInternal(statusFromRemoteException(re), true);
    }
  }

  @GuardedBy("this")
  private final void sendShutdownTransaction() {
    if (outgoingBinder != null) {
      try {
        outgoingBinder.getDelegate().unlinkToDeath(this, 0);
      } catch (NoSuchElementException e) {
        // Ignore.
      }
      try (ParcelHolder parcel = ParcelHolder.obtain()) {
        // Send empty flags to avoid a memory leak linked to empty parcels (b/207778694).
        parcel.get().writeInt(0);
        outgoingBinder.transact(SHUTDOWN_TRANSPORT, parcel);
      } catch (RemoteException re) {
        // Ignore.
      }
    }
  }

  protected synchronized void sendPing(int id) throws StatusException {
    if (inState(TransportState.SHUTDOWN_TERMINATED)) {
      throw shutdownStatus.asException();
    } else if (outgoingBinder == null) {
      throw Status.FAILED_PRECONDITION.withDescription("Transport not ready.").asException();
    } else {
      try (ParcelHolder parcel = ParcelHolder.obtain()) {
        parcel.get().writeInt(id);
        outgoingBinder.transact(PING, parcel);
      } catch (RemoteException re) {
        throw statusFromRemoteException(re).asException();
      }
    }
  }

  protected void unregisterInbound(Inbound<?> inbound) {
    unregisterCall(inbound.callId);
  }

  final void unregisterCall(int callId) {
    boolean removed = (ongoingCalls.remove(callId) != null);
    if (removed && ongoingCalls.isEmpty()) {
      // Possibly shutdown (not synchronously, since inbound is held).
      scheduledExecutorService.execute(
          () -> {
            synchronized (this) {
              if (inState(TransportState.SHUTDOWN)) {
                // No more ongoing calls, and we're shutdown. Finish the shutdown.
                shutdownInternal(shutdownStatus, true);
              }
            }
          });
    }
  }

  final void sendTransaction(int callId, ParcelHolder parcel) throws StatusException {
    int dataSize = parcel.get().dataSize();
    try {
      outgoingBinder.transact(callId, parcel);
    } catch (RemoteException re) {
      throw statusFromRemoteException(re).asException();
    }
    if (flowController.notifyBytesSent(dataSize)) {
      logger.log(Level.FINE, "transmit window now full " + this);
    }
  }

  final void sendOutOfBandClose(int callId, Status status) {
    try (ParcelHolder parcel = ParcelHolder.obtain()) {
      parcel.get().writeInt(0); // Placeholder for flags. Will be filled in below.
      int flags = TransactionUtils.writeStatus(parcel.get(), status);
      TransactionUtils.fillInFlags(parcel.get(), flags | TransactionUtils.FLAG_OUT_OF_BAND_CLOSE);
      sendTransaction(callId, parcel);
    } catch (StatusException e) {
      logger.log(Level.FINER, "Failed sending oob close transaction", e);
    }
  }

  @BinderThread
  @VisibleForTesting
  final boolean handleTransaction(int code, Parcel parcel) {
    try {
      return handleTransactionInternal(code, parcel);
    } catch (RuntimeException e) {
      logger.log(
          Level.SEVERE, "Terminating transport for uncaught Exception in transaction " + code, e);
      synchronized (this) {
        // This unhandled exception may have put us in an inconsistent state. Force terminate the
        // whole transport so our peer knows something is wrong and so that clients can retry with
        // a fresh transport instance on both sides.
        shutdownInternal(Status.INTERNAL.withCause(e), true);
        return false;
      }
    }
  }

  @BinderThread
  private boolean handleTransactionInternal(int code, Parcel parcel) {
    if (code < FIRST_CALL_ID) {
      synchronized (this) {
        switch (code) {
          case ACKNOWLEDGE_BYTES:
            handleAcknowledgedBytes(parcel.readLong());
            break;
          case SHUTDOWN_TRANSPORT:
            shutdownInternal(
                Status.UNAVAILABLE.withDescription("transport shutdown by peer"), true);
            break;
          case SETUP_TRANSPORT:
            handleSetupTransport(parcel);
            break;
          case PING:
            handlePing(parcel);
            break;
          case PING_RESPONSE:
            handlePingResponse(parcel);
            break;
          default:
            return false;
        }
        return true;
      }
    } else {
      int size = parcel.dataSize();
      Inbound<?> inbound = ongoingCalls.get(code);
      if (inbound == null) {
        synchronized (this) {
          if (!isShutdown()) {
            inbound = createInbound(code);
            if (inbound != null) {
              Inbound<?> existing = ongoingCalls.put(code, inbound);
              // Can't happen as only one invocation of handleTransaction() is running at a time.
              Verify.verify(existing == null, "impossible appearance of %s", existing);
            }
          }
        }
      }
      if (inbound != null) {
        inbound.handleTransaction(parcel);
      }
      numIncomingBytes += size;
      if ((numIncomingBytes - acknowledgedIncomingBytes) > TRANSACTION_BYTES_WINDOW_FORCE_ACK) {
        synchronized (this) {
          sendAcknowledgeBytes(checkNotNull(outgoingBinder), numIncomingBytes);
        }
        acknowledgedIncomingBytes = numIncomingBytes;
      }
      return true;
    }
  }

  @BinderThread
  @GuardedBy("this")
  protected void restrictIncomingBinderToCallsFrom(int allowedCallingUid) {
    TransactionHandler currentHandler = incomingBinder.getHandler();
    if (currentHandler != null) {
      incomingBinder.setHandler(newCallerFilteringHandler(allowedCallingUid, currentHandler));
    }
  }

  @Nullable
  @GuardedBy("this")
  protected Inbound<?> createInbound(int callId) {
    return null;
  }

  @GuardedBy("this")
  protected void handleSetupTransport(Parcel parcel) {}

  @GuardedBy("this")
  private final void handlePing(Parcel requestParcel) {
    int id = requestParcel.readInt();
    if (transportState == TransportState.READY) {
      try (ParcelHolder replyParcel = ParcelHolder.obtain()) {
        replyParcel.get().writeInt(id);
        outgoingBinder.transact(PING_RESPONSE, replyParcel);
      } catch (RemoteException re) {
        // Ignore.
      }
    }
  }

  @GuardedBy("this")
  protected void handlePingResponse(Parcel parcel) {}

  @GuardedBy("this")
  private void sendAcknowledgeBytes(OneWayBinderProxy iBinder, long n) {
    // Send a transaction to acknowledge reception of incoming data.
    try (ParcelHolder parcel = ParcelHolder.obtain()) {
      parcel.get().writeLong(n);
      iBinder.transact(ACKNOWLEDGE_BYTES, parcel);
    } catch (RemoteException re) {
      shutdownInternal(statusFromRemoteException(re), true);
    }
  }

  @GuardedBy("this")
  final void handleAcknowledgedBytes(long numBytes) {
    if (flowController.handleAcknowledgedBytes(numBytes)) {
      logger.log(
          Level.FINE,
          "handleAcknowledgedBytes: Transmit Window No-Longer Full. Unblock calls: " + this);

      // The LinkedHashSet contract guarantees that an id already present in this collection will
      // not lose its priority if we re-insert it here.
      callIdsToNotifyWhenReady.addAll(ongoingCalls.keySet());

      Iterator<Integer> i = callIdsToNotifyWhenReady.iterator();
      while (isReady() && i.hasNext()) {
        Inbound<?> inbound = ongoingCalls.get(i.next());
        i.remove();
        if (inbound != null) { // Calls can be removed out from under us.
          inbound.onTransportReady();
        }
      }
    }
  }

  private static void checkTransition(TransportState current, TransportState next) {
    switch (next) {
      case SETUP:
        checkState(current == TransportState.NOT_STARTED);
        break;
      case READY:
        checkState(current == TransportState.NOT_STARTED || current == TransportState.SETUP);
        break;
      case SHUTDOWN:
        checkState(
            current == TransportState.NOT_STARTED
                || current == TransportState.SETUP
                || current == TransportState.READY);
        break;
      case SHUTDOWN_TERMINATED:
        checkState(current == TransportState.SHUTDOWN);
        break;
      default:
        throw new AssertionError();
    }
  }

  @VisibleForTesting
  Map<Integer, Inbound<?>> getOngoingCalls() {
    return ongoingCalls;
  }

  @VisibleForTesting
  synchronized LeakSafeOneWayBinder getIncomingBinderForTesting() {
    return this.incomingBinder;
  }

  private static Status statusFromRemoteException(RemoteException e) {
    if (e instanceof DeadObjectException || e instanceof TransactionTooLargeException) {
      // These are to be expected from time to time and can simply be retried.
      return Status.UNAVAILABLE.withCause(e);
    }
    // Otherwise, this exception from transact is unexpected.
    return Status.INTERNAL.withCause(e);
  }
}
