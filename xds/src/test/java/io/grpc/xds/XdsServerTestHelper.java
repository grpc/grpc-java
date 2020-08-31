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

import io.grpc.xds.internal.sds.XdsServerBuilder;
import java.io.IOException;
import java.net.ServerSocket;
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

  static void generateListenerUpdate(
      XdsClient.ListenerWatcher registeredWatcher,
      int destPort1,
      int destPort2,
      EnvoyServerProtoData.DownstreamTlsContext tlsContext1,
      EnvoyServerProtoData.DownstreamTlsContext tlsContext2) {
    EnvoyServerProtoData.Listener listener =
        XdsClientWrapperForServerSdsTest.buildTestListener(
            "listener1",
            "10.1.2.3",
            destPort1,
            destPort2,
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
}
