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

package io.grpc.xds.internal.security;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.CA_PEM_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.CLIENT_KEY_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.CLIENT_PEM_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.SERVER_1_KEY_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.SERVER_1_PEM_FILE;
import static io.grpc.xds.internal.security.SecurityProtocolNegotiators.ATTR_SERVER_SSL_CONTEXT_PROVIDER_SUPPLIER;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.internal.FakeClock;
import io.grpc.internal.TestUtils.NoopChannelLogger;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalProtocolNegotiationEvent;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiators;
import io.grpc.netty.ProtocolNegotiationEvent;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.TlsContextManager;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.CommonBootstrapperTestUtils;
import io.grpc.xds.internal.XdsInternalAttributes;
import io.grpc.xds.internal.security.SecurityProtocolNegotiators.ClientSecurityHandler;
import io.grpc.xds.internal.security.SecurityProtocolNegotiators.ClientSecurityProtocolNegotiator;
import io.grpc.xds.internal.security.certprovider.CommonCertProviderTestUtils;
import io.grpc.xds.internal.security.trust.CertificateUtils;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionDecoder;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionEncoder;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.CertStoreException;
import java.util.AbstractMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.X509TrustManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SecurityProtocolNegotiators}. */
@RunWith(JUnit4.class)
public class SecurityProtocolNegotiatorsTest {

  private static final String HOSTNAME = "hostname";
  private static final String SNI_IN_UTC = "sni-in-upstream-tls-context";
  private static final String FAKE_AUTHORITY = "authority";

  private final GrpcHttp2ConnectionHandler grpcHandler =
      FakeGrpcHttp2ConnectionHandler.newHandler();

  private EmbeddedChannel channel = new EmbeddedChannel();
  private ChannelPipeline pipeline = channel.pipeline();
  private ChannelHandlerContext channelHandlerCtx;

  @Test
  public void clientSecurityProtocolNegotiatorNewHandler_noTlsContextAttribute() {
    ChannelHandler mockChannelHandler = mock(ChannelHandler.class);
    ProtocolNegotiator mockProtocolNegotiator = mock(ProtocolNegotiator.class);
    when(mockProtocolNegotiator.newHandler(grpcHandler)).thenReturn(mockChannelHandler);
    ClientSecurityProtocolNegotiator pn
        = new ClientSecurityProtocolNegotiator(mockProtocolNegotiator);
    ChannelHandler newHandler = pn.newHandler(grpcHandler);
    assertThat(newHandler).isNotNull();
    assertThat(newHandler).isSameInstanceAs(mockChannelHandler);
  }

  @Test
  public void clientSecurityProtocolNegotiatorNewHandler_noFallback_expectException() {
    ClientSecurityProtocolNegotiator pn =
        new ClientSecurityProtocolNegotiator(/* fallbackProtocolNegotiator= */ null);
    try {
      pn.newHandler(grpcHandler);
      fail("exception expected!");
    } catch (NullPointerException expected) {
      assertThat(expected)
          .hasMessageThat()
          .contains("No TLS config and no fallbackProtocolNegotiator!");
    }
  }

  @Test
  public void clientSecurityProtocolNegotiatorNewHandler_withTlsContextAttribute() {
    UpstreamTlsContext upstreamTlsContext = CommonTlsContextTestsUtil.buildUpstreamTlsContext(
        CommonTlsContext.newBuilder().build());
    ClientSecurityProtocolNegotiator pn =
        new ClientSecurityProtocolNegotiator(InternalProtocolNegotiators.plaintext());
    GrpcHttp2ConnectionHandler mockHandler = mock(GrpcHttp2ConnectionHandler.class);
    ChannelLogger logger = mock(ChannelLogger.class);
    doNothing().when(logger).log(any(ChannelLogLevel.class), anyString());
    when(mockHandler.getNegotiationLogger()).thenReturn(logger);
    TlsContextManager mockTlsContextManager = mock(TlsContextManager.class);
    when(mockHandler.getEagAttributes())
        .thenReturn(
            Attributes.newBuilder()
                .set(SecurityProtocolNegotiators.ATTR_SSL_CONTEXT_PROVIDER_SUPPLIER,
                    new SslContextProviderSupplier(upstreamTlsContext, mockTlsContextManager))
                .build());
    ChannelHandler newHandler = pn.newHandler(mockHandler);
    assertThat(newHandler).isNotNull();
    assertThat(newHandler).isInstanceOf(ClientSecurityHandler.class);
  }

