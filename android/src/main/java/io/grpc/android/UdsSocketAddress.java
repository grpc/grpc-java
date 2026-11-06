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

package io.grpc.android;

import static com.google.common.base.Preconditions.checkNotNull;

import android.net.LocalSocketAddress;
import android.net.LocalSocketAddress.Namespace;
import java.io.File;
import java.net.SocketAddress;

/**
 * {@link SocketAddress} for an Android Unix domain socket endpoint.
 *
 * <p>Used as the listen address for {@link UdsServerBuilder} servers and reported by {@link
 * io.grpc.Server#getListenSockets()} and {@link io.grpc.Grpc#TRANSPORT_ATTR_LOCAL_ADDR}.
 */
final class UdsSocketAddress extends SocketAddress {
  private static final long serialVersionUID = 1L;

  private final String name;
  private final Namespace namespace;

  private UdsSocketAddress(String name, Namespace namespace) {
    this.name = checkNotNull(name, "name");
    this.namespace = checkNotNull(namespace, "namespace");
  }

  /**
   * Resolves {@code socketFile} with {@link File#getAbsolutePath()} in {@link
   * Namespace#FILESYSTEM}.
   */
  static UdsSocketAddress forFile(File socketFile) {
    return new UdsSocketAddress(
        checkNotNull(socketFile, "socketFile").getAbsolutePath(), Namespace.FILESYSTEM);
  }

  /** Wraps {@code name} (without the leading NUL byte) in {@link Namespace#ABSTRACT}. */
  static UdsSocketAddress forAbstractName(String name) {
    return new UdsSocketAddress(name, Namespace.ABSTRACT);
  }

  /**
   * Returns the socket's name: an absolute file path for {@link Namespace#FILESYSTEM}, or the
   * abstract name (without the leading NUL byte) for {@link Namespace#ABSTRACT}. Mirrors {@link
   * LocalSocketAddress#getName()}.
   */
  String getName() {
    return name;
  }

  Namespace getNamespace() {
    return namespace;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof UdsSocketAddress)) {
      return false;
    }
    UdsSocketAddress that = (UdsSocketAddress) o;
    return name.equals(that.name) && namespace == that.namespace;
  }

  @Override
  public int hashCode() {
    return 31 * name.hashCode() + namespace.hashCode();
  }

  @Override
  public String toString() {
    switch (namespace) {
      case ABSTRACT:
        return "unix-abstract:" + name;
      case FILESYSTEM:
        return "unix:" + name;
      default:
        return namespace + ":" + name;
    }
  }
}
