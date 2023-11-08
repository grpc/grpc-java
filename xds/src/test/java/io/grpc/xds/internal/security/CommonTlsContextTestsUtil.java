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

package io.grpc.xds.internal.security;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.CharStreams;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.BoolValue;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateProviderPluginInstance;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext.CertificateProviderInstance;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext.CombinedCertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.grpc.internal.testing.TestUtils;
import io.grpc.testing.TlsTesting;
import io.grpc.xds.EnvoyServerProtoData;
import io.grpc.xds.internal.security.trust.CertificateUtils;
import io.netty.handler.ssl.SslContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
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

  /** takes additional values and creates CombinedCertificateValidationContext as needed. */
  @SuppressWarnings("deprecation")
  static CommonTlsContext buildCommonTlsContextWithAdditionalValues(
      String certInstanceName, String certName,
      String validationContextCertInstanceName, String validationContextCertName,
      Iterable<StringMatcher> matchSubjectAltNames,
      Iterable<String> alpnNames) {

    CommonTlsContext.Builder builder = CommonTlsContext.newBuilder();

    CertificateProviderInstance certificateProviderInstance = CertificateProviderInstance
        .newBuilder().setInstanceName(certInstanceName).setCertificateName(certName).build();
    if (certificateProviderInstance != null) {
      builder.setTlsCertificateCertificateProviderInstance(certificateProviderInstance);
    }
    CertificateProviderInstance validationCertificateProviderInstance =
        CertificateProviderInstance.newBuilder().setInstanceName(validationContextCertInstanceName)
            .setCertificateName(validationContextCertName).build();
    CertificateValidationContext certValidationContext =
        matchSubjectAltNames == null
            ? null
            : CertificateValidationContext.newBuilder()
                .addAllMatchSubjectAltNames(matchSubjectAltNames)
                .build();
    if (validationCertificateProviderInstance != null) {
      CombinedCertificateValidationContext.Builder combinedBuilder =
          CombinedCertificateValidationContext.newBuilder()
              .setValidationContextCertificateProviderInstance(
                  validationCertificateProviderInstance);
      if (certValidationContext != null) {
        combinedBuilder = combinedBuilder.setDefaultValidationContext(certValidationContext);
      }
      builder.setCombinedValidationContext(combinedBuilder);
    } else if (validationCertificateProviderInstance != null) {
      builder
          .setValidationContextCertificateProviderInstance(validationCertificateProviderInstance);
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
    DownstreamTlsContext.Builder downstreamTlsContextBuilder =
        DownstreamTlsContext.newBuilder()
            .setRequireClientCertificate(BoolValue.of(requireClientCert));
    if (commonTlsContext != null) {
      downstreamTlsContextBuilder = downstreamTlsContextBuilder
          .setCommonTlsContext(commonTlsContext);
    }
    return downstreamTlsContextBuilder.build();
  }

  /** Helper method to build DownstreamTlsContext for multiple test classes. */
  public static EnvoyServerProtoData.DownstreamTlsContext buildDownstreamTlsContext(
      String commonInstanceName, boolean hasRootCert,
      boolean requireClientCertificate) {
    return buildDownstreamTlsContextForCertProviderInstance(
        commonInstanceName,
        "default",
        hasRootCert ? commonInstanceName : null,
        hasRootCert ? "ROOT" : null,
        /* alpnProtocols= */ null,
        /* staticCertValidationContext= */ null,
        /* requireClientCert= */ requireClientCertificate);
  }

  /** Helper method to build internal DownstreamTlsContext for multiple test classes. */
  static EnvoyServerProtoData.DownstreamTlsContext buildInternalDownstreamTlsContext(
      CommonTlsContext commonTlsContext, boolean requireClientCert) {
    return EnvoyServerProtoData.DownstreamTlsContext.fromEnvoyProtoDownstreamTlsContext(
        buildDownstreamTlsContext(commonTlsContext, requireClientCert));
  }

  /** Helper method for creating DownstreamTlsContext values with names. */
  public static DownstreamTlsContext buildTestDownstreamTlsContext(
      String certName, String validationContextCertName, boolean useSans) {
    CommonTlsContext commonTlsContext = null;
    if (certName != null || validationContextCertName != null || useSans) {
      commonTlsContext = buildCommonTlsContextWithAdditionalValues(
          "cert-instance-name", certName,
          "cert-instance-name", validationContextCertName,
          useSans ? Arrays.asList(
              StringMatcher.newBuilder()
                  .setExact("spiffe://grpc-sds-testing.svc.id.goog/ns/default/sa/bob")
                  .build()) : null,
          Arrays.asList("managed-tls"));
    }
    return buildDownstreamTlsContext(commonTlsContext, /* requireClientCert= */ false);
  }

  public static EnvoyServerProtoData.DownstreamTlsContext buildTestInternalDownstreamTlsContext(
      String certName, String validationContextName) {
    return EnvoyServerProtoData.DownstreamTlsContext.fromEnvoyProtoDownstreamTlsContext(
        buildTestDownstreamTlsContext(certName, validationContextName, true));
  }

  public static String getTempFileNameForResourcesFile(String resFile) throws IOException {
    return TestUtils.loadCert(resFile).getAbsolutePath();
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

  /** Helper method to build UpstreamTlsContext for multiple test classes. */
  public static EnvoyServerProtoData.UpstreamTlsContext buildUpstreamTlsContext(
      String commonInstanceName, boolean hasIdentityCert) {
    return buildUpstreamTlsContextForCertProviderInstance(
        hasIdentityCert ? commonInstanceName : null,
        hasIdentityCert ? "default" : null,
        commonInstanceName,
        "ROOT",
        null,
        null);
  }

  /** Gets a cert from contents of a resource. */
  public static X509Certificate getCertFromResourceName(String resourceName)
      throws IOException, CertificateException {
    try (ByteArrayInputStream bais =
        new ByteArrayInputStream(getResourceContents(resourceName).getBytes(UTF_8))) {
      return CertificateUtils.toX509Certificate(bais);
    }
  }

  /** Gets contents of a certs resource. */
  public static String getResourceContents(String resourceName) throws IOException {
    InputStream inputStream = TlsTesting.loadCert(resourceName);
    String text = null;
    try (Reader reader = new InputStreamReader(inputStream, UTF_8)) {
      text = CharStreams.toString(reader);
    }
    return text;
  }

  @SuppressWarnings("deprecation")
  private static CommonTlsContext buildCommonTlsContextForCertProviderInstance(
      String certInstanceName,
      String certName,
      String rootInstanceName,
      String rootCertName,
      Iterable<String> alpnProtocols,
      CertificateValidationContext staticCertValidationContext) {
    CommonTlsContext.Builder builder = CommonTlsContext.newBuilder();
    if (certInstanceName != null) {
      builder =
          builder.setTlsCertificateCertificateProviderInstance(
              CommonTlsContext.CertificateProviderInstance.newBuilder()
                  .setInstanceName(certInstanceName)
                  .setCertificateName(certName));
    }
    builder =
        addCertificateValidationContext(
            builder, rootInstanceName, rootCertName, staticCertValidationContext);
    if (alpnProtocols != null) {
      builder.addAllAlpnProtocols(alpnProtocols);
    }
    return builder.build();
  }

  private static CommonTlsContext buildNewCommonTlsContextForCertProviderInstance(
          String certInstanceName,
          String certName,
          String rootInstanceName,
          String rootCertName,
          Iterable<String> alpnProtocols,
          CertificateValidationContext staticCertValidationContext) {
    CommonTlsContext.Builder builder = CommonTlsContext.newBuilder();
    if (certInstanceName != null) {
      builder =
              builder.setTlsCertificateProviderInstance(
                      CertificateProviderPluginInstance.newBuilder()
                              .setInstanceName(certInstanceName)
                              .setCertificateName(certName));
    }
    builder =
            addNewCertificateValidationContext(
                    builder, rootInstanceName, rootCertName, staticCertValidationContext);
    if (alpnProtocols != null) {
      builder.addAllAlpnProtocols(alpnProtocols);
    }
    return builder.build();
  }

  @SuppressWarnings("deprecation")
  private static CommonTlsContext.Builder addCertificateValidationContext(
      CommonTlsContext.Builder builder,
      String rootInstanceName,
      String rootCertName,
      CertificateValidationContext staticCertValidationContext) {
    if (rootInstanceName != null) {
      CertificateProviderInstance providerInstance =
          CertificateProviderInstance.newBuilder()
              .setInstanceName(rootInstanceName)
              .setCertificateName(rootCertName)
              .build();
      if (staticCertValidationContext != null) {
        CombinedCertificateValidationContext combined =
            CombinedCertificateValidationContext.newBuilder()
                .setDefaultValidationContext(staticCertValidationContext)
                .setValidationContextCertificateProviderInstance(providerInstance)
                .build();
        return builder.setCombinedValidationContext(combined);
      }
      builder = builder.setValidationContextCertificateProviderInstance(providerInstance);
    }
    return builder;
  }

  private static CommonTlsContext.Builder addNewCertificateValidationContext(
          CommonTlsContext.Builder builder,
          String rootInstanceName,
          String rootCertName,
          CertificateValidationContext staticCertValidationContext) {
    if (rootInstanceName != null) {
      CertificateProviderPluginInstance providerInstance =
          CertificateProviderPluginInstance.newBuilder()
              .setInstanceName(rootInstanceName)
              .setCertificateName(rootCertName)
              .build();
      CertificateValidationContext.Builder validationContextBuilder =
          staticCertValidationContext != null ? staticCertValidationContext.toBuilder()
              : CertificateValidationContext.newBuilder();
      return builder.setValidationContext(
          validationContextBuilder.setCaCertificateProviderInstance(providerInstance));
    }
    return builder;
  }

  /** Helper method to build UpstreamTlsContext for CertProvider tests. */
  public static EnvoyServerProtoData.UpstreamTlsContext
      buildUpstreamTlsContextForCertProviderInstance(
          @Nullable String certInstanceName,
          @Nullable String certName,
          @Nullable String rootInstanceName,
          @Nullable String rootCertName,
          Iterable<String> alpnProtocols,
          CertificateValidationContext staticCertValidationContext) {
    return buildUpstreamTlsContext(
        buildCommonTlsContextForCertProviderInstance(
            certInstanceName,
            certName,
            rootInstanceName,
            rootCertName,
            alpnProtocols,
            staticCertValidationContext));
  }

  /** Helper method to build UpstreamTlsContext for CertProvider tests. */
  public static EnvoyServerProtoData.UpstreamTlsContext
      buildNewUpstreamTlsContextForCertProviderInstance(
          @Nullable String certInstanceName,
          @Nullable String certName,
          @Nullable String rootInstanceName,
          @Nullable String rootCertName,
          Iterable<String> alpnProtocols,
          CertificateValidationContext staticCertValidationContext) {
    return buildUpstreamTlsContext(
        buildNewCommonTlsContextForCertProviderInstance(
            certInstanceName,
            certName,
            rootInstanceName,
            rootCertName,
            alpnProtocols,
            staticCertValidationContext));
  }

  /** Helper method to build DownstreamTlsContext for CertProvider tests. */
  public static EnvoyServerProtoData.DownstreamTlsContext
      buildDownstreamTlsContextForCertProviderInstance(
          @Nullable String certInstanceName,
          @Nullable String certName,
          @Nullable String rootInstanceName,
          @Nullable String rootCertName,
          Iterable<String> alpnProtocols,
          CertificateValidationContext staticCertValidationContext,
          boolean requireClientCert) {
    return buildInternalDownstreamTlsContext(
        buildCommonTlsContextForCertProviderInstance(
            certInstanceName,
            certName,
            rootInstanceName,
            rootCertName,
            alpnProtocols,
            staticCertValidationContext), requireClientCert);
  }

  /** Helper method to build DownstreamTlsContext for CertProvider tests. */
  public static EnvoyServerProtoData.DownstreamTlsContext
      buildNewDownstreamTlsContextForCertProviderInstance(
          @Nullable String certInstanceName,
          @Nullable String certName,
          @Nullable String rootInstanceName,
          @Nullable String rootCertName,
          Iterable<String> alpnProtocols,
          CertificateValidationContext staticCertValidationContext,
          boolean requireClientCert) {
    return buildInternalDownstreamTlsContext(
            buildNewCommonTlsContextForCertProviderInstance(
                    certInstanceName,
                    certName,
                    rootInstanceName,
                    rootCertName,
                    alpnProtocols,
                    staticCertValidationContext), requireClientCert);
  }

  /** Perform some simple checks on sslContext. */
  public static void doChecksOnSslContext(boolean server, SslContext sslContext,
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
   * Helper method to get the value thru directExecutor callback. Because of directExecutor this is
   * a synchronous callback - so need to provide a listener.
   */
  public static TestCallback getValueThruCallback(SslContextProvider provider) {
    return getValueThruCallback(provider, MoreExecutors.directExecutor());
  }

  /** Helper method to get the value thru callback with a user passed executor. */
  public static TestCallback getValueThruCallback(SslContextProvider provider, Executor executor) {
    TestCallback testCallback = new TestCallback(executor);
    provider.addCallback(testCallback);
    return testCallback;
  }

  public static class TestCallback extends SslContextProvider.Callback {

    public SslContext updatedSslContext;
    public Throwable updatedThrowable;

    public TestCallback(Executor executor) {
      super(executor);
    }

    @Override
    public void updateSslContext(SslContext sslContext) {
      updatedSslContext = sslContext;
    }

    @Override
    public void onException(Throwable throwable) {
      updatedThrowable = throwable;
    }
  }
}
