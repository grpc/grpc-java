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

/**
 * Internal accessors for {@link ManagedChannelBuilder}.
 */
@Internal
public final class InternalManagedChannelBuilder {
  private InternalManagedChannelBuilder() {}

  public static <T extends ManagedChannelBuilder<T>> T interceptWithTarget(
      ManagedChannelBuilder<T> builder, InternalInterceptorFactory factory) {
    return builder.interceptWithTarget(factory);
  }

  public static <T extends ManagedChannelBuilder<T>> T addMetricSink(
      ManagedChannelBuilder<T> builder, MetricSink metricSink) {
    return builder.addMetricSink(metricSink);
  }

  public interface InternalInterceptorFactory extends ManagedChannelBuilder.InterceptorFactory {}
}
