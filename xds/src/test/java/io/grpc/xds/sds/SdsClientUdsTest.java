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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.auth.Secret;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.DataSource;
import io.envoyproxy.envoy.api.v2.core.GrpcService;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.internal.testing.TestUtils;
import io.netty.channel.epoll.Epoll;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

import org.junit.After;
import org.junit.Assume;
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
public class SdsClientUdsTest {

  private static final String SERVER_0_PEM_FILE = "server0.pem";
  private static final String SERVER_0_KEY_FILE = "server0.key";
  private static final String SERVER_1_PEM_FILE = "server1.pem";
  private static final String SERVER_1_KEY_FILE = "server1.key";
  private static final String SDSCLIENT_TEST_SOCKET = "/tmp/sdsclient-test.socket";

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
    Assume.assumeTrue(Epoll.isAvailable());
    serverMock = mock(TestSdsServer.ServerMock.class);
    server = new TestSdsServer(serverMock);
    server.startServer(SDSCLIENT_TEST_SOCKET, true);
    ConfigSource configSource = buildConfigSource("unix:" + SDSCLIENT_TEST_SOCKET);
    sdsSecretConfig =
        SdsSecretConfig.newBuilder().setSdsConfig(configSource).setName("name1").build();
    node = Node.newBuilder().setId("sds-client-temp-test2").build();
    sdsClient = new SdsClient(sdsSecretConfig, node);
    sdsClient.start(MoreExecutors.directExecutor());
  }

  @After
  public void teardown() {
    if (sdsClient != null) {
      sdsClient.shutdown();
    }
    if (server != null) {
      server.shutdown();
    }
  }

  @Test
  public void testSecretWatcher_tlsCertificate() throws IOException, InterruptedException {
    final SdsClient.SecretWatcher mockWatcher = mock(SdsClient.SecretWatcher.class);

    when(serverMock.getSecretFor("name1"))
        .thenReturn(getOneTlsCertSecret("name1", SERVER_0_KEY_FILE, SERVER_0_PEM_FILE));

    doAnswer(new Answer<Object>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        synchronized (mockWatcher) {
          mockWatcher.notifyAll();
        }
        return null;
      }
    }).when(mockWatcher).onSecretChanged(any(Secret.class));
    sdsClient.watchSecret(mockWatcher);
    synchronized (mockWatcher) {
      mockWatcher.wait(100L);
    }
    //Thread.sleep(100L);
    discoveryRequestVerification(server.lastGoodRequest, "[name1]", "", "");
    secretWatcherVerification(mockWatcher, "name1", SERVER_0_KEY_FILE, SERVER_0_PEM_FILE);
    Thread.sleep(100L);
    discoveryRequestVerification(
        server.lastRequestOnlyForAck,
        "[name1]",
        server.lastResponse.getVersionInfo(),
        server.lastResponse.getNonce());

    reset(mockWatcher);
    when(serverMock.getSecretFor("name1"))
        .thenReturn(getOneTlsCertSecret("name1", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE));
    server.generateAsyncResponse("name1");
    synchronized (mockWatcher) {
      mockWatcher.wait(100L);
    }
    secretWatcherVerification(mockWatcher, "name1", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE);

    reset(mockWatcher);
    sdsClient.cancelSecretWatch(mockWatcher);
    server.generateAsyncResponse("name1");
    Thread.sleep(100L);
    verify(mockWatcher, never()).onSecretChanged(ArgumentMatchers.any(Secret.class));
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
}
