/*
 * Copyright 2018 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import javax.annotation.Nullable;

/**
 * A {@link SocketAddress} that may contain proxy information. If a proxy address is provided,
 * then it must be used.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/4645")
public final class ProxySocketAddress {
  private final SocketAddress address;
  private final InetSocketAddress proxyAddress;
  private final String username;
  private final String password;

  ProxySocketAddress(
      SocketAddress address,
      InetSocketAddress proxyAddress,
      String username,
      String password) {
    this.address = checkNotNull(address, "address");
    this.proxyAddress = proxyAddress;
    this.username = username;
    this.password = password;
  }

  /**
   * Creates an address that does not use a proxy. The address must already be resolved if it
   * is an {@link InetSocketAddress}.
   */
  public static ProxySocketAddress withoutProxy(SocketAddress address) {
    checkNotNull(address, "address");
    if (address instanceof InetSocketAddress) {
      checkArgument(!((InetSocketAddress) address).isUnresolved(), "address must be resolved");
    }
    return new ProxySocketAddress(address, null, null, null);
  }

  /**
   * Creates an address that uses a proxy. The proxy address must already be resolved.
   */
  public static ProxySocketAddress withProxy(
      InetSocketAddress address,
      InetSocketAddress proxyAddress,
      @Nullable String username,
      @Nullable String password) {
    checkNotNull(proxyAddress, "proxyAddress");
    checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
    return new ProxySocketAddress(
        address,
        proxyAddress,
        username,
        password);
  }

  /**
   * Returns the address.
   */
  public SocketAddress getAddress() {
    return address;
  }

  /**
   * Returns the proxy. If null, then no proxy must be used. If non null, then the proxy must be
   * used.
   */
  @Nullable
  public InetSocketAddress getProxyAddress() {
    return proxyAddress;
  }

  /**
   * Only applicable if {@link #getProxyAddress()} is non null. If the proxy does not require
   * authentication, then this method returns null. Throws an {@link IllegalStateException}
   * if no proxy is used.
   */
  @Nullable
  public String getUsername() {
    if (proxyAddress == null) {
      throw new IllegalStateException("Can only be called if a proxy is provided");
    }
    return username;
  }

  /**
   * Only applicable if {@link #getProxyAddress()} is non null. If the proxy does not require
   * authentication, then this method returns null. Throws an {@link IllegalStateException}
   * if no proxy is used.
   */
  @Nullable
  public String getPassword() {
    if (proxyAddress == null) {
      throw new IllegalStateException("Can only be called if a proxy is provided");
    }
    return password;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("address", this.address)
        .add("proxyAddress", proxyAddress)
        .toString();
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(address, proxyAddress, username, password);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof ProxySocketAddress)) {
      return false;
    }
    ProxySocketAddress that = (ProxySocketAddress) o;
    return Objects.equal(address, that.address)
        && Objects.equal(proxyAddress, that.proxyAddress)
        && Objects.equal(username, that.username)
        && Objects.equal(password, that.password);
  }
}
