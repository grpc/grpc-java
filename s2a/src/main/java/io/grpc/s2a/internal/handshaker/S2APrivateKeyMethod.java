/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.s2a.internal.handshaker;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ByteString;
import io.grpc.s2a.internal.handshaker.S2AIdentity;
import io.netty.handler.ssl.OpenSslPrivateKeyMethod;
import java.io.IOException;
import java.util.Optional;
import javax.annotation.concurrent.NotThreadSafe;
import javax.net.ssl.SSLEngine;

/**
 * Handles requests on signing bytes with a private key designated by {@code stub}.
 *
 * <p>This is done by sending the to-be-signed bytes to an S2A server (designated by {@code stub})
 * and read the signature from the server.
 *
 * <p>OpenSSL libraries must be appropriately initialized before using this class. One possible way
 * to initialize OpenSSL library is to call {@code
 * GrpcSslContexts.configure(SslContextBuilder.forClient());}.
 */
@NotThreadSafe
final class S2APrivateKeyMethod implements OpenSslPrivateKeyMethod {
  private final S2AStub stub;
  private final Optional<S2AIdentity> localIdentity;
  private static final ImmutableMap<Integer, SignatureAlgorithm>
      OPENSSL_TO_S2A_SIGNATURE_ALGORITHM_MAP =
          ImmutableMap.of(
              OpenSslPrivateKeyMethod.SSL_SIGN_RSA_PKCS1_SHA256,
              SignatureAlgorithm.S2A_SSL_SIGN_RSA_PKCS1_SHA256,
              OpenSslPrivateKeyMethod.SSL_SIGN_RSA_PKCS1_SHA384,
              SignatureAlgorithm.S2A_SSL_SIGN_RSA_PKCS1_SHA384,
              OpenSslPrivateKeyMethod.SSL_SIGN_RSA_PKCS1_SHA512,
              SignatureAlgorithm.S2A_SSL_SIGN_RSA_PKCS1_SHA512,
              OpenSslPrivateKeyMethod.SSL_SIGN_ECDSA_SECP256R1_SHA256,
              SignatureAlgorithm.S2A_SSL_SIGN_ECDSA_SECP256R1_SHA256,
              OpenSslPrivateKeyMethod.SSL_SIGN_ECDSA_SECP384R1_SHA384,
              SignatureAlgorithm.S2A_SSL_SIGN_ECDSA_SECP384R1_SHA384,
              OpenSslPrivateKeyMethod.SSL_SIGN_ECDSA_SECP521R1_SHA512,
              SignatureAlgorithm.S2A_SSL_SIGN_ECDSA_SECP521R1_SHA512,
              OpenSslPrivateKeyMethod.SSL_SIGN_RSA_PSS_RSAE_SHA256,
              SignatureAlgorithm.S2A_SSL_SIGN_RSA_PSS_RSAE_SHA256,
              OpenSslPrivateKeyMethod.SSL_SIGN_RSA_PSS_RSAE_SHA384,
              SignatureAlgorithm.S2A_SSL_SIGN_RSA_PSS_RSAE_SHA384,
              OpenSslPrivateKeyMethod.SSL_SIGN_RSA_PSS_RSAE_SHA512,
              SignatureAlgorithm.S2A_SSL_SIGN_RSA_PSS_RSAE_SHA512);

  public static S2APrivateKeyMethod create(S2AStub stub, Optional<S2AIdentity> localIdentity) {
    checkNotNull(stub);
    return new S2APrivateKeyMethod(stub, localIdentity);
  }

  private S2APrivateKeyMethod(S2AStub stub, Optional<S2AIdentity> localIdentity) {
    this.stub = stub;
    this.localIdentity = localIdentity;
  }

  /**
   * Converts the signature algorithm to an enum understood by S2A.
   *
   * @param signatureAlgorithm the int representation of the signature algorithm define by {@code
   *     OpenSslPrivateKeyMethod}.
   * @return the signature algorithm enum defined by S2A proto.
   * @throws UnsupportedOperationException if the algorithm is not supported by S2A.
   */
  @VisibleForTesting
  static SignatureAlgorithm convertOpenSslSignAlgToS2ASignAlg(int signatureAlgorithm) {
    SignatureAlgorithm sig = OPENSSL_TO_S2A_SIGNATURE_ALGORITHM_MAP.get(signatureAlgorithm);
    if (sig == null) {
      throw new UnsupportedOperationException(
          String.format("Signature Algorithm %d is not supported.", signatureAlgorithm));
    }
    return sig;
  }

  /**
   * Signs the input bytes by sending the request to the S2A srever.
   *
   * @param engine not used.
   * @param signatureAlgorithm the {@link OpenSslPrivateKeyMethod}'s signature algorithm
   *     representation
   * @param input the bytes to be signed.
   * @return the signature of the {@code input}.
   * @throws IOException if the connection to the S2A server is corrupted.
   * @throws InterruptedException if the connection to the S2A server is interrupted.
   * @throws S2AConnectionException if the response from the S2A server does not contain valid data.
   */
  @Override
  public byte[] sign(SSLEngine engine, int signatureAlgorithm, byte[] input)
      throws IOException, InterruptedException {
    checkArgument(input.length > 0, "No bytes to sign.");
    SignatureAlgorithm s2aSignatureAlgorithm =
        convertOpenSslSignAlgToS2ASignAlg(signatureAlgorithm);
    SessionReq.Builder reqBuilder =
        SessionReq.newBuilder()
            .setOffloadPrivateKeyOperationReq(
                OffloadPrivateKeyOperationReq.newBuilder()
                    .setOperation(OffloadPrivateKeyOperationReq.PrivateKeyOperation.SIGN)
                    .setSignatureAlgorithm(s2aSignatureAlgorithm)
                    .setRawBytes(ByteString.copyFrom(input)));
    if (localIdentity.isPresent()) {
      reqBuilder.setLocalIdentity(localIdentity.get().getIdentity());
    }

    SessionResp resp = stub.send(reqBuilder.build());

    if (resp.hasStatus() && resp.getStatus().getCode() != 0) {
      throw new S2AConnectionException(
          String.format(
              "Error occurred in response from S2A, error code: %d, error message: \"%s\".",
              resp.getStatus().getCode(), resp.getStatus().getDetails()));
    }
    if (!resp.hasOffloadPrivateKeyOperationResp()) {
      throw new S2AConnectionException("No valid response received from S2A.");
    }
    return resp.getOffloadPrivateKeyOperationResp().getOutBytes().toByteArray();
  }

  @Override
  public byte[] decrypt(SSLEngine engine, byte[] input) {
    throw new UnsupportedOperationException("decrypt is not supported.");
  }
}