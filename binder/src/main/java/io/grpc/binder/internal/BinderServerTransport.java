package io.grpc.binder.internal;

import android.os.IBinder;
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
  @Nullable private ServerTransportListener serverTransportListener;

  /**
   * Constructs a new transport instance.
   *
   * @param binderDecorator used to decorate 'callbackBinder', for fault injection.
   */
  public BinderServerTransport(
      ObjectPool<ScheduledExecutorService> executorServicePool,
      Attributes attributes,
      List<ServerStreamTracer.Factory> streamTracerFactories,
      OneWayBinderProxy.Decorator binderDecorator,
      IBinder callbackBinder) {
    super(executorServicePool, attributes, binderDecorator, buildLogId(attributes));
    this.streamTracerFactories = streamTracerFactories;
    // TODO(jdcormie): Plumb in the Server's executor() and use it here instead.
    setOutgoingBinder(OneWayBinderProxy.wrap(callbackBinder, getScheduledExecutorService()));
  }

  public synchronized void setServerTransportListener(
      ServerTransportListener serverTransportListener) {
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
  void notifyShutdown(Status status) {
    // Nothing to do.
  }

  @Override
  @GuardedBy("this")
  void notifyTerminated() {
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
        io.grpc.binder.internal.BinderServerTransport.class,
        "from " + attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR));
  }
}
