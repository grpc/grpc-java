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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.inprocess.InProcessSocketAddress;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
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
  private XdsClient.ListenerWatcher registeredWatcher;

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);
    xdsClientWrapperForServerSds = new XdsClientWrapperForServerSds(PORT);
  }

  @After
  public void tearDown() {
    xdsClientWrapperForServerSds.shutdown();
  }

  @Test
  public void verifyListenerWatcherRegistered() {
    registeredWatcher =
            XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds, xdsClient, PORT);
    verify(xdsClient).watchListenerData(eq(PORT), any(XdsClient.ListenerWatcher.class));
  }

  @Test
  public void nonInetSocketAddress_expectNull() throws UnknownHostException {
    registeredWatcher =
        XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds, xdsClient, PORT);
    assertThat(sendListenerUpdate(new InProcessSocketAddress("test1"), null)).isNull();
  }

  @Test
  public void nonMatchingPort_expectException() throws UnknownHostException {
    registeredWatcher =
        XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds, xdsClient, PORT);
    try {
      InetAddress ipLocalAddress = InetAddress.getByName("10.1.2.3");
      InetSocketAddress localAddress = new InetSocketAddress(ipLocalAddress, PORT + 1);
      DownstreamTlsContext unused = sendListenerUpdate(localAddress, null);
      fail("exception expected");
    } catch (IllegalStateException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("Channel localAddress port does not match requested listener port");
    }
  }

  @Test
  public void emptyFilterChain_expectNull() throws UnknownHostException {
    registeredWatcher =
            XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds, xdsClient, PORT);
    InetAddress ipLocalAddress = InetAddress.getByName("10.1.2.3");
    InetSocketAddress localAddress = new InetSocketAddress(ipLocalAddress, PORT);
    ArgumentCaptor<XdsClient.ListenerWatcher> listenerWatcherCaptor = ArgumentCaptor.forClass(null);
    verify(xdsClient).watchListenerData(eq(PORT), listenerWatcherCaptor.capture());
    XdsClient.ListenerWatcher registeredWatcher = listenerWatcherCaptor.getValue();
    when(channel.localAddress()).thenReturn(localAddress);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1",
            "10.1.2.3",
            Collections.<EnvoyServerProtoData.FilterChain>emptyList(),
            null);
    XdsClient.ListenerUpdate listenerUpdate =
        XdsClient.ListenerUpdate.newBuilder().setListener(listener).build();
    registeredWatcher.onListenerChanged(listenerUpdate);
    DownstreamTlsContext tlsContext = xdsClientWrapperForServerSds.getDownstreamTlsContext(channel);
    assertThat(tlsContext).isNull();
  }

  @Test
  public void registerServerWatcher() throws UnknownHostException {
    registeredWatcher =
            XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds, xdsClient, PORT);
    XdsClientWrapperForServerSds.ServerWatcher mockServerWatcher =
        mock(XdsClientWrapperForServerSds.ServerWatcher.class);
    xdsClientWrapperForServerSds.addServerWatcher(mockServerWatcher);
    InetAddress ipLocalAddress = InetAddress.getByName("10.1.2.3");
    InetSocketAddress localAddress = new InetSocketAddress(ipLocalAddress, PORT);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    verify(mockServerWatcher, never())
        .onListenerUpdate();
    DownstreamTlsContext returnedTlsContext = sendListenerUpdate(localAddress, tlsContext);
    assertThat(returnedTlsContext).isSameInstanceAs(tlsContext);
    verify(mockServerWatcher).onListenerUpdate();
    xdsClientWrapperForServerSds.removeServerWatcher(mockServerWatcher);
  }

  @Test
  public void registerServerWatcher_afterListenerUpdate() throws UnknownHostException {
    registeredWatcher =
            XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds, xdsClient, PORT);
    InetAddress ipLocalAddress = InetAddress.getByName("10.1.2.3");
    InetSocketAddress localAddress = new InetSocketAddress(ipLocalAddress, PORT);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    DownstreamTlsContext returnedTlsContext = sendListenerUpdate(localAddress, tlsContext);
    assertThat(returnedTlsContext).isSameInstanceAs(tlsContext);
    XdsClientWrapperForServerSds.ServerWatcher mockServerWatcher =
            mock(XdsClientWrapperForServerSds.ServerWatcher.class);
    xdsClientWrapperForServerSds.addServerWatcher(mockServerWatcher);
    verify(mockServerWatcher).onListenerUpdate();
  }

  @Test
  public void registerServerWatcher_notifyError() throws UnknownHostException {
    registeredWatcher =
            XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds, xdsClient, PORT);
    XdsClientWrapperForServerSds.ServerWatcher mockServerWatcher =
        mock(XdsClientWrapperForServerSds.ServerWatcher.class);
    xdsClientWrapperForServerSds.addServerWatcher(mockServerWatcher);
    registeredWatcher.onError(Status.INTERNAL);
    ArgumentCaptor<Throwable> argCaptor = ArgumentCaptor.forClass(null);
    verify(mockServerWatcher).onError(argCaptor.capture(), eq(false));
    Throwable throwable = argCaptor.getValue();
    assertThat(throwable).isInstanceOf(StatusException.class);
    Status captured = ((StatusException)throwable).getStatus();
    assertThat(captured.getCode()).isEqualTo(Status.Code.INTERNAL);
    reset(mockServerWatcher);
    registeredWatcher.onResourceDoesNotExist("not-found Error");
    ArgumentCaptor<Throwable> argCaptor1 = ArgumentCaptor.forClass(null);
    verify(mockServerWatcher).onError(argCaptor1.capture(), eq(true));
    throwable = argCaptor1.getValue();
    assertThat(throwable).isInstanceOf(StatusException.class);
    captured = ((StatusException)throwable).getStatus();
    assertThat(captured.getCode()).isEqualTo(Status.Code.NOT_FOUND);
    InetAddress ipLocalAddress = InetAddress.getByName("10.1.2.3");
    InetSocketAddress localAddress = new InetSocketAddress(ipLocalAddress, PORT);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    verify(mockServerWatcher, never())
        .onListenerUpdate();
    DownstreamTlsContext returnedTlsContext = sendListenerUpdate(localAddress, tlsContext);
    assertThat(returnedTlsContext).isSameInstanceAs(tlsContext);
    verify(mockServerWatcher).onListenerUpdate();
  }

  @Test
  public void startXdsClient_expectException() {
    XdsClientWrapperForServerSds.ServerWatcher mockServerWatcher =
            mock(XdsClientWrapperForServerSds.ServerWatcher.class);
    xdsClientWrapperForServerSds.addServerWatcher(mockServerWatcher);
    try {
      xdsClientWrapperForServerSds.createXdsClientAndStart();
      fail("exception expected");
    } catch (IOException expected) {
      assertThat(expected)
              .hasMessageThat()
              .contains("Cannot find bootstrap configuration");
    }
    verify(mockServerWatcher, never()).onError(any(Throwable.class), eq(false));
  }

  private DownstreamTlsContext sendListenerUpdate(
      SocketAddress localAddress, DownstreamTlsContext tlsContext) throws UnknownHostException {
    when(channel.localAddress()).thenReturn(localAddress);
    InetAddress ipRemoteAddress = InetAddress.getByName("10.4.5.6");
    InetSocketAddress remoteAddress = new InetSocketAddress(ipRemoteAddress, 1234);
    when(channel.remoteAddress()).thenReturn(remoteAddress);
    XdsServerTestHelper.generateListenerUpdate(registeredWatcher, tlsContext);
    return xdsClientWrapperForServerSds.getDownstreamTlsContext(channel);
  }

  /** Creates XdsClientWrapperForServerSds: also used by other classes. */
  public static XdsClientWrapperForServerSds createXdsClientWrapperForServerSds(
      int port, DownstreamTlsContext downstreamTlsContext) {
    XdsClient mockXdsClient = mock(XdsClient.class);
    XdsClientWrapperForServerSds xdsClientWrapperForServerSds =
        new XdsClientWrapperForServerSds(port);
    xdsClientWrapperForServerSds.start(mockXdsClient);
    XdsSdsClientServerTest.generateListenerUpdateToWatcher(
        downstreamTlsContext, xdsClientWrapperForServerSds.getListenerWatcher());
    return xdsClientWrapperForServerSds;
  }
}
