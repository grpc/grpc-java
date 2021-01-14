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

import com.google.common.base.Strings;
import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.envoyproxy.envoy.api.v2.core.DataSource;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ClientSslContextProviderFactory}. */
@RunWith(JUnit4.class)
public class ClientSslContextProviderFactoryTest {

  private static final String CLIENT_PEM_FILE = "client.pem";
  private static final String CLIENT_KEY_FILE = "client.key";
  private static final String CA_PEM_FILE = "ca.pem";

  ClientSslContextProviderFactory clientSslContextProviderFactory =
      new ClientSslContextProviderFactory();

  static CommonTlsContext buildCommonTlsContextFromSdsConfigForTlsCertificate(
      String name, String targetUri, String trustCa) {

    SdsSecretConfig sdsSecretConfig =
        buildSdsSecretConfig(name, targetUri, /* channelType= */ null);
    CommonTlsContext.Builder builder =
        CommonTlsContext.newBuilder().addTlsCertificateSdsSecretConfigs(sdsSecretConfig);

    if (!Strings.isNullOrEmpty(trustCa)) {
      builder.setValidationContext(
          CertificateValidationContext.newBuilder()
              .setTrustedCa(DataSource.newBuilder().setFilename(trustCa))
              .build());
    }
    return builder.build();
  }

  static CommonTlsContext buildCommonTlsContextFromSdsConfigForValidationContext(
      String name, String targetUri, String privateKey, String certChain) {
    SdsSecretConfig sdsSecretConfig =
        buildSdsSecretConfig(name, targetUri, /* channelType= */ null);

    CommonTlsContext.Builder builder =
        CommonTlsContext.newBuilder().setValidationContextSdsSecretConfig(sdsSecretConfig);

    if (!Strings.isNullOrEmpty(privateKey) && !Strings.isNullOrEmpty(certChain)) {
      builder.addTlsCertificates(
          TlsCertificate.newBuilder()
              .setCertificateChain(DataSource.newBuilder().setFilename(certChain))
              .setPrivateKey(DataSource.newBuilder().setFilename(privateKey))
              .build());
    }
    return builder.build();
  }

  private static SdsSecretConfig buildSdsSecretConfig(
      String name, String targetUri, String channelType) {
    SdsSecretConfig sdsSecretConfig = null;
    if (!Strings.isNullOrEmpty(name) && !Strings.isNullOrEmpty(targetUri)) {
      sdsSecretConfig =
          SdsSecretConfig.newBuilder()
              .setName(name)
              .setSdsConfig(SdsClientTest.buildConfigSource(targetUri, channelType))
              .build();
    }
    return sdsSecretConfig;
  }

  static CommonTlsContext buildCommonTlsContextFromSdsConfigsForAll(
      String certName,
      String certTargetUri,
      String validationContextName,
      String validationContextTargetUri,
      String channelType) {

    CommonTlsContext.Builder builder = CommonTlsContext.newBuilder();

    SdsSecretConfig sdsSecretConfig = buildSdsSecretConfig(certName, certTargetUri, channelType);
    if (sdsSecretConfig != null) {
      builder.addTlsCertificateSdsSecretConfigs(sdsSecretConfig);
    }
    sdsSecretConfig =
        buildSdsSecretConfig(validationContextName, validationContextTargetUri, channelType);
    if (sdsSecretConfig != null) {
      builder.setValidationContextSdsSecretConfig(sdsSecretConfig);
    }
    return builder.build();
  }

  @Test
  public void createSslContextProvider_allFilenames() {
    UpstreamTlsContext upstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildUpstreamTlsContextFromFilenames(
            CLIENT_KEY_FILE, CLIENT_PEM_FILE, CA_PEM_FILE);

    SslContextProvider<UpstreamTlsContext> sslContextProvider =
        clientSslContextProviderFactory.createSslContextProvider(upstreamTlsContext);
    assertThat(sslContextProvider).isNotNull();
  }

  @Test
  public void createSslContextProvider_sdsConfigForTlsCert_expectException() {
    CommonTlsContext commonTlsContext =
        buildCommonTlsContextFromSdsConfigForTlsCertificate(
            /* name= */ "name", /* targetUri= */ "unix:/tmp/sds/path", CA_PEM_FILE);
    UpstreamTlsContext upstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildUpstreamTlsContext(commonTlsContext);

    try {
      SslContextProvider<UpstreamTlsContext> unused =
          clientSslContextProviderFactory.createSslContextProvider(upstreamTlsContext);
      Assert.fail("no exception thrown");
    } catch (UnsupportedOperationException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("UpstreamTlsContext to have all filenames or all SdsConfig");
    }
  }

  @Test
  public void createSslContextProvider_sdsConfigForCertValidationContext_expectException() {
    CommonTlsContext commonTlsContext =
        buildCommonTlsContextFromSdsConfigForValidationContext(
            /* name= */ "name",
            /* targetUri= */ "unix:/tmp/sds/path",
            CLIENT_KEY_FILE,
            CLIENT_PEM_FILE);
    UpstreamTlsContext upstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildUpstreamTlsContext(commonTlsContext);

    try {
      SslContextProvider<UpstreamTlsContext> unused =
          clientSslContextProviderFactory.createSslContextProvider(upstreamTlsContext);
      Assert.fail("no exception thrown");
    } catch (UnsupportedOperationException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("UpstreamTlsContext to have all filenames or all SdsConfig");
    }
  }
}
