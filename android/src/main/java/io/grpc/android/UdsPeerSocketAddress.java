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

import java.net.SocketAddress;

/** {@link SocketAddress} for the peer process of an accepted UDS connection. */
final class UdsPeerSocketAddress extends SocketAddress {
  private static final long serialVersionUID = 1L;

  private final int uid;
  private final int gid;
  private final int pid;

  UdsPeerSocketAddress(int uid, int gid, int pid) {
    this.uid = uid;
    this.gid = gid;
    this.pid = pid;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof UdsPeerSocketAddress)) {
      return false;
    }
    UdsPeerSocketAddress that = (UdsPeerSocketAddress) o;
    return uid == that.uid && gid == that.gid && pid == that.pid;
  }

  @Override
  public int hashCode() {
    int result = uid;
    result = 31 * result + gid;
    result = 31 * result + pid;
    return result;
  }

  @Override
  public String toString() {
    return "unix-peer:uid=" + uid + ",gid=" + gid + ",pid=" + pid;
  }
}
