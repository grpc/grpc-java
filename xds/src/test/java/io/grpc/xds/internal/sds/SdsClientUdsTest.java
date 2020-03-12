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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.MoreExecutors;
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.auth.Secret;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.GrpcService;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.netty.channel.epoll.Epoll;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatchers;

/** Unit tests for {@link SdsClient} using UDS transport. */
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

  @Before
  public void setUp() throws IOException {
    Assume.assumeTrue(Epoll.isAvailable());
    serverMock = mock(TestSdsServer.ServerMock.class);
    server = new TestSdsServer(serverMock);
    server.startServer(SDSCLIENT_TEST_SOCKET, /* useUds= */ true, /* useInterceptor= */ false);
    ConfigSource configSource = buildConfigSource("unix:" + SDSCLIENT_TEST_SOCKET);
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

    when(serverMock.getSecretFor("name1"))
        .thenReturn(
            SdsClientTest.getOneTlsCertSecret("name1", SERVER_0_KEY_FILE, SERVER_0_PEM_FILE));

    sdsClient.watchSecret(mockWatcher);
    // wait until our server received the requests
    assertThat(server.requestsCounter.tryAcquire(2, 1000, TimeUnit.MILLISECONDS)).isTrue();
    SdsClientTest.verifyDiscoveryRequest(server.lastGoodRequest, "", "", node, "name1");
    SdsClientTest.verifySecretWatcher(mockWatcher, "name1", SERVER_0_KEY_FILE, SERVER_0_PEM_FILE);
    SdsClientTest.verifyDiscoveryRequest(
        server.lastRequestOnlyForAck,
        server.lastResponse.getVersionInfo(),
        server.lastResponse.getNonce(),
        node,
        "name1");

    reset(mockWatcher);
    when(serverMock.getSecretFor("name1"))
        .thenReturn(
            SdsClientTest.getOneTlsCertSecret("name1", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE));
    server.generateAsyncResponse("name1");
    // wait until our server received the request
    assertThat(server.requestsCounter.tryAcquire(1, 1000, TimeUnit.MILLISECONDS)).isTrue();
    SdsClientTest.verifySecretWatcher(mockWatcher, "name1", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE);

    reset(mockWatcher);
    sdsClient.cancelSecretWatch(mockWatcher);
    server.generateAsyncResponse("name1");
    // wait until our server received the request
    assertThat(server.requestsCounter.tryAcquire(1, 1000, TimeUnit.MILLISECONDS)).isTrue();
    verify(mockWatcher, never()).onSecretChanged(ArgumentMatchers.any(Secret.class));
  }
}
