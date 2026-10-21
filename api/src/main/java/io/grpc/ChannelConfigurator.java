/*
 * Copyright 2026 The gRPC Authors
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
 * A configurator for child channels created by gRPC's internal infrastructure.
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
 * // 1. Define the configurator
 * ChannelConfigurator configurator = builder -> {
 *   builder.maxInboundMessageSize(4 * 1024 * 1024);
 * };
 *
 * // 2. Apply to parent channel - automatically used for ALL child channels
 * ManagedChannel channel = ManagedChannelBuilder
 *     .forTarget("xds:///my-service")
 *     .childChannelConfigurator(configurator)
 *     .build();
 * }</pre>
 *
 * <p>Implementations must be thread-safe as the configure methods may be invoked concurrently
 * by multiple internal components.
 *
 * @since 1.83.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/12574")
@FunctionalInterface
public interface ChannelConfigurator {

  /**
   * Configures a builder for a new child channel.
   *
   * <p>This method is invoked synchronously during the creation of the child channel,
   * before {@link ManagedChannelBuilder#build()} is called.
   *
   * <p><strong>Note:</strong> Implementations must only apply configurations to the
   * provided builder and must NOT call {@code builder.build()} themselves.
   *
   * <p><strong>Note:</strong> The provided {@code builder} is generic ({@code ?}). Implementations
   * should use universal configuration methods (like {@code intercept()}, {@code userAgent()})
   * on the builder rather than casting it to specific implementation types.
   *
   * @param builder the mutable channel builder for the new child channel
   */
  void configureChannelBuilder(ManagedChannelBuilder<?> builder);
}
