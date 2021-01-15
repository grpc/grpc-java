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
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.api.v2.core.GrpcService.GoogleGrpc;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.DataSource;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext.CombinedCertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.grpc.internal.testing.TestUtils;
import io.grpc.xds.EnvoyServerProtoData;
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

  static io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig buildSdsSecretConfigV2(
      String name, String targetUri, String channelType) {
    io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig sdsSecretConfig = null;
    if (!Strings.isNullOrEmpty(name) && !Strings.isNullOrEmpty(targetUri)) {
      sdsSecretConfig =
          io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig.newBuilder()
              .setName(name)
              .setSdsConfig(buildConfigSourceV2(targetUri, channelType))
              .build();
    }
    return sdsSecretConfig;
  }

  private static SdsSecretConfig
      buildSdsSecretConfig(String name, String targetUri, String channelType) {
    SdsSecretConfig sdsSecretConfig = null;
    if (!Strings.isNullOrEmpty(name) && !Strings.isNullOrEmpty(targetUri)) {
      sdsSecretConfig =
          SdsSecretConfig.newBuilder()
              .setName(name)
              .setSdsConfig(buildConfigSource(targetUri, channelType))
              .build();
    }
    return sdsSecretConfig;
  }

  /**
   * Builds a {@link io.envoyproxy.envoy.api.v2.core.ConfigSource} for the given targetUri.
   *
   * @param channelType specifying "inproc" creates an Inprocess channel for testing.
   */
  private static io.envoyproxy.envoy.api.v2.core.ConfigSource buildConfigSourceV2(
      String targetUri, String channelType) {
    GoogleGrpc.Builder googleGrpcBuilder = GoogleGrpc.newBuilder().setTargetUri(targetUri);
    if (channelType != null) {
      Struct.Builder structBuilder = Struct.newBuilder()
          .putFields("channelType", Value.newBuilder().setStringValue(channelType).build());
      googleGrpcBuilder.setConfig(structBuilder.build());
    }
    return io.envoyproxy.envoy.api.v2.core.ConfigSource.newBuilder()
        .setApiConfigSource(
            io.envoyproxy.envoy.api.v2.core.ApiConfigSource.newBuilder()
                .setApiType(ApiType.GRPC)
                .addGrpcServices(
                    io.envoyproxy.envoy.api.v2.core.GrpcService.newBuilder()
                        .setGoogleGrpc(googleGrpcBuilder.build())
                        .build())
                .build())
        .build();
  }

  /**
   * Builds a {@link ConfigSource} for the given targetUri.
   *
   * @param channelType specifying "inproc" creates an Inprocess channel for testing.
   */
  private static ConfigSource buildConfigSource(String targetUri, String channelType) {
    GrpcService.GoogleGrpc.Builder googleGrpcBuilder =
        GrpcService.GoogleGrpc.newBuilder().setTargetUri(targetUri);
    if (channelType != null) {
      Struct.Builder structBuilder = Struct.newBuilder()
          .putFields("channelType", Value.newBuilder().setStringValue(channelType).build());
      googleGrpcBuilder.setConfig(structBuilder.build());
    }
    return ConfigSource.newBuilder()
        .setApiConfigSource(
            ApiConfigSource.newBuilder()
                .setApiType(ApiConfigSource.ApiType.GRPC)
                .addGrpcServices(GrpcService.newBuilder().setGoogleGrpc(googleGrpcBuilder))
                .build())
        .build();
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
  static io.envoyproxy.envoy.api.v2.auth.CommonTlsContext
      buildCommonTlsContextWithAdditionalValuesV2(
          String certName,
          String certTargetUri,
          String validationContextName,
          String validationContextTargetUri,
          Iterable<String> verifySubjectAltNames,
          Iterable<String> alpnNames,
          String channelType) {

    io.envoyproxy.envoy.api.v2.auth.CommonTlsContext.Builder builder =
        io.envoyproxy.envoy.api.v2.auth.CommonTlsContext.newBuilder();

    io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig sdsSecretConfig =
        buildSdsSecretConfigV2(certName, certTargetUri, channelType);
    if (sdsSecretConfig != null) {
      builder.addTlsCertificateSdsSecretConfigs(sdsSecretConfig);
    }
    sdsSecretConfig =
        buildSdsSecretConfigV2(validationContextName, validationContextTargetUri, channelType);
    io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext certValidationContext =
        verifySubjectAltNames == null ? null
            : io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext.newBuilder()
                .addAllVerifySubjectAltName(verifySubjectAltNames).build();

    if (sdsSecretConfig != null && certValidationContext != null) {
      io.envoyproxy.envoy.api.v2.auth.CommonTlsContext.CombinedCertificateValidationContext
          combined =
              io.envoyproxy.envoy.api.v2.auth.CommonTlsContext.CombinedCertificateValidationContext
                  .newBuilder()
                  .setDefaultValidationContext(certValidationContext)
                  .setValidationContextSdsSecretConfig(sdsSecretConfig)
                  .build();
      builder.setCombinedValidationContext(combined);
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

  /** takes additional values and creates CombinedCertificateValidationContext as needed. */
  static CommonTlsContext buildCommonTlsContextWithAdditionalValues(
      String certName,
      String certTargetUri,
      String validationContextName,
      String validationContextTargetUri,
      Iterable<StringMatcher> matchSubjectAltNames,
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
        matchSubjectAltNames == null
            ? null
            : CertificateValidationContext.newBuilder()
                .addAllMatchSubjectAltNames(matchSubjectAltNames)
                .build();
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
  static io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext buildDownstreamTlsContextV2(
      io.envoyproxy.envoy.api.v2.auth.CommonTlsContext commonTlsContext,
      boolean requireClientCert) {
    io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext downstreamTlsContext =
        io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext.newBuilder()
            .setCommonTlsContext(commonTlsContext)
            .setRequireClientCertificate(BoolValue.of(requireClientCert))
            .build();
    return downstreamTlsContext;
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

  /** Helper method to build internal DownstreamTlsContext for multiple test classes. */
  static EnvoyServerProtoData.DownstreamTlsContext buildInternalDownstreamTlsContext(
      CommonTlsContext commonTlsContext, boolean requireClientCert) {
    return EnvoyServerProtoData.DownstreamTlsContext.fromEnvoyProtoDownstreamTlsContext(
        buildDownstreamTlsContext(commonTlsContext, requireClientCert));
  }

  /** Helper method for creating DownstreamTlsContext values with names. */
  public static io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext
      buildTestDownstreamTlsContextV2(String certName, String validationContextName) {
    return buildDownstreamTlsContextV2(
        buildCommonTlsContextWithAdditionalValuesV2(
            certName,
            "unix:/var/run/sds/uds_path",
            validationContextName,
            "unix:/var/run/sds/uds_path",
            Arrays.asList("spiffe://grpc-sds-testing.svc.id.goog/ns/default/sa/bob"),
            Arrays.asList("managed-tls"),
            null),
        /* requireClientCert= */ false);
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
            Arrays.asList(
                StringMatcher.newBuilder()
                    .setExact("spiffe://grpc-sds-testing.svc.id.goog/ns/default/sa/bob")
                    .build()),
            Arrays.asList("managed-tls"),
            null),
        /* requireClientCert= */ false);
  }

  public static EnvoyServerProtoData.DownstreamTlsContext buildTestInternalDownstreamTlsContext(
      String certName, String validationContextName) {
    return EnvoyServerProtoData.DownstreamTlsContext.fromEnvoyProtoDownstreamTlsContext(
        buildTestDownstreamTlsContext(certName, validationContextName));
  }

  static String getTempFileNameForResourcesFile(String resFile) throws IOException {
    return TestUtils.loadCert(resFile).getAbsolutePath();
  }

  /**
   * Helper method to build DownstreamTlsContext for above tests. Called from other classes as well.
   */
  public static EnvoyServerProtoData.DownstreamTlsContext buildDownstreamTlsContextFromFilenames(
      @Nullable String privateKey, @Nullable String certChain, @Nullable String trustCa) {
    return buildDownstreamTlsContextFromFilenamesWithClientAuth(privateKey, certChain, trustCa,
        false);
  }

  /**
   * Helper method to build DownstreamTlsContext for above tests. Called from other classes as well.
   */
  public static EnvoyServerProtoData.DownstreamTlsContext
      buildDownstreamTlsContextFromFilenamesWithClientCertRequired(
          @Nullable String privateKey, @Nullable String certChain, @Nullable String trustCa) {
    return buildDownstreamTlsContextFromFilenamesWithClientAuth(privateKey, certChain, trustCa,
        true);
  }

  private static EnvoyServerProtoData.DownstreamTlsContext
      buildDownstreamTlsContextFromFilenamesWithClientAuth(
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
    return buildInternalDownstreamTlsContext(
        buildCommonTlsContextFromFilenames(privateKey, certChain, trustCa), requireClientCert);
  }

  /**
   * Helper method to build UpstreamTlsContext for above tests. Called from other classes as well.
   */
  public static EnvoyServerProtoData.UpstreamTlsContext buildUpstreamTlsContextFromFilenames(
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

  /**
   * Helper method to build UpstreamTlsContext for above tests. Called from other classes as well.
   */
  static EnvoyServerProtoData.UpstreamTlsContext buildUpstreamTlsContext(
      CommonTlsContext commonTlsContext) {
    UpstreamTlsContext upstreamTlsContext =
        UpstreamTlsContext.newBuilder().setCommonTlsContext(commonTlsContext).build();
    return EnvoyServerProtoData.UpstreamTlsContext.fromEnvoyProtoUpstreamTlsContext(
        upstreamTlsContext);
  }
}
