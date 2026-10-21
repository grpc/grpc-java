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

package io.grpc.binder.internal;

import io.grpc.Internal;
import java.net.SocketAddress;

/** An address to represent a binding from a remote client. */
@Internal
public final class BoundClientAddress extends SocketAddress {

  private static final long serialVersionUID = 0L;

  /** The UID of the address. For incoming binder transactions, this is all the info we have. */
  private final int uid;

  public BoundClientAddress(int uid) {
    this.uid = uid;
  }

  @Override
  public int hashCode() {
    return uid;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof BoundClientAddress) {
      BoundClientAddress that = (BoundClientAddress) obj;
      return uid == that.uid;
    }
    return false;
  }

  @Override
  public String toString() {
    return "BoundClientAddress[" + uid + "]";
  }
}
