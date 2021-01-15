/*
 * Copyright 2020 The gRPC Authors
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

import com.google.common.base.Strings;
import com.google.protobuf.BoolValue;
import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext.CombinedCertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.envoyproxy.envoy.api.v2.core.DataSource;
import io.grpc.internal.testing.TestUtils;
import java.io.IOException;
import java.util.Arrays;
import javax.annotation.Nullable;

/** Utility class for client and server ssl provider tests. */
public class CommonTlsContextTestsUtil {

  public static final String SERVER_0_PEM_FILE = "server0.pem";
  public static final String SERVER_0_KEY_FILE = "server0.key";
  public static final String SERVER_1_PEM_FILE = "server1.pem";
  public static final String SERVER_1_KEY_FILE = "server1.key";
  public static final String CLIENT_PEM_FILE = "client.pem";
  public static final String CLIENT_KEY_FILE = "client.key";
  public static final String CA_PEM_FILE = "ca.pem";
  /** Bad/untrusted server certs. */
  public static final String BAD_SERVER_PEM_FILE = "badserver.pem";
  public static final String BAD_SERVER_KEY_FILE = "badserver.key";
  public static final String BAD_CLIENT_PEM_FILE = "badclient.pem";
  public static final String BAD_CLIENT_KEY_FILE = "badclient.key";

  static SdsSecretConfig buildSdsSecretConfig(String name, String targetUri, String channelType) {
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

  /** takes additional values and creates CombinedCertificateValidationContext as needed. */
  @SuppressWarnings("deprecation")
  static CommonTlsContext buildCommonTlsContextWithAdditionalValues(
      String certName,
      String certTargetUri,
      String validationContextName,
      String validationContextTargetUri,
      Iterable<String> verifySubjectAltNames,
      Iterable<String> alpnNames,
      String channelType) {

    CommonTlsContext.Builder builder = CommonTlsContext.newBuilder();

    SdsSecretConfig sdsSecretConfig = buildSdsSecretConfig(certName, certTargetUri, channelType);
    if (sdsSecretConfig != null) {
      builder.addTlsCertificateSdsSecretConfigs(sdsSecretConfig);
    }
    sdsSecretConfig =
        buildSdsSecretConfig(validationContextName, validationContextTargetUri, channelType);
    CertificateValidationContext certValidationContext =
        verifySubjectAltNames == null ? null
            : CertificateValidationContext.newBuilder()
                .addAllVerifySubjectAltName(verifySubjectAltNames).build();

    if (sdsSecretConfig != null && certValidationContext != null) {
      CombinedCertificateValidationContext.Builder combinedBuilder =
          CombinedCertificateValidationContext.newBuilder()
              .setDefaultValidationContext(certValidationContext)
              .setValidationContextSdsSecretConfig(sdsSecretConfig);
      builder.setCombinedValidationContext(combinedBuilder);
    } else if (sdsSecretConfig != null) {
      builder.setValidationContextSdsSecretConfig(sdsSecretConfig);
    } else if (certValidationContext != null) {
      builder.setValidationContext(certValidationContext);
    }
    if (alpnNames != null) {
      builder.addAllAlpnProtocols(alpnNames);
    }
    return builder.build();
  }

  /** Helper method to build DownstreamTlsContext for multiple test classes. */
  static DownstreamTlsContext buildDownstreamTlsContext(
      CommonTlsContext commonTlsContext, boolean requireClientCert) {
    DownstreamTlsContext downstreamTlsContext =
        DownstreamTlsContext.newBuilder()
            .setCommonTlsContext(commonTlsContext)
            .setRequireClientCertificate(BoolValue.of(requireClientCert))
            .build();
    return downstreamTlsContext;
  }

  /** Helper method for creating DownstreamTlsContext values for tests. */
  public static DownstreamTlsContext buildTestDownstreamTlsContext() {
    return buildTestDownstreamTlsContext("google-sds-config-default", "ROOTCA");
  }

  /** Helper method for creating DownstreamTlsContext values with names. */
  public static DownstreamTlsContext buildTestDownstreamTlsContext(
      String certName, String validationContextName) {
    return buildDownstreamTlsContext(
        buildCommonTlsContextWithAdditionalValues(
            certName,
            "unix:/var/run/sds/uds_path",
            validationContextName,
            "unix:/var/run/sds/uds_path",
            Arrays.asList("spiffe://grpc-sds-testing.svc.id.goog/ns/default/sa/bob"),
            Arrays.asList("managed-tls"),
            null),
        /* requireClientCert= */ false);
  }

  static String getTempFileNameForResourcesFile(String resFile) throws IOException {
    return TestUtils.loadCert(resFile).getAbsolutePath();
  }

  /**
   * Helper method to build DownstreamTlsContext for above tests. Called from other classes as well.
   */
  public static DownstreamTlsContext buildDownstreamTlsContextFromFilenames(
      @Nullable String privateKey, @Nullable String certChain, @Nullable String trustCa) {
    return buildDownstreamTlsContextFromFilenamesWithClientAuth(privateKey, certChain, trustCa,
        false);
  }

  /**
   * Helper method to build DownstreamTlsContext for above tests. Called from other classes as well.
   */
  public static DownstreamTlsContext buildDownstreamTlsContextFromFilenamesWithClientCertRequired(
      @Nullable String privateKey,
      @Nullable String certChain,
      @Nullable String trustCa) {

    return buildDownstreamTlsContextFromFilenamesWithClientAuth(privateKey, certChain, trustCa,
        true);
  }

  private static DownstreamTlsContext buildDownstreamTlsContextFromFilenamesWithClientAuth(
      @Nullable String privateKey,
      @Nullable String certChain,
      @Nullable String trustCa,
      boolean requireClientCert) {
    // get temp file for each file
    try {
      if (certChain != null) {
        certChain = getTempFileNameForResourcesFile(certChain);
      }
      if (privateKey != null) {
        privateKey = getTempFileNameForResourcesFile(privateKey);
      }
      if (trustCa != null) {
        trustCa = getTempFileNameForResourcesFile(trustCa);
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
    return buildDownstreamTlsContext(
        buildCommonTlsContextFromFilenames(privateKey, certChain, trustCa), requireClientCert);
  }

  /**
   * Helper method to build UpstreamTlsContext for above tests. Called from other classes as well.
   */
  public static UpstreamTlsContext buildUpstreamTlsContextFromFilenames(
      @Nullable String privateKey, @Nullable String certChain, @Nullable String trustCa) {
    try {
      if (certChain != null) {
        certChain = getTempFileNameForResourcesFile(certChain);
      }
      if (privateKey != null) {
        privateKey = getTempFileNameForResourcesFile(privateKey);
      }
      if (trustCa != null) {
        trustCa = getTempFileNameForResourcesFile(trustCa);
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
    return SecretVolumeSslContextProviderTest.buildUpstreamTlsContext(
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

  static CommonTlsContext getCommonTlsContext(
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
}
