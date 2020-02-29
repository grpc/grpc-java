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
import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext.CombinedCertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.core.DataSource;
import java.util.Arrays;

/** Utility class for client and server ssl provider tests. */
public class CommonTlsContextTestsUtil {

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

  /**
   * Helper method to build DownstreamTlsContext for multiple test classes.
   */
  static DownstreamTlsContext buildDownstreamTlsContext(CommonTlsContext commonTlsContext) {
    DownstreamTlsContext downstreamTlsContext =
        DownstreamTlsContext.newBuilder().setCommonTlsContext(commonTlsContext).build();
    return downstreamTlsContext;
  }

  /** Helper method for creating DownstreamTlsContext values for tests. */
  public static DownstreamTlsContext buildTestDownstreamTlsContext() {
    return buildDownstreamTlsContext(
        buildCommonTlsContextWithAdditionalValues(
            "google-sds-config-default",
            "unix:/var/run/sds/uds_path",
            "ROOTCA",
            "unix:/var/run/sds/uds_path",
            Arrays.asList("spiffe://grpc-sds-testing.svc.id.goog/ns/default/sa/bob"),
            Arrays.asList("managed-tls"),
            null
        ));
  }
}
