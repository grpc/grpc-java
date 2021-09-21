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
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CA_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CLIENT_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CLIENT_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_1_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_1_PEM_FILE;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.base.Strings;
import io.envoyproxy.envoy.config.core.v3.DataSource;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate;
import io.grpc.Attributes;
import io.grpc.internal.testing.TestUtils;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalProtocolNegotiationEvent;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiators;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.InternalXdsAttributes;
import io.grpc.xds.XdsClientWrapperForServerSds;
import io.grpc.xds.XdsClientWrapperForServerSdsTest;
import io.grpc.xds.internal.sds.SdsProtocolNegotiators.ClientSdsHandler;
import io.grpc.xds.internal.sds.SdsProtocolNegotiators.ClientSdsProtocolNegotiator;
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
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.CertStoreException;
import java.util.Iterator;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SdsProtocolNegotiators}. */
@RunWith(JUnit4.class)
public class SdsProtocolNegotiatorsTest {

  private final GrpcHttp2ConnectionHandler grpcHandler =
      FakeGrpcHttp2ConnectionHandler.newHandler();

  private EmbeddedChannel channel = new EmbeddedChannel();
  private ChannelPipeline pipeline = channel.pipeline();
  private ChannelHandlerContext channelHandlerCtx;

  private static String getTempFileNameForResourcesFile(String resFile) throws IOException {
    return Strings.isNullOrEmpty(resFile) ? null : TestUtils.loadCert(resFile).getAbsolutePath();
  }

  /** Builds DownstreamTlsContext from file-names. */
  private static DownstreamTlsContext buildDownstreamTlsContextFromFilenames(
      String privateKey, String certChain, String trustCa) throws IOException {
    return buildDownstreamTlsContext(
        buildCommonTlsContextFromFilenames(privateKey, certChain, trustCa));
  }

  /** Builds UpstreamTlsContext from file-names. */
  private static UpstreamTlsContext buildUpstreamTlsContextFromFilenames(
      String privateKey, String certChain, String trustCa) throws IOException {
    return CommonTlsContextTestsUtil.buildUpstreamTlsContext(
        buildCommonTlsContextFromFilenames(privateKey, certChain, trustCa));
  }

  /** Builds DownstreamTlsContext from commonTlsContext. */
  private static DownstreamTlsContext buildDownstreamTlsContext(CommonTlsContext commonTlsContext) {
    io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext
        downstreamTlsContext =
            io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext
                .newBuilder()
                .setCommonTlsContext(commonTlsContext)
                .build();
    return DownstreamTlsContext.fromEnvoyProtoDownstreamTlsContext(downstreamTlsContext);
  }

  private static CommonTlsContext buildCommonTlsContextFromFilenames(
      String privateKey, String certChain, String trustCa) throws IOException {
    TlsCertificate tlsCert = null;
    privateKey = getTempFileNameForResourcesFile(privateKey);
    certChain = getTempFileNameForResourcesFile(certChain);
    trustCa = getTempFileNameForResourcesFile(trustCa);
    if (!Strings.isNullOrEmpty(privateKey) && !Strings.isNullOrEmpty(certChain)) {
      tlsCert =
          TlsCertificate.newBuilder()
              .setCertificateChain(DataSource.newBuilder().setFilename(certChain))
              .setPrivateKey(DataSource.newBuilder().setFilename(privateKey))
              .build();
    }
    CertificateValidationContext certContext = null;
    if (!Strings.isNullOrEmpty(trustCa)) {
      certContext =
          CertificateValidationContext.newBuilder()
              .setTrustedCa(DataSource.newBuilder().setFilename(trustCa))
              .build();
    }
    return getCommonTlsContext(tlsCert, certContext);
  }

  private static CommonTlsContext getCommonTlsContext(
      TlsCertificate tlsCertificate, CertificateValidationContext certContext) {
    CommonTlsContext.Builder builder = CommonTlsContext.newBuilder();
    if (tlsCertificate != null) {
      builder = builder.addTlsCertificates(tlsCertificate);
    }
    if (certContext != null) {
      builder = builder.setValidationContext(certContext);
    }
    return builder.build();
  }

  @Test
  public void clientSdsProtocolNegotiatorNewHandler_noTlsContextAttribute() {
    ChannelHandler mockChannelHandler = mock(ChannelHandler.class);
    ProtocolNegotiator mockProtocolNegotiator = mock(ProtocolNegotiator.class);
    when(mockProtocolNegotiator.newHandler(grpcHandler)).thenReturn(mockChannelHandler);
    ClientSdsProtocolNegotiator pn = new ClientSdsProtocolNegotiator(mockProtocolNegotiator);
    ChannelHandler newHandler = pn.newHandler(grpcHandler);
    assertThat(newHandler).isNotNull();
    assertThat(newHandler).isSameInstanceAs(mockChannelHandler);
  }

