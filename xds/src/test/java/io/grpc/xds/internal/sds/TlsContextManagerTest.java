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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.grpc.xds.internal.sds.ReferenceCountingSslContextProviderMap.SslContextProviderFactory;
import java.lang.reflect.Field;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link TlsContextManagerImpl}. */
@RunWith(JUnit4.class)
public class TlsContextManagerTest {

  private static final String SERVER_0_PEM_FILE = "server0.pem";
  private static final String SERVER_0_KEY_FILE = "server0.key";
  private static final String SERVER_1_PEM_FILE = "server1.pem";
  private static final String SERVER_1_KEY_FILE = "server1.key";
  private static final String CLIENT_PEM_FILE = "client.pem";
  private static final String CLIENT_KEY_FILE = "client.key";
  private static final String CA_PEM_FILE = "ca.pem";

  @Rule public final MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock
  SslContextProviderFactory<UpstreamTlsContext> mockClientFactory;

  @Mock
  SslContextProviderFactory<DownstreamTlsContext> mockServerFactory;

  @Before
  public void clearInstance() throws NoSuchFieldException, IllegalAccessException {
    Field field = TlsContextManagerImpl.class.getDeclaredField("instance");
    field.setAccessible(true);
    field.set(null, null);
  }

  @Test
  public void createServerSslContextProvider() {
    DownstreamTlsContext downstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildDownstreamTlsContextFromFilenames(
            SERVER_1_KEY_FILE, SERVER_1_PEM_FILE, /* trustCa= */ null);

    TlsContextManagerImpl tlsContextManagerImpl = TlsContextManagerImpl.getInstance();
    SslContextProvider<DownstreamTlsContext> serverSecretProvider =
        tlsContextManagerImpl.findOrCreateServerSslContextProvider(downstreamTlsContext);
    assertThat(serverSecretProvider).isNotNull();

    SslContextProvider<DownstreamTlsContext> serverSecretProvider1 =
        tlsContextManagerImpl.findOrCreateServerSslContextProvider(downstreamTlsContext);
    assertThat(serverSecretProvider1).isSameInstanceAs(serverSecretProvider);
  }

  @Test
  public void createClientSslContextProvider() {
    UpstreamTlsContext upstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildUpstreamTlsContextFromFilenames(
            /* privateKey= */ null, /* certChain= */ null, CA_PEM_FILE);

    TlsContextManagerImpl tlsContextManagerImpl = TlsContextManagerImpl.getInstance();
    SslContextProvider<UpstreamTlsContext> clientSecretProvider =
        tlsContextManagerImpl.findOrCreateClientSslContextProvider(upstreamTlsContext);
    assertThat(clientSecretProvider).isNotNull();

    SslContextProvider<UpstreamTlsContext> clientSecretProvider1 =
        tlsContextManagerImpl.findOrCreateClientSslContextProvider(upstreamTlsContext);
    assertThat(clientSecretProvider1).isSameInstanceAs(clientSecretProvider);
  }

  @Test
  public void createServerSslContextProvider_differentInstance() {
    DownstreamTlsContext downstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildDownstreamTlsContextFromFilenames(
            SERVER_1_KEY_FILE, SERVER_1_PEM_FILE, /* trustCa= */ null);

    TlsContextManagerImpl tlsContextManagerImpl = TlsContextManagerImpl.getInstance();
    SslContextProvider<DownstreamTlsContext> serverSecretProvider =
        tlsContextManagerImpl.findOrCreateServerSslContextProvider(downstreamTlsContext);
    assertThat(serverSecretProvider).isNotNull();

    DownstreamTlsContext downstreamTlsContext1 =
        SecretVolumeSslContextProviderTest.buildDownstreamTlsContextFromFilenames(
            SERVER_0_KEY_FILE, SERVER_0_PEM_FILE, CA_PEM_FILE);
    SslContextProvider<DownstreamTlsContext> serverSecretProvider1 =
        tlsContextManagerImpl.findOrCreateServerSslContextProvider(downstreamTlsContext1);
    assertThat(serverSecretProvider1).isNotNull();
    assertThat(serverSecretProvider1).isNotSameInstanceAs(serverSecretProvider);
  }

  @Test
  public void createClientSslContextProvider_differentInstance() {
    UpstreamTlsContext upstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildUpstreamTlsContextFromFilenames(
            /* privateKey= */ null, /* certChain= */ null, CA_PEM_FILE);

    TlsContextManagerImpl tlsContextManagerImpl = TlsContextManagerImpl.getInstance();
    SslContextProvider<UpstreamTlsContext> clientSecretProvider =
        tlsContextManagerImpl.findOrCreateClientSslContextProvider(upstreamTlsContext);
    assertThat(clientSecretProvider).isNotNull();

    UpstreamTlsContext upstreamTlsContext1 =
        SecretVolumeSslContextProviderTest.buildUpstreamTlsContextFromFilenames(
            CLIENT_KEY_FILE, CLIENT_PEM_FILE, CA_PEM_FILE);

    SslContextProvider<UpstreamTlsContext> clientSecretProvider1 =
        tlsContextManagerImpl.findOrCreateClientSslContextProvider(upstreamTlsContext1);
    assertThat(clientSecretProvider1).isNotSameInstanceAs(clientSecretProvider);
  }

  @Test
  public void createServerSslContextProvider_releaseInstance() {
    DownstreamTlsContext downstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildDownstreamTlsContextFromFilenames(
            SERVER_1_KEY_FILE, SERVER_1_PEM_FILE, /* trustCa= */ null);

    TlsContextManagerImpl tlsContextManagerImpl =
        new TlsContextManagerImpl(mockClientFactory, mockServerFactory);
    @SuppressWarnings("unchecked")
    SslContextProvider<DownstreamTlsContext> mockProvider = mock(SslContextProvider.class);
    when(mockServerFactory.createSslContextProvider(downstreamTlsContext)).thenReturn(mockProvider);
    SslContextProvider<DownstreamTlsContext> serverSecretProvider =
        tlsContextManagerImpl.findOrCreateServerSslContextProvider(downstreamTlsContext);
    assertThat(serverSecretProvider).isSameInstanceAs(mockProvider);
    verify(mockProvider, never()).close();
    when(mockProvider.getSource()).thenReturn(downstreamTlsContext);
    tlsContextManagerImpl.releaseServerSslContextProvider(mockProvider);
    verify(mockProvider, times(1)).close();
  }

  @Test
  public void createClientSslContextProvider_releaseInstance() {
    UpstreamTlsContext upstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildUpstreamTlsContextFromFilenames(
            CLIENT_KEY_FILE, CLIENT_PEM_FILE, CA_PEM_FILE);

    TlsContextManagerImpl tlsContextManagerImpl =
        new TlsContextManagerImpl(mockClientFactory, mockServerFactory);
    @SuppressWarnings("unchecked")
    SslContextProvider<UpstreamTlsContext> mockProvider = mock(SslContextProvider.class);
    when(mockClientFactory.createSslContextProvider(upstreamTlsContext)).thenReturn(mockProvider);
    SslContextProvider<UpstreamTlsContext> clientSecretProvider =
        tlsContextManagerImpl.findOrCreateClientSslContextProvider(upstreamTlsContext);
    assertThat(clientSecretProvider).isSameInstanceAs(mockProvider);
    verify(mockProvider, never()).close();
    when(mockProvider.getSource()).thenReturn(upstreamTlsContext);
    tlsContextManagerImpl.releaseClientSslContextProvider(mockProvider);
    verify(mockProvider, times(1)).close();
  }
}
