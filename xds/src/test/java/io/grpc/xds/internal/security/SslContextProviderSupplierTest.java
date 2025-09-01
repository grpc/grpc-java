/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.xds.internal.security;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.xds.EnvoyServerProtoData;
import io.grpc.xds.TlsContextManager;
import io.netty.handler.ssl.SslContext;
import java.util.concurrent.Executor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit tests for {@link SslContextProviderSupplier}.
 */
@RunWith(JUnit4.class)
public class SslContextProviderSupplierTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private static final String ENDPOINT_HOSTNAME_FROM_ATTR = "endpoint-hostname-from-attribute";
  private static final String SNI_IN_UTC = "sni-in-upstream-tls-context";

  @Mock private TlsContextManager mockTlsContextManager;
  private SslContextProviderSupplier supplier;
  private SslContextProvider mockSslContextProvider;
  private EnvoyServerProtoData.UpstreamTlsContext upstreamTlsContext;
  private SslContextProvider.Callback mockCallback;

  private void prepareSupplier(boolean autoHostSni, String sniInUTC, String sniSentByClient) {
    upstreamTlsContext = CommonTlsContextTestsUtil.buildUpstreamTlsContext(
            "google_cloud_private_spiffe", true, sniInUTC, autoHostSni);
    mockSslContextProvider = mock(SslContextProvider.class);
    doReturn(mockSslContextProvider)
            .when(mockTlsContextManager)
            .findOrCreateClientSslContextProvider(eq(upstreamTlsContext), eq(sniSentByClient));
    supplier = new SslContextProviderSupplier(upstreamTlsContext, mockTlsContextManager);
  }

  private void callUpdateSslContext(String endpointHostname) {
    mockCallback = mock(SslContextProvider.Callback.class);
    when(mockCallback.getHostname()).thenReturn(endpointHostname);
    Executor mockExecutor = mock(Executor.class);
    doReturn(mockExecutor).when(mockCallback).getExecutor();
    supplier.updateSslContext(mockCallback, null);
  }

  @Test
  public void get_updateSecret() {
    prepareSupplier(false, null, "");
    callUpdateSslContext(ENDPOINT_HOSTNAME_FROM_ATTR);
    verify(mockTlsContextManager, times(2))
        .findOrCreateClientSslContextProvider(eq(upstreamTlsContext), eq(""));
    verify(mockTlsContextManager, times(0))
        .releaseClientSslContextProvider(any(SslContextProvider.class), eq(""));
    ArgumentCaptor<SslContextProvider.Callback> callbackCaptor =
        ArgumentCaptor.forClass(SslContextProvider.Callback.class);
    verify(mockSslContextProvider, times(1)).addCallback(callbackCaptor.capture());
    SslContextProvider.Callback capturedCallback = callbackCaptor.getValue();
    assertThat(capturedCallback).isNotNull();
    SslContext mockSslContext = mock(SslContext.class);
    capturedCallback.updateSslContext(mockSslContext);
    verify(mockCallback, times(1)).updateSslContext(eq(mockSslContext));
    verify(mockTlsContextManager, times(1))
        .releaseClientSslContextProvider(eq(mockSslContextProvider), eq(""));
    SslContextProvider.Callback mockCallback = mock(SslContextProvider.Callback.class);
    supplier.updateSslContext(mockCallback, null);
    verify(mockTlsContextManager, times(3))
        .findOrCreateClientSslContextProvider(eq(upstreamTlsContext), eq(""));
  }

  @Test
  public void autoHostSniFalse_usesSniFromUpstreamTlsContext() {
    prepareSupplier(false, SNI_IN_UTC, SNI_IN_UTC);
    callUpdateSslContext(ENDPOINT_HOSTNAME_FROM_ATTR);
    verify(mockTlsContextManager, times(2))
            .findOrCreateClientSslContextProvider(eq(upstreamTlsContext), eq(SNI_IN_UTC));
    verify(mockTlsContextManager, times(0))
            .releaseClientSslContextProvider(any(SslContextProvider.class), eq(SNI_IN_UTC));
    ArgumentCaptor<SslContextProvider.Callback> callbackCaptor =
            ArgumentCaptor.forClass(SslContextProvider.Callback.class);
    verify(mockSslContextProvider, times(1)).addCallback(callbackCaptor.capture());
    SslContextProvider.Callback capturedCallback = callbackCaptor.getValue();
    assertThat(capturedCallback).isNotNull();
    SslContext mockSslContext = mock(SslContext.class);
    capturedCallback.updateSslContext(mockSslContext);
    verify(mockCallback, times(1)).updateSslContext(eq(mockSslContext));
    verify(mockTlsContextManager, times(1))
            .releaseClientSslContextProvider(eq(mockSslContextProvider), eq(SNI_IN_UTC));
    SslContextProvider.Callback mockCallback = mock(SslContextProvider.Callback.class);
    supplier.updateSslContext(mockCallback, null);
    verify(mockTlsContextManager, times(3))
            .findOrCreateClientSslContextProvider(eq(upstreamTlsContext), eq(SNI_IN_UTC));
  }

  @Test
  public void autoHostSniTrue_usesSniFromEndpointHostname() {
    prepareSupplier(true, SNI_IN_UTC, ENDPOINT_HOSTNAME_FROM_ATTR);
    callUpdateSslContext(ENDPOINT_HOSTNAME_FROM_ATTR);
    verify(mockTlsContextManager, times(2))
            .findOrCreateClientSslContextProvider(eq(upstreamTlsContext), eq(ENDPOINT_HOSTNAME_FROM_ATTR));
    verify(mockTlsContextManager, times(0))
            .releaseClientSslContextProvider(any(SslContextProvider.class), eq(ENDPOINT_HOSTNAME_FROM_ATTR));
    ArgumentCaptor<SslContextProvider.Callback> callbackCaptor =
            ArgumentCaptor.forClass(SslContextProvider.Callback.class);
    verify(mockSslContextProvider, times(1)).addCallback(callbackCaptor.capture());
    SslContextProvider.Callback capturedCallback = callbackCaptor.getValue();
    assertThat(capturedCallback).isNotNull();
    SslContext mockSslContext = mock(SslContext.class);
    capturedCallback.updateSslContext(mockSslContext);
    verify(mockCallback, times(1)).updateSslContext(eq(mockSslContext));
    verify(mockTlsContextManager, times(1))
            .releaseClientSslContextProvider(eq(mockSslContextProvider), eq(ENDPOINT_HOSTNAME_FROM_ATTR));
    SslContextProvider.Callback mockCallback = mock(SslContextProvider.Callback.class);
    when(mockCallback.getHostname()).thenReturn(ENDPOINT_HOSTNAME_FROM_ATTR);
    supplier.updateSslContext(mockCallback, null);
    verify(mockTlsContextManager, times(3))
            .findOrCreateClientSslContextProvider(eq(upstreamTlsContext), eq(ENDPOINT_HOSTNAME_FROM_ATTR));
  }

  @Test
  public void autoHostSniTrue_endpointHostNameIsNull_usesSniFromUpstreamTlsContext() {
    prepareSupplier(true, SNI_IN_UTC, SNI_IN_UTC);
    callUpdateSslContext(null);
    verify(mockTlsContextManager, times(2))
            .findOrCreateClientSslContextProvider(eq(upstreamTlsContext), eq(SNI_IN_UTC));
    verify(mockTlsContextManager, times(0))
            .releaseClientSslContextProvider(any(SslContextProvider.class), eq(SNI_IN_UTC));
    ArgumentCaptor<SslContextProvider.Callback> callbackCaptor =
            ArgumentCaptor.forClass(SslContextProvider.Callback.class);
    verify(mockSslContextProvider, times(1)).addCallback(callbackCaptor.capture());
    SslContextProvider.Callback capturedCallback = callbackCaptor.getValue();
    assertThat(capturedCallback).isNotNull();
    SslContext mockSslContext = mock(SslContext.class);
    capturedCallback.updateSslContext(mockSslContext);
    verify(mockCallback, times(1)).updateSslContext(eq(mockSslContext));
    verify(mockTlsContextManager, times(1))
            .releaseClientSslContextProvider(eq(mockSslContextProvider), eq(SNI_IN_UTC));
    SslContextProvider.Callback mockCallback = mock(SslContextProvider.Callback.class);
    when(mockCallback.getHostname()).thenReturn(null);
    supplier.updateSslContext(mockCallback, null);
    verify(mockTlsContextManager, times(3))
            .findOrCreateClientSslContextProvider(eq(upstreamTlsContext), eq(SNI_IN_UTC));
  }

  @Test
  public void autoHostSniTrue_endpointHostNameIsEmpty_usesSniFromUpstreamTlsContext() {
    prepareSupplier(true, SNI_IN_UTC, SNI_IN_UTC);
    callUpdateSslContext("");
    verify(mockTlsContextManager, times(2))
            .findOrCreateClientSslContextProvider(eq(upstreamTlsContext), eq(SNI_IN_UTC));
    verify(mockTlsContextManager, times(0))
            .releaseClientSslContextProvider(any(SslContextProvider.class), eq(SNI_IN_UTC));
    ArgumentCaptor<SslContextProvider.Callback> callbackCaptor =
            ArgumentCaptor.forClass(SslContextProvider.Callback.class);
    verify(mockSslContextProvider, times(1)).addCallback(callbackCaptor.capture());
    SslContextProvider.Callback capturedCallback = callbackCaptor.getValue();
    assertThat(capturedCallback).isNotNull();
    SslContext mockSslContext = mock(SslContext.class);
    capturedCallback.updateSslContext(mockSslContext);
    verify(mockCallback, times(1)).updateSslContext(eq(mockSslContext));
    verify(mockTlsContextManager, times(1))
            .releaseClientSslContextProvider(eq(mockSslContextProvider), eq(SNI_IN_UTC));
    SslContextProvider.Callback mockCallback = mock(SslContextProvider.Callback.class);
    when(mockCallback.getHostname()).thenReturn("");
    supplier.updateSslContext(mockCallback, null);
    verify(mockTlsContextManager, times(3))
            .findOrCreateClientSslContextProvider(eq(upstreamTlsContext), eq(SNI_IN_UTC));
  }

  @Test
  public void get_onException() {
    prepareSupplier(false, null, "");
    callUpdateSslContext(ENDPOINT_HOSTNAME_FROM_ATTR);
    ArgumentCaptor<SslContextProvider.Callback> callbackCaptor =
        ArgumentCaptor.forClass(SslContextProvider.Callback.class);
    verify(mockSslContextProvider, times(1)).addCallback(callbackCaptor.capture());
    SslContextProvider.Callback capturedCallback = callbackCaptor.getValue();
    assertThat(capturedCallback).isNotNull();
    Exception exception = new Exception("test");
    capturedCallback.onException(exception);
    verify(mockCallback, times(1)).onException(eq(exception));
    verify(mockTlsContextManager, times(1))
        .releaseClientSslContextProvider(eq(mockSslContextProvider), eq(""));
  }

  @Test
  public void testClose() {
    prepareSupplier(false, null, "");
    callUpdateSslContext(ENDPOINT_HOSTNAME_FROM_ATTR);
    supplier.close();
    verify(mockTlsContextManager, times(1))
        .releaseClientSslContextProvider(eq(mockSslContextProvider), eq(""));
    supplier.updateSslContext(mockCallback, null);
    verify(mockTlsContextManager, times(3))
        .findOrCreateClientSslContextProvider(eq(upstreamTlsContext), eq(""));
    verify(mockTlsContextManager, times(1))
        .releaseClientSslContextProvider(any(SslContextProvider.class), eq(""));
  }

  @Test
  public void testClose_nullSslContextProvider() {
    prepareSupplier(false, null, "");
    doThrow(new NullPointerException()).when(mockTlsContextManager)
        .releaseClientSslContextProvider(null, "");
    supplier.close();
    verify(mockTlsContextManager, never())
        .releaseClientSslContextProvider(eq(mockSslContextProvider), eq(""));
    callUpdateSslContext(ENDPOINT_HOSTNAME_FROM_ATTR);
    verify(mockTlsContextManager, times(1))
        .findOrCreateClientSslContextProvider(eq(upstreamTlsContext), eq(""));
  }
}