  @Test
  public void clientSecurityProtocolNegotiator_autoHostSni_hostnamePassedToClientSecurityHandlr() {
    CertificateUtils.isXdsSniEnabled = true;
    try {
      UpstreamTlsContext upstreamTlsContext =
          CommonTlsContextTestsUtil.buildUpstreamTlsContext(
              CommonTlsContext.newBuilder().build(), "", true, false);
      ClientSecurityProtocolNegotiator pn =
          new ClientSecurityProtocolNegotiator(InternalProtocolNegotiators.plaintext());
      GrpcHttp2ConnectionHandler mockHandler = mock(GrpcHttp2ConnectionHandler.class);
      ChannelLogger logger = mock(ChannelLogger.class);
      doNothing().when(logger).log(any(ChannelLogLevel.class), anyString());
      when(mockHandler.getNegotiationLogger()).thenReturn(logger);
      TlsContextManager mockTlsContextManager = mock(TlsContextManager.class);
      when(mockHandler.getEagAttributes())
          .thenReturn(
              Attributes.newBuilder()
                  .set(SecurityProtocolNegotiators.ATTR_SSL_CONTEXT_PROVIDER_SUPPLIER,
                      new SslContextProviderSupplier(upstreamTlsContext, mockTlsContextManager))
                  .set(XdsInternalAttributes.ATTR_ADDRESS_NAME, FAKE_AUTHORITY)
                  .build());
      ChannelHandler newHandler = pn.newHandler(mockHandler);
      assertThat(newHandler).isNotNull();
      assertThat(newHandler).isInstanceOf(ClientSecurityHandler.class);
      assertThat(((ClientSecurityHandler) newHandler).getSni()).isEqualTo(FAKE_AUTHORITY);
    } finally {
      CertificateUtils.isXdsSniEnabled = false;
    }
  }

  @Test
  public void clientSecurityHandler_addLast()
      throws InterruptedException, TimeoutException, ExecutionException {
    FakeClock executor = new FakeClock();
    CommonCertProviderTestUtils.register(executor);
    Bootstrapper.BootstrapInfo bootstrapInfoForClient = CommonBootstrapperTestUtils
        .buildBootstrapInfo("google_cloud_private_spiffe-client", CLIENT_KEY_FILE, CLIENT_PEM_FILE,
            CA_PEM_FILE, null, null, null, null, null);
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil
            .buildUpstreamTlsContext("google_cloud_private_spiffe-client", true);

    SslContextProviderSupplier sslContextProviderSupplier =
        new SslContextProviderSupplier(upstreamTlsContext,
            new TlsContextManagerImpl(bootstrapInfoForClient));
    ClientSecurityHandler clientSecurityHandler =
        new ClientSecurityHandler(grpcHandler, sslContextProviderSupplier, HOSTNAME);
    pipeline.addLast(clientSecurityHandler);
    channelHandlerCtx = pipeline.context(clientSecurityHandler);
    assertNotNull(channelHandlerCtx);

    // kick off protocol negotiation.
    pipeline.fireUserEventTriggered(InternalProtocolNegotiationEvent.getDefault());
    final SettableFuture<Object> future = SettableFuture.create();
    sslContextProviderSupplier
        .updateSslContext(new SslContextProvider.Callback(MoreExecutors.directExecutor()) {
          @Override
          public void updateSslContextAndExtendedX509TrustManager(
              AbstractMap.SimpleImmutableEntry<SslContext, X509TrustManager> sslContextAndTm) {
            future.set(sslContextAndTm);
          }

          @Override
          protected void onException(Throwable throwable) {
            future.set(throwable);
          }
        });
    assertThat(executor.runDueTasks()).isEqualTo(1);
    channel.runPendingTasks();
    Object fromFuture = future.get(2, TimeUnit.SECONDS);
    assertThat(fromFuture).isInstanceOf(AbstractMap.SimpleImmutableEntry.class);
    channel.runPendingTasks();
    channelHandlerCtx = pipeline.context(clientSecurityHandler);
    assertThat(channelHandlerCtx).isNull();

    // pipeline should have SslHandler and ClientTlsHandler
    Iterator<Map.Entry<String, ChannelHandler>> iterator = pipeline.iterator();
    assertThat(iterator.next().getValue()).isInstanceOf(SslHandler.class);
    // ProtocolNegotiators.ClientTlsHandler.class not accessible, get canonical name
    assertThat(iterator.next().getValue().getClass().getCanonicalName())
        .contains("ProtocolNegotiators.ClientTlsHandler");
    CommonCertProviderTestUtils.register0();
  }

