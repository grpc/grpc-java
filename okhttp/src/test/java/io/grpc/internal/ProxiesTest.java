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
