/*
 * Copyright 2021 The gRPC Authors
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
import static org.mockito.Mockito.when;

import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.sds.SslContextProviderSupplier;
import io.netty.channel.Channel;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link XdsClientWrapperForServerSds}. */
@RunWith(JUnit4.class)
public class FilterChainMatchTest {

  private static final int PORT = 7000;
  private static final String LOCAL_IP = "10.1.2.3";  // dest
  private static final String REMOTE_IP = "10.4.2.3"; // source

  @Mock private Channel channel;
  @Mock private TlsContextManager tlsContextManager;

  private XdsClientWrapperForServerSds xdsClientWrapperForServerSds;
  private XdsClient.LdsResourceWatcher registeredWatcher;

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);
    xdsClientWrapperForServerSds = XdsServerTestHelper
        .createXdsClientWrapperForServerSds(PORT, tlsContextManager);
    registeredWatcher =
            XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds);
  }

  @After
  public void tearDown() {
    xdsClientWrapperForServerSds.shutdown();
  }

  private DownstreamTlsContext getDownstreamTlsContext() {
    SslContextProviderSupplier sslContextProviderSupplier =
        xdsClientWrapperForServerSds.getSslContextProviderSupplier(channel);
    if (sslContextProviderSupplier != null) {
      EnvoyServerProtoData.BaseTlsContext tlsContext = sslContextProviderSupplier.getTlsContext();
      assertThat(tlsContext).isInstanceOf(DownstreamTlsContext.class);
      return (DownstreamTlsContext) tlsContext;
    }
    return null;
  }

  @Test
  public void singleFilterChainWithoutAlpn() throws UnknownHostException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    EnvoyServerProtoData.FilterChainMatch filterChainMatch =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    DownstreamTlsContext tlsContext =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChain filterChain =
        new EnvoyServerProtoData.FilterChain(filterChainMatch, tlsContext, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener("listener1", LOCAL_IP, Arrays.asList(filterChain), null);
    XdsClient.LdsUpdate listenerUpdate = new XdsClient.LdsUpdate(listener);
    registeredWatcher.onChanged(listenerUpdate);
    DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContext);
  }

  @Test
  public void singleFilterChainWithAlpn() throws UnknownHostException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    EnvoyServerProtoData.FilterChainMatch filterChainMatch =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.asList("managed-mtls"),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    DownstreamTlsContext tlsContext =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChain filterChain =
        new EnvoyServerProtoData.FilterChain(filterChainMatch, tlsContext, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener("listener1", LOCAL_IP, Arrays.asList(filterChain), null);
    XdsClient.LdsUpdate listenerUpdate = new XdsClient.LdsUpdate(listener);
    registeredWatcher.onChanged(listenerUpdate);
    DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContext);
  }

  @Test
  public void defaultFilterChain() throws UnknownHostException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    DownstreamTlsContext tlsContext =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChain filterChain =
        new EnvoyServerProtoData.FilterChain(null, tlsContext, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1", LOCAL_IP, Arrays.<EnvoyServerProtoData.FilterChain>asList(), filterChain);
    XdsClient.LdsUpdate listenerUpdate = new XdsClient.LdsUpdate(listener);
    registeredWatcher.onChanged(listenerUpdate);
    DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContext);
  }

  @Test
  public void destPortFails_returnDefaultFilterChain() throws UnknownHostException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    DownstreamTlsContext tlsContextWithDestPort =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchWithDestPort =
        new EnvoyServerProtoData.FilterChainMatch(
            PORT,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.asList("managed-mtls"),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChainWithDestPort =
        new EnvoyServerProtoData.FilterChain(filterChainMatchWithDestPort, tlsContextWithDestPort,
            tlsContextManager);
    DownstreamTlsContext tlsContextForDefaultFilterChain =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChain defaultFilterChain =
        new EnvoyServerProtoData.FilterChain(null, tlsContextForDefaultFilterChain,
            tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1", LOCAL_IP, Arrays.asList(filterChainWithDestPort), defaultFilterChain);
    XdsClient.LdsUpdate listenerUpdate = new XdsClient.LdsUpdate(listener);
    registeredWatcher.onChanged(listenerUpdate);
    DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextForDefaultFilterChain);
  }

  @Test
  public void destPrefixRangeMatch() throws UnknownHostException, InvalidProtocolBufferException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    DownstreamTlsContext tlsContextMatch =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchWithMatch =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.1.2.0", 24)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChainWithMatch =
        new EnvoyServerProtoData.FilterChain(filterChainMatchWithMatch, tlsContextMatch,
            tlsContextManager);
    DownstreamTlsContext tlsContextForDefaultFilterChain =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChain defaultFilterChain =
        new EnvoyServerProtoData.FilterChain(null, tlsContextForDefaultFilterChain,
            tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1", LOCAL_IP, Arrays.asList(filterChainWithMatch), defaultFilterChain);
    XdsClient.LdsUpdate listenerUpdate = new XdsClient.LdsUpdate(listener);
    registeredWatcher.onChanged(listenerUpdate);
    DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextMatch);
  }

  @Test
  public void destPrefixRangeMismatch_returnDefaultFilterChain()
      throws UnknownHostException, InvalidProtocolBufferException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    DownstreamTlsContext tlsContextMismatch =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    // 10.2.2.0/24 doesn't match LOCAL_IP
    EnvoyServerProtoData.FilterChainMatch filterChainMatchWithMismatch =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.2.2.0", 24)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChainWithMismatch =
        new EnvoyServerProtoData.FilterChain(filterChainMatchWithMismatch, tlsContextMismatch,
            tlsContextManager);
    DownstreamTlsContext tlsContextForDefaultFilterChain =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChain defaultFilterChain =
        new EnvoyServerProtoData.FilterChain(null, tlsContextForDefaultFilterChain,
            tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1", LOCAL_IP, Arrays.asList(filterChainWithMismatch), defaultFilterChain);
    XdsClient.LdsUpdate listenerUpdate = new XdsClient.LdsUpdate(listener);
    registeredWatcher.onChanged(listenerUpdate);
    DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextForDefaultFilterChain);
  }

  @Test
  public void dest0LengthPrefixRange()
      throws UnknownHostException, InvalidProtocolBufferException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    DownstreamTlsContext tlsContext0Length =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    // 10.2.2.0/24 doesn't match LOCAL_IP
    EnvoyServerProtoData.FilterChainMatch filterChainMatch0Length =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.2.2.0", 0)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChain0Length =
        new EnvoyServerProtoData.FilterChain(filterChainMatch0Length, tlsContext0Length,
            tlsContextManager);
    DownstreamTlsContext tlsContextForDefaultFilterChain =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChain defaultFilterChain =
        new EnvoyServerProtoData.FilterChain(null, tlsContextForDefaultFilterChain,
            tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1", LOCAL_IP, Arrays.asList(filterChain0Length), defaultFilterChain);
    XdsClient.LdsUpdate listenerUpdate = new XdsClient.LdsUpdate(listener);
    registeredWatcher.onChanged(listenerUpdate);
    DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContext0Length);
  }

  @Test
  public void destPrefixRange_moreSpecificWins()
      throws UnknownHostException, InvalidProtocolBufferException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    DownstreamTlsContext tlsContextLessSpecific =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchLessSpecific =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.1.2.0", 24)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChainLessSpecific =
        new EnvoyServerProtoData.FilterChain(filterChainMatchLessSpecific, tlsContextLessSpecific,
            tlsContextManager);

    DownstreamTlsContext tlsContextMoreSpecific =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchMoreSpecific =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.1.2.2", 31)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChainMoreSpecific =
        new EnvoyServerProtoData.FilterChain(filterChainMatchMoreSpecific, tlsContextMoreSpecific,
            tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain =
        new EnvoyServerProtoData.FilterChain(null, null, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1",
            LOCAL_IP,
            Arrays.asList(filterChainLessSpecific, filterChainMoreSpecific),
            defaultFilterChain);
    XdsClient.LdsUpdate listenerUpdate = new XdsClient.LdsUpdate(listener);
    registeredWatcher.onChanged(listenerUpdate);
    DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextMoreSpecific);
  }

  @Test
  public void destPrefixRange_emptyListLessSpecific()
      throws UnknownHostException, InvalidProtocolBufferException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    DownstreamTlsContext tlsContextLessSpecific =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchLessSpecific =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChainLessSpecific =
        new EnvoyServerProtoData.FilterChain(filterChainMatchLessSpecific, tlsContextLessSpecific,
            tlsContextManager);

    DownstreamTlsContext tlsContextMoreSpecific =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchMoreSpecific =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("8.0.0.0", 5)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChainMoreSpecific =
        new EnvoyServerProtoData.FilterChain(filterChainMatchMoreSpecific, tlsContextMoreSpecific,
            tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain =
        new EnvoyServerProtoData.FilterChain(null, null, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1",
            LOCAL_IP,
            Arrays.asList(filterChainLessSpecific, filterChainMoreSpecific),
            defaultFilterChain);
    XdsClient.LdsUpdate listenerUpdate = new XdsClient.LdsUpdate(listener);
    registeredWatcher.onChanged(listenerUpdate);
    DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextMoreSpecific);
  }

  @Test
  public void destPrefixRangeIpv6_moreSpecificWins()
      throws UnknownHostException, InvalidProtocolBufferException {
    setupChannel("FE80:0000:0000:0000:0202:B3FF:FE1E:8329", "2001:DB8::8:800:200C:417A", 15000);
    DownstreamTlsContext tlsContextLessSpecific =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchLessSpecific =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("FE80:0:0:0:0:0:0:0", 60)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChainLessSpecific =
        new EnvoyServerProtoData.FilterChain(filterChainMatchLessSpecific, tlsContextLessSpecific,
            tlsContextManager);

    DownstreamTlsContext tlsContextMoreSpecific =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchMoreSpecific =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("FE80:0000:0000:0000:0202:0:0:0", 80)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChainMoreSpecific =
        new EnvoyServerProtoData.FilterChain(filterChainMatchMoreSpecific, tlsContextMoreSpecific,
            tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain =
        new EnvoyServerProtoData.FilterChain(null, null, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1",
            "FE80:0000:0000:0000:0202:B3FF:FE1E:8329",
            Arrays.asList(filterChainLessSpecific, filterChainMoreSpecific),
            defaultFilterChain);
    XdsClient.LdsUpdate listenerUpdate = new XdsClient.LdsUpdate(listener);
    registeredWatcher.onChanged(listenerUpdate);
    DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextMoreSpecific);
  }

  @Test
  public void destPrefixRange_moreSpecificWith2Wins()
      throws UnknownHostException, InvalidProtocolBufferException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    DownstreamTlsContext tlsContextMoreSpecificWith2 =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchMoreSpecificWith2 =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(
                new EnvoyServerProtoData.CidrRange("10.1.2.0", 24),
                new EnvoyServerProtoData.CidrRange(LOCAL_IP, 32)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChainMoreSpecificWith2 =
        new EnvoyServerProtoData.FilterChain(
            filterChainMatchMoreSpecificWith2, tlsContextMoreSpecificWith2, tlsContextManager);

    DownstreamTlsContext tlsContextLessSpecific =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchLessSpecific =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.1.2.2", 31)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChainLessSpecific =
        new EnvoyServerProtoData.FilterChain(filterChainMatchLessSpecific, tlsContextLessSpecific,
            tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain =
        new EnvoyServerProtoData.FilterChain(null, null, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1",
            LOCAL_IP,
            Arrays.asList(filterChainMoreSpecificWith2, filterChainLessSpecific),
            defaultFilterChain);
    XdsClient.LdsUpdate listenerUpdate = new XdsClient.LdsUpdate(listener);
    registeredWatcher.onChanged(listenerUpdate);
    DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextMoreSpecificWith2);
  }

  @Test
  public void sourceTypeMismatch_returnDefaultFilterChain() throws UnknownHostException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    DownstreamTlsContext tlsContextMismatch =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchWithMismatch =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.SAME_IP_OR_LOOPBACK,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChainWithMismatch =
        new EnvoyServerProtoData.FilterChain(filterChainMatchWithMismatch, tlsContextMismatch,
            tlsContextManager);
    DownstreamTlsContext tlsContextForDefaultFilterChain =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChain defaultFilterChain =
        new EnvoyServerProtoData.FilterChain(null, tlsContextForDefaultFilterChain,
            tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1", LOCAL_IP, Arrays.asList(filterChainWithMismatch), defaultFilterChain);
    XdsClient.LdsUpdate listenerUpdate = new XdsClient.LdsUpdate(listener);
    registeredWatcher.onChanged(listenerUpdate);
    DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextForDefaultFilterChain);
  }

  @Test
  public void sourceTypeLocal() throws UnknownHostException {
    setupChannel(LOCAL_IP, LOCAL_IP, 15000);
    DownstreamTlsContext tlsContextMatch =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchWithMatch =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.SAME_IP_OR_LOOPBACK,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChainWithMatch =
        new EnvoyServerProtoData.FilterChain(filterChainMatchWithMatch, tlsContextMatch,
            tlsContextManager);
    DownstreamTlsContext tlsContextForDefaultFilterChain =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChain defaultFilterChain =
        new EnvoyServerProtoData.FilterChain(null, tlsContextForDefaultFilterChain,
            tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1", LOCAL_IP, Arrays.asList(filterChainWithMatch), defaultFilterChain);
    XdsClient.LdsUpdate listenerUpdate = new XdsClient.LdsUpdate(listener);
    registeredWatcher.onChanged(listenerUpdate);
    DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextMatch);
  }

  @Test
  public void sourcePrefixRange_moreSpecificWith2Wins()
      throws UnknownHostException, InvalidProtocolBufferException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    DownstreamTlsContext tlsContextMoreSpecificWith2 =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchMoreSpecificWith2 =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.asList(
                new EnvoyServerProtoData.CidrRange("10.4.2.0", 24),
                new EnvoyServerProtoData.CidrRange(REMOTE_IP, 32)),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChainMoreSpecificWith2 =
        new EnvoyServerProtoData.FilterChain(
            filterChainMatchMoreSpecificWith2, tlsContextMoreSpecificWith2, tlsContextManager);

    DownstreamTlsContext tlsContextLessSpecific =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchLessSpecific =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.4.2.2", 31)),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChainLessSpecific =
        new EnvoyServerProtoData.FilterChain(filterChainMatchLessSpecific, tlsContextLessSpecific,
            tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain =
        new EnvoyServerProtoData.FilterChain(null, null, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1",
            LOCAL_IP,
            Arrays.asList(filterChainMoreSpecificWith2, filterChainLessSpecific),
            defaultFilterChain);
    XdsClient.LdsUpdate listenerUpdate = new XdsClient.LdsUpdate(listener);
    registeredWatcher.onChanged(listenerUpdate);
    DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextMoreSpecificWith2);
  }

  @Test
  public void sourcePrefixRange_2Matchers_expectException()
      throws UnknownHostException, InvalidProtocolBufferException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    DownstreamTlsContext tlsContext1 =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatch1 =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.asList(
                new EnvoyServerProtoData.CidrRange("10.4.2.0", 24),
                new EnvoyServerProtoData.CidrRange("192.168.10.2", 32)),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChain1 =
        new EnvoyServerProtoData.FilterChain(filterChainMatch1, tlsContext1, tlsContextManager);

    DownstreamTlsContext tlsContext2 =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatch2 =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.4.2.0", 24)),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChain2 =
        new EnvoyServerProtoData.FilterChain(filterChainMatch2, tlsContext2, tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain =
        new EnvoyServerProtoData.FilterChain(null, null, null);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1", LOCAL_IP, Arrays.asList(filterChain1, filterChain2), defaultFilterChain);
    XdsClient.LdsUpdate listenerUpdate = new XdsClient.LdsUpdate(listener);
    registeredWatcher.onChanged(listenerUpdate);
    try {
      xdsClientWrapperForServerSds.getSslContextProviderSupplier(channel);
      fail("expect exception!");
    } catch (IllegalStateException ise) {
      assertThat(ise).hasMessageThat().isEqualTo("Found 2 matching filter-chains");
    }
  }

  @Test
  public void sourcePortMatch_exactMatchWinsOverEmptyList()
      throws UnknownHostException, InvalidProtocolBufferException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    DownstreamTlsContext tlsContextEmptySourcePorts =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchEmptySourcePorts =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.asList(
                new EnvoyServerProtoData.CidrRange("10.4.2.0", 24),
                new EnvoyServerProtoData.CidrRange("10.4.2.2", 31)),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChainEmptySourcePorts =
        new EnvoyServerProtoData.FilterChain(
            filterChainMatchEmptySourcePorts, tlsContextEmptySourcePorts, tlsContextManager);

    DownstreamTlsContext tlsContextSourcePortMatch =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchSourcePortMatch =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.4.2.2", 31)),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.asList(7000, 15000));
    EnvoyServerProtoData.FilterChain filterChainSourcePortMatch =
        new EnvoyServerProtoData.FilterChain(
            filterChainMatchSourcePortMatch, tlsContextSourcePortMatch, tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain =
        new EnvoyServerProtoData.FilterChain(null, null, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1",
            LOCAL_IP,
            Arrays.asList(filterChainEmptySourcePorts, filterChainSourcePortMatch),
            defaultFilterChain);
    XdsClient.LdsUpdate listenerUpdate = new XdsClient.LdsUpdate(listener);
    registeredWatcher.onChanged(listenerUpdate);
    DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextSourcePortMatch);
  }

  /**
   * Create 6 filterChains: - 1st filter chain has dest port & specific prefix range but is
   * eliminated due to dest port - 5 advance to next step: 1 is eliminated due to being less
   * specific than the remaining 4. - 4 advance to 3rd step: source type external eliminates one
   * with local source_type. - 3 advance to 4th step: more specific 2 get picked based on
   * source-prefix range. - 5th step: out of 2 one with matching source port gets picked
   */
  @Test
  public void filterChain_5stepMatch() throws UnknownHostException, InvalidProtocolBufferException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    DownstreamTlsContext tlsContext1 =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    DownstreamTlsContext tlsContext2 =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    DownstreamTlsContext tlsContext3 =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT3", "VA3");
    DownstreamTlsContext tlsContext4 =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT4", "VA4");
    DownstreamTlsContext tlsContext5 =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT5", "VA5");
    DownstreamTlsContext tlsContext6 =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT6", "VA6");

    // has dest port and specific prefix ranges: gets eliminated in step 1
    EnvoyServerProtoData.FilterChainMatch filterChainMatch1 =
        new EnvoyServerProtoData.FilterChainMatch(
            PORT,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.asList(new EnvoyServerProtoData.CidrRange(REMOTE_IP, 32)),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChain1 =
        new EnvoyServerProtoData.FilterChain(filterChainMatch1, tlsContext1, tlsContextManager);

    // next 5 use prefix range: 4 with prefixLen of 30 and last one with 29

    // has single prefix range: and less specific source prefix range: gets eliminated in step 4
    EnvoyServerProtoData.FilterChainMatch filterChainMatch2 =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.1.2.0", 30)),
            Arrays.<String>asList(),
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.4.0.0", 16)),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChain2 =
        new EnvoyServerProtoData.FilterChain(filterChainMatch2, tlsContext2, tlsContextManager);

    // has prefix ranges with one not matching and source type local: gets eliminated in step 3
    EnvoyServerProtoData.FilterChainMatch filterChainMatch3 =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(
                new EnvoyServerProtoData.CidrRange("192.168.2.0", 24),
                new EnvoyServerProtoData.CidrRange("10.1.2.0", 30)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.SAME_IP_OR_LOOPBACK,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChain3 =
        new EnvoyServerProtoData.FilterChain(filterChainMatch3, tlsContext3, tlsContextManager);

    // has prefix ranges with both matching and source type external but non matching source port:
    // gets eliminated in step 5
    EnvoyServerProtoData.FilterChainMatch filterChainMatch4 =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(
                new EnvoyServerProtoData.CidrRange("10.1.0.0", 16),
                new EnvoyServerProtoData.CidrRange("10.1.2.0", 30)),
            Arrays.<String>asList(),
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.4.2.0", 24)),
            EnvoyServerProtoData.ConnectionSourceType.EXTERNAL,
            Arrays.asList(16000, 9000));
    EnvoyServerProtoData.FilterChain filterChain4 =
        new EnvoyServerProtoData.FilterChain(filterChainMatch4, tlsContext4, tlsContextManager);

    // has prefix ranges with both matching and source type external and matching source port: this
    // gets selected
    EnvoyServerProtoData.FilterChainMatch filterChainMatch5 =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(
                new EnvoyServerProtoData.CidrRange("10.1.0.0", 16),
                new EnvoyServerProtoData.CidrRange("10.1.2.0", 30)),
            Arrays.<String>asList(),
            Arrays.asList(
                new EnvoyServerProtoData.CidrRange("10.4.2.0", 24),
                new EnvoyServerProtoData.CidrRange("192.168.2.0", 24)),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.asList(15000, 8000));
    EnvoyServerProtoData.FilterChain filterChain5 =
        new EnvoyServerProtoData.FilterChain(filterChainMatch5, tlsContext5, tlsContextManager);

    // has prefix range with prefixLen of 29: gets eliminated in step 2
    EnvoyServerProtoData.FilterChainMatch filterChainMatch6 =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.1.2.0", 29)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChain6 =
        new EnvoyServerProtoData.FilterChain(filterChainMatch6, tlsContext6, tlsContextManager);

    EnvoyServerProtoData.FilterChain defaultFilterChain =
        new EnvoyServerProtoData.FilterChain(null, null, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1",
            LOCAL_IP,
            Arrays.asList(
                filterChain1, filterChain2, filterChain3, filterChain4, filterChain5, filterChain6),
            defaultFilterChain);
    XdsClient.LdsUpdate listenerUpdate = new XdsClient.LdsUpdate(listener);
    registeredWatcher.onChanged(listenerUpdate);
    DownstreamTlsContext tlsContextPicked = getDownstreamTlsContext();
    assertThat(tlsContextPicked).isSameInstanceAs(tlsContext5);
  }

  private void setupChannel(String localIp, String remoteIp, int remotePort)
      throws UnknownHostException {
    when(channel.localAddress())
        .thenReturn(new InetSocketAddress(InetAddress.getByName(localIp), PORT));
    when(channel.remoteAddress())
        .thenReturn(new InetSocketAddress(InetAddress.getByName(remoteIp), remotePort));
  }
}
