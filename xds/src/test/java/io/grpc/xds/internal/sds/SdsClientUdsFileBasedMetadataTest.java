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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.google.common.io.Files;
import com.google.common.util.concurrent.MoreExecutors;
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.netty.channel.epoll.Epoll;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SdsClient} & {@link FileBasedPluginCredential} using UDS transport. */
@RunWith(JUnit4.class)
public class SdsClientUdsFileBasedMetadataTest {

  private static final String SDSCLIENT_TEST_SOCKET = "/tmp/sdsclient-test.socket";

  private static final String TOKEN_FILE_NAME = "tempFile.txt";
  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();
  private TestSdsServer.ServerMock serverMock;
  private TestSdsServer server;
  private SdsClient sdsClient;
  private Node node;
  private SdsSecretConfig sdsSecretConfig;
  private File tempTokenFile;

  @Before
  public void setUp() throws IOException {
    Assume.assumeTrue(Epoll.isAvailable());
    serverMock = mock(TestSdsServer.ServerMock.class);
    server = new TestSdsServer(serverMock);
    server.startServer(SDSCLIENT_TEST_SOCKET, /* useUds= */ true, /* useInterceptor= */ true);

    tempTokenFile = tempFolder.newFile(TOKEN_FILE_NAME);
    Files.write("test-token-content".getBytes(StandardCharsets.UTF_8), tempTokenFile);

    ConfigSource configSource =
        SdsClientFileBasedMetadataTest.buildConfigSourceWithCreds(
            "unix:" + SDSCLIENT_TEST_SOCKET,
            null,
            tempTokenFile.getAbsolutePath(),
            null,
            FileBasedPluginCredential.PLUGIN_NAME);
    sdsSecretConfig =
        SdsSecretConfig.newBuilder().setSdsConfig(configSource).setName("name1").build();
    node = Node.newBuilder().setId("sds-client-temp-test2").build();
    sdsClient =
        SdsClient.Factory.createSdsClient(
            sdsSecretConfig, node, MoreExecutors.directExecutor(), MoreExecutors.directExecutor());
    sdsClient.start();
  }

  @After
  public void teardown() throws InterruptedException {
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

    doReturn(
            SdsClientTest.getOneTlsCertSecret(
                "name1", SdsClientTest.SERVER_0_KEY_FILE, SdsClientTest.SERVER_0_PEM_FILE))
        .when(serverMock)
        .getSecretFor("name1");

    sdsClient.watchSecret(mockWatcher);
    // wait until our server received the requests
    assertThat(server.requestsCounter.tryAcquire(2, 1000, TimeUnit.MILLISECONDS)).isTrue();
    assertThat(server.lastK8sJwtTokenValue).isEqualTo("test-token-content");
  }
}
