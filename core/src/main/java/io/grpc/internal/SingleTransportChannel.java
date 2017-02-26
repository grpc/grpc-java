/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.internal;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.instrumentation.stats.StatsContextFactory;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.internal.ClientCallImpl.ClientTransportProvider;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

/**
 * A {@link Channel} that wraps a {@link ClientTransport}.
 */
final class SingleTransportChannel extends Channel {

  private final StatsContextFactory statsFactory;
  private final ClientTransport transport;
  private final Executor executor;
  private final String authority;
  private final ScheduledExecutorService deadlineCancellationExecutor;
  private final Supplier<Stopwatch> stopwatchSupplier;

  private final ClientTransportProvider transportProvider = new ClientTransportProvider() {
    @Override
    public ClientTransport get(CallOptions callOptions, Metadata headers) {
      return transport;
    }
  };

  /**
   * Creates a new channel with a connected transport.
   */
  public SingleTransportChannel(StatsContextFactory statsFactory, ClientTransport transport,
      Executor executor, ScheduledExecutorService deadlineCancellationExecutor, String authority,
      Supplier<Stopwatch> stopwatchSupplier) {
    this.statsFactory = Preconditions.checkNotNull(statsFactory, "statsFactory");
    this.transport = Preconditions.checkNotNull(transport, "transport");
    this.executor = Preconditions.checkNotNull(executor, "executor");
    this.deadlineCancellationExecutor = Preconditions.checkNotNull(
        deadlineCancellationExecutor, "deadlineCancellationExecutor");
    this.authority = Preconditions.checkNotNull(authority, "authority");
    this.stopwatchSupplier = Preconditions.checkNotNull(stopwatchSupplier, "stopwatchSupplier");
  }

  @Override
  public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
      MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
    StatsTraceContext statsTraceCtx = StatsTraceContext.newClientContext(
        methodDescriptor.getFullMethodName(), statsFactory, stopwatchSupplier);
    return new ClientCallImpl<RequestT, ResponseT>(methodDescriptor,
        new SerializingExecutor(executor), callOptions, statsTraceCtx, transportProvider,
        deadlineCancellationExecutor);
  }

  @Override
  public String authority() {
    return authority;
  }
}
