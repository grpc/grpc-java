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

import com.google.protobuf.util.Timestamps;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.internal.Matchers.FractionMatcher;
import io.grpc.xds.internal.ThreadSafeRandom;
import io.grpc.xds.internal.headermutations.HeaderMutations.ResponseHeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutator;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A server interceptor that performs external authorization for incoming RPCs.
 */
public final class ExtAuthzServerInterceptor implements ServerInterceptor {

  /**
   * A factory for creating {@link ExtAuthzServerInterceptor} instances.
   */
  @FunctionalInterface
  public interface Factory {
    /**
     * Creates a new {@link ExtAuthzServerInterceptor}.
     *
     * @param config the external authorization configuration.
     * @param authzStub the gRPC stub for the authorization service.
     * @param random the random number generator for filter matching.
     * @param checkRequestBuilder the builder for creating authorization check requests.
     * @param responseHandler the handler for processing authorization responses.
     * @param headerMutator the mutator for applying header mutations.
     * @return a new {@link ServerInterceptor}.
     */
    ServerInterceptor create(ExtAuthzConfig config, AuthorizationGrpc.AuthorizationStub authzStub,
        ThreadSafeRandom random, CheckRequestBuilder checkRequestBuilder,
        CheckResponseHandler responseHandler, HeaderMutator headerMutator);
  }

  /**
   * A factory for creating {@link ExtAuthzServerInterceptor} instances. This is the only supported
   * way to create a new ExtAuthzServerInterceptor.
   */
  public static final Factory INSTANCE = ExtAuthzServerInterceptor::new;

  private final ExtAuthzConfig config;
  private final AuthorizationGrpc.AuthorizationStub authzStub;
  private final ThreadSafeRandom random;
  private final CheckRequestBuilder checkRequestBuilder;
  private final CheckResponseHandler responseHandler;
  private final HeaderMutator headerMutator;

  private ExtAuthzServerInterceptor(ExtAuthzConfig config,
      AuthorizationGrpc.AuthorizationStub authzStub, ThreadSafeRandom random,
      CheckRequestBuilder checkRequestBuilder,
      CheckResponseHandler responseHandler, HeaderMutator headerMutator) {
    this.config = config;
    this.random = random;
    this.authzStub = authzStub;
    this.checkRequestBuilder = checkRequestBuilder;
    this.responseHandler = responseHandler;
    this.headerMutator = headerMutator;
  }