  @Test
  public void sniInClientSecurityHandler_autoHostSniIsTrue_usesEndpointHostname() {
    CertificateUtils.isXdsSniEnabled = true;
    try {
      Bootstrapper.BootstrapInfo bootstrapInfoForClient = CommonBootstrapperTestUtils
          .buildBootstrapInfo("google_cloud_private_spiffe-client", CLIENT_KEY_FILE,
              CLIENT_PEM_FILE, CA_PEM_FILE, null, null, null, null, null);
      UpstreamTlsContext upstreamTlsContext =
          CommonTlsContextTestsUtil
              .buildUpstreamTlsContext("google_cloud_private_spiffe-client", true, "", true);
      SslContextProviderSupplier sslContextProviderSupplier =
          new SslContextProviderSupplier(upstreamTlsContext,
              new TlsContextManagerImpl(bootstrapInfoForClient));

      ClientSecurityHandler clientSecurityHandler =
          new ClientSecurityHandler(grpcHandler, sslContextProviderSupplier, HOSTNAME);

      assertThat(clientSecurityHandler.getSni()).isEqualTo(HOSTNAME);
    } finally {
      CertificateUtils.isXdsSniEnabled = false;
    }
  }

  @Test
  public void sniInClientSecurityHandler_autoHostSni_endpointHostnameIsEmpty_usesSniFromUtc() {
    CertificateUtils.isXdsSniEnabled = true;
    try {
      Bootstrapper.BootstrapInfo bootstrapInfoForClient = CommonBootstrapperTestUtils
          .buildBootstrapInfo("google_cloud_private_spiffe-client", CLIENT_KEY_FILE,
              CLIENT_PEM_FILE, CA_PEM_FILE, null, null, null, null, null);
      UpstreamTlsContext upstreamTlsContext = CommonTlsContextTestsUtil.buildUpstreamTlsContext(
          "google_cloud_private_spiffe-client", true, SNI_IN_UTC, true);
      SslContextProviderSupplier sslContextProviderSupplier =
          new SslContextProviderSupplier(upstreamTlsContext,
              new TlsContextManagerImpl(bootstrapInfoForClient));

      ClientSecurityHandler clientSecurityHandler =
          new ClientSecurityHandler(grpcHandler, sslContextProviderSupplier, "");

      assertThat(clientSecurityHandler.getSni()).isEqualTo(SNI_IN_UTC);
    } finally {
      CertificateUtils.isXdsSniEnabled = false;
    }
  }

  @Test
  public void sniInClientSecurityHandler_autoHostSni_endpointHostnameIsNull_usesSniFromUtc() {
    CertificateUtils.isXdsSniEnabled = true;
    try {
      Bootstrapper.BootstrapInfo bootstrapInfoForClient = CommonBootstrapperTestUtils
          .buildBootstrapInfo("google_cloud_private_spiffe-client", CLIENT_KEY_FILE,
              CLIENT_PEM_FILE, CA_PEM_FILE, null, null, null, null, null);
      UpstreamTlsContext upstreamTlsContext = CommonTlsContextTestsUtil.buildUpstreamTlsContext(
          "google_cloud_private_spiffe-client", true, SNI_IN_UTC, true);
      SslContextProviderSupplier sslContextProviderSupplier =
          new SslContextProviderSupplier(upstreamTlsContext,
              new TlsContextManagerImpl(bootstrapInfoForClient));

      ClientSecurityHandler clientSecurityHandler =
          new ClientSecurityHandler(grpcHandler, sslContextProviderSupplier, null);

      assertThat(clientSecurityHandler.getSni()).isEqualTo(SNI_IN_UTC);
    } finally {
      CertificateUtils.isXdsSniEnabled = false;
    }
  }

