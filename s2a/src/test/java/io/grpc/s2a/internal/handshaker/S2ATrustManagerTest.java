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
import static org.junit.Assert.assertThrows;

import io.grpc.s2a.internal.handshaker.S2AIdentity;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class S2ATrustManagerTest {
  private S2AStub stub;
  private FakeWriter writer;
  private static final String FAKE_HOSTNAME = "Fake-Hostname";
  private static final String CLIENT_CERT_PEM =
      "MIICKjCCAc+gAwIBAgIUC2GShcVO+5Zkml+7VO3OQ+B2c7EwCgYIKoZIzj0EAwIw"
          + "HzEdMBsGA1UEAwwUcm9vdGNlcnQuZXhhbXBsZS5jb20wIBcNMjMwMTI2MTk0OTUx"
          + "WhgPMjA1MDA2MTMxOTQ5NTFaMB8xHTAbBgNVBAMMFGxlYWZjZXJ0LmV4YW1wbGUu"
          + "Y29tMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEeciYZgFAZjxyzTrklCRIWpad"
          + "8wkyCZQzJSf0IfNn9NKtfzL2V/blteULO0o9Da8e2Avaj+XCKfFTc7salMo/waOB"
          + "5jCB4zAOBgNVHQ8BAf8EBAMCB4AwIAYDVR0lAQH/BBYwFAYIKwYBBQUHAwIGCCsG"
          + "AQUFBwMBMAwGA1UdEwEB/wQCMAAwYQYDVR0RBFowWIYic3BpZmZlOi8vZm9vLnBy"
          + "b2QuZ29vZ2xlLmNvbS9wMS9wMoIUZm9vLnByb2Quc3BpZmZlLmdvb2eCHG1hY2hp"
          + "bmUtbmFtZS5wcm9kLmdvb2dsZS5jb20wHQYDVR0OBBYEFETY6Cu/aW924nfvUrOs"
          + "yXCC1hrpMB8GA1UdIwQYMBaAFJLkXGlTYKISiGd+K/Ijh4IOEpHBMAoGCCqGSM49"
          + "BAMCA0kAMEYCIQCZDW472c1/4jEOHES/88X7NTqsYnLtIpTjp5PZ62z3sAIhAN1J"
          + "vxvbxt9ySdFO+cW7oLBEkCwUicBhxJi5VfQeQypT";

  @Before
  public void setUp() {
    writer = new FakeWriter();
    stub = S2AStub.newInstanceForTesting(writer);
    writer.setReader(stub.getReader());
  }

  @Test
  public void createForClient_withNullStub_throwsError() {
    NullPointerException expected =
        assertThrows(
            NullPointerException.class,
            () ->
                S2ATrustManager.createForClient(
                    /* stub= */ null, FAKE_HOSTNAME, /* localIdentity= */ Optional.empty()));

    assertThat(expected).hasMessageThat().isNull();
  }

  @Test
  public void createForClient_withNullHostname_throwsError() {
    NullPointerException expected =
        assertThrows(
            NullPointerException.class,
            () ->
                S2ATrustManager.createForClient(
                    stub, /* hostname= */ null, /* localIdentity= */ Optional.empty()));

    assertThat(expected).hasMessageThat().isNull();
  }

  @Test
  public void getAcceptedIssuers_returnsExpectedNullResult() {
    S2ATrustManager trustManager =
        S2ATrustManager.createForClient(stub, FAKE_HOSTNAME, /* localIdentity= */ Optional.empty());

    assertThat(trustManager.getAcceptedIssuers()).isNull();
  }

  @Test
  public void checkClientTrusted_withEmptyCertificateChain_throwsException()
      throws CertificateException {
    writer.setVerificationResult(FakeWriter.VerificationResult.SUCCESS);
    S2ATrustManager trustManager =
        S2ATrustManager.createForClient(stub, FAKE_HOSTNAME, /* localIdentity= */ Optional.empty());

    IllegalArgumentException expected =
        assertThrows(
            IllegalArgumentException.class,
            () -> trustManager.checkClientTrusted(new X509Certificate[] {}, /* authType= */ ""));

    assertThat(expected).hasMessageThat().contains("Certificate chain has zero certificates.");
  }

  @Test
  public void checkServerTrusted_withEmptyCertificateChain_throwsException()
      throws CertificateException {
    writer.setVerificationResult(FakeWriter.VerificationResult.SUCCESS);
    S2ATrustManager trustManager =
        S2ATrustManager.createForClient(stub, FAKE_HOSTNAME, /* localIdentity= */ Optional.empty());

    IllegalArgumentException expected =
        assertThrows(
            IllegalArgumentException.class,
            () -> trustManager.checkServerTrusted(new X509Certificate[] {}, /* authType= */ ""));

    assertThat(expected).hasMessageThat().contains("Certificate chain has zero certificates.");
  }

  @Test
  public void checkClientTrusted_getsSuccessResponse() throws CertificateException {
    writer.setVerificationResult(FakeWriter.VerificationResult.SUCCESS);
    S2ATrustManager trustManager =
        S2ATrustManager.createForClient(stub, FAKE_HOSTNAME, /* localIdentity= */ Optional.empty());

    // Expect no exception.
    trustManager.checkClientTrusted(getCerts(), /* authType= */ "");
  }

  @Test
  public void checkClientTrusted_withLocalIdentity_getsSuccessResponse()
      throws CertificateException {
    writer.setVerificationResult(FakeWriter.VerificationResult.SUCCESS);
    S2ATrustManager trustManager =
        S2ATrustManager.createForClient(
            stub, FAKE_HOSTNAME, Optional.of(S2AIdentity.fromSpiffeId("fake-spiffe-id")));

    // Expect no exception.
    trustManager.checkClientTrusted(getCerts(), /* authType= */ "");
  }

  @Test
  public void checkServerTrusted_getsSuccessResponse() throws CertificateException {
    writer.setVerificationResult(FakeWriter.VerificationResult.SUCCESS);
    S2ATrustManager trustManager =
        S2ATrustManager.createForClient(stub, FAKE_HOSTNAME, /* localIdentity= */ Optional.empty());

    // Expect no exception.
    trustManager.checkServerTrusted(getCerts(), /* authType= */ "");
  }

  @Test
  public void checkServerTrusted_withLocalIdentity_getsSuccessResponse()
      throws CertificateException {
    writer.setVerificationResult(FakeWriter.VerificationResult.SUCCESS);
    S2ATrustManager trustManager =
        S2ATrustManager.createForClient(
            stub, FAKE_HOSTNAME, Optional.of(S2AIdentity.fromSpiffeId("fake-spiffe-id")));

    // Expect no exception.
    trustManager.checkServerTrusted(getCerts(), /* authType= */ "");
  }

  @Test
  public void checkClientTrusted_getsIntendedFailureResponse() throws CertificateException {
    writer
        .setVerificationResult(FakeWriter.VerificationResult.FAILURE)
        .setFailureReason("Intended failure.");
    S2ATrustManager trustManager =
        S2ATrustManager.createForClient(stub, FAKE_HOSTNAME, /* localIdentity= */ Optional.empty());

    CertificateException expected =
        assertThrows(
            CertificateException.class,
            () -> trustManager.checkClientTrusted(getCerts(), /* authType= */ ""));

    assertThat(expected).hasMessageThat().contains("Intended failure.");
  }

  @Test
  public void checkClientTrusted_getsIntendedFailureStatusInResponse() throws CertificateException {
    writer.setBehavior(FakeWriter.Behavior.ERROR_STATUS);
    S2ATrustManager trustManager =
        S2ATrustManager.createForClient(stub, FAKE_HOSTNAME, /* localIdentity= */ Optional.empty());

    CertificateException expected =
        assertThrows(
            CertificateException.class,
            () -> trustManager.checkClientTrusted(getCerts(), /* authType= */ ""));

    assertThat(expected).hasMessageThat().contains("Error occurred in response from S2A");
  }

  @Test
  public void checkClientTrusted_getsIntendedFailureFromServer() throws CertificateException {
    writer.setBehavior(FakeWriter.Behavior.ERROR_RESPONSE);
    S2ATrustManager trustManager =
        S2ATrustManager.createForClient(stub, FAKE_HOSTNAME, /* localIdentity= */ Optional.empty());

    CertificateException expected =
        assertThrows(
            CertificateException.class,
            () -> trustManager.checkClientTrusted(getCerts(), /* authType= */ ""));

    assertThat(expected).hasMessageThat().isEqualTo("Failed to send request to S2A.");
  }

  @Test
  public void checkServerTrusted_getsIntendedFailureResponse() throws CertificateException {
    writer
        .setVerificationResult(FakeWriter.VerificationResult.FAILURE)
        .setFailureReason("Intended failure.");
    S2ATrustManager trustManager =
        S2ATrustManager.createForClient(stub, FAKE_HOSTNAME, /* localIdentity= */ Optional.empty());

    CertificateException expected =
        assertThrows(
            CertificateException.class,
            () -> trustManager.checkServerTrusted(getCerts(), /* authType= */ ""));

    assertThat(expected).hasMessageThat().contains("Intended failure.");
  }

  @Test
  public void checkServerTrusted_getsIntendedFailureStatusInResponse() throws CertificateException {
    writer.setBehavior(FakeWriter.Behavior.ERROR_STATUS);
    S2ATrustManager trustManager =
        S2ATrustManager.createForClient(stub, FAKE_HOSTNAME, /* localIdentity= */ Optional.empty());

    CertificateException expected =
        assertThrows(
            CertificateException.class,
            () -> trustManager.checkServerTrusted(getCerts(), /* authType= */ ""));

    assertThat(expected).hasMessageThat().contains("Error occurred in response from S2A");
  }

  @Test
  public void checkServerTrusted_getsIntendedFailureFromServer() throws CertificateException {
    writer.setBehavior(FakeWriter.Behavior.ERROR_RESPONSE);
    S2ATrustManager trustManager =
        S2ATrustManager.createForClient(stub, FAKE_HOSTNAME, /* localIdentity= */ Optional.empty());

    CertificateException expected =
        assertThrows(
            CertificateException.class,
            () -> trustManager.checkServerTrusted(getCerts(), /* authType= */ ""));

    assertThat(expected).hasMessageThat().isEqualTo("Failed to send request to S2A.");
  }

  private X509Certificate[] getCerts() throws CertificateException {
    byte[] decoded = Base64.getDecoder().decode(CLIENT_CERT_PEM);
    return new X509Certificate[] {
      (X509Certificate)
          CertificateFactory.getInstance("X.509")
              .generateCertificate(new ByteArrayInputStream(decoded))
    };
  }
}