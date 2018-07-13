/*
 * Copyright 2015 The gRPC Authors
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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A group of {@link ProxySocketAddress}es that are considered equivalent when channel makes
 * connections.
 *
 * <p>Usually the addresses are addresses resolved from the same host name, and connecting to any of
 * them is equally sufficient. They do have order. An address appears earlier on the list is likely
 * to be tried earlier.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
public final class EquivalentAddressGroup {

  private final List<ProxySocketAddress> addrs;
  private final Attributes attrs;

  /**
   * {@link SocketAddress} docs say that the addresses are immutable, so we cache the hashCode.
   */
  private final int hashCode;

  /**
   * List constructor without {@link Attributes}. The addresses are used without proxies.
   */
  public EquivalentAddressGroup(List<SocketAddress> addrs) {
    this(addrs, Attributes.EMPTY);
  }

  /**
   * List constructor with {@link Attributes}. The addresses are used without proxies.
   */
  public EquivalentAddressGroup(List<SocketAddress> addrs, Attributes attrs) {
    Preconditions.checkArgument(!addrs.isEmpty(), "addrs is empty");
    this.addrs = Collections.unmodifiableList(withoutProxy(addrs));
    this.attrs = Preconditions.checkNotNull(attrs, "attrs");
    // Attributes may contain mutable objects, which means Attributes' hashCode may change over
    // time, thus we don't cache Attributes' hashCode.
    hashCode = this.addrs.hashCode();
  }

  /**
   * Singleton constructor without Attributes. The address is used without a proxy.
   */
  public EquivalentAddressGroup(SocketAddress addr) {
    this(addr, Attributes.EMPTY);
  }

  /**
   * Singleton constructor with Attributes. The address is used without a proxy.
   */
  public EquivalentAddressGroup(SocketAddress addr, Attributes attrs) {
    this(Collections.singletonList(addr), attrs);
  }

  /**
   * List constructor with {@link Attributes}. The extra ignored parameter exists to prevent
   * aliasing with the other constructor that takes a (List, Attributes).
   */
  private EquivalentAddressGroup(List<ProxySocketAddress> addrs, Attributes attrs, int ignored) {
    Preconditions.checkArgument(!addrs.isEmpty(), "addrs is empty");
    this.addrs = Collections.unmodifiableList(new ArrayList<ProxySocketAddress>(addrs));
    this.attrs = Preconditions.checkNotNull(attrs, "attrs");
    // Attributes may contain mutable objects, which means Attributes' hashCode may change over
    // time, thus we don't cache Attributes' hashCode.
    hashCode = this.addrs.hashCode();
  }

  /**
   * List constructor.
   */
  public static EquivalentAddressGroup createFromList(List<ProxySocketAddress> addrs) {
    return new EquivalentAddressGroup(addrs, Attributes.EMPTY, 0);
  }

  /**
   * List constructor with {@link Attributes}.
   */
  public static EquivalentAddressGroup createFromList(
      List<ProxySocketAddress> addrs, Attributes attrs) {
    return new EquivalentAddressGroup(addrs, attrs, 0);
  }

  /**
   * Singleton constructor without Attributes.
   */
  public EquivalentAddressGroup(ProxySocketAddress addr) {
    this(ImmutableList.of(addr), Attributes.EMPTY, 0);
  }

  /**
   * Singleton constructor with Attributes.
   */
  public EquivalentAddressGroup(ProxySocketAddress addr, Attributes attrs) {
    this(Collections.singletonList(addr), attrs, 0);
  }

  /**
   * Returns an immutable list of the addresses, without proxy information.
   *
   * @deprecated use {@link #getProxySocketAddresses()}, which retains proxy information.
   */
  @Deprecated
  public List<SocketAddress> getAddresses() {
    List<SocketAddress> ret = new ArrayList<SocketAddress>();
    for (ProxySocketAddress addr : addrs) {
      ret.add(addr.getAddress());
    }
    return ret;
  }

  /**
   * Returns an immutable list of the addresses.
   */
  public List<ProxySocketAddress> getProxySocketAddresses() {
    return addrs;
  }

  /**
   * Returns the attributes.
   */
  public Attributes getAttributes() {
    return attrs;
  }

  @Override
  public String toString() {
    // TODO(zpencer): Summarize return value if addr is very large
    return "[addrs=" + addrs + ", attrs=" + attrs + "]";
  }

  @Override
  public int hashCode() {
    // Avoids creating an iterator on the underlying array list.
    return hashCode;
  }

  /**
   * Returns true if the given object is also an {@link EquivalentAddressGroup} with an equal
   * address list and equal attribute values.
   *
   * <p>Note that if the attributes include mutable values, it is possible for two objects to be
   * considered equal at one point in time and not equal at another (due to concurrent mutation of
   * attribute values).
   */
  @Override
  public boolean equals(Object other) {
    if (!(other instanceof EquivalentAddressGroup)) {
      return false;
    }
    EquivalentAddressGroup that = (EquivalentAddressGroup) other;
    if (addrs.size() != that.addrs.size()) {
      return false;
    }
    // Avoids creating an iterator on the underlying array list.
    for (int i = 0; i < addrs.size(); i++) {
      if (!addrs.get(i).equals(that.addrs.get(i))) {
        return false;
      }
    }
    if (!attrs.equals(that.attrs)) {
      return false;
    }
    return true;
  }

  private static List<ProxySocketAddress> withoutProxy(List<SocketAddress> addrs) {
    List<ProxySocketAddress> ret = new ArrayList<ProxySocketAddress>();
    for (SocketAddress addr : addrs) {
      ret.add(ProxySocketAddress.withoutProxy(addr));
    }
    return ret;
  }
}
