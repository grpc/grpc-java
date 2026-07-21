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

import static com.google.common.util.concurrent.Futures.nonCancellationPropagating;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.errorprone.annotations.CheckReturnValue;
import io.grpc.Attributes;
import io.grpc.Internal;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.SecurityLevel;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.internal.GrpcAttributes;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;

/**
 * Manages security for an Android Service hosted gRPC server.
 *
 * <p>Attaches authorization state to a newly-created transport, and contains a ServerInterceptor
 * which ensures calls are authorized before allowing them to proceed.
 */
public final class BinderTransportSecurity {

  private static final Attributes.Key<TransportAuthorizationState> TRANSPORT_AUTHORIZATION_STATE =
      Attributes.Key.create("internal:transport-authorization-state");

  private BinderTransportSecurity() {}

  /**
   * Install a security policy on an about-to-be created server.
   *
   * @param serverBuilder The ServerBuilder being used to create the server.
   */
  @Internal
  public static void installAuthInterceptor(ServerBuilder<?> serverBuilder) {
    serverBuilder.intercept(new ServerAuthInterceptor());
  }

  /**
   * Attach the given security policy to the transport attributes being built. Will be used by the
   * auth interceptor to confirm accept or reject calls.
   *
   * @param builder The {@link Attributes.Builder} for the transport being created.
   * @param remoteUid The remote UID of the transport.
   * @param serverPolicyChecker The policy checker for this transport.
   * @param executor used for calling into the application. Must outlive the transport.
   */
  @Internal
  public static void attachAuthAttrs(
      Attributes.Builder builder,
      int remoteUid,
      ServerPolicyChecker serverPolicyChecker,
      Executor executor) {
    builder
        .set(
            TRANSPORT_AUTHORIZATION_STATE,
            new TransportAuthorizationState(remoteUid, serverPolicyChecker, executor))
        .set(GrpcAttributes.ATTR_SECURITY_LEVEL, SecurityLevel.PRIVACY_AND_INTEGRITY);
  }

  /**
   * Intercepts server calls and ensures they're authorized before allowing them to proceed.
   * Authentication state is fetched from the call attributes, inherited from the transport.
   */
  private static final class ServerAuthInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      TransportAuthorizationState transportAuthState =
          call.getAttributes().get(TRANSPORT_AUTHORIZATION_STATE);
      ListenableFuture<Status> authStatusFuture =
          transportAuthState.checkAuthorization(call.getMethodDescriptor());

      // Most SecurityPolicy will have synchronous implementations that provide an
      // immediately-resolved Future. In that case, short-circuit to avoid unnecessary allocations
      // and asynchronous code if the authorization result is already present.
      if (!authStatusFuture.isDone()) {
        return newServerCallListenerForPendingAuthResult(
            authStatusFuture, transportAuthState.executor, call, headers, next);
      }

      Status authStatus;
      try {
        authStatus = Futures.getDone(authStatusFuture);
      } catch (ExecutionException e) {
        authStatus = statusFromFailedAuthorizationFuture(e.getCause());
      } catch (CancellationException e) {
        authStatus = statusFromFailedAuthorizationFuture(e);
      }

