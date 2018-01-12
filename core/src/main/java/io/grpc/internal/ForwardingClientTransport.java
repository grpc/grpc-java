/*
 * Copyright 2018, gRPC Authors All rights reserved.
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

package io.grpc.internal;

import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.CallOptions;
import io.grpc.InternalTransportStats;
import io.grpc.LogId;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.util.concurrent.Executor;

abstract class ForwardingClientTransport implements ClientTransport {
  protected abstract ClientTransport delegate();

  @Override
  public LogId getLogId() {
    return delegate().getLogId();
  }

  @Override
  public ListenableFuture<InternalTransportStats> getStats() {
    return delegate().getStats();
  }

  @Override
  public ClientStream newStream(MethodDescriptor<?, ?> method, Metadata headers,
      CallOptions callOptions) {
    return delegate().newStream(method, headers, callOptions);
  }

  @Override
  public void ping(PingCallback callback, Executor executor) {
    delegate().ping(callback, executor);
  }
}
