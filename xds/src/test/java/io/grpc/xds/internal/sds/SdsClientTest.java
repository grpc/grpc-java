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
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.auth.Secret;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.DataSource;
import io.envoyproxy.envoy.api.v2.core.GrpcService;
import io.envoyproxy.envoy.api.v2.core.GrpcService.GoogleGrpc;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.Status;
import io.grpc.Status.Code;
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

  static final String SERVER_0_PEM_FILE = "server0.pem";
  static final String SERVER_0_KEY_FILE = "server0.key";
  static final String SERVER_1_PEM_FILE = "server1.pem";
  static final String SERVER_1_KEY_FILE = "server1.key";
  static final String CA_PEM_FILE = "ca.pem";

  private TestSdsServer.ServerMock serverMock;
  private TestSdsServer server;
  private SdsClient sdsClient;
  private Node node;
  private SdsSecretConfig sdsSecretConfig;

  /**
   * Builds a {@link ConfigSource} for the given targetUri.
   *
   * @param channelType specifying "inproc" creates an Inprocess channel for testing.
   */
  static ConfigSource buildConfigSource(String targetUri, String channelType) {
    GoogleGrpc.Builder googleGrpcBuilder = GoogleGrpc.newBuilder().setTargetUri(targetUri);
    if (channelType != null) {
      Struct.Builder structBuilder = Struct.newBuilder();
      structBuilder.putFields(
          "channelType", Value.newBuilder().setStringValue(channelType).build());
      googleGrpcBuilder.setConfig(structBuilder.build());
    }
    return ConfigSource.newBuilder()
        .setApiConfigSource(
            ApiConfigSource.newBuilder()
                .setApiType(ApiConfigSource.ApiType.GRPC)
                .addGrpcServices(
                    GrpcService.newBuilder().setGoogleGrpc(googleGrpcBuilder.build()).build())
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
    server.startServer("inproc", /* useUds= */ false, /* useInterceptor= */ false);
    ConfigSource configSource = buildConfigSource("inproc", "inproc");
    sdsSecretConfig =
        SdsSecretConfig.newBuilder().setSdsConfig(configSource).setName("name1").build();
    node = Node.newBuilder().setId("sds-client-temp-test1").build();
    sdsClient =
        SdsClient.Factory.createSdsClient(
            sdsSecretConfig, node, MoreExecutors.directExecutor(), MoreExecutors.directExecutor());
    sdsClient.start();
  }

  @After
  public void teardown() throws InterruptedException {
    sdsClient.shutdown();
    server.shutdown();
  }

  @Test
  public void configSourceUdsTarget() {
    ConfigSource configSource = buildConfigSource("unix:/tmp/uds_path", null);
    SdsClient.ChannelInfo channelInfo = SdsClient.Factory.extractChannelInfo(configSource);
    assertThat(channelInfo.targetUri).isEqualTo("unix:/tmp/uds_path");
    assertThat(channelInfo.channelType).isNull();
  }

  @Test
  public void testSecretWatcher_tlsCertificate() throws IOException {
    SdsClient.SecretWatcher mockWatcher = mock(SdsClient.SecretWatcher.class);

    when(serverMock.getSecretFor("name1"))
        .thenReturn(getOneTlsCertSecret("name1", SERVER_0_KEY_FILE, SERVER_0_PEM_FILE));

    sdsClient.watchSecret(mockWatcher);
    verifyDiscoveryRequest(server.lastGoodRequest, "", "", node, "name1");
    verifyDiscoveryRequest(
        server.lastRequestOnlyForAck,
        server.lastResponse.getVersionInfo(),
        server.lastResponse.getNonce(),
        node,
        "name1");
    verifySecretWatcher(mockWatcher, "name1", SERVER_0_KEY_FILE, SERVER_0_PEM_FILE);

    reset(mockWatcher);
    when(serverMock.getSecretFor("name1"))
        .thenReturn(getOneTlsCertSecret("name1", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE));
    server.generateAsyncResponse("name1");
    verifySecretWatcher(mockWatcher, "name1", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE);

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
    verifyDiscoveryRequest(server.lastGoodRequest, "", "", node, "name1");
    verifyDiscoveryRequest(
        server.lastRequestOnlyForAck,
        server.lastResponse.getVersionInfo(),
        server.lastResponse.getNonce(),
        node,
        "name1");
    verifySecretWatcher(mockWatcher, "name1", CA_PEM_FILE);

    reset(mockWatcher);
    when(serverMock.getSecretFor("name1"))
        .thenReturn(getOneCertificateValidationContextSecret("name1", SERVER_1_PEM_FILE));
    server.generateAsyncResponse("name1");
    verifySecretWatcher(mockWatcher, "name1", SERVER_1_PEM_FILE);

    reset(mockWatcher);
    sdsClient.cancelSecretWatch(mockWatcher);
    server.generateAsyncResponse("name1");
    verify(mockWatcher, never()).onSecretChanged(ArgumentMatchers.any(Secret.class));
  }

  @Test
  public void testSecretWatcher_multipleWatchers_expectException() throws IOException {
    SdsClient.SecretWatcher mockWatcher1 = mock(SdsClient.SecretWatcher.class);
    SdsClient.SecretWatcher mockWatcher2 = mock(SdsClient.SecretWatcher.class);

    when(serverMock.getSecretFor("name1"))
        .thenReturn(getOneTlsCertSecret("name1", SERVER_0_KEY_FILE, SERVER_0_PEM_FILE));

    sdsClient.watchSecret(mockWatcher1);
    verifyDiscoveryRequest(server.lastGoodRequest, "", "", node, "name1");
    verifyDiscoveryRequest(
        server.lastRequestOnlyForAck,
        server.lastResponse.getVersionInfo(),
        server.lastResponse.getNonce(),
        node,
        "name1");
    verifySecretWatcher(mockWatcher1, "name1", SERVER_0_KEY_FILE, SERVER_0_PEM_FILE);

    // add mockWatcher2
    try {
      sdsClient.watchSecret(mockWatcher2);
      fail("exception expected");
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("watcher already set");
    }
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
    verifyDiscoveryRequest(server.lastGoodRequest, "", "", node, "name1");
    assertThat(server.lastRequestOnlyForAck).isNull();
    assertThat(server.lastNack).isNotNull();
    assertThat(server.lastNack.getVersionInfo()).isEmpty();
    assertThat(server.lastNack.getResponseNonce()).isEmpty();
    com.google.rpc.Status errorDetail = server.lastNack.getErrorDetail();
    assertThat(errorDetail.getCode()).isEqualTo(Code.UNKNOWN.value());
    assertThat(errorDetail.getMessage()).isEqualTo("Secret not updated");
  }

  static void verifyDiscoveryRequest(
      DiscoveryRequest request,
      String versionInfo,
      String responseNonce,
      Node node,
      String... resourceNames) {
    assertThat(request).isNotNull();
    assertThat(request.getNode()).isEqualTo(node);
    assertThat(request.getResourceNamesList()).isEqualTo(Arrays.asList(resourceNames));
    assertThat(request.getTypeUrl()).isEqualTo("type.googleapis.com/envoy.api.v2.auth.Secret");
    if (versionInfo != null) {
      assertThat(request.getVersionInfo()).isEqualTo(versionInfo);
    }
    if (responseNonce != null) {
      assertThat(request.getResponseNonce()).isEqualTo(responseNonce);
    }
  }

  static void verifySecretWatcher(
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

  private void verifySecretWatcher(
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

  static Secret getOneCertificateValidationContextSecret(String name, String trustFileName)
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
