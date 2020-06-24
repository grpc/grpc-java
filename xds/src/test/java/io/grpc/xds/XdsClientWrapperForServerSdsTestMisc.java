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
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.inprocess.InProcessSocketAddress;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.netty.channel.Channel;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link XdsClientWrapperForServerSds}. */
@RunWith(JUnit4.class)
public class XdsClientWrapperForServerSdsTestMisc {

  private static final int PORT = 7000;

  @Mock private XdsClient xdsClient;
  @Mock private Channel channel;

  private XdsClientWrapperForServerSds xdsClientWrapperForServerSds;

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);
    xdsClientWrapperForServerSds = new XdsClientWrapperForServerSds(PORT);
    xdsClientWrapperForServerSds.start(xdsClient);
  }

  @After
  public void tearDown() {
    xdsClientWrapperForServerSds.shutdown();
  }

  @Test
  public void verifyListenerWatcherRegistered() {
    verify(xdsClient).watchListenerData(eq(PORT), any(XdsClient.ListenerWatcher.class));
  }

  @Test
  public void nonInetSocketAddress_expectException() {
    try {
      DownstreamTlsContext unused =
          commonTestPrep(new InProcessSocketAddress("test1"));
      fail("exception expected");
    } catch (IllegalStateException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("Channel localAddress is expected to be InetSocketAddress");
    }
  }

  @Test
  public void nonMatchingPort_expectException() throws UnknownHostException {
    try {
      InetAddress ipLocalAddress = InetAddress.getByName("10.1.2.3");
      InetSocketAddress localAddress = new InetSocketAddress(ipLocalAddress, PORT + 1);
      DownstreamTlsContext unused = commonTestPrep(localAddress);
      fail("exception expected");
    } catch (IllegalStateException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("Channel localAddress port does not match requested listener port");
    }
  }

  @Test
  public void emptyFilterChain_expectNull() throws UnknownHostException {
    InetAddress ipLocalAddress = InetAddress.getByName("10.1.2.3");
    InetSocketAddress localAddress = new InetSocketAddress(ipLocalAddress, PORT);
    ArgumentCaptor<XdsClient.ListenerWatcher> listenerWatcherCaptor = ArgumentCaptor.forClass(null);
    verify(xdsClient).watchListenerData(eq(PORT), listenerWatcherCaptor.capture());
    XdsClient.ListenerWatcher registeredWatcher = listenerWatcherCaptor.getValue();
    when(channel.localAddress()).thenReturn(localAddress);
    EnvoyServerProtoData.Listener listener =
            new EnvoyServerProtoData.Listener("listener1",
                    "10.1.2.3", Collections.<EnvoyServerProtoData.FilterChain>emptyList());
    XdsClient.ListenerUpdate listenerUpdate =
            XdsClient.ListenerUpdate.newBuilder().setListener(listener).build();
    registeredWatcher.onListenerChanged(listenerUpdate);
    DownstreamTlsContext tlsContext = xdsClientWrapperForServerSds.getDownstreamTlsContext(channel);
    assertThat(tlsContext).isNull();
  }

  private DownstreamTlsContext commonTestPrep(SocketAddress localAddress) {
    ArgumentCaptor<XdsClient.ListenerWatcher> listenerWatcherCaptor = ArgumentCaptor.forClass(null);
    verify(xdsClient).watchListenerData(eq(PORT), listenerWatcherCaptor.capture());
    XdsClient.ListenerWatcher registeredWatcher = listenerWatcherCaptor.getValue();
    when(channel.localAddress()).thenReturn(localAddress);
    EnvoyServerProtoData.Listener listener =
        XdsClientWrapperForServerSdsTest.buildTestListener(
            "listener1", "10.1.2.3", PORT, PORT, null, null, null, null, null, null);
    XdsClient.ListenerUpdate listenerUpdate =
        XdsClient.ListenerUpdate.newBuilder().setListener(listener).build();
    registeredWatcher.onListenerChanged(listenerUpdate);
    return xdsClientWrapperForServerSds.getDownstreamTlsContext(channel);
  }
}