  /**
   * Intercepts an incoming call to perform external authorization.
   *
   * @param call the server call to intercept.
   * @param headers the headers of the incoming call.
   * @param next the next handler in the chain.
   * @return a listener for the server call.
   */
  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
      final Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    FractionMatcher filterEnabled = config.filterEnabled();
    if (random.nextInt(filterEnabled.denominator()) < filterEnabled.numerator()) {
      if (config.denyAtDisable()) {
        call.close(config.statusOnError(), new Metadata());
        return new ServerCall.Listener<ReqT>() {};
      }
      return next.startCall(call, headers);
    }
    ExtAuthzForwardingListener<ReqT, RespT> listener = new ExtAuthzForwardingListener<>(config,
        authzStub, headers, call, next, checkRequestBuilder, responseHandler, headerMutator);
    listener.startAuthzCall();
    return listener;
  }

  /**
   * A forwarding server call listener that handles the external authorization process.
   */
  private static final class ExtAuthzForwardingListener<ReqT, RespT>
      extends ForwardingServerCallListener<ReqT> {
    private static final String X_ENVOY_AUTH_FAILURE_MODE_ALLOWED =
        "x-envoy-auth-failure-mode-allowed";

    private final ExtAuthzConfig config;
    private final AuthorizationGrpc.AuthorizationStub authzStub;
    private final Metadata headers;
    private final ServerCall<ReqT, RespT> realServerCall;
    private final ServerCallHandler<ReqT, RespT> serverCallHandler;
    private final CheckRequestBuilder checkRequestBuilder;
    private final CheckResponseHandler responseHandler;
    private final HeaderMutator headerMutator;
    private final AtomicReference<ServerCall.Listener<ReqT>> delegateListener;

    /**
     * Constructs a new {@link ExtAuthzForwardingListener}.
     */
    ExtAuthzForwardingListener(ExtAuthzConfig config,
        AuthorizationGrpc.AuthorizationStub authzStub, Metadata headers,
        ServerCall<ReqT, RespT> serverCall, ServerCallHandler<ReqT, RespT> serverCallHandler,
        CheckRequestBuilder checkRequestBuilder, CheckResponseHandler responseHandler,
            HeaderMutator headerMutator) {
      this.config = config;
      this.authzStub = authzStub;
      this.headers = headers;
      this.realServerCall = serverCall;
      this.serverCallHandler = serverCallHandler;
      this.checkRequestBuilder = checkRequestBuilder;
      this.responseHandler = responseHandler;
      this.headerMutator = headerMutator;
      this.delegateListener =
          new AtomicReference<ServerCall.Listener<ReqT>>(new ServerCall.Listener<ReqT>() {});
    }

    /**
     * Starts the external authorization call.
     */
    void startAuthzCall() {
      CheckRequest checkRequest = checkRequestBuilder.buildRequest(realServerCall, headers,
          Timestamps.fromMillis(System.currentTimeMillis()));
      StreamObserver<CheckResponse> observer = new StreamObserver<CheckResponse>() {
        @Override
        public void onNext(CheckResponse value) {
          // The handleResponse method may add or modify headers based on the authorization
          // response.
          AuthzResponse authzResponse = responseHandler.handleResponse(value, headers);
          if (authzResponse.decision() == AuthzResponse.Decision.ALLOW) {
            AuthzServerCall<ReqT, RespT> authzServerCall = new AuthzServerCall<>(realServerCall,
                authzResponse.responseHeaderMutations(), headerMutator);
            delegateListener
                .set(serverCallHandler.startCall(authzServerCall, authzResponse.headers().get()));
          } else {
            // A deny response is guaranteed to have a status set, so the `get` without
            // check is safe.
            realServerCall.close(authzResponse.status().get(), new Metadata());
          }
        }

        @Override
        public void onError(Throwable t) {
          if (config.failureModeAllow()) {
            if (config.failureModeAllowHeaderAdd()) {
              Metadata.Key<String> key = Metadata.Key.of(X_ENVOY_AUTH_FAILURE_MODE_ALLOWED,
                  Metadata.ASCII_STRING_MARSHALLER);
              headers.put(key, "true");
            }
            delegateListener.set(serverCallHandler.startCall(realServerCall, headers));
          } else {
            realServerCall.close(config.statusOnError().withCause(t), new Metadata());
          }
        }

        @Override
        public void onCompleted() {
          // No-op. The authorization service uses a unary RPC, so we only expect one response.
        }
      };
      authzStub.check(checkRequest, observer);
    }

    @Override
    protected Listener<ReqT> delegate() {
      return delegateListener.get();
    }
  }

  /**
   * A server call that applies response header mutations from the authorization service.
   */
  private static class AuthzServerCall<ReqT, RespT>
      extends SimpleForwardingServerCall<ReqT, RespT> {
    private final ResponseHeaderMutations responseHeaderMutations;
    private final HeaderMutator headerMutator;

    /**
     * Constructs a new {@link AuthzServerCall}.
     *
     * @param delegate the original server call.
     * @param responseHeaderMutations the response header mutations to apply.
     * @param headerMutator the mutator for applying header mutations.
     */
    private AuthzServerCall(ServerCall<ReqT, RespT> delegate,
        ResponseHeaderMutations responseHeaderMutations, HeaderMutator headerMutator) {
      super(delegate);
      this.responseHeaderMutations = responseHeaderMutations;
      this.headerMutator = headerMutator;
    }

    /**
     * Sends the headers after applying any mutations from the authorization service.
     *
     * @param headers the headers to send.
     */
    @Override
    public void sendHeaders(Metadata headers) {
      headerMutator.applyResponseMutations(responseHeaderMutations, headers);
      super.sendHeaders(headers);
    }
  }
}
