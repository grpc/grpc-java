package io.grpc;

/**
 * Listens on the client transport life-cycle events. These filters do not have the capability
 * to modify the channels or transport life-cycle event behavior, but they can be useful hooks
 * for transport observability. Multiple filters may be registered to the client.
 */
@ExperimentalApi("https://gitub.com/grpc/grpc-java/issues/TODO")
public abstract class ClientTransportFilter {
  /**
   * Called when a transport is ready to accept traffic (when a connection has been established).
   * The default implementation is a no-op.
   */
  public void transportReady() {

  }

  /**
   * Called when a transport is shutting down. Shutdown could have been caused by an error or normal
   * operation.
   * This is called prior to {@link #transportTerminated}.
   * Default implementation is a no-op.
   *
   * @param s the reason for the shutdown.
   */
  public void transportShutdown(Status s) {

  }

  /**
   * Called when a transport completed shutting down. All resources have been released.
   * All streams have either been closed or transferred off this transport.
   * Default implementation is a no-op
   */
  public void transportTerminated() {

  }

}
