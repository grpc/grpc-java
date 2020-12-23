/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc.okhttp;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.internal.GrpcUtil.TIMER_SERVICE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;

import com.squareup.okhttp.ConnectionSpec;
import io.grpc.CallCredentials;
import io.grpc.ChannelCredentials;
import io.grpc.ChannelLogger;
import io.grpc.ChoiceChannelCredentials;
import io.grpc.CompositeChannelCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.FakeClock;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.testing.GrpcCleanupRule;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ScheduledExecutorService;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link OkHttpChannelBuilder}.
 */
@RunWith(JUnit4.class)
public class OkHttpChannelBuilderTest {

  @SuppressWarnings("deprecation") // https://github.com/grpc/grpc-java/issues/7467
  @Rule public final ExpectedException thrown = ExpectedException.none();
  @Rule public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();

  @Test
  public void authorityIsReadable() {
    OkHttpChannelBuilder builder = OkHttpChannelBuilder.forAddress("original", 1234);
    ManagedChannel channel = grpcCleanupRule.register(builder.build());
    assertEquals("original:1234", channel.authority());
  }

  @Test
  public void overrideAuthorityIsReadableForAddress() {
    OkHttpChannelBuilder builder = OkHttpChannelBuilder.forAddress("original", 1234);
    overrideAuthorityIsReadableHelper(builder, "override:5678");
  }

  @Test
  public void overrideAuthorityIsReadableForTarget() {
    OkHttpChannelBuilder builder = OkHttpChannelBuilder.forTarget("original:1234");
    overrideAuthorityIsReadableHelper(builder, "override:5678");
  }

  private void overrideAuthorityIsReadableHelper(OkHttpChannelBuilder builder,
      String overrideAuthority) {
    builder.overrideAuthority(overrideAuthority);
    ManagedChannel channel = grpcCleanupRule.register(builder.build());
    assertEquals(overrideAuthority, channel.authority());
  }

  @Test
  public void failOverrideInvalidAuthority() {
    OkHttpChannelBuilder builder = OkHttpChannelBuilder.forAddress("good", 1234);

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Invalid authority:");
    builder.overrideAuthority("[invalidauthority");
  }

  @Test
  public void disableCheckAuthorityAllowsInvalidAuthority() {
    OkHttpChannelBuilder builder = OkHttpChannelBuilder.forAddress("good", 1234)
        .disableCheckAuthority();
    builder.overrideAuthority("[invalidauthority").usePlaintext().buildTransportFactory();
  }

  @Test
  public void enableCheckAuthorityFailOverrideInvalidAuthority() {
    OkHttpChannelBuilder builder = OkHttpChannelBuilder.forAddress("good", 1234)
        .disableCheckAuthority()
        .enableCheckAuthority();

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Invalid authority:");
    builder.overrideAuthority("[invalidauthority");
  }

  @Test
  public void failInvalidAuthority() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Invalid host or port");

