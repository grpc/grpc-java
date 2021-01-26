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

package io.grpc.testing.integration;

import static io.grpc.testing.integration.AbstractInteropTest.EMPTY;
import static org.junit.Assert.assertEquals;

import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.ServerInterceptors;
import io.grpc.alts.AltsChannelCredentials;
import io.grpc.alts.AltsServerCredentials;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AltsHandshakerTest {
  private ScheduledExecutorService executor;
  private Server testServer;
  private Server handshakeServer;
  private ManagedChannel channel;

  private final int handshakerServerPort = 8000;
  private final int testServerPort = 8080;
  private final String serverHost = "localhost";

  private void startHandshakerServer() throws Exception {
    handshakeServer = Grpc.newServerBuilderForPort(handshakerServerPort,
        InsecureServerCredentials.create())
        .addService(ServerInterceptors.intercept(new AltsHandshakerTestService()))
        .build()
        .start();
  }

  private void startAltsServer() throws Exception {
    executor = Executors.newSingleThreadScheduledExecutor();
    ServerCredentials serverCreds = AltsServerCredentials.newBuilder()
        .enableUntrustedAltsForTesting()
        .setHandshakerAddressForTesting(serverHost + ":" + handshakerServerPort)
        .build();
    testServer = Grpc.newServerBuilderForPort(testServerPort, serverCreds)
        .addService(ServerInterceptors.intercept(new TestServiceImpl(executor)))
        .build()
        .start();
  }

  @Before
  public void setup() throws Exception {
    startHandshakerServer();
    startAltsServer();

    ChannelCredentials channelCredentials = AltsChannelCredentials.newBuilder()
        .enableUntrustedAltsForTesting()
        .setHandshakerAddressForTesting(serverHost + ":" + handshakerServerPort).build();
    channel = Grpc.newChannelBuilderForAddress(serverHost, testServerPort, channelCredentials)
        .build();
  }

  @After
  public void stop() throws Exception {
    if (testServer != null) {
      testServer.shutdown();
      testServer.awaitTermination();
    }
    if (handshakeServer != null) {
      handshakeServer.shutdown();
      handshakeServer.awaitTermination();
    }
    if (channel != null) {
      channel.shutdown();
      channel.awaitTermination(1, TimeUnit.SECONDS);
    }
    MoreExecutors.shutdownAndAwaitTermination(executor, 10, TimeUnit.SECONDS);
  }

  @Test
  public void testAlts() {
    TestServiceGrpc.TestServiceBlockingStub blockingStub = TestServiceGrpc.newBlockingStub(channel);
    assertEquals(EMPTY, blockingStub.emptyCall(EMPTY));
  }
}
