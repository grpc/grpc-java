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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import com.google.common.base.Strings;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.netty.channel.Channel;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
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
public class XdsClientWrapperForServerSdsTest {

  private static final int PORT = 7000;

  @Mock private XdsClient xdsClient;
  @Mock private Channel channel;

  private XdsClientWrapperForServerSds xdsClientWrapperForServerSds;
  private DownstreamTlsContext tlsContext1;
  private DownstreamTlsContext tlsContext2;

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);
    xdsClientWrapperForServerSds = new XdsClientWrapperForServerSds(PORT, xdsClient);
    tlsContext1 = CommonTlsContextTestsUtil.buildTestDownstreamTlsContext("CERT1", "VA1");
    tlsContext2 = CommonTlsContextTestsUtil.buildTestDownstreamTlsContext("CERT2", "VA2");
  }

  @Test
  public void verifyListenerWatcherRegistered() {
    verify(xdsClient, times(1)).watchListenerData(eq(PORT), any(XdsClient.ListenerWatcher.class));
  }

  @Test
  public void listener_nullFilterChainMatch1() throws UnknownHostException {
    // filterChainMatch missing for filter1, so it matches and returns tlsContext1
    commonFilterChainMatchTest(-1, null, null, "192.168.10.1", "192.168.10.2", tlsContext1);
  }

  @Test
  public void listener_destPortMatchNoAddressPrefix() throws UnknownHostException {
    // destPort matches for filter2 even if no cidrRange, so it returns tlsContext2
    commonFilterChainMatchTest(PORT + 1, "192.168.10.1", "192.168.10.2", null, null, tlsContext2);
  }

  @Test
  public void listener_destPortAndAddressPrefixMatch() throws UnknownHostException {
    // destPort and cidrRange exact match for filter1, so it returns tlsContext1
    commonFilterChainMatchTest(
        PORT, "168.20.20.2", "10.1.2.3", "192.168.10.1", "192.168.10.2", tlsContext1);
  }

  @Test
  public void listener_emptyAddressRangeOverEmptyFilterChainMatch() throws UnknownHostException {
    // existing filterChain with empty address preferred over empty filterChain
    commonFilterChainMatchTest(-1, null, null, null, null, tlsContext2);
  }

  @Test
  public void listener_cidrRangeIpAnyOverEmptyAddressRange() throws UnknownHostException {
    // IPANY (0.0.0.0) preferred over empty address range
    commonFilterChainMatchTest(
        PORT,
        null,
        null,
        "192.168.1.4",
        "0.0.0.0",
        tlsContext2); // IPANY to make second filterChain selected
  }

  @Test
  public void listener_CidrRangeIpExactOverCidrRangeIpAny() throws UnknownHostException {
    // exact address match preferred over IPANY (0.0.0.0)
    commonFilterChainMatchTest(
        PORT,
        "192.168.1.4",
        "0.0.0.0",
        "168.154.4.7",
        "10.1.2.3",
        tlsContext2); // Matching IP to make second filterChain selected
  }

  @Test
  public void listener_destPortAndAddressPrefixNoMatch() throws UnknownHostException {
    // when none matches it should return null
    commonFilterChainMatchTest(
        PORT, "168.20.20.2", "10.1.2.4", "192.168.10.1", "192.168.10.2", null);
  }

  /**
   * Common method called by most tests. Creates 2 filterChains each with 2 addresses. First
   * filterChain's destPort is always PORT.
   *
   * @param destPort1 dest port of 1st filterChain
   * @param addressPrefix11 1st address of 1st filter
   * @param addressPrefix12 2nd address of 1st filter
   * @param addressPrefix21 1st address of 2nd filter
   * @param addressPrefix22 2nd address of 2nd filter
   * @param expectedTlsContext expected DownstreamTlsContext for the test
   */
  private void commonFilterChainMatchTest(
      int destPort1,
      String addressPrefix11,
      String addressPrefix12,
      String addressPrefix21,
      String addressPrefix22,
      DownstreamTlsContext expectedTlsContext)
      throws UnknownHostException {
    ArgumentCaptor<XdsClient.ListenerWatcher> listenerWatcherCaptor = ArgumentCaptor.forClass(null);
    verify(xdsClient).watchListenerData(eq(PORT), listenerWatcherCaptor.capture());
    XdsClient.ListenerWatcher registeredWatcher = listenerWatcherCaptor.getValue();
    InetAddress ipLocalAddress = Inet4Address.getByName("10.1.2.3");
    InetSocketAddress localAddress = new InetSocketAddress(ipLocalAddress, PORT);
    when(channel.localAddress()).thenReturn(localAddress);
    EnvoyServerProtoData.Listener listener =
        buildTestListener(
            "listener1",
            "10.1.2.3",
            destPort1,
            PORT,
            addressPrefix11,
            addressPrefix12,
            addressPrefix21,
            addressPrefix22);
    XdsClient.ListenerUpdate listenerUpdate =
        XdsClient.ListenerUpdate.newBuilder().setListener(listener).build();
    registeredWatcher.onListenerChanged(listenerUpdate);
    DownstreamTlsContext downstreamTlsContext =
        xdsClientWrapperForServerSds.getDownstreamTlsContext(channel);
    assertThat(downstreamTlsContext).isSameInstanceAs(expectedTlsContext);
  }

  @After
  public void tearDown() {}

  EnvoyServerProtoData.Listener buildTestListener(
      String name,
      String address,
      int destPort1,
      int destPort2,
      String addressPrefix11,
      String addressPrefix12,
      String addressPrefix21,
      String addressPrefix22) {
    EnvoyServerProtoData.FilterChainMatch filterChainMatch1 =
        destPort1 > 0 ? buildFilterChainMatch(destPort1, addressPrefix11, addressPrefix12) : null;
    EnvoyServerProtoData.FilterChainMatch filterChainMatch2 =
        destPort2 > 0 ? buildFilterChainMatch(destPort2, addressPrefix21, addressPrefix22) : null;
    EnvoyServerProtoData.FilterChain filterChain1 =
        new EnvoyServerProtoData.FilterChain(filterChainMatch1, tlsContext1);
    EnvoyServerProtoData.FilterChain filterChain2 =
        new EnvoyServerProtoData.FilterChain(filterChainMatch2, tlsContext2);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(name, address, Arrays.asList(filterChain1, filterChain2));
    return listener;
  }

  static EnvoyServerProtoData.FilterChainMatch buildFilterChainMatch(
      int destPort, String... addressPrefix) {
    ArrayList<EnvoyServerProtoData.CidrRange> prefixRanges = new ArrayList<>();
    for (String address : addressPrefix) {
      if (!Strings.isNullOrEmpty(address)) {
        prefixRanges.add(new EnvoyServerProtoData.CidrRange(address, 32));
      }
    }
    return new EnvoyServerProtoData.FilterChainMatch(
        destPort, prefixRanges, Arrays.<String>asList());
  }

  static EnvoyServerProtoData.FilterChainMatch buildFilterChainMatch(
      int destPort, EnvoyServerProtoData.CidrRange... prefixRanges) {
    return new EnvoyServerProtoData.FilterChainMatch(
        destPort, Arrays.asList(prefixRanges), Arrays.<String>asList());
  }
}
