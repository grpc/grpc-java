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

import static android.os.IBinder.FLAG_ONEWAY;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.binder.internal.BinderTransport.SHUTDOWN_TRANSPORT;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalInstrumented;
import io.grpc.ServerStreamTracer;
import io.grpc.binder.AndroidComponentAddress;
import io.grpc.binder.BinderInternal;
import io.grpc.binder.InboundParcelablePolicy;
import io.grpc.binder.SecurityPolicies;
import io.grpc.binder.ServerSecurityPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServerListener;
import io.grpc.internal.SharedResourcePool;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A gRPC InternalServer which accepts connections via a host AndroidService.
 *
 * <p>Multiple incoming connections transports may be active at a time.
 *
 * <p><b>IMPORTANT</b>: This implementation must comply with this published wire format.
 * https://github.com/grpc/proposal/blob/master/L73-java-binderchannel/wireformat.md
 */
@ThreadSafe
public final class BinderServer implements InternalServer, LeakSafeOneWayBinder.TransactionHandler {
  private static final Logger logger = Logger.getLogger(BinderServer.class.getName());

  private final ObjectPool<ScheduledExecutorService> executorServicePool;
  private final ObjectPool<? extends Executor> executorPool;
  private final ImmutableList<ServerStreamTracer.Factory> streamTracerFactories;
  private final AndroidComponentAddress listenAddress;
  private final LeakSafeOneWayBinder hostServiceBinder;
  private final BinderTransportSecurity.ServerPolicyChecker serverPolicyChecker;
  private final InboundParcelablePolicy inboundParcelablePolicy;

  @GuardedBy("this")
  private ServerListener listener;

  @GuardedBy("this")
  private ScheduledExecutorService executorService;

  @Nullable // Before start() and after termination.
  @GuardedBy("this")
  private Executor executor;

  @GuardedBy("this")
  private boolean shutdown;

  private BinderServer(Builder builder) {
    this.listenAddress = checkNotNull(builder.listenAddress);
    this.executorPool = checkNotNull(builder.executorPool);
    this.executorServicePool = builder.executorServicePool;
    this.streamTracerFactories =
        ImmutableList.copyOf(checkNotNull(builder.streamTracerFactories, "streamTracerFactories"));
    this.serverPolicyChecker = BinderInternal.createPolicyChecker(builder.serverSecurityPolicy);
    this.inboundParcelablePolicy = builder.inboundParcelablePolicy;
    hostServiceBinder = new LeakSafeOneWayBinder(this);
  }

  /** Return the binder we're listening on. */
  public IBinder getHostBinder() {
    return hostServiceBinder;
  }

  @Override
  public synchronized void start(ServerListener serverListener) throws IOException {
    listener = new ActiveTransportTracker(serverListener, this::onTerminated);
    executorService = executorServicePool.getObject();
    executor = executorPool.getObject();
  }

  @Override
  public SocketAddress getListenSocketAddress() {
    return listenAddress;
  }

  @Override
  public List<? extends SocketAddress> getListenSocketAddresses() {
    return ImmutableList.of(listenAddress);
  }

  @Override
  public InternalInstrumented<SocketStats> getListenSocketStats() {
    return null;
  }

  @Override
  @Nullable
  public List<InternalInstrumented<SocketStats>> getListenSocketStatsList() {
    return null;
  }

  @Override
  public synchronized void shutdown() {
    if (!shutdown) {
      shutdown = true;
      // Break the connection to the binder. We'll receive no more transactions.
      hostServiceBinder.setHandler(GoAwayHandler.INSTANCE);
      listener.serverShutdown();
      // TODO(jdcormie): Shouldn't this happen in onTerminated()? Is this even used anywhere?
      executorService = executorServicePool.returnObject(executorService);
    }
  }

  private synchronized void onTerminated() {
    executor = executorPool.returnObject(executor);
  }

  @Override
  public String toString() {
    return "BinderServer[" + listenAddress + "]";
  }

