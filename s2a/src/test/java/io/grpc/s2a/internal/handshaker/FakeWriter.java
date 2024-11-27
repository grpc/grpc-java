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
import com.google.common.io.CharStreams;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import io.grpc.util.CertificateUtils;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.InvalidKeySpecException;

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

  private static final ClassLoader classLoader = FakeWriter.class.getClassLoader();
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

  public static String convertInputStreamToString(InputStream is) throws IOException {
    return CharStreams.toString(new InputStreamReader(is, StandardCharsets.UTF_8));
  }

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
  FakeWriter initializePrivateKey() throws InvalidKeySpecException, NoSuchAlgorithmException,
                    IOException, FileNotFoundException, UnsupportedEncodingException {
    try (
        InputStream keyInputStream = classLoader.getResourceAsStream("leaf_key_ec.pem");
    ) {
      privateKey = CertificateUtils.getPrivateKey(keyInputStream);
    }
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
    String leafCertString = "";
    String cert2String = "";
    String cert1String = "";
    try (
        InputStream leafCert = classLoader.getResourceAsStream("leaf_cert_ec.pem");
        InputStream cert2 = classLoader.getResourceAsStream("int_cert2_ec.pem");
        InputStream cert1 = classLoader.getResourceAsStream("int_cert1_ec.pem");
    ) {
      leafCertString = FakeWriter.convertInputStreamToString(leafCert);
      cert2String = FakeWriter.convertInputStreamToString(cert2);
      cert1String = FakeWriter.convertInputStreamToString(cert1);
    } catch (IOException e) {
      reader.onError(e);
    }
    reader.onNext(
        SessionResp.newBuilder()
            .setGetTlsConfigurationResp(
                GetTlsConfigurationResp.newBuilder()
                    .setClientTlsConfiguration(
                        GetTlsConfigurationResp.ClientTlsConfiguration.newBuilder()
                            .addCertificateChain(leafCertString)
                            .addCertificateChain(cert1String)
                            .addCertificateChain(cert2String)
                            .setMinTlsVersion(TLS_VERSION_1_3)
                            .setMaxTlsVersion(TLS_VERSION_1_3)
                            .addCiphersuites(
                                Ciphersuite.CIPHERSUITE_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)
                            .addCiphersuites(
                                Ciphersuite.CIPHERSUITE_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384)
                            .addCiphersuites(
                                Ciphersuite
                                .CIPHERSUITE_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256)))
            .build());
  }

  boolean isFakeWriterClosed() {
    return fakeWriterClosed;
  }

  @Override
  public void onNext(SessionReq sessionReq) {
    switch (behavior) {
      case OK_STATUS:
        try {
          reader.onNext(handleResponse(sessionReq));
        } catch (IOException e) {
          reader.onError(e);
        }
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
        String leafCertString = "";
        String cert2String = "";
        String cert1String = "";
        try (
            InputStream leafCert = classLoader.getResourceAsStream("leaf_cert_ec.pem");
            InputStream cert2 = classLoader.getResourceAsStream("int_cert2_ec.pem");
            InputStream cert1 = classLoader.getResourceAsStream("int_cert1_ec.pem");
        ) {
          leafCertString = FakeWriter.convertInputStreamToString(leafCert);
          cert2String = FakeWriter.convertInputStreamToString(cert2);
          cert1String = FakeWriter.convertInputStreamToString(cert1);
        } catch (IOException e) {
          reader.onError(e);
        }
        reader.onNext(
            SessionResp.newBuilder()
                .setGetTlsConfigurationResp(
                    GetTlsConfigurationResp.newBuilder()
                        .setClientTlsConfiguration(
                            GetTlsConfigurationResp.ClientTlsConfiguration.newBuilder()
                                .addCertificateChain(leafCertString)
                                .addCertificateChain(cert1String)
                                .addCertificateChain(cert2String)
                                .setMinTlsVersion(TLS_VERSION_1_3)
                                .setMaxTlsVersion(TLS_VERSION_1_2)))
                .build());
        break;
      default:
        try {
          reader.onNext(handleResponse(sessionReq));
        } catch (IOException e) {
          reader.onError(e);
        }
    }
  }

  SessionResp handleResponse(SessionReq sessionReq) throws IOException {
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

  private SessionResp handleGetTlsConfigurationReq(GetTlsConfigurationReq req)
      throws IOException {
    if (!req.getConnectionSide().equals(ConnectionSide.CONNECTION_SIDE_CLIENT)) {
      return SessionResp.newBuilder()
          .setStatus(
              Status.newBuilder()
                  .setCode(255)
                  .setDetails("No TLS configuration for the server side."))
          .build();
    }
    String leafCertString = "";
    String cert2String = "";
    String cert1String = "";
    try (
        InputStream leafCert = classLoader.getResourceAsStream("leaf_cert_ec.pem");
        InputStream cert2 = classLoader.getResourceAsStream("int_cert2_ec.pem");
        InputStream cert1 = classLoader.getResourceAsStream("int_cert1_ec.pem");
    ) {
      leafCertString = FakeWriter.convertInputStreamToString(leafCert);
      cert2String = FakeWriter.convertInputStreamToString(cert2);
      cert1String = FakeWriter.convertInputStreamToString(cert1);
    } catch (IOException e) {
      reader.onError(e);
    }
    return SessionResp.newBuilder()
        .setGetTlsConfigurationResp(
            GetTlsConfigurationResp.newBuilder()
                .setClientTlsConfiguration(
                    GetTlsConfigurationResp.ClientTlsConfiguration.newBuilder()
                        .addCertificateChain(leafCertString)
                        .addCertificateChain(cert1String)
                        .addCertificateChain(cert2String)
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