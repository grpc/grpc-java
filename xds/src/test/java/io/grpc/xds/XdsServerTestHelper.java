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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.google.common.base.Strings;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;

import org.mockito.ArgumentCaptor;

/**
 * Helper methods related to {@link XdsServerBuilder} and related classes.
 */
class XdsServerTestHelper {

  static XdsClient.ListenerWatcher startAndGetWatcher(
      XdsClientWrapperForServerSds xdsClientWrapperForServerSds,
      XdsClient mockXdsClient,
      int port) {
    xdsClientWrapperForServerSds.start(mockXdsClient);
    ArgumentCaptor<XdsClient.ListenerWatcher> listenerWatcherCaptor = ArgumentCaptor.forClass(null);
    verify(mockXdsClient).watchListenerData(eq(port), listenerWatcherCaptor.capture());
    return listenerWatcherCaptor.getValue();
  }

  /**
   * Creates a {@link XdsClient.ListenerUpdate} with maximum of 2
   * {@link io.grpc.xds.EnvoyServerProtoData.FilterChain} each one with a destination port and an
   * optional {@link EnvoyServerProtoData.DownstreamTlsContext}.
   *  @param registeredWatcher the watcher on which to generate the update
   * @param destPort if > 0 to create both the {@link EnvoyServerProtoData.FilterChain}
   * @param tlsContext1 if non-null, used to populate the 1st filterChain
   * @param tlsContext2 if non-null, used to populate the 2nd filterChain
   */
  static void generateListenerUpdate(
          XdsClient.ListenerWatcher registeredWatcher,
          int destPort,
          EnvoyServerProtoData.DownstreamTlsContext tlsContext1,
          EnvoyServerProtoData.DownstreamTlsContext tlsContext2) {
    EnvoyServerProtoData.Listener listener =
        buildTestListener(
            "listener1",
            "10.1.2.3",
            destPort,
            destPort,
            null,
            null,
            null,
            null,
            tlsContext1,
            tlsContext2);
    XdsClient.ListenerUpdate listenerUpdate =
        XdsClient.ListenerUpdate.newBuilder().setListener(listener).build();
    registeredWatcher.onListenerChanged(listenerUpdate);
  }

  static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  static EnvoyServerProtoData.Listener buildTestListener(
      String name,
      String address,
      int destPort1,
      int destPort2,
      String addressPrefix11,
      String addressPrefix12,
      String addressPrefix21,
      String addressPrefix22,
      EnvoyServerProtoData.DownstreamTlsContext tlsContext1,
      EnvoyServerProtoData.DownstreamTlsContext tlsContext2) {
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
}
