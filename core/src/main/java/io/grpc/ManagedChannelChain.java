/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

import java.util.concurrent.TimeUnit;

/**
 * {@code ManagedChannelChain} helps solve the problem where, by wrapping a {@link ManagedChannel}
 * in multiple layers of {@link io.grpc.ClientInterceptors.InterceptorChannel}, you lose the
 * "ManagedChannel-ness" of the the channel chain.
 *
 * <br>{@code ManagedChannelChain} is used to help {@code AbstractStub} preserve the
 * "ManagedChannel-ness" of its {@code getChannel()} method result, when the stub is built around a
 * {@code ManagedChannel} and the {@code AbstractStub.withInterceptors()} is used.
 */
class ManagedChannelChain extends ManagedChannel {
  private final ManagedChannel base;
  private final Channel head;

  public ManagedChannelChain(Channel head, ManagedChannel base) {
    this.head = head;
    this.base = base;
  }

  // Channel Methods
  @Override
  public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
      MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
    return head.newCall(methodDescriptor, callOptions);
  }

  @Override
  public String authority() {
    return head.authority();
  }

  // ManagedChannel Methods
  @Override
  public ManagedChannel shutdown() {
    return base.shutdown();
  }

  @Override
  public boolean isShutdown() {
    return base.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return base.isTerminated();
  }

  @Override
  public ManagedChannel shutdownNow() {
    return base.shutdownNow();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return base.awaitTermination(timeout, unit);
  }
}
