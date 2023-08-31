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

import android.content.Context;
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.TransactionTooLargeException;
import android.os.UserHandle;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientStreamTracer;
import io.grpc.Grpc;
import io.grpc.Internal;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalLogId;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.SecurityLevel;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.binder.AndroidComponentAddress;
import io.grpc.binder.BindServiceFlags;
import io.grpc.binder.BinderChannelCredentials;
import io.grpc.binder.InboundParcelablePolicy;
import io.grpc.binder.SecurityPolicy;
import io.grpc.internal.ClientStream;
import io.grpc.internal.ConnectionClientTransport;
import io.grpc.internal.FailingClientStream;
import io.grpc.internal.GrpcAttributes;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServerStream;
import io.grpc.internal.ServerTransport;
import io.grpc.internal.ServerTransportListener;
import io.grpc.internal.StatsTraceContext;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
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
public abstract class BinderTransport
    implements LeakSafeOneWayBinder.TransactionHandler, IBinder.DeathRecipient {

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
  @Internal
  public static final int WIRE_FORMAT_VERSION = 1;

  /** The version code of the earliest wire format we support. */
  @Internal
  public static final int EARLIEST_SUPPORTED_WIRE_FORMAT_VERSION = 1;

  /** The max number of "in-flight" bytes before we start buffering transactions. */
  private static final int TRANSACTION_BYTES_WINDOW = 128 * 1024;

  /** The number of in-flight bytes we should receive between sendings acks to our peer. */
  private static final int TRANSACTION_BYTES_WINDOW_FORCE_ACK = 16 * 1024;

  /**
   * Sent from the client to host service binder to initiate a new transport, and from the host to
   * the binder. and from the host s Followed by: int wire_protocol_version IBinder
   * client_transports_callback_binder
   */
  @Internal
  public static final int SETUP_TRANSPORT = IBinder.FIRST_CALL_TRANSACTION;

  /** Send to shutdown the transport from either end. */
  @Internal
  public static final int SHUTDOWN_TRANSPORT = IBinder.FIRST_CALL_TRANSACTION + 1;

  /** Send to acknowledge receipt of rpc bytes, for flow control. */
  static final int ACKNOWLEDGE_BYTES = IBinder.FIRST_CALL_TRANSACTION + 2;

  /** A ping request. */
  private static final int PING = IBinder.FIRST_CALL_TRANSACTION + 3;

  /** A response to a ping. */
  private static final int PING_RESPONSE = IBinder.FIRST_CALL_TRANSACTION + 4;

  /** Reserved transaction IDs for any special events we might need. */
  private static final int RESERVED_TRANSACTIONS = 1000;

  /** The first call ID we can use. */
  private static final int FIRST_CALL_ID = IBinder.FIRST_CALL_TRANSACTION + RESERVED_TRANSACTIONS;

  /** The last call ID we can use. */
  private static final int LAST_CALL_ID = IBinder.LAST_CALL_TRANSACTION;

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
  private final LeakSafeOneWayBinder incomingBinder;

  protected final ConcurrentHashMap<Integer, Inbound<?>> ongoingCalls;

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
  private final AtomicLong numIncomingBytes;

  /** The number of incoming bytes we've told our peer we've received. */
  private long acknowledgedIncomingBytes;

  private BinderTransport(
      ObjectPool<ScheduledExecutorService> executorServicePool,
      Attributes attributes,
      InternalLogId logId) {
    this.executorServicePool = executorServicePool;
    this.attributes = attributes;
    this.logId = logId;
    scheduledExecutorService = executorServicePool.getObject();
    incomingBinder = new LeakSafeOneWayBinder(this);
    ongoingCalls = new ConcurrentHashMap<>();
    flowController = new FlowController(TRANSACTION_BYTES_WINDOW);
    numIncomingBytes = new AtomicLong();
  }

  // Override in child class.
  public final ScheduledExecutorService getScheduledExecutorService() {
    return scheduledExecutorService;
  }

  // Override in child class.
  public final ListenableFuture<SocketStats> getStats() {
    return immediateFuture(null);
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

  abstract void notifyShutdown(Status shutdownStatus);

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
    shutdownInternal(Status.UNAVAILABLE.withDescription("binderDied"), true);
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
            notifyTerminated();
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
      logger.log(Level.WARNING, "Failed sending oob close transaction", e);
    }
  }

  @Override
  public final boolean handleTransaction(int code, Parcel parcel) {
    try {
      return handleTransactionInternal(code, parcel);
    } catch (RuntimeException e) {
      logger.log(Level.SEVERE,
          "Terminating transport for uncaught Exception in transaction " + code, e);
      synchronized (this) {
        // This unhandled exception may have put us in an inconsistent state. Force terminate the
        // whole transport so our peer knows something is wrong and so that clients can retry with
        // a fresh transport instance on both sides.
        shutdownInternal(Status.INTERNAL.withCause(e), true);
        return false;
      }
    }
  }

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
            // Create a new inbound. Strictly speaking we could end up doing this twice on
            // two threads, hence the need to use putIfAbsent, and check its result.
            inbound = createInbound(code);
            if (inbound != null) {
              Inbound<?> inbound2 = ongoingCalls.putIfAbsent(code, inbound);
              if (inbound2 != null) {
                inbound = inbound2;
              }
            }
          }
        }
      }
      if (inbound != null) {
        inbound.handleTransaction(parcel);
      }
      long nib = numIncomingBytes.addAndGet(size);
      if ((nib - acknowledgedIncomingBytes) > TRANSACTION_BYTES_WINDOW_FORCE_ACK) {
        synchronized (this) {
          sendAcknowledgeBytes(checkNotNull(outgoingBinder));
        }
      }
      return true;
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
  private void sendAcknowledgeBytes(OneWayBinderProxy iBinder) {
    // Send a transaction to acknowledge reception of incoming data.
    long n = numIncomingBytes.get();
    acknowledgedIncomingBytes = n;
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

  /** Concrete client-side transport implementation. */
  @ThreadSafe
  @Internal
  public static final class BinderClientTransport extends BinderTransport
      implements ConnectionClientTransport, Bindable.Observer {

    private final ObjectPool<? extends Executor> offloadExecutorPool;
    private final Executor offloadExecutor;
    private final SecurityPolicy securityPolicy;
    private final Bindable serviceBinding;
    /** Number of ongoing calls which keep this transport "in-use". */
    private final AtomicInteger numInUseStreams;

    private final PingTracker pingTracker;

    @Nullable private ManagedClientTransport.Listener clientTransportListener;

    @GuardedBy("this")
    private int latestCallId = FIRST_CALL_ID;

    public BinderClientTransport(
        Context sourceContext,
        BinderChannelCredentials channelCredentials,
        AndroidComponentAddress targetAddress,
        @Nullable UserHandle targetUserHandle,
        BindServiceFlags bindServiceFlags,
        Executor mainThreadExecutor,
        ObjectPool<ScheduledExecutorService> executorServicePool,
        ObjectPool<? extends Executor> offloadExecutorPool,
        SecurityPolicy securityPolicy,
        InboundParcelablePolicy inboundParcelablePolicy,
        Attributes eagAttrs) {
      super(
          executorServicePool,
          buildClientAttributes(eagAttrs, sourceContext, targetAddress, inboundParcelablePolicy),
          buildLogId(sourceContext, targetAddress));
      this.offloadExecutorPool = offloadExecutorPool;
      this.securityPolicy = securityPolicy;
      this.offloadExecutor = offloadExecutorPool.getObject();
      numInUseStreams = new AtomicInteger();
      pingTracker = new PingTracker(Ticker.systemTicker(), (id) -> sendPing(id));

      serviceBinding =
          new ServiceBinding(
              mainThreadExecutor,
              sourceContext,
              channelCredentials,
              targetAddress.asBindIntent(),
              targetUserHandle,
              bindServiceFlags.toInteger(),
              this);
    }

    @Override
    void releaseExecutors() {
      super.releaseExecutors();
      offloadExecutorPool.returnObject(offloadExecutor);
    }

    @Override
    public synchronized void onBound(IBinder binder) {
      sendSetupTransaction(OneWayBinderProxy.wrap(binder, offloadExecutor));
    }

    @Override
    public synchronized void onUnbound(Status reason) {
      shutdownInternal(reason, true);
    }

    @CheckReturnValue
    @Override
    public synchronized Runnable start(ManagedClientTransport.Listener clientTransportListener) {
      this.clientTransportListener = checkNotNull(clientTransportListener);
      return () -> {
        synchronized (BinderClientTransport.this) {
          if (inState(TransportState.NOT_STARTED)) {
            setState(TransportState.SETUP);
            serviceBinding.bind();
          }
        }
      };
    }

    @Override
    public synchronized ClientStream newStream(
        final MethodDescriptor<?, ?> method,
        final Metadata headers,
        final CallOptions callOptions,
        ClientStreamTracer[] tracers) {
      if (!inState(TransportState.READY)) {
        return newFailingClientStream(
            isShutdown()
                ? shutdownStatus
                : Status.INTERNAL.withDescription("newStream() before transportReady()"),
            attributes,
            headers,
            tracers);
      }

      int callId = latestCallId++;
      if (latestCallId == LAST_CALL_ID) {
        latestCallId = FIRST_CALL_ID;
      }
      StatsTraceContext statsTraceContext =
          StatsTraceContext.newClientContext(tracers, attributes, headers);
      Inbound.ClientInbound inbound =
          new Inbound.ClientInbound(
              this, attributes, callId, GrpcUtil.shouldBeCountedForInUse(callOptions));
      if (ongoingCalls.putIfAbsent(callId, inbound) != null) {
        Status failure = Status.INTERNAL.withDescription("Clashing call IDs");
        shutdownInternal(failure, true);
        return newFailingClientStream(failure, attributes, headers, tracers);
      } else {
        if (inbound.countsForInUse() && numInUseStreams.getAndIncrement() == 0) {
          clientTransportListener.transportInUse(true);
        }
        Outbound.ClientOutbound outbound =
            new Outbound.ClientOutbound(this, callId, method, headers, statsTraceContext);
        if (method.getType().clientSendsOneMessage()) {
          return new SingleMessageClientStream(inbound, outbound, attributes);
        } else {
          return new MultiMessageClientStream(inbound, outbound, attributes);
        }
      }
    }

    @Override
    protected void unregisterInbound(Inbound<?> inbound) {
      if (inbound.countsForInUse() && numInUseStreams.decrementAndGet() == 0) {
        clientTransportListener.transportInUse(false);
      }
      super.unregisterInbound(inbound);
    }

    @Override
    public void ping(final PingCallback callback, Executor executor) {
      pingTracker.startPing(callback, executor);
    }

    @Override
    public synchronized void shutdown(Status reason) {
      checkNotNull(reason, "reason");
      shutdownInternal(reason, false);
    }

    @Override
    public synchronized void shutdownNow(Status reason) {
      checkNotNull(reason, "reason");
      shutdownInternal(reason, true);
    }

    @Override
    @GuardedBy("this")
    public void notifyShutdown(Status status) {
      clientTransportListener.transportShutdown(status);
    }

    @Override
    @GuardedBy("this")
    public void notifyTerminated() {
      if (numInUseStreams.getAndSet(0) > 0) {
        clientTransportListener.transportInUse(false);
      }
      serviceBinding.unbind();
      clientTransportListener.transportTerminated();
    }

    @Override
    @GuardedBy("this")
    protected void handleSetupTransport(Parcel parcel) {
      // Add the remote uid to our attributes.
      attributes = setSecurityAttrs(attributes, Binder.getCallingUid());
      if (inState(TransportState.SETUP)) {
        int version = parcel.readInt();
        IBinder binder = parcel.readStrongBinder();
        if (version != WIRE_FORMAT_VERSION) {
          shutdownInternal(
              Status.UNAVAILABLE.withDescription("Wire format version mismatch"), true);
        } else if (binder == null) {
          shutdownInternal(
              Status.UNAVAILABLE.withDescription("Malformed SETUP_TRANSPORT data"), true);
        } else {
          offloadExecutor.execute(() -> checkSecurityPolicy(binder));
        }
      }
    }

    private void checkSecurityPolicy(IBinder binder) {
      Status authorization;
      Integer remoteUid;
      synchronized (this) {
        remoteUid = attributes.get(REMOTE_UID);
      }
      if (remoteUid == null) {
        authorization = Status.UNAUTHENTICATED.withDescription("No remote UID available");
      } else {
        authorization = securityPolicy.checkAuthorization(remoteUid);
      }
      synchronized (this) {
        if (inState(TransportState.SETUP)) {
          if (!authorization.isOk()) {
            shutdownInternal(authorization, true);
          } else if (!setOutgoingBinder(OneWayBinderProxy.wrap(binder, offloadExecutor))) {
            shutdownInternal(
                Status.UNAVAILABLE.withDescription("Failed to observe outgoing binder"), true);
          } else {
            // Check state again, since a failure inside setOutgoingBinder (or a callback it
            // triggers), could have shut us down.
            if (!isShutdown()) {
              setState(TransportState.READY);
              clientTransportListener.transportReady();
            }
          }
        }
      }
    }

    @GuardedBy("this")
    @Override
    protected void handlePingResponse(Parcel parcel) {
      pingTracker.onPingResponse(parcel.readInt());
    }

    private static ClientStream newFailingClientStream(
        Status failure, Attributes attributes, Metadata headers,
        ClientStreamTracer[] tracers) {
      StatsTraceContext statsTraceContext =
          StatsTraceContext.newClientContext(tracers, attributes, headers);
      statsTraceContext.clientOutboundHeaders();
      return new FailingClientStream(failure, tracers);
    }

    private static InternalLogId buildLogId(
        Context sourceContext, AndroidComponentAddress targetAddress) {
      return InternalLogId.allocate(
          BinderClientTransport.class,
          sourceContext.getClass().getSimpleName()
              + "->"
              + targetAddress.getComponent().toShortString());
    }

    private static Attributes buildClientAttributes(
        Attributes eagAttrs,
        Context sourceContext,
        AndroidComponentAddress targetAddress,
        InboundParcelablePolicy inboundParcelablePolicy) {
      return Attributes.newBuilder()
          .set(GrpcAttributes.ATTR_SECURITY_LEVEL, SecurityLevel.NONE) // Trust noone for now.
          .set(GrpcAttributes.ATTR_CLIENT_EAG_ATTRS, eagAttrs)
          .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, AndroidComponentAddress.forContext(sourceContext))
          .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, targetAddress)
          .set(INBOUND_PARCELABLE_POLICY, inboundParcelablePolicy)
          .build();
    }

    private static Attributes setSecurityAttrs(Attributes attributes, int uid) {
      return attributes.toBuilder()
          .set(REMOTE_UID, uid)
          .set(
              GrpcAttributes.ATTR_SECURITY_LEVEL,
              uid == Process.myUid()
                  ? SecurityLevel.PRIVACY_AND_INTEGRITY
                  : SecurityLevel.INTEGRITY) // TODO: Have the SecrityPolicy decide this.
          .build();
    }
  }

  /** Concrete server-side transport implementation. */
  @Internal
  public static final class BinderServerTransport extends BinderTransport implements ServerTransport {

    private final List<ServerStreamTracer.Factory> streamTracerFactories;
    @Nullable private ServerTransportListener serverTransportListener;

    public BinderServerTransport(
        ObjectPool<ScheduledExecutorService> executorServicePool,
        Attributes attributes,
        List<ServerStreamTracer.Factory> streamTracerFactories,
        IBinder callbackBinder) {
      super(executorServicePool, attributes, buildLogId(attributes));
      this.streamTracerFactories = streamTracerFactories;
      // TODO(jdcormie): Plumb in the Server's executor() and use it here instead.
      setOutgoingBinder(OneWayBinderProxy.wrap(callbackBinder, getScheduledExecutorService()));
    }

    public synchronized void setServerTransportListener(ServerTransportListener serverTransportListener) {
      this.serverTransportListener = serverTransportListener;
      if (isShutdown()) {
        setState(TransportState.SHUTDOWN_TERMINATED);
        notifyTerminated();
        releaseExecutors();
      } else {
        sendSetupTransaction();
        // Check we're not shutdown again, since a failure inside sendSetupTransaction (or a
        // callback it triggers), could have shut us down.
        if (!isShutdown()) {
          setState(TransportState.READY);
          attributes = serverTransportListener.transportReady(attributes);
        }
      }
    }

    StatsTraceContext createStatsTraceContext(String methodName, Metadata headers) {
      return StatsTraceContext.newServerContext(streamTracerFactories, methodName, headers);
    }

    synchronized Status startStream(ServerStream stream, String methodName, Metadata headers) {
      if (isShutdown()) {
        return Status.UNAVAILABLE.withDescription("transport is shutdown");
      } else {
        serverTransportListener.streamCreated(stream, methodName, headers);
        return Status.OK;
      }
    }

    @Override
    @GuardedBy("this")
    public void notifyShutdown(Status status) {
      // Nothing to do.
    }

    @Override
    @GuardedBy("this")
    public void notifyTerminated() {
      if (serverTransportListener != null) {
        serverTransportListener.transportTerminated();
      }
    }

    @Override
    public synchronized void shutdown() {
      shutdownInternal(Status.OK, false);
    }

    @Override
    public synchronized void shutdownNow(Status reason) {
      shutdownInternal(reason, true);
    }

    @Override
    @Nullable
    @GuardedBy("this")
    protected Inbound<?> createInbound(int callId) {
      return new Inbound.ServerInbound(this, attributes, callId);
    }

    private static InternalLogId buildLogId(Attributes attributes) {
      return InternalLogId.allocate(
          BinderServerTransport.class, "from " + attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR));
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

  private static Status statusFromRemoteException(RemoteException e) {
    if (e instanceof DeadObjectException || e instanceof TransactionTooLargeException) {
      // These are to be expected from time to time and can simply be retried.
      return Status.UNAVAILABLE.withCause(e);
    }
    // Otherwise, this exception from transact is unexpected.
    return Status.INTERNAL.withCause(e);
  }
}