  @Override
  public synchronized boolean handleTransaction(int code, Parcel parcel) {
    if (code == BinderTransport.SETUP_TRANSPORT) {
      if (shutdown) {
        // An incoming SETUP_TRANSPORT transaction may have already been in-flight when we removed
        // ourself as TransactionHandler in #shutdown(). So we must check for shutdown again here.
        return GoAwayHandler.INSTANCE.handleTransaction(code, parcel);
      }

      int version = parcel.readInt();
      // If the client-provided version is more recent, we accept the connection,
      // but specify the older version which we support.
      if (version >= BinderTransport.EARLIEST_SUPPORTED_WIRE_FORMAT_VERSION) {
        IBinder callbackBinder = parcel.readStrongBinder();
        if (callbackBinder != null) {
          int callingUid = Binder.getCallingUid();
          Attributes.Builder attrsBuilder =
              Attributes.newBuilder()
                  .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, listenAddress)
                  .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, new BoundClientAddress(callingUid))
                  .set(BinderTransport.REMOTE_UID, callingUid)
                  .set(BinderTransport.SERVER_AUTHORITY, listenAddress.getAuthority())
                  .set(BinderTransport.INBOUND_PARCELABLE_POLICY, inboundParcelablePolicy);
          BinderTransportSecurity.attachAuthAttrs(
              attrsBuilder,
              callingUid,
              serverPolicyChecker,
              checkNotNull(executor, "Not started?"));
          // Create a new transport and let our listener know about it.
          BinderTransport.BinderServerTransport transport =
              new BinderTransport.BinderServerTransport(
                  executorServicePool,
                  attrsBuilder.build(),
                  streamTracerFactories,
                  OneWayBinderProxy.IDENTITY_DECORATOR,
                  callbackBinder);
          transport.setServerTransportListener(listener.transportCreated(transport));
          return true;
        }
      }
    }
    return false;
  }

  static final class GoAwayHandler implements LeakSafeOneWayBinder.TransactionHandler {
    static final GoAwayHandler INSTANCE = new GoAwayHandler();

    @Override
    public boolean handleTransaction(int code, Parcel parcel) {
      if (code == BinderTransport.SETUP_TRANSPORT) {
        int version = parcel.readInt();
        if (version >= BinderTransport.EARLIEST_SUPPORTED_WIRE_FORMAT_VERSION) {
          IBinder callbackBinder = parcel.readStrongBinder();
          try (ParcelHolder goAwayReply = ParcelHolder.obtain()) {
            // Send empty flags to avoid a memory leak linked to empty parcels (b/207778694).
            goAwayReply.get().writeInt(0);
            callbackBinder.transact(SHUTDOWN_TRANSPORT, goAwayReply.get(), null, FLAG_ONEWAY);
          } catch (RemoteException re) {
            logger.log(Level.WARNING, "Couldn't reply to post-shutdown() SETUP_TRANSPORT.", re);
          }
        }
      }
      return false;
    }
  }

  /** Fluent builder of {@link BinderServer} instances. */
  public static class Builder {
    @Nullable AndroidComponentAddress listenAddress;
    @Nullable List<? extends ServerStreamTracer.Factory> streamTracerFactories;
    @Nullable ObjectPool<? extends Executor> executorPool;

    ObjectPool<ScheduledExecutorService> executorServicePool =
        SharedResourcePool.forResource(GrpcUtil.TIMER_SERVICE);
    ServerSecurityPolicy serverSecurityPolicy = SecurityPolicies.serverInternalOnly();
    InboundParcelablePolicy inboundParcelablePolicy = InboundParcelablePolicy.DEFAULT;

    public BinderServer build() {
      return new BinderServer(this);
    }

    /**
     * Sets the "listen" address for this server.
     *
     * <p>This is somewhat of a grpc-java formality. Binder servers don't really listen, rather,
     * Android creates and destroys them according to client needs.
     *
     * <p>Required.
     */
    public Builder setListenAddress(AndroidComponentAddress listenAddress) {
      this.listenAddress = listenAddress;
      return this;
    }

    /**
     * Sets the source for {@link ServerStreamTracer}s that will be installed on all new streams.
     *
     * <p>Required.
     */
    public Builder setStreamTracerFactories(
        List<? extends ServerStreamTracer.Factory> streamTracerFactories) {
      this.streamTracerFactories = streamTracerFactories;
      return this;
    }

    /**
     * Sets the executor to be used for calling into the application.
     *
     * <p>Required.
     */
    public Builder setExecutorPool(ObjectPool<? extends Executor> executorPool) {
      this.executorPool = executorPool;
      return this;
    }

    /**
     * Sets the executor to be used for scheduling channel timers.
     *
     * <p>Optional. A process-wide default executor will be used if unset.
     */
    public Builder setExecutorServicePool(
        ObjectPool<ScheduledExecutorService> executorServicePool) {
      this.executorServicePool = checkNotNull(executorServicePool, "executorServicePool");
      return this;
    }

    /**
     * Sets the {@link ServerSecurityPolicy} to be used for built servers.
     *
     * <p>Optional, {@link SecurityPolicies#serverInternalOnly()} is the default.
     */
    public Builder setServerSecurityPolicy(ServerSecurityPolicy serverSecurityPolicy) {
      this.serverSecurityPolicy = checkNotNull(serverSecurityPolicy, "serverSecurityPolicy");
      return this;
    }

    /**
     * Sets the {@link InboundParcelablePolicy} to be used for built servers.
     *
     * <p>Optional, {@link InboundParcelablePolicy#DEFAULT} is the default.
     */
    public Builder setInboundParcelablePolicy(InboundParcelablePolicy inboundParcelablePolicy) {
      this.inboundParcelablePolicy =
          checkNotNull(inboundParcelablePolicy, "inboundParcelablePolicy");
      return this;
    }
  }
}
