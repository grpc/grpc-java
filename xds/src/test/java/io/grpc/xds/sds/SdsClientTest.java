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
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.internal.testing.TestUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

/** Unit tests for {@link SdsClient}. */
@RunWith(JUnit4.class)
public class SdsClientTest {

  private static final String SERVER_0_PEM_FILE = "server0.pem";
  private static final String SERVER_0_KEY_FILE = "server0.key";
  private static final String SERVER_1_PEM_FILE = "server1.pem";
  private static final String SERVER_1_KEY_FILE = "server1.key";
  private static final String CA_PEM_FILE = "ca.pem";

  private TestSdsServer.SecretGetter secretGetter;
  private TestSdsServer server;
  private SdsClient sdsClient;
  private Node node;
  private ConfigSource configSource;

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
    secretGetter = mock(TestSdsServer.SecretGetter.class);
    server = new TestSdsServer(secretGetter);
    server.startServer("inproc");
    configSource = buildConfigSource("inproc");
    node = Node.newBuilder().setId("sds-client-temp-test1").build();
    ManagedChannel channel = InProcessChannelBuilder.forName("inproc").directExecutor().build();
    sdsClient = new SdsClient(configSource, node, channel);
    sdsClient.start();
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

    when(secretGetter.getFor("name1"))
        .thenReturn(getOneTlsCertSecret("name1", SERVER_0_KEY_FILE, SERVER_0_PEM_FILE));

    SdsSecretConfig sdsSecretConfig =
        SdsSecretConfig.newBuilder().setSdsConfig(configSource).setName("name1").build();
    SdsClient.SecretWatcherHandle handle = sdsClient.watchSecret(sdsSecretConfig, mockWatcher);
    discoveryRequestVerification(server.lastGoodRequest, "[name1]", "", "");
    discoveryRequestVerification(
        server.lastRequestOnlyForAck,
        "[name1]",
        server.lastResponse.getVersionInfo(),
        server.lastResponse.getNonce());
    secretWatcherVerification(mockWatcher, "name1", SERVER_0_KEY_FILE, SERVER_0_PEM_FILE);

    reset(mockWatcher);
    when(secretGetter.getFor("name1"))
        .thenReturn(getOneTlsCertSecret("name1", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE));
    server.generateAsyncResponse("name1");
    secretWatcherVerification(mockWatcher, "name1", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE);

    reset(mockWatcher);
    sdsClient.cancelSecretWatch(handle);
    server.generateAsyncResponse("name1");
    verify(mockWatcher, never()).onSecretChanged(ArgumentMatchers.any(Secret.class));
  }

  @Test
  public void testSecretWatcher_certificateValidationContext() throws IOException {
    SdsClient.SecretWatcher mockWatcher = mock(SdsClient.SecretWatcher.class);

    when(secretGetter.getFor("name2"))
        .thenReturn(getOneCertificateValidationContextSecret("name2", CA_PEM_FILE));

    SdsSecretConfig sdsSecretConfig =
        SdsSecretConfig.newBuilder().setSdsConfig(configSource).setName("name2").build();
    SdsClient.SecretWatcherHandle handle = sdsClient.watchSecret(sdsSecretConfig, mockWatcher);
    discoveryRequestVerification(server.lastGoodRequest, "[name2]", "", "");
    discoveryRequestVerification(
        server.lastRequestOnlyForAck,
        "[name2]",
        server.lastResponse.getVersionInfo(),
        server.lastResponse.getNonce());
    secretWatcherVerification(mockWatcher, "name2", CA_PEM_FILE);

    reset(mockWatcher);
    when(secretGetter.getFor("name2"))
        .thenReturn(getOneCertificateValidationContextSecret("name2", SERVER_1_PEM_FILE));
    server.generateAsyncResponse("name2");
    secretWatcherVerification(mockWatcher, "name2", SERVER_1_PEM_FILE);
    
    reset(mockWatcher);
    sdsClient.cancelSecretWatch(handle);
    server.generateAsyncResponse("name2");
    verify(mockWatcher, never()).onSecretChanged(ArgumentMatchers.any(Secret.class));
  }

  @Test
  public void testSecretWatcher_tlsCertificate_multipleWatchers() throws IOException {
    SdsClient.SecretWatcher mockWatcher1 = mock(SdsClient.SecretWatcher.class);
    SdsClient.SecretWatcher mockWatcher2 = mock(SdsClient.SecretWatcher.class);

    when(secretGetter.getFor("name1"))
        .thenReturn(getOneTlsCertSecret("name1", SERVER_0_KEY_FILE, SERVER_0_PEM_FILE));
    when(secretGetter.getFor("name2"))
        .thenReturn(getOneTlsCertSecret("name2", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE));

    SdsSecretConfig sdsSecretConfig1 =
        SdsSecretConfig.newBuilder().setSdsConfig(configSource).setName("name1").build();
    SdsClient.SecretWatcherHandle handle1 = sdsClient.watchSecret(sdsSecretConfig1, mockWatcher1);
    discoveryRequestVerification(server.lastGoodRequest, "[name1]", "", "");
    discoveryRequestVerification(
        server.lastRequestOnlyForAck,
        "[name1]",
        server.lastResponse.getVersionInfo(),
        server.lastResponse.getNonce());
    secretWatcherVerification(mockWatcher1, "name1", SERVER_0_KEY_FILE, SERVER_0_PEM_FILE);

    final String lastVersionInfo = server.lastResponse.getVersionInfo();
    final String lastNonce = server.lastResponse.getNonce();

    SdsSecretConfig sdsSecretConfig2 =
        SdsSecretConfig.newBuilder().setSdsConfig(configSource).setName("name2").build();
    SdsClient.SecretWatcherHandle handle2 = sdsClient.watchSecret(sdsSecretConfig2, mockWatcher2);
    discoveryRequestVerification(server.lastGoodRequest, "[name2]", lastVersionInfo, lastNonce);
    discoveryRequestVerification(
        server.lastRequestOnlyForAck,
        "[name2]",
        server.lastResponse.getVersionInfo(),
        server.lastResponse.getNonce());
    secretWatcherVerification(mockWatcher2, "name2", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE);

    // verify no callbacks on name1 when name2 updates
    reset(mockWatcher1);
    reset(mockWatcher2);
    server.generateAsyncResponse("name2");
    verify(mockWatcher1, never()).onSecretChanged(any(Secret.class));
    secretWatcherVerification(mockWatcher2, "name2", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE);

    // cancel watch on name2 ...
    reset(mockWatcher1);
    reset(mockWatcher2);
    sdsClient.cancelSecretWatch(handle2);
    server.generateAsyncResponse("name2");
    verify(mockWatcher2, never()).onSecretChanged(any(Secret.class));

    // but watch on name1 still works
    server.generateAsyncResponse("name1");
    secretWatcherVerification(mockWatcher1, "name1", SERVER_0_KEY_FILE, SERVER_0_PEM_FILE);

    // clean up
    sdsClient.cancelSecretWatch(handle1);
  }

  private void discoveryRequestVerification(
      DiscoveryRequest request, String resourceNames, String versionInfo, String responseNonce) {
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

  private void secretWatcherVerification(
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

  private Secret getOneTlsCertSecret(String name, String keyFileName, String certFileName)
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
