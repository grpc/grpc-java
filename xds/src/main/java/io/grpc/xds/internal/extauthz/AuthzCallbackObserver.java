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

import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.internal.DelayedClientCall;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.internal.grpcservice.HeaderValue;
import io.grpc.xds.internal.headermutations.HeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutator;
import io.grpc.xds.internal.headermutations.HeaderValueOption;
import java.util.concurrent.Executor;

/**
 * A {@link StreamObserver} that handles the response from the external authorization service,
 * transitioning the delayed gRPC call based on the authorization decision.
 */
final class AuthzCallbackObserver<ReqT, RespT> implements StreamObserver<CheckResponse> {

  private static final Metadata.Key<String> X_ENVOY_AUTH_FAILURE_MODE_ALLOWED =
      Metadata.Key.of("x-envoy-auth-failure-mode-allowed", Metadata.ASCII_STRING_MARSHALLER);

  private final DelayedClientCall<ReqT, RespT> delayedCall;
  private final Channel next;
  private final MethodDescriptor<ReqT, RespT> method;
  private final CallOptions callOptions;
  private final Executor callExecutor;
  private final CheckResponseHandler responseHandler;
  private final HeaderMutator headerMutator;
  private final ExtAuthzConfig config;
  private final Context.CancellableContext authzContext;

  AuthzCallbackObserver(
      DelayedClientCall<ReqT, RespT> delayedCall,
      Channel next,
      MethodDescriptor<ReqT, RespT> method,
      CallOptions callOptions,
      Executor callExecutor,
      CheckResponseHandler responseHandler,
      HeaderMutator headerMutator,
      ExtAuthzConfig config,
      Context.CancellableContext authzContext) {
    this.delayedCall = delayedCall;
    this.next = next;
    this.method = method;
    this.callOptions = callOptions;
    this.callExecutor = callExecutor;
    this.responseHandler = responseHandler;
    this.headerMutator = headerMutator;
    this.config = config;
    this.authzContext = authzContext;
  }

  @Override
  public void onNext(CheckResponse value) {
    // Note on exception safety: handleResponse() internally catches
    // HeaderMutationDisallowedException and converts it to a DENY response.
    // Remaining calls (newCall, setCallAndDrain) use battle-tested
    // infrastructure with protobuf-guaranteed non-null defaults. An
    // uncaught RuntimeException here would propagate to the gRPC stub
    // framework, which handles observer stream cleanup. The authzContext
    // would remain uncancelled, but will be GC'd when this observer is
    // collected — acceptable since the authz RPC would have already
    // completed (we're inside onNext).
    AuthzResponse authzResponse = responseHandler.handleResponse(value);
    if (authzResponse.decision() == AuthzResponse.Decision.ALLOW) {
      ClientCall<ReqT, RespT> delegate = next.newCall(method, callOptions);
      ClientCall<ReqT, RespT> finalCall;
      if (isHeaderMutationsEmpty(authzResponse.requestHeaderMutations())
          && isHeaderMutationsEmpty(authzResponse.responseHeaderMutations())) {
        finalCall = delegate;
      } else {
        finalCall = new MutatingClientCall<>(
            delegate,
            authzResponse.requestHeaderMutations(),
            authzResponse.responseHeaderMutations(),
            headerMutator);
      }
      setCallAndDrain(finalCall);
    } else {
      // Defensive programming: status is always present on DENY branches since every
      // deny() factory method requires a Status parameter. This orElseThrow satisfies
      // static analysis and documents the invariant.
      Status status = authzResponse.status().orElseThrow(
          () -> new IllegalArgumentException("DENY response missing status"));
      ClientCall<ReqT, RespT> failingCall;
      if (isHeaderMutationsEmpty(authzResponse.responseHeaderMutations())) {
        failingCall = new FailingClientCall<>(status);
      } else {
        failingCall = new FailingCallWithTrailerMutations<>(
            status,
            authzResponse.responseHeaderMutations(),
            headerMutator);
      }
      setCallAndDrain(failingCall);
    }
  }

  @Override
  public void onError(Throwable t) {
    if (config.failureModeAllow()) {
      ClientCall<ReqT, RespT> delegate = next.newCall(method, callOptions);
      ClientCall<ReqT, RespT> finalCall;
      if (config.failureModeAllowHeaderAdd()) {
        HeaderMutations requestMutations = HeaderMutations.create(
            ImmutableList.of(HeaderValueOption.create(
                HeaderValue.create(X_ENVOY_AUTH_FAILURE_MODE_ALLOWED.name(), "true"),
                HeaderValueOption.HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD)),
            ImmutableList.of());
        // TODO(sauravz): Consider using a static EMPTY_MUTATIONS constant
        // to avoid repeated allocation of empty HeaderMutations.
        HeaderMutations responseMutations =
            HeaderMutations.create(ImmutableList.of(), ImmutableList.of());
        finalCall = new MutatingClientCall<>(
            delegate, requestMutations, responseMutations, headerMutator);
      } else {
        finalCall = delegate;
      }
      setCallAndDrain(finalCall);
      authzContext.close();
    } else {
      Status statusToReturn = config.statusOnError().withCause(t);
      setCallAndDrain(new FailingClientCall<>(statusToReturn));
      authzContext.close();
    }
  }

  @Override
  public void onCompleted() {
    // Defensive: if the authz server completed without sending a CheckResponse
    // (buggy server), resolve the DelayedClientCall with a failure and close
    // the authzContext. If onNext() already called setCall(), this is a no-op
    // since DelayedClientCall.setCall() ignores subsequent calls.
    Status status = config.statusOnError()
        .withDescription("Authz server completed without sending CheckResponse");
    setCallAndDrain(new FailingClientCall<>(status));
    authzContext.close();
  }

  private void setCallAndDrain(ClientCall<ReqT, RespT> call) {
    Runnable drain = delayedCall.setCall(call);
    if (drain != null) {
      callExecutor.execute(drain);
    }
  }

  private static boolean isHeaderMutationsEmpty(HeaderMutations mutations) {
    return mutations.headers().isEmpty() && mutations.headersToRemove().isEmpty();
  }
}
