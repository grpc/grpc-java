/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver.ResolutionResultAttr;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import javax.annotation.Nullable;

final class AddressFilter {
  @ResolutionResultAttr
  static final Attributes.Key<PathChain> PATH_CHAIN_KEY =
      Attributes.Key.create("io.grpc.xds.AddressFilter.PATH_CHAIN_KEY");

  // Prevent instantiation.
  private AddressFilter() {}

  /**
   * Returns a new EquivalentAddressGroup by setting a path filter to the given
   * EquivalentAddressGroup. This method does not modify the input address.
   */
  static EquivalentAddressGroup setPathFilter(EquivalentAddressGroup address, List<String> names) {
    checkNotNull(address, "address");
    checkNotNull(names, "names");
    Attributes.Builder attrBuilder = address.getAttributes().toBuilder()
        .set(PATH_CHAIN_KEY, createPathChain(names));
    return new EquivalentAddressGroup(address.getAddresses(), attrBuilder.build());
  }

  /**
   * Creates a PathChain that can be set in an EquivalentAddressGroup's Attributes as a value of
   * PATH_CHAIN_KEY.
   */
  @Nullable static PathChain createPathChain(List<String> names) {
    checkNotNull(names, "names");
    PathChain current = null;
    ListIterator<String> iter = names.listIterator(names.size());
    while (iter.hasPrevious()) {
      current = new PathChain(iter.previous(), current);
    }
    return current;
  }

  /**
   * Returns the next level hierarchical addresses derived from the given hierarchical addresses
   * with the given filter name (any non-hierarchical addresses in the input will be ignored).
   * This method does not modify the input addresses.
   */
  static List<EquivalentAddressGroup> filter(List<EquivalentAddressGroup> addresses, String name) {
    checkNotNull(addresses, "addresses");
    checkNotNull(name, "name");
    List<EquivalentAddressGroup> filteredAddresses = new ArrayList<>();
    for (EquivalentAddressGroup address : addresses) {
      PathChain pathChain = address.getAttributes().get(PATH_CHAIN_KEY);
      if (pathChain != null && pathChain.name.equals(name)) {
        Attributes filteredAddressAttrs =
            address.getAttributes().toBuilder().set(PATH_CHAIN_KEY, pathChain.next).build();
        filteredAddresses.add(
            new EquivalentAddressGroup(address.getAddresses(), filteredAddressAttrs));
      }
    }
    return Collections.unmodifiableList(filteredAddresses);
  }

  static final class PathChain {
    final String name;
    @Nullable final PathChain next;

    PathChain(String name, @Nullable PathChain next) {
      this.name = checkNotNull(name, "name");
      this.next = next;
    }

    @Override
    public String toString() {
      return name + (next == null ? "" : ", " + next);
    }
  }
}
