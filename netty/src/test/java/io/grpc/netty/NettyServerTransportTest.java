/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

package io.grpc.netty;

import static io.grpc.netty.NettyServerTransport.getLogLevel;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.ServerStreamTracer;
import io.grpc.internal.Channelz.Security;
import io.grpc.internal.Channelz.Tls;
import io.grpc.internal.TransportTracer;
import io.grpc.internal.testing.TestUtils;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.logging.Level;
import javax.net.ssl.SSLSession;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NettyServerTransportTest {
  @Test
  public void unknownException() {
    assertEquals(Level.INFO, getLogLevel(new Exception()));
  }

  @Test
  public void quiet() {
    assertEquals(Level.FINE, getLogLevel(new IOException("Connection reset by peer")));
    assertEquals(Level.FINE, getLogLevel(new IOException(
        "An existing connection was forcibly closed by the remote host")));
  }

  @Test
  public void nonquiet() {
    assertEquals(Level.INFO, getLogLevel(new IOException("foo")));
  }

  @Test
  public void nullMessage() {
    IOException e = new IOException();
    assertNull(e.getMessage());
    assertEquals(Level.INFO, getLogLevel(e));
  }

  @Test
  public void tlsReportedForChannelz() throws Exception {
    EmbeddedChannel ch = new EmbeddedChannel();
    NettyServerTransport transport = new NettyServerTransport(
        ch,
        ch.newPromise(),
        ProtocolNegotiators.serverPlaintext(),
        Collections.<ServerStreamTracer.Factory>emptyList(),
        TransportTracer.getDefaultFactory().create(),
        /*maxStreams=*/ 0,
        /*flowControlWindow=*/ 0,
        /*maxMessageSize=*/ 0,
        /*maxHeaderListSize=*/ 0,
        /*keepAliveTimeInNanos=*/ 0,
        /*keepAliveTimeoutInNanos=*/ 0,
        /*maxConnectionIdleInNanos=*/ 0,
        /*maxConnectionAgeInNanos=*/ 0,
        /*maxConnectionAgeGraceInNanos=*/ 0,
        /*permitKeepAliveWithoutCalls=*/ false,
        /*permitKeepAliveTimeInNanos=*/ 0);

    NettyServerHandler handler = mock(NettyServerHandler.class);
    transport.grpcHandler = handler;
    when(handler.getAttributes()).thenReturn(Attributes.EMPTY);
    assertNull(transport.getSecurity());

    Certificate local = TestUtils.loadX509Cert("server0.pem");
    Certificate remote = TestUtils.loadX509Cert("client.pem");
    final SSLSession session = mock(SSLSession.class);
    when(session.getCipherSuite()).thenReturn("TLS_NULL_WITH_NULL_NULL");
    when(session.getLocalCertificates()).thenReturn(new Certificate[]{local});
    when(session.getPeerCertificates()).thenReturn(new Certificate[]{remote});
    Attributes attr = Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session).build();

    when(handler.getAttributes()).thenReturn(attr);
    Security security = transport.getSecurity();
    Tls tls = security.tls;
    assertNotNull(tls);
    assertEquals(local.toString(), tls.localCert);
    assertEquals(remote.toString(), tls.remoteCert);
    assertEquals("TLS_NULL_WITH_NULL_NULL", tls.cipherSuiteStandardName);
  }
}
