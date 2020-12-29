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
import io.grpc.ManagedChannel;
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
        new BootstrapInfo(Collections.<ServerInfo>emptyList(), node, null, null);
    when(bootstrapper.bootstrap()).thenReturn(bootstrapInfo);
    SharedXdsClientPoolProvider provider = new SharedXdsClientPoolProvider(bootstrapper);
    thrown.expect(XdsInitializationException.class);
    thrown.expectMessage("No xDS server provided");
    provider.getXdsClientPool();
  }

  @Test
  public void sharedXdsClientObjectPool() throws XdsInitializationException {
    ServerInfo server = new ServerInfo(SERVER_URI, InsecureChannelCredentials.create(), false);
    BootstrapInfo bootstrapInfo =
        new BootstrapInfo(Collections.singletonList(server), node, null, null);
    when(bootstrapper.bootstrap()).thenReturn(bootstrapInfo);

    SharedXdsClientPoolProvider provider = new SharedXdsClientPoolProvider(bootstrapper);
    ObjectPool<XdsClient> xdsClientPool = provider.getXdsClientPool();
    verify(bootstrapper).bootstrap();
    assertThat(provider.getXdsClientPool()).isSameInstanceAs(xdsClientPool);
    verifyNoMoreInteractions(bootstrapper);
  }

  @Test
  public void refCountedXdsClientObjectPool_delayedCreation() {
    RefCountedXdsClientObjectPool xdsClientPool = new RefCountedXdsClientObjectPool(
        SERVER_URI, InsecureChannelCredentials.create(), false, node);
    assertThat(xdsClientPool.getXdsClientForTest()).isNull();
    assertThat(xdsClientPool.getChannelForTest()).isNull();
    XdsClient xdsClient = xdsClientPool.getObject();
    assertThat(xdsClientPool.getXdsClientForTest()).isNotNull();
    xdsClientPool.returnObject(xdsClient);
  }

  @Test
  public void refCountedXdsClientObjectPool_refCounted() {
    RefCountedXdsClientObjectPool xdsClientPool = new RefCountedXdsClientObjectPool(
        SERVER_URI, InsecureChannelCredentials.create(), false, node);
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
    assertThat(xdsClientPool.getChannelForTest().isShutdown()).isTrue();
  }

  @Test
  public void refCountedXdsClientObjectPool_getObjectCreatesNewInstanceIfAlreadyShutdown() {
    RefCountedXdsClientObjectPool xdsClientPool = new RefCountedXdsClientObjectPool(
        SERVER_URI, InsecureChannelCredentials.create(), false, node);
    XdsClient xdsClient1 = xdsClientPool.getObject();
    ManagedChannel channel1 = xdsClientPool.getChannelForTest();
    assertThat(xdsClientPool.returnObject(xdsClient1)).isNull();
    assertThat(xdsClient1.isShutDown()).isTrue();
    assertThat(channel1.isShutdown()).isTrue();

    XdsClient xdsClient2 = xdsClientPool.getObject();
    assertThat(xdsClient2).isNotSameInstanceAs(xdsClient1);
    assertThat(xdsClientPool.getChannelForTest()).isNotSameInstanceAs(channel1);
    xdsClientPool.returnObject(xdsClient2);
  }
}