  @Test
  public void sniInClientSecurityHandler_autoHostSniIsFalse_usesSniFromUpstreamTlsContext() {
    CertificateUtils.isXdsSniEnabled = true;
    try {
      Bootstrapper.BootstrapInfo bootstrapInfoForClient = CommonBootstrapperTestUtils
          .buildBootstrapInfo("google_cloud_private_spiffe-client", CLIENT_KEY_FILE,
              CLIENT_PEM_FILE, CA_PEM_FILE, null, null, null, null, null);
      UpstreamTlsContext upstreamTlsContext = CommonTlsContextTestsUtil.buildUpstreamTlsContext(
          "google_cloud_private_spiffe-client", true, SNI_IN_UTC, false);
      SslContextProviderSupplier sslContextProviderSupplier =
          new SslContextProviderSupplier(upstreamTlsContext,
              new TlsContextManagerImpl(bootstrapInfoForClient));

      ClientSecurityHandler clientSecurityHandler =
          new ClientSecurityHandler(grpcHandler, sslContextProviderSupplier, HOSTNAME);

      assertThat(clientSecurityHandler.getSni()).isEqualTo(SNI_IN_UTC);
    } finally {
      CertificateUtils.isXdsSniEnabled = false;
    }
  }

  @Test
  public void sniFeatureNotEnabled_usesChannelAuthorityForSni() {
    CertificateUtils.isXdsSniEnabled = false;
    Bootstrapper.BootstrapInfo bootstrapInfoForClient = CommonBootstrapperTestUtils
            .buildBootstrapInfo("google_cloud_private_spiffe-client", CLIENT_KEY_FILE,
                    CLIENT_PEM_FILE, CA_PEM_FILE, null, null, null, null, null);
    UpstreamTlsContext upstreamTlsContext =
            CommonTlsContextTestsUtil
                .buildUpstreamTlsContext("google_cloud_private_spiffe-client", true);
    SslContextProviderSupplier sslContextProviderSupplier =
            new SslContextProviderSupplier(upstreamTlsContext,
                    new TlsContextManagerImpl(bootstrapInfoForClient));

    ClientSecurityHandler clientSecurityHandler =
            new ClientSecurityHandler(grpcHandler, sslContextProviderSupplier, HOSTNAME);

    assertThat(clientSecurityHandler.getSni()).isEqualTo(FAKE_AUTHORITY);
  }

