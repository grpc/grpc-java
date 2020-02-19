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
import static io.grpc.xds.internal.sds.SdsClientTest.getOneCertificateValidationContextSecret;
import static io.grpc.xds.internal.sds.SdsClientTest.getOneTlsCertSecret;
import static io.grpc.xds.internal.sds.SecretVolumeSslContextProviderTest.doChecksOnSslContext;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.MoreExecutors;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.Status.Code;
import java.io.IOException;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SdsSslContextProvider}. */
@RunWith(JUnit4.class)
public class SdsSslContextProviderTest {

  private static final String SERVER_1_PEM_FILE = "server1.pem";
  private static final String SERVER_1_KEY_FILE = "server1.key";
  private static final String CLIENT_PEM_FILE = "client.pem";
  private static final String CLIENT_KEY_FILE = "client.key";
  private static final String CA_PEM_FILE = "ca.pem";

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

  /** Helper method to build SdsSslContextProvider from given names. */
  private SdsSslContextProvider<?> getSdsSslContextProvider(
      boolean server, String certName, String validationContextName,
      Iterable<String> verifySubjectAltNames, Iterable<String> alpnProtocols) throws IOException {

    CommonTlsContext commonTlsContext =
        CommonTlsContextTestsUtil.buildCommonTlsContextWithAdditionalValues(
            certName,
            /* certTargetUri= */ "inproc",
            validationContextName,
            /* validationContextTargetUri= */ "inproc",
            verifySubjectAltNames,
            alpnProtocols,
            /* channelType= */ "inproc");

    return server
        ? SdsSslContextProvider.getProviderForServer(
            CommonTlsContextTestsUtil.buildDownstreamTlsContext(commonTlsContext),
            node,
            MoreExecutors.directExecutor(),
            MoreExecutors.directExecutor())
        : SdsSslContextProvider.getProviderForClient(
            SecretVolumeSslContextProviderTest.buildUpstreamTlsContext(commonTlsContext),
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

    SdsSslContextProvider<?> provider =
        getSdsSslContextProvider(/* server= */ true, "cert1", "valid1", null, null);
    SecretVolumeSslContextProviderTest.TestCallback testCallback =
        SecretVolumeSslContextProviderTest.getValueThruCallback(provider);

    doChecksOnSslContext(true, testCallback.updatedSslContext, /* expectedApnProtos= */ null);
  }

  @Test
  public void testProviderForClient() throws IOException {
    when(serverMock.getSecretFor(/* name= */ "cert1"))
        .thenReturn(getOneTlsCertSecret(/* name= */ "cert1", CLIENT_KEY_FILE, CLIENT_PEM_FILE));
    when(serverMock.getSecretFor("valid1"))
        .thenReturn(getOneCertificateValidationContextSecret(/* name= */ "valid1", CA_PEM_FILE));

    SdsSslContextProvider<?> provider =
        getSdsSslContextProvider(
            /* server= */ false,
            /* certName= */ "cert1",
            /* validationContextName= */ "valid1",
            /* verifySubjectAltNames= */ null,
            /* alpnProtocols= */ null);
    SecretVolumeSslContextProviderTest.TestCallback testCallback =
        SecretVolumeSslContextProviderTest.getValueThruCallback(provider);

    doChecksOnSslContext(false, testCallback.updatedSslContext, /* expectedApnProtos= */ null);
  }

  @Test
  public void testProviderForServer_onlyCert() throws IOException {
    when(serverMock.getSecretFor(/* name= */ "cert1"))
        .thenReturn(getOneTlsCertSecret(/* name= */ "cert1", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE));

    SdsSslContextProvider<?> provider =
        getSdsSslContextProvider(
            /* server= */ true, /* certName= */ "cert1", /* validationContextName= */ null,
            /* verifySubjectAltNames= */ null, /* alpnProtocols= */ null);
    SecretVolumeSslContextProviderTest.TestCallback testCallback =
        SecretVolumeSslContextProviderTest.getValueThruCallback(provider);

    doChecksOnSslContext(true, testCallback.updatedSslContext, /* expectedApnProtos= */ null);
  }

  @Test
  public void getProviderForClient_onlyTrust() throws IOException {
    when(serverMock.getSecretFor(/* name= */ "valid1"))
        .thenReturn(getOneCertificateValidationContextSecret(/* name= */ "valid1", CA_PEM_FILE));

    SdsSslContextProvider<?> provider =
        getSdsSslContextProvider(
            /* server= */ false, /* certName= */ null, /* validationContextName= */ "valid1",
            /* verifySubjectAltNames= */ null, null);
    SecretVolumeSslContextProviderTest.TestCallback testCallback =
        SecretVolumeSslContextProviderTest.getValueThruCallback(provider);

    doChecksOnSslContext(false, testCallback.updatedSslContext, /* expectedApnProtos= */ null);
  }

  @Test
  public void getProviderForServer_noCert_throwsException() throws IOException {
    when(serverMock.getSecretFor(/* name= */ "valid1"))
        .thenReturn(getOneCertificateValidationContextSecret(/* name= */ "valid1", CA_PEM_FILE));

    SdsSslContextProvider<?> provider =
        getSdsSslContextProvider(
            /* server= */ true, /* certName= */ null, /* validationContextName= */ "valid1",
            /* verifySubjectAltNames= */ null, /* alpnProtocols= */ null);
    SecretVolumeSslContextProviderTest.TestCallback testCallback =
        SecretVolumeSslContextProviderTest.getValueThruCallback(provider);

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
        .thenReturn(getOneCertificateValidationContextSecret(/* name= */ "valid1",
            CA_PEM_FILE));

    SdsSslContextProvider<?> provider =
        getSdsSslContextProvider(
            /* server= */ false,
            /* certName= */ "cert1",
            /* validationContextName= */ "valid1",
            Arrays.asList(
                "spiffe://grpc-sds-testing.svc.id.goog/ns/default/sa/bob"),
            /* alpnProtocols= */ null);

    SecretVolumeSslContextProviderTest.TestCallback testCallback =
        SecretVolumeSslContextProviderTest.getValueThruCallback(provider);
    doChecksOnSslContext(false, testCallback.updatedSslContext, /* expectedApnProtos= */ null);
  }

  @Test
  public void testProviderForClient_withAlpnProtocols() throws IOException {
    when(serverMock.getSecretFor(/* name= */ "cert1"))
        .thenReturn(getOneTlsCertSecret(/* name= */ "cert1", CLIENT_KEY_FILE, CLIENT_PEM_FILE));
    when(serverMock.getSecretFor("valid1"))
        .thenReturn(getOneCertificateValidationContextSecret(/* name= */ "valid1", CA_PEM_FILE));

    SdsSslContextProvider<?> provider =
        getSdsSslContextProvider(
            /* server= */ false,
            /* certName= */ "cert1",
            /* validationContextName= */ "valid1",
            /* verifySubjectAltNames= */ null,
            /* alpnProtocols= */ Arrays.asList("managed-mtls", "h2"));
    SecretVolumeSslContextProviderTest.TestCallback testCallback =
        SecretVolumeSslContextProviderTest.getValueThruCallback(provider);

    doChecksOnSslContext(
        false, testCallback.updatedSslContext, Arrays.asList("managed-mtls", "h2"));
  }

  @Test
  public void testProviderForServer_withAlpnProtocols() throws IOException {
    when(serverMock.getSecretFor(/* name= */ "cert1"))
        .thenReturn(getOneTlsCertSecret(/* name= */ "cert1", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE));
    when(serverMock.getSecretFor(/* name= */ "valid1"))
        .thenReturn(getOneCertificateValidationContextSecret(/* name= */ "valid1", CA_PEM_FILE));

    SdsSslContextProvider<?> provider =
        getSdsSslContextProvider(
            /* server= */ true,
            /* certName= */ "cert1",
            /* validationContextName= */ "valid1",
            /* verifySubjectAltNames= */ null,
            /* alpnProtocols= */ Arrays.asList("managed-mtls", "h2"));
    SecretVolumeSslContextProviderTest.TestCallback testCallback =
        SecretVolumeSslContextProviderTest.getValueThruCallback(provider);

    doChecksOnSslContext(
        true, testCallback.updatedSslContext, Arrays.asList("managed-mtls", "h2"));
  }
}
