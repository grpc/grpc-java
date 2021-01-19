/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.alts.internal;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.grpclb.GrpclbConstants;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.internal.ObjectPool;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.InternalProtocolNegotiationEvent;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.SslContext;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(Enclosed.class)
public final class GoogleDefaultProtocolNegotiatorTest {

  @RunWith(JUnit4.class)
  public abstract static class HandlerSelectionTest {
    private ProtocolNegotiator googleProtocolNegotiator;
    private final ObjectPool<Channel> handshakerChannelPool = new ObjectPool<Channel>() {

      @Override
      public Channel getObject() {
        return InProcessChannelBuilder.forName("test").build();
      }

      @Override
      public Channel returnObject(Object object) {
        ((ManagedChannel) object).shutdownNow();
        return null;
      }
    };

    @Before
    public void setUp() throws Exception {
      SslContext sslContext = GrpcSslContexts.forClient().build();

      googleProtocolNegotiator = new AltsProtocolNegotiator.GoogleDefaultProtocolNegotiatorFactory(
          ImmutableList.<String>of(),
          handshakerChannelPool,
          sslContext,
          getClusterNameAttrKey())
          .newNegotiator();
    }

    @After
    public void tearDown() {
      googleProtocolNegotiator.close();
    }

    @Nullable
    abstract Attributes.Key<String> getClusterNameAttrKey();

    @Test
    public void altsHandler_lbProvidedBackend() {
      Attributes attrs =
          Attributes.newBuilder().set(GrpclbConstants.ATTR_LB_PROVIDED_BACKEND, true).build();
      subtest_altsHandler(attrs);
    }

    @Test
    public void tlsHandler_emptyAttributes() {
      subtest_tlsHandler(Attributes.EMPTY);
    }

    void subtest_altsHandler(Attributes eagAttributes) {
      GrpcHttp2ConnectionHandler mockHandler = mock(GrpcHttp2ConnectionHandler.class);
      when(mockHandler.getEagAttributes()).thenReturn(eagAttributes);

      final AtomicReference<Throwable> failure = new AtomicReference<>();
      ChannelHandler exceptionCaught = new ChannelInboundHandlerAdapter() {
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
          failure.set(cause);
          super.exceptionCaught(ctx, cause);
        }
      };
      ChannelHandler h = googleProtocolNegotiator.newHandler(mockHandler);
      EmbeddedChannel chan = new EmbeddedChannel(exceptionCaught);
      // Add the negotiator handler last, but to the front. Putting this in ctor above would make
      // it throw early.
      chan.pipeline().addFirst(h);
      chan.pipeline().fireUserEventTriggered(InternalProtocolNegotiationEvent.getDefault());

      // Check that the message complained about the ALTS code, rather than SSL.  ALTS throws on
      // being added, so it's hard to catch it at the right time to make this assertion.
      assertThat(failure.get()).hasMessageThat().contains("TsiHandshakeHandler");
    }

    void subtest_tlsHandler(Attributes eagAttributes) {
      GrpcHttp2ConnectionHandler mockHandler = mock(GrpcHttp2ConnectionHandler.class);
      when(mockHandler.getEagAttributes()).thenReturn(eagAttributes);
      when(mockHandler.getAuthority()).thenReturn("authority");

      ChannelHandler h = googleProtocolNegotiator.newHandler(mockHandler);
      EmbeddedChannel chan = new EmbeddedChannel(h);
      chan.pipeline().fireUserEventTriggered(InternalProtocolNegotiationEvent.getDefault());

      assertThat(chan.pipeline().first().getClass().getSimpleName()).isEqualTo("SslHandler");
    }
  }

  @RunWith(JUnit4.class)
  public static class WithoutXdsInClasspath extends HandlerSelectionTest {

    @Nullable
    @Override
    Attributes.Key<String> getClusterNameAttrKey() {
      return null;
    }
  }

  @RunWith(JUnit4.class)
  public static class WithXdsInClasspath extends HandlerSelectionTest {
    // Same as io.grpc.xds.InternalXdsAttributes.ATTR_CLUSTER_NAME
    private static final Attributes.Key<String> XDS_CLUSTER_NAME_ATTR_KEY =
        Attributes.Key.create("io.grpc.xds.InternalXdsAttributes.clusterName");

    @Nullable
    @Override
    Attributes.Key<String> getClusterNameAttrKey() {
      return XDS_CLUSTER_NAME_ATTR_KEY;
    }

    @Test
    public void altsHandler_xdsCluster() {
      Attributes attrs =
          Attributes.newBuilder().set(XDS_CLUSTER_NAME_ATTR_KEY, "api.googleapis.com").build();
      subtest_altsHandler(attrs);
    }

    @Test
    public void tlsHandler_googleCfe() {
      Attributes attrs =
          Attributes.newBuilder().set(XDS_CLUSTER_NAME_ATTR_KEY, "google_cfe").build();
      subtest_tlsHandler(attrs);
    }
  }
}
