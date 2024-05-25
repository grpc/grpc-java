package io.grpc.binder.internal;

import static com.google.common.base.Preconditions.checkState;

import java.util.HashSet;
import javax.annotation.concurrent.GuardedBy;
import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.internal.ServerStream;
import io.grpc.internal.ServerTransportListener;

/**
 * Tracks which {@link BinderTransport.BinderServerTransport} are currently active and allows
 * invoking a {@link BinderTransportSecurity.TerminationListener} only once all transports are
 * terminated.
 */
final class ActiveTransportTracker {
  @GuardedBy("this")
  private final HashSet<BinderTransport.BinderServerTransport> activeTransports = new HashSet<>();
  private final BinderTransportSecurity.TerminationListener transportSecurityTerminationListener;
  @GuardedBy("this")
  private boolean awaitingNoActiveTransports = false;
  @GuardedBy("this")
  private boolean terminationNotificationRequested = false;

  /**
   * @param transportSecurityTerminationListener invoked only once scheduled via
   * {@link #scheduleTerminationNotification()} AND the last active transport is terminated.
   */
  ActiveTransportTracker(
      BinderTransportSecurity.TerminationListener transportSecurityTerminationListener) {
    this.transportSecurityTerminationListener = transportSecurityTerminationListener;
  }

  /**
   * @throws IllegalStateException if {@link #scheduleTerminationNotification()} has already been
   * called.
   */
  ServerTransportListener track(
      ServerTransportListener listener,
      BinderTransport.BinderServerTransport binderServerTransport) {
    synchronized (this) {
      checkState(
          !terminationNotificationRequested,
          "Attempting to track a new BinderServerTransport, but termination notice has already " +
              "been scheduled.");
      activeTransports.add(binderServerTransport);
    }
    return new TrackedTransportListener(listener, binderServerTransport);
  }

  private void untrack(BinderTransport.BinderServerTransport binderServerTransport) {
    synchronized (this) {
      activeTransports.remove(binderServerTransport);
      if (awaitingNoActiveTransports && activeTransports.isEmpty()) {
        transportSecurityTerminationListener.onServerTerminated();
      }
    }
  }

  /**
   * Schedules this tracker to notify the {@link BinderTransportSecurity.TerminationListener}
   * provided in the constructor once all remaining active transports have terminated. The
   * notification may happen immediately if there are no active transports.
   *
   * <p>There is no guarantee about which thread the TerminationListener will be called from.
   */
  void scheduleTerminationNotification() {
    synchronized (this) {
      terminationNotificationRequested = true;

      // We can run immediately
      if (activeTransports.isEmpty()) {
        transportSecurityTerminationListener.onServerTerminated();
        return;
      }

      // Schedule for later, as soon as the last active transport is terminated.
      awaitingNoActiveTransports = true;
    }
  }

  /**
   * Wraps a {@link ServerTransportListener}, unregistering it from the parent tracker once the
   * transport terminates.
   */
  private final class TrackedTransportListener implements ServerTransportListener {
    private final ServerTransportListener delegate;
    private final BinderTransport.BinderServerTransport binderServerTransport;

    TrackedTransportListener(
        ServerTransportListener delegate,
        BinderTransport.BinderServerTransport binderServerTransport) {
      this.delegate = delegate;
      this.binderServerTransport = binderServerTransport;
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
      untrack(binderServerTransport);
      delegate.transportTerminated();
    }
  }
}