  @Test
  public void serverSecurityHandler_addLast()
      throws InterruptedException, TimeoutException, ExecutionException {
    FakeClock executor = new FakeClock();
    CommonCertProviderTestUtils.register(executor);
    // we need InetSocketAddress instead of EmbeddedSocketAddress as localAddress for this test
    channel =
        new EmbeddedChannel() {
          @Override
          public SocketAddress localAddress() {
            return new InetSocketAddress("172.168.1.1", 80);
          }

          @Override
          public SocketAddress remoteAddress() {
            return new InetSocketAddress("172.168.2.2", 90);
          }
        };
    pipeline = channel.pipeline();
    Bootstrapper.BootstrapInfo bootstrapInfoForServer = CommonBootstrapperTestUtils
        .buildBootstrapInfo("google_cloud_private_spiffe-server", SERVER_1_KEY_FILE,
            SERVER_1_PEM_FILE, CA_PEM_FILE, null, null, null, null, null);
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildDownstreamTlsContext(
            "google_cloud_private_spiffe-server", true, true);

    TlsContextManagerImpl tlsContextManager = new TlsContextManagerImpl(bootstrapInfoForServer);
    SecurityProtocolNegotiators.HandlerPickerHandler handlerPickerHandler =
        new SecurityProtocolNegotiators.HandlerPickerHandler(grpcHandler,
                InternalProtocolNegotiators.serverPlaintext());
    pipeline.addLast(handlerPickerHandler);
    channelHandlerCtx = pipeline.context(handlerPickerHandler);
    assertThat(channelHandlerCtx).isNotNull(); // should find HandlerPickerHandler

    // kick off protocol negotiation: should replace HandlerPickerHandler with ServerSecurityHandler
    ProtocolNegotiationEvent event = InternalProtocolNegotiationEvent.getDefault();
    Attributes attr = InternalProtocolNegotiationEvent.getAttributes(event)
            .toBuilder().set(ATTR_SERVER_SSL_CONTEXT_PROVIDER_SUPPLIER,
              new SslContextProviderSupplier(downstreamTlsContext, tlsContextManager)).build();
    pipeline.fireUserEventTriggered(InternalProtocolNegotiationEvent.withAttributes(event, attr));
    channelHandlerCtx = pipeline.context(handlerPickerHandler);
    assertThat(channelHandlerCtx).isNull();
    channelHandlerCtx = pipeline.context(SecurityProtocolNegotiators.ServerSecurityHandler.class);
    assertThat(channelHandlerCtx).isNotNull();

    SslContextProviderSupplier sslContextProviderSupplier =
        new SslContextProviderSupplier(downstreamTlsContext, tlsContextManager);
    final SettableFuture<Object> future = SettableFuture.create();
    sslContextProviderSupplier
        .updateSslContext(new SslContextProvider.Callback(MoreExecutors.directExecutor()) {
          @Override
          public void updateSslContextAndExtendedX509TrustManager(
              AbstractMap.SimpleImmutableEntry<SslContext, X509TrustManager> sslContextAndTm) {
            future.set(sslContextAndTm);
          }

          @Override
          protected void onException(Throwable throwable) {
            future.set(throwable);
          }
        });
    channel.runPendingTasks(); // need this for tasks to execute on eventLoop
    assertThat(executor.runDueTasks()).isEqualTo(1);
    Object fromFuture = future.get(2, TimeUnit.SECONDS);
    assertThat(fromFuture).isInstanceOf(AbstractMap.SimpleImmutableEntry.class);
    channel.runPendingTasks();
    channelHandlerCtx = pipeline.context(SecurityProtocolNegotiators.ServerSecurityHandler.class);
    assertThat(channelHandlerCtx).isNull();

    // pipeline should only have SslHandler and ServerTlsHandler
    Iterator<Map.Entry<String, ChannelHandler>> iterator = pipeline.iterator();
    assertThat(iterator.next().getValue()).isInstanceOf(SslHandler.class);
    // ProtocolNegotiators.ServerTlsHandler.class is not accessible, get canonical name
    assertThat(iterator.next().getValue().getClass().getCanonicalName())
        .contains("ProtocolNegotiators.ServerTlsHandler");
    CommonCertProviderTestUtils.register0();
  }

  @Test
  public void serverSecurityHandler_defaultDownstreamTlsContext_expectFallbackProtocolNegotiator()
      throws IOException {
    ChannelHandler mockChannelHandler = mock(ChannelHandler.class);
    ProtocolNegotiator mockProtocolNegotiator = mock(ProtocolNegotiator.class);
    when(mockProtocolNegotiator.newHandler(grpcHandler)).thenReturn(mockChannelHandler);
    // we need InetSocketAddress instead of EmbeddedSocketAddress as localAddress for this test
    channel =
        new EmbeddedChannel() {
          @Override
          public SocketAddress localAddress() {
            return new InetSocketAddress("172.168.1.1", 80);
          }
        };
    pipeline = channel.pipeline();

    SecurityProtocolNegotiators.HandlerPickerHandler handlerPickerHandler =
        new SecurityProtocolNegotiators.HandlerPickerHandler(
            grpcHandler, mockProtocolNegotiator);
    pipeline.addLast(handlerPickerHandler);
    channelHandlerCtx = pipeline.context(handlerPickerHandler);
    assertThat(channelHandlerCtx).isNotNull(); // should find HandlerPickerHandler

    // kick off protocol negotiation: should replace HandlerPickerHandler with ServerSecurityHandler
    ProtocolNegotiationEvent event = InternalProtocolNegotiationEvent.getDefault();
    Attributes attr = InternalProtocolNegotiationEvent.getAttributes(event)
            .toBuilder().set(ATTR_SERVER_SSL_CONTEXT_PROVIDER_SUPPLIER, null).build();
    pipeline.fireUserEventTriggered(InternalProtocolNegotiationEvent.withAttributes(event, attr));
    channelHandlerCtx = pipeline.context(handlerPickerHandler);
    assertThat(channelHandlerCtx).isNull();
    channel.runPendingTasks(); // need this for tasks to execute on eventLoop
    Iterator<Map.Entry<String, ChannelHandler>> iterator = pipeline.iterator();
    assertThat(iterator.next().getValue()).isSameInstanceAs(mockChannelHandler);
    // no more handlers in the pipeline
    assertThat(iterator.hasNext()).isFalse();
  }

