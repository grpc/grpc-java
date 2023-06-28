/*
 * Copyright 2016 The gRPC Authors
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

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.TlsChannelCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.internal.testing.TestUtils;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.TlsTesting;
import io.grpc.testing.integration.Messages.ResponseParameters;
import io.grpc.testing.integration.Messages.StreamingOutputCallRequest;
import io.grpc.testing.integration.Messages.StreamingOutputCallResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;


/**
 * Tests that gRPC clients and servers can handle concurrent RPCs.
 *
 * <p>These tests use TLS to make them more realistic, and because we'd like to test the thread
 * safety of the TLS-related code paths as well.
 */
// TODO: Consider augmenting this class to perform non-streaming, client streaming, and
// bidirectional streaming requests also.
@RunWith(JUnit4.class)
public class ConcurrencyTest {

  /**
   * A response observer that completes a {@code ListenableFuture} when the proper number of
   * responses arrives and the server signals that the RPC is complete.
   */
  private static class SignalingResponseObserver
      implements StreamObserver<StreamingOutputCallResponse> {
    public SignalingResponseObserver(SettableFuture<Void> completionFuture) {
      this.completionFuture = completionFuture;
    }

    @Override
    public void onCompleted() {
      if (numResponsesReceived != NUM_RESPONSES_PER_REQUEST) {
        completionFuture.setException(
            new IllegalStateException("Wrong number of responses: " + numResponsesReceived));
      } else {
        completionFuture.set(null);
      }
    }

    @Override
    public void onError(Throwable error) {
      completionFuture.setException(error);
    }

    @Override
    public void onNext(StreamingOutputCallResponse response) {
      numResponsesReceived++;
    }

    private final SettableFuture<Void> completionFuture;
    private int numResponsesReceived = 0;
  }

  /**
   * A client worker task that waits until all client workers are ready, then sends a request for a
   * server-streaming RPC and arranges for a {@code ListenableFuture} to be signaled when the RPC is
   * complete.
   */
  private class ClientWorker implements Runnable {
    public ClientWorker(CyclicBarrier startBarrier, SettableFuture<Void> completionFuture) {
      this.startBarrier = startBarrier;
      this.completionFuture = completionFuture;
    }

    @Override
    public void run() {
      try {
        // Prepare the request.
        StreamingOutputCallRequest.Builder requestBuilder = StreamingOutputCallRequest.newBuilder();
        for (int i = 0; i < NUM_RESPONSES_PER_REQUEST; i++) {
          requestBuilder.addResponseParameters(ResponseParameters.newBuilder()
              .setSize(1000)
              .setIntervalUs(0));  // No delay between responses, for maximum concurrency.
        }
        StreamingOutputCallRequest request = requestBuilder.build();

        // Wait until all client worker threads are poised & ready, then send the request. This way
        // all clients send their requests at approximately the same time.
        startBarrier.await();
        clientStub.streamingOutputCall(request, new SignalingResponseObserver(completionFuture));
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        completionFuture.setException(ex);
      } catch (Throwable t) {
        completionFuture.setException(t);
      }
    }

    private final CyclicBarrier startBarrier;
    private final SettableFuture<Void> completionFuture;
  }

  private static final int NUM_SERVER_THREADS = 10;
  private static final int NUM_CONCURRENT_REQUESTS = 100;
  private static final int NUM_RESPONSES_PER_REQUEST = 100;

  private Server server;
  private ManagedChannel clientChannel;
  private TestServiceGrpc.TestServiceStub clientStub;
  private ScheduledExecutorService serverExecutor;
  private ExecutorService clientExecutor;

  @Before
  public void setUp() throws Exception {
    serverExecutor = Executors.newScheduledThreadPool(NUM_SERVER_THREADS);
    clientExecutor = Executors.newFixedThreadPool(NUM_CONCURRENT_REQUESTS);

    server = newServer();

    // Create the client. Keep a reference to its channel so we can shut it down during tearDown().
    clientChannel = newClientChannel();
    clientStub = TestServiceGrpc.newStub(clientChannel);
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.shutdown();
    }
    if (clientChannel != null) {
      clientChannel.shutdown();
    }

    MoreExecutors.shutdownAndAwaitTermination(serverExecutor, 5, TimeUnit.SECONDS);
    MoreExecutors.shutdownAndAwaitTermination(clientExecutor, 5, TimeUnit.SECONDS);
  }

  /**
   * Tests that gRPC can handle concurrent server-streaming RPCs.
   */
  @Test
  public void serverStreamingTest() throws Exception {
    CyclicBarrier startBarrier = new CyclicBarrier(NUM_CONCURRENT_REQUESTS);
    List<ListenableFuture<Void>> workerFutures = new ArrayList<>(NUM_CONCURRENT_REQUESTS);

    for (int i = 0; i < NUM_CONCURRENT_REQUESTS; i++) {
      SettableFuture<Void> future = SettableFuture.create();
      clientExecutor.execute(new ClientWorker(startBarrier, future));
      workerFutures.add(future);
    }

    Futures.allAsList(workerFutures).get(60, TimeUnit.SECONDS);
  }

  /**
   * Creates and starts a new {@link TestServiceImpl} server.
   */
  private Server newServer() throws IOException {
    ServerCredentials serverCreds = TlsServerCredentials.newBuilder()
        .keyManager(TlsTesting.loadCert("server1.pem"), TlsTesting.loadCert("server1.key"))
        .trustManager(TlsTesting.loadCert("ca.pem"))
        .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
        .build();

    return Grpc.newServerBuilderForPort(0, serverCreds)
        .addService(new TestServiceImpl(serverExecutor))
        .build()
        .start();
  }

  private ManagedChannel newClientChannel() throws IOException {
    ChannelCredentials channelCreds = TlsChannelCredentials.newBuilder()
        .keyManager(TlsTesting.loadCert("client.pem"), TlsTesting.loadCert("client.key"))
        .trustManager(TlsTesting.loadCert("ca.pem"))
        .build();

    return Grpc.newChannelBuilder("localhost:" + server.getPort(), channelCreds)
        .overrideAuthority(TestUtils.TEST_SERVER_HOST)
        .build();
  }
}
