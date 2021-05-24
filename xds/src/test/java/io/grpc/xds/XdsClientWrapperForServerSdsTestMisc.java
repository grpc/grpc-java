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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.inprocess.InProcessSocketAddress;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.GrpcAuthorizationEngine.Action;
import io.grpc.xds.GrpcAuthorizationEngine.AlwaysTrueMatcher;
import io.grpc.xds.GrpcAuthorizationEngine.AuthConfig;
import io.grpc.xds.GrpcAuthorizationEngine.DestinationIpMatcher;
import io.grpc.xds.GrpcAuthorizationEngine.DestinationPortMatcher;
import io.grpc.xds.GrpcAuthorizationEngine.HeaderMatcher;
import io.grpc.xds.GrpcAuthorizationEngine.OrMatcher;
import io.grpc.xds.GrpcAuthorizationEngine.PathMatcher;
import io.grpc.xds.GrpcAuthorizationEngine.PolicyMatcher;
import io.grpc.xds.XdsClientWrapperForServerSds.RbacServerInterceptor;
import io.grpc.xds.internal.Matchers;
import io.grpc.xds.internal.Matchers.CidrMatcher;
import io.grpc.xds.internal.Matchers.StringMatcher;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.netty.channel.Channel;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

  private XdsClientWrapperForServerSds xdsClientWrapperForServerSds;
  private XdsClient.LdsResourceWatcher registeredWatcher;

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);
    xdsClientWrapperForServerSds = XdsServerTestHelper
        .createXdsClientWrapperForServerSds(PORT, null);
  }

  @After
  public void tearDown() {
    xdsClientWrapperForServerSds.shutdown();
  }

  @Test
  public void nonInetSocketAddress_expectNull() throws UnknownHostException {
    registeredWatcher =
        XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds);
    assertThat(sendListenerUpdate(new InProcessSocketAddress("test1"), null)).isNull();
  }

  @Test
  public void nonMatchingPort_expectException() throws UnknownHostException {
    registeredWatcher =
        XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds);
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
    XdsClient.LdsUpdate listenerUpdate = new XdsClient.LdsUpdate(listener);
    registeredWatcher.onChanged(listenerUpdate);
    DownstreamTlsContext tlsContext = xdsClientWrapperForServerSds.getDownstreamTlsContext(channel);
    assertThat(tlsContext).isNull();
  }

  @Test
  public void registerServerWatcher() throws UnknownHostException {
    registeredWatcher =
            XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds);
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
            XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds);
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
            XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds);
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
      int port, DownstreamTlsContext downstreamTlsContext, TlsContextManager tlsContextManager) {
    XdsClientWrapperForServerSds xdsClientWrapperForServerSds =
        XdsServerTestHelper.createXdsClientWrapperForServerSds(port, tlsContextManager);
    xdsClientWrapperForServerSds.start();
    XdsSdsClientServerTest.generateListenerUpdateToWatcher(
        downstreamTlsContext, xdsClientWrapperForServerSds.getListenerWatcher());
    return xdsClientWrapperForServerSds;
  }


  @SuppressWarnings("unchecked")
  @Test
  public void testAuthorizationInterceptor_Allow() throws Exception {
    ServerCallHandler<Void, Void> mockHandler = mock(ServerCallHandler.class);
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("header-key", Metadata.ASCII_STRING_MARSHALLER), "header-value");
    ServerCall<Void, Void> mockServerCall = mock(ServerCall.class);
    String hostname = "1.1.1.1";
    Attributes attr = Attributes.newBuilder()
        .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, new InetSocketAddress("10.10.10.10", 10))
        .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, new InetSocketAddress(hostname, 20))
        .build();
    when(mockServerCall.getAttributes()).thenReturn(attr);
    when(mockServerCall.getMethodDescriptor()).thenReturn(method());

    DestinationIpMatcher destIpMatcher = new DestinationIpMatcher(CidrMatcher.create(hostname, 24));
    PathMatcher pathMatcher = new PathMatcher(StringMatcher.forExact("/path", false));
    OrMatcher principal = OrMatcher.create(pathMatcher);
    OrMatcher permission = OrMatcher.create(destIpMatcher);
    PolicyMatcher policyMatcher = new PolicyMatcher("policy-matcher", permission, principal);
    AuthConfig authconfig =  new AuthConfig(Collections.singletonList(policyMatcher), Action.ALLOW);
    xdsClientWrapperForServerSds.authInterceptorChain = ImmutableSet.of(
        xdsClientWrapperForServerSds.generateAuthorizationInterceptor(authconfig));
    RbacServerInterceptor interceptor = xdsClientWrapperForServerSds.rbacServerInterceptor;
    interceptor.interceptCall(mockServerCall, headers, mockHandler);
    verify(mockHandler).startCall(mockServerCall, headers);
    verify(mockServerCall).getMethodDescriptor();
    verify(mockServerCall).getAttributes();

    DestinationPortMatcher destinationPortMatcher = new DestinationPortMatcher(99999);
    policyMatcher = new PolicyMatcher("policy-matcher",
        OrMatcher.create(destinationPortMatcher), principal);
    authconfig =  new AuthConfig(Collections.singletonList(policyMatcher), Action.DENY);
    xdsClientWrapperForServerSds.authInterceptorChain = ImmutableSet.of(
        xdsClientWrapperForServerSds.generateAuthorizationInterceptor(authconfig));
    interceptor.interceptCall(mockServerCall, headers, mockHandler);
    verify(mockHandler, times(2)).startCall(mockServerCall, headers);
  }

  @SuppressWarnings("unchecked")
  @Test(expected = UnsupportedOperationException.class)
  public void testAuthorizationInterceptor_Deny() throws Exception {
    ServerCallHandler<Void, Void> mockHandler = mock(ServerCallHandler.class);
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("header-key", Metadata.ASCII_STRING_MARSHALLER), "header-value");
    ServerCall<Void, Void> mockServerCall = mock(ServerCall.class);
    String hostname = "1.1.1.1";
    Attributes attr = Attributes.newBuilder()
        .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, new InetSocketAddress("10.10.10.10", 10))
        .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, new InetSocketAddress(hostname, 20))
        .build();
    when(mockServerCall.getAttributes()).thenReturn(attr);
    when(mockServerCall.getMethodDescriptor()).thenReturn(method());

    DestinationIpMatcher destIpMatcher = new DestinationIpMatcher(CidrMatcher.create(hostname, 24));
    PathMatcher pathMatcher = new PathMatcher(StringMatcher.forExact("/path/", false));
    OrMatcher principal = OrMatcher.create(pathMatcher);
    OrMatcher permission = OrMatcher.create(destIpMatcher);
    PolicyMatcher policyMatcher = new PolicyMatcher("policy-matcher", permission, principal);
    AuthConfig authconfig =  new AuthConfig(Collections.singletonList(policyMatcher), Action.ALLOW);
    xdsClientWrapperForServerSds.authInterceptorChain = ImmutableSet.of(
        xdsClientWrapperForServerSds.generateAuthorizationInterceptor(authconfig));
    RbacServerInterceptor interceptor = xdsClientWrapperForServerSds.rbacServerInterceptor;
    interceptor.interceptCall(mockServerCall, headers, mockHandler);
    verify(mockHandler, never()).startCall(mockServerCall, headers);
    verify(mockServerCall).getMethodDescriptor();
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testAuthorizationInterceptor_MultipleInterceptors() throws Exception {
    ServerCallHandler<Void, Void> mockHandler = mock(ServerCallHandler.class);
    Metadata headers = new Metadata();
    ServerCall<Void, Void> mockServerCall = mock(ServerCall.class);
    String hostname = "1.1.1.1";
    Attributes attr = Attributes.newBuilder()
        .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, new InetSocketAddress("10.10.10.10", 10))
        .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, new InetSocketAddress(hostname, 20))
        .build();
    when(mockServerCall.getAttributes()).thenReturn(attr);
    when(mockServerCall.getMethodDescriptor()).thenReturn(method());

    DestinationIpMatcher destIpMatcher = new DestinationIpMatcher(CidrMatcher.create(hostname, 24));
    PathMatcher pathMatcher = new PathMatcher(StringMatcher.forPrefix("/path", false));
    OrMatcher principal = OrMatcher.create(pathMatcher);
    OrMatcher permission = OrMatcher.create(destIpMatcher);
    PolicyMatcher policyMatcher = new PolicyMatcher("policy-matcher", permission, principal);
    PolicyMatcher policyMatcher2 = new PolicyMatcher("policy-matcher-2",
        OrMatcher.create(new PathMatcher(StringMatcher.forExact("/path/block", false))),
        OrMatcher.create(AlwaysTrueMatcher.INSTANCE));
    ServerInterceptor interceptor1 = xdsClientWrapperForServerSds.generateAuthorizationInterceptor(
        new AuthConfig(Collections.singletonList(policyMatcher), Action.ALLOW));
    ServerInterceptor interceptor2 = xdsClientWrapperForServerSds.generateAuthorizationInterceptor(
        new AuthConfig(Collections.singletonList(policyMatcher2), Action.DENY));

    xdsClientWrapperForServerSds.authInterceptorChain = ImmutableSet.of(interceptor1, interceptor2);
    RbacServerInterceptor interceptor = xdsClientWrapperForServerSds.rbacServerInterceptor;
    interceptor.interceptCall(mockServerCall, headers, mockHandler);
    verify(mockHandler).startCall(mockServerCall, headers);
    verify(mockServerCall, times(2)).getMethodDescriptor();
    verify(mockServerCall).getAttributes();
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testAuthorizationInterceptor_ConcurrentConfigUpdate() throws Exception {
    ServerCallHandler<Void, Void> mockHandler = mock(ServerCallHandler.class);
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("header-key", Metadata.ASCII_STRING_MARSHALLER), "header-value");
    ServerCall<Void, Void> mockServerCall = mock(ServerCall.class);
    String hostname = "1.1.1.1";
    Attributes attr = Attributes.newBuilder()
        .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, new InetSocketAddress("10.10.10.10", 10))
        .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, new InetSocketAddress(hostname, 20))
        .build();
    when(mockServerCall.getAttributes()).thenReturn(attr);
    when(mockServerCall.getMethodDescriptor()).thenReturn(method());

    PolicyMatcher policyMatcher = new PolicyMatcher("policy-matcher",
        OrMatcher.create(new DestinationIpMatcher(CidrMatcher.create(hostname, 24))),
        OrMatcher.create(new PathMatcher(StringMatcher.forExact("/path", false)) ));
    AuthConfig authconfig = new AuthConfig(Collections.singletonList(policyMatcher), Action.ALLOW);
    xdsClientWrapperForServerSds.authInterceptorChain = ImmutableSet.of(
        xdsClientWrapperForServerSds.generateAuthorizationInterceptor(authconfig));
    RbacServerInterceptor interceptor = xdsClientWrapperForServerSds.rbacServerInterceptor;
    interceptor.interceptCall(mockServerCall, headers, mockHandler);
    verify(mockHandler).startCall(mockServerCall, headers);
    verify(mockServerCall).getMethodDescriptor();
    verify(mockServerCall).getAttributes();

    final CountDownLatch latch = new CountDownLatch(1);
    new Thread(new Runnable() {
      @Override
      public void run() {
        PolicyMatcher policyMatcher = new PolicyMatcher("policy-matcher-new",
            OrMatcher.create(new HeaderMatcher(
                Matchers.HeaderMatcher.forExactValue("header-key", "wrong", false))),
            OrMatcher.create(new PathMatcher(StringMatcher.forExact("/path", false))));
        AuthConfig config = new AuthConfig(Collections.singletonList(policyMatcher), Action.DENY);
        xdsClientWrapperForServerSds.authInterceptorChain = ImmutableSet.of(
            xdsClientWrapperForServerSds.generateAuthorizationInterceptor(config));
        latch.countDown();
      }
    }).start();
    latch.await(5, TimeUnit.SECONDS);
    interceptor.interceptCall(mockServerCall, headers, mockHandler);
    verify(mockHandler, times(2)).startCall(mockServerCall, headers);
    verify(mockServerCall, times(1)).getMethodDescriptor();
    verify(mockServerCall, times(1)).getAttributes();
  }

  private MethodDescriptor<Void, Void> method() {
    return MethodDescriptor.<Void,Void>newBuilder()
        .setType(MethodType.BIDI_STREAMING)
        .setFullMethodName("/path")
        .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
        .setResponseMarshaller(TestMethodDescriptors.voidMarshaller()).build();
  }
}
