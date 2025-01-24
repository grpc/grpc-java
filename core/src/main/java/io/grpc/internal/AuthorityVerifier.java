package io.grpc.internal;

import io.grpc.Status;

public interface AuthorityVerifier {
  Status verifyAuthority(String authority);
}
