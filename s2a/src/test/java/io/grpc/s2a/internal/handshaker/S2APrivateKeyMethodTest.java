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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.truth.Expect;
import com.google.protobuf.ByteString;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.s2a.internal.handshaker.S2AIdentity;
import io.netty.handler.ssl.OpenSslPrivateKeyMethod;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class S2APrivateKeyMethodTest {
  @Rule public final Expect expect = Expect.create();
  private static final byte[] DATA_TO_SIGN = "random bytes for signing.".getBytes(UTF_8);

  private S2AStub stub;
  private FakeWriter writer;
  private S2APrivateKeyMethod keyMethod;

  private static PublicKey extractPublicKeyFromPem(String pem) throws Exception {
    X509Certificate cert =
        (X509Certificate)
            CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(UTF_8)));
    return cert.getPublicKey();
  }

  private static boolean verifySignature(
      byte[] dataToSign, byte[] signature, String signatureAlgorithm) throws Exception {
    Signature sig = Signature.getInstance(signatureAlgorithm);
    InputStream leafCert =
        S2APrivateKeyMethodTest.class.getClassLoader().getResourceAsStream("leaf_cert_ec.pem");
    sig.initVerify(extractPublicKeyFromPem(FakeWriter.convertInputStreamToString(
        leafCert)));
    leafCert.close();
    sig.update(dataToSign);
    return sig.verify(signature);
  }

  @Before
  public void setUp() {
    // This is line is to ensure that JNI correctly links the necessary objects. Without this, we
    // get `java.lang.UnsatisfiedLinkError` on
    // `io.netty.internal.tcnative.NativeStaticallyReferencedJniMethods.sslSignRsaPkcsSha1()`
    GrpcSslContexts.configure(SslContextBuilder.forClient());

    writer = new FakeWriter();
    stub = S2AStub.newInstanceForTesting(writer);
    writer.setReader(stub.getReader());
    keyMethod = S2APrivateKeyMethod.create(stub, /* localIdentity= */ Optional.empty());
  }

  @Test
  public void signatureAlgorithmConversion_success() {
    expect
        .that(
            S2APrivateKeyMethod.convertOpenSslSignAlgToS2ASignAlg(
                OpenSslPrivateKeyMethod.SSL_SIGN_RSA_PKCS1_SHA256))
        .isEqualTo(SignatureAlgorithm.S2A_SSL_SIGN_RSA_PKCS1_SHA256);
    expect
        .that(
            S2APrivateKeyMethod.convertOpenSslSignAlgToS2ASignAlg(
                OpenSslPrivateKeyMethod.SSL_SIGN_RSA_PKCS1_SHA384))
        .isEqualTo(SignatureAlgorithm.S2A_SSL_SIGN_RSA_PKCS1_SHA384);
    expect
        .that(
            S2APrivateKeyMethod.convertOpenSslSignAlgToS2ASignAlg(
                OpenSslPrivateKeyMethod.SSL_SIGN_RSA_PKCS1_SHA512))
        .isEqualTo(SignatureAlgorithm.S2A_SSL_SIGN_RSA_PKCS1_SHA512);
    expect
        .that(
            S2APrivateKeyMethod.convertOpenSslSignAlgToS2ASignAlg(
                OpenSslPrivateKeyMethod.SSL_SIGN_ECDSA_SECP256R1_SHA256))
        .isEqualTo(SignatureAlgorithm.S2A_SSL_SIGN_ECDSA_SECP256R1_SHA256);
    expect
        .that(
            S2APrivateKeyMethod.convertOpenSslSignAlgToS2ASignAlg(
                OpenSslPrivateKeyMethod.SSL_SIGN_ECDSA_SECP384R1_SHA384))
        .isEqualTo(SignatureAlgorithm.S2A_SSL_SIGN_ECDSA_SECP384R1_SHA384);
    expect
        .that(
            S2APrivateKeyMethod.convertOpenSslSignAlgToS2ASignAlg(
                OpenSslPrivateKeyMethod.SSL_SIGN_ECDSA_SECP521R1_SHA512))
        .isEqualTo(SignatureAlgorithm.S2A_SSL_SIGN_ECDSA_SECP521R1_SHA512);
    expect
        .that(
            S2APrivateKeyMethod.convertOpenSslSignAlgToS2ASignAlg(
                OpenSslPrivateKeyMethod.SSL_SIGN_RSA_PSS_RSAE_SHA256))
        .isEqualTo(SignatureAlgorithm.S2A_SSL_SIGN_RSA_PSS_RSAE_SHA256);
    expect
        .that(
            S2APrivateKeyMethod.convertOpenSslSignAlgToS2ASignAlg(
                OpenSslPrivateKeyMethod.SSL_SIGN_RSA_PSS_RSAE_SHA384))
        .isEqualTo(SignatureAlgorithm.S2A_SSL_SIGN_RSA_PSS_RSAE_SHA384);
    expect
        .that(
            S2APrivateKeyMethod.convertOpenSslSignAlgToS2ASignAlg(
                OpenSslPrivateKeyMethod.SSL_SIGN_RSA_PSS_RSAE_SHA512))
        .isEqualTo(SignatureAlgorithm.S2A_SSL_SIGN_RSA_PSS_RSAE_SHA512);
  }

  @Test
  public void signatureAlgorithmConversion_unsupportedOperation() {
    UnsupportedOperationException e =
        assertThrows(
            UnsupportedOperationException.class,
            () -> S2APrivateKeyMethod.convertOpenSslSignAlgToS2ASignAlg(-1));

    assertThat(e).hasMessageThat().contains("Signature Algorithm -1 is not supported.");
  }

  @Test
  public void createOnNullStub_returnsNullPointerException() {
    assertThrows(
        NullPointerException.class,
        () -> S2APrivateKeyMethod.create(/* stub= */ null, /* localIdentity= */ Optional.empty()));
  }

  @Test
  public void decrypt_unsupportedOperation() {
    UnsupportedOperationException e =
        assertThrows(
            UnsupportedOperationException.class,
            () -> keyMethod.decrypt(/* engine= */ null, DATA_TO_SIGN));

    assertThat(e).hasMessageThat().contains("decrypt is not supported.");
  }

  @Test
  public void fakelocalIdentity_signWithSha256_success() throws Exception {
    S2AIdentity fakeIdentity = S2AIdentity.fromSpiffeId("fake-spiffe-id");
    S2AStub mockStub = mock(S2AStub.class);
    OpenSslPrivateKeyMethod keyMethodWithFakeIdentity =
        S2APrivateKeyMethod.create(mockStub, Optional.of(fakeIdentity));
    SessionReq req =
        SessionReq.newBuilder()
            .setLocalIdentity(fakeIdentity.getIdentity())
            .setOffloadPrivateKeyOperationReq(
                OffloadPrivateKeyOperationReq.newBuilder()
                    .setOperation(OffloadPrivateKeyOperationReq.PrivateKeyOperation.SIGN)
                    .setSignatureAlgorithm(SignatureAlgorithm.S2A_SSL_SIGN_ECDSA_SECP256R1_SHA256)
                    .setRawBytes(ByteString.copyFrom(DATA_TO_SIGN)))
            .build();
    byte[] expectedOutbytes = "fake out bytes".getBytes(UTF_8);
    when(mockStub.send(req))
        .thenReturn(
            SessionResp.newBuilder()
                .setOffloadPrivateKeyOperationResp(
                    OffloadPrivateKeyOperationResp.newBuilder()
                        .setOutBytes(ByteString.copyFrom(expectedOutbytes)))
                .build());

    byte[] signature =
        keyMethodWithFakeIdentity.sign(
            /* engine= */ null,
            OpenSslPrivateKeyMethod.SSL_SIGN_ECDSA_SECP256R1_SHA256,
            DATA_TO_SIGN);
    verify(mockStub).send(req);
    assertThat(signature).isEqualTo(expectedOutbytes);
  }

  @Test
  public void signWithSha256_success() throws Exception {
    writer.initializePrivateKey().setBehavior(FakeWriter.Behavior.OK_STATUS);

    byte[] signature =
        keyMethod.sign(
            /* engine= */ null,
            OpenSslPrivateKeyMethod.SSL_SIGN_ECDSA_SECP256R1_SHA256,
            DATA_TO_SIGN);

    assertThat(signature).isNotEmpty();
    assertThat(verifySignature(DATA_TO_SIGN, signature, "SHA256withECDSA")).isTrue();
  }

  @Test
  public void signWithSha384_success() throws Exception {
    writer.initializePrivateKey().setBehavior(FakeWriter.Behavior.OK_STATUS);

    byte[] signature =
        keyMethod.sign(
            /* engine= */ null,
            OpenSslPrivateKeyMethod.SSL_SIGN_ECDSA_SECP384R1_SHA384,
            DATA_TO_SIGN);

    assertThat(signature).isNotEmpty();
    assertThat(verifySignature(DATA_TO_SIGN, signature, "SHA384withECDSA")).isTrue();
  }

  @Test
  public void signWithSha512_success() throws Exception {
    writer.initializePrivateKey().setBehavior(FakeWriter.Behavior.OK_STATUS);

    byte[] signature =
        keyMethod.sign(
            /* engine= */ null,
            OpenSslPrivateKeyMethod.SSL_SIGN_ECDSA_SECP521R1_SHA512,
            DATA_TO_SIGN);

    assertThat(signature).isNotEmpty();
    assertThat(verifySignature(DATA_TO_SIGN, signature, "SHA512withECDSA")).isTrue();
  }

  @Test
  public void sign_noKeyAvailable() throws Exception {
    writer.resetPrivateKey().setBehavior(FakeWriter.Behavior.OK_STATUS);

    S2AConnectionException e =
        assertThrows(
            S2AConnectionException.class,
            () ->
                keyMethod.sign(
                    /* engine= */ null,
                    OpenSslPrivateKeyMethod.SSL_SIGN_ECDSA_SECP256R1_SHA256,
                    DATA_TO_SIGN));

    assertThat(e)
        .hasMessageThat()
        .contains(
            "Error occurred in response from S2A, error code: 255, error message: \"No Private Key"
                + " available.\".");
  }

  @Test
  public void sign_algorithmNotSupported() throws Exception {
    writer.initializePrivateKey().setBehavior(FakeWriter.Behavior.OK_STATUS);

    S2AConnectionException e =
        assertThrows(
            S2AConnectionException.class,
            () ->
                keyMethod.sign(
                    /* engine= */ null,
                    OpenSslPrivateKeyMethod.SSL_SIGN_RSA_PKCS1_SHA256,
                    DATA_TO_SIGN));

    assertThat(e)
        .hasMessageThat()
        .contains(
            "Error occurred in response from S2A, error code: 255, error message: \"Only ECDSA key"
                + " algorithms are supported.\".");
  }

  @Test
  public void sign_getsErrorResponse() throws Exception {
    writer.initializePrivateKey().setBehavior(FakeWriter.Behavior.ERROR_STATUS);

    S2AConnectionException e =
        assertThrows(
            S2AConnectionException.class,
            () ->
                keyMethod.sign(
                    /* engine= */ null,
                    OpenSslPrivateKeyMethod.SSL_SIGN_ECDSA_SECP256R1_SHA256,
                    DATA_TO_SIGN));

    assertThat(e)
        .hasMessageThat()
        .contains(
            "Error occurred in response from S2A, error code: 1, error message: \"Intended ERROR"
                + " Status from FakeWriter.\".");
  }

  @Test
  public void sign_getsEmptyResponse() throws Exception {
    writer.initializePrivateKey().setBehavior(FakeWriter.Behavior.EMPTY_RESPONSE);

    S2AConnectionException e =
        assertThrows(
            S2AConnectionException.class,
            () ->
                keyMethod.sign(
                    /* engine= */ null,
                    OpenSslPrivateKeyMethod.SSL_SIGN_ECDSA_SECP256R1_SHA256,
                    DATA_TO_SIGN));

    assertThat(e).hasMessageThat().contains("No valid response received from S2A.");
  }
}