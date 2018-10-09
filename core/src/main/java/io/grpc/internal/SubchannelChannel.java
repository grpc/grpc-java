/*
 * Copyright 2016 The gRPC Authors
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

final class SubchannelChannel extends Channel {
  @VisibleForTesting
  static final Status NOT_READY_ERROR =
      Status.UNAVAILABLE.withDescription("Subchannel is NOT READY");
  private static final FailingClientTransport failingTransport =
      new FailingClientTransport(NOT_READY_ERROR, RpcProgress.REFUSED);
  private final InternalSubchannel subchannel;
  private final Executor executor;
  private final ScheduledExecutorService deadlineCancellationExecutor;
  private final CallTracer callsTracer;

  private final ClientTransportProvider transportProvider = new ClientTransportProvider() {
    @Override
    public ClientTransport get(PickSubchannelArgs args) {
      ClientTransport transport = subchannel.getTransport();
      if (transport == null) {
        return failingTransport;
      } else {
        return transport;
      }
    }

    @Override
    public <ReqT> RetriableStream<ReqT> newRetriableStream(MethodDescriptor<ReqT, ?> method,
        CallOptions callOptions, Metadata headers, Context context) {
      throw new UnsupportedOperationException("OobChannel should not create retriable streams");
    }
  };

  SubchannelChannel(
      InternalSubchannel subchannel, Executor executor,
      ScheduledExecutorService deadlineCancellationExecutor, CallTracer callsTracer) {
    this.subchannel = checkNotNull(subchannel, "subchannel");
    this.executor = checkNotNull(executor, "executor");
    this.deadlineCancellationExecutor =
        checkNotNull(deadlineCancellationExecutor, "deadlineCancellationExecutor");
    this.callsTracer = checkNotNull(callsTracer, "callsTracer");
  }

  @Override
  public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
      MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
    return new ClientCallImpl<RequestT, ResponseT>(methodDescriptor,
        callOptions.getExecutor() == null ? executor : callOptions.getExecutor(),
        callOptions, transportProvider, deadlineCancellationExecutor, callsTracer,
        false /* retryEnabled */);
  }

  @Override
  public String authority() {
    return subchannel.getAuthority();
  }
}
