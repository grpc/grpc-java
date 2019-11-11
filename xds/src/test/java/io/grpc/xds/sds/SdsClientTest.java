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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.auth.Secret;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.DataSource;
import io.envoyproxy.envoy.api.v2.core.GrpcService;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.internal.testing.TestUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Unit tests for {@link SdsClient}. */
@RunWith(JUnit4.class)
public class SdsClientTest {

  private static final String SERVER_0_PEM_FILE = "server0.pem";
  private static final String SERVER_0_KEY_FILE = "server0.key";
  private static final String SERVER_1_PEM_FILE = "server1.pem";
  private static final String SERVER_1_KEY_FILE = "server1.key";
  private static final String CA_PEM_FILE = "ca.pem";

  private TestSdsServer.ServerMock serverMock;
  private TestSdsServer server;
  private SdsClient sdsClient;
  private Node node;
  private SdsSecretConfig sdsSecretConfig;

  private static ConfigSource buildConfigSource(String targetUri) {
    return ConfigSource.newBuilder()
        .setApiConfigSource(
            ApiConfigSource.newBuilder()
                .setApiType(ApiConfigSource.ApiType.GRPC)
                .addGrpcServices(
                    GrpcService.newBuilder()
                        .setGoogleGrpc(
                            GrpcService.GoogleGrpc.newBuilder().setTargetUri(targetUri).build())
                        .build())
                .build())
        .build();
  }

  private static String getResourcesFileContent(String resFile) throws IOException {
    String tempFile = TestUtils.loadCert(resFile).getAbsolutePath();
    return new String(Files.readAllBytes(Paths.get(tempFile)), StandardCharsets.UTF_8);
  }

  @Before
  public void setUp() throws IOException {
    serverMock = mock(TestSdsServer.ServerMock.class);
    server = new TestSdsServer(serverMock);
    server.startServer("inproc", false);
    ConfigSource configSource = buildConfigSource("inproc");
    sdsSecretConfig =
        SdsSecretConfig.newBuilder().setSdsConfig(configSource).setName("name1").build();
    node = Node.newBuilder().setId("sds-client-temp-test1").build();
    ManagedChannel channel = InProcessChannelBuilder.forName("inproc").directExecutor().build();
    sdsClient = new SdsClient(sdsSecretConfig, node, channel);
    sdsClient.start(null);
  }

  @After
  public void teardown() {
    sdsClient.shutdown();
    server.shutdown();
  }

  @Test
  public void configSourceUdsTarget() {
    ConfigSource configSource = buildConfigSource("unix:/tmp/uds_path");
    String targetUri = SdsClient.extractUdsTarget(configSource);
    assertThat(targetUri).isEqualTo("unix:/tmp/uds_path");
  }

  @Test
  public void testSecretWatcher_tlsCertificate() throws IOException {
    SdsClient.SecretWatcher mockWatcher = mock(SdsClient.SecretWatcher.class);

    when(serverMock.getSecretFor("name1"))
        .thenReturn(getOneTlsCertSecret("name1", SERVER_0_KEY_FILE, SERVER_0_PEM_FILE));

    sdsClient.watchSecret(mockWatcher);
    discoveryRequestVerification(server.lastGoodRequest, "[name1]", "", "", node);
    discoveryRequestVerification(
        server.lastRequestOnlyForAck,
        "[name1]",
        server.lastResponse.getVersionInfo(),
        server.lastResponse.getNonce(),
        node);
    secretWatcherVerification(mockWatcher, "name1", SERVER_0_KEY_FILE, SERVER_0_PEM_FILE);

    reset(mockWatcher);
    when(serverMock.getSecretFor("name1"))
        .thenReturn(getOneTlsCertSecret("name1", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE));
    server.generateAsyncResponse("name1");
    secretWatcherVerification(mockWatcher, "name1", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE);

    reset(mockWatcher);
    sdsClient.cancelSecretWatch(mockWatcher);
    server.generateAsyncResponse("name1");
    verify(mockWatcher, never()).onSecretChanged(ArgumentMatchers.any(Secret.class));
  }

