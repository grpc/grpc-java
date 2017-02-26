/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.netty;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import io.grpc.netty.InternalNettyChannelBuilder.OverrideAuthorityChecker;
import io.grpc.netty.ProtocolNegotiators.TlsNegotiator;
import io.netty.handler.ssl.SslContext;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import javax.net.ssl.SSLException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NettyChannelBuilderTest {

  @Rule public final ExpectedException thrown = ExpectedException.none();
  private final SslContext noSslContext = null;

  @Test
  public void overrideAllowsInvalidAuthority() {
    NettyChannelBuilder builder = new NettyChannelBuilder(new SocketAddress(){});
    InternalNettyChannelBuilder.overrideAuthorityChecker(builder, new OverrideAuthorityChecker() {
      @Override
      public String checkAuthority(String authority) {
        return authority;
      }
    });
    Object unused = builder.overrideAuthority("[invalidauthority")
        .negotiationType(NegotiationType.PLAINTEXT)
        .buildTransportFactory();
  }

  @Test
  public void failOverrideInvalidAuthority() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Invalid authority:");

    NettyChannelBuilder builder = new NettyChannelBuilder(new SocketAddress(){});

    Object unused = builder.overrideAuthority("[invalidauthority")
        .negotiationType(NegotiationType.PLAINTEXT)
        .buildTransportFactory();
  }

  @Test
  public void failInvalidAuthority() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Invalid host or port");

    Object unused =
        NettyChannelBuilder.forAddress(new InetSocketAddress("invalid_authority", 1234));
  }

  @Test
  public void sslContextCanBeNull() {
    NettyChannelBuilder builder = new NettyChannelBuilder(new SocketAddress(){});
    builder.sslContext(null);
  }

  @Test
  public void failIfSslContextIsNotClient() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Server SSL context can not be used for client channel");

    SslContext sslContext = mock(SslContext.class);

    NettyChannelBuilder builder = new NettyChannelBuilder(new SocketAddress(){});
    builder.sslContext(sslContext);
  }

  @Test
  public void createProtocolNegotiator_plaintext() {
    ProtocolNegotiator negotiator = NettyChannelBuilder.createProtocolNegotiator(
        "authority",
        NegotiationType.PLAINTEXT,
        noSslContext);
    // just check that the classes are the same, and that negotiator is not null.
    assertTrue(negotiator instanceof ProtocolNegotiators.PlaintextNegotiator);
  }

  @Test
  public void createProtocolNegotiator_plaintextUpgrade() {
    ProtocolNegotiator negotiator = NettyChannelBuilder.createProtocolNegotiator(
        "authority",
        NegotiationType.PLAINTEXT_UPGRADE,
        noSslContext);
    // just check that the classes are the same, and that negotiator is not null.
    assertTrue(negotiator instanceof ProtocolNegotiators.PlaintextUpgradeNegotiator);
  }

  @Test
  public void createProtocolNegotiator_tlsWithNoContext() {
    ProtocolNegotiator negotiator = NettyChannelBuilder.createProtocolNegotiator(
        "authority:1234",
        NegotiationType.TLS,
        noSslContext);

    assertTrue(negotiator instanceof ProtocolNegotiators.TlsNegotiator);
    ProtocolNegotiators.TlsNegotiator n = (TlsNegotiator) negotiator;

    assertEquals("authority", n.getHost());
    assertEquals(1234, n.getPort());
  }

  @Test
  public void createProtocolNegotiator_tlsWithClientContext() throws SSLException {
    ProtocolNegotiator negotiator = NettyChannelBuilder.createProtocolNegotiator(
        "authority:1234",
        NegotiationType.TLS,
        GrpcSslContexts.forClient().build());

    assertTrue(negotiator instanceof ProtocolNegotiators.TlsNegotiator);
    ProtocolNegotiators.TlsNegotiator n = (TlsNegotiator) negotiator;

    assertEquals("authority", n.getHost());
    assertEquals(1234, n.getPort());
  }

  @Test
  public void createProtocolNegotiator_tlsWithAuthorityFallback() {
    ProtocolNegotiator negotiator = NettyChannelBuilder.createProtocolNegotiator(
        "bad_authority",
        NegotiationType.TLS,
        noSslContext);

    assertTrue(negotiator instanceof ProtocolNegotiators.TlsNegotiator);
    ProtocolNegotiators.TlsNegotiator n = (TlsNegotiator) negotiator;

    assertEquals("bad_authority", n.getHost());
    assertEquals(-1, n.getPort());
  }
}
