/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds.sds;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.util.concurrent.MoreExecutors;
import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.core.DataSource;
import io.grpc.internal.testing.TestUtils;
import io.netty.handler.ssl.SslContext;
import java.io.IOException;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SslContextSecretVolumeSecretProvider}. */
@RunWith(JUnit4.class)
public class SslContextSecretVolumeSecretProviderTest {

  private static final String SERVER_1_PEM_FILE = "server1.pem";
  private static final String SERVER_1_KEY_FILE = "server1.key";
  private static final String CLIENT_PEM_FILE = "client.pem";
  private static final String CLIENT_KEY_FILE = "client.key";
  private static final String CA_PEM_FILE = "ca.pem";

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void validateCertificateContext_nullAndNotOptional_throwsException() {
    // expect exception when certContext is null and not optional
    try {
      SslContextSecretVolumeSecretProvider.validateCertificateContext(
          /* certContext= */ null, /* optional= */ false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("certContext is required");
    }
  }

  @Test
  public void validateCertificateContext_missingTrustCa_throwsException() {
    // expect exception when certContext has no CA and not optional
    CertificateValidationContext certContext = CertificateValidationContext.getDefaultInstance();
    try {
      SslContextSecretVolumeSecretProvider.validateCertificateContext(
          certContext, /* optional= */ false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("certContext is required");
    }
  }

  @Test
  public void validateCertificateContext_nullAndOptional() {
    // certContext argument can be null when optional
    CertificateValidationContext certContext =
        SslContextSecretVolumeSecretProvider.validateCertificateContext(
            /* certContext= */ null, /* optional= */ true);
    assertThat(certContext).isNull();
  }

  @Test
  public void validateCertificateContext_missingTrustCaOptional() {
    // certContext argument can have missing CA when optional
    CertificateValidationContext certContext = CertificateValidationContext.getDefaultInstance();
    assertThat(
            SslContextSecretVolumeSecretProvider.validateCertificateContext(
                certContext, /* optional= */ true))
        .isNull();
  }

  @Test
  public void validateCertificateContext_inlineString_throwsException() {
    // expect exception when certContext doesn't use filename (inline string)
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .setTrustedCa(DataSource.newBuilder().setInlineString("foo"))
            .build();
    try {
      SslContextSecretVolumeSecretProvider.validateCertificateContext(
          certContext, /* optional= */ false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void validateCertificateContext_filename() {
    // validation succeeds and returns same instance when filename provided
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .setTrustedCa(DataSource.newBuilder().setFilename("bar"))
            .build();
    assertThat(
            SslContextSecretVolumeSecretProvider.validateCertificateContext(
                certContext, /* optional= */ false))
        .isSameInstanceAs(certContext);
  }

  @Test
  public void validateTlsCertificate_nullAndNotOptional_throwsException() {
    // expect exception when tlsCertificate is null and not optional
    try {
      SslContextSecretVolumeSecretProvider.validateTlsCertificate(
          /* tlsCertificate= */ null, /* optional= */ false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("tlsCertificate is required");
    }
  }

  @Test
  public void validateTlsCertificate_nullOptional() {
    assertThat(
            SslContextSecretVolumeSecretProvider.validateTlsCertificate(
                /* tlsCertificate= */ null, /* optional= */ true))
        .isNull();
  }

  @Test
  public void validateTlsCertificate_defaultInstance_returnsNull() {
    // tlsCertificate is not null but has no value (default instance): expect null
    TlsCertificate tlsCert = TlsCertificate.getDefaultInstance();
    assertThat(
            SslContextSecretVolumeSecretProvider.validateTlsCertificate(
                tlsCert, /* optional= */ true))
        .isNull();
  }

  @Test
  public void validateTlsCertificate_missingCertChainNotOptional_throwsException() {
    // expect exception when tlsCertificate has missing certChain and not optional
    TlsCertificate tlsCert =
        TlsCertificate.newBuilder()
            .setPrivateKey(DataSource.newBuilder().setInlineString("foo"))
            .build();
    try {
      SslContextSecretVolumeSecretProvider.validateTlsCertificate(tlsCert, /* optional= */ false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void validateTlsCertificate_missingCertChainOptional_throwsException() {
    // expect exception when tlsCertificate has missing certChain even if optional
    TlsCertificate tlsCert =
        TlsCertificate.newBuilder()
            .setPrivateKey(DataSource.newBuilder().setInlineString("foo"))
            .build();
    try {
      SslContextSecretVolumeSecretProvider.validateTlsCertificate(tlsCert, /* optional= */ true);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void validateTlsCertificate_missingPrivateKeyNotOptional_throwsException() {
    // expect exception when tlsCertificate has missing private key and not optional
    TlsCertificate tlsCert =
        TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setInlineString("foo"))
            .build();
    try {
      SslContextSecretVolumeSecretProvider.validateTlsCertificate(tlsCert, /* optional= */ false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void validateTlsCertificate_missingPrivateKeyOptional_throwsException() {
    // expect exception when tlsCertificate has missing private key even if optional
    TlsCertificate tlsCert =
        TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setInlineString("foo"))
            .build();
    try {
      SslContextSecretVolumeSecretProvider.validateTlsCertificate(tlsCert, /* optional= */ true);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void validateTlsCertificate_optional_returnsSameInstance() {
    TlsCertificate tlsCert =
        TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setFilename("foo"))
            .setPrivateKey(DataSource.newBuilder().setFilename("bar"))
            .build();
    assertThat(
            SslContextSecretVolumeSecretProvider.validateTlsCertificate(
                tlsCert, /* optional= */ true))
        .isSameInstanceAs(tlsCert);
  }

  @Test
  public void validateTlsCertificate_notOptional_returnsSameInstance() {
    TlsCertificate tlsCert =
        TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setFilename("foo"))
            .setPrivateKey(DataSource.newBuilder().setFilename("bar"))
            .build();
    assertThat(
            SslContextSecretVolumeSecretProvider.validateTlsCertificate(
                tlsCert, /* optional= */ false))
        .isSameInstanceAs(tlsCert);
  }

  @Test
  public void validateTlsCertificate_certChainInlineString_throwsException() {
    // expect exception when tlsCertificate has certChain as inline string
    TlsCertificate tlsCert =
        TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setInlineString("foo"))
            .setPrivateKey(DataSource.newBuilder().setFilename("bar"))
            .build();
    try {
      SslContextSecretVolumeSecretProvider.validateTlsCertificate(tlsCert, /* optional= */ true);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void validateTlsCertificate_privateKeyInlineString_throwsException() {
    // expect exception when tlsCertificate has private key as inline string
    TlsCertificate tlsCert =
        TlsCertificate.newBuilder()
            .setPrivateKey(DataSource.newBuilder().setInlineString("foo"))
            .setCertificateChain(DataSource.newBuilder().setFilename("bar"))
            .build();
    try {
      SslContextSecretVolumeSecretProvider.validateTlsCertificate(tlsCert, /* optional= */ true);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void getProviderForServer_nullTlsCertificate_throwsException() {
    try {
      SslContextSecretVolumeSecretProvider.getProviderForServer(
          /* tlsCertificate= */ null, /* certContext= */ null);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("tlsCertificate is required");
    }
  }

  @Test
  public void getProviderForServer_defaultTlsCertificate_throwsException() {
    TlsCertificate tlsCert = TlsCertificate.getDefaultInstance();
    try {
      SslContextSecretVolumeSecretProvider.getProviderForServer(tlsCert, /* certContext= */ null);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void getProviderForServer_certContextWithInlineString_throwsException() {
    TlsCertificate tlsCert =
        TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setFilename("foo"))
            .setPrivateKey(DataSource.newBuilder().setFilename("bar"))
            .build();
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .setTrustedCa(DataSource.newBuilder().setInlineString("foo"))
            .build();
    try {
      SslContextSecretVolumeSecretProvider.getProviderForServer(tlsCert, certContext);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void getProviderForClient_nullCertContext_throwsException() {
    try {
      SslContextSecretVolumeSecretProvider.getProviderForClient(
          /* tlsCertificate= */ null, /* certContext= */ null);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("certContext is required");
    }
  }

  @Test
  public void getProviderForClient_defaultCertContext_throwsException() {
    CertificateValidationContext certContext = CertificateValidationContext.getDefaultInstance();
    try {
      SslContextSecretVolumeSecretProvider.getProviderForClient(
          /* tlsCertificate= */ null, certContext);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("certContext is required");
    }
  }

  @Test
  public void getProviderForClient_certWithPrivateKeyInlineString_throwsException() {
    TlsCertificate tlsCert =
        TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setFilename("foo"))
            .setPrivateKey(DataSource.newBuilder().setInlineString("bar"))
            .build();
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .setTrustedCa(DataSource.newBuilder().setFilename("foo"))
            .build();
    try {
      SslContextSecretVolumeSecretProvider.getProviderForClient(tlsCert, certContext);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void getProviderForClient_certWithCertChainInlineString_throwsException() {
    TlsCertificate tlsCert =
        TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setInlineString("foo"))
            .setPrivateKey(DataSource.newBuilder().setFilename("bar"))
            .build();
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .setTrustedCa(DataSource.newBuilder().setFilename("foo"))
            .build();
    try {
      SslContextSecretVolumeSecretProvider.getProviderForClient(tlsCert, certContext);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  private static String getTempFileNameForResourcesFile(String resFile) throws IOException {
    return TestUtils.loadCert(resFile).getAbsolutePath();
  }

  /** Helper method to build SslContextSecretVolumeSecretProvider from given files. */
  private static SslContextSecretVolumeSecretProvider getSslContextSecretVolumeSecretProvider(
      boolean server, String certChainFilename, String privateKeyFilename, String trustedCaFilename)
      throws IOException {

    // get temp file for each file
    if (certChainFilename != null) {
      certChainFilename = getTempFileNameForResourcesFile(certChainFilename);
    }
    if (privateKeyFilename != null) {
      privateKeyFilename = getTempFileNameForResourcesFile(privateKeyFilename);
    }
    if (trustedCaFilename != null) {
      trustedCaFilename = getTempFileNameForResourcesFile(trustedCaFilename);
    }

    TlsCertificate tlsCert =
        (certChainFilename == null && privateKeyFilename == null)
            ? null
            : TlsCertificate.newBuilder()
                .setCertificateChain(DataSource.newBuilder().setFilename(certChainFilename))
                .setPrivateKey(DataSource.newBuilder().setFilename(privateKeyFilename))
                .build();
    CertificateValidationContext certContext =
        trustedCaFilename == null
            ? null
            : CertificateValidationContext.newBuilder()
                .setTrustedCa(DataSource.newBuilder().setFilename(trustedCaFilename))
                .build();

    return server
        ? SslContextSecretVolumeSecretProvider.getProviderForServer(tlsCert, certContext)
        : SslContextSecretVolumeSecretProvider.getProviderForClient(tlsCert, certContext);
  }

  /**
   * Helper method to build SslContextSecretVolumeSecretProvider, call buildSslContext on it and
   * check returned SslContext.
   */
  private void sslContextForEitherWithBothCertAndTrust(
      boolean server, String pemFile, String keyFile, String caFile) throws IOException {
    SslContextSecretVolumeSecretProvider provider =
        getSslContextSecretVolumeSecretProvider(server, pemFile, keyFile, caFile);

    SslContext sslContext = provider.buildSslContextFromSecrets();
    doChecksOnSslContext(server, sslContext);
  }

  private void doChecksOnSslContext(boolean server, SslContext sslContext) {
    if (server) {
      assertThat(sslContext.isServer()).isTrue();
    } else {
      assertThat(sslContext.isClient()).isTrue();
    }
    List<String> apnProtos = sslContext.applicationProtocolNegotiator().protocols();
    assertThat(apnProtos).isNotNull();
    assertThat(apnProtos).contains("h2");
  }

  @Test
  public void getProviderForServer() throws IOException {
    sslContextForEitherWithBothCertAndTrust(
        true, SERVER_1_PEM_FILE, SERVER_1_KEY_FILE, CA_PEM_FILE);
  }

  @Test
  public void getProviderForClient() throws IOException {
    sslContextForEitherWithBothCertAndTrust(false, CLIENT_PEM_FILE, CLIENT_KEY_FILE, CA_PEM_FILE);
  }

  @Test
  public void getProviderForServer_onlyCert() throws IOException {
    sslContextForEitherWithBothCertAndTrust(true, SERVER_1_PEM_FILE, SERVER_1_KEY_FILE, null);
  }

  @Test
  public void getProviderForClient_onlyTrust() throws IOException {
    sslContextForEitherWithBothCertAndTrust(false, null, null, CA_PEM_FILE);
  }

  @Test
  public void getProviderForServer_badFile_throwsException() throws IOException {
    try {
      sslContextForEitherWithBothCertAndTrust(true, SERVER_1_PEM_FILE, SERVER_1_PEM_FILE, null);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).contains("File does not contain valid private key");
    }
  }

  static class TestCallback<T> implements SecretProvider.Callback<T> {

    T updatedSecret;
    Throwable updatedThrowable;

    @Override
    public void updateSecret(T secret) {
      updatedSecret = secret;
    }

    @Override
    public void onException(Throwable throwable) {
      updatedThrowable = throwable;
    }
  }

  /**
   * Helper method to get the value thru directExecutor callback. Because of directExecutor this is
   * a synchronous callback - so need to provide a listener.
   */
  private static TestCallback<SslContext> getValueThruCallback(
      SecretProvider<SslContext> provider) {
    TestCallback<SslContext> testCallback = new TestCallback<>();
    provider.addCallback(testCallback, MoreExecutors.directExecutor());
    return testCallback;
  }

  @Test
  public void getProviderForServer_both_callsback() throws IOException {
    SslContextSecretVolumeSecretProvider provider =
        getSslContextSecretVolumeSecretProvider(
            true, SERVER_1_PEM_FILE, SERVER_1_KEY_FILE, CA_PEM_FILE);

    TestCallback<SslContext> testCallback = getValueThruCallback(provider);
    doChecksOnSslContext(true, testCallback.updatedSecret);
  }

  @Test
  public void getProviderForClient_both_callsback() throws IOException {
    SslContextSecretVolumeSecretProvider provider =
        getSslContextSecretVolumeSecretProvider(
            false, CLIENT_PEM_FILE, CLIENT_KEY_FILE, CA_PEM_FILE);

    TestCallback<SslContext> testCallback = getValueThruCallback(provider);
    doChecksOnSslContext(false, testCallback.updatedSecret);
  }

  @Test
  public void getProviderForClient_both_callsback_setException() throws IOException {
    SslContextSecretVolumeSecretProvider provider =
        getSslContextSecretVolumeSecretProvider(
            false, CLIENT_PEM_FILE, CLIENT_PEM_FILE, CA_PEM_FILE);
    TestCallback<SslContext> testCallback = getValueThruCallback(provider);
    assertThat(testCallback.updatedSecret).isNull();
    assertThat(testCallback.updatedThrowable).isInstanceOf(IllegalArgumentException.class);
    assertThat(testCallback.updatedThrowable.getMessage())
        .contains("File does not contain valid private key");
  }
}
