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
import io.netty.handler.ssl.SslContext;
import java.util.List;
import java.util.logging.Level;
import javax.net.ssl.SSLException;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link SslContextSecretVolumeSecretProvider}.
 */
@RunWith(JUnit4.class)
public class SslContextSecretVolumeSecretProviderTest {

  private static final String SERVER_1_PEM_FILE = "src/test/resources/io/grpc/xds/sds/server1.pem";
  private static final String SERVER_1_KEY_FILE = "src/test/resources/io/grpc/xds/sds/server1.key";
  private static final String CLIENT_PEM_FILE = "src/test/resources/io/grpc/xds/sds/client.pem";
  private static final String CLIENT_KEY_FILE = "src/test/resources/io/grpc/xds/sds/client.key";
  private static final String CA_PEM_FILE = "src/test/resources/io/grpc/xds/sds/ca.pem";

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void certContextNullNotOptional() {
    try {
      SslContextSecretVolumeSecretProvider.validateCertificateContext(null, false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("certContext is required");
    }
  }

  @Test
  public void certContextNoTrustCaOptional() {
    CertificateValidationContext certContext = CertificateValidationContext.getDefaultInstance();
    try {
      SslContextSecretVolumeSecretProvider.validateCertificateContext(certContext, false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("certContext is required");
    }
  }

  @Test
  public void certContextNullOptional() {
    SslContextSecretVolumeSecretProvider.validateCertificateContext(null, true);
  }

  @Test
  public void certContextNoTrustCaNotOptional() {
    CertificateValidationContext certContext = CertificateValidationContext.getDefaultInstance();
    assertThat(SslContextSecretVolumeSecretProvider.validateCertificateContext(certContext,
            true)).isNull();
  }

  @Test
  public void certContextNonFilename() {
    CertificateValidationContext certContext = CertificateValidationContext.newBuilder()
            .setTrustedCa(DataSource.newBuilder().setInlineString("foo"))
            .build();
    try {
      SslContextSecretVolumeSecretProvider.validateCertificateContext(certContext, false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void certContextFilename() {
    CertificateValidationContext certContext = CertificateValidationContext.newBuilder()
            .setTrustedCa(DataSource.newBuilder().setFilename("bar"))
            .build();
    assertThat(SslContextSecretVolumeSecretProvider.validateCertificateContext(certContext,
            false)).isSameInstanceAs(certContext);
  }

  @Test
  public void tlsCertificateNullNotOptional() {
    try {
      SslContextSecretVolumeSecretProvider.validateTlsCertificate(null, false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("tlsCertificate is required");
    }
  }

  @Test
  public void tlsCertificateNullOptional() {
    assertThat(SslContextSecretVolumeSecretProvider.validateTlsCertificate(null,
            true)).isNull();
  }

  @Test
  public void tlsCertificateOptional() {
    TlsCertificate tlsCert = TlsCertificate.getDefaultInstance();
    assertThat(SslContextSecretVolumeSecretProvider.validateTlsCertificate(tlsCert,
            true)).isNull();
  }

  @Test
  public void tlsCertificatePrivateKeySetNotOptional() {
    TlsCertificate tlsCert = TlsCertificate.newBuilder()
            .setPrivateKey(DataSource.newBuilder().setInlineString("foo"))
            .build();
    try {
      SslContextSecretVolumeSecretProvider.validateTlsCertificate(tlsCert, false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void tlsCertificatePrivateKeySetOptional() {
    TlsCertificate tlsCert = TlsCertificate.newBuilder()
            .setPrivateKey(DataSource.newBuilder().setInlineString("foo"))
            .build();
    try {
      SslContextSecretVolumeSecretProvider.validateTlsCertificate(tlsCert, true);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void tlsCertificateCertChainSetNotOptional() {
    TlsCertificate tlsCert = TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setInlineString("foo"))
            .build();
    try {
      SslContextSecretVolumeSecretProvider.validateTlsCertificate(tlsCert, false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void tlsCertificateCertChainSetOptional() {
    TlsCertificate tlsCert = TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setInlineString("foo"))
            .build();
    try {
      SslContextSecretVolumeSecretProvider.validateTlsCertificate(tlsCert, true);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void tlsCertificatePrivateKeyCertChainSetOptional() {
    TlsCertificate tlsCert = TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setFilename("foo"))
            .setPrivateKey(DataSource.newBuilder().setFilename("bar"))
            .build();
    assertThat(SslContextSecretVolumeSecretProvider.validateTlsCertificate(tlsCert,
            true)).isSameInstanceAs(tlsCert);
  }

  @Test
  public void tlsCertificatePrivateKeyCertChainSetNotOptional() {
    TlsCertificate tlsCert = TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setFilename("foo"))
            .setPrivateKey(DataSource.newBuilder().setFilename("bar"))
            .build();
    assertThat(SslContextSecretVolumeSecretProvider.validateTlsCertificate(tlsCert,
            false)).isSameInstanceAs(tlsCert);
  }

  @Test
  public void tlsCertificateCertChainInlineString() {
    TlsCertificate tlsCert = TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setInlineString("foo"))
            .setPrivateKey(DataSource.newBuilder().setFilename("bar"))
            .build();
    try {
      SslContextSecretVolumeSecretProvider.validateTlsCertificate(tlsCert, true);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void tlsCertificatePrivateKeyInlineString() {
    TlsCertificate tlsCert = TlsCertificate.newBuilder()
            .setPrivateKey(DataSource.newBuilder().setInlineString("foo"))
            .setCertificateChain(DataSource.newBuilder().setFilename("bar"))
            .build();
    try {
      SslContextSecretVolumeSecretProvider.validateTlsCertificate(tlsCert, true);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void constructorForServerWithNullTlsCertificate() {
    try {
      new SslContextSecretVolumeSecretProvider(null, null, true);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("tlsCertificate is required");
    }
  }

  @Test
  public void constructorForServerWithDefaultTlsCertificate() {
    TlsCertificate tlsCert = TlsCertificate.getDefaultInstance();
    try {
      new SslContextSecretVolumeSecretProvider(tlsCert, null, true);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void constructorForServerWithCertContextWithInlineString() {
    TlsCertificate tlsCert = TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setFilename("foo"))
            .setPrivateKey(DataSource.newBuilder().setFilename("bar"))
            .build();
    CertificateValidationContext certContext = CertificateValidationContext.newBuilder()
            .setTrustedCa(DataSource.newBuilder().setInlineString("foo"))
            .build();
    try {
      new SslContextSecretVolumeSecretProvider(tlsCert, certContext, true);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void constructorForClientWithNullCertContext() {
    try {
      new SslContextSecretVolumeSecretProvider(null, null, false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("certContext is required");
    }
  }

  @Test
  public void constructorForClientWithDefaultCertContext() {
    CertificateValidationContext certContext = CertificateValidationContext.getDefaultInstance();
    try {
      new SslContextSecretVolumeSecretProvider(null, certContext, false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("certContext is required");
    }
  }

  @Test
  public void constructorForClientWithTlsCertWithPrivateKeyInlineString() {
    TlsCertificate tlsCert = TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setFilename("foo"))
            .setPrivateKey(DataSource.newBuilder().setInlineString("bar"))
            .build();
    CertificateValidationContext certContext = CertificateValidationContext.newBuilder()
            .setTrustedCa(DataSource.newBuilder().setFilename("foo"))
            .build();
    try {
      new SslContextSecretVolumeSecretProvider(tlsCert, certContext, false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void constructorForClientWithTlsCertWithCertChainInlineString() {
    TlsCertificate tlsCert = TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setInlineString("foo"))
            .setPrivateKey(DataSource.newBuilder().setFilename("bar"))
            .build();
    CertificateValidationContext certContext = CertificateValidationContext.newBuilder()
            .setTrustedCa(DataSource.newBuilder().setFilename("foo"))
            .build();
    try {
      new SslContextSecretVolumeSecretProvider(tlsCert, certContext, false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  private static SslContextSecretVolumeSecretProvider getSslContextSecretVolumeSecretProvider(
          boolean server, String certChainFilename, String privateKeyFilename,
          String trustedCaFilename) {
    TlsCertificate tlsCert = (certChainFilename == null && privateKeyFilename == null) ? null :
            TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setFilename(certChainFilename))
            .setPrivateKey(DataSource.newBuilder().setFilename(privateKeyFilename))
            .build();
    CertificateValidationContext certContext = trustedCaFilename == null ? null :
            CertificateValidationContext.newBuilder()
            .setTrustedCa(DataSource.newBuilder().setFilename(trustedCaFilename))
            .build();

    return new SslContextSecretVolumeSecretProvider(tlsCert, certContext, server);
  }

  @Test
  public void constructorForServerWithBoth() {
    SslContextSecretVolumeSecretProvider provider =
            getSslContextSecretVolumeSecretProvider(true,
                    "foo", "bar", "baz");

    assertThat(provider.privateKey).isEqualTo("bar");
    assertThat(provider.certificateChain).isEqualTo("foo");
    assertThat(provider.trustedCa).isEqualTo("baz");
    assertThat(provider.server).isTrue();
  }

  @Test
  public void constructorForClientWithBoth() {
    SslContextSecretVolumeSecretProvider provider =
            getSslContextSecretVolumeSecretProvider(false,
                    "foo", "bar", "baz");

    assertThat(provider.privateKey).isEqualTo("bar");
    assertThat(provider.certificateChain).isEqualTo("foo");
    assertThat(provider.trustedCa).isEqualTo("baz");
    assertThat(provider.server).isFalse();
  }

  private void sslContextForEitherWithBothCertAndTrust(boolean server,
                                                       String pemFile,
                                                       String keyFile,
                                                       String caFile) throws SSLException {
    SslContextSecretVolumeSecretProvider provider =
            getSslContextSecretVolumeSecretProvider(server,
                    pemFile,
                    keyFile,
                    caFile);

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
    // not much to test beyond this
  }

  @Test
  public void sslContextForServerWithBoth() throws SSLException {
    sslContextForEitherWithBothCertAndTrust(true,
            SERVER_1_PEM_FILE, SERVER_1_KEY_FILE, CA_PEM_FILE);
  }

  @Test
  public void sslContextForClientWithBoth() throws SSLException {
    sslContextForEitherWithBothCertAndTrust(false,
            CLIENT_PEM_FILE, CLIENT_KEY_FILE, CA_PEM_FILE);
  }

  @Test
  public void sslContextForServerWithOnlyCert() throws SSLException {
    sslContextForEitherWithBothCertAndTrust(true,
            SERVER_1_PEM_FILE, SERVER_1_KEY_FILE, null);
  }

  @Test
  public void sslContextForClientWithOnlyTrust() throws SSLException {
    sslContextForEitherWithBothCertAndTrust(false,
            null, null, CA_PEM_FILE);
  }

  @Test
  public void sslContextForServerWithBadFileException() throws SSLException {
    try {
      sslContextForEitherWithBothCertAndTrust(true,
            SERVER_1_PEM_FILE, SERVER_1_PEM_FILE, null);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).contains("File does not contain valid private key");
    }
  }

  private static class TestCallback implements
          SecretProvider.Callback<SslContext> {

    SslContext updatedSslContext;
    Throwable updatedThrowable;

    @Override
    public void updateSecret(SslContext sslContext, Throwable throwable) {
      updatedSslContext = sslContext;
      updatedThrowable = throwable;
    }
  }

  /**
   * Helper method to get the value thru directExecutore callback. Because of
   * directExecutor this is a synchronous callback - so need to provide a listener.
   */
  private static TestCallback getValueThruCallback(SecretProvider<SslContext> provider) {
    TestCallback testCallback = new TestCallback();
    provider.addCallback(testCallback, MoreExecutors.directExecutor());
    return testCallback;
  }

  @Test
  public void sslContextForServerWithBothCallbackTest() {
    SslContextSecretVolumeSecretProvider provider =
            getSslContextSecretVolumeSecretProvider(true,
                    SERVER_1_PEM_FILE, SERVER_1_KEY_FILE, CA_PEM_FILE);

    TestCallback testCallback = getValueThruCallback(provider);
    doChecksOnSslContext(true, testCallback.updatedSslContext);
  }

  @Test
  public void sslContextForClientWithBothCallbackTest() {
    SslContextSecretVolumeSecretProvider provider =
            getSslContextSecretVolumeSecretProvider(false,
                    CLIENT_PEM_FILE, CLIENT_KEY_FILE, CA_PEM_FILE);

    TestCallback testCallback = getValueThruCallback(provider);
    doChecksOnSslContext(false, testCallback.updatedSslContext);
  }

  @Test
  public void sslContextForClientWithBadFileExceptionCallbackTest() {
    SslContextSecretVolumeSecretProvider provider =
            getSslContextSecretVolumeSecretProvider(false,
                    CLIENT_PEM_FILE, CLIENT_PEM_FILE, CA_PEM_FILE);
    // suppress the scary stack-trace on stdout
    Level oldLevel = SslContextSecretVolumeSecretProvider.logger.getLevel();
    SslContextSecretVolumeSecretProvider.logger.setLevel(Level.OFF);
    TestCallback testCallback = getValueThruCallback(provider);
    assertThat(testCallback.updatedSslContext).isNull();
    assertThat(testCallback.updatedThrowable).isInstanceOf(IllegalArgumentException.class);
    assertThat(testCallback.updatedThrowable.getMessage())
            .contains("File does not contain valid private key");
    SslContextSecretVolumeSecretProvider.logger.setLevel(oldLevel);
  }
}
