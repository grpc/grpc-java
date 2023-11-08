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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.InsecureChannelCredentials;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.SharedXdsClientPoolProvider.RefCountedXdsClientObjectPool;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link SharedXdsClientPoolProvider}. */
@RunWith(JUnit4.class)
public class SharedXdsClientPoolProviderTest {

  private static final String SERVER_URI = "trafficdirector.googleapis.com";
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  @SuppressWarnings("deprecation") // https://github.com/grpc/grpc-java/issues/7467
  @Rule
  public final ExpectedException thrown = ExpectedException.none();
  private final Node node = Node.newBuilder().setId("SharedXdsClientPoolProviderTest").build();

  @Mock
  private Bootstrapper bootstrapper;

  @Test
  public void noServer() throws XdsInitializationException {
    BootstrapInfo bootstrapInfo =
        BootstrapInfo.builder().servers(Collections.<ServerInfo>emptyList()).node(node).build();
    when(bootstrapper.bootstrap()).thenReturn(bootstrapInfo);
    SharedXdsClientPoolProvider provider = new SharedXdsClientPoolProvider(bootstrapper);
    thrown.expect(XdsInitializationException.class);
    thrown.expectMessage("No xDS server provided");
    provider.getOrCreate();
    assertThat(provider.get()).isNull();
  }

  @Test
  public void sharedXdsClientObjectPool() throws XdsInitializationException {
    ServerInfo server = ServerInfo.create(SERVER_URI, InsecureChannelCredentials.create());
    BootstrapInfo bootstrapInfo =
        BootstrapInfo.builder().servers(Collections.singletonList(server)).node(node).build();
    when(bootstrapper.bootstrap()).thenReturn(bootstrapInfo);

    SharedXdsClientPoolProvider provider = new SharedXdsClientPoolProvider(bootstrapper);
    assertThat(provider.get()).isNull();
    ObjectPool<XdsClient> xdsClientPool = provider.getOrCreate();
    verify(bootstrapper).bootstrap();
    assertThat(provider.getOrCreate()).isSameInstanceAs(xdsClientPool);
    assertThat(provider.get()).isNotNull();
    assertThat(provider.get()).isSameInstanceAs(xdsClientPool);
    verifyNoMoreInteractions(bootstrapper);
  }

  @Test
  public void refCountedXdsClientObjectPool_delayedCreation() {
    ServerInfo server = ServerInfo.create(SERVER_URI, InsecureChannelCredentials.create());
    BootstrapInfo bootstrapInfo =
        BootstrapInfo.builder().servers(Collections.singletonList(server)).node(node).build();
    RefCountedXdsClientObjectPool xdsClientPool = new RefCountedXdsClientObjectPool(bootstrapInfo);
    assertThat(xdsClientPool.getXdsClientForTest()).isNull();
    XdsClient xdsClient = xdsClientPool.getObject();
    assertThat(xdsClientPool.getXdsClientForTest()).isNotNull();
    xdsClientPool.returnObject(xdsClient);
  }

  @Test
  public void refCountedXdsClientObjectPool_refCounted() {
    ServerInfo server = ServerInfo.create(SERVER_URI, InsecureChannelCredentials.create());
    BootstrapInfo bootstrapInfo =
        BootstrapInfo.builder().servers(Collections.singletonList(server)).node(node).build();
    RefCountedXdsClientObjectPool xdsClientPool = new RefCountedXdsClientObjectPool(bootstrapInfo);
    // getObject once
    XdsClient xdsClient = xdsClientPool.getObject();
    assertThat(xdsClient).isNotNull();
    // getObject twice
    assertThat(xdsClientPool.getObject()).isSameInstanceAs(xdsClient);
    // returnObject once
    assertThat(xdsClientPool.returnObject(xdsClient)).isNull();
    assertThat(xdsClient.isShutDown()).isFalse();
    // returnObject twice
    assertThat(xdsClientPool.returnObject(xdsClient)).isNull();
    assertThat(xdsClient.isShutDown()).isTrue();
  }

  @Test
  public void refCountedXdsClientObjectPool_getObjectCreatesNewInstanceIfAlreadyShutdown() {
    ServerInfo server = ServerInfo.create(SERVER_URI, InsecureChannelCredentials.create());
    BootstrapInfo bootstrapInfo =
        BootstrapInfo.builder().servers(Collections.singletonList(server)).node(node).build();
    RefCountedXdsClientObjectPool xdsClientPool = new RefCountedXdsClientObjectPool(bootstrapInfo);
    XdsClient xdsClient1 = xdsClientPool.getObject();
    assertThat(xdsClientPool.returnObject(xdsClient1)).isNull();
    assertThat(xdsClient1.isShutDown()).isTrue();

    XdsClient xdsClient2 = xdsClientPool.getObject();
    assertThat(xdsClient2).isNotSameInstanceAs(xdsClient1);
    xdsClientPool.returnObject(xdsClient2);
  }
}
