package io.grpc.binder.internal;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.internal.ObjectPool;

import java.util.concurrent.Executor;

/**
 * A {@link ServerCall.Listener} that can be returned by a {@link io.grpc.ServerInterceptor} to
 * asynchronously advance the gRPC pending resolving a possibly asynchronous security policy check.
 */
final class PendingAuthListener<ReqT, RespT> extends ServerCall.Listener<ReqT> {

    private final ListenableFuture<Listener<ReqT>> authStatusFuture;
    private final Executor sequentialExecutor;
    private final ObjectPool<? extends Executor> executorPool;
    private final Executor executor;

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
    PendingAuthListener(
            ListenableFuture<Status> authStatusFuture,
            ObjectPool<? extends Executor> executorPool,
            ServerCall<ReqT, RespT> call, Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        this.executorPool = executorPool;
        this.executor = executorPool.getObject();
        this.authStatusFuture = Futures.transform(authStatusFuture, authStatus -> {
            if (authStatus.isOk()) {
                return next.startCall(call, headers);
            }
            call.close(authStatus, new Metadata());
            throw new IllegalStateException("Auth failed", authStatus.asException());
        }, executor);
        this.sequentialExecutor = MoreExecutors.newSequentialExecutor(executor);
    }

    @Override
    public void onCancel() {
        ListenableFuture<?> unused = Futures.transform(authStatusFuture, delegate -> {
            delegate.onCancel();
            executorPool.returnObject(executor);
            return null;
        }, sequentialExecutor);
    }

    @Override
    public void onComplete() {
        ListenableFuture<?> unused = Futures.transform(authStatusFuture, delegate -> {
            delegate.onComplete();
            executorPool.returnObject(executor);
            return null;
        }, sequentialExecutor);
    }

    @Override
    public void onHalfClose() {
        ListenableFuture<?> unused = Futures.transform(authStatusFuture, delegate -> {
            delegate.onHalfClose();
            return null;
        }, sequentialExecutor);
    }

    @Override
    public void onMessage(ReqT message) {
        ListenableFuture<?> unused = Futures.transform(authStatusFuture, delegate -> {
            delegate.onMessage(message);
            return null;
        }, sequentialExecutor);
    }

    @Override
    public void onReady() {
        ListenableFuture<?> unused = Futures.transform(authStatusFuture, delegate -> {
            delegate.onReady();
            return null;
        }, sequentialExecutor);
    }
}
