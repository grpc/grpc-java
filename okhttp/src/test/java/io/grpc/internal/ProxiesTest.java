/*
 * Copyright 2017, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.internal;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.net.HostAndPort;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public class ProxiesTest {
  private static final InetSocketAddress destination = InetSocketAddress.createUnresolved(
      "destination",
      5678
  );

  @Mock private ProxySelector proxySelector;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
  }

  @Test
  public void proxyOverride() throws Exception {
    final String overrideHost = "override";
    final int overridePort = 1234;
    String hostPort = HostAndPort.fromParts(overrideHost, overridePort).toString();
    InetSocketAddress proxySocket = Proxies.proxyFor(destination, proxySelector, hostPort);
    assertEquals(overrideHost, proxySocket.getHostName());
    assertEquals(overridePort, proxySocket.getPort());
  }

  @Test
  public void proxyOverrideOmittedPort() throws Exception {
    final String overrideHost = "override";
    final int defaultPort = 80;
    InetSocketAddress proxySocket = Proxies.proxyFor(destination, proxySelector, overrideHost);
    assertEquals(overrideHost, proxySocket.getHostName());
    assertEquals(defaultPort, proxySocket.getPort());
  }

  @Test
  public void returnNullWhenNoProxy() throws Exception {
    when(proxySelector.select(any(URI.class))).thenReturn(ImmutableList.of(Proxy.NO_PROXY));
    assertEquals(null, Proxies.proxyFor(destination, proxySelector, null));
  }

  @Test
  public void httpProxyTest() throws Exception {
    final InetSocketAddress proxyAddress = InetSocketAddress.createUnresolved("proxy", 1234);
    Proxy proxy = new Proxy(Proxy.Type.HTTP, proxyAddress);
    when(proxySelector.select(any(URI.class))).thenReturn(ImmutableList.of(proxy));

    InetSocketAddress detected = Proxies.proxyFor(destination, proxySelector, null);
    assertEquals(proxyAddress.getHostName(), detected.getHostName());
    assertEquals(proxyAddress.getPort(), detected.getPort());
  }

  @Test
  public void pickFirstHttpProxy() throws Exception {
    final InetSocketAddress proxyAddress = InetSocketAddress.createUnresolved("proxy1", 1111);
    InetSocketAddress otherProxy = InetSocketAddress.createUnresolved("proxy2", 2222);
    Proxy proxy1 = new Proxy(Proxy.Type.HTTP, proxyAddress);
    Proxy proxy2 = new Proxy(Proxy.Type.HTTP, otherProxy);
    when(proxySelector.select(any(URI.class))).thenReturn(ImmutableList.of(
        proxy1, proxy2
    ));

    InetSocketAddress detected = Proxies.proxyFor(destination, proxySelector, null);
    assertEquals(proxyAddress.getHostName(), detected.getHostName());
    assertEquals(proxyAddress.getPort(), detected.getPort());
  }

  // Mainly for InProcessSocketAddress
  @Test
  public void noProxyForNonInetSocket() throws Exception {
    assertEquals(null, Proxies.proxyFor(mock(SocketAddress.class), proxySelector, null));
  }
}
