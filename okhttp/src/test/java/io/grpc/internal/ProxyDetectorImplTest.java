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

import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HostAndPort;
import io.grpc.internal.ProxyDetector.ProxyParameters;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
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
public class ProxyDetectorImplTest {
  private static final InetSocketAddress destination = InetSocketAddress.createUnresolved(
      "destination",
      5678
  );

  @Mock private ProxySelector proxySelector;
  @Mock private ProxyDetectorImpl.AuthenticationProvider authenticator;
  private Supplier<ProxySelector> proxySelectorSupplier;
  private ProxyDetector proxyDetector;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    proxySelectorSupplier = new Supplier<ProxySelector>() {
      @Override
      public ProxySelector get() {
        return proxySelector;
      }
    };
    proxyDetector = new ProxyDetectorImpl(proxySelectorSupplier, authenticator, null);
  }

  @Test
  public void override_hostPort() throws Exception {
    final String overrideHost = "override";
    final int overridePort = 1234;
    HostAndPort hostPort = HostAndPort.fromParts(overrideHost, overridePort);
    ProxyDetectorImpl proxyDetector = new ProxyDetectorImpl(
        proxySelectorSupplier,
        authenticator,
        hostPort.toString());
    Optional<ProxyParameters> detected = proxyDetector.proxyFor(destination);
    assertTrue(detected.isPresent());
    assertEquals(
        new ProxyParameters(
            InetSocketAddress.createUnresolved(overrideHost, overridePort), null, null),
        detected.get());
  }

  @Test
  public void override_hostOnly() throws Exception {
    final String overrideHostWithoutPort = "override";
    final int defaultPort = 80;
    ProxyDetectorImpl proxyDetector = new ProxyDetectorImpl(
        proxySelectorSupplier,
        authenticator,
        overrideHostWithoutPort);
    Optional<ProxyParameters> detected = proxyDetector.proxyFor(destination);
    assertTrue(detected.isPresent());
    assertEquals(
        new ProxyParameters(
            InetSocketAddress.createUnresolved(overrideHostWithoutPort, defaultPort), null, null),
        detected.get());
  }

  @Test
  public void returnAbsentWhenNoProxy() throws Exception {
    when(proxySelector.select(any(URI.class))).thenReturn(ImmutableList.of(Proxy.NO_PROXY));
    assertFalse(proxyDetector.proxyFor(destination).isPresent());
  }

  @Test
  public void httpProxyTest() throws Exception {
    final InetSocketAddress proxyAddress = InetSocketAddress.createUnresolved("proxy", 1234);
    Proxy proxy = new Proxy(Proxy.Type.HTTP, proxyAddress);
    when(proxySelector.select(any(URI.class))).thenReturn(ImmutableList.of(proxy));

    Optional<ProxyParameters> detected = proxyDetector.proxyFor(destination);
    assertTrue(detected.isPresent());
    assertEquals(new ProxyParameters(proxyAddress, null, null), detected.get());
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

    Optional<ProxyParameters> detected = proxyDetector.proxyFor(destination);
    assertTrue(detected.isPresent());
    assertEquals(new ProxyParameters(proxyAddress, null, null), detected.get());
  }

  // Mainly for InProcessSocketAddress
  @Test
  public void noProxyForNonInetSocket() throws Exception {
    assertFalse(proxyDetector.proxyFor(mock(SocketAddress.class)).isPresent());
  }

  @Test
  public void authRequired() throws Exception {
    final String proxyHost = "proxyhost";
    final int proxyPort = 1234;
    final InetSocketAddress proxyAddress = InetSocketAddress.createUnresolved(proxyHost, proxyPort);
    Proxy proxy = new Proxy(Proxy.Type.HTTP, proxyAddress);
    final String proxyUser = "testuser";
    final String proxyPassword = "testpassword";
    PasswordAuthentication auth = new PasswordAuthentication(
        proxyUser,
        proxyPassword.toCharArray());
    when(authenticator.requestPasswordAuthentication(
        any(String.class),
        any(InetAddress.class),
        any(Integer.class),
        any(String.class),
        any(String.class),
        any(String.class))).thenReturn(auth);
    when(proxySelector.select(any(URI.class))).thenReturn(ImmutableList.of(proxy));

    Optional<ProxyParameters> detected = proxyDetector.proxyFor(destination);
    assertTrue(detected.isPresent());
    assertEquals(
        new ProxyParameters(proxyAddress, proxyUser, proxyPassword),
        detected.get());
  }
}
