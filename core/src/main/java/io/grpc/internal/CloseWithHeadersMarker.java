package io.grpc.internal;

import io.grpc.Status;

/**
 * Marker to be used in {@link Status#withCause(Throwable)} to signal that stream should be closed
 * by sending headers.
 */
public class CloseWithHeadersMarker extends RuntimeException {

  @Override
  public synchronized Throwable fillInStackTrace() {
    return this;
  }
}
