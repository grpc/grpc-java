/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds.internal.extauthz;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.util.Timestamps;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.internal.DelayedClientCall;
import io.grpc.xds.internal.headermutations.HeaderMutator;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;

/**
 * A {@link ForwardingClientCall} that delegates to a lightweight {@link DelayedClientCall}
 * to buffer RPC requests safely while waiting for the asynchronous authorization decision.
 */
public final class ExtAuthzClientCall<ReqT, RespT> extends ForwardingClientCall<ReqT, RespT> {

  private final Channel next;
  private final MethodDescriptor<ReqT, RespT> method;
  private final CallOptions callOptions;
  private final AuthorizationGrpc.AuthorizationStub authzStub;
  private final CheckRequestBuilder checkRequestBuilder;
  private final CheckResponseHandler responseHandler;
  private final HeaderMutator headerMutator;
  private final ExtAuthzConfig config;
  private final Executor callExecutor;
  private final Context.CancellableContext authzContext;

  private final DelayedAuthzCall<ReqT, RespT> delegate;

  public ExtAuthzClientCall(
      Executor executor,
      ScheduledExecutorService scheduler,
      CallOptions callOptions,
      Channel next,
      MethodDescriptor<ReqT, RespT> method,
      AuthorizationGrpc.AuthorizationStub authzStub,
      CheckRequestBuilder checkRequestBuilder,
      CheckResponseHandler responseHandler,
      HeaderMutator headerMutator,
      ExtAuthzConfig config) {
    this.callOptions = callOptions;
    this.next = next;
    this.method = method;
    this.authzStub = authzStub;
    this.checkRequestBuilder = checkRequestBuilder;
    this.responseHandler = responseHandler;
    this.headerMutator = headerMutator;
    this.config = config;
    this.callExecutor = executor;
    this.authzContext = Context.current().withCancellation();
    this.delegate = new DelayedAuthzCall<>(executor, scheduler, callOptions.getDeadline());
  }

  @Override
  protected ClientCall<ReqT, RespT> delegate() {
    return delegate;
  }

  @Override
  public void start(Listener<RespT> responseListener, Metadata headers) {
    // 1. Construct the check request FIRST before handoff (respects metadata thread safety)
    CheckRequest request = checkRequestBuilder.buildRequest(method, headers,
        Timestamps.fromMillis(System.currentTimeMillis()));

    // 2. Start the delayed call (buffers headers and outbound payloads)
    super.start(responseListener, headers);

    // 3. Trigger the async authorization check under the cancellable context
    authzContext.run(() -> {
      authzStub.check(request, new AuthzCallbackObserver<>(
          delegate, next, method, callOptions, callExecutor, responseHandler, headerMutator,
          config, authzContext));
    });
  }

  @Override
  public void cancel(
      @Nullable String message, @Nullable Throwable cause) {
    authzContext.cancel(cause);
    super.cancel(message, cause);
  }

  /**
   * Returns the cancellable context used for the authorization check.
   * Visible for testing.
   */
  @VisibleForTesting
  Context.CancellableContext getAuthzContextForTest() {
    return authzContext;
  }

  /**
   * A lightweight package-private DelayedClientCall subclass to expose
   * the protected constructor.
   */
  private static final class DelayedAuthzCall<ReqT, RespT> extends DelayedClientCall<ReqT, RespT> {
    DelayedAuthzCall(Executor executor, ScheduledExecutorService scheduler, Deadline deadline) {
      super("ExtAuthzClientCall", executor, scheduler, deadline);
    }
  }
}
