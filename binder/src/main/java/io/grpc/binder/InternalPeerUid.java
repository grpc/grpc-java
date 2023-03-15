package io.grpc.binder;

import io.grpc.Internal;

/** Internal accessors for {@link PeerUid}. */
@Internal
public final class InternalPeerUid {
  /** Returns the result of calling {@link PeerUid#getUid()}. */
  @Internal
  public static int getUid(PeerUid peerUid) {
    return peerUid.getUid();
  }

  /** Returns the result of calling new {@link PeerUid}. */
  @Internal
  public static PeerUid newPeerUid(int uid) {
    return new PeerUid(uid);
  }

  private InternalPeerUid() {}
}