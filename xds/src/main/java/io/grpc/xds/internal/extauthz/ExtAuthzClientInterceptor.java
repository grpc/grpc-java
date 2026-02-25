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

import com.google.errorprone.annotations.ThreadSafe;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;
import io.grpc.internal.FailingClientCall;
import io.grpc.xds.internal.Matchers.FractionMatcher;
import io.grpc.xds.internal.ThreadSafeRandom;
import io.grpc.xds.internal.headermutations.HeaderMutator;

@ThreadSafe
public final class ExtAuthzClientInterceptor implements ClientInterceptor {

  /** A factory for creating {@link ExtAuthzClientInterceptor} instances. */
  @FunctionalInterface
  public interface Factory {
    ClientInterceptor create(ExtAuthzConfig config, AuthorizationGrpc.AuthorizationStub authzStub,
        ThreadSafeRandom random, BufferingAuthzClientCall.Factory clientCallFactory,
        CheckRequestBuilder checkRequestBuilder, CheckResponseHandler responseHandler,
            HeaderMutator headerMutator);
  }

  public static final Factory INSTANCE = ExtAuthzClientInterceptor::new;

  private final ExtAuthzConfig config;
  private final AuthorizationGrpc.AuthorizationStub authzStub;
  private final ThreadSafeRandom random;
  private final BufferingAuthzClientCall.Factory clientCallFactory;
  private final CheckRequestBuilder checkRequestBuilder;
  private final CheckResponseHandler responseHandler;
  private final HeaderMutator headerMutator;


  private ExtAuthzClientInterceptor(ExtAuthzConfig config,
      AuthorizationGrpc.AuthorizationStub authzStub, ThreadSafeRandom random,
      BufferingAuthzClientCall.Factory clientCallFactory, CheckRequestBuilder checkRequestBuilder,
      CheckResponseHandler responseHandler, HeaderMutator headerMutator) {
    this.config = config;
    this.random = random;
    this.authzStub = authzStub;
    this.clientCallFactory = clientCallFactory;
    this.checkRequestBuilder = checkRequestBuilder;
    this.responseHandler = responseHandler;
    this.headerMutator = headerMutator;
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
      CallOptions callOptions, Channel next) {
    FractionMatcher filterEnabled = config.filterEnabled();
    if (random.nextInt(filterEnabled.denominator()) < filterEnabled.numerator()) {
      if (config.denyAtDisable()) {
        return new FailingClientCall<>(config.statusOnError());
      }
      return next.newCall(method, callOptions);
    }
    return clientCallFactory.create(next.newCall(method, callOptions), config, authzStub,
        checkRequestBuilder, responseHandler, headerMutator, method, new CallBuffer());
  }
}
