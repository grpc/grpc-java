/*
 * Copyright 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc;

import com.google.common.base.Objects;
import io.grpc.Attributes.Key;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Set;
import javax.annotation.concurrent.Immutable;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * The information about a server from a {@link NameResolver}.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
@Immutable
public final class ResolvedServerInfo {
  private final SocketAddress address;
  private final Attributes attributes;

  /**
   * Constructor.
   *
   * @param address the address object
   * @param attributes attributes associated with this address.
   */
  public ResolvedServerInfo(SocketAddress address, Attributes attributes) {
    this.address = checkNotNull(address);
    // TODO(jhump): require not null?
    this.attributes = attributes == null ? Attributes.EMPTY : attributes;
  }

  /**
   * Returns the address.
   */
  public SocketAddress getAddress() {
    return address;
  }

  /**
   * Returns the associated attributes.
   */
  public Attributes getAttributes() {
    return attributes;
  }

  @Override
  public String toString() {
    return "[address=" + address + ", attrs=" + attributes + "]";
  }

  /**
   * Returns true if the given object is also a {@link ResolvedServerInfo} with an equal address
   * and equal attribute values.
   *
   * <p>Two objects have equal attribute values if their {@linkplain #getAttributes() attributes}
   * have the same set of {@linkplain Attributes#keys() keys} and the values associated with each
   * key are equal.
   *
   * <p>Note that if a resolver includes mutable values in the attributes, it is possible for two
   * objects to be considered equal at one point in time and not equal at another (due to concurrent
   * mutation of attribute values).
   *
   * @param o an object
   * @return true if the given object is a {@link ResolvedServerInfo} with an equal address and
   * equal attributes
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ResolvedServerInfo that = (ResolvedServerInfo) o;
    return Objects.equal(address, that.address) &&
        areAttributesEqual(attributes, that.attributes);
  }

  /**
   * Returns a hash code for the server info. The hash code is computed like so:<pre>{@code
   * address.hashCode() * 31 + hash(attributes)
   * }</pre>
   *
   * <p>The hash of the attributes is computed as if the attributes were a {@link Map#hashCode()
   * Map} whose entries consist of all of the attributes' {@linkplain Attributes#keys() keys} and
   * their associated {@linkplain Attributes#get(Key) values}.
   *
   * <p>Note that if a resolver includes mutable values in the attributes, this object's hash code
   * could change over time. So care must be used when putting these objects into a set or using
   * them as keys for a map.
   *
   * @return a hash code for the server info, computed as described above
   */
  @Override
  public int hashCode() {
    return address.hashCode() * 31 + attributesHashCode(attributes);
  }

  private static boolean areAttributesEqual(Attributes a1, Attributes a2) {
    Set<Key<?>> k1 = a1.keys();
    Set<Key<?>> k2 = a2.keys();
    if (!k1.equals(k2)) {
      return false;
    }
    for (Key<?> key : k1) {
      Object o1 = a1.get(key);
      Object o2 = a2.get(key);
      if (!Objects.equal(o1, o2)) {
        return false;
      }
    }
    return true;
  }

  private static int attributesHashCode(Attributes attributes) {
    int hash = 0;
    for (Key<?> key : attributes.keys()) {
      Object o = attributes.get(key);
      hash += key.hashCode() ^ (o == null ? 0 : o.hashCode());
    }
    return hash;
  }
}
