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

import static io.grpc.s2a.internal.handshaker.TLSVersion.TLS_VERSION_1_2;
import static io.grpc.s2a.internal.handshaker.TLSVersion.TLS_VERSION_1_3;

import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** A fake Writer Class to mock the behavior of S2A server. */
final class FakeWriter implements StreamObserver<SessionReq> {
  /** Fake behavior of S2A service. */
  enum Behavior {
    OK_STATUS,
    EMPTY_RESPONSE,
    ERROR_STATUS,
    ERROR_RESPONSE,
    COMPLETE_STATUS,
    BAD_TLS_VERSION_RESPONSE,
  }

  enum VerificationResult {
    UNSPECIFIED,
    SUCCESS,
    FAILURE
  }

  public static final String LEAF_CERT =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIICkDCCAjagAwIBAgIUSAtcrPhNNs1zxv51lIfGOVtkw6QwCgYIKoZIzj0EAwIw\n"
          + "QTEXMBUGA1UECgwOc2VjdXJpdHktcmVhbG0xEDAOBgNVBAsMB2NvbnRleHQxFDAS\n"
          + "BgorBgEEAdZ5AggBDAQyMDIyMCAXDTIzMDcxNDIyMzYwNFoYDzIwNTAxMTI5MjIz\n"
          + "NjA0WjARMQ8wDQYDVQQDDAZ1bnVzZWQwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNC\n"
          + "AAQGFlJpLxJMh4HuUm0DKjnUF7larH3tJvroQ12xpk+pPKQepn4ILoq9lZ8Xd3jz\n"
          + "U98eDRXG5f4VjnX98DDHE4Ido4IBODCCATQwDgYDVR0PAQH/BAQDAgeAMCAGA1Ud\n"
          + "JQEB/wQWMBQGCCsGAQUFBwMCBggrBgEFBQcDATAMBgNVHRMBAf8EAjAAMIGxBgNV\n"
          + "HREBAf8EgaYwgaOGSnNwaWZmZTovL3NpZ25lci1yb2xlLmNvbnRleHQuc2VjdXJp\n"
          + "dHktcmVhbG0ucHJvZC5nb29nbGUuY29tL3JvbGUvbGVhZi1yb2xlgjNzaWduZXIt\n"
          + "cm9sZS5jb250ZXh0LnNlY3VyaXR5LXJlYWxtLnByb2Quc3BpZmZlLmdvb2eCIGZx\n"
          + "ZG4tb2YtdGhlLW5vZGUucHJvZC5nb29nbGUuY29tMB0GA1UdDgQWBBSWSd5Fw6dI\n"
          + "TGpt0m1Uxwf0iKqebzAfBgNVHSMEGDAWgBRm5agVVdpWfRZKM7u6OMuzHhqPcDAK\n"
          + "BggqhkjOPQQDAgNIADBFAiB0sjRPSYy2eFq8Y0vQ8QN4AZ2NMajskvxnlifu7O4U\n"
          + "RwIhANTh5Fkyx2nMYFfyl+W45dY8ODTw3HnlZ4b51hTAdkWl\n"
          + "-----END CERTIFICATE-----";
  public static final String INTERMEDIATE_CERT_2 =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIICQjCCAeigAwIBAgIUKxXRDlnWXefNV5lj5CwhDuXEq7MwCgYIKoZIzj0EAwIw\n"
          + "OzEXMBUGA1UECgwOc2VjdXJpdHktcmVhbG0xEDAOBgNVBAsMB2NvbnRleHQxDjAM\n"
          + "BgNVBAMMBTEyMzQ1MCAXDTIzMDcxNDIyMzYwNFoYDzIwNTAxMTI5MjIzNjA0WjBB\n"
          + "MRcwFQYDVQQKDA5zZWN1cml0eS1yZWFsbTEQMA4GA1UECwwHY29udGV4dDEUMBIG\n"
          + "CisGAQQB1nkCCAEMBDIwMjIwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAT/Zu7x\n"
          + "UYVyg+T/vg2H+y4I6t36Kc4qxD0eqqZjRLYBVKkUQHxBqc14t0DpoROMYQCNd4DF\n"
          + "pcxv/9m6DaJbRk6Ao4HBMIG+MA4GA1UdDwEB/wQEAwIBBjASBgNVHRMBAf8ECDAG\n"
          + "AQH/AgEBMFgGA1UdHgEB/wROMEygSjA1gjNzaWduZXItcm9sZS5jb250ZXh0LnNl\n"
          + "Y3VyaXR5LXJlYWxtLnByb2Quc3BpZmZlLmdvb2cwEYIPcHJvZC5nb29nbGUuY29t\n"
          + "MB0GA1UdDgQWBBRm5agVVdpWfRZKM7u6OMuzHhqPcDAfBgNVHSMEGDAWgBQcjNAh\n"
          + "SCHTj+BW8KrzSSLo2ASEgjAKBggqhkjOPQQDAgNIADBFAiEA6KyGd9VxXDZceMZG\n"
          + "IsbC40rtunFjLYI0mjZw9RcRWx8CIHCIiIHxafnDaCi+VB99NZfzAdu37g6pJptB\n"
          + "gjIY71MO\n"
          + "-----END CERTIFICATE-----";
  public static final String INTERMEDIATE_CERT_1 =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIICODCCAd6gAwIBAgIUXtZECORWRSKnS9rRTJYkiALUXswwCgYIKoZIzj0EAwIw\n"
          + "NzEXMBUGA1UECgwOc2VjdXJpdHktcmVhbG0xDTALBgNVBAsMBHJvb3QxDTALBgNV\n"
          + "BAMMBDEyMzQwIBcNMjMwNzE0MjIzNjA0WhgPMjA1MDExMjkyMjM2MDRaMDsxFzAV\n"
          + "BgNVBAoMDnNlY3VyaXR5LXJlYWxtMRAwDgYDVQQLDAdjb250ZXh0MQ4wDAYDVQQD\n"
          + "DAUxMjM0NTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABAycVTZrjockbpD59f1a\n"
          + "4l1SNL7nSyXz66Guz4eDveQqLmaMBg7vpACfO4CtiAGnolHEffuRtSkdM434m5En\n"
          + "bXCjgcEwgb4wDgYDVR0PAQH/BAQDAgEGMBIGA1UdEwEB/wQIMAYBAf8CAQIwWAYD\n"
          + "VR0eAQH/BE4wTKBKMDWCM3NpZ25lci1yb2xlLmNvbnRleHQuc2VjdXJpdHktcmVh\n"
          + "bG0ucHJvZC5zcGlmZmUuZ29vZzARgg9wcm9kLmdvb2dsZS5jb20wHQYDVR0OBBYE\n"
          + "FByM0CFIIdOP4FbwqvNJIujYBISCMB8GA1UdIwQYMBaAFMX+vebuj/lYfYEC23IA\n"
          + "8HoIW0HsMAoGCCqGSM49BAMCA0gAMEUCIQCfxeXEBd7UPmeImT16SseCRu/6cHxl\n"
          + "kTDsq9sKZ+eXBAIgA+oViAVOUhUQO1/6Mjlczg8NmMy2vNtG4V/7g9dMMVU=\n"
          + "-----END CERTIFICATE-----";

