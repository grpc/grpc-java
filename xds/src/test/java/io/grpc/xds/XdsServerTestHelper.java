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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.InsecureChannelCredentials;
import io.grpc.internal.ObjectPool;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.Map;
import javax.annotation.Nullable;
import org.mockito.ArgumentCaptor;

/**
 * Helper methods related to {@link XdsServerBuilder} and related classes.
 */
class XdsServerTestHelper {

  private static final String SERVER_URI = "trafficdirector.googleapis.com";
  private static final String NODE_ID =
      "projects/42/networks/default/nodes/5c85b298-6f5b-4722-b74a-f7d1f0ccf5ad";
  private static final EnvoyProtoData.Node BOOTSTRAP_NODE =
      EnvoyProtoData.Node.newBuilder().setId(NODE_ID).build();
  static final Bootstrapper.BootstrapInfo BOOTSTRAP_INFO =
      new Bootstrapper.BootstrapInfo(
          Arrays.asList(
              new Bootstrapper.ServerInfo(SERVER_URI, InsecureChannelCredentials.create(), false)),
          BOOTSTRAP_NODE,
          null,
          "grpc/server?udpa.resource.listening_address=%s");

  static final class FakeXdsClientPoolFactory
      implements XdsNameResolverProvider.XdsClientPoolFactory {

    private XdsClient xdsClient;

    FakeXdsClientPoolFactory(XdsClient xdsClient) {
      this.xdsClient = xdsClient;
    }

    @Override
    public void setBootstrapOverride(Map<String, ?> bootstrap) {
      throw new UnsupportedOperationException("Should not be called");
    }

    @Override
    @Nullable
    public ObjectPool<XdsClient> get() {
      throw new UnsupportedOperationException("Should not be called");
    }

    @Override
    public ObjectPool<XdsClient> getOrCreate() throws XdsInitializationException {
      return new ObjectPool<XdsClient>() {
        @Override
        public XdsClient getObject() {
          return xdsClient;
        }

        @Override
        public XdsClient returnObject(Object object) {
          return null;
        }
      };
    }
  }

  static XdsClientWrapperForServerSds createXdsClientWrapperForServerSds(int port,
      TlsContextManager tlsContextManager) {
    FakeXdsClientPoolFactory fakeXdsClientPoolFactory = new FakeXdsClientPoolFactory(
        buildMockXdsClient(tlsContextManager));
    return new XdsClientWrapperForServerSds(port, fakeXdsClientPoolFactory);
  }

  private static XdsClient buildMockXdsClient(TlsContextManager tlsContextManager) {
    XdsClient xdsClient = mock(XdsClient.class);
    when(xdsClient.getBootstrapInfo()).thenReturn(BOOTSTRAP_INFO);
    when(xdsClient.getTlsContextManager()).thenReturn(tlsContextManager);
    return xdsClient;
  }

  static XdsClient.LdsResourceWatcher startAndGetWatcher(
      XdsClientWrapperForServerSds xdsClientWrapperForServerSds) {
    xdsClientWrapperForServerSds.start();
    XdsClient mockXdsClient = xdsClientWrapperForServerSds.getXdsClient();
    ArgumentCaptor<XdsClient.LdsResourceWatcher> listenerWatcherCaptor =
        ArgumentCaptor.forClass(null);
    verify(mockXdsClient).watchLdsResource(any(String.class), listenerWatcherCaptor.capture());
    return listenerWatcherCaptor.getValue();
  }

  /**
   * Creates a {@link XdsClient.LdsUpdate} with {@link
   * io.grpc.xds.EnvoyServerProtoData.FilterChain} with a destination port and an optional {@link
   * EnvoyServerProtoData.DownstreamTlsContext}.
   *
   * @param registeredWatcher the watcher on which to generate the update
   * @param tlsContext if non-null, used to populate filterChain
   */
  static void generateListenerUpdate(
      XdsClient.LdsResourceWatcher registeredWatcher,
      EnvoyServerProtoData.DownstreamTlsContext tlsContext) {
    EnvoyServerProtoData.Listener listener = buildTestListener("listener1", "10.1.2.3", tlsContext);
    XdsClient.LdsUpdate listenerUpdate = new XdsClient.LdsUpdate(listener);
    registeredWatcher.onChanged(listenerUpdate);
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
