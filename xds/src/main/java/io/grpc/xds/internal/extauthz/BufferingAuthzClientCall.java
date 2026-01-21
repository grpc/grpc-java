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
import io.grpc.Attributes;
import io.grpc.ClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.internal.headermutations.HeaderMutations.ResponseHeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutator;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

public final class BufferingAuthzClientCall<ReqT, RespT> extends ClientCall<ReqT, RespT> {

  private static final String X_ENVOY_AUTH_FAILURE_MODE_ALLOWED =
      "x-envoy-auth-failure-mode-allowed";

  /** A factory for creating {@link BufferingAuthzClientCall} instances. */
  @FunctionalInterface
  public interface Factory {
    <ReqT, RespT> ClientCall<ReqT, RespT> create(ClientCall<ReqT, RespT> delegate,
        ExtAuthzConfig config, AuthorizationGrpc.AuthorizationStub authzStub,
        CheckRequestBuilder checkRequestBuilder, CheckResponseHandler responseHandler,
        HeaderMutator headerMutator, MethodDescriptor<ReqT, RespT> method, CallBuffer callBuffer);
  }

  public static final Factory FACTORY_INSTANCE = BufferingAuthzClientCall::new;

  private final ClientCall<ReqT, RespT> delegate;
  private final ExtAuthzConfig config;
  private final MethodDescriptor<ReqT, RespT> method;
  private final AuthorizationGrpc.AuthorizationStub authzStub;
  private final CallBuffer callBuffer;
  private final CheckRequestBuilder checkRequestBuilder;
  private final CheckResponseHandler responseHandler;
  private final HeaderMutator headerMutator;
  private final AtomicBoolean callFailed = new AtomicBoolean(false);

  private BufferingAuthzClientCall(ClientCall<ReqT, RespT> delegate, ExtAuthzConfig config,
      AuthorizationGrpc.AuthorizationStub authzStub, CheckRequestBuilder checkRequestBuilder,
      CheckResponseHandler responseHandler, HeaderMutator headerMutator,
          MethodDescriptor<ReqT, RespT> method, CallBuffer callBuffer) {
    this.delegate = delegate;
    this.config = config;
    this.authzStub = authzStub;
    this.checkRequestBuilder = checkRequestBuilder;
    this.responseHandler = responseHandler;
    this.headerMutator = headerMutator;
    this.method = method;
    this.callBuffer = callBuffer;
  }

  private ClientCall<ReqT, RespT> delegate() {
    return delegate;
  }

  @Override
  public boolean isReady() {
    return callBuffer.isProcessed() && delegate.isReady();
  }


  @Override
  public void start(Listener<RespT> responseListener, Metadata headers) {
    // Headers is not thread-safe beyond `start`, so we need to create a copy to use in the async
    // callback.
    Metadata headersCopy = new Metadata();
    headersCopy.merge(headers);
    StreamObserver<CheckResponse> observer = new StreamObserver<CheckResponse>() {
      @Override
      public void onNext(CheckResponse value) {
        // This operation may mutate the headers
        AuthzResponse authzResponse = responseHandler.handleResponse(value, headers);
        if (authzResponse.decision() == AuthzResponse.Decision.ALLOW) {
          // A allow response is guaranteed to have metadata set, so the `get` without
          // check is safe.
          delegate.start(
              HeaderMutatingClientCallListener.create(responseListener,
                  authzResponse.responseHeaderMutations(), headerMutator),
              authzResponse.headers().get());
          callBuffer.runAndFlush();
        } else {
          // A deny response is guaranteed to have a status set, so the `get` without
          // check is safe.
          failUnstartedCall(authzResponse.status().get(), new Metadata(), responseListener);
        }
      }

      @Override
      public void onError(Throwable t) {
        // If failureModeAllow is true, bypass the authorization failure
        if (config.failureModeAllow()) {
          if (config.failureModeAllowHeaderAdd()) {
            Metadata.Key<String> failureModeKey = Metadata.Key.of(X_ENVOY_AUTH_FAILURE_MODE_ALLOWED,
                Metadata.ASCII_STRING_MARSHALLER);
            headersCopy.put(failureModeKey, "true");
          }
          delegate.start(responseListener, headersCopy);
          callBuffer.runAndFlush();
        } else {
          // Authorization failed and failureModeAllow is false
          Status statusToReturn = config.statusOnError().withCause(t);
          failUnstartedCall(statusToReturn, new Metadata(), responseListener);
        }
      }

      @Override
      public void onCompleted() {
        // no-op, since this is a unary API.
      }
    };
    CheckRequest request = checkRequestBuilder.buildRequest(method, headers,
        Timestamps.fromMillis(System.currentTimeMillis()));
    authzStub.check(request, observer);
  }

  @Override
  public void request(int numMessages) {
    runOrBuffer(() -> delegate().request(numMessages));
  }

  @Override
  public void cancel(@Nullable String message, @Nullable Throwable cause) {
    delegate().cancel(message, cause);
    callBuffer.abandon();
  }

  @Override
  public void halfClose() {
    runOrBuffer(() -> delegate().halfClose());
  }

  @Override
  public void sendMessage(ReqT message) {
    runOrBuffer(() -> delegate().sendMessage(message));
  }

  @Override
  public void setMessageCompression(boolean enabled) {
    runOrBuffer(() -> delegate().setMessageCompression(enabled));
  }

  @Override
  public Attributes getAttributes() {
    // Since returning attributes can't be buffered and no other method except `cancel` can be
    // called on the delegated object until it's started,we will have to unfortunately return empty
    // until we are sure that `start` had been called.
    if (!callBuffer.isProcessed() || callFailed.get()) {
      return Attributes.EMPTY;
    } else {
      return delegate.getAttributes();
    }
  }

  private void runOrBuffer(Runnable runnable) {
    if (callFailed.get()) {
      return;
    }
    if (callBuffer.isProcessed()) {
      runnable.run();
    } else {
      callBuffer.runOrBuffer(runnable);
    }
  }

  private void failUnstartedCall(Status status, Metadata trailers,
      Listener<RespT> responseListener) {
    callFailed.set(true);
    responseListener.onClose(status, trailers);
    callBuffer.abandon();
  }

  /**
   * A {@link ForwardingClientCallListener} that mutates the response headers before passing them to
   * the delegate.
   */
  private static final class HeaderMutatingClientCallListener<RespT>
      extends ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT> {

    private final ResponseHeaderMutations responseHeaderMutations;
    private final HeaderMutator headerMutator;

    static <RespT> ClientCall.Listener<RespT> create(ClientCall.Listener<RespT> delegate,
        ResponseHeaderMutations responseHeaderMutations, HeaderMutator headerMutator) {
      return new HeaderMutatingClientCallListener<>(delegate, responseHeaderMutations,
          headerMutator);
    }

    private HeaderMutatingClientCallListener(ClientCall.Listener<RespT> delegate,
        ResponseHeaderMutations responseHeaderMutations, HeaderMutator headerMutator) {
      super(delegate);
      this.responseHeaderMutations = responseHeaderMutations;
      this.headerMutator = headerMutator;
    }

    @Override
    public void onHeaders(Metadata headers) {
      headerMutator.applyResponseMutations(responseHeaderMutations, headers);
      super.onHeaders(headers);
    }
  }
}
