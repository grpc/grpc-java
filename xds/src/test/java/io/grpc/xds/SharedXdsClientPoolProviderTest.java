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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.Bootstrapper.ChannelCreds;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.SharedXdsClientPoolProvider.RefCountedXdsClientObjectPool;
import io.grpc.xds.SharedXdsClientPoolProvider.RefCountedXdsClientObjectPool.XdsClientFactory;
import io.grpc.xds.XdsClient.XdsChannel;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatchers;

/** Tests for {@link SharedXdsClientPoolProvider}. */
@RunWith(JUnit4.class)
public class SharedXdsClientPoolProviderTest {

  private final XdsChannel channel = new XdsChannel(mock(ManagedChannel.class), false);
  private final Node node = Node.newBuilder().setId("SharedXdsClientPoolProviderTest").build();
  private final AtomicReference<XdsClient> xdsClientRef = new AtomicReference<>();
  private final XdsClientFactory factory = new XdsClientFactory() {
    @Override
    XdsClient newXdsClient(XdsChannel channel, Node node, ScheduledExecutorService timeService) {
      XdsClient xdsClient = mock(XdsClient.class);
      xdsClientRef.set(xdsClient);
      return xdsClient;
    }
  };

  @Test
  public void getXdsClientPool_sharedInstance() throws XdsInitializationException {
    ServerInfo server =
        new ServerInfo("trafficdirector.googleapis.com",
            Collections.singletonList(new ChannelCreds("insecure", null)),
            Collections.<String>emptyList());
    BootstrapInfo bootstrapInfo = new BootstrapInfo(Collections.singletonList(server), node, null);
    Bootstrapper bootstrapper = mock(Bootstrapper.class);
    when(bootstrapper.readBootstrap()).thenReturn(bootstrapInfo);
    XdsChannelFactory channelFactory = mock(XdsChannelFactory.class);
    when(channelFactory.createChannel(ArgumentMatchers.<ServerInfo>anyList())).thenReturn(channel);

    SharedXdsClientPoolProvider provider =
        new SharedXdsClientPoolProvider(bootstrapper, channelFactory);

    ObjectPool<XdsClient> xdsClientPool = provider.getXdsClientPool();
    verify(bootstrapper).readBootstrap();
    verify(channelFactory).createChannel(Collections.singletonList(server));
    assertThat(provider.getXdsClientPool()).isSameInstanceAs(xdsClientPool);
    verifyNoMoreInteractions(bootstrapper, channelFactory);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  @Test
  public void getXdsClientPool_concurrent()
      throws InterruptedException, XdsInitializationException {
    int numThreads = 3;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    BootstrapInfo bootstrapInfo = new BootstrapInfo(
        Collections.singletonList(
            new ServerInfo("trafficdirector.googleapis.com",
                Collections.singletonList(new ChannelCreds("insecure", null)),
                Collections.<String>emptyList())),
        node, null);
    Bootstrapper bootstrapper = mock(Bootstrapper.class);
    when(bootstrapper.readBootstrap()).thenReturn(bootstrapInfo);
    XdsChannelFactory channelFactory = mock(XdsChannelFactory.class);
    when(channelFactory.createChannel(ArgumentMatchers.<ServerInfo>anyList())).thenReturn(channel);

    final SharedXdsClientPoolProvider provider =
        new SharedXdsClientPoolProvider(bootstrapper, channelFactory);

    final ObjectPool<XdsClient>[] xdsClientPools = new ObjectPool[numThreads];
    final CountDownLatch latch = new CountDownLatch(numThreads);
    class GetXdsClientPool implements Runnable {
      private final int index;

      private GetXdsClientPool(int index) {
        this.index = index;
      }

      @Override
      public void run() {
        try {
          xdsClientPools[index] = provider.getXdsClientPool();
        } catch (XdsInitializationException e) {
          throw new RuntimeException(e);
        }
        latch.countDown();
      }
    }

    for (int i = 0; i < numThreads; i++) {
      executor.execute(new GetXdsClientPool(i));
    }
    latch.await(1L, TimeUnit.SECONDS);
    assertThat(xdsClientPools[0]).isNotNull();
    for (int i = 1; i < numThreads; i++) {
      assertThat(xdsClientPools[i]).isSameInstanceAs(xdsClientPools[0]);
    }
    verify(bootstrapper).readBootstrap();
    verify(channelFactory).createChannel(ArgumentMatchers.<ServerInfo>anyList());
    executor.shutdown();
  }

  @Test
  public void refCountedXdsClientObjectPool_delayedCreation() {
    RefCountedXdsClientObjectPool xdsClientPool =
        new RefCountedXdsClientObjectPool(channel, node, factory);
    assertThat(xdsClientRef.get()).isNull();
    xdsClientPool.getObject();
    assertThat(xdsClientRef.get()).isNotNull();
  }

  @Test
  public void refCountedXdsClientObjectPool_refCounted() {
    RefCountedXdsClientObjectPool xdsClientPool =
        new RefCountedXdsClientObjectPool(channel, node, factory);

    // getObject once
    XdsClient xdsClient = xdsClientPool.getObject();
    assertThat(xdsClient).isNotNull();
    // getObject twice
    assertThat(xdsClientPool.getObject()).isSameInstanceAs(xdsClient);
    // returnObject once
    assertThat(xdsClientPool.returnObject(xdsClient)).isNull();
    verify(xdsClient, never()).shutdown();
    // returnObject twice
    assertThat(xdsClientPool.returnObject(xdsClient)).isNull();
    verify(xdsClient).shutdown();
  }

  @Test
  public void refCountedXdsClientObjectPool_getObjectCreatesNewInstanceIfAlreadyShutdown() {
    RefCountedXdsClientObjectPool xdsClientPool =
        new RefCountedXdsClientObjectPool(channel, node, factory);
    XdsClient xdsClient1 = xdsClientPool.getObject();
    verify(xdsClient1, never()).shutdown();
    assertThat(xdsClientPool.returnObject(xdsClient1)).isNull();
    verify(xdsClient1).shutdown();

    XdsClient xdsClient2 = xdsClientPool.getObject();
    assertThat(xdsClient2).isNotSameInstanceAs(xdsClient1);
  }

  @Test
  public void refCountedXdsClientObjectPool_concurrentGet() throws InterruptedException {
    int numThreads = 3;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    final RefCountedXdsClientObjectPool xdsClientPool =
        new RefCountedXdsClientObjectPool(channel, node, factory);
    final XdsClient[] xdsClients = new XdsClient[numThreads];
    final CountDownLatch latch = new CountDownLatch(numThreads);
    class GetXdsClient implements Runnable {
      private final int index;

      private GetXdsClient(int index) {
        this.index = index;
      }

      @Override
      public void run() {
        xdsClients[index] = xdsClientPool.getObject();
        latch.countDown();
      }
    }

    for (int i = 0; i < numThreads; i++) {
      executor.execute(new GetXdsClient(i));
    }
    latch.await(1L, TimeUnit.SECONDS);
    assertThat(xdsClientRef.get()).isNotNull();
    for (int i = 0; i < numThreads; i++) {
      assertThat(xdsClients[i]).isSameInstanceAs(xdsClientRef.get());
    }
    executor.shutdown();
  }

  @Test
  public void refCountedXdsClientObjectPool_concurrentReturn() throws InterruptedException {
    int numThreads = 3;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    final RefCountedXdsClientObjectPool xdsClientPool =
        new RefCountedXdsClientObjectPool(channel, node, factory);
    final XdsClient[] xdsClients = new XdsClient[numThreads];
    for (int i = 0; i < numThreads; i++) {
      xdsClients[i] = xdsClientPool.getObject();
    }
    final CountDownLatch latch = new CountDownLatch(numThreads);
    class ReturnXdsClient implements Runnable {
      private final int index;

      private ReturnXdsClient(int index) {
        this.index = index;
      }

      @Override
      public void run() {
        xdsClients[index] = xdsClientPool.returnObject(xdsClients[index]);
        latch.countDown();
      }
    }

    for (int i = 0; i < numThreads; i++) {
      executor.execute(new ReturnXdsClient(i));
    }
    latch.await(1L, TimeUnit.SECONDS);
    for (int i = 0; i < numThreads; i++) {
      assertThat(xdsClients[i]).isNull();
    }
    verify(xdsClientRef.get()).shutdown();
    executor.shutdown();
  }
}
