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

package io.grpc.xds.internal.sds;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.internal.sds.FilterChainMatchingHandler.ATTR_SERVER_SSL_CONTEXT_PROVIDER_SUPPLIER;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.SettableFuture;
import io.grpc.internal.TestUtils.NoopChannelLogger;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalProtocolNegotiationEvent;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.ProtocolNegotiationEvent;
import io.grpc.xds.EnvoyServerProtoData;
import io.grpc.xds.EnvoyServerProtoData.CidrRange;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.EnvoyServerProtoData.FilterChain;
import io.grpc.xds.EnvoyServerProtoData.FilterChainMatch;
import io.grpc.xds.Filter.NamedFilterConfig;
import io.grpc.xds.HttpConnectionManager;
import io.grpc.xds.TlsContextManager;
import io.grpc.xds.internal.sds.FilterChainMatchingHandler.FilterChainSelector;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
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
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
  private ProtocolNegotiationEvent event = InternalProtocolNegotiationEvent.getDefault();
  private ChannelPipeline pipeline;
  private EmbeddedChannel channel;
  private ChannelHandlerContext channelHandlerCtx;
  @Mock
  private ProtocolNegotiator mockDelegate;
  @Mock
  private ChannelHandler mockHandler;

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);
    when(mockDelegate.newHandler(any(GrpcHttp2ConnectionHandler.class))).thenReturn(mockHandler);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void filterChainMatch() throws Exception {
    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    ChannelHandler next = new ChannelInboundHandlerAdapter() {
      @Override
      public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        ProtocolNegotiationEvent e = (ProtocolNegotiationEvent)evt;
        sslSet.set(InternalProtocolNegotiationEvent.getAttributes(e)
                .get(ATTR_SERVER_SSL_CONTEXT_PROVIDER_SUPPLIER));
      }
    };
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);

    FilterChain f0 = createFilterChain("filter-chain-0", createRds("r0"));
    SslContextProviderSupplier defaultSsl = new SslContextProviderSupplier(createTls(),
            mock(TlsContextManager.class));
    FilterChainSelector selector = new FilterChainSelector(Collections.singletonList(f0),
            defaultSsl);
    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);
    setupChannel("172.168.1.1", "172.168.1.2", 80, filterChainMatchingHandler);
    ChannelHandlerContext channelHandlerCtx = pipeline.context(filterChainMatchingHandler);
    assertThat(channelHandlerCtx).isNotNull();

    pipeline.fireUserEventTriggered(event);
    channelHandlerCtx = pipeline.context(filterChainMatchingHandler);
    assertThat(channelHandlerCtx).isNull();
    channel.runPendingTasks();
    assertThat(sslSet.isDone()).isTrue();
    assertThat(sslSet.get()).isEqualTo(f0.getSslContextProviderSupplier());
    channelHandlerCtx = pipeline.context(next);
    assertThat(channelHandlerCtx).isNotNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void nofilterChainMatch_defaultSslContext() throws Exception {
    final SettableFuture<SslContextProviderSupplier> sslSet = SettableFuture.create();
    ChannelHandler next = new ChannelInboundHandlerAdapter() {
      @Override
      public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        ProtocolNegotiationEvent e = (ProtocolNegotiationEvent)evt;
        sslSet.set(InternalProtocolNegotiationEvent.getAttributes(e)
                .get(ATTR_SERVER_SSL_CONTEXT_PROVIDER_SUPPLIER));
      }
    };
    when(mockDelegate.newHandler(grpcHandler)).thenReturn(next);

    SslContextProviderSupplier ssl = new SslContextProviderSupplier(createTls(), tlsContextManager);
    FilterChainSelector selector = new FilterChainSelector(Collections.EMPTY_LIST, ssl);
    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);
    setupChannel("172.168.1.1", "172.168.1.2", 80, filterChainMatchingHandler);
    ChannelHandlerContext channelHandlerCtx = pipeline.context(filterChainMatchingHandler);
    assertThat(channelHandlerCtx).isNotNull();

    pipeline.fireUserEventTriggered(event);
    channelHandlerCtx = pipeline.context(filterChainMatchingHandler);
    assertThat(channelHandlerCtx).isNull();

    channel.runPendingTasks();
    assertThat(sslSet.isDone()).isTrue();
    assertThat(sslSet.get()).isEqualTo(ssl);
    channelHandlerCtx = pipeline.context(next);
    assertThat(channelHandlerCtx).isNotNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void noFilterChainMatch_noDefaultSslContext() {
    FilterChainSelector selector = new FilterChainSelector(Collections.EMPTY_LIST, null);
    FilterChainMatchingHandler filterChainMatchingHandler =
            new FilterChainMatchingHandler(grpcHandler, selector, mockDelegate);
    setupChannel("172.168.1.1", "172.168.2.2", 90, filterChainMatchingHandler);
    channelHandlerCtx = pipeline.context(filterChainMatchingHandler);
    assertThat(channelHandlerCtx).isNotNull();

    pipeline.fireUserEventTriggered(event);
    channelHandlerCtx = pipeline.context(filterChainMatchingHandler);
    assertThat(channelHandlerCtx).isNotNull();
    try {
      channel.checkException();
      fail("exception expected!");
    } catch (Exception e) {
      assertThat(e).isInstanceOf(IllegalStateException.class);
      assertThat(e).hasMessageThat().contains("No matching filter chain.");
    }
  }


  private HttpConnectionManager createRds(String name) {
    return HttpConnectionManager.forRdsName(0L, name,
            new ArrayList<NamedFilterConfig>());
  }

  private FilterChain createFilterChain(String name, HttpConnectionManager hcm) {
    return new FilterChain(name, createMatch(),
            hcm, createTls(), tlsContextManager);
  }

  private FilterChainMatch createMatch() {
    return new FilterChainMatch(
            0,
            Arrays.<CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.<CidrRange>asList(),
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
