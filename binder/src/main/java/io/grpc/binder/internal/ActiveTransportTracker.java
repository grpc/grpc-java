package io.grpc.binder.internal;

import static com.google.common.base.Preconditions.checkState;

import javax.annotation.concurrent.GuardedBy;
import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.internal.ServerListener;
import io.grpc.internal.ServerStream;
import io.grpc.internal.ServerTransport;
import io.grpc.internal.ServerTransportListener;

/**
 * Tracks which {@link BinderTransport.BinderServerTransport} are currently active and allows
 * invoking a {@link Runnable} only once all transports are terminated.
 */
final class ActiveTransportTracker implements ServerListener  {
  private final ServerListener delegate;
  private final Runnable terminationListener;
  @GuardedBy("this")
  private boolean shutdown = false;
  @GuardedBy("this")
  private int activeTransportCount = 0;

  /**
   * @param delegate the original server listener that this object decorates. Usually passed to
   *   {@link BinderServer#start(ServerListener)}.
   * @param terminationListener invoked only once the server has started shutdown
   *   ({@link #serverShutdown()} AND the last active transport is terminated.
   */
  ActiveTransportTracker(ServerListener delegate, Runnable terminationListener) {
    this.delegate = delegate;
    this.terminationListener = terminationListener;
  }

  @Override
  public ServerTransportListener transportCreated(ServerTransport transport) {
    synchronized (this) {
      checkState(!shutdown, "Illegal transportCreated() after serverShutdown()");
      activeTransportCount++;
    }
    ServerTransportListener originalListener = delegate.transportCreated(transport);
    return new TrackedTransportListener(originalListener);
  }

  private void untrack() {
    synchronized (this) {
      activeTransportCount--;
      maybeInvokeTerminationCallback();
    }
  }

  @Override
  public void serverShutdown() {
    delegate.serverShutdown();
    synchronized (this) {
      shutdown = true;
      // We may be able to shutdown immediately if there are no active transports.
      maybeInvokeTerminationCallback();
    }
  }

  /** Invokes the termination listener iff a shutdown has been requested (via
   * {@link #serverShutdown()} and there are no active transports. */
  private void maybeInvokeTerminationCallback() {
    synchronized (this) {
      if (shutdown && activeTransportCount == 0) {
        terminationListener.run();
      }
    }
  }

  /**
   * Wraps a {@link ServerTransportListener}, unregistering it from the parent tracker once the
   * transport terminates.
   */
  private final class TrackedTransportListener implements ServerTransportListener {
    private final ServerTransportListener delegate;

    TrackedTransportListener(ServerTransportListener delegate) {
      this.delegate = delegate;
    }

    @Override
    public void streamCreated(ServerStream stream, String method, Metadata headers) {
      delegate.streamCreated(stream, method, headers);
    }

    @Override
    public Attributes transportReady(Attributes attributes) {
      return delegate.transportReady(attributes);
    }

    @Override
    public void transportTerminated() {
      untrack();
      delegate.transportTerminated();
    }
  }
}
