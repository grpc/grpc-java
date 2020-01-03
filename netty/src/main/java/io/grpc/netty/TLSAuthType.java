package io.grpc.netty;

public enum TLSAuthType {
  NoPeerCert,
  RequirePeerCert,
  RequireAndVerifyPeerCert,
}
