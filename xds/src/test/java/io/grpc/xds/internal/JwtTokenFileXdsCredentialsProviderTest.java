/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableMap;
import io.grpc.InternalServiceProviders;
import io.grpc.xds.XdsCredentialsProvider;
import java.io.File;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;


/** Unit tests for {@link JwtTokenFileXdsCredentialsProvider}. */
@RunWith(JUnit4.class)
public class JwtTokenFileXdsCredentialsProviderTest {
  private JwtTokenFileXdsCredentialsProvider provider = new JwtTokenFileXdsCredentialsProvider();

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  @Test
  public void provided() {
    for (XdsCredentialsProvider current
        : InternalServiceProviders.getCandidatesViaServiceLoader(
          XdsCredentialsProvider.class, getClass().getClassLoader())) {
      if (current instanceof JwtTokenFileXdsCredentialsProvider) {
        return;
      }
    }
    fail("ServiceLoader unable to load JwtTokenFileXdsCredentialsProvider");
  }

  @Test
  public void isAvailable() {
    assertTrue(provider.isAvailable());
  }

  @Test
  public void channelCredentials() {
    assertNull(provider.newChannelCredentials(null));
  }

  @Test
  public void callCredentialsWhenNullConfig() {
    assertNull(provider.newCallCredentials(null));
  }

  @Test
  public void callCredentialsWhenWrongConfig() {
    Map<String, ?> jsonConfig = ImmutableMap.of("jwt_token_file", "/tmp/not-exisiting-file.txt");
    assertNull(provider.newCallCredentials(jsonConfig));
  }

  @Test
  public void callCredentialsWhenExpectedConfig() throws Exception {
    File createdFile = tempFolder.newFile(new String("existing-file.txt"));
    Map<String, ?> jsonConfig = ImmutableMap.of("jwt_token_file", createdFile.toString());
    assertEquals("io.grpc.auth.GoogleAuthLibraryCallCredentials",
        provider.newCallCredentials(jsonConfig).getClass().getName());
    createdFile.delete();
  }
}
