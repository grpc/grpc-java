/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.xds.client;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.collect.Iterables;
import io.grpc.CallCredentials;
import io.grpc.CompositeCallCredentials;
import io.grpc.xds.client.Bootstrapper.AuthorityInfo;
import io.grpc.xds.client.Bootstrapper.BootstrapInfo;
import io.grpc.xds.client.Bootstrapper.ServerInfo;
import java.io.IOException;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link BootstrapperImpl}. */
@RunWith(JUnit4.class)
public class BootstrapperImplTest {

  private static final String BOOTSTRAP_FILE_PATH = "/fake/fs/path/bootstrap.json";
  private static final String SERVER_URI = "trafficdirector.googleapis.com:443";

  private TestBootstrapperImpl bootstrapper;
  private boolean originalExperimentalXdsFallbackFlag;

  @Before
  public void setUp() {
    originalExperimentalXdsFallbackFlag = BootstrapperImpl.enableXdsFallback;
    BootstrapperImpl.enableXdsFallback = true;
  }

  @After
  public void tearDown() {
    BootstrapperImpl.enableXdsFallback = originalExperimentalXdsFallbackFlag;
  }

  private static class TestBootstrapperImpl extends BootstrapperImpl {
    private final String jsonContent;

    TestBootstrapperImpl(String jsonContent) {
      this.jsonContent = jsonContent;
    }

    @Override
    protected String getJsonContent() {
      return jsonContent;
    }

    @Override
    protected Object getImplSpecificConfig(Map<String, ?> serverConfig, String serverUri) {
      return "dummy-config";
    }
  }

