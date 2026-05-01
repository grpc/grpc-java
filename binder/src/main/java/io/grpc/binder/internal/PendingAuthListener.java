package io.grpc.binder.internal;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * A {@link ServerCall.Listener} that can be returned by a {@link io.grpc.ServerInterceptor} to
 * asynchronously advance the gRPC pending resolving a possibly asynchronous security policy check.
 */
final class PendingAuthListener<ReqT, RespT> extends ServerCall.Listener<ReqT> {

  private final ConcurrentLinkedQueue<ListenerConsumer<ReqT>> pendingSteps =
      new ConcurrentLinkedQueue<>();
  private final AtomicReference<ServerCall.Listener<ReqT>> delegateRef =
      new AtomicReference<>(null);

  PendingAuthListener() {}

  void startCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    ServerCall.Listener<ReqT> delegate;
    try {
      delegate = next.startCall(call, headers);
    } catch (RuntimeException e) {
      call.close(
          Status.INTERNAL
              .withCause(e)
              .withDescription("Failed to start server call after authorization check"),
          new Metadata());
      return;
    }
    delegateRef.set(delegate);
    maybeRunPendingSteps();
  }

  /**
   * Runs any enqueued step in this ServerCall listener as long as the authorization check is
   * complete. Otherwise, no-op and returns immediately.
   */
  private void maybeRunPendingSteps() {
    @Nullable ServerCall.Listener<ReqT> delegate = delegateRef.get();
    if (delegate == null) {
      return;
    }

    // This section is synchronized so that no 2 threads may attempt to retrieve elements from the
    // queue in order but end up executing the steps out of order.
    synchronized (this) {
      ListenerConsumer<ReqT> nextStep;
      while ((nextStep = pendingSteps.poll()) != null) {
        nextStep.accept(delegate);
      }
    }
  }

  @Override
  public void onCancel() {
    pendingSteps.offer(ServerCall.Listener::onCancel);
    maybeRunPendingSteps();
  }

  @Override
  public void onComplete() {
    pendingSteps.offer(ServerCall.Listener::onComplete);
    maybeRunPendingSteps();
  }

  @Override
  public void onHalfClose() {
    pendingSteps.offer(ServerCall.Listener::onHalfClose);
    maybeRunPendingSteps();
  }

  @Override
  public void onMessage(ReqT message) {
    pendingSteps.offer(delegate -> delegate.onMessage(message));
    maybeRunPendingSteps();
  }

  @Override
  public void onReady() {
    pendingSteps.offer(ServerCall.Listener::onReady);
    maybeRunPendingSteps();
  }

  /**
   * Similar to Java8's {@link java.util.function.Consumer}, but redeclared in order to support
   * Android SDK 21.
   */
  private interface ListenerConsumer<ReqT> {
    void accept(ServerCall.Listener<ReqT> listener);
  }
}
