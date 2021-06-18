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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.xds.Filter.NamedFilterConfig;
import io.grpc.xds.XdsClient.LdsUpdate;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.sds.SslContextProviderSupplier;
import io.netty.channel.Channel;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
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
  private static final HttpConnectionManager HTTP_CONNECTION_MANAGER =
      HttpConnectionManager.forRdsName(
          10L, "route-config", Collections.<NamedFilterConfig>emptyList());

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

  private EnvoyServerProtoData.DownstreamTlsContext getDownstreamTlsContext() {
    SslContextProviderSupplier sslContextProviderSupplier =
        xdsClientWrapperForServerSds.getSslContextProviderSupplier(channel);
    if (sslContextProviderSupplier != null) {
      EnvoyServerProtoData.BaseTlsContext tlsContext = sslContextProviderSupplier.getTlsContext();
      assertThat(tlsContext).isInstanceOf(EnvoyServerProtoData.DownstreamTlsContext.class);
      return (EnvoyServerProtoData.DownstreamTlsContext) tlsContext;
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
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChain filterChain = new EnvoyServerProtoData.FilterChain(
        "filter-chain-foo", filterChainMatch, HTTP_CONNECTION_MANAGER, tlsContext,
        tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener("listener1", LOCAL_IP, Arrays.asList(filterChain), null);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    registeredWatcher.onChanged(listenerUpdate);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
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
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChain filterChain = new EnvoyServerProtoData.FilterChain(
        "filter-chain-foo", filterChainMatch, HTTP_CONNECTION_MANAGER, tlsContext,
        tlsContextManager);
    EnvoyServerProtoData.DownstreamTlsContext defaultTlsContext =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
        "filter-chain-bar", null, HTTP_CONNECTION_MANAGER, defaultTlsContext,
        tlsContextManager);
    EnvoyServerProtoData.Listener listener = new EnvoyServerProtoData.Listener(
        "listener1", LOCAL_IP, Arrays.asList(filterChain), defaultFilterChain);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    registeredWatcher.onChanged(listenerUpdate);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(defaultTlsContext);
  }

  @Test
  public void defaultFilterChain() throws UnknownHostException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChain filterChain = new EnvoyServerProtoData.FilterChain(
        "filter-chain-foo", null, HTTP_CONNECTION_MANAGER, tlsContext, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1", LOCAL_IP, Arrays.<EnvoyServerProtoData.FilterChain>asList(), filterChain);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    registeredWatcher.onChanged(listenerUpdate);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContext);
  }

  @Test
  public void destPortFails_returnDefaultFilterChain() throws UnknownHostException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextWithDestPort =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchWithDestPort =
        new EnvoyServerProtoData.FilterChainMatch(
            PORT,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.asList("managed-mtls"),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChainWithDestPort =
        new EnvoyServerProtoData.FilterChain(
            "filter-chain-foo", filterChainMatchWithDestPort, HTTP_CONNECTION_MANAGER,
            tlsContextWithDestPort, tlsContextManager);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextForDefaultFilterChain =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChain defaultFilterChain =
        new EnvoyServerProtoData.FilterChain(
            "filter-chain-bar", null, HTTP_CONNECTION_MANAGER,
            tlsContextForDefaultFilterChain, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1", LOCAL_IP, Arrays.asList(filterChainWithDestPort), defaultFilterChain);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    registeredWatcher.onChanged(listenerUpdate);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextForDefaultFilterChain);
  }

  @Test
  public void destPrefixRangeMatch() throws UnknownHostException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextMatch =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchWithMatch =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.1.2.0", 24)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChainWithMatch = new EnvoyServerProtoData.FilterChain(
        "filter-chain-foo", filterChainMatchWithMatch, HTTP_CONNECTION_MANAGER,
        tlsContextMatch, tlsContextManager);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextForDefaultFilterChain =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
        "filter-chain-bar", null, HTTP_CONNECTION_MANAGER,
        tlsContextForDefaultFilterChain, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1", LOCAL_IP, Arrays.asList(filterChainWithMatch), defaultFilterChain);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    registeredWatcher.onChanged(listenerUpdate);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextMatch);
  }

  @Test
  public void destPrefixRangeMismatch_returnDefaultFilterChain()
      throws UnknownHostException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextMismatch =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    // 10.2.2.0/24 doesn't match LOCAL_IP
    EnvoyServerProtoData.FilterChainMatch filterChainMatchWithMismatch =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.2.2.0", 24)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChainWithMismatch =
        new EnvoyServerProtoData.FilterChain(
            "filter-chain-foo", filterChainMatchWithMismatch, HTTP_CONNECTION_MANAGER,
            tlsContextMismatch, tlsContextManager);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextForDefaultFilterChain =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
        "filter-chain-bar", null, HTTP_CONNECTION_MANAGER,
        tlsContextForDefaultFilterChain, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1", LOCAL_IP, Arrays.asList(filterChainWithMismatch), defaultFilterChain);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    registeredWatcher.onChanged(listenerUpdate);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextForDefaultFilterChain);
  }

  @Test
  public void dest0LengthPrefixRange()
      throws UnknownHostException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext0Length =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    // 10.2.2.0/24 doesn't match LOCAL_IP
    EnvoyServerProtoData.FilterChainMatch filterChainMatch0Length =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.2.2.0", 0)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChain0Length = new EnvoyServerProtoData.FilterChain(
        "filter-chain-foo", filterChainMatch0Length, HTTP_CONNECTION_MANAGER,
        tlsContext0Length, tlsContextManager);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextForDefaultFilterChain =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
        "filter-chain-bar", null, HTTP_CONNECTION_MANAGER,
        tlsContextForDefaultFilterChain, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1", LOCAL_IP, Arrays.asList(filterChain0Length), defaultFilterChain);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    registeredWatcher.onChanged(listenerUpdate);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContext0Length);
  }

  @Test
  public void destPrefixRange_moreSpecificWins()
      throws UnknownHostException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextLessSpecific =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchLessSpecific =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.1.2.0", 24)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChainLessSpecific =
        new EnvoyServerProtoData.FilterChain(
            "filter-chain-foo", filterChainMatchLessSpecific, HTTP_CONNECTION_MANAGER,
            tlsContextLessSpecific, tlsContextManager);

    EnvoyServerProtoData.DownstreamTlsContext tlsContextMoreSpecific =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchMoreSpecific =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.1.2.2", 31)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChainMoreSpecific =
        new EnvoyServerProtoData.FilterChain(
            "filter-chain-bar", filterChainMatchMoreSpecific, HTTP_CONNECTION_MANAGER,
            tlsContextMoreSpecific,
            tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
        "filter-chain-baz", null, HTTP_CONNECTION_MANAGER, null, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1",
            LOCAL_IP,
            Arrays.asList(filterChainLessSpecific, filterChainMoreSpecific),
            defaultFilterChain);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    registeredWatcher.onChanged(listenerUpdate);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextMoreSpecific);
  }

  @Test
  public void destPrefixRange_emptyListLessSpecific()
      throws UnknownHostException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextLessSpecific =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchLessSpecific =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChainLessSpecific =
        new EnvoyServerProtoData.FilterChain(
            "filter-chain-foo", filterChainMatchLessSpecific, HTTP_CONNECTION_MANAGER,
            tlsContextLessSpecific, tlsContextManager);

    EnvoyServerProtoData.DownstreamTlsContext tlsContextMoreSpecific =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchMoreSpecific =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("8.0.0.0", 5)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChainMoreSpecific =
        new EnvoyServerProtoData.FilterChain(
            "filter-chain-bar", filterChainMatchMoreSpecific, HTTP_CONNECTION_MANAGER,
            tlsContextMoreSpecific,
            tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
        "filter-chain-baz", null, HTTP_CONNECTION_MANAGER, null, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1",
            LOCAL_IP,
            Arrays.asList(filterChainLessSpecific, filterChainMoreSpecific),
            defaultFilterChain);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    registeredWatcher.onChanged(listenerUpdate);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextMoreSpecific);
  }

  @Test
  public void destPrefixRangeIpv6_moreSpecificWins()
      throws UnknownHostException {
    setupChannel("FE80:0000:0000:0000:0202:B3FF:FE1E:8329", "2001:DB8::8:800:200C:417A", 15000);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextLessSpecific =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchLessSpecific =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("FE80:0:0:0:0:0:0:0", 60)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChainLessSpecific =
        new EnvoyServerProtoData.FilterChain(
            "filter-chain-foo", filterChainMatchLessSpecific, HTTP_CONNECTION_MANAGER,
            tlsContextLessSpecific, tlsContextManager);

    EnvoyServerProtoData.DownstreamTlsContext tlsContextMoreSpecific =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchMoreSpecific =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("FE80:0000:0000:0000:0202:0:0:0", 80)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChainMoreSpecific =
        new EnvoyServerProtoData.FilterChain(
            "filter-chain-bar", filterChainMatchMoreSpecific, HTTP_CONNECTION_MANAGER,
            tlsContextMoreSpecific, tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
        "filter-chain-baz", null, HTTP_CONNECTION_MANAGER, null, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1",
            "FE80:0000:0000:0000:0202:B3FF:FE1E:8329",
            Arrays.asList(filterChainLessSpecific, filterChainMoreSpecific),
            defaultFilterChain);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    registeredWatcher.onChanged(listenerUpdate);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextMoreSpecific);
  }

  @Test
  public void destPrefixRange_moreSpecificWith2Wins()
      throws UnknownHostException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextMoreSpecificWith2 =
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
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChainMoreSpecificWith2 =
        new EnvoyServerProtoData.FilterChain(
            "filter-chain-foo", filterChainMatchMoreSpecificWith2, HTTP_CONNECTION_MANAGER,
            tlsContextMoreSpecificWith2, tlsContextManager);

    EnvoyServerProtoData.DownstreamTlsContext tlsContextLessSpecific =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchLessSpecific =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.1.2.2", 31)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChainLessSpecific =
        new EnvoyServerProtoData.FilterChain(
            "filter-chain-bar", filterChainMatchLessSpecific, HTTP_CONNECTION_MANAGER,
            tlsContextLessSpecific, tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
        "filter-chain-baz", null, HTTP_CONNECTION_MANAGER, null, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1",
            LOCAL_IP,
            Arrays.asList(filterChainMoreSpecificWith2, filterChainLessSpecific),
            defaultFilterChain);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    registeredWatcher.onChanged(listenerUpdate);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextMoreSpecificWith2);
  }

  @Test
  public void sourceTypeMismatch_returnDefaultFilterChain() throws UnknownHostException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextMismatch =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchWithMismatch =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.SAME_IP_OR_LOOPBACK,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChainWithMismatch =
        new EnvoyServerProtoData.FilterChain(
            "filter-chain-foo", filterChainMatchWithMismatch, HTTP_CONNECTION_MANAGER,
            tlsContextMismatch, tlsContextManager);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextForDefaultFilterChain =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
        "filter-chain-bar", null, HTTP_CONNECTION_MANAGER,tlsContextForDefaultFilterChain,
        tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1", LOCAL_IP, Arrays.asList(filterChainWithMismatch), defaultFilterChain);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    registeredWatcher.onChanged(listenerUpdate);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextForDefaultFilterChain);
  }

  @Test
  public void sourceTypeLocal() throws UnknownHostException {
    setupChannel(LOCAL_IP, LOCAL_IP, 15000);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextMatch =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchWithMatch =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.SAME_IP_OR_LOOPBACK,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChainWithMatch = new EnvoyServerProtoData.FilterChain(
        "filter-chain-foo", filterChainMatchWithMatch, HTTP_CONNECTION_MANAGER, tlsContextMatch,
        tlsContextManager);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextForDefaultFilterChain =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
        "filter-chain-bar", null, HTTP_CONNECTION_MANAGER, tlsContextForDefaultFilterChain,
        tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1", LOCAL_IP, Arrays.asList(filterChainWithMatch), defaultFilterChain);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    registeredWatcher.onChanged(listenerUpdate);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextMatch);
  }

  @Test
  public void sourcePrefixRange_moreSpecificWith2Wins()
      throws UnknownHostException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextMoreSpecificWith2 =
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
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChainMoreSpecificWith2 =
        new EnvoyServerProtoData.FilterChain(
            "filter-chain-foo", filterChainMatchMoreSpecificWith2, HTTP_CONNECTION_MANAGER,
            tlsContextMoreSpecificWith2, tlsContextManager);

    EnvoyServerProtoData.DownstreamTlsContext tlsContextLessSpecific =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchLessSpecific =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.4.2.2", 31)),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChainLessSpecific =
        new EnvoyServerProtoData.FilterChain(
            "filter-chain-bar", filterChainMatchLessSpecific, HTTP_CONNECTION_MANAGER,
            tlsContextLessSpecific, tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
        "filter-chain-baz", null, HTTP_CONNECTION_MANAGER, null, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1",
            LOCAL_IP,
            Arrays.asList(filterChainMoreSpecificWith2, filterChainLessSpecific),
            defaultFilterChain);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    registeredWatcher.onChanged(listenerUpdate);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
    assertThat(tlsContext1).isSameInstanceAs(tlsContextMoreSpecificWith2);
  }

  @Test
  public void sourcePrefixRange_2Matchers_expectException()
      throws UnknownHostException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 =
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
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChain1 = new EnvoyServerProtoData.FilterChain(
        "filter-chain-foo", filterChainMatch1, HTTP_CONNECTION_MANAGER, tlsContext1,
        tlsContextManager);

    EnvoyServerProtoData.DownstreamTlsContext tlsContext2 =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatch2 =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.4.2.0", 24)),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChain2 = new EnvoyServerProtoData.FilterChain(
        "filter-chain-bar", filterChainMatch2, HTTP_CONNECTION_MANAGER, tlsContext2,
        tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
        "filter-chain-baz", null, HTTP_CONNECTION_MANAGER, null, null);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1", LOCAL_IP, Arrays.asList(filterChain1, filterChain2), defaultFilterChain);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
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
      throws UnknownHostException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextEmptySourcePorts =
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
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChainEmptySourcePorts =
        new EnvoyServerProtoData.FilterChain(
            "filter-chain-foo", filterChainMatchEmptySourcePorts, HTTP_CONNECTION_MANAGER,
            tlsContextEmptySourcePorts, tlsContextManager);

    EnvoyServerProtoData.DownstreamTlsContext tlsContextSourcePortMatch =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.FilterChainMatch filterChainMatchSourcePortMatch =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.4.2.2", 31)),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.asList(7000, 15000),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChainSourcePortMatch =
        new EnvoyServerProtoData.FilterChain(
            "filter-chain-bar", filterChainMatchSourcePortMatch, HTTP_CONNECTION_MANAGER,
            tlsContextSourcePortMatch, tlsContextManager);
    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
        "filter-chain-baz", null, HTTP_CONNECTION_MANAGER, null, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1",
            LOCAL_IP,
            Arrays.asList(filterChainEmptySourcePorts, filterChainSourcePortMatch),
            defaultFilterChain);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    registeredWatcher.onChanged(listenerUpdate);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 = getDownstreamTlsContext();
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
  public void filterChain_5stepMatch() throws UnknownHostException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1");
    EnvoyServerProtoData.DownstreamTlsContext tlsContext2 =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2");
    EnvoyServerProtoData.DownstreamTlsContext tlsContext3 =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT3", "VA3");
    EnvoyServerProtoData.DownstreamTlsContext tlsContext4 =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT4", "VA4");
    EnvoyServerProtoData.DownstreamTlsContext tlsContext5 =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT5", "VA5");
    EnvoyServerProtoData.DownstreamTlsContext tlsContext6 =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT6", "VA6");

    // has dest port and specific prefix ranges: gets eliminated in step 1
    EnvoyServerProtoData.FilterChainMatch filterChainMatch1 =
        new EnvoyServerProtoData.FilterChainMatch(
            PORT,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.asList(new EnvoyServerProtoData.CidrRange(REMOTE_IP, 32)),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChain1 = new EnvoyServerProtoData.FilterChain(
        "filter-chain-1", filterChainMatch1, HTTP_CONNECTION_MANAGER, tlsContext1,
        tlsContextManager);

    // next 5 use prefix range: 4 with prefixLen of 30 and last one with 29

    // has single prefix range: and less specific source prefix range: gets eliminated in step 4
    EnvoyServerProtoData.FilterChainMatch filterChainMatch2 =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.1.2.0", 30)),
            Arrays.<String>asList(),
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.4.0.0", 16)),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChain2 = new EnvoyServerProtoData.FilterChain(
        "filter-chain-2", filterChainMatch2, HTTP_CONNECTION_MANAGER, tlsContext2,
        tlsContextManager);

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
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChain3 = new EnvoyServerProtoData.FilterChain(
        "filter-chain-3", filterChainMatch3, HTTP_CONNECTION_MANAGER, tlsContext3,
        tlsContextManager);

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
            Arrays.asList(16000, 9000),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChain4 =
        new EnvoyServerProtoData.FilterChain(
            "filter-chain-4", filterChainMatch4, HTTP_CONNECTION_MANAGER, tlsContext4,
            tlsContextManager);

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
            Arrays.asList(15000, 8000),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChain5 =
        new EnvoyServerProtoData.FilterChain(
            "filter-chain-5", filterChainMatch5, HTTP_CONNECTION_MANAGER, tlsContext5,
            tlsContextManager);

    // has prefix range with prefixLen of 29: gets eliminated in step 2
    EnvoyServerProtoData.FilterChainMatch filterChainMatch6 =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.asList(new EnvoyServerProtoData.CidrRange("10.1.2.0", 29)),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            EnvoyServerProtoData.ConnectionSourceType.ANY,
            Arrays.<Integer>asList(),
            Arrays.<String>asList(),
            null);
    EnvoyServerProtoData.FilterChain filterChain6 =
        new EnvoyServerProtoData.FilterChain(
            "filter-chain-6", filterChainMatch6, HTTP_CONNECTION_MANAGER, tlsContext6,
            tlsContextManager);

    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
        "filter-chain-7", null, HTTP_CONNECTION_MANAGER, null, tlsContextManager);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            "listener1",
            LOCAL_IP,
            Arrays.asList(
                filterChain1, filterChain2, filterChain3, filterChain4, filterChain5, filterChain6),
            defaultFilterChain);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    registeredWatcher.onChanged(listenerUpdate);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextPicked = getDownstreamTlsContext();
    assertThat(tlsContextPicked).isSameInstanceAs(tlsContext5);
  }

  @Test
  public void filterChainMatch_unsupportedMatchers()
      throws UnknownHostException {
    setupChannel(LOCAL_IP, REMOTE_IP, 15000);
    EnvoyServerProtoData.DownstreamTlsContext tlsContext1 =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "ROOTCA");
    EnvoyServerProtoData.DownstreamTlsContext tlsContext2 =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "ROOTCA");
    EnvoyServerProtoData.DownstreamTlsContext tlsContext3 =
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT3", "ROOTCA");

    EnvoyServerProtoData.FilterChainMatch filterChainMatch1 =
        new EnvoyServerProtoData.FilterChainMatch(
            0 /* destinationPort */,
            Collections.singletonList(
                new EnvoyServerProtoData.CidrRange("10.1.0.0", 16)) /* prefixRange */,
            Arrays.asList("managed-mtls", "h2") /* applicationProtocol */,
            Collections.<EnvoyServerProtoData.CidrRange>emptyList() /* sourcePrefixRanges */,
            EnvoyServerProtoData.ConnectionSourceType.ANY /* sourceType */,
            Collections.<Integer>emptyList() /* sourcePorts */,
            Arrays.asList("server1", "server2") /* serverNames */,
            "tls" /* transportProtocol */);

    EnvoyServerProtoData.FilterChainMatch filterChainMatch2 =
        new EnvoyServerProtoData.FilterChainMatch(
            0 /* destinationPort */,
            Collections.singletonList(
                new EnvoyServerProtoData.CidrRange("10.0.0.0", 8)) /* prefixRange */,
            Collections.<String>emptyList() /* applicationProtocol */,
            Collections.<EnvoyServerProtoData.CidrRange>emptyList() /* sourcePrefixRanges */,
            EnvoyServerProtoData.ConnectionSourceType.ANY /* sourceType */,
            Collections.<Integer>emptyList() /* sourcePorts */,
            Collections.<String>emptyList() /* serverNames */,
            "" /* transportProtocol */);

    EnvoyServerProtoData.FilterChainMatch defaultFilterChainMatch =
        new EnvoyServerProtoData.FilterChainMatch(
            0 /* destinationPort */,
            Collections.<EnvoyServerProtoData.CidrRange>emptyList() /* prefixRange */,
            Collections.<String>emptyList() /* applicationProtocol */,
            Collections.<EnvoyServerProtoData.CidrRange>emptyList() /* sourcePrefixRanges */,
            EnvoyServerProtoData.ConnectionSourceType.ANY /* sourceType */,
            Collections.<Integer>emptyList() /* sourcePorts */,
            Collections.<String>emptyList() /* serverNames */,
            "" /* transportProtocol */);

    EnvoyServerProtoData.FilterChain filterChain1 = new EnvoyServerProtoData.FilterChain(
        "filter-chain-foo", filterChainMatch1, HTTP_CONNECTION_MANAGER, tlsContext1,
        mock(TlsContextManager.class));
    EnvoyServerProtoData.FilterChain filterChain2 = new EnvoyServerProtoData.FilterChain(
        "filter-chain-bar", filterChainMatch2, HTTP_CONNECTION_MANAGER, tlsContext2,
        mock(TlsContextManager.class));

    EnvoyServerProtoData.FilterChain defaultFilterChain = new EnvoyServerProtoData.FilterChain(
        "filter-chain-baz", defaultFilterChainMatch, HTTP_CONNECTION_MANAGER, tlsContext3,
        mock(TlsContextManager.class));

    EnvoyServerProtoData.Listener listener = new EnvoyServerProtoData.Listener(
        "", "10.2.1.34:8000", Arrays.asList(filterChain1, filterChain2), defaultFilterChain);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    registeredWatcher.onChanged(listenerUpdate);
    EnvoyServerProtoData.DownstreamTlsContext tlsContextPicked = getDownstreamTlsContext();
    // assert defaultFilterChain match
    assertThat(tlsContextPicked.getCommonTlsContext().getTlsCertificateSdsSecretConfigsList()
        .get(0).getName()).isEqualTo("CERT3");
  }

  private void setupChannel(String localIp, String remoteIp, int remotePort)
      throws UnknownHostException {
    when(channel.localAddress())
        .thenReturn(new InetSocketAddress(InetAddress.getByName(localIp), PORT));
    when(channel.remoteAddress())
        .thenReturn(new InetSocketAddress(InetAddress.getByName(remoteIp), remotePort));
  }
}
