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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.google.common.io.Files;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.GrpcService;
import io.envoyproxy.envoy.api.v2.core.GrpcService.GoogleGrpc;
import io.envoyproxy.envoy.api.v2.core.GrpcService.GoogleGrpc.CallCredentials;
import io.envoyproxy.envoy.api.v2.core.GrpcService.GoogleGrpc.CallCredentials.MetadataCredentialsFromPlugin;
import io.envoyproxy.envoy.api.v2.core.GrpcService.GoogleGrpc.ChannelCredentials;
import io.envoyproxy.envoy.api.v2.core.GrpcService.GoogleGrpc.GoogleLocalCredentials;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.Metadata;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SdsClient} and {@link FileBasedPluginCredential}. */
@RunWith(JUnit4.class)
public class SdsClientFileBasedMetadataTest {

  /**
   * K8S_SA_JWT_TOKEN_HEADER_KEYNAME is the request header key for k8s jwt token. Binary header name
   * must have suffix "-bin".
   */
  private static final String K8S_SA_JWT_TOKEN_HEADER_KEYNAME = "istio_sds_credentials_header-bin";

  static final Metadata.Key<byte[]> K8S_SA_JWT_TOKEN_HEADER_METADATA_KEY =
      Metadata.Key.of(K8S_SA_JWT_TOKEN_HEADER_KEYNAME, Metadata.BINARY_BYTE_MARSHALLER);
  private static final String TOKEN_FILE_NAME = "tempFile.txt";
  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();
  private TestSdsServer.ServerMock serverMock;
  private TestSdsServer server;
  private SdsClient sdsClient;
  private Node node;
  private SdsSecretConfig sdsSecretConfig;
  private File tempTokenFile;

  @SuppressWarnings("deprecation")
  static ConfigSource buildConfigSourceWithCreds(
      String targetUri,
      String channelType,
      String filename,
      String headerPrefix,
      String pluginName) {
    GoogleGrpc.Builder googleGrpcBuilder = GoogleGrpc.newBuilder().setTargetUri(targetUri);

    if (filename != null) {
      googleGrpcBuilder.setStatPrefix("sdsstat");
      googleGrpcBuilder.setCredentialsFactoryName(pluginName);
      googleGrpcBuilder.setChannelCredentials(
          ChannelCredentials.newBuilder()
              .setLocalCredentials(GoogleLocalCredentials.newBuilder())
              .build());

      Struct.Builder configStructBuilder =
          Struct.newBuilder()
              .putFields(
                  "header_key",
                  Value.newBuilder().setStringValue(K8S_SA_JWT_TOKEN_HEADER_KEYNAME).build())
              .putFields(
                  FileBasedPluginCredential.SECRET_DATA,
                  Value.newBuilder()
                      .setStructValue(
                          Struct.newBuilder()
                              .putFields(
                                  FileBasedPluginCredential.FILENAME,
                                  Value.newBuilder().setStringValue(filename).build()))
                      .build());

      if (headerPrefix != null) {
        configStructBuilder.putFields(
            FileBasedPluginCredential.HEADER_PREFIX,
            Value.newBuilder().setStringValue(headerPrefix).build());
      }

      MetadataCredentialsFromPlugin.Builder metadataCredBuilder =
              MetadataCredentialsFromPlugin.newBuilder().setName(pluginName);
      metadataCredBuilder.setConfig(configStructBuilder);

      CallCredentials.Builder callCredBuilder =
          CallCredentials.newBuilder().setFromPlugin(metadataCredBuilder);
      googleGrpcBuilder.addCallCredentials(callCredBuilder);
    }
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

  @Before
  public void setUp() throws IOException {
    serverMock = mock(TestSdsServer.ServerMock.class);
    server = new TestSdsServer(serverMock);
    server.startServer("inproc", /* useUds= */ false, /* useInterceptor= */ true);

    tempTokenFile = tempFolder.newFile(TOKEN_FILE_NAME);
    Files.write("test-token-content".getBytes(StandardCharsets.UTF_8), tempTokenFile);

    ConfigSource configSource =
        buildConfigSourceWithCreds(
            "inproc",
            "inproc",
            tempTokenFile.getAbsolutePath(),
            null,
            FileBasedPluginCredential.PLUGIN_NAME);
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
  public void configSourceUdsTarget_noHeaderPrefix() {
    ConfigSource configSource =
        buildConfigSourceWithCreds(
            "unix:/tmp/uds_path",
            null,
            "/var/run/secrets/tokens/istio-token",
            null,
            FileBasedPluginCredential.PLUGIN_NAME);
    SdsClient.ChannelInfo channelInfo = SdsClient.Factory.extractChannelInfo(configSource);
    assertThat(channelInfo.callCredentials).isInstanceOf(FileBasedPluginCredential.class);
    FileBasedPluginCredential fileBasedPluginCredential =
        (FileBasedPluginCredential) channelInfo.callCredentials;
    assertThat(fileBasedPluginCredential.headerKey).isEqualTo(K8S_SA_JWT_TOKEN_HEADER_KEYNAME);
    assertThat(fileBasedPluginCredential.headerPrefix).isEmpty();
  }

  @Test
  public void configSourceUdsTarget_withHeaderPrefix() {
    ConfigSource configSource =
        buildConfigSourceWithCreds(
            "unix:/tmp/uds_path",
            null,
            "/var/run/secrets/tokens/istio-token",
            "test-header-prefix",
            FileBasedPluginCredential.PLUGIN_NAME);
    SdsClient.ChannelInfo channelInfo = SdsClient.Factory.extractChannelInfo(configSource);
    assertThat(channelInfo.callCredentials).isInstanceOf(FileBasedPluginCredential.class);
    FileBasedPluginCredential fileBasedPluginCredential =
        (FileBasedPluginCredential) channelInfo.callCredentials;
    assertThat(fileBasedPluginCredential.headerPrefix).isEqualTo("test-header-prefix");
  }

  @Test
  public void configSource_badPluginName_expectException() {
    ConfigSource configSource =
        buildConfigSourceWithCreds(
            "unix:/tmp/uds_path",
            null,
            "/var/run/secrets/tokens/istio-token",
            null,
            "bad-plugin-name");
    try {
      SdsClient.Factory.extractChannelInfo(configSource);
      fail("expected exception");
    } catch (IllegalArgumentException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("factory name should be envoy.grpc_credentials.file_based_metadata");
    }
  }

  @Test
  public void testSecretWatcher_tlsCertificate() throws IOException {
    SdsClient.SecretWatcher mockWatcher = mock(SdsClient.SecretWatcher.class);

    doReturn(
            SdsClientTest.getOneTlsCertSecret(
                "name1", SdsClientTest.SERVER_0_KEY_FILE, SdsClientTest.SERVER_0_PEM_FILE))
        .when(serverMock)
        .getSecretFor("name1");

    sdsClient.watchSecret(mockWatcher);
    assertThat(server.lastK8sJwtTokenValue).isEqualTo("test-token-content");
  }
}
