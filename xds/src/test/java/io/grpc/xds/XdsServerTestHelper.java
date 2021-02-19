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

import java.io.IOException;
import java.net.ServerSocket;
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
   * Creates a {@link XdsClient.ListenerUpdate} with {@link
   * io.grpc.xds.EnvoyServerProtoData.FilterChain} with a destination port and an optional {@link
   * EnvoyServerProtoData.DownstreamTlsContext}.
   *
   * @param registeredWatcher the watcher on which to generate the update
   * @param tlsContext if non-null, used to populate filterChain
   */
  static void generateListenerUpdate(
      XdsClient.ListenerWatcher registeredWatcher,
      EnvoyServerProtoData.DownstreamTlsContext tlsContext) {
    EnvoyServerProtoData.Listener listener = buildTestListener("listener1", "10.1.2.3", tlsContext);
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
      String name, String address, EnvoyServerProtoData.DownstreamTlsContext tlsContext) {
    EnvoyServerProtoData.FilterChainMatch filterChainMatch1 =
        new EnvoyServerProtoData.FilterChainMatch(
            0,
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            Arrays.<String>asList(),
            Arrays.<EnvoyServerProtoData.CidrRange>asList(),
            null,
            Arrays.<Integer>asList());
    EnvoyServerProtoData.FilterChain filterChain1 =
        new EnvoyServerProtoData.FilterChain(filterChainMatch1, tlsContext);
    EnvoyServerProtoData.FilterChain defaultFilterChain =
        new EnvoyServerProtoData.FilterChain(null, null);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(
            name, address, Arrays.asList(filterChain1), defaultFilterChain);
    return listener;
  }
}
