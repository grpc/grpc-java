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
import static io.grpc.binder.ApiConstants.PRE_AUTH_SERVER_OVERRIDE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import androidx.annotation.BinderThread;
import androidx.annotation.MainThread;
import com.google.common.base.Ticker;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientStreamTracer;
import io.grpc.Grpc;
import io.grpc.Internal;
import io.grpc.InternalLogId;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.SecurityLevel;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.binder.AndroidComponentAddress;
import io.grpc.binder.AsyncSecurityPolicy;
import io.grpc.binder.InboundParcelablePolicy;
import io.grpc.binder.SecurityPolicy;
import io.grpc.internal.ClientStream;
import io.grpc.internal.ClientTransportFactory.ClientTransportOptions;
import io.grpc.internal.ConnectionClientTransport;
import io.grpc.internal.FailingClientStream;
import io.grpc.internal.GrpcAttributes;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.StatsTraceContext;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/** Concrete client-side transport implementation. */
@ThreadSafe
@Internal
public final class BinderClientTransport extends BinderTransport
    implements ConnectionClientTransport, Bindable.Observer {

  private final ObjectPool<? extends Executor> offloadExecutorPool;
  private final Executor offloadExecutor;
  private final SecurityPolicy securityPolicy;
  private final Bindable serviceBinding;
  private final ClientHandshake handshake;

  /** Number of ongoing calls which keep this transport "in-use". */
  private final AtomicInteger numInUseStreams;

  private final long readyTimeoutMillis;
  private final PingTracker pingTracker;
  private final boolean preAuthorizeServer;

  @Nullable private ManagedClientTransport.Listener clientTransportListener;

  @GuardedBy("this")
  private int latestCallId = FIRST_CALL_ID;

  @GuardedBy("this")
  private ScheduledFuture<?> readyTimeoutFuture; // != null iff timeout scheduled.

  @GuardedBy("this")
  @Nullable
  private ListenableFuture<Status> authResultFuture; // null before we check auth.

  @GuardedBy("this")
  @Nullable
  private ListenableFuture<Status> preAuthResultFuture; // null before we pre-auth.

  /**
   * Constructs a new transport instance.
   *
   * @param factory parameters common to all a Channel's transports
   * @param targetAddress the fully resolved and load-balanced server address
   * @param options other parameters that can vary as transports come and go within a Channel
   */
  public BinderClientTransport(
      BinderClientTransportFactory factory,
      AndroidComponentAddress targetAddress,
      ClientTransportOptions options) {
    super(
        factory.scheduledExecutorPool,
        buildClientAttributes(
            options.getEagAttributes(),
            factory.sourceContext,
            targetAddress,
            factory.inboundParcelablePolicy),
        factory.binderDecorator,
        buildLogId(factory.sourceContext, targetAddress));
    this.offloadExecutorPool = factory.offloadExecutorPool;
    this.securityPolicy = factory.securityPolicy;
    this.offloadExecutor = offloadExecutorPool.getObject();
    this.readyTimeoutMillis = factory.readyTimeoutMillis;
    Boolean preAuthServerOverride = options.getEagAttributes().get(PRE_AUTH_SERVER_OVERRIDE);
    this.preAuthorizeServer =
        preAuthServerOverride != null ? preAuthServerOverride : factory.preAuthorizeServers;
    this.handshake = new LegacyClientHandshake();
    numInUseStreams = new AtomicInteger();
    pingTracker = new PingTracker(Ticker.systemTicker(), (id) -> sendPing(id));

    serviceBinding =
        new ServiceBinding(
            factory.mainThreadExecutor,
            factory.sourceContext,
            factory.channelCredentials,
            targetAddress.asBindIntent(),
            targetAddress.getTargetUser() != null
                ? targetAddress.getTargetUser()
                : factory.defaultTargetUserHandle,
            factory.bindServiceFlags.toInteger(),
            this);
  }

  @Override
  void releaseExecutors() {
    super.releaseExecutors();
    offloadExecutorPool.returnObject(offloadExecutor);
  }

  @Override
  public synchronized void onBound(IBinder binder) {
    OneWayBinderProxy binderProxy = OneWayBinderProxy.wrap(binder, offloadExecutor);
    binderProxy = binderDecorator.decorate(binderProxy);
    handshake.onBound(binderProxy);
  }

  @Override
  public synchronized void onUnbound(Status reason) {
    shutdownInternal(reason, true);
  }

  @CheckReturnValue
  @Override
  public synchronized Runnable start(Listener clientTransportListener) {
    this.clientTransportListener = checkNotNull(clientTransportListener);
    return () -> {
      synchronized (BinderClientTransport.this) {
        if (inState(TransportState.NOT_STARTED)) {
          setState(TransportState.SETUP);
          try {
            if (preAuthorizeServer) {
              preAuthorize(serviceBinding.resolve());
            } else {
              serviceBinding.bind();
            }
          } catch (StatusException e) {
            shutdownInternal(e.getStatus(), true);
            return;
          }
          if (readyTimeoutMillis >= 0) {
            readyTimeoutFuture =
                getScheduledExecutorService()
                    .schedule(
                        BinderClientTransport.this::onReadyTimeout,
                        readyTimeoutMillis,
                        MILLISECONDS);
          }
        }
      }
    };
  }

  @GuardedBy("this")
  private void preAuthorize(ServiceInfo serviceInfo) {
    // It's unlikely, but the identity/existence of this Service could change by the time we
    // actually connect. It doesn't matter though, because:
    // - If pre-auth fails (but would succeed against the server's new state), the grpc-core layer
    // will eventually retry using a new transport instance that will see the Service's new state.
    // - If pre-auth succeeds (but would fail against the server's new state), we might give an
    // unauthorized server a chance to run, but the connection will still fail by SecurityPolicy
    // check later in handshake. Pre-auth remains effective at mitigating abuse because malware
    // can't typically control the exact timing of its installation.
    preAuthResultFuture = checkServerAuthorizationAsync(serviceInfo.applicationInfo.uid);
    Futures.addCallback(
        preAuthResultFuture,
        new FutureCallback<Status>() {
          @Override
          public void onSuccess(Status result) {
            handlePreAuthResult(result);
          }

          @Override
          public void onFailure(Throwable t) {
            handleAuthResult(t);
          }
        },
        offloadExecutor);
  }

  private synchronized void handlePreAuthResult(Status authorization) {
    if (inState(TransportState.SETUP)) {
      if (!authorization.isOk()) {
        shutdownInternal(authorization, true);
      } else {
        serviceBinding.bind();
      }
    }
  }

  private synchronized void onReadyTimeout() {
    if (inState(TransportState.SETUP)) {
      readyTimeoutFuture = null;
      shutdownInternal(
          Status.DEADLINE_EXCEEDED.withDescription(
              "Connect timeout " + readyTimeoutMillis + "ms lapsed"),
          true);
    }
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
  void notifyShutdown(Status status) {
    clientTransportListener.transportShutdown(status);
  }

  @Override
  @GuardedBy("this")
  void notifyTerminated() {
    if (numInUseStreams.getAndSet(0) > 0) {
      clientTransportListener.transportInUse(false);
    }
    if (readyTimeoutFuture != null) {
      readyTimeoutFuture.cancel(false);
      readyTimeoutFuture = null;
    }
    if (preAuthResultFuture != null) {
      preAuthResultFuture.cancel(false); // No effect if already complete.
    }
    if (authResultFuture != null) {
      authResultFuture.cancel(false); // No effect if already complete.
    }
    serviceBinding.unbind();
    clientTransportListener.transportTerminated();
  }

  @Override
  @GuardedBy("this")
  protected void handleSetupTransport(Parcel parcel) {
    if (inState(TransportState.SETUP)) {
      int version = parcel.readInt();
      IBinder binder = parcel.readStrongBinder();
      if (version != WIRE_FORMAT_VERSION) {
        shutdownInternal(Status.UNAVAILABLE.withDescription("Wire format version mismatch"), true);
      } else if (binder == null) {
        shutdownInternal(
            Status.UNAVAILABLE.withDescription("Malformed SETUP_TRANSPORT data"), true);
      } else {
        OneWayBinderProxy binderProxy = OneWayBinderProxy.wrap(binder, offloadExecutor);
        handshake.handleSetupTransport(binderProxy);
      }
    }
  }

  private ListenableFuture<Status> checkServerAuthorizationAsync(int remoteUid) {
    return (securityPolicy instanceof AsyncSecurityPolicy)
        ? ((AsyncSecurityPolicy) securityPolicy).checkAuthorizationAsync(remoteUid)
        : Futures.submit(() -> securityPolicy.checkAuthorization(remoteUid), offloadExecutor);
  }

  class LegacyClientHandshake implements ClientHandshake {
    @Override
    @MainThread
    @GuardedBy("BinderClientTransport.this")
    public void onBound(OneWayBinderProxy binder) {
      sendSetupTransaction(binder);
    }

    @Override
    @BinderThread
    @GuardedBy("BinderClientTransport.this")
    public void handleSetupTransport(OneWayBinderProxy binder) {
      int remoteUid = Binder.getCallingUid();
      attributes = setSecurityAttrs(attributes, remoteUid);
      authResultFuture = checkServerAuthorizationAsync(remoteUid);
      Futures.addCallback(
          authResultFuture,
          new FutureCallback<Status>() {
            @Override
            public void onSuccess(Status result) {
              synchronized (BinderClientTransport.this) {
                handleAuthResult(binder, result);
              }
            }

            @Override
            public void onFailure(Throwable t) {
              BinderClientTransport.this.handleAuthResult(t);
            }
          },
          offloadExecutor);
    }

    @GuardedBy("BinderClientTransport.this")
    private void handleAuthResult(OneWayBinderProxy binder, Status authorization) {
      if (inState(TransportState.SETUP)) {
        if (!authorization.isOk()) {
          shutdownInternal(authorization, true);
        } else if (!setOutgoingBinder(binder)) {
          shutdownInternal(
              Status.UNAVAILABLE.withDescription("Failed to observe outgoing binder"), true);
        } else {
          // Check state again, since a failure inside setOutgoingBinder (or a callback it
          // triggers), could have shut us down.
          if (!isShutdown()) {
            onHandshakeComplete();
          }
        }
      }
    }
  }

  @GuardedBy("this")
  private void onHandshakeComplete() {
    setState(TransportState.READY);
    attributes = clientTransportListener.filterTransport(attributes);
    clientTransportListener.transportReady();
    if (readyTimeoutFuture != null) {
      readyTimeoutFuture.cancel(false);
      readyTimeoutFuture = null;
    }
  }

  private synchronized void handleAuthResult(Throwable t) {
    shutdownInternal(
        Status.INTERNAL.withDescription("Could not evaluate SecurityPolicy").withCause(t), true);
  }

  @GuardedBy("this")
  @Override
  protected void handlePingResponse(Parcel parcel) {
    pingTracker.onPingResponse(parcel.readInt());
  }

  /**
   * An abstraction of the client handshake, used to transition off a problematic legacy approach.
   */
  interface ClientHandshake {
    /**
     * Notifies the implementation that the binding has succeeded and we are now connected to the
     * server 'endpointBinder'.
     */
    @GuardedBy("this")
    @MainThread
    void onBound(OneWayBinderProxy endpointBinder);

    /**
     * Notifies the implementation that we've received a valid SETUP_TRANSPORT transaction from a
     * server that can be reached at 'serverBinder'.
     */
    @GuardedBy("this")
    @BinderThread
    void handleSetupTransport(OneWayBinderProxy serverBinder);
  }

  private static ClientStream newFailingClientStream(
      Status failure, Attributes attributes, Metadata headers, ClientStreamTracer[] tracers) {
    StatsTraceContext statsTraceContext =
        StatsTraceContext.newClientContext(tracers, attributes, headers);
    statsTraceContext.clientOutboundHeaders();
    return new FailingClientStream(failure, tracers);
  }

  private static InternalLogId buildLogId(
      Context sourceContext, AndroidComponentAddress targetAddress) {
    return InternalLogId.allocate(
        BinderClientTransport.class,
        sourceContext.getClass().getSimpleName() + "->" + targetAddress);
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
