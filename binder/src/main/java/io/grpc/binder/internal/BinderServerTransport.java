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

import android.os.IBinder;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Internal;
import io.grpc.InternalLogId;
import io.grpc.Metadata;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServerStream;
import io.grpc.internal.ServerTransport;
import io.grpc.internal.ServerTransportListener;
import io.grpc.internal.StatsTraceContext;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;

/** Concrete server-side transport implementation. */
@Internal
public final class BinderServerTransport extends BinderTransport implements ServerTransport {

  private final List<ServerStreamTracer.Factory> streamTracerFactories;

  @GuardedBy("this")
  private final SimplePromise<ServerTransportListener> listenerPromise = new SimplePromise<>();

  private BinderServerTransport(
      ObjectPool<ScheduledExecutorService> executorServicePool,
      Attributes attributes,
      List<ServerStreamTracer.Factory> streamTracerFactories,
      OneWayBinderProxy.Decorator binderDecorator,
      LeakSafeOneWayBinder.Decorator inboundBinderDecorator) {
    super(
        executorServicePool,
        attributes,
        binderDecorator,
        inboundBinderDecorator,
        buildLogId(attributes));
    this.streamTracerFactories = streamTracerFactories;
  }

  /**
   * Constructs a new transport instance.
   *
   * @param binderDecorator used to decorate 'callbackBinder', for fault injection.
   */
  private static BinderServerTransport create(Builder builder) {
    BinderServerTransport transport =
        new BinderServerTransport(
            checkNotNull(builder.executorServicePool, "executorServicePool"),
            builder.attributes,
            builder.streamTracerFactories,
            builder.binderDecorator,
            builder.inboundBinderDecorator);
    // TODO(jdcormie): Plumb in the Server's executor() and use it here instead.
    // No need to handle failure here because if 'callbackBinder' is already dead, we'll notice it
    // again in start() when we send the first transaction.
    synchronized (transport) {
      transport.setOutgoingBinder(
          OneWayBinderProxy.wrap(
              checkNotNull(builder.callbackBinder, "callbackBinder"),
              transport.getScheduledExecutorService()));
    }
    return transport;
  }

  /**
   * Initializes this transport instance.
   *
   * <p>Must be called exactly once, even if {@link #shutdown} or {@link #shutdownNow} was called
   * first.
   *
   * @param serverTransportListener where this transport will report events
   */
  public synchronized void start(ServerTransportListener serverTransportListener) {
    this.listenerPromise.set(serverTransportListener);
    if (isShutdown()) {
      // It's unlikely, but we could be shutdown externally between construction and start(). One
      // possible cause is an extremely short handshake timeout.
      return;
    }

    sendSetupTransaction();

    // Check we're not shutdown again, since a failure inside sendSetupTransaction (or a callback
    // it triggers), could have shut us down.
    if (isShutdown()) {
      return;
    }

    setState(TransportState.READY);
    attributes = serverTransportListener.transportReady(attributes);
  }

  StatsTraceContext createStatsTraceContext(String methodName, Metadata headers) {
    return StatsTraceContext.newServerContext(streamTracerFactories, methodName, headers);
  }

  /**
   * Reports a new ServerStream requested by the remote client.
   *
   * <p>Precondition: {@link #start(ServerTransportListener)} must already have been called.
   */
  synchronized Status startStream(ServerStream stream, String methodName, Metadata headers) {
    if (isShutdown()) {
      return Status.UNAVAILABLE.withDescription("transport is shutdown");
    }

    listenerPromise.get().streamCreated(stream, methodName, headers);
    return Status.OK;
  }

  @Override
  @GuardedBy("this")
  void notifyShutdown(Status status) {
    // Nothing to do.
  }

  @Override
  @GuardedBy("this")
  void notifyTerminated() {
    listenerPromise.runWhenSet(ServerTransportListener::transportTerminated);
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
  protected Inbound<?, ?> createInbound(int callId) {
    return new Inbound.ServerInbound(this, attributes, callId);
  }

  private static InternalLogId buildLogId(Attributes attributes) {
    return InternalLogId.allocate(
        BinderServerTransport.class, "from " + attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR));
  }

  /** Builder for {@link BinderServerTransport} instances. */
  public static final class Builder {
    private ObjectPool<ScheduledExecutorService> executorServicePool;
    private Attributes attributes = Attributes.EMPTY;
    private List<ServerStreamTracer.Factory> streamTracerFactories = ImmutableList.of();
    private OneWayBinderProxy.Decorator binderDecorator = OneWayBinderProxy.IDENTITY_DECORATOR;
    private LeakSafeOneWayBinder.Decorator inboundBinderDecorator = LeakSafeOneWayBinder.IDENTITY_DECORATOR;
    private IBinder callbackBinder;

    public Builder() {}

    public Builder setExecutorServicePool(ObjectPool<ScheduledExecutorService> executorServicePool) {
      this.executorServicePool = checkNotNull(executorServicePool, "executorServicePool");
      return this;
    }

    public Builder setAttributes(Attributes attributes) {
      this.attributes = checkNotNull(attributes, "attributes");
      return this;
    }

    public Builder setStreamTracerFactories(List<ServerStreamTracer.Factory> streamTracerFactories) {
      this.streamTracerFactories = checkNotNull(streamTracerFactories, "streamTracerFactories");
      return this;
    }

    public Builder setBinderDecorator(OneWayBinderProxy.Decorator binderDecorator) {
      this.binderDecorator = checkNotNull(binderDecorator, "binderDecorator");
      return this;
    }

    public Builder setInboundBinderDecorator(LeakSafeOneWayBinder.Decorator inboundBinderDecorator) {
      this.inboundBinderDecorator = checkNotNull(inboundBinderDecorator, "inboundBinderDecorator");
      return this;
    }

    public Builder setCallbackBinder(IBinder callbackBinder) {
      this.callbackBinder = checkNotNull(callbackBinder, "callbackBinder");
      return this;
    }

    public BinderServerTransport build() {
      return BinderServerTransport.create(this);
    }
  }
}
