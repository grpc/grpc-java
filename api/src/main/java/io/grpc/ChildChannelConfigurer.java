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

package io.grpc;

import java.util.function.Consumer;

/**
 * A configurer for child channels created by gRPC's internal infrastructure.
 *
 * <p>This interface allows users to inject configuration (such as credentials, interceptors,
 * or flow control settings) into channels created automatically by gRPC for control plane
 * operations. Common use cases include:
 * <ul>
 * <li>xDS control plane connections</li>
 * <li>Load Balancing helper channels (OOB channels)</li>
 * </ul>
 *
 * <p><strong>Usage Example:</strong>
 * <pre>{@code
 * // 1. Define the configurer
 * ChildChannelConfigurer configurer = builder -> {
 *   builder.intercept(new MyAuthInterceptor());
 *   builder.maxInboundMessageSize(4 * 1024 * 1024);
 * };
 *
 * // 2. Apply to parent channel - automatically used for ALL child channels
 * ManagedChannel channel = ManagedChannelBuilder
 *     .forTarget("xds:///my-service")
 *     .childChannelConfigurer(configurer)
 *     .build();
 * }</pre>
 *
 * <p>Implementations must be thread-safe as {@link #accept} may be invoked concurrently
 * by multiple internal components.
 *
 * @since 1.79.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/12574")
@FunctionalInterface
public interface ChildChannelConfigurer extends Consumer<ManagedChannelBuilder<?>> {

  /**
   * Configures a builder for a new child channel.
   *
   * <p>This method is invoked synchronously during the creation of the child channel,
   * before {@link ManagedChannelBuilder#build()} is called.
   *
   * <p>Note: The provided {@code builder} is generic (`?`). Implementations should use
   * universal configuration methods (like {@code intercept()}, {@code userAgent()}) rather
   * than casting to specific implementation types.
   *
   * @param builder the mutable channel builder for the new child channel
   */
  @Override
  void accept(ManagedChannelBuilder<?> builder);
}
