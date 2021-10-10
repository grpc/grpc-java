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

package io.grpc.inprocess;

import static com.google.common.base.Preconditions.checkNotNull;

import java.net.SocketAddress;
import javax.annotation.Nullable;

/**
 * Custom SocketAddress class for {@link InProcessTransport}.
 */
public final class InProcessSocketAddress extends SocketAddress {
  private static final long serialVersionUID = -2803441206326023474L;

  private final String name;
  @Nullable
  private final InProcessServer server;

  /**
   * @param name - The name of the inprocess channel or server.
   * @since 1.0.0
   */
  public InProcessSocketAddress(String name) {
    this(name, null);
  }

  /**
   * @param name - The name of the inprocess channel or server.
   * @param server - The concrete {@link InProcessServer} instance, Will be present on the listen
   *     address of an anonymous server.
   */
  InProcessSocketAddress(String name, @Nullable InProcessServer server) {
    this.name = checkNotNull(name, "name");
    this.server = server;
  }

  /**
   * Gets the name of the inprocess channel or server.
   *
   * @since 1.0.0
   */
  public String getName() {
    return name;
  }

  @Nullable
  InProcessServer getServer() {
    return server;
  }

  /**
   * @since 1.14.0
   */
  @Override
  public String toString() {
    return name;
  }

  /**
   * @since 1.15.0
   */
  @Override
  public int hashCode() {
    if (server != null) {
      // Since there's a single canonical InProcessSocketAddress instance for
      // an anonymous inprocess server, we can just use identity equality.
      return super.hashCode();
    } else {
      return name.hashCode();
    }
  }

  /**
   * @since 1.15.0
   */
  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof InProcessSocketAddress)) {
      return false;
    }
    InProcessSocketAddress addr = (InProcessSocketAddress) obj;
    if (server == null && addr.server == null) {
      return name.equals(addr.name);
    } else {
      // Since there's a single canonical InProcessSocketAddress instance for
      // an anonymous inprocess server, we can just use identity equality.
      return addr == this;
    }
  }
}
