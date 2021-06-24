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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.inprocess.InProcessSocketAddress;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.XdsClient.LdsUpdate;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.sds.SslContextProvider;
import io.grpc.xds.internal.sds.SslContextProviderSupplier;
import io.netty.channel.Channel;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
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

  @Mock private Channel channel;
  @Mock private TlsContextManager tlsContextManager;
  @Mock private XdsClientWrapperForServerSds.ServerWatcher mockServerWatcher;

  private XdsClientWrapperForServerSds xdsClientWrapperForServerSds;
  private XdsClient.LdsResourceWatcher registeredWatcher;
  private InetSocketAddress localAddress;
  private DownstreamTlsContext tlsContext1;
  private DownstreamTlsContext tlsContext2;
  private DownstreamTlsContext tlsContext3;

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);
    tlsContext1 =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    tlsContext2 =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    tlsContext3 =
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT3", "VA3");
    xdsClientWrapperForServerSds = XdsServerTestHelper
        .createXdsClientWrapperForServerSds(PORT, tlsContextManager);
  }

  @After
  public void tearDown() {
    xdsClientWrapperForServerSds.shutdown();
  }

  @Test
  public void nonInetSocketAddress_expectNull() throws UnknownHostException {
    registeredWatcher =
        XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds);
    assertThat(
        sendListenerUpdate(new InProcessSocketAddress("test1"), null, null, tlsContextManager))
        .isNull();
  }

  @Test
  public void nonMatchingPort_expectException() throws UnknownHostException {
    registeredWatcher =
        XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds);
    try {
      InetAddress ipLocalAddress = InetAddress.getByName("10.1.2.3");
      InetSocketAddress localAddress = new InetSocketAddress(ipLocalAddress, PORT + 1);
      sendListenerUpdate(localAddress, null, null, tlsContextManager);
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
        XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds);
    InetAddress ipLocalAddress = InetAddress.getByName("10.1.2.3");
    InetSocketAddress localAddress = new InetSocketAddress(ipLocalAddress, PORT);
    ArgumentCaptor<XdsClient.LdsResourceWatcher> listenerWatcherCaptor = ArgumentCaptor
        .forClass(null);
    XdsClient xdsClient = xdsClientWrapperForServerSds.getXdsClient();
    verify(xdsClient)
        .watchLdsResource(eq("grpc/server?udpa.resource.listening_address=0.0.0.0:" + PORT),
            listenerWatcherCaptor.capture());
    XdsClient.LdsResourceWatcher registeredWatcher = listenerWatcherCaptor.getValue();
    when(channel.localAddress()).thenReturn(localAddress);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1",
            "10.1.2.3",
            Collections.<EnvoyServerProtoData.FilterChain>emptyList(),
            null);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    registeredWatcher.onChanged(listenerUpdate);
    DownstreamTlsContext tlsContext = getDownstreamTlsContext();
    assertThat(tlsContext).isNull();
  }

  @Test
  public void registerServerWatcher_afterListenerUpdate() throws UnknownHostException {
    registerWatcherAndCreateListenerUpdate(tlsContext1);
    verify(mockServerWatcher).onListenerUpdate();
  }

  @Test
  public void registerServerWatcher_notifyNotFound() throws UnknownHostException {
    commonErrorCheck(true, Status.NOT_FOUND, true);
  }

  @Test
  public void registerServerWatcher_notifyInternalError() throws UnknownHostException {
    commonErrorCheck(false, Status.INTERNAL, false);
  }

  @Test
  public void registerServerWatcher_notifyPermDeniedError() throws UnknownHostException {
    commonErrorCheck(false, Status.PERMISSION_DENIED, true);
  }

  @Test
  public void releaseOldSupplierOnChanged_noCloseDueToLazyLoading() throws UnknownHostException {
    registerWatcherAndCreateListenerUpdate(tlsContext1);
    XdsServerTestHelper.generateListenerUpdate(registeredWatcher, tlsContext2, tlsContextManager);
    verify(tlsContextManager, never())
        .findOrCreateServerSslContextProvider(any(DownstreamTlsContext.class));
  }

  @Test
  public void releaseOldSupplierOnChangedOnShutdown_verifyClose() throws UnknownHostException {
    SslContextProvider sslContextProvider1 = mock(SslContextProvider.class);
    when(tlsContextManager.findOrCreateServerSslContextProvider(eq(tlsContext1)))
        .thenReturn(sslContextProvider1);
    registerWatcherAndCreateListenerUpdate(tlsContext1);
    callUpdateSslContext(channel);
    XdsServerTestHelper
        .generateListenerUpdate(registeredWatcher, Arrays.<Integer>asList(1234), tlsContext2,
            tlsContext3, tlsContextManager);
    verify(tlsContextManager, times(1)).releaseServerSslContextProvider(eq(sslContextProvider1));
    reset(tlsContextManager);
    SslContextProvider sslContextProvider2 = mock(SslContextProvider.class);
    when(tlsContextManager.findOrCreateServerSslContextProvider(eq(tlsContext2)))
            .thenReturn(sslContextProvider2);
    SslContextProvider sslContextProvider3 = mock(SslContextProvider.class);
    when(tlsContextManager.findOrCreateServerSslContextProvider(eq(tlsContext3)))
            .thenReturn(sslContextProvider3);
    callUpdateSslContext(channel);
    InetAddress ipRemoteAddress = InetAddress.getByName("10.4.5.6");
    InetSocketAddress remoteAddress = new InetSocketAddress(ipRemoteAddress, 1111);
    when(channel.remoteAddress()).thenReturn(remoteAddress);
    callUpdateSslContext(channel);
    XdsClient mockXdsClient = xdsClientWrapperForServerSds.getXdsClient();
    xdsClientWrapperForServerSds.shutdown();
    verify(mockXdsClient, times(1))
        .cancelLdsResourceWatch(eq("grpc/server?udpa.resource.listening_address=0.0.0.0:" + PORT),
            eq(registeredWatcher));
    verify(tlsContextManager, never()).releaseServerSslContextProvider(eq(sslContextProvider1));
    verify(tlsContextManager, times(1)).releaseServerSslContextProvider(eq(sslContextProvider2));
    verify(tlsContextManager, times(1)).releaseServerSslContextProvider(eq(sslContextProvider3));
  }

  @Test
  public void releaseOldSupplierOnNotFound_verifyClose() throws UnknownHostException {
    SslContextProvider sslContextProvider1 = mock(SslContextProvider.class);
    when(tlsContextManager.findOrCreateServerSslContextProvider(eq(tlsContext1)))
            .thenReturn(sslContextProvider1);
    registerWatcherAndCreateListenerUpdate(tlsContext1);
    callUpdateSslContext(channel);
    registeredWatcher.onResourceDoesNotExist("not-found Error");
    verify(tlsContextManager, times(1)).releaseServerSslContextProvider(eq(sslContextProvider1));
  }

  @Test
  public void releaseOldSupplierOnPermDeniedError_verifyClose() throws UnknownHostException {
    SslContextProvider sslContextProvider1 = mock(SslContextProvider.class);
    when(tlsContextManager.findOrCreateServerSslContextProvider(eq(tlsContext1)))
            .thenReturn(sslContextProvider1);
    registerWatcherAndCreateListenerUpdate(tlsContext1);
    callUpdateSslContext(channel);
    registeredWatcher.onError(Status.PERMISSION_DENIED);
    verify(tlsContextManager, times(1)).releaseServerSslContextProvider(eq(sslContextProvider1));
  }

  @Test
  public void releaseOldSupplierOnInternalError_noClose() throws UnknownHostException {
    SslContextProvider sslContextProvider1 = mock(SslContextProvider.class);
    when(tlsContextManager.findOrCreateServerSslContextProvider(eq(tlsContext1)))
            .thenReturn(sslContextProvider1);
    registerWatcherAndCreateListenerUpdate(tlsContext1);
    callUpdateSslContext(channel);
    registeredWatcher.onError(Status.INTERNAL);
    verify(tlsContextManager, never()).releaseServerSslContextProvider(eq(sslContextProvider1));
  }

  private void callUpdateSslContext(Channel channel) {
    SslContextProviderSupplier sslContextProviderSupplier =
        xdsClientWrapperForServerSds.getSslContextProviderSupplier(channel);
    assertThat(sslContextProviderSupplier).isNotNull();
    SslContextProvider.Callback callback = mock(SslContextProvider.Callback.class);
    sslContextProviderSupplier.updateSslContext(callback);
  }

  private void registerWatcherAndCreateListenerUpdate(DownstreamTlsContext tlsContext)
      throws UnknownHostException {
    registeredWatcher =
        XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds);
    InetAddress ipLocalAddress = InetAddress.getByName("10.1.2.3");
    localAddress = new InetSocketAddress(ipLocalAddress, PORT);
    xdsClientWrapperForServerSds.addServerWatcher(mockServerWatcher);
    DownstreamTlsContext returnedTlsContext = sendListenerUpdate(localAddress, tlsContext, null,
        tlsContextManager);
    assertThat(returnedTlsContext).isSameInstanceAs(tlsContext);
  }

  private void commonErrorCheck(boolean generateResourceDoesNotExist, Status status,
      boolean isAbsent) throws UnknownHostException {
    registerWatcherAndCreateListenerUpdate(tlsContext1);
    reset(mockServerWatcher);
    if (generateResourceDoesNotExist) {
      registeredWatcher.onResourceDoesNotExist("not-found Error");
    } else {
      registeredWatcher.onError(status);
    }
    ArgumentCaptor<Throwable> argCaptor = ArgumentCaptor.forClass(null);
    verify(mockServerWatcher).onError(argCaptor.capture(), eq(isAbsent));
    Throwable throwable = argCaptor.getValue();
    assertThat(throwable).isInstanceOf(StatusException.class);
    Status captured = ((StatusException) throwable).getStatus();
    assertThat(captured.getCode()).isEqualTo(status.getCode());
    if (isAbsent) {
      assertThat(xdsClientWrapperForServerSds.getSslContextProviderSupplier(channel)).isNull();
    } else {
      assertThat(xdsClientWrapperForServerSds.getSslContextProviderSupplier(channel)).isNotNull();
    }
  }

  private DownstreamTlsContext sendListenerUpdate(
      SocketAddress localAddress, DownstreamTlsContext tlsContext,
      DownstreamTlsContext tlsContextForDefaultFilterChain, TlsContextManager tlsContextManager)
      throws UnknownHostException {
    when(channel.localAddress()).thenReturn(localAddress);
    InetAddress ipRemoteAddress = InetAddress.getByName("10.4.5.6");
    InetSocketAddress remoteAddress = new InetSocketAddress(ipRemoteAddress, 1234);
    when(channel.remoteAddress()).thenReturn(remoteAddress);
    XdsServerTestHelper
        .generateListenerUpdate(registeredWatcher, Arrays.<Integer>asList(), tlsContext,
            tlsContextForDefaultFilterChain, tlsContextManager);
    return getDownstreamTlsContext();
  }

  private DownstreamTlsContext getDownstreamTlsContext() {
    SslContextProviderSupplier sslContextProviderSupplier =
            xdsClientWrapperForServerSds.getSslContextProviderSupplier(channel);
    if (sslContextProviderSupplier != null) {
      EnvoyServerProtoData.BaseTlsContext tlsContext = sslContextProviderSupplier.getTlsContext();
      assertThat(tlsContext).isInstanceOf(DownstreamTlsContext.class);
      return (DownstreamTlsContext)tlsContext;
    }
    return null;
  }

  /** Creates XdsClientWrapperForServerSds: also used by other classes. */
  public static XdsClientWrapperForServerSds createXdsClientWrapperForServerSds(
      int port, DownstreamTlsContext downstreamTlsContext, TlsContextManager tlsContextManager) {
    XdsClientWrapperForServerSds xdsClientWrapperForServerSds =
        XdsServerTestHelper.createXdsClientWrapperForServerSds(port, tlsContextManager);
    xdsClientWrapperForServerSds.start();
    XdsSdsClientServerTest.generateListenerUpdateToWatcher(
        downstreamTlsContext, xdsClientWrapperForServerSds.getListenerWatcher(), tlsContextManager);
    return xdsClientWrapperForServerSds;
  }
}
