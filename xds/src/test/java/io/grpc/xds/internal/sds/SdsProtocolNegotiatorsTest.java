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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Strings;
import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.envoyproxy.envoy.api.v2.core.DataSource;
import io.grpc.internal.testing.TestUtils;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalProtocolNegotiationEvent;
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
import java.util.Iterator;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SdsProtocolNegotiators}. */
@RunWith(JUnit4.class)
public class SdsProtocolNegotiatorsTest {

  private static final String SERVER_1_PEM_FILE = "server1.pem";
  private static final String SERVER_1_KEY_FILE = "server1.key";
  private static final String CLIENT_PEM_FILE = "client.pem";
  private static final String CLIENT_KEY_FILE = "client.key";
  private static final String CA_PEM_FILE = "ca.pem";

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
    return buildUpstreamTlsContext(
        buildCommonTlsContextFromFilenames(privateKey, certChain, trustCa));
  }

  /** Builds UpstreamTlsContext from commonTlsContext. */
  private static UpstreamTlsContext buildUpstreamTlsContext(CommonTlsContext commonTlsContext) {
    UpstreamTlsContext upstreamTlsContext =
        UpstreamTlsContext.newBuilder().setCommonTlsContext(commonTlsContext).build();
    return upstreamTlsContext;
  }

  /** Builds DownstreamTlsContext from commonTlsContext. */
  private static DownstreamTlsContext buildDownstreamTlsContext(CommonTlsContext commonTlsContext) {
    DownstreamTlsContext downstreamTlsContext =
        DownstreamTlsContext.newBuilder().setCommonTlsContext(commonTlsContext).build();
    return downstreamTlsContext;
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
  public void clientSdsProtocolNegotiatorNewHandler_nullTlsContext() {
    ClientSdsProtocolNegotiator pn =
        new ClientSdsProtocolNegotiator(/* upstreamTlsContext= */ null);
    ChannelHandler newHandler = pn.newHandler(grpcHandler);
    assertThat(newHandler).isNotNull();
    // ProtocolNegotiators.WaitUntilActiveHandler not accessible, get canonical name
    assertThat(newHandler.getClass().getCanonicalName())
        .contains("io.grpc.netty.ProtocolNegotiators.WaitUntilActiveHandler");
  }

  @Test
  public void clientSdsProtocolNegotiatorNewHandler_nonNullTlsContext() {
    UpstreamTlsContext upstreamTlsContext =
        buildUpstreamTlsContext(getCommonTlsContext(null, null));
    ClientSdsProtocolNegotiator pn = new ClientSdsProtocolNegotiator(upstreamTlsContext);
    ChannelHandler newHandler = pn.newHandler(grpcHandler);
    assertThat(newHandler).isNotNull();
    assertThat(newHandler).isInstanceOf(ClientSdsHandler.class);
  }

  @Test
  public void clientSdsHandler_addLast() throws IOException {
    UpstreamTlsContext upstreamTlsContext =
        buildUpstreamTlsContextFromFilenames(CLIENT_KEY_FILE, CLIENT_PEM_FILE, CA_PEM_FILE);

    SdsProtocolNegotiators.ClientSdsHandler clientSdsHandler =
        new SdsProtocolNegotiators.ClientSdsHandler(grpcHandler, upstreamTlsContext);
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
    DownstreamTlsContext downstreamTlsContext =
        buildDownstreamTlsContextFromFilenames(SERVER_1_KEY_FILE, SERVER_1_PEM_FILE, CA_PEM_FILE);

    SdsProtocolNegotiators.ServerSdsHandler serverSdsHandler =
        new SdsProtocolNegotiators.ServerSdsHandler(grpcHandler, downstreamTlsContext);
    pipeline.addLast(serverSdsHandler);
    channelHandlerCtx = pipeline.context(serverSdsHandler);
    assertNotNull(channelHandlerCtx); // serverSdsHandler ctx is non-null since we just added it

    // kick off protocol negotiation
    pipeline.fireUserEventTriggered(InternalProtocolNegotiationEvent.getDefault());
    channel.runPendingTasks(); // need this for tasks to execute on eventLoop
    channelHandlerCtx = pipeline.context(serverSdsHandler);
    assertThat(channelHandlerCtx).isNull();

    // pipeline should have SslHandler and ServerTlsHandler
    Iterator<Map.Entry<String, ChannelHandler>> iterator = pipeline.iterator();
    assertThat(iterator.next().getValue()).isInstanceOf(SslHandler.class);
    // ProtocolNegotiators.ServerTlsHandler.class is not accessible, get canonical name
    assertThat(iterator.next().getValue().getClass().getCanonicalName())
        .contains("ProtocolNegotiators.ServerTlsHandler");
  }

  @Test
  public void clientSdsProtocolNegotiatorNewHandler_fireProtocolNegotiationEvent()
      throws IOException, InterruptedException {
    UpstreamTlsContext upstreamTlsContext =
        buildUpstreamTlsContextFromFilenames(CLIENT_KEY_FILE, CLIENT_PEM_FILE, CA_PEM_FILE);

    SdsProtocolNegotiators.ClientSdsHandler clientSdsHandler =
        new SdsProtocolNegotiators.ClientSdsHandler(grpcHandler, upstreamTlsContext);

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