  private static final String PRIVATE_KEY =
      "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgqA2U0ld1OOHLMXWf"
          + "uyN4GSaqhhudEIaKkll3rdIq0M+hRANCAAQGFlJpLxJMh4HuUm0DKjnUF7larH3t"
          + "JvroQ12xpk+pPKQepn4ILoq9lZ8Xd3jzU98eDRXG5f4VjnX98DDHE4Id";
  private static final ImmutableMap<SignatureAlgorithm, String>
      ALGORITHM_TO_SIGNATURE_INSTANCE_IDENTIFIER =
          ImmutableMap.of(
              SignatureAlgorithm.S2A_SSL_SIGN_ECDSA_SECP256R1_SHA256,
              "SHA256withECDSA",
              SignatureAlgorithm.S2A_SSL_SIGN_ECDSA_SECP384R1_SHA384,
              "SHA384withECDSA",
              SignatureAlgorithm.S2A_SSL_SIGN_ECDSA_SECP521R1_SHA512,
              "SHA512withECDSA");

  private boolean fakeWriterClosed = false;
  private Behavior behavior = Behavior.OK_STATUS;
  private StreamObserver<SessionResp> reader;
  private VerificationResult verificationResult = VerificationResult.UNSPECIFIED;
  private String failureReason;
  private PrivateKey privateKey;

  @CanIgnoreReturnValue
  FakeWriter setReader(StreamObserver<SessionResp> reader) {
    this.reader = reader;
    return this;
  }

