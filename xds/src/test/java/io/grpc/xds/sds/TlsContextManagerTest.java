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

import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.netty.handler.ssl.SslContext;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link TlsContextManager}. */
@RunWith(JUnit4.class)
public class TlsContextManagerTest {

  private static final String SERVER_1_PEM_FILE = "server1.pem";
  private static final String SERVER_1_KEY_FILE = "server1.key";
  private static final String CA_PEM_FILE = "ca.pem";

  @Test
  public void createServerSslContextProvider() {
    DownstreamTlsContext downstreamTlsContext =
        SslContextSecretVolumeSecretProviderTest.buildDownstreamTlsContextFromFilenames(
            SERVER_1_KEY_FILE, SERVER_1_PEM_FILE, /* trustCa= */ null);

    SecretProvider<SslContext> serverSecretProvider =
        TlsContextManager.getInstance().findOrCreateServerSslContextProvider(downstreamTlsContext);
    assertThat(serverSecretProvider).isNotNull();
  }

  @Test
  public void createClientSslContextProvider() {
    UpstreamTlsContext upstreamTlsContext =
        SslContextSecretVolumeSecretProviderTest.buildUpstreamTlsContextFromFilenames(
            /* privateKey= */ null, /* certChain= */ null, CA_PEM_FILE);

    SecretProvider<SslContext> serverSecretProvider =
        TlsContextManager.getInstance().findOrCreateClientSslContextProvider(upstreamTlsContext);
    assertThat(serverSecretProvider).isNotNull();
  }
}
