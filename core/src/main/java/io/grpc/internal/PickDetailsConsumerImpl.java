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

package io.grpc.internal;

import com.google.common.base.Preconditions;
import io.grpc.ClientStreamTracer;
import io.grpc.LoadBalancer.PickDetailsConsumer;

/**
 * Adapter for tracers into details consumers.
 */
final class PickDetailsConsumerImpl implements PickDetailsConsumer {
  private final ClientStreamTracer[] tracers;

  /** Construct a consumer with unchanging tracers array. */
  public PickDetailsConsumerImpl(ClientStreamTracer[] tracers) {
    this.tracers = Preconditions.checkNotNull(tracers, "tracers");
  }

  @Override
  public void addOptionalLabel(String key, String value) {
    Preconditions.checkNotNull(key, "key");
    Preconditions.checkNotNull(value, "value");
    for (ClientStreamTracer tracer : tracers) {
      tracer.addOptionalLabel(key, value);
    }
  }
}
