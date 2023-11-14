package io.grpc.binder.internal;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.internal.ObjectPool;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
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

  /**
   * @param authStatusFuture a ListenableFuture holding the result status of the authorization
   *                         policy from a {@link io.grpc.binder.SecurityPolicy} or a
   *                         {@link io.grpc.binder.AsyncSecurityPolicy}. The call only progresses
   *                         if {@link Status#isOk()} is true.
   * @param executorPool     a pool that can provide at least one Executor under which the result
   *                         of {@code authStatusFuture} can be handled, progressing the gRPC
   *                         stages.
   * @param call             the 'call' parameter from {@link io.grpc.ServerInterceptor}
   * @param headers          the 'headers' parameter from {@link io.grpc.ServerInterceptor}
   * @param next             the 'next' parameter from {@link io.grpc.ServerInterceptor}
   */
  static <ReqT, RespT> PendingAuthListener<ReqT, RespT> create(
      ListenableFuture<Status> authStatusFuture,
      ObjectPool<? extends Executor> executorPool,
      ServerCall<ReqT, RespT> call,
      Metadata headers,
      ServerCallHandler<ReqT, RespT> next) {
    PendingAuthListener<ReqT, RespT> listener = new PendingAuthListener<>();
    Executor executor = executorPool.getObject();
    Futures.addCallback(
        authStatusFuture,
        new FutureCallback<Status>() {
          @Override
          public void onSuccess(Status authStatus) {
            try {
              if (!authStatus.isOk()) {
                call.close(authStatus, new Metadata());
                return;
              }

              listener.startCall(call, headers, next);
            } finally {
              executorPool.returnObject(executor);
            }
          }

          @Override
          public void onFailure(Throwable t) {
            call.close(
                Status.INTERNAL.withCause(t).withDescription("Authorization future failed"),
                new Metadata());
            executorPool.returnObject(executor);
          }
        }, executor);
    return listener;
  }

  private PendingAuthListener() {}

  private void startCall(ServerCall<ReqT, RespT> call,
                         Metadata headers,
                         ServerCallHandler<ReqT, RespT> next) {
    ServerCall.Listener<ReqT> delegate;
    try {
      delegate = next.startCall(call, headers);
    } catch (Exception e) {
      call.close(
          Status
              .INTERNAL
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