    OkHttpChannelBuilder.forAddress("invalid_authority", 1234);
  }

  @Test
  public void sslSocketFactoryFrom_unknown() {
    OkHttpChannelBuilder.SslSocketFactoryResult result =
        OkHttpChannelBuilder.sslSocketFactoryFrom(new ChannelCredentials() {
          @Override
          public ChannelCredentials withoutBearerTokens() {
            throw new UnsupportedOperationException();
          }
        });
    assertThat(result.error).isNotNull();
    assertThat(result.callCredentials).isNull();
    assertThat(result.factory).isNull();
  }

  @Test
  public void sslSocketFactoryFrom_tls() {
    OkHttpChannelBuilder.SslSocketFactoryResult result =
        OkHttpChannelBuilder.sslSocketFactoryFrom(TlsChannelCredentials.create());
    assertThat(result.error).isNull();
    assertThat(result.callCredentials).isNull();
    assertThat(result.factory).isNotNull();
  }

  @Test
  public void sslSocketFactoryFrom_unsupportedTls() {
    OkHttpChannelBuilder.SslSocketFactoryResult result = OkHttpChannelBuilder.sslSocketFactoryFrom(
        TlsChannelCredentials.newBuilder().requireFakeFeature().build());
    assertThat(result.error).contains("FAKE");
    assertThat(result.callCredentials).isNull();
    assertThat(result.factory).isNull();
  }

  @Test
  public void sslSocketFactoryFrom_insecure() {
    OkHttpChannelBuilder.SslSocketFactoryResult result =
        OkHttpChannelBuilder.sslSocketFactoryFrom(InsecureChannelCredentials.create());
    assertThat(result.error).isNull();
    assertThat(result.callCredentials).isNull();
    assertThat(result.factory).isNull();
  }

  @Test
  public void sslSocketFactoryFrom_composite() {
    CallCredentials callCredentials = mock(CallCredentials.class);
    OkHttpChannelBuilder.SslSocketFactoryResult result =
        OkHttpChannelBuilder.sslSocketFactoryFrom(CompositeChannelCredentials.create(
          TlsChannelCredentials.create(), callCredentials));
    assertThat(result.error).isNull();
    assertThat(result.callCredentials).isSameInstanceAs(callCredentials);
    assertThat(result.factory).isNotNull();

    result = OkHttpChannelBuilder.sslSocketFactoryFrom(CompositeChannelCredentials.create(
          InsecureChannelCredentials.create(), callCredentials));
    assertThat(result.error).isNull();
    assertThat(result.callCredentials).isSameInstanceAs(callCredentials);
    assertThat(result.factory).isNull();
  }

  @Test
  public void sslSocketFactoryFrom_okHttp() throws Exception {
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, null, null);
    SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
    OkHttpChannelBuilder.SslSocketFactoryResult result = OkHttpChannelBuilder.sslSocketFactoryFrom(
        SslSocketFactoryChannelCredentials.create(sslSocketFactory));
    assertThat(result.error).isNull();
    assertThat(result.callCredentials).isNull();
    assertThat(result.factory).isSameInstanceAs(sslSocketFactory);
  }

  @Test
  public void sslSocketFactoryFrom_choice() {
    OkHttpChannelBuilder.SslSocketFactoryResult result =
        OkHttpChannelBuilder.sslSocketFactoryFrom(ChoiceChannelCredentials.create(
          new ChannelCredentials() {
            @Override
            public ChannelCredentials withoutBearerTokens() {
              throw new UnsupportedOperationException();
            }
          },
          TlsChannelCredentials.create(),
          InsecureChannelCredentials.create()));
    assertThat(result.error).isNull();
    assertThat(result.callCredentials).isNull();
    assertThat(result.factory).isNotNull();

    result = OkHttpChannelBuilder.sslSocketFactoryFrom(ChoiceChannelCredentials.create(
          InsecureChannelCredentials.create(),
          new ChannelCredentials() {
            @Override
            public ChannelCredentials withoutBearerTokens() {
              throw new UnsupportedOperationException();
            }
          },
          TlsChannelCredentials.create()));
    assertThat(result.error).isNull();
    assertThat(result.callCredentials).isNull();
    assertThat(result.factory).isNull();
  }

  @Test
  public void sslSocketFactoryFrom_choice_unknown() {
    OkHttpChannelBuilder.SslSocketFactoryResult result =
        OkHttpChannelBuilder.sslSocketFactoryFrom(ChoiceChannelCredentials.create(
          new ChannelCredentials() {
            @Override
            public ChannelCredentials withoutBearerTokens() {
              throw new UnsupportedOperationException();
            }
          }));
    assertThat(result.error).isNotNull();
    assertThat(result.callCredentials).isNull();
    assertThat(result.factory).isNull();
  }

  @Test
  public void failForUsingClearTextSpecDirectly() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("plaintext ConnectionSpec is not accepted");

    OkHttpChannelBuilder.forAddress("host", 1234).connectionSpec(ConnectionSpec.CLEARTEXT);
  }

  @Test
  public void allowUsingTlsConnectionSpec() {
    OkHttpChannelBuilder.forAddress("host", 1234).connectionSpec(ConnectionSpec.MODERN_TLS);
  }

  @Test
  public void usePlaintext_newClientTransportAllowed() {
    OkHttpChannelBuilder builder = OkHttpChannelBuilder.forAddress("host", 1234).usePlaintext();
    builder.buildTransportFactory().newClientTransport(
        new InetSocketAddress(5678),
        new ClientTransportFactory.ClientTransportOptions(), new FakeChannelLogger());
  }

  @Test
  public void usePlaintextDefaultPort() {
    OkHttpChannelBuilder builder = OkHttpChannelBuilder.forAddress("host", 1234).usePlaintext();
    assertEquals(GrpcUtil.DEFAULT_PORT_PLAINTEXT, builder.getDefaultPort());
  }

  @Test
  public void usePlaintextCreatesNullSocketFactory() {
    OkHttpChannelBuilder builder = OkHttpChannelBuilder.forAddress("host", 1234);
    assertNotNull(builder.createSslSocketFactory());

    builder.usePlaintext();
    assertNull(builder.createSslSocketFactory());
  }

  @Test
  public void scheduledExecutorService_default() {
    OkHttpChannelBuilder builder = OkHttpChannelBuilder.forTarget("foo");
    ClientTransportFactory clientTransportFactory = builder.buildTransportFactory();
    assertSame(
        SharedResourceHolder.get(TIMER_SERVICE),
        clientTransportFactory.getScheduledExecutorService());

    SharedResourceHolder.release(
        TIMER_SERVICE, clientTransportFactory.getScheduledExecutorService());
    clientTransportFactory.close();
  }

  @Test
  public void scheduledExecutorService_custom() {
    OkHttpChannelBuilder builder = OkHttpChannelBuilder.forTarget("foo");
    ScheduledExecutorService scheduledExecutorService =
        new FakeClock().getScheduledExecutorService();

    OkHttpChannelBuilder builder1 = builder.scheduledExecutorService(scheduledExecutorService);
    assertSame(builder, builder1);

    ClientTransportFactory clientTransportFactory = builder1.buildTransportFactory();

    assertSame(scheduledExecutorService, clientTransportFactory.getScheduledExecutorService());

    clientTransportFactory.close();
  }

  @Test
  public void socketFactory_default() {
    OkHttpChannelBuilder builder = OkHttpChannelBuilder.forTarget("foo");
    ClientTransportFactory transportFactory = builder.buildTransportFactory();
    OkHttpClientTransport transport =
        (OkHttpClientTransport)
            transportFactory.newClientTransport(
                new InetSocketAddress(5678),
                new ClientTransportFactory.ClientTransportOptions(),
                new FakeChannelLogger());

    assertSame(SocketFactory.getDefault(), transport.getSocketFactory());

    transportFactory.close();
  }

  @Test
  public void socketFactory_custom() {
    SocketFactory socketFactory =
        new SocketFactory() {
          @Override
          public Socket createSocket(String s, int i) {
            return null;
          }

          @Override
          public Socket createSocket(String s, int i, InetAddress inetAddress, int i1) {
            return null;
          }

          @Override
          public Socket createSocket(InetAddress inetAddress, int i) {
            return null;
          }

          @Override
          public Socket createSocket(
              InetAddress inetAddress, int i, InetAddress inetAddress1, int i1) {
            return null;
          }
        };
    OkHttpChannelBuilder builder =
        OkHttpChannelBuilder.forTarget("foo").socketFactory(socketFactory);
    ClientTransportFactory transportFactory = builder.buildTransportFactory();
    OkHttpClientTransport transport =
        (OkHttpClientTransport)
            transportFactory.newClientTransport(
                new InetSocketAddress(5678),
                new ClientTransportFactory.ClientTransportOptions(),
                new FakeChannelLogger());

    assertSame(socketFactory, transport.getSocketFactory());

    transportFactory.close();
  }

  private static final class FakeChannelLogger extends ChannelLogger {

    @Override
    public void log(ChannelLogLevel level, String message) {

    }

    @Override
    public void log(ChannelLogLevel level, String messageFormat, Object... args) {

    }
  }
}
