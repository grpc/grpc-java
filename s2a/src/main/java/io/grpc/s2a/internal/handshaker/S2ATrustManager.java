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

import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import io.grpc.s2a.internal.handshaker.S2AIdentity;
import io.grpc.s2a.internal.handshaker.ValidatePeerCertificateChainReq.VerificationMode;
import java.io.IOException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Optional;
import javax.annotation.concurrent.NotThreadSafe;
import javax.net.ssl.X509TrustManager;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Offloads verification of the peer certificate chain to S2A. */
@NotThreadSafe
final class S2ATrustManager implements X509TrustManager {
  private final Optional<S2AIdentity> localIdentity;
  private final S2AStub stub;
  private final String hostname;

  static S2ATrustManager createForClient(
      S2AStub stub, String hostname, Optional<S2AIdentity> localIdentity) {
    checkNotNull(stub);
    checkNotNull(hostname);
    return new S2ATrustManager(stub, hostname, localIdentity);
  }

  private S2ATrustManager(S2AStub stub, String hostname, Optional<S2AIdentity> localIdentity) {
    this.stub = stub;
    this.hostname = hostname;
    this.localIdentity = localIdentity;
  }

  /**
   * Validates the given certificate chain provided by the peer.
   *
   * @param chain the peer certificate chain
   * @param authType the authentication type based on the client certificate
   * @throws IllegalArgumentException if null or zero-length chain is passed in for the chain
   *     parameter.
   * @throws CertificateException if the certificate chain is not trusted by this TrustManager.
   */
  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    checkPeerTrusted(chain, /* isCheckingClientCertificateChain= */ true);
  }

  /**
   * Validates the given certificate chain provided by the peer.
   *
   * @param chain the peer certificate chain
   * @param authType the authentication type based on the client certificate
   * @throws IllegalArgumentException if null or zero-length chain is passed in for the chain
   *     parameter.
   * @throws CertificateException if the certificate chain is not trusted by this TrustManager.
   */
  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    checkPeerTrusted(chain, /* isCheckingClientCertificateChain= */ false);
  }

  /**
   * Returns null because the accepted issuers are held in S2A and this class receives decision made
   * from S2A on the fly about which to use to verify a given chain.
   *
   * @return null.
   */
  @Override
  public X509Certificate @Nullable [] getAcceptedIssuers() {
    return null;
  }

  private void checkPeerTrusted(X509Certificate[] chain, boolean isCheckingClientCertificateChain)
      throws CertificateException {
    checkNotNull(chain);
    checkArgument(chain.length > 0, "Certificate chain has zero certificates.");

    ValidatePeerCertificateChainReq.Builder validatePeerCertificateChainReq =
        ValidatePeerCertificateChainReq.newBuilder().setMode(VerificationMode.UNSPECIFIED);
    if (isCheckingClientCertificateChain) {
      validatePeerCertificateChainReq.setClientPeer(
          ValidatePeerCertificateChainReq.ClientPeer.newBuilder()
              .addAllCertificateChain(certificateChainToDerChain(chain)));
    } else {
      validatePeerCertificateChainReq.setServerPeer(
          ValidatePeerCertificateChainReq.ServerPeer.newBuilder()
              .addAllCertificateChain(certificateChainToDerChain(chain))
              .setServerHostname(hostname));
    }

    SessionReq.Builder reqBuilder =
        SessionReq.newBuilder().setValidatePeerCertificateChainReq(validatePeerCertificateChainReq);
    if (localIdentity.isPresent()) {
      reqBuilder.setLocalIdentity(localIdentity.get().getIdentity());
    }

    SessionResp resp;
    try {
      resp = stub.send(reqBuilder.build());
    } catch (IOException e) {
      throw new CertificateException("Failed to send request to S2A.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CertificateException("Failed to send request to S2A.", e);
    }
    if (resp.hasStatus() && resp.getStatus().getCode() != 0) {
      throw new CertificateException(
          String.format(
              "Error occurred in response from S2A, error code: %d, error message: %s.",
              resp.getStatus().getCode(), resp.getStatus().getDetails()));
    }

    if (!resp.hasValidatePeerCertificateChainResp()) {
      throw new CertificateException("No valid response received from S2A.");
    }

    ValidatePeerCertificateChainResp validationResult = resp.getValidatePeerCertificateChainResp();
    if (validationResult.getValidationResult()
        != ValidatePeerCertificateChainResp.ValidationResult.SUCCESS) {
      throw new CertificateException(validationResult.getValidationDetails());
    }
  }

  private static ImmutableList<ByteString> certificateChainToDerChain(X509Certificate[] chain)
      throws CertificateEncodingException {
    ImmutableList.Builder<ByteString> derChain = ImmutableList.<ByteString>builder();
    for (X509Certificate certificate : chain) {
      derChain.add(ByteString.copyFrom(certificate.getEncoded()));
    }
    return derChain.build();
  }
}