  private static String getFilePath(JwtTokenFileCallCredentials credentials) {
    try {
      java.lang.reflect.Field field =
          JwtTokenFileCallCredentials.class.getDeclaredField("filePath");
      field.setAccessible(true);
      return (String) field.get(credentials);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static BootstrapperImpl.FileReader createFileReader(
      final String expectedPath, final String rawData) {
    return new BootstrapperImpl.FileReader() {
      @Override
      public String readFile(String path) throws IOException {
        assertThat(path).isEqualTo(expectedPath);
        return rawData;
      }
    };
  }

  @Test
  public void parseBootstrap_callCreds_flagDisabled() throws Exception {
    BootstrapperImpl.enableXdsBootstrapCallCreds = false;
    try {
      String rawData = "{\n"
          + "  \"xds_servers\": [\n"
          + "    {\n"
          + "      \"server_uri\": \"" + SERVER_URI + "\",\n"
          + "      \"channel_creds\": [{\"type\": \"insecure\"}],\n"
          + "      \"call_creds\": [\n"
          + "        {\n"
          + "          \"type\": \"jwt_token_file\",\n"
          + "          \"config\": {\n"
          + "            \"jwt_token_file\": \"/var/run/secrets/token\"\n"
          + "          }\n"
          + "        }\n"
          + "      ]\n"
          + "    }\n"
          + "  ]\n"
          + "}";
      bootstrapper = new TestBootstrapperImpl(rawData);
      bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
      BootstrapInfo info = bootstrapper.bootstrap();
      assertThat(info.servers()).hasSize(1);
      ServerInfo serverInfo = Iterables.getOnlyElement(info.servers());
      assertThat(serverInfo.callCredentials()).isNull();
    } finally {
      BootstrapperImpl.enableXdsBootstrapCallCreds = false;
    }
  }

  @Test
  public void parseBootstrap_xdsServers_jwtTokenFileCallCreds() throws Exception {
    BootstrapperImpl.enableXdsBootstrapCallCreds = true;
    try {
      String rawData = "{\n"
          + "  \"xds_servers\": [\n"
          + "    {\n"
          + "      \"server_uri\": \"" + SERVER_URI + "\",\n"
          + "      \"channel_creds\": [{\"type\": \"insecure\"}],\n"
          + "      \"call_creds\": [\n"
          + "        {\n"
          + "          \"type\": \"jwt_token_file\",\n"
          + "          \"config\": {\n"
          + "            \"jwt_token_file\": \"/var/run/secrets/token\"\n"
          + "          }\n"
          + "        }\n"
          + "      ]\n"
          + "    }\n"
          + "  ]\n"
          + "}";
      bootstrapper = new TestBootstrapperImpl(rawData);
      bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
      BootstrapInfo info = bootstrapper.bootstrap();
      assertThat(info.servers()).hasSize(1);
      ServerInfo serverInfo = Iterables.getOnlyElement(info.servers());
      assertThat(serverInfo.callCredentials())
          .isInstanceOf(JwtTokenFileCallCredentials.class);
      assertThat(getFilePath((JwtTokenFileCallCredentials) serverInfo.callCredentials()))
          .isEqualTo("/var/run/secrets/token");
    } finally {
      BootstrapperImpl.enableXdsBootstrapCallCreds = false;
    }
  }

  @Test
  public void parseBootstrap_authorities_jwtTokenFileCallCreds() throws Exception {
    BootstrapperImpl.enableXdsBootstrapCallCreds = true;
    try {
      String rawData = "{\n"
          + "  \"authorities\": {\n"
          + "    \"a.com\": {\n"
          + "      \"xds_servers\": [\n"
          + "        {\n"
          + "          \"server_uri\": \"td2.googleapis.com:443\",\n"
          + "          \"channel_creds\": [\n"
          + "            {\"type\": \"insecure\"}\n"
          + "          ],\n"
          + "          \"call_creds\": [\n"
          + "            {\n"
          + "              \"type\": \"jwt_token_file\",\n"
          + "              \"config\": {\n"
          + "                \"jwt_token_file\": \"/var/run/secrets/authority_token\"\n"
          + "              }\n"
          + "            }\n"
          + "          ]\n"
          + "        }\n"
          + "      ]\n"
          + "    }\n"
          + "  },\n"
          + "  \"xds_servers\": [\n"
          + "    {\n"
          + "      \"server_uri\": \"" + SERVER_URI + "\",\n"
          + "      \"channel_creds\": [\n"
          + "        {\"type\": \"insecure\"}\n"
          + "      ]\n"
          + "    }\n"
          + "  ]\n"
          + "}";
      bootstrapper = new TestBootstrapperImpl(rawData);
      bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
      BootstrapInfo info = bootstrapper.bootstrap();
      assertThat(info.authorities()).hasSize(1);
      AuthorityInfo authorityInfo = info.authorities().get("a.com");
      assertThat(authorityInfo.xdsServers()).hasSize(1);
      ServerInfo serverInfo = authorityInfo.xdsServers().get(0);
      assertThat(serverInfo.callCredentials())
          .isInstanceOf(JwtTokenFileCallCredentials.class);
      assertThat(getFilePath((JwtTokenFileCallCredentials) serverInfo.callCredentials()))
          .isEqualTo("/var/run/secrets/authority_token");
    } finally {
      BootstrapperImpl.enableXdsBootstrapCallCreds = false;
    }
  }

  @Test
  public void parseBootstrap_unsupportedCallCredsType_ignored() throws Exception {
    BootstrapperImpl.enableXdsBootstrapCallCreds = true;
    try {
      String rawData = "{\n"
          + "  \"xds_servers\": [\n"
          + "    {\n"
          + "      \"server_uri\": \"" + SERVER_URI + "\",\n"
          + "      \"channel_creds\": [{\"type\": \"insecure\"}],\n"
          + "      \"call_creds\": [\n"
          + "        {\n"
          + "          \"type\": \"unsupported_type\",\n"
          + "          \"config\": {\n"
          + "            \"some_field\": \"some_val\"\n"
          + "          }\n"
          + "        },\n"
          + "        {\n"
          + "          \"type\": \"jwt_token_file\",\n"
          + "          \"config\": {\n"
          + "            \"jwt_token_file\": \"/var/run/secrets/token\"\n"
          + "          }\n"
          + "        }\n"
          + "      ]\n"
          + "    }\n"
          + "  ]\n"
          + "}";
      bootstrapper = new TestBootstrapperImpl(rawData);
      bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
      BootstrapInfo info = bootstrapper.bootstrap();
      assertThat(info.servers()).hasSize(1);
      ServerInfo serverInfo = Iterables.getOnlyElement(info.servers());
      assertThat(serverInfo.callCredentials())
          .isInstanceOf(JwtTokenFileCallCredentials.class);
      assertThat(getFilePath((JwtTokenFileCallCredentials) serverInfo.callCredentials()))
          .isEqualTo("/var/run/secrets/token");
    } finally {
      BootstrapperImpl.enableXdsBootstrapCallCreds = false;
    }
  }

  @Test
  public void parseBootstrap_malformedCallCreds_throws() throws Exception {
    BootstrapperImpl.enableXdsBootstrapCallCreds = true;
    try {
      String rawData = "{\n"
          + "  \"xds_servers\": [\n"
          + "    {\n"
          + "      \"server_uri\": \"" + SERVER_URI + "\",\n"
          + "      \"channel_creds\": [{\"type\": \"insecure\"}],\n"
          + "      \"call_creds\": [\n"
          + "        {\n"
          + "          \"type\": \"jwt_token_file\",\n"
          + "          \"config\": {}\n"
          + "        }\n"
          + "      ]\n"
          + "    }\n"
          + "  ]\n"
          + "}";
      bootstrapper = new TestBootstrapperImpl(rawData);
      bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
      assertThrows(XdsInitializationException.class, bootstrapper::bootstrap);
    } finally {
      BootstrapperImpl.enableXdsBootstrapCallCreds = false;
    }
  }

  @Test
  public void parseBootstrap_xdsServers_multipleValidCallCreds() throws Exception {
    BootstrapperImpl.enableXdsBootstrapCallCreds = true;
    try {
      String rawData = "{\n"
          + "  \"xds_servers\": [\n"
          + "    {\n"
          + "      \"server_uri\": \"" + SERVER_URI + "\",\n"
          + "      \"channel_creds\": [{\"type\": \"insecure\"}],\n"
          + "      \"call_creds\": [\n"
          + "        {\n"
          + "          \"type\": \"jwt_token_file\",\n"
          + "          \"config\": { \"jwt_token_file\": \"/var/run/secrets/token1\" }\n"
          + "        },\n"
          + "        {\n"
          + "          \"type\": \"jwt_token_file\",\n"
          + "          \"config\": { \"jwt_token_file\": \"/var/run/secrets/token2\" }\n"
          + "        }\n"
          + "      ]\n"
          + "    }\n"
          + "  ]\n"
          + "}";
      bootstrapper = new TestBootstrapperImpl(rawData);
      bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
      BootstrapInfo info = bootstrapper.bootstrap();
      assertThat(info.servers()).hasSize(1);
      ServerInfo serverInfo = info.servers().get(0);
      CallCredentials creds = serverInfo.callCredentials();
      assertThat(creds).isNotNull();
      assertThat(creds).isInstanceOf(CompositeCallCredentials.class);
    } finally {
      BootstrapperImpl.enableXdsBootstrapCallCreds = false;
    }
  }

  @Test
  public void parseBootstrap_xdsServers_missingTypeCallCreds() throws Exception {
    BootstrapperImpl.enableXdsBootstrapCallCreds = true;
    try {
      String rawData = "{\n"
          + "  \"xds_servers\": [\n"
          + "    {\n"
          + "      \"server_uri\": \"" + SERVER_URI + "\",\n"
          + "      \"channel_creds\": [{\"type\": \"insecure\"}],\n"
          + "      \"call_creds\": [\n"
          + "        {\n"
          + "          \"config\": { \"jwt_token_file\": \"/var/run/secrets/token\" }\n"
          + "        }\n"
          + "      ]\n"
          + "    }\n"
          + "  ]\n"
          + "}";
      bootstrapper = new TestBootstrapperImpl(rawData);
      bootstrapper.setFileReader(createFileReader(BOOTSTRAP_FILE_PATH, rawData));
      bootstrapper.bootstrap();
      fail("Expected exception");
    } catch (XdsInitializationException e) {
      assertThat(e).hasMessageThat().contains("with 'call_creds' type unspecified");
    } finally {
      BootstrapperImpl.enableXdsBootstrapCallCreds = false;
    }
  }
}
