/*
 * Copyright 2017 The gRPC Authors
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

import java.io.IOException;
import java.net.SocketAddress;
import javax.annotation.Nullable;

/**
 * A utility class to detect which proxy, if any, should be used for a given
 * {@link java.net.SocketAddress}. This class performs network requests to resolve address names,
 * and should only be used in places that are expected to do IO such as the
 * {@link io.grpc.NameResolver}.
 *
 * <h1>How Proxy works in gRPC</h1>
 *
 * <p>In general the NameResolver is in the place of invoking this class and passing the returned
 * {@link ProxiedSocketAddress} back to the channel.  The {@code ProxiedSocketAddress} is then
 * handled by the transport.  Therefore, in order for proxy to work, the NameResolver needs to
 * call ProxyDetector, and the transport needs to support the specific type of {@code ProxiedSocketAddress}.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/5279")
public interface ProxyDetector {
  /**
   * Given a target address, returns a proxied address if a proxy should be used. If no proxy should
   * be used, then return value will be {@code null}.
   *
   * <p>If the returned {@code ProxiedSocketAddress} contains any address that needs to be resolved
   * locally, it should be resolved before it's returned, and this method throws if unable to
   * resolve it.
   *
   * @param targetServerAddress the target address, which is generally unresolved, because the proxy
   *                            will resolve it.
   */
  @Nullable
  ProxiedSocketAddress proxyFor(SocketAddress targetServerAddress) throws IOException;
}
