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

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

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
import io.grpc.internal.ObjectPool;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;

/**
 * Manages security for an Android Service hosted gRPC server.
 *
 * <p>Attaches authorization state to a newly-created transport, and contains a
 * ServerInterceptor which ensures calls are authorized before allowing them to proceed.
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
  public static void installAuthInterceptor(
      ServerBuilder<?> serverBuilder,
      ObjectPool<? extends Executor> executorPool) {
    serverBuilder.intercept(new ServerAuthInterceptor(executorPool));
  }

  /**
   * Attach the given security policy to the transport attributes being built. Will be used by the
   * auth interceptor to confirm accept or reject calls.
   *
   * @param builder The {@link Attributes.Builder} for the transport being created.
   * @param remoteUid The remote UID of the transport.
   * @param serverPolicyChecker The policy checker for this transport.
   */
  @Internal
  public static void attachAuthAttrs(
      Attributes.Builder builder, int remoteUid, ServerPolicyChecker serverPolicyChecker) {
    builder
        .set(
            TRANSPORT_AUTHORIZATION_STATE,
            new TransportAuthorizationState(remoteUid, serverPolicyChecker))
        .set(GrpcAttributes.ATTR_SECURITY_LEVEL, SecurityLevel.PRIVACY_AND_INTEGRITY);
  }

  /**
   * Intercepts server calls and ensures they're authorized before allowing them to proceed.
   * Authentication state is fetched from the call attributes, inherited from the transport.
   */
  private static final class ServerAuthInterceptor implements ServerInterceptor {

    private final ObjectPool<? extends Executor> executorPool;

    ServerAuthInterceptor(ObjectPool<? extends Executor> executorPool) {
      this.executorPool = executorPool;
    }

    /**
     * @param authStatusFuture a Future that is known to be complete, i.e.
     *                         {@link ListenableFuture#isDone()} returns true.
     */
    private <ReqT, RespT> ServerCall.Listener<ReqT> newServerCallListenerForDoneAuthResult(
        ListenableFuture<Status> authStatusFuture,
        ServerCall<ReqT, RespT> call,
        Metadata headers,
        ServerCallHandler<ReqT, RespT> next) {
      Status authStatus;
      try {
        authStatus = Futures.getDone(authStatusFuture);
      } catch (ExecutionException e) {
        authStatus = Status.INTERNAL.withCause(e);
      }

      if (authStatus.isOk()) {
        return next.startCall(call, headers);
      }
      call.close(authStatus, new Metadata());
      return new ServerCall.Listener<ReqT>() {
      };
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      ListenableFuture<Status> authStatusFuture =
          call.getAttributes()
              .get(TRANSPORT_AUTHORIZATION_STATE)
              .checkAuthorization(call.getMethodDescriptor());

      // Most SecurityPolicy will have synchronous implementations that provide an
      // immediately-resolved Future. In that case, short-circuit to avoid unnecessary allocations
      // and asynchronous code.
      if (authStatusFuture.isDone()) {
        return newServerCallListenerForDoneAuthResult(authStatusFuture, call, headers, next);
      }

      return PendingAuthListener.create(authStatusFuture, executorPool, call, headers, next);
    }
  }

  /**
   * Maintains the authorization state for a single transport instance. This class lives for the
   * lifetime of a single transport.
   */
  private static final class TransportAuthorizationState {
    private final int uid;
    private final ServerPolicyChecker serverPolicyChecker;
    private final ConcurrentHashMap<String, ListenableFuture<Status>> serviceAuthorization;

    TransportAuthorizationState(int uid, ServerPolicyChecker serverPolicyChecker) {
      this.uid = uid;
      this.serverPolicyChecker = serverPolicyChecker;
      serviceAuthorization = new ConcurrentHashMap<>(8);
    }

    /** Get whether we're authorized to make this call. */
    @CheckReturnValue
    ListenableFuture<Status> checkAuthorization(MethodDescriptor<?, ?> method) {
      String serviceName = method.getServiceName();
      // Only cache decisions if the method can be sampled for tracing,
      // which is true for all generated methods. Otherwise, programmatically
      // created methods could cause this cache to grow unbounded.
      boolean useCache = method.isSampledToLocalTracing();
      if (useCache) {
        @Nullable ListenableFuture<Status> authorization = serviceAuthorization.get(serviceName);
        if (authorization != null) {
          return authorization;
        }
      }
      // Under high load, this may trigger a large number of concurrent authorization checks that
      // perform essentially the same work and have the potential of exhausting the resources they
      // depend on. This was a non-issue in the past with synchronous policy checks due to the
      // fixed-size nature of the thread pool this method runs under.
      //
      // TODO(10669): evaluate if there should be at most a single pending authorization check per
      //  (uid, serviceName) pair at any given time.
      ListenableFuture<Status> authorization =
          serverPolicyChecker.checkAuthorizationForServiceAsync(uid, serviceName);
      if (useCache) {
        serviceAuthorization.putIfAbsent(serviceName, authorization);
      }
      return authorization;
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
     *    failure to perform the authorization check, not that the access is denied.
     */
    ListenableFuture<Status> checkAuthorizationForServiceAsync(int uid, String serviceName);
  }
}
