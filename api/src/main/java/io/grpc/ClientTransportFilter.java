/*
 * Copyright 2023 The gRPC Authors
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
 * Listens on the client transport life-cycle events. These filters do not have the capability
 * to modify the channels or transport life-cycle event behavior, but they can be useful hooks
 * for transport observability. Multiple filters may be registered to the client.
 *
 * @since 1.62.0
 */
@ExperimentalApi("https://gitub.com/grpc/grpc-java/issues/10652")
public abstract class ClientTransportFilter {
  /**
   * Called when a transport is ready to accept traffic (when a connection has been established).
   * The default implementation is a no-op.
   *
   * @param transportAttrs current transport attributes
   *
   * @return new transport attributes. Default implementation returns the passed-in attributes
   *     intact.
   */
  public Attributes transportReady(Attributes transportAttrs) {
    return transportAttrs;
  }

  /**
   * Called when a transport completed shutting down. All resources have been released.
   * All streams have either been closed or transferred off this transport.
   * Default implementation is a no-op
   *
   * @param transportAttrs the effective transport attributes, which is what is returned by {@link
   * #transportReady} of the last executed filter.
   */
  public void transportTerminated(Attributes transportAttrs) {
  }
}
