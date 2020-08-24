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
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CA_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CLIENT_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CLIENT_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_1_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_1_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.doChecksOnSslContext;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.getValueThruCallback;
import static io.grpc.xds.internal.sds.SdsClientTest.getOneCertificateValidationContextSecret;
import static io.grpc.xds.internal.sds.SdsClientTest.getOneTlsCertSecret;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.MoreExecutors;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.grpc.Status.Code;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.TestCallback;
import java.io.IOException;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SdsClientSslContextProvider}. */
@RunWith(JUnit4.class)
public class SdsSslContextProviderTest {

  private TestSdsServer.ServerMock serverMock;
  private TestSdsServer server;
  private Node node;

  @Before
  public void setUp() throws Exception {
    serverMock = mock(TestSdsServer.ServerMock.class);
    server = new TestSdsServer(serverMock);
    server.startServer(/* name= */ "inproc", /* useUds= */ false, /* useInterceptor= */ false);

    node = Node.newBuilder().setId("sds-client-temp-test1").build();
  }

  @After
  public void teardown() throws InterruptedException {
    server.shutdown();
  }

  /** Helper method to build SdsClientSslContextProvider from given names. */
  private SdsClientSslContextProvider getSdsClientSslContextProvider(
      String certName,
      String validationContextName,
      Iterable<StringMatcher> matchSubjectAltNames,
      Iterable<String> alpnProtocols)
      throws IOException {

    CommonTlsContext commonTlsContext =
        CommonTlsContextTestsUtil.buildCommonTlsContextWithAdditionalValues(
            certName,
            /* certTargetUri= */ "inproc",
            validationContextName,
            /* validationContextTargetUri= */ "inproc",
            matchSubjectAltNames,
            alpnProtocols,
            /* channelType= */ "inproc");

    return SdsClientSslContextProvider.getProvider(
        CommonTlsContextTestsUtil.buildUpstreamTlsContext(commonTlsContext),
        node,
        MoreExecutors.directExecutor(),
        MoreExecutors.directExecutor());
  }

  /** Helper method to build SdsServerSslContextProvider from given names. */
  private SdsServerSslContextProvider getSdsServerSslContextProvider(
      String certName,
      String validationContextName,
      Iterable<StringMatcher> matchSubjectAltNames,
      Iterable<String> alpnProtocols)
      throws IOException {

    CommonTlsContext commonTlsContext =
        CommonTlsContextTestsUtil.buildCommonTlsContextWithAdditionalValues(
            certName,
            /* certTargetUri= */ "inproc",
            validationContextName,
            /* validationContextTargetUri= */ "inproc",
            matchSubjectAltNames,
            alpnProtocols,
            /* channelType= */ "inproc");

    return SdsServerSslContextProvider.getProvider(
        CommonTlsContextTestsUtil.buildInternalDownstreamTlsContext(
            commonTlsContext, /* requireClientCert= */ false),
        node,
        MoreExecutors.directExecutor(),
        MoreExecutors.directExecutor());
  }

  @Test
  public void testProviderForServer() throws IOException {
    when(serverMock.getSecretFor(/* name= */ "cert1"))
        .thenReturn(getOneTlsCertSecret(/* name= */ "cert1", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE));
    when(serverMock.getSecretFor(/* name= */ "valid1"))
        .thenReturn(getOneCertificateValidationContextSecret(/* name= */ "valid1", CA_PEM_FILE));

    SdsServerSslContextProvider provider =
        getSdsServerSslContextProvider("cert1", "valid1", null, null);
    TestCallback testCallback = getValueThruCallback(provider);

    doChecksOnSslContext(true, testCallback.updatedSslContext, /* expectedApnProtos= */ null);
  }

  @Test
  public void testProviderForClient() throws IOException {
    when(serverMock.getSecretFor(/* name= */ "cert1"))
        .thenReturn(getOneTlsCertSecret(/* name= */ "cert1", CLIENT_KEY_FILE, CLIENT_PEM_FILE));
    when(serverMock.getSecretFor("valid1"))
        .thenReturn(getOneCertificateValidationContextSecret(/* name= */ "valid1", CA_PEM_FILE));

    SdsClientSslContextProvider provider =
        getSdsClientSslContextProvider(
            /* certName= */ "cert1",
            /* validationContextName= */ "valid1",
            /* matchSubjectAltNames= */ null,
            /* alpnProtocols= */ null);
    TestCallback testCallback = getValueThruCallback(provider);

    doChecksOnSslContext(false, testCallback.updatedSslContext, /* expectedApnProtos= */ null);
  }

  @Test
  public void testProviderForServer_onlyCert() throws IOException {
    when(serverMock.getSecretFor(/* name= */ "cert1"))
        .thenReturn(getOneTlsCertSecret(/* name= */ "cert1", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE));

    SdsServerSslContextProvider provider =
        getSdsServerSslContextProvider(
            /* certName= */ "cert1",
            /* validationContextName= */ null,
            /* matchSubjectAltNames= */ null,
            /* alpnProtocols= */ null);
    TestCallback testCallback = getValueThruCallback(provider);

    doChecksOnSslContext(true, testCallback.updatedSslContext, /* expectedApnProtos= */ null);
  }

