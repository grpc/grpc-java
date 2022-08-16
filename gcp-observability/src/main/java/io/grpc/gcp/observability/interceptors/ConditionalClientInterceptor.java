/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.gcp.observability.interceptors;

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Internal;
import io.grpc.MethodDescriptor;
import java.util.function.BiPredicate;

/**
 * A client interceptor that conditionally calls a delegated interceptor.
 */
@Internal
public final class ConditionalClientInterceptor implements ClientInterceptor {

  private final ClientInterceptor delegate;
  private final BiPredicate<MethodDescriptor<?, ?>, CallOptions> predicate;

  public ConditionalClientInterceptor(ClientInterceptor delegate,
      BiPredicate<MethodDescriptor<?, ?>, CallOptions> predicate) {
    this.delegate = checkNotNull(delegate, "delegate");
    this.predicate = checkNotNull(predicate, "predicate");
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
      CallOptions callOptions, Channel next) {
    if (!predicate.test(method, callOptions)) {
      return next.newCall(method, callOptions);
    }
    return delegate.interceptCall(method, callOptions, next);
  }
}
