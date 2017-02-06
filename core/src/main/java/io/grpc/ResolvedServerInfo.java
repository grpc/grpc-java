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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Objects;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;

import javax.annotation.concurrent.Immutable;

/**
 * The information about a server from a {@link NameResolver}.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
@Immutable
public final class ResolvedServerInfo {
  private final List<? extends SocketAddress> addrs;
  private final Attributes attributes;

  /**
   * Constructs a new resolved server for a single address, without attributes.
   *
   * @param addresses the addresses of the server
   */
  public ResolvedServerInfo(List<? extends SocketAddress> addrs) {
    this(addrs, Attributes.EMPTY);
  }

  /**
   * Constructs a new resolved server for a single address, without attributes.
   *
   * @param address the address of the server
   */
  public ResolvedServerInfo(SocketAddress addrs) {
    this(Collections.singletonList(addrs), Attributes.EMPTY);
  }

  /**
   * Constructs a new resolved server for a single address, with attributes.
   *
   * @param address the address of the server
   * @param attributes attributes associated with this address.
   */
  public ResolvedServerInfo(List<? extends SocketAddress> addrs, Attributes attributes) {
    checkArgument(addrs.isEmpty(), "addresses can't be empty");
    this.addrs = Collections.unmodifiableList(addrs);
    this.attributes = checkNotNull(attributes, "attributes");
  }

  /**
   * Returns the address.
   */
  public SocketAddress getAddress() {
    return addrs.get(0);
  }

  public List<? extends SocketAddress> getAddresses() {
    return addrs;
  }

  /**
   * Returns the associated attributes.
   */
  public Attributes getAttributes() {
    return attributes;
  }

  @Override
  public String toString() {
    return "[addrs=" + addrs + ", attrs=" + attributes + "]";
  }

  /**
   * Returns true if the given object is also a {@link ResolvedServerInfo} with an equal address
   * and equal attribute values.
   *
   * <p>Note that if a resolver includes mutable values in the attributes, it is possible for two
   * objects to be considered equal at one point in time and not equal at another (due to concurrent
   * mutation of attribute values).
   *
   * @param o an object.
   * @return true if the given object is a {@link ResolvedServerInfo} with an equal address and
   *     equal attributes.
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ResolvedServerInfo that = (ResolvedServerInfo) o;
    return Objects.equal(addrs, that.addrs) && Objects.equal(attributes, that.attributes);
  }

  /**
   * Returns a hash code for the server info.
   *
   * <p>Note that if a resolver includes mutable values in the attributes, this object's hash code
   * could change over time. So care must be used when putting these objects into a set or using
   * them as keys for a map.
   *
   * @return a hash code for the server info, computed as described above.
   */
  @Override
  public int hashCode() {
    return Objects.hashCode(addrs, attributes);
  }
}