  @Test
  public void getProviderForClient_onlyTrust() throws IOException {
    when(serverMock.getSecretFor(/* name= */ "valid1"))
        .thenReturn(getOneCertificateValidationContextSecret(/* name= */ "valid1", CA_PEM_FILE));

    SdsClientSslContextProvider provider =
        getSdsClientSslContextProvider(
            /* certName= */ null,
            /* validationContextName= */ "valid1",
            /* matchSubjectAltNames= */ null,
            null);
    TestCallback testCallback = getValueThruCallback(provider);

    doChecksOnSslContext(false, testCallback.updatedSslContext, /* expectedApnProtos= */ null);
  }

  @Test
  public void getProviderForServer_noCert_throwsException() throws IOException {
    when(serverMock.getSecretFor(/* name= */ "valid1"))
        .thenReturn(getOneCertificateValidationContextSecret(/* name= */ "valid1", CA_PEM_FILE));

    SdsServerSslContextProvider provider =
        getSdsServerSslContextProvider(
            /* certName= */ null,
            /* validationContextName= */ "valid1",
            /* matchSubjectAltNames= */ null,
            /* alpnProtocols= */ null);
    TestCallback testCallback = getValueThruCallback(provider);

    assertThat(server.lastNack).isNotNull();
    assertThat(server.lastNack.getVersionInfo()).isEmpty();
    assertThat(server.lastNack.getResponseNonce()).isEmpty();
    com.google.rpc.Status errorDetail = server.lastNack.getErrorDetail();
    assertThat(errorDetail.getCode()).isEqualTo(Code.UNKNOWN.value());
    assertThat(errorDetail.getMessage()).isEqualTo("Secret not updated");
    assertThat(testCallback.updatedSslContext).isNull();
  }

  @Test
  public void testProviderForClient_withSubjectAltNames() throws IOException {
    when(serverMock.getSecretFor(/* name= */ "cert1"))
        .thenReturn(getOneTlsCertSecret(/* name= */ "cert1", CLIENT_KEY_FILE, CLIENT_PEM_FILE));
    when(serverMock.getSecretFor("valid1"))
        .thenReturn(getOneCertificateValidationContextSecret(/* name= */ "valid1", CA_PEM_FILE));

    SdsClientSslContextProvider provider =
        getSdsClientSslContextProvider(
            /* certName= */ "cert1",
            /* validationContextName= */ "valid1",
            Arrays.asList(
                StringMatcher.newBuilder()
                    .setExact("spiffe://grpc-sds-testing.svc.id.goog/ns/default/sa/bob")
                    .build()),
            /* alpnProtocols= */ null);

    TestCallback testCallback = getValueThruCallback(provider);
    doChecksOnSslContext(false, testCallback.updatedSslContext, /* expectedApnProtos= */ null);
  }

  @Test
  public void testProviderForClient_withAlpnProtocols() throws IOException {
    when(serverMock.getSecretFor(/* name= */ "cert1"))
        .thenReturn(getOneTlsCertSecret(/* name= */ "cert1", CLIENT_KEY_FILE, CLIENT_PEM_FILE));
    when(serverMock.getSecretFor("valid1"))
        .thenReturn(getOneCertificateValidationContextSecret(/* name= */ "valid1", CA_PEM_FILE));

    SdsClientSslContextProvider provider =
        getSdsClientSslContextProvider(
            /* certName= */ "cert1",
            /* validationContextName= */ "valid1",
            /* matchSubjectAltNames= */ null,
            /* alpnProtocols= */ Arrays.asList("managed-mtls", "h2"));
    TestCallback testCallback = getValueThruCallback(provider);

    doChecksOnSslContext(
        false, testCallback.updatedSslContext, Arrays.asList("managed-mtls", "h2"));
  }

  @Test
  public void testProviderForServer_withAlpnProtocols() throws IOException {
    when(serverMock.getSecretFor(/* name= */ "cert1"))
        .thenReturn(getOneTlsCertSecret(/* name= */ "cert1", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE));
    when(serverMock.getSecretFor(/* name= */ "valid1"))
        .thenReturn(getOneCertificateValidationContextSecret(/* name= */ "valid1", CA_PEM_FILE));

    SdsServerSslContextProvider provider =
        getSdsServerSslContextProvider(
            /* certName= */ "cert1",
            /* validationContextName= */ "valid1",
            /* matchSubjectAltNames= */ null,
            /* alpnProtocols= */ Arrays.asList("managed-mtls", "h2"));
    TestCallback testCallback = getValueThruCallback(provider);

    doChecksOnSslContext(
        true, testCallback.updatedSslContext, Arrays.asList("managed-mtls", "h2"));
  }
}