  @CanIgnoreReturnValue
  FakeWriter setBehavior(Behavior behavior) {
    this.behavior = behavior;
    return this;
  }

  @CanIgnoreReturnValue
  FakeWriter setVerificationResult(VerificationResult verificationResult) {
    this.verificationResult = verificationResult;
    return this;
  }

  @CanIgnoreReturnValue
  FakeWriter setFailureReason(String failureReason) {
    this.failureReason = failureReason;
    return this;
  }

  @CanIgnoreReturnValue
  FakeWriter initializePrivateKey() throws InvalidKeySpecException, NoSuchAlgorithmException {
    privateKey =
        KeyFactory.getInstance("EC")
            .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(PRIVATE_KEY)));
    return this;
  }

  @CanIgnoreReturnValue
  FakeWriter resetPrivateKey() {
    privateKey = null;
    return this;
  }

  void sendUnexpectedResponse() {
    reader.onNext(SessionResp.getDefaultInstance());
  }

  void sendIoError() {
    reader.onError(new IOException("Intended ERROR from FakeWriter."));
  }

  void sendGetTlsConfigResp() {
    reader.onNext(
        SessionResp.newBuilder()
            .setGetTlsConfigurationResp(
                GetTlsConfigurationResp.newBuilder()
                    .setClientTlsConfiguration(
                        GetTlsConfigurationResp.ClientTlsConfiguration.newBuilder()
                            .addCertificateChain(LEAF_CERT)
                            .addCertificateChain(INTERMEDIATE_CERT_2)
                            .addCertificateChain(INTERMEDIATE_CERT_1)
                            .setMinTlsVersion(TLS_VERSION_1_3)
                            .setMaxTlsVersion(TLS_VERSION_1_3)
                            .addCiphersuites(
                                Ciphersuite.CIPHERSUITE_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)
                            .addCiphersuites(
                                Ciphersuite.CIPHERSUITE_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384)
                            .addCiphersuites(
                                Ciphersuite.CIPHERSUITE_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256)))
            .build());
  }

  boolean isFakeWriterClosed() {
    return fakeWriterClosed;
  }

  @Override
  public void onNext(SessionReq sessionReq) {
    switch (behavior) {
      case OK_STATUS:
        reader.onNext(handleResponse(sessionReq));
        break;
      case EMPTY_RESPONSE:
        reader.onNext(SessionResp.getDefaultInstance());
        break;
      case ERROR_STATUS:
        reader.onNext(
            SessionResp.newBuilder()
                .setStatus(
                    Status.newBuilder()
                        .setCode(1)
                        .setDetails("Intended ERROR Status from FakeWriter."))
                .build());
        break;
      case ERROR_RESPONSE:
        reader.onError(new S2AConnectionException("Intended ERROR from FakeWriter."));
        break;
      case COMPLETE_STATUS:
        reader.onCompleted();
        break;
      case BAD_TLS_VERSION_RESPONSE:
        reader.onNext(
            SessionResp.newBuilder()
                .setGetTlsConfigurationResp(
                    GetTlsConfigurationResp.newBuilder()
                        .setClientTlsConfiguration(
                            GetTlsConfigurationResp.ClientTlsConfiguration.newBuilder()
                                .addCertificateChain(LEAF_CERT)
                                .addCertificateChain(INTERMEDIATE_CERT_2)
                                .addCertificateChain(INTERMEDIATE_CERT_1)
                                .setMinTlsVersion(TLS_VERSION_1_3)
                                .setMaxTlsVersion(TLS_VERSION_1_2)))
                .build());
        break;
      default:
        reader.onNext(handleResponse(sessionReq));
    }
  }

  SessionResp handleResponse(SessionReq sessionReq) {
    if (sessionReq.hasGetTlsConfigurationReq()) {
      return handleGetTlsConfigurationReq(sessionReq.getGetTlsConfigurationReq());
    }

    if (sessionReq.hasValidatePeerCertificateChainReq()) {
      return handleValidatePeerCertificateChainReq(sessionReq.getValidatePeerCertificateChainReq());
    }

    if (sessionReq.hasOffloadPrivateKeyOperationReq()) {
      return handleOffloadPrivateKeyOperationReq(sessionReq.getOffloadPrivateKeyOperationReq());
    }

    return SessionResp.newBuilder()
        .setStatus(
            Status.newBuilder().setCode(255).setDetails("No supported operation designated."))
        .build();
  }

  private SessionResp handleGetTlsConfigurationReq(GetTlsConfigurationReq req) {
    if (!req.getConnectionSide().equals(ConnectionSide.CONNECTION_SIDE_CLIENT)) {
      return SessionResp.newBuilder()
          .setStatus(
              Status.newBuilder()
                  .setCode(255)
                  .setDetails("No TLS configuration for the server side."))
          .build();
    }
    return SessionResp.newBuilder()
        .setGetTlsConfigurationResp(
            GetTlsConfigurationResp.newBuilder()
                .setClientTlsConfiguration(
                    GetTlsConfigurationResp.ClientTlsConfiguration.newBuilder()
                        .addCertificateChain(LEAF_CERT)
                        .addCertificateChain(INTERMEDIATE_CERT_2)
                        .addCertificateChain(INTERMEDIATE_CERT_1)
                        .setMinTlsVersion(TLS_VERSION_1_3)
                        .setMaxTlsVersion(TLS_VERSION_1_3)
                        .addCiphersuites(
                            Ciphersuite.CIPHERSUITE_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)
                        .addCiphersuites(
                            Ciphersuite.CIPHERSUITE_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384)
                        .addCiphersuites(
                            Ciphersuite.CIPHERSUITE_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256)))
        .build();
  }

  private SessionResp handleValidatePeerCertificateChainReq(ValidatePeerCertificateChainReq req) {
    if (verifyValidatePeerCertificateChainReq(req)
        && verificationResult == VerificationResult.SUCCESS) {
      return SessionResp.newBuilder()
          .setValidatePeerCertificateChainResp(
              ValidatePeerCertificateChainResp.newBuilder()
                  .setValidationResult(ValidatePeerCertificateChainResp.ValidationResult.SUCCESS))
          .build();
    }
    return SessionResp.newBuilder()
        .setValidatePeerCertificateChainResp(
            ValidatePeerCertificateChainResp.newBuilder()
                .setValidationResult(
                    verificationResult == VerificationResult.FAILURE
                        ? ValidatePeerCertificateChainResp.ValidationResult.FAILURE
                        : ValidatePeerCertificateChainResp.ValidationResult.UNSPECIFIED)
                .setValidationDetails(failureReason))
        .build();
  }

  private boolean verifyValidatePeerCertificateChainReq(ValidatePeerCertificateChainReq req) {
    if (req.getMode() != ValidatePeerCertificateChainReq.VerificationMode.UNSPECIFIED) {
      return false;
    }
    if (req.getClientPeer().getCertificateChainCount() > 0) {
      return true;
    }
    if (req.getServerPeer().getCertificateChainCount() > 0
        && !req.getServerPeer().getServerHostname().isEmpty()) {
      return true;
    }
    return false;
  }

  private SessionResp handleOffloadPrivateKeyOperationReq(OffloadPrivateKeyOperationReq req) {
    if (privateKey == null) {
      return SessionResp.newBuilder()
          .setStatus(Status.newBuilder().setCode(255).setDetails("No Private Key available."))
          .build();
    }
    String signatureIdentifier =
        ALGORITHM_TO_SIGNATURE_INSTANCE_IDENTIFIER.get(req.getSignatureAlgorithm());
    if (signatureIdentifier == null) {
      return SessionResp.newBuilder()
          .setStatus(
              Status.newBuilder()
                  .setCode(255)
                  .setDetails("Only ECDSA key algorithms are supported."))
          .build();
    }

    byte[] signature;
    try {
      Signature sig = Signature.getInstance(signatureIdentifier);
      sig.initSign(privateKey);
      sig.update(req.getRawBytes().toByteArray());
      signature = sig.sign();
    } catch (Exception e) {
      return SessionResp.newBuilder()
          .setStatus(Status.newBuilder().setCode(255).setDetails(e.getMessage()))
          .build();
    }

    return SessionResp.newBuilder()
        .setOffloadPrivateKeyOperationResp(
            OffloadPrivateKeyOperationResp.newBuilder().setOutBytes(ByteString.copyFrom(signature)))
        .build();
  }

  @Override
  public void onError(Throwable t) {
    throw new UnsupportedOperationException("onError is not supported by FakeWriter.");
  }

  @Override
  public void onCompleted() {
    fakeWriterClosed = true;
    reader.onCompleted();
  }
}