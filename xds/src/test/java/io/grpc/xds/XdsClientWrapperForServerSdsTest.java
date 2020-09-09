/*
 * Copyright 2020 The gRPC Authors
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.netty.channel.Channel;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link XdsClientWrapperForServerSds}. */
@RunWith(Parameterized.class)
public class XdsClientWrapperForServerSdsTest {

  private static final int PORT = 7000;

  /** Iterable of various configurations to use for tests. */
  @Parameterized.Parameters(name = "{6}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(
        new Object[][] {
          {
            -1, // creates null filterChainMatch for filter1
            null,
            null,
            "192.168.10.1",
            "192.168.10.2",
            1,
            "null filter chain match, expect filter1"
          },
          {
            PORT + 1,
            "192.168.10.1",
            "192.168.10.2",
            null,
            null,
            2,
            "only dest port match, expect filter2"
          },
          {
            PORT, // matches dest port
            "168.20.20.2",
            "10.1.2.3",  // matches local address
            "192.168.10.1",
            "192.168.10.2",
            1,
            "dest port & address match, expect filter1"
          },
          {
            -1, // creates null filterChainMatch for filter1
            null,
            null,
            null, // empty address range for filter2
            null, // empty address range for filter2
            2,
            "empty address range over empty filterChainMatch, expect filter2"
          },
          {
            PORT,
            null,
            null,
            "192.168.1.4",
            "0.0.0.0",  // IPANY for filter2
            2,
            "IPANY over empty address match, expect filter2"
          },
          {
            PORT,
            "192.168.1.4",
            "0.0.0.0",  // IPANY for filter1
            "168.154.4.7",
            "10.1.2.3",  // matches local address
            2,
            "exact IP over IPANY match, expect filter2"
          },
          {
            PORT, // matches dest port but no address match
            "168.20.20.2",
            "10.1.2.4",
            "192.168.10.1",
            "192.168.10.2",
            0,
            "dest port match but no address match, expect null"
          }
        });
  }

  @Parameter(0)
  public int destPort1;
  @Parameter(1)
  public String addressPrefix11;
  @Parameter(2)
  public String addressPrefix12;
  @Parameter(3)
  public String addressPrefix21;
  @Parameter(4)
  public String addressPrefix22;
  @Parameter(5)
  public int expectedIndex;
  @Parameter(6)
  public String testName;

  @Mock private XdsClient xdsClient;
  @Mock private Channel channel;

  private XdsClientWrapperForServerSds xdsClientWrapperForServerSds;
  private final DownstreamTlsContext[] tlsContexts = new DownstreamTlsContext[3];

  /** Creates XdsClientWrapperForServerSds: also used by other classes. */
  public static XdsClientWrapperForServerSds createXdsClientWrapperForServerSds(
      int port, DownstreamTlsContext downstreamTlsContext) {
    XdsClient mockXdsClient = mock(XdsClient.class);
    XdsClientWrapperForServerSds xdsClientWrapperForServerSds =
        new XdsClientWrapperForServerSds(port);
    xdsClientWrapperForServerSds.start(mockXdsClient);
    generateListenerUpdateToWatcher(
        port, downstreamTlsContext, xdsClientWrapperForServerSds.getListenerWatcher());
    return xdsClientWrapperForServerSds;
  }

  static void generateListenerUpdateToWatcher(
      int port, DownstreamTlsContext tlsContext, XdsClient.ListenerWatcher registeredWatcher) {
    EnvoyServerProtoData.Listener listener =
        XdsSdsClientServerTest.buildListener("listener1", "0.0.0.0", port, tlsContext);
    XdsClient.ListenerUpdate listenerUpdate =
        XdsClient.ListenerUpdate.newBuilder().setListener(listener).build();
    registeredWatcher.onListenerChanged(listenerUpdate);
  }

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);
    xdsClientWrapperForServerSds = new XdsClientWrapperForServerSds(PORT);
    xdsClientWrapperForServerSds.start(xdsClient);
    tlsContexts[0] = null;
    tlsContexts[1] =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    tlsContexts[2] =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
  }

  @After
  public void tearDown() {
    xdsClientWrapperForServerSds.shutdown();
  }

  /**
   * Common method called by most tests. Creates 2 filterChains each with 2 addresses. First
   * filterChain's destPort is always PORT.
   */
  @Test
  public void commonFilterChainMatchTest()
      throws UnknownHostException {
    ArgumentCaptor<XdsClient.ListenerWatcher> listenerWatcherCaptor = ArgumentCaptor.forClass(null);
    verify(xdsClient).watchListenerData(eq(PORT), listenerWatcherCaptor.capture());
    XdsClient.ListenerWatcher registeredWatcher = listenerWatcherCaptor.getValue();
    InetAddress ipLocalAddress = Inet4Address.getByName("10.1.2.3");
    InetSocketAddress localAddress = new InetSocketAddress(ipLocalAddress, PORT);
    when(channel.localAddress()).thenReturn(localAddress);
    EnvoyServerProtoData.Listener listener =
        XdsServerTestHelper.buildTestListener(
            "listener1",
            "10.1.2.3",
            destPort1,
            PORT,
            addressPrefix11,
            addressPrefix12,
            addressPrefix21,
            addressPrefix22,
            tlsContexts[1],
            tlsContexts[2]);
    XdsClient.ListenerUpdate listenerUpdate =
        XdsClient.ListenerUpdate.newBuilder().setListener(listener).build();
    registeredWatcher.onListenerChanged(listenerUpdate);
    DownstreamTlsContext downstreamTlsContext =
        xdsClientWrapperForServerSds.getDownstreamTlsContext(channel);
    assertThat(downstreamTlsContext).isSameInstanceAs(tlsContexts[expectedIndex]);
  }

}