  @Test
  public void clientSdsProtocolNegotiatorNewHandler_noFallback_expectException() {
    ClientSdsProtocolNegotiator pn =
        new ClientSdsProtocolNegotiator(/* fallbackProtocolNegotiator= */ null);
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
  public void clientSdsProtocolNegotiatorNewHandler_withTlsContextAttribute() {
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContext(
            getCommonTlsContext(/* tlsCertificate= */ null, /* certContext= */ null));
    ClientSdsProtocolNegotiator pn =
        new ClientSdsProtocolNegotiator(InternalProtocolNegotiators.plaintext());
    GrpcHttp2ConnectionHandler mockHandler = mock(GrpcHttp2ConnectionHandler.class);
    TlsContextManager mockTlsContextManager = mock(TlsContextManager.class);
    when(mockHandler.getEagAttributes())
        .thenReturn(
            Attributes.newBuilder()
                .set(InternalXdsAttributes.ATTR_SSL_CONTEXT_PROVIDER_SUPPLIER,
                    new SslContextProviderSupplier(upstreamTlsContext, mockTlsContextManager))
                .build());
    ChannelHandler newHandler = pn.newHandler(mockHandler);
    assertThat(newHandler).isNotNull();
    assertThat(newHandler).isInstanceOf(ClientSdsHandler.class);
  }

  @Test
  public void clientSdsHandler_addLast() throws IOException {
    UpstreamTlsContext upstreamTlsContext =
        buildUpstreamTlsContextFromFilenames(CLIENT_KEY_FILE, CLIENT_PEM_FILE, CA_PEM_FILE);

    SslContextProviderSupplier sslContextProviderSupplier =
        new SslContextProviderSupplier(upstreamTlsContext, TlsContextManagerImpl.getInstance());
    SdsProtocolNegotiators.ClientSdsHandler clientSdsHandler =
        new SdsProtocolNegotiators.ClientSdsHandler(grpcHandler, sslContextProviderSupplier);
    pipeline.addLast(clientSdsHandler);
    channelHandlerCtx = pipeline.context(clientSdsHandler);
    assertNotNull(channelHandlerCtx); // clientSdsHandler ctx is non-null since we just added it

    // kick off protocol negotiation.
    pipeline.fireUserEventTriggered(InternalProtocolNegotiationEvent.getDefault());
    channel.runPendingTasks(); // need this for tasks to execute on eventLoop
    channelHandlerCtx = pipeline.context(clientSdsHandler);
    assertThat(channelHandlerCtx).isNull();

    // pipeline should have SslHandler and ClientTlsHandler
    Iterator<Map.Entry<String, ChannelHandler>> iterator = pipeline.iterator();
    assertThat(iterator.next().getValue()).isInstanceOf(SslHandler.class);
    // ProtocolNegotiators.ClientTlsHandler.class not accessible, get canonical name
    assertThat(iterator.next().getValue().getClass().getCanonicalName())
        .contains("ProtocolNegotiators.ClientTlsHandler");
  }

  @Test
  public void serverSdsHandler_addLast() throws IOException {
    // we need InetSocketAddress instead of EmbeddedSocketAddress as localAddress for this test
    channel =
        new EmbeddedChannel() {
          @Override
          public SocketAddress localAddress() {
            return new InetSocketAddress("172.168.1.1", 80);
          }
        };
    pipeline = channel.pipeline();
    DownstreamTlsContext downstreamTlsContext =
        buildDownstreamTlsContextFromFilenames(SERVER_1_KEY_FILE, SERVER_1_PEM_FILE, CA_PEM_FILE);

    XdsClientWrapperForServerSds xdsClientWrapperForServerSds =
        XdsClientWrapperForServerSdsTest.createXdsClientWrapperForServerSds(
            80, downstreamTlsContext);
    SdsProtocolNegotiators.HandlerPickerHandler handlerPickerHandler =
        new SdsProtocolNegotiators.HandlerPickerHandler(grpcHandler, xdsClientWrapperForServerSds,
            InternalProtocolNegotiators.serverPlaintext());
    pipeline.addLast(handlerPickerHandler);
    channelHandlerCtx = pipeline.context(handlerPickerHandler);
    assertThat(channelHandlerCtx).isNotNull(); // should find HandlerPickerHandler

    // kick off protocol negotiation: should replace HandlerPickerHandler with ServerSdsHandler
    pipeline.fireUserEventTriggered(InternalProtocolNegotiationEvent.getDefault());
    channelHandlerCtx = pipeline.context(handlerPickerHandler);
    assertThat(channelHandlerCtx).isNull();
    channelHandlerCtx = pipeline.context(SdsProtocolNegotiators.ServerSdsHandler.class);
    assertThat(channelHandlerCtx).isNotNull();
    channel.runPendingTasks(); // need this for tasks to execute on eventLoop
    channelHandlerCtx = pipeline.context(SdsProtocolNegotiators.ServerSdsHandler.class);
    assertThat(channelHandlerCtx).isNull();

    // pipeline should only have SslHandler and ServerTlsHandler
    Iterator<Map.Entry<String, ChannelHandler>> iterator = pipeline.iterator();
    assertThat(iterator.next().getValue()).isInstanceOf(SslHandler.class);
    // ProtocolNegotiators.ServerTlsHandler.class is not accessible, get canonical name
    assertThat(iterator.next().getValue().getClass().getCanonicalName())
        .contains("ProtocolNegotiators.ServerTlsHandler");
  }

  @Test
  public void serverSdsHandler_defaultDownstreamTlsContext_expectFallbackProtocolNegotiator()
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
    DownstreamTlsContext downstreamTlsContext =
        DownstreamTlsContext.fromEnvoyProtoDownstreamTlsContext(
            io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext
                .getDefaultInstance());

    XdsClientWrapperForServerSds xdsClientWrapperForServerSds =
        XdsClientWrapperForServerSdsTest.createXdsClientWrapperForServerSds(
            80, downstreamTlsContext);
    SdsProtocolNegotiators.HandlerPickerHandler handlerPickerHandler =
        new SdsProtocolNegotiators.HandlerPickerHandler(
            grpcHandler, xdsClientWrapperForServerSds, mockProtocolNegotiator);
    pipeline.addLast(handlerPickerHandler);
    channelHandlerCtx = pipeline.context(handlerPickerHandler);
    assertThat(channelHandlerCtx).isNotNull(); // should find HandlerPickerHandler

    // kick off protocol negotiation: should replace HandlerPickerHandler with ServerSdsHandler
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
  public void serverSdsHandler_nullTlsContext_expectFallbackProtocolNegotiator() {
    ChannelHandler mockChannelHandler = mock(ChannelHandler.class);
    ProtocolNegotiator mockProtocolNegotiator = mock(ProtocolNegotiator.class);
    when(mockProtocolNegotiator.newHandler(grpcHandler)).thenReturn(mockChannelHandler);
    SdsProtocolNegotiators.HandlerPickerHandler handlerPickerHandler =
        new SdsProtocolNegotiators.HandlerPickerHandler(
            grpcHandler, /* xdsClientWrapperForServerSds= */ null,
            mockProtocolNegotiator);
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
    SdsProtocolNegotiators.HandlerPickerHandler handlerPickerHandler =
        new SdsProtocolNegotiators.HandlerPickerHandler(
            grpcHandler, /* xdsClientWrapperForServerSds= */ null,
            null);
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
  public void clientSdsProtocolNegotiatorNewHandler_fireProtocolNegotiationEvent()
      throws IOException, InterruptedException {
    UpstreamTlsContext upstreamTlsContext =
        buildUpstreamTlsContextFromFilenames(CLIENT_KEY_FILE, CLIENT_PEM_FILE, CA_PEM_FILE);

    SslContextProviderSupplier sslContextProviderSupplier =
        new SslContextProviderSupplier(upstreamTlsContext, TlsContextManagerImpl.getInstance());
    SdsProtocolNegotiators.ClientSdsHandler clientSdsHandler =
        new SdsProtocolNegotiators.ClientSdsHandler(grpcHandler, sslContextProviderSupplier);

    pipeline.addLast(clientSdsHandler);
    channelHandlerCtx = pipeline.context(clientSdsHandler);
    assertNotNull(channelHandlerCtx); // non-null since we just added it

    // kick off protocol negotiation.
    pipeline.fireUserEventTriggered(InternalProtocolNegotiationEvent.getDefault());
    channel.runPendingTasks(); // need this for tasks to execute on eventLoop
    channelHandlerCtx = pipeline.context(clientSdsHandler);
    assertThat(channelHandlerCtx).isNull();
    Object sslEvent = SslHandshakeCompletionEvent.SUCCESS;

    pipeline.fireUserEventTriggered(sslEvent);
    channel.runPendingTasks(); // need this for tasks to execute on eventLoop
    assertTrue(channel.isOpen());
  }

  private static final class FakeGrpcHttp2ConnectionHandler extends GrpcHttp2ConnectionHandler {

    FakeGrpcHttp2ConnectionHandler(
        ChannelPromise channelUnused,
        Http2ConnectionDecoder decoder,
        Http2ConnectionEncoder encoder,
        Http2Settings initialSettings) {
      super(channelUnused, decoder, encoder, initialSettings);
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
      return "authority";
    }
  }
}
