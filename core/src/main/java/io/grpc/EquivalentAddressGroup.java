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

package io.grpc;

import com.google.common.base.Preconditions;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A group of {@link SocketAddress}es that are considered equivalent when channel makes connections.
 *
 * <p>Usually the addresses are addresses resolved from the same host name, and connecting to any of
 * them is equally sufficient. They do have order. An address appears earlier on the list is likely
 * to be tried earlier.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
public final class EquivalentAddressGroup {

  private final List<SocketAddress> addrs;

  /**
   * {@link SocketAddress} docs say that the addresses are immutable, so we cache the hashCode.
   */
  private final int hashCode;

  /**
   * List constructor.
   */
  public EquivalentAddressGroup(List<SocketAddress> addrs) {
    Preconditions.checkArgument(!addrs.isEmpty(), "addrs is empty");
    this.addrs = Collections.unmodifiableList(new ArrayList<SocketAddress>(addrs));
    hashCode = this.addrs.hashCode();
  }

  /**
   * Singleton constructor.
   */
  public EquivalentAddressGroup(SocketAddress addr) {
    this.addrs = Collections.singletonList(addr);
    hashCode = addrs.hashCode();
  }

  /**
   * Returns an immutable list of the addresses.
   */
  public List<SocketAddress> getAddresses() {
    return addrs;
  }

  @Override
  public String toString() {
    return addrs.toString();
  }

  @Override
  public int hashCode() {
    // Avoids creating an iterator on the underlying array list.
    return hashCode;
  }

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
    return true;
  }
}
