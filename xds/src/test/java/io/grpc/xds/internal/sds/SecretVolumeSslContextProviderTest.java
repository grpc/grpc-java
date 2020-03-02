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

package io.grpc.xds.internal.sds;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Strings;
import com.google.common.util.concurrent.MoreExecutors;
import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.envoyproxy.envoy.api.v2.core.DataSource;
import io.grpc.internal.testing.TestUtils;
import io.netty.handler.ssl.SslContext;
import java.io.IOException;
import java.security.cert.CertStoreException;
import java.security.cert.CertificateException;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SecretVolumeSslContextProvider}. */
@RunWith(JUnit4.class)
public class SecretVolumeSslContextProviderTest {

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
      SecretVolumeSslContextProvider.validateCertificateContext(
          /* certContext= */ null, /* optional= */ false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("certContext is required");
    }
  }

  @Test
  public void validateCertificateContext_missingTrustCa_throwsException() {
    // expect exception when certContext has no CA and not optional
    CertificateValidationContext certContext = CertificateValidationContext.getDefaultInstance();
    try {
      SecretVolumeSslContextProvider.validateCertificateContext(
          certContext, /* optional= */ false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("certContext is required");
    }
  }

  @Test
  public void validateCertificateContext_nullAndOptional() {
    // certContext argument can be null when optional
    CertificateValidationContext certContext =
        SecretVolumeSslContextProvider.validateCertificateContext(
            /* certContext= */ null, /* optional= */ true);
    assertThat(certContext).isNull();
  }

  @Test
  public void validateCertificateContext_missingTrustCaOptional() {
    // certContext argument can have missing CA when optional
    CertificateValidationContext certContext = CertificateValidationContext.getDefaultInstance();
    assertThat(
            SecretVolumeSslContextProvider.validateCertificateContext(
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
      SecretVolumeSslContextProvider.validateCertificateContext(
          certContext, /* optional= */ false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("filename expected");
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
            SecretVolumeSslContextProvider.validateCertificateContext(
                certContext, /* optional= */ false))
        .isSameInstanceAs(certContext);
  }

  @Test
  public void validateTlsCertificate_nullAndNotOptional_throwsException() {
    // expect exception when tlsCertificate is null and not optional
    try {
      SecretVolumeSslContextProvider.validateTlsCertificate(
          /* tlsCertificate= */ null, /* optional= */ false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("tlsCertificate is required");
    }
  }

  @Test
  public void validateTlsCertificate_nullOptional() {
    assertThat(
            SecretVolumeSslContextProvider.validateTlsCertificate(
                /* tlsCertificate= */ null, /* optional= */ true))
        .isNull();
  }

  @Test
  public void validateTlsCertificate_defaultInstance_returnsNull() {
    // tlsCertificate is not null but has no value (default instance): expect null
    TlsCertificate tlsCert = TlsCertificate.getDefaultInstance();
    assertThat(
            SecretVolumeSslContextProvider.validateTlsCertificate(
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
      SecretVolumeSslContextProvider.validateTlsCertificate(tlsCert, /* optional= */ false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("filename expected");
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
      SecretVolumeSslContextProvider.validateTlsCertificate(tlsCert, /* optional= */ true);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("filename expected");
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
      SecretVolumeSslContextProvider.validateTlsCertificate(tlsCert, /* optional= */ false);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("filename expected");
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
      SecretVolumeSslContextProvider.validateTlsCertificate(tlsCert, /* optional= */ true);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("filename expected");
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
            SecretVolumeSslContextProvider.validateTlsCertificate(
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
            SecretVolumeSslContextProvider.validateTlsCertificate(
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
      SecretVolumeSslContextProvider.validateTlsCertificate(tlsCert, /* optional= */ true);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("filename expected");
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
      SecretVolumeSslContextProvider.validateTlsCertificate(tlsCert, /* optional= */ true);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("filename expected");
    }
  }

  @Test
  public void getProviderForServer_defaultTlsCertificate_throwsException() {
    TlsCertificate tlsCert = TlsCertificate.getDefaultInstance();
    try {
      SecretVolumeSslContextProvider.getProviderForServer(
          CommonTlsContextTestsUtil
              .buildDownstreamTlsContext(getCommonTlsContext(tlsCert, /* certContext= */ null)));
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("filename expected");
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
      SecretVolumeSslContextProvider.getProviderForServer(
          CommonTlsContextTestsUtil
              .buildDownstreamTlsContext(getCommonTlsContext(tlsCert, certContext)));
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).isEqualTo("filename expected");
    }
  }

  @Test
  public void getProviderForClient_defaultCertContext_throwsException() {
    CertificateValidationContext certContext = CertificateValidationContext.getDefaultInstance();
    try {
      SecretVolumeSslContextProvider.getProviderForClient(
          buildUpstreamTlsContext(getCommonTlsContext(/* tlsCertificate= */ null, certContext)));
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("certContext is required");
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
      SecretVolumeSslContextProvider.getProviderForClient(
          buildUpstreamTlsContext(getCommonTlsContext(tlsCert, certContext)));
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("filename expected");
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
      SecretVolumeSslContextProvider.getProviderForClient(
          buildUpstreamTlsContext(getCommonTlsContext(tlsCert, certContext)));
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("filename expected");
    }
  }

  private static String getTempFileNameForResourcesFile(String resFile) throws IOException {
    return TestUtils.loadCert(resFile).getAbsolutePath();
  }

  /** Helper method to build SecretVolumeSslContextProvider from given files. */
  private static SecretVolumeSslContextProvider<?> getSslContextSecretVolumeSecretProvider(
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
    return server
        ? SecretVolumeSslContextProvider.getProviderForServer(
        buildDownstreamTlsContextFromFilenames(
            privateKeyFilename, certChainFilename, trustedCaFilename))
        : SecretVolumeSslContextProvider.getProviderForClient(
            buildUpstreamTlsContextFromFilenames(
                privateKeyFilename, certChainFilename, trustedCaFilename));
  }

  /**
   * Helper method to build SecretVolumeSslContextProvider, call buildSslContext on it and
   * check returned SslContext.
   */
  private static void sslContextForEitherWithBothCertAndTrust(
      boolean server, String pemFile, String keyFile, String caFile)
      throws IOException, CertificateException, CertStoreException {
    SecretVolumeSslContextProvider<?> provider =
        getSslContextSecretVolumeSecretProvider(server, pemFile, keyFile, caFile);

    SslContext sslContext = provider.buildSslContextFromSecrets();
    doChecksOnSslContext(server, sslContext, /* expectedApnProtos= */ null);
  }

  static void doChecksOnSslContext(boolean server, SslContext sslContext,
      List<String> expectedApnProtos) {
    if (server) {
      assertThat(sslContext.isServer()).isTrue();
    } else {
      assertThat(sslContext.isClient()).isTrue();
    }
    List<String> apnProtos = sslContext.applicationProtocolNegotiator().protocols();
    assertThat(apnProtos).isNotNull();
    if (expectedApnProtos != null) {
      assertThat(apnProtos).isEqualTo(expectedApnProtos);
    } else {
      assertThat(apnProtos).contains("h2");
    }
  }

  /**
   * Helper method to build DownstreamTlsContext for above tests. Called from other classes as well.
   */
  static DownstreamTlsContext buildDownstreamTlsContextFromFilenames(
      String privateKey, String certChain, String trustCa) {
    return CommonTlsContextTestsUtil.buildDownstreamTlsContext(
        buildCommonTlsContextFromFilenames(privateKey, certChain, trustCa));
  }

  /**
   * Helper method to build UpstreamTlsContext for above tests. Called from other classes as well.
   */
  public static UpstreamTlsContext buildUpstreamTlsContextFromFilenames(
      String privateKey, String certChain, String trustCa) {
    return buildUpstreamTlsContext(
        buildCommonTlsContextFromFilenames(privateKey, certChain, trustCa));
  }

  private static CommonTlsContext buildCommonTlsContextFromFilenames(
      String privateKey, String certChain, String trustCa) {
    TlsCertificate tlsCert = null;
    if (!Strings.isNullOrEmpty(privateKey) && !Strings.isNullOrEmpty(certChain)) {
      tlsCert =
          TlsCertificate.newBuilder()
              .setCertificateChain(DataSource.newBuilder().setFilename(certChain))
              .setPrivateKey(DataSource.newBuilder().setFilename(privateKey))
              .build();
    }
    CertificateValidationContext certContext = null;
    if (!Strings.isNullOrEmpty(trustCa)) {
      certContext =
          CertificateValidationContext.newBuilder()
              .setTrustedCa(DataSource.newBuilder().setFilename(trustCa))
              .build();
    }
    return getCommonTlsContext(tlsCert, certContext);
  }

  private static CommonTlsContext getCommonTlsContext(
      TlsCertificate tlsCertificate, CertificateValidationContext certContext) {
    CommonTlsContext.Builder builder = CommonTlsContext.newBuilder();
    if (tlsCertificate != null) {
      builder = builder.addTlsCertificates(tlsCertificate);
    }
    if (certContext != null) {
      builder = builder.setValidationContext(certContext);
    }
    return builder.build();
  }

  /**
   * Helper method to build UpstreamTlsContext for above tests. Called from other classes as well.
   */
  static UpstreamTlsContext buildUpstreamTlsContext(CommonTlsContext commonTlsContext) {
    UpstreamTlsContext upstreamTlsContext =
        UpstreamTlsContext.newBuilder().setCommonTlsContext(commonTlsContext).build();
    return upstreamTlsContext;
  }

  @Test
  public void getProviderForServer() throws IOException, CertificateException, CertStoreException {
    sslContextForEitherWithBothCertAndTrust(
        true, SERVER_1_PEM_FILE, SERVER_1_KEY_FILE, CA_PEM_FILE);
  }

  @Test
  public void getProviderForClient() throws IOException, CertificateException, CertStoreException {
    sslContextForEitherWithBothCertAndTrust(false, CLIENT_PEM_FILE, CLIENT_KEY_FILE, CA_PEM_FILE);
  }

  @Test
  public void getProviderForServer_onlyCert()
      throws IOException, CertificateException, CertStoreException {
    sslContextForEitherWithBothCertAndTrust(true, SERVER_1_PEM_FILE, SERVER_1_KEY_FILE, null);
  }

  @Test
  public void getProviderForClient_onlyTrust()
      throws IOException, CertificateException, CertStoreException {
    sslContextForEitherWithBothCertAndTrust(false, null, null, CA_PEM_FILE);
  }

  @Test
  public void getProviderForServer_badFile_throwsException()
      throws IOException, CertificateException, CertStoreException {
    try {
      sslContextForEitherWithBothCertAndTrust(true, SERVER_1_PEM_FILE, SERVER_1_PEM_FILE, null);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().contains("File does not contain valid private key");
    }
  }

  static class TestCallback implements SslContextProvider.Callback {

    SslContext updatedSslContext;
    Throwable updatedThrowable;

    @Override
    public void updateSecret(SslContext sslContext) {
      updatedSslContext = sslContext;
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
  static TestCallback getValueThruCallback(SslContextProvider<?> provider) {
    TestCallback testCallback = new TestCallback();
    provider.addCallback(testCallback, MoreExecutors.directExecutor());
    return testCallback;
  }

  @Test
  public void getProviderForServer_both_callsback() throws IOException {
    SecretVolumeSslContextProvider<?> provider =
        getSslContextSecretVolumeSecretProvider(
            true, SERVER_1_PEM_FILE, SERVER_1_KEY_FILE, CA_PEM_FILE);

    TestCallback testCallback = getValueThruCallback(provider);
    doChecksOnSslContext(true, testCallback.updatedSslContext, /* expectedApnProtos= */ null);
  }

  @Test
  public void getProviderForClient_both_callsback() throws IOException {
    SecretVolumeSslContextProvider<?> provider =
        getSslContextSecretVolumeSecretProvider(
            false, CLIENT_PEM_FILE, CLIENT_KEY_FILE, CA_PEM_FILE);

    TestCallback testCallback = getValueThruCallback(provider);
    doChecksOnSslContext(false, testCallback.updatedSslContext, /* expectedApnProtos= */ null);
  }

  // note this test generates stack-trace but can be safely ignored
  @Test
  public void getProviderForClient_both_callsback_setException() throws IOException {
    SecretVolumeSslContextProvider<?> provider =
        getSslContextSecretVolumeSecretProvider(
            false, CLIENT_PEM_FILE, CLIENT_PEM_FILE, CA_PEM_FILE);
    TestCallback testCallback = getValueThruCallback(provider);
    assertThat(testCallback.updatedSslContext).isNull();
    assertThat(testCallback.updatedThrowable).isInstanceOf(IllegalArgumentException.class);
    assertThat(testCallback.updatedThrowable).hasMessageThat()
        .contains("File does not contain valid private key");
  }
}