  @Test
  public void serverSecurityHandler_nullTlsContext_expectFallbackProtocolNegotiator() {
    ChannelHandler mockChannelHandler = mock(ChannelHandler.class);
    ProtocolNegotiator mockProtocolNegotiator = mock(ProtocolNegotiator.class);
    when(mockProtocolNegotiator.newHandler(grpcHandler)).thenReturn(mockChannelHandler);
    SecurityProtocolNegotiators.HandlerPickerHandler handlerPickerHandler =
        new SecurityProtocolNegotiators.HandlerPickerHandler(
            grpcHandler, mockProtocolNegotiator);
    pipeline.addLast(handlerPickerHandler);
    channelHandlerCtx = pipeline.context(handlerPickerHandler);
    assertThat(channelHandlerCtx).isNotNull(); // should find HandlerPickerHandler

    // kick off protocol negotiation
    pipeline.fireUserEventTriggered(InternalProtocolNegotiationEvent.getDefault());
    channelHandlerCtx = pipeline.context(handlerPickerHandler);
    assertThat(channelHandlerCtx).isNull();
    channel.runPendingTasks(); // need this for tasks to execute on eventLoop
    Iterator<Map.Entry<String, ChannelHandler>> iterator = pipeline.iterator();
    assertThat(iterator.next().getValue()).isSameInstanceAs(mockChannelHandler);
    // no more handlers in the pipeline
    assertThat(iterator.hasNext()).isFalse();
  }

  @Test
  public void nullTlsContext_nullFallbackProtocolNegotiator_expectException() {
    SecurityProtocolNegotiators.HandlerPickerHandler handlerPickerHandler =
        new SecurityProtocolNegotiators.HandlerPickerHandler(
            grpcHandler, null);
    pipeline.addLast(handlerPickerHandler);
    channelHandlerCtx = pipeline.context(handlerPickerHandler);
    assertThat(channelHandlerCtx).isNotNull(); // should find HandlerPickerHandler

    // kick off protocol negotiation
    pipeline.fireUserEventTriggered(InternalProtocolNegotiationEvent.getDefault());
    channelHandlerCtx = pipeline.context(handlerPickerHandler);
    assertThat(channelHandlerCtx).isNotNull(); // HandlerPickerHandler still there
    try {
      channel.checkException();
      fail("exception expected!");
    } catch (Exception e) {
      assertThat(e).isInstanceOf(CertStoreException.class);
      assertThat(e).hasMessageThat().contains("No certificate source found!");
    }
  }

