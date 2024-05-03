/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc;

import com.google.common.base.Preconditions;
import io.grpc.CallOptions;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.mockito.ArgumentMatcher;
import org.mockito.ArgumentMatchers;

/**
 * Mockito Matcher for {@link PickSubchannelArgs}.
 */
public final class PickSubchannelArgsMatcher implements ArgumentMatcher<PickSubchannelArgs> {
  private final MethodDescriptor<?, ?> method;
  private final Metadata headers;
  private final CallOptions callOptions;

  public PickSubchannelArgsMatcher(
      MethodDescriptor<?, ?> method, Metadata headers, CallOptions callOptions) {
    this.method = Preconditions.checkNotNull(method, "method");
    this.headers = Preconditions.checkNotNull(headers, "headers");
    this.callOptions = Preconditions.checkNotNull(callOptions, "callOptions");
  }

  @Override
  public boolean matches(PickSubchannelArgs args) {
    return args != null
        && method.equals(args.getMethodDescriptor())
        && headers.equals(args.getHeaders())
        && callOptions.equals(args.getCallOptions());
  }

  @Override
  public final String toString() {
    return "[method=" + method + " headers=" + headers + " callOptions=" + callOptions + "]";
  }

  public static PickSubchannelArgs eqPickSubchannelArgs(
      MethodDescriptor<?, ?> method, Metadata headers, CallOptions callOptions) {
    return ArgumentMatchers.argThat(new PickSubchannelArgsMatcher(method, headers, callOptions));
  }
}
