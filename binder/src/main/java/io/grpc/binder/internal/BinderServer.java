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

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalInstrumented;
import io.grpc.ServerStreamTracer;
import io.grpc.binder.AndroidComponentAddress;
import io.grpc.binder.InboundParcelablePolicy;
import io.grpc.binder.ServerSecurityPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServerListener;
import io.grpc.internal.SharedResourceHolder;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A gRPC InternalServer which accepts connections via a host AndroidService.
 *
 * <p>Multiple incoming connections transports may be active at a time.
 *
 * <b>IMPORTANT</b>: This implementation must comply with this published wire format.
 * https://github.com/grpc/proposal/blob/master/L73-java-binderchannel/wireformat.md
 */
@ThreadSafe
public final class BinderServer implements InternalServer, LeakSafeOneWayBinder.TransactionHandler {

  private final ObjectPool<ScheduledExecutorService> executorServicePool;
  private final ImmutableList<ServerStreamTracer.Factory> streamTracerFactories;
  private final AndroidComponentAddress listenAddress;
  private final LeakSafeOneWayBinder hostServiceBinder;
  private final ServerSecurityPolicy serverSecurityPolicy;
  private final InboundParcelablePolicy inboundParcelablePolicy;

  @GuardedBy("this")
  private ServerListener listener;

  @GuardedBy("this")
  private ScheduledExecutorService executorService;

  @GuardedBy("this")
  private boolean shutdown;

  public BinderServer(
      AndroidComponentAddress listenAddress,
      ObjectPool<ScheduledExecutorService> executorServicePool,
      List<? extends ServerStreamTracer.Factory> streamTracerFactories,
      ServerSecurityPolicy serverSecurityPolicy,
      InboundParcelablePolicy inboundParcelablePolicy) {
    this.listenAddress = listenAddress;
    this.executorServicePool = executorServicePool;
    this.streamTracerFactories =
        ImmutableList.copyOf(checkNotNull(streamTracerFactories, "streamTracerFactories"));
    this.serverSecurityPolicy = checkNotNull(serverSecurityPolicy, "serverSecurityPolicy");
    this.inboundParcelablePolicy = inboundParcelablePolicy;
    hostServiceBinder = new LeakSafeOneWayBinder(this);
  }

  /** Return the binder we're listening on. */
  public IBinder getHostBinder() {
    return hostServiceBinder;
  }

  @Override
  public synchronized void start(ServerListener serverListener) throws IOException {
    this.listener = serverListener;
    executorService = executorServicePool.getObject();
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
      hostServiceBinder.detach();
      listener.serverShutdown();
      executorService = executorServicePool.returnObject(executorService);
    }
  }

  @Override
  public String toString() {
    return "BinderServer[" + listenAddress + "]";
  }

  @Override
  public synchronized boolean handleTransaction(int code, Parcel parcel) {
    if (code == BinderTransport.SETUP_TRANSPORT) {
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
          BinderTransportSecurity.attachAuthAttrs(attrsBuilder, callingUid, serverSecurityPolicy);
          // Create a new transport and let our listener know about it.
          BinderTransport.BinderServerTransport transport =
              new BinderTransport.BinderServerTransport(
                  executorServicePool, attrsBuilder.build(), streamTracerFactories, callbackBinder);
          transport.setServerTransportListener(listener.transportCreated(transport));
          return true;
        }
      }
    }
    return false;
  }
}
