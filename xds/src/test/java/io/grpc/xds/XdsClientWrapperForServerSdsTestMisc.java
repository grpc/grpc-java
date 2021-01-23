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
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
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
  public void nonInetSocketAddress_expectException() {
    registeredWatcher =
            XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds, xdsClient, PORT);
    try {
      DownstreamTlsContext unused =
          sendListenerUpdate(new InProcessSocketAddress("test1"), null, null);
      fail("exception expected");
    } catch (IllegalStateException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("Channel localAddress is expected to be InetSocketAddress");
    }
  }

  @Test
  public void nonMatchingPort_expectException() throws UnknownHostException {
    registeredWatcher =
            XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds, xdsClient, PORT);
    try {
      InetAddress ipLocalAddress = InetAddress.getByName("10.1.2.3");
      InetSocketAddress localAddress = new InetSocketAddress(ipLocalAddress, PORT + 1);
      DownstreamTlsContext unused = sendListenerUpdate(localAddress, null, null);
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
            "listener1", "10.1.2.3", Collections.<EnvoyServerProtoData.FilterChain>emptyList());
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
        .onSuccess(any(EnvoyServerProtoData.DownstreamTlsContext.class));
    DownstreamTlsContext returnedTlsContext = sendListenerUpdate(localAddress, tlsContext, null);
    assertThat(returnedTlsContext).isSameInstanceAs(tlsContext);
    verify(mockServerWatcher).onSuccess(same(tlsContext));
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
    DownstreamTlsContext returnedTlsContext = sendListenerUpdate(localAddress, tlsContext, null);
    assertThat(returnedTlsContext).isSameInstanceAs(tlsContext);
    XdsClientWrapperForServerSds.ServerWatcher mockServerWatcher =
            mock(XdsClientWrapperForServerSds.ServerWatcher.class);
    xdsClientWrapperForServerSds.addServerWatcher(mockServerWatcher);
    verify(mockServerWatcher).onSuccess(same(tlsContext));
  }

  @Test
  public void registerServerWatcher_notifyError() throws UnknownHostException {
    registeredWatcher =
            XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds, xdsClient, PORT);
    XdsClientWrapperForServerSds.ServerWatcher mockServerWatcher =
        mock(XdsClientWrapperForServerSds.ServerWatcher.class);
    xdsClientWrapperForServerSds.addServerWatcher(mockServerWatcher);
    registeredWatcher.onError(Status.INTERNAL);
    verify(mockServerWatcher).onError(eq(Status.INTERNAL));
    reset(mockServerWatcher);
    registeredWatcher.onResourceDoesNotExist("not-found Error");
    ArgumentCaptor<Status> argCaptor = ArgumentCaptor.forClass(null);
    verify(mockServerWatcher).onError(argCaptor.capture());
    Status captured = argCaptor.getValue();
    assertThat(captured.getCode()).isEqualTo(Status.Code.NOT_FOUND);
    assertThat(captured.getDescription()).isEqualTo("not-found Error");
    InetAddress ipLocalAddress = InetAddress.getByName("10.1.2.3");
    InetSocketAddress localAddress = new InetSocketAddress(ipLocalAddress, PORT);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    verify(mockServerWatcher, never())
        .onSuccess(any(EnvoyServerProtoData.DownstreamTlsContext.class));
    DownstreamTlsContext returnedTlsContext = sendListenerUpdate(localAddress, tlsContext, null);
    assertThat(returnedTlsContext).isSameInstanceAs(tlsContext);
    verify(mockServerWatcher).onSuccess(same(tlsContext));
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
    ArgumentCaptor<Status> argCaptor = ArgumentCaptor.forClass(null);
    verify(mockServerWatcher).onError(argCaptor.capture());
    Status captured = argCaptor.getValue();
    assertThat(captured.getCode()).isEqualTo(Status.Code.UNKNOWN);
    assertThat(captured.getCause()).isInstanceOf(XdsInitializationException.class);
    assertThat(captured.getCause())
        .hasMessageThat()
        .contains("Cannot find bootstrap configuration");
  }

  private DownstreamTlsContext sendListenerUpdate(
      SocketAddress localAddress,
      DownstreamTlsContext tlsContext1,
      DownstreamTlsContext tlsContext2) {
    when(channel.localAddress()).thenReturn(localAddress);
    XdsServerTestHelper.generateListenerUpdate(
        registeredWatcher, PORT, tlsContext1, tlsContext2);
    return xdsClientWrapperForServerSds.getDownstreamTlsContext(channel);
  }
}
