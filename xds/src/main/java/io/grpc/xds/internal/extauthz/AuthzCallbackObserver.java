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
import io.grpc.MetricRecorder;
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
  // TODO(sauravzg): Make this static seeing that it holds no state.
  private final HeaderMutator headerMutator = HeaderMutator.create();
  private final ExtAuthzConfig config;
  private final Context.CancellableContext authzContext;
  private final MetricRecorder metricRecorder;
  private final String target;

  AuthzCallbackObserver(
      DelayedClientCall<ReqT, RespT> delayedCall,
      Channel next,
      MethodDescriptor<ReqT, RespT> method,
      CallOptions callOptions,
      Executor callExecutor,
      CheckResponseHandler responseHandler,
      ExtAuthzConfig config,
      Context.CancellableContext authzContext,
      MetricRecorder metricRecorder,
      String target) {
    this.delayedCall = delayedCall;
    this.next = next;
    this.method = method;
    this.callOptions = callOptions;
    this.callExecutor = callExecutor;
    this.responseHandler = responseHandler;
    this.config = config;
    this.authzContext = authzContext;
    this.metricRecorder = metricRecorder;
    this.target = target;
  }

  @Override
  public void onNext(CheckResponse value) {
    try {
      handleCheckResponse(value);
    } catch (RuntimeException e) {
      // A processing failure is not an authz communication failure, so failure_mode_allow
      // must not apply here.
      if (setCallAndDrain(new FailingClientCall<>(
          Status.INTERNAL.withCause(e).withDescription("Failed to process authz response")))) {
        ExtAuthzMetrics.recordClientFailed(metricRecorder, target);
        ExtAuthzMetrics.recordClientDenied(metricRecorder, target);
      }
    }
  }

  private void handleCheckResponse(CheckResponse value) {
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
      if (setCallAndDrain(finalCall)) {
        ExtAuthzMetrics.recordClientAllowed(metricRecorder, target);
      }
    } else {
      // TODO: Treat response validation failures as failures instead of authorization denials,
      // per the clarified ext_authz spec. Count them in failed_rpcs instead of denied_rpcs.
      // DENY responses always have a status because the deny() factory requires one.
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
      if (setCallAndDrain(failingCall)) {
        ExtAuthzMetrics.recordClientDenied(metricRecorder, target);
      }
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
      if (setCallAndDrain(finalCall)) {
        ExtAuthzMetrics.recordClientFailed(metricRecorder, target);
        ExtAuthzMetrics.recordClientAllowed(metricRecorder, target);
      }
      authzContext.close();
    } else {
      Status statusToReturn = config.statusOnError().withCause(t);
      if (setCallAndDrain(new FailingClientCall<>(statusToReturn))) {
        ExtAuthzMetrics.recordClientFailed(metricRecorder, target);
        ExtAuthzMetrics.recordClientDenied(metricRecorder, target);
      }
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
    if (setCallAndDrain(new FailingClientCall<>(status))) {
      ExtAuthzMetrics.recordClientFailed(metricRecorder, target);
      ExtAuthzMetrics.recordClientDenied(metricRecorder, target);
    }
    authzContext.close();
  }

  private boolean setCallAndDrain(ClientCall<ReqT, RespT> call) {
    Runnable drain = delayedCall.setCall(call);
    // drain is null if the call was already set (e.g. onCompleted() following onNext())
    // or if the call was cancelled in-flight.
    if (drain != null) {
      callExecutor.execute(drain);
      return true;
    }
    return false;
  }

  private static boolean isHeaderMutationsEmpty(HeaderMutations mutations) {
    return mutations.headers().isEmpty() && mutations.headersToRemove().isEmpty();
  }
}
