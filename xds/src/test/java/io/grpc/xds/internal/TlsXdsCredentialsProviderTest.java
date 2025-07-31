/*
 * Copyright 2022 The gRPC Authors
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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableMap;
import io.grpc.ChannelCredentials;
import io.grpc.InternalServiceProviders;
import io.grpc.TlsChannelCredentials;
import io.grpc.xds.XdsCredentialsProvider;
import java.io.File;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link TlsXdsCredentialsProvider}. */
@RunWith(JUnit4.class)
public class TlsXdsCredentialsProviderTest {
  private TlsXdsCredentialsProvider provider = new TlsXdsCredentialsProvider();

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  @Test
  public void provided() {
    for (XdsCredentialsProvider current
        : InternalServiceProviders.getCandidatesViaServiceLoader(
          XdsCredentialsProvider.class, getClass().getClassLoader())) {
      if (current instanceof TlsXdsCredentialsProvider) {
        return;
      }
    }
    fail("ServiceLoader unable to load TlsXdsCredentialsProvider");
  }

  @Test
  public void isAvailable() {
    assertTrue(provider.isAvailable());
  }

  @Test
  public void channelCredentialsWhenNullConfig() {
    assertSame(TlsChannelCredentials.class,
        provider.newChannelCredentials(null).getClass());
  }

  @Test
  public void channelCredentialsWhenNotExistingTrustFileConfig() {
    Map<String, ?> jsonConfig = ImmutableMap.of(
        "ca_certificate_file", "/tmp/not-exisiting-file.txt");
    assertNull(provider.newChannelCredentials(jsonConfig));
  }

  @Test
  public void channelCredentialsWhenNotExistingCertificateFileConfig() {
    Map<String, ?> jsonConfig = ImmutableMap.of(
        "certificate_file", "/tmp/not-exisiting-file.txt",
        "private_key_file", "/tmp/not-exisiting-file-2.txt");
    assertNull(provider.newChannelCredentials(jsonConfig));
  }

  @Test
  public void channelCredentialsWhenInvalidConfig() throws Exception {
    File certFile = tempFolder.newFile(new String("identity.cert"));
    Map<String, ?> jsonConfig = ImmutableMap.of("certificate_file", certFile.toString());
    assertNull(provider.newChannelCredentials(jsonConfig));
  }

  @Test
  public void channelCredentialsWhenValidConfig() throws Exception {
    File trustFile = tempFolder.newFile(new String("root.cert"));
    File certFile = tempFolder.newFile(new String("identity.cert"));
    File keyFile = tempFolder.newFile(new String("private.key"));

    Map<String, ?> jsonConfig = ImmutableMap.of(
        "ca_certificate_file", trustFile.toString(),
        "certificate_file", certFile.toString(),
        "private_key_file", keyFile.toString());

    ChannelCredentials creds = provider.newChannelCredentials(jsonConfig);
    assertSame(TlsChannelCredentials.class, creds.getClass());
    assertSame(((TlsChannelCredentials) creds).getCustomCertificatesConfig(), jsonConfig);

    trustFile.delete();
    certFile.delete();
    keyFile.delete();
  }
}
