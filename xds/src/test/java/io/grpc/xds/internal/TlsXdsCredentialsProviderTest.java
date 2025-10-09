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

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.CA_PEM_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.CLIENT_KEY_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.CLIENT_PEM_FILE;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import io.grpc.ChannelCredentials;
import io.grpc.InternalServiceProviders;
import io.grpc.TlsChannelCredentials;
import io.grpc.internal.testing.TestUtils;
import io.grpc.util.AdvancedTlsX509KeyManager;
import io.grpc.util.AdvancedTlsX509TrustManager;
import io.grpc.xds.ResourceAllocatingChannelCredentials;
import io.grpc.xds.XdsCredentialsProvider;
import java.io.Closeable;
import java.util.Map;
import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link TlsXdsCredentialsProvider}. */
@RunWith(JUnit4.class)
public class TlsXdsCredentialsProviderTest {
  private TlsXdsCredentialsProvider provider = new TlsXdsCredentialsProvider();

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
  public void channelCredentialsWhenInvalidRefreshInterval() {
    Map<String, ?> jsonConfig = ImmutableMap.of(
        "refresh_interval", "invalid-duration-format");
    assertNull(provider.newChannelCredentials(jsonConfig));
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
    String certChainPath = TestUtils.loadCert(CLIENT_PEM_FILE).getAbsolutePath();
    Map<String, ?> jsonConfig = ImmutableMap.of("certificate_file", certChainPath.toString());
    assertNull(provider.newChannelCredentials(jsonConfig));
  }

  @Test
  public void channelCredentialsWhenValidConfig() throws Exception {
    String rootCertPath = TestUtils.loadCert(CA_PEM_FILE).getAbsolutePath();
    String certChainPath = TestUtils.loadCert(CLIENT_PEM_FILE).getAbsolutePath();
    String privateKeyPath = TestUtils.loadCert(CLIENT_KEY_FILE).getAbsolutePath();

    Map<String, ?> jsonConfig = ImmutableMap.of(
        "ca_certificate_file", rootCertPath,
        "certificate_file", certChainPath,
        "private_key_file", privateKeyPath,
        "refresh_interval", "440s");

    ChannelCredentials creds = provider.newChannelCredentials(jsonConfig);
    assertSame(ResourceAllocatingChannelCredentials.class, creds.getClass());
    ResourceAllocatingChannelCredentials resourceAllocatingChannelCredentials =
        (ResourceAllocatingChannelCredentials) creds;
    TlsChannelCredentials tlsChannelCredentials =
        (TlsChannelCredentials) resourceAllocatingChannelCredentials.getChannelCredentials();
    assertThat(tlsChannelCredentials.getKeyManagers()).hasSize(1);
    KeyManager keyManager = Iterables.getOnlyElement(tlsChannelCredentials.getKeyManagers());
    assertThat(keyManager).isInstanceOf(AdvancedTlsX509KeyManager.class);
    assertThat(tlsChannelCredentials.getTrustManagers()).hasSize(1);
    TrustManager trustManager = Iterables.getOnlyElement(tlsChannelCredentials.getTrustManagers());
    assertThat(trustManager).isInstanceOf(AdvancedTlsX509TrustManager.class);
    assertThat(resourceAllocatingChannelCredentials.getAllocatedResources()).hasSize(3);
    for (Closeable resource : resourceAllocatingChannelCredentials.getAllocatedResources()) {
      resource.close();
    }
  }
}
