/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.grpclb;

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.EquivalentAddressGroup;

/**
 * Represents a balancer address entry.
 */
class LbAddressGroup {
  private final EquivalentAddressGroup addresses;
  private final String authority;

  LbAddressGroup(EquivalentAddressGroup addresses, String authority) {
    this.addresses = checkNotNull(addresses, "addresses");
    this.authority = checkNotNull(authority, "authority");
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof LbAddressGroup)) {
      return false;
    }
    LbAddressGroup otherGroup = (LbAddressGroup) other;
    return addresses.equals(otherGroup.addresses) && authority.equals(otherGroup.authority);
  }

  @Override
  public int hashCode() {
    return addresses.hashCode();
  }

  EquivalentAddressGroup getAddresses() {
    return addresses;
  }

  String getAuthority() {
    return authority;
  }
}
