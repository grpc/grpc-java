package io.grpc.binder.internal;

import static com.google.common.base.Preconditions.checkState;

import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.internal.ServerListener;
import io.grpc.internal.ServerStream;
import io.grpc.internal.ServerTransport;
import io.grpc.internal.ServerTransportListener;
import javax.annotation.concurrent.GuardedBy;

/**
 * Tracks which {@link BinderTransport.BinderServerTransport} are currently active and allows
 * invoking a {@link Runnable} only once all transports are terminated.
 */
final class ActiveTransportTracker implements ServerListener {
  private final ServerListener delegate;
  private final Runnable terminationListener;

  @GuardedBy("this")
  private boolean shutdown = false;

  @GuardedBy("this")
  private int activeTransportCount = 0;

  /**
   * @param delegate the original server listener that this object decorates. Usually passed to
   *     {@link BinderServer#start(ServerListener)}.
   * @param terminationListener invoked only once the server has started shutdown ({@link
   *     #serverShutdown()} AND the last active transport is terminated.
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
    Runnable maybeTerminationListener;
    synchronized (this) {
      activeTransportCount--;
      maybeTerminationListener = getListenerIfTerminated();
    }
    // Prefer running the listener outside of the synchronization lock to release it sooner, since
    // we don't know how the callback is implemented nor how long it will take. This should
    // minimize the possibility of deadlocks.
    if (maybeTerminationListener != null) {
      maybeTerminationListener.run();
    }
  }

  @Override
  public void serverShutdown() {
    delegate.serverShutdown();
    Runnable maybeTerminationListener;
    synchronized (this) {
      shutdown = true;
      maybeTerminationListener = getListenerIfTerminated();
    }
    // We may be able to shutdown immediately if there are no active transports.
    //
    // Executed outside of the lock. See "untrack()" above.
    if (maybeTerminationListener != null) {
      maybeTerminationListener.run();
    }
  }

  @GuardedBy("this")
  private Runnable getListenerIfTerminated() {
    return (shutdown && activeTransportCount == 0) ? terminationListener : null;
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
      delegate.transportTerminated();
      untrack();
    }
  }
}