  @Test
  public void testSecretWatcher_certificateValidationContext() throws IOException {
    SdsClient.SecretWatcher mockWatcher = mock(SdsClient.SecretWatcher.class);

    when(serverMock.getSecretFor("name1"))
        .thenReturn(getOneCertificateValidationContextSecret("name1", CA_PEM_FILE));

    sdsClient.watchSecret(mockWatcher);
    discoveryRequestVerification(server.lastGoodRequest, "[name1]", "", "", node);
    discoveryRequestVerification(
        server.lastRequestOnlyForAck,
        "[name1]",
        server.lastResponse.getVersionInfo(),
        server.lastResponse.getNonce(),
        node);
    secretWatcherVerification(mockWatcher, "name1", CA_PEM_FILE);

    reset(mockWatcher);
    when(serverMock.getSecretFor("name1"))
        .thenReturn(getOneCertificateValidationContextSecret("name1", SERVER_1_PEM_FILE));
    server.generateAsyncResponse("name1");
    secretWatcherVerification(mockWatcher, "name1", SERVER_1_PEM_FILE);

    reset(mockWatcher);
    sdsClient.cancelSecretWatch(mockWatcher);
    server.generateAsyncResponse("name1");
    verify(mockWatcher, never()).onSecretChanged(ArgumentMatchers.any(Secret.class));
  }

  @Test
  public void testSecretWatcher_tlsCertificate_multipleWatchers() throws IOException {
    SdsClient.SecretWatcher mockWatcher1 = mock(SdsClient.SecretWatcher.class);
    SdsClient.SecretWatcher mockWatcher2 = mock(SdsClient.SecretWatcher.class);

    when(serverMock.getSecretFor("name1"))
        .thenReturn(getOneTlsCertSecret("name1", SERVER_0_KEY_FILE, SERVER_0_PEM_FILE));

    sdsClient.watchSecret(mockWatcher1);
    discoveryRequestVerification(server.lastGoodRequest, "[name1]", "", "", node);
    discoveryRequestVerification(
        server.lastRequestOnlyForAck,
        "[name1]",
        server.lastResponse.getVersionInfo(),
        server.lastResponse.getNonce(),
        node);
    secretWatcherVerification(mockWatcher1, "name1", SERVER_0_KEY_FILE, SERVER_0_PEM_FILE);

    final DiscoveryRequest lastGoodRequest = server.lastGoodRequest;
    final DiscoveryRequest lastRequestOnlyForAck = server.lastRequestOnlyForAck;

    // add mockWatcher2
    sdsClient.watchSecret(mockWatcher2);
    // should not have generated a request because client has the info.
    assertThat(server.lastGoodRequest).isSameInstanceAs(lastGoodRequest);
    assertThat(server.lastRequestOnlyForAck).isSameInstanceAs(lastRequestOnlyForAck);
    verify(serverMock, times(1)).getSecretFor(any(String.class));

    // ... mockWatcher2 gets updated
    secretWatcherVerification(mockWatcher2, "name1", SERVER_0_KEY_FILE, SERVER_0_PEM_FILE);

    reset(mockWatcher1);
    reset(mockWatcher2);
    sdsClient.cancelSecretWatch(mockWatcher2);
    server.generateAsyncResponse("name1");
    verify(mockWatcher2, never()).onSecretChanged(any(Secret.class));

    // ... watch on name1 still works
    secretWatcherVerification(mockWatcher1, "name1", SERVER_0_KEY_FILE, SERVER_0_PEM_FILE);
  }

