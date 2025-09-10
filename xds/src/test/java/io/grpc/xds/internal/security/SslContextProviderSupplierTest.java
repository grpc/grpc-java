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
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.buildUpstreamTlsContext;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
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

  @Mock private TlsContextManager mockTlsContextManager;
  @Mock private Executor mockExecutor;
  private SslContextProviderSupplier supplier;
  private SslContextProvider mockSslContextProvider;
  private EnvoyServerProtoData.UpstreamTlsContext upstreamTlsContext;
  private SslContextProvider.Callback mockCallback;

  private void prepareSupplier(boolean createUpstreamTlsContext) {
    if (createUpstreamTlsContext) {
      upstreamTlsContext =
          buildUpstreamTlsContext("google_cloud_private_spiffe", true);
    }
    mockSslContextProvider = mock(SslContextProvider.class);
    doReturn(mockSslContextProvider)
            .when(mockTlsContextManager)
            .findOrCreateClientSslContextProvider(eq(upstreamTlsContext));
    supplier = new SslContextProviderSupplier(upstreamTlsContext, mockTlsContextManager);
  }

  private void callUpdateSslContext() {
    mockCallback = mock(SslContextProvider.Callback.class);
    doReturn(mockExecutor).when(mockCallback).getExecutor();
    supplier.updateSslContext(mockCallback);
  }

  @Test
  public void get_updateSecret() {
    prepareSupplier(true);
    callUpdateSslContext();
    verify(mockTlsContextManager, times(2))
        .findOrCreateClientSslContextProvider(eq(upstreamTlsContext));
    verify(mockTlsContextManager, times(0))
        .releaseClientSslContextProvider(any(SslContextProvider.class));
    ArgumentCaptor<SslContextProvider.Callback> callbackCaptor =
        ArgumentCaptor.forClass(SslContextProvider.Callback.class);
    verify(mockSslContextProvider, times(1)).addCallback(callbackCaptor.capture());
    SslContextProvider.Callback capturedCallback = callbackCaptor.getValue();
    assertThat(capturedCallback).isNotNull();
    SslContext mockSslContext = mock(SslContext.class);
    capturedCallback.updateSslContext(mockSslContext);
    verify(mockCallback, times(1)).updateSslContext(eq(mockSslContext));
    verify(mockTlsContextManager, times(1))
        .releaseClientSslContextProvider(eq(mockSslContextProvider));
    SslContextProvider.Callback mockCallback = mock(SslContextProvider.Callback.class);
    supplier.updateSslContext(mockCallback);
    verify(mockTlsContextManager, times(3))
        .findOrCreateClientSslContextProvider(eq(upstreamTlsContext));
  }

  @Test
  public void get_onException() {
    prepareSupplier(true);
    callUpdateSslContext();
    ArgumentCaptor<SslContextProvider.Callback> callbackCaptor =
        ArgumentCaptor.forClass(SslContextProvider.Callback.class);
    verify(mockSslContextProvider, times(1))
            .addCallback(callbackCaptor.capture());
    SslContextProvider.Callback capturedCallback = callbackCaptor.getValue();
    assertThat(capturedCallback).isNotNull();
    Exception exception = new Exception("test");
    capturedCallback.onException(exception);
    verify(mockCallback, times(1)).onException(eq(exception));
    verify(mockTlsContextManager, times(1))
        .releaseClientSslContextProvider(eq(mockSslContextProvider));
  }

  @Test
  public void systemRootCertsWithMtls_callbackExecutedFromProvider() {
    upstreamTlsContext =
        CommonTlsContextTestsUtil.buildNewUpstreamTlsContextForCertProviderInstance(
            "gcp_id",
            "cert-default",
            null,
            "root-default",
            null,
            CertificateValidationContext.newBuilder()
                .setSystemRootCerts(
                    CertificateValidationContext.SystemRootCerts.getDefaultInstance())
                .build());
    prepareSupplier(false);

    callUpdateSslContext();

    verify(mockTlsContextManager, times(2))
        .findOrCreateClientSslContextProvider(eq(upstreamTlsContext));
    verify(mockTlsContextManager, times(0))
        .releaseClientSslContextProvider(any(SslContextProvider.class));
    ArgumentCaptor<SslContextProvider.Callback> callbackCaptor =
        ArgumentCaptor.forClass(SslContextProvider.Callback.class);
    verify(mockSslContextProvider, times(1)).addCallback(callbackCaptor.capture());
    SslContextProvider.Callback capturedCallback = callbackCaptor.getValue();
    assertThat(capturedCallback).isNotNull();
    SslContext mockSslContext = mock(SslContext.class);
    capturedCallback.updateSslContext(mockSslContext);
    verify(mockCallback, times(1)).updateSslContext(eq(mockSslContext));
    verify(mockTlsContextManager, times(1))
        .releaseClientSslContextProvider(eq(mockSslContextProvider));
    SslContextProvider.Callback mockCallback = mock(SslContextProvider.Callback.class);
    supplier.updateSslContext(mockCallback);
    verify(mockTlsContextManager, times(3))
        .findOrCreateClientSslContextProvider(eq(upstreamTlsContext));
  }

  @Test
  public void systemRootCertsWithRegularTls_callbackExecutedFromSupplier() {
    upstreamTlsContext =
        CommonTlsContextTestsUtil.buildNewUpstreamTlsContextForCertProviderInstance(
            null,
            null,
            null,
            "root-default",
            null,
            CertificateValidationContext.newBuilder()
                .setSystemRootCerts(
                        CertificateValidationContext.SystemRootCerts.getDefaultInstance())
                .build());
    supplier = new SslContextProviderSupplier(upstreamTlsContext, mockTlsContextManager);
    reset(mockTlsContextManager);

    callUpdateSslContext();
    ArgumentCaptor<Runnable> runnableArgumentCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(mockExecutor).execute(runnableArgumentCaptor.capture());
    runnableArgumentCaptor.getValue().run();
    verify(mockCallback, times(1)).updateSslContext(any(SslContext.class));
    verify(mockTlsContextManager, times(1))
            .releaseClientSslContextProvider(eq(mockSslContextProvider));
  }

  @Test
  public void testClose() {
    prepareSupplier(true);
    callUpdateSslContext();
    supplier.close();
    verify(mockTlsContextManager, times(1))
        .releaseClientSslContextProvider(eq(mockSslContextProvider));
    supplier.updateSslContext(mockCallback);
    verify(mockTlsContextManager, times(3))
        .findOrCreateClientSslContextProvider(eq(upstreamTlsContext));
    verify(mockTlsContextManager, times(1))
        .releaseClientSslContextProvider(any(SslContextProvider.class));
  }

  @Test
  public void testClose_nullSslContextProvider() {
    prepareSupplier(true);
    doThrow(new NullPointerException()).when(mockTlsContextManager)
        .releaseClientSslContextProvider(null);
    supplier.close();
    verify(mockTlsContextManager, never())
        .releaseClientSslContextProvider(eq(mockSslContextProvider));
    callUpdateSslContext();
    verify(mockTlsContextManager, times(1))
        .findOrCreateClientSslContextProvider(eq(upstreamTlsContext));
  }
}
