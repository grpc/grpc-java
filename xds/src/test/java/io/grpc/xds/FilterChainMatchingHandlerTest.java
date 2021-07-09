/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CA_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_1_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_1_PEM_FILE;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.internal.TestUtils.NoopChannelLogger;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalProtocolNegotiationEvent;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiators;
import io.grpc.netty.ProtocolNegotiationEvent;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.EnvoyServerProtoData.FilterChain;
import io.grpc.xds.Filter.NamedFilterConfig;
import io.grpc.xds.FilterChainMatchingHandler.FilterChainSelector;
import io.grpc.xds.XdsServerWrapper.ServerRoutingConfig;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.sds.SdsProtocolNegotiators;
import io.grpc.xds.internal.sds.SslContextProvider;
import io.grpc.xds.internal.sds.SslContextProviderSupplier;
import io.grpc.xds.internal.sds.TlsContextManagerImpl;
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
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.CertStoreException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public class FilterChainMatchingHandlerTest {
  private final GrpcHttp2ConnectionHandler grpcHandler =
          FakeGrpcHttp2ConnectionHandler.newHandler();
  @Mock private TlsContextManager tlsContextManager;
  private static final HttpConnectionManager HTTP_CONNECTION_MANAGER =
          HttpConnectionManager.forRdsName(
                  10L, "route-config", Collections.<NamedFilterConfig>emptyList());
  private ProtocolNegotiationEvent event = InternalProtocolNegotiationEvent.getDefault();
  private ChannelPipeline pipeline;
  private EmbeddedChannel channel;
  private ChannelHandlerContext channelHandlerCtx;

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);
  }

  @Test
  public void singleFilterChainWithoutAlpn() {
    EnvoyServerProtoData.FilterChainMatch filterChainMatch =
            new EnvoyServerProtoData.FilterChainMatch(
                    0,
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    Arrays.<String>asList(),
                    Arrays.<EnvoyServerProtoData.CidrRange>asList(),
                    EnvoyServerProtoData.ConnectionSourceType.ANY,
                    Arrays.<Integer>asList(),
                    Arrays.<String>asList(),
                    null);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChain filterChain = new EnvoyServerProtoData.FilterChain(
            "filter-chain-foo", filterChainMatch, HTTP_CONNECTION_MANAGER, tlsContext,
            tlsContextManager);
    ServerRoutingConfig noopConfig = ServerRoutingConfig.create(
            new ArrayList<NamedFilterConfig>(), new ArrayList<VirtualHost>());
    FilterChainSelector selector = new FilterChainSelector(ImmutableMap.of(filterChain, noopConfig),
            null, null);
    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, null);
    setupChannel("10.0.0.0", "10.0.0.1", 0, filterChainMatchingHandler);
    pipeline.fireUserEventTriggered(event);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void serverSdsHandler_defaultDownstreamTlsContext() {
    ChannelHandler mockChannelHandler = mock(ChannelHandler.class);
    ProtocolNegotiator mockProtocolNegotiator = mock(ProtocolNegotiator.class);
    when(mockProtocolNegotiator.newHandler(grpcHandler)).thenReturn(mockChannelHandler);

    EnvoyServerProtoData.FilterChain f0 = createFilterChain("filter-chain-0", createRds("r0"));
    ServerRoutingConfig noopConfig = ServerRoutingConfig.create(
            Collections.EMPTY_LIST, Collections.EMPTY_LIST);
    FilterChainSelector selector = new FilterChainSelector(
            ImmutableMap.of(f0, noopConfig), null, null);
    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector,
                    InternalProtocolNegotiators.serverPlaintext());
    setupChannel("172.168.1.1", "172.168.1.2", 80, filterChainMatchingHandler);
    ChannelHandlerContext channelHandlerCtx = pipeline.context(filterChainMatchingHandler);
    assertThat(channelHandlerCtx).isNotNull();

    pipeline.fireUserEventTriggered(InternalProtocolNegotiationEvent.getDefault());
    channelHandlerCtx = pipeline.context(filterChainMatchingHandler);
    assertThat(channelHandlerCtx).isNull();
    channel.runPendingTasks();
    channelHandlerCtx = pipeline.context(SdsProtocolNegotiators.ServerSdsHandler.class);
    assertThat(channelHandlerCtx).isNotNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void serverSdsHandler_nullTlsContext_expectFallbackProtocolNegotiator() {
    ChannelHandler mockChannelHandler = mock(ChannelHandler.class);
    ProtocolNegotiator mockProtocolNegotiator = mock(ProtocolNegotiator.class);
    when(mockProtocolNegotiator.newHandler(grpcHandler)).thenReturn(mockChannelHandler);
    ServerRoutingConfig noopConfig =
            ServerRoutingConfig.create(Collections.EMPTY_LIST, Collections.EMPTY_LIST);
    FilterChainSelector selector =
            new FilterChainSelector(Collections.EMPTY_MAP, null, noopConfig);
    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector,
                    mockProtocolNegotiator);
    setupChannel("172.168.1.1", "172.168.2.2", 90, filterChainMatchingHandler);
    channelHandlerCtx = pipeline.context(filterChainMatchingHandler);
    assertThat(channelHandlerCtx).isNotNull();

    pipeline.fireUserEventTriggered(InternalProtocolNegotiationEvent.getDefault());
    channelHandlerCtx = pipeline.context(filterChainMatchingHandler);
    assertThat(channelHandlerCtx).isNull();
    channel.runPendingTasks();
    Iterator<Map.Entry<String, ChannelHandler>> iterator = pipeline.iterator();
    assertThat(iterator.next().getValue()).isSameInstanceAs(mockChannelHandler);
    assertThat(iterator.hasNext()).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void serverSdsHandler_addLast()
          throws InterruptedException, TimeoutException, ExecutionException {
    Bootstrapper.BootstrapInfo bootstrapInfoForServer = CommonBootstrapperTestUtils
            .buildBootstrapInfo("google_cloud_private_spiffe-server", SERVER_1_KEY_FILE,
                    SERVER_1_PEM_FILE, CA_PEM_FILE, null, null, null, null);
    DownstreamTlsContext downstreamTlsContext =
            CommonTlsContextTestsUtil.buildDownstreamTlsContext(
                    "google_cloud_private_spiffe-server", true, true);

    TlsContextManagerImpl tlsContextManager = new TlsContextManagerImpl(bootstrapInfoForServer);
    SslContextProviderSupplier sslContextProviderSupplier =
            new SslContextProviderSupplier(downstreamTlsContext, tlsContextManager);
    ServerRoutingConfig noopConfig =
            ServerRoutingConfig.create(Collections.EMPTY_LIST, Collections.EMPTY_LIST);
    FilterChainSelector selector = new FilterChainSelector(
            Collections.EMPTY_MAP, sslContextProviderSupplier, noopConfig);
    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector,
                    InternalProtocolNegotiators.serverPlaintext());
    setupChannel("172.168.1.1", "172.168.2.2", 90, filterChainMatchingHandler);
    channelHandlerCtx = pipeline.context(filterChainMatchingHandler);
    assertThat(channelHandlerCtx).isNotNull(); // should find HandlerPickerHandler

    // kick off protocol negotiation: should replace HandlerPickerHandler with ServerSdsHandler
    pipeline.fireUserEventTriggered(InternalProtocolNegotiationEvent.getDefault());
    channelHandlerCtx = pipeline.context(filterChainMatchingHandler);
    assertThat(channelHandlerCtx).isNull();
    channelHandlerCtx = pipeline.context(SdsProtocolNegotiators.ServerSdsHandler.class);
    assertThat(channelHandlerCtx).isNotNull();

    final SettableFuture<Object> future = SettableFuture.create();
    sslContextProviderSupplier
        .updateSslContext(new SslContextProvider.Callback(MoreExecutors.directExecutor()) {
          @Override
          public void updateSecret(SslContext sslContext) {
            future.set(sslContext);
          }

          @Override
          protected void onException(Throwable throwable) {
            future.set(throwable);
          }
        });
    channel.runPendingTasks(); // need this for tasks to execute on eventLoop
    Object fromFuture = future.get(2, TimeUnit.SECONDS);
    assertThat(fromFuture).isInstanceOf(SslContext.class);
    channel.runPendingTasks();
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
  @SuppressWarnings("unchecked")
  public void nullTlsContext_nullFallbackProtocolNegotiator_expectException() {
    ServerRoutingConfig noopConfig =
            ServerRoutingConfig.create(Collections.EMPTY_LIST, Collections.EMPTY_LIST);
    FilterChainSelector selector = new FilterChainSelector(Collections.EMPTY_MAP,
            null, noopConfig);
    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, null);
    setupChannel("172.168.1.1", "172.168.2.2", 90, filterChainMatchingHandler);
    channelHandlerCtx = pipeline.context(filterChainMatchingHandler);
    assertThat(channelHandlerCtx).isNotNull(); // should find HandlerPickerHandler

    // kick off protocol negotiation
    pipeline.fireUserEventTriggered(InternalProtocolNegotiationEvent.getDefault());
    channelHandlerCtx = pipeline.context(filterChainMatchingHandler);
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
  @SuppressWarnings("unchecked")
  public void noMatchingFilterChain_expectException() {
    ChannelHandler mockChannelHandler = mock(ChannelHandler.class);
    ProtocolNegotiator mockProtocolNegotiator = mock(ProtocolNegotiator.class);
    when(mockProtocolNegotiator.newHandler(grpcHandler)).thenReturn(mockChannelHandler);
    ServerRoutingConfig noopConfig =
            ServerRoutingConfig.create(Collections.EMPTY_LIST, Collections.EMPTY_LIST);
    FilterChainSelector selector = new FilterChainSelector(Collections.EMPTY_MAP,
            null, noopConfig);
    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector,
                    mockProtocolNegotiator);
    setupChannel("172.168.1.1", "172.168.2.2", 90, filterChainMatchingHandler);
    channelHandlerCtx = pipeline.context(filterChainMatchingHandler);

    assertThat(channelHandlerCtx).isNotNull();

    pipeline.fireUserEventTriggered(InternalProtocolNegotiationEvent.getDefault());
    channelHandlerCtx = pipeline.context(filterChainMatchingHandler);
    assertThat(channelHandlerCtx).isNull(); // HandlerPickerHandler still there
    channel.runPendingTasks(); // need this for tasks to execute on eventLoop
    Iterator<Map.Entry<String, ChannelHandler>> iterator = pipeline.iterator();
    assertThat(iterator.next().getValue()).isSameInstanceAs(mockChannelHandler);
    assertThat(iterator.hasNext()).isFalse();
  }

  private HttpConnectionManager createRds(String name) {
    return HttpConnectionManager.forRdsName(0L, name,
            new ArrayList<NamedFilterConfig>());
  }

  private FilterChain createFilterChain(String name, HttpConnectionManager hcm) {
    return new EnvoyServerProtoData.FilterChain(name, createMatch(),
            hcm, createTls(), tlsContextManager);
  }

  private EnvoyServerProtoData.FilterChainMatch createMatch() {
    return new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
  }

  private EnvoyServerProtoData.DownstreamTlsContext createTls() {
    return DownstreamTlsContext.fromEnvoyProtoDownstreamTlsContext(
            io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext
                    .getDefaultInstance());
  }

  private void setupChannel(final String localIp, final String remoteIp, final int remotePort,
                            FilterChainMatchingHandler matchingHandler) {
    channel =
        new EmbeddedChannel() {
          @Override
          public SocketAddress localAddress() {
            return new InetSocketAddress(localIp, 80);
          }

          @Override
          public SocketAddress remoteAddress() {
            return new InetSocketAddress(remoteIp, remotePort);
          }
        };
    pipeline = channel.pipeline();
    pipeline.addLast(matchingHandler);
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
      return "authority";
    }
  }
}
