/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.binder;

import io.grpc.ExperimentalApi;

/**
 * Identifies a gRPC/binder client or server by Android/Linux UID
 * (https://source.android.com/security/app-sandbox).
 *
 * <p>Use {@link PeerUids#REMOTE_PEER} to obtain the client's {@link PeerUid} from the server's
 * {@link io.grpc.Context}
 *
 * <p>The actual integer uid is intentionally not exposed to prevent misuse. If you want the uid for
 * access control, consider one of the existing {@link SecurityPolicies} instead (or propose a new
 * one). If you want the uid to pass to some other Android API, consider one of the static wrapper
 * methods of {@link PeerUids} instead (or propose a new one).
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8022")
public final class PeerUid {

  private final int uid;

  /** Constructs a new instance. Intentionally non-public to prevent misuse. */
  PeerUid(int uid) {
    this.uid = uid;
  }

  /** Returns an identifier for the current process. */
  public static PeerUid forCurrentProcess() {
    return new PeerUid(android.os.Process.myUid());
  }

  /**
   * Returns this peer's Android/Linux uid.
   *
   * <p>Intentionally non-public to prevent misuse.
   */
  int getUid() {
    return uid;
  }

  @Override
  public boolean equals(Object otherObj) {
    if (this == otherObj) {
      return true;
    }
    if (otherObj == null || getClass() != otherObj.getClass()) {
      return false;
    }
    PeerUid otherPeerUid = (PeerUid) otherObj;
    return uid == otherPeerUid.uid;
  }

  @Override
  public int hashCode() {
    return Integer.valueOf(uid).hashCode();
  }

  @Override
  public String toString() {
    return "PeerUid{" + uid + '}';
  }
}