  @Test
  public void clientSecurityProtocolNegotiatorNewHandler_fireProtocolNegotiationEvent()
          throws InterruptedException, TimeoutException, ExecutionException {
    CertificateUtils.isXdsSniEnabled = true;
    try {
      FakeClock executor = new FakeClock();
      CommonCertProviderTestUtils.register(executor);
      Bootstrapper.BootstrapInfo bootstrapInfoForClient = CommonBootstrapperTestUtils
          .buildBootstrapInfo("google_cloud_private_spiffe-client", CLIENT_KEY_FILE,
              CLIENT_PEM_FILE, CA_PEM_FILE, null, null, null, null, null);
      UpstreamTlsContext upstreamTlsContext =
          CommonTlsContextTestsUtil
              .buildUpstreamTlsContext("google_cloud_private_spiffe-client", true);

      SslContextProviderSupplier sslContextProviderSupplier =
          new SslContextProviderSupplier(upstreamTlsContext,
              new TlsContextManagerImpl(bootstrapInfoForClient));
      ClientSecurityHandler clientSecurityHandler =
          new ClientSecurityHandler(grpcHandler, sslContextProviderSupplier, HOSTNAME);

      pipeline.addLast(clientSecurityHandler);
      channelHandlerCtx = pipeline.context(clientSecurityHandler);
      assertNotNull(channelHandlerCtx); // non-null since we just added it

      // kick off protocol negotiation.
      pipeline.fireUserEventTriggered(InternalProtocolNegotiationEvent.getDefault());
      final SettableFuture<Object> future = SettableFuture.create();
      sslContextProviderSupplier
          .updateSslContext(new SslContextProvider.Callback(MoreExecutors.directExecutor()) {
            @Override
            public void updateSslContextAndExtendedX509TrustManager(
                AbstractMap.SimpleImmutableEntry<SslContext, X509TrustManager> sslContextAndTm) {
              future.set(sslContextAndTm);
            }

            @Override
            protected void onException(Throwable throwable) {
              future.set(throwable);
            }
          });
      executor.runDueTasks();
      channel.runPendingTasks(); // need this for tasks to execute on eventLoop
      Object fromFuture = future.get(5, TimeUnit.SECONDS);
      assertThat(fromFuture).isInstanceOf(AbstractMap.SimpleImmutableEntry.class);
      channel.runPendingTasks();
      channelHandlerCtx = pipeline.context(clientSecurityHandler);
      assertThat(channelHandlerCtx).isNull();
      Object sslEvent = SslHandshakeCompletionEvent.SUCCESS;

      pipeline.fireUserEventTriggered(sslEvent);
      channel.runPendingTasks(); // need this for tasks to execute on eventLoop
      assertTrue(channel.isOpen());
      CommonCertProviderTestUtils.register0();
    } finally {
      CertificateUtils.isXdsSniEnabled = false;
    }
  }

  @Test
  public void clientSecurityProtocolNegotiatorNewHandler_handleHandlerRemoved() {
    FakeClock executor = new FakeClock();
    CommonCertProviderTestUtils.register(executor);
    Bootstrapper.BootstrapInfo bootstrapInfoForClient = CommonBootstrapperTestUtils
        .buildBootstrapInfo("google_cloud_private_spiffe-client", CLIENT_KEY_FILE, CLIENT_PEM_FILE,
            CA_PEM_FILE, null, null, null, null, null);
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil
            .buildUpstreamTlsContext("google_cloud_private_spiffe-client", true);

    SslContextProviderSupplier sslContextProviderSupplier =
        new SslContextProviderSupplier(upstreamTlsContext,
            new TlsContextManagerImpl(bootstrapInfoForClient));
    ClientSecurityHandler clientSecurityHandler =
        new ClientSecurityHandler(grpcHandler, sslContextProviderSupplier, HOSTNAME);

    pipeline.addLast(clientSecurityHandler);
    channelHandlerCtx = pipeline.context(clientSecurityHandler);

    // kick off protocol negotiation.
    pipeline.fireUserEventTriggered(InternalProtocolNegotiationEvent.getDefault());

    executor.runDueTasks();
    pipeline.remove(clientSecurityHandler);
    channel.runPendingTasks();
    channel.checkException();
    CommonCertProviderTestUtils.register0();
  }

  private static final class FakeGrpcHttp2ConnectionHandler extends GrpcHttp2ConnectionHandler {

    FakeGrpcHttp2ConnectionHandler(
        ChannelPromise channelUnused,
        Http2ConnectionDecoder decoder,
        Http2ConnectionEncoder encoder,
        Http2Settings initialSettings) {
      super(channelUnused, decoder, encoder, initialSettings, new NoopChannelLogger());
    }

    static FakeGrpcHttp2ConnectionHandler newHandler() {
      DefaultHttp2Connection conn = new DefaultHttp2Connection(/*server=*/ false);
      DefaultHttp2ConnectionEncoder encoder =
          new DefaultHttp2ConnectionEncoder(conn, new DefaultHttp2FrameWriter());
      DefaultHttp2ConnectionDecoder decoder =
          new DefaultHttp2ConnectionDecoder(conn, encoder, new DefaultHttp2FrameReader());
      Http2Settings settings = new Http2Settings();
      return new FakeGrpcHttp2ConnectionHandler(
          /*channelUnused=*/ null, decoder, encoder, settings);
    }

    @Override
    public String getAuthority() {
      return FAKE_AUTHORITY;
    }
  }
}