  @Test
  public void testSecretWatcher_onError_expectOnError() throws IOException {
    SdsClient.SecretWatcher mockWatcher = mock(SdsClient.SecretWatcher.class);
    final ArrayList<DiscoveryRequest> requestArrayList = new ArrayList<>();

    when(serverMock.onNext(any(DiscoveryRequest.class)))
        .thenAnswer(
            new Answer<Boolean>() {
              @Override
              public Boolean answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                DiscoveryRequest req = (DiscoveryRequest) args[0];
                requestArrayList.add(req);
                server.discoveryService.inboundStreamObserver.responseObserver.onError(
                    Status.NOT_FOUND.asException());
                return true;
              }
            });
    sdsClient.watchSecret(mockWatcher);
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(mockWatcher, times(1)).onError(statusCaptor.capture());
    Status status = statusCaptor.getValue();
    assertThat(status).isEqualTo(Status.NOT_FOUND);
    assertThat(requestArrayList.size()).isEqualTo(1);
  }

  @Test
  public void testSecretWatcher_onSecretChangedException_expectNack() throws IOException {
    SdsClient.SecretWatcher mockWatcher = mock(SdsClient.SecretWatcher.class);

    when(serverMock.getSecretFor("name1"))
        .thenReturn(getOneTlsCertSecret("name1", SERVER_0_KEY_FILE, SERVER_0_PEM_FILE));
    doThrow(new RuntimeException("test exception-abc"))
        .when(mockWatcher)
        .onSecretChanged(any(Secret.class));

    sdsClient.watchSecret(mockWatcher);
    discoveryRequestVerification(server.lastGoodRequest, "[name1]", "", "", node);
    assertThat(server.lastRequestOnlyForAck).isNull();
    assertThat(server.lastNack).isNotNull();
    assertThat(server.lastNack.getVersionInfo()).isEmpty();
    assertThat(server.lastNack.getResponseNonce()).isEmpty();
    com.google.rpc.Status errorDetail = server.lastNack.getErrorDetail();
    assertThat(errorDetail.getCode()).isEqualTo(Status.Code.INTERNAL.value());
    assertThat(errorDetail.getMessage()).isEqualTo("test exception-abc");
  }

  static void discoveryRequestVerification(
      DiscoveryRequest request,
      String resourceNames,
      String versionInfo,
      String responseNonce,
      Node node) {
    assertThat(request).isNotNull();
    assertThat(request.getNode()).isEqualTo(node);
    assertThat(Arrays.toString(request.getResourceNamesList().toArray())).isEqualTo(resourceNames);
    assertThat(request.getTypeUrl()).isEqualTo("type.googleapis.com/envoy.api.v2.auth.Secret");
    if (versionInfo != null) {
      assertThat(request.getVersionInfo()).isEqualTo(versionInfo);
    }
    if (responseNonce != null) {
      assertThat(request.getResponseNonce()).isEqualTo(responseNonce);
    }
  }

  static void secretWatcherVerification(
      SdsClient.SecretWatcher mockWatcher,
      String secretName,
      String keyFileName,
      String certFileName)
      throws IOException {
    ArgumentCaptor<Secret> secretCaptor = ArgumentCaptor.forClass(Secret.class);
    verify(mockWatcher, times(1)).onSecretChanged(secretCaptor.capture());
    Secret secret = secretCaptor.getValue();
    assertThat(secret.getName()).isEqualTo(secretName);
    assertThat(secret.hasTlsCertificate()).isTrue();
    TlsCertificate tlsCertificate = secret.getTlsCertificate();
    assertThat(tlsCertificate.getPrivateKey().getInlineBytes().toStringUtf8())
        .isEqualTo(getResourcesFileContent(keyFileName));
    assertThat(tlsCertificate.getCertificateChain().getInlineBytes().toStringUtf8())
        .isEqualTo(getResourcesFileContent(certFileName));
  }

  private void secretWatcherVerification(
      SdsClient.SecretWatcher mockWatcher, String secretName, String caFileName)
      throws IOException {
    ArgumentCaptor<Secret> secretCaptor = ArgumentCaptor.forClass(Secret.class);
    verify(mockWatcher, times(1)).onSecretChanged(secretCaptor.capture());
    Secret secret = secretCaptor.getValue();
    assertThat(secret.getName()).isEqualTo(secretName);
    assertThat(secret.hasValidationContext()).isTrue();
    CertificateValidationContext certificateValidationContext = secret.getValidationContext();
    assertThat(certificateValidationContext.getTrustedCa().getInlineBytes().toStringUtf8())
        .isEqualTo(getResourcesFileContent(caFileName));
  }

  static Secret getOneTlsCertSecret(String name, String keyFileName, String certFileName)
      throws IOException {
    TlsCertificate tlsCertificate =
        TlsCertificate.newBuilder()
            .setPrivateKey(
                DataSource.newBuilder()
                    .setInlineBytes(ByteString.copyFromUtf8(getResourcesFileContent(keyFileName)))
                    .build())
            .setCertificateChain(
                DataSource.newBuilder()
                    .setInlineBytes(ByteString.copyFromUtf8(getResourcesFileContent(certFileName)))
                    .build())
            .build();
    return Secret.newBuilder().setName(name).setTlsCertificate(tlsCertificate).build();
  }

  private Secret getOneCertificateValidationContextSecret(String name, String trustFileName)
      throws IOException {
    CertificateValidationContext certificateValidationContext =
        CertificateValidationContext.newBuilder()
            .setTrustedCa(
                DataSource.newBuilder()
                    .setInlineBytes(ByteString.copyFromUtf8(getResourcesFileContent(trustFileName)))
                    .build())
            .build();

    return Secret.newBuilder()
        .setName(name)
        .setValidationContext(certificateValidationContext)
        .build();
  }
}