      if (authStatus.isOk()) {
        return next.startCall(call, headers);
      } else {
        call.close(authStatus, new Metadata());
        return new ServerCall.Listener<ReqT>() {};
      }
    }

    private <ReqT, RespT> ServerCall.Listener<ReqT> newServerCallListenerForPendingAuthResult(
        ListenableFuture<Status> authStatusFuture,
        Executor executor,
        ServerCall<ReqT, RespT> call,
        Metadata headers,
        ServerCallHandler<ReqT, RespT> next) {
      PendingAuthListener<ReqT, RespT> listener = new PendingAuthListener<>();
      Futures.addCallback(
          authStatusFuture,
          new FutureCallback<Status>() {
            @Override
            public void onSuccess(Status authStatus) {
              if (!authStatus.isOk()) {
                call.close(authStatus, new Metadata());
                return;
              }

              listener.startCall(call, headers, next);
            }

            @Override
            public void onFailure(Throwable t) {
              call.close(statusFromFailedAuthorizationFuture(t), new Metadata());
            }
          },
          executor);
      return listener;
    }

    private static Status statusFromFailedAuthorizationFuture(Throwable cause) {
      // The actual failure is retained as the cause for debugging, but peers should see a
      // uniform transport-level failure instead of the underlying exception message.
      return Status.INTERNAL.withCause(cause).withDescription("Authorization future failed");
    }
  }

  /**
   * Maintains the authorization state for a single transport instance. This class lives for the
   * lifetime of a single transport.
   */
  @VisibleForTesting
  static final class TransportAuthorizationState {
    private final int uid;
    private final ServerPolicyChecker serverPolicyChecker;
    // Holds *all* pending policy check futures and *certain* complete ones that we want to cache.
    private final ConcurrentHashMap<String, ListenableFuture<Status>> serviceAuthorization;
    private final Executor executor;

    /**
     * @param executor used for calling into the application. Must outlive the transport.
     */
    TransportAuthorizationState(
        int uid, ServerPolicyChecker serverPolicyChecker, Executor executor) {
      this.uid = uid;
      this.serverPolicyChecker = serverPolicyChecker;
      this.executor = executor;
      serviceAuthorization = new ConcurrentHashMap<>(8);
    }

    /** Get whether we're authorized to make this call. */
    @CheckReturnValue
    ListenableFuture<Status> checkAuthorization(MethodDescriptor<?, ?> method) {
      String serviceName = method.getServiceName();
      @Nullable
      ListenableFuture<Status> pendingOrCachedAuthResult = serviceAuthorization.get(serviceName);
      if (pendingOrCachedAuthResult != null) {
        return nonCancellationPropagating(pendingOrCachedAuthResult);
      }

      SettableFuture<Status> newPendingAuthResult = SettableFuture.create();
      ListenableFuture<Status> checkThenActRaceWinner =
          serviceAuthorization.putIfAbsent(serviceName, newPendingAuthResult);
      if (checkThenActRaceWinner != null) {
        // Another thread running this method must have also just saw no entry for serviceName, then
        // beat us to calling putIfAbsent(). We can only track one check at a time so share theirs.
        return nonCancellationPropagating(checkThenActRaceWinner);
      }

      try {
        newPendingAuthResult.setFuture(
            serverPolicyChecker.checkAuthorizationForServiceAsync(uid, serviceName));
      } catch (Exception e) {  // Not just RuntimeException! Handle the "sneaky" checked case too.
        newPendingAuthResult.setException(e);
      }

      Futures.addCallback(
          newPendingAuthResult,
          new FutureCallback<Status>() {
            @Override
            public void onSuccess(Status result) {
              // Auth checks can be expensive so we want to cache the results. But programmatically
              // created service names could cause the cache to grow without bound. Conservatively,
              // we only cache results for codegen service names as there can't be too many of them.
              if (!method.isSampledToLocalTracing()) {
                serviceAuthorization.remove(serviceName, newPendingAuthResult);
              }
            }

            @Override
            public void onFailure(Throwable t) {
              // Not simply a non-OK auth result but a failure to return any decision at all. Never
              // cache these so that if the caller retries, we'll retry the auth check as well.
              serviceAuthorization.remove(serviceName, newPendingAuthResult);
            }
          },
          MoreExecutors.directExecutor());

      return nonCancellationPropagating(newPendingAuthResult);
    }

  }

  /**
   * Decides whether a given Android UID is authorized to access some resource.
   *
   * <p>This class provides the asynchronous version of {@link io.grpc.binder.SecurityPolicy},
   * allowing implementations of authorization logic that involves slow or asynchronous calls
   * without necessarily blocking the calling thread.
   *
   * @see io.grpc.binder.SecurityPolicy
   */
  public interface ServerPolicyChecker {
    /**
     * Returns whether the given Android UID is authorized to access a particular service.
     *
     * <p>This method never throws an exception. If the execution of the security policy check
     * fails, a failed future with such exception is returned.
     *
     * @param uid The Android UID to authenticate.
     * @param serviceName The name of the gRPC service being called.
     * @return a future with the result of the authorization check. A failed future represents a
     *     failure to perform the authorization check, not that the access is denied.
     */
    ListenableFuture<Status> checkAuthorizationForServiceAsync(int uid, String serviceName);
  }
}
