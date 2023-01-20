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

package io.grpc.benchmarks.driver;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.common.util.concurrent.SettableFuture;
import io.grpc.ManagedChannel;
import io.grpc.benchmarks.Utils;
import io.grpc.benchmarks.proto.Control;
import io.grpc.benchmarks.proto.Stats;
import io.grpc.benchmarks.proto.WorkerServiceGrpc;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Basic tests for {@link io.grpc.benchmarks.driver.LoadWorker}.
 */
@RunWith(JUnit4.class)
public class LoadWorkerTest {


  private static final int TIMEOUT = 10;
  private static final Control.ClientArgs MARK = Control.ClientArgs.newBuilder()
      .setMark(Control.Mark.newBuilder().setReset(true).build())
      .build();

  private LoadWorker worker;
  private ManagedChannel channel;
  private WorkerServiceGrpc.WorkerServiceStub workerServiceStub;
  private LinkedBlockingQueue<Stats.ClientStats> marksQueue;
  private StreamObserver<Control.ServerArgs> serverLifetime;

  @Before
  public void setup() throws Exception {
    int port = Utils.pickUnusedPort();
    worker = new LoadWorker(port, 0);
    worker.start();
    channel = NettyChannelBuilder.forAddress("localhost", port).usePlaintext().build();
    workerServiceStub = WorkerServiceGrpc.newStub(channel);
    marksQueue = new LinkedBlockingQueue<>();
  }

  @After
  public void tearDown() {
    if (serverLifetime != null) {
      serverLifetime.onCompleted();
    }
    try {
      WorkerServiceGrpc.newBlockingStub(channel).quitWorker(Control.Void.getDefaultInstance());
    } finally {
      channel.shutdownNow();
    }
  }

  @Test
  public void runUnaryBlockingClosedLoop() throws Exception {
    Control.ServerArgs.Builder serverArgsBuilder = Control.ServerArgs.newBuilder();
    serverArgsBuilder.getSetupBuilder()
        .setServerType(Control.ServerType.ASYNC_SERVER)
        .setAsyncServerThreads(4)
        .setPort(0)
        .getPayloadConfigBuilder().getSimpleParamsBuilder().setRespSize(1000);
    int serverPort = startServer(serverArgsBuilder.build());

    Control.ClientArgs.Builder clientArgsBuilder = Control.ClientArgs.newBuilder();
    String serverAddress = "localhost:" + serverPort;
    clientArgsBuilder.getSetupBuilder()
        .setClientType(Control.ClientType.SYNC_CLIENT)
        .setRpcType(Control.RpcType.UNARY)
        .setClientChannels(2)
        .setOutstandingRpcsPerChannel(2)
        .addServerTargets(serverAddress);
    clientArgsBuilder.getSetupBuilder().getPayloadConfigBuilder().getSimpleParamsBuilder()
        .setReqSize(1000)
        .setRespSize(1000);
    clientArgsBuilder.getSetupBuilder().getHistogramParamsBuilder()
        .setResolution(0.01)
        .setMaxPossible(60000000000.0);
    StreamObserver<Control.ClientArgs> clientObserver = startClient(clientArgsBuilder.build());
    assertWorkOccurred(clientObserver);
  }

  @Test
  public void runUnaryAsyncClosedLoop() throws Exception {
    Control.ServerArgs.Builder serverArgsBuilder = Control.ServerArgs.newBuilder();
    serverArgsBuilder.getSetupBuilder()
        .setServerType(Control.ServerType.ASYNC_SERVER)
        .setAsyncServerThreads(4)
        .setPort(0)
        .getPayloadConfigBuilder().getSimpleParamsBuilder().setRespSize(1000);
    int serverPort = startServer(serverArgsBuilder.build());

    Control.ClientArgs.Builder clientArgsBuilder = Control.ClientArgs.newBuilder();
    String serverAddress = "localhost:" + serverPort;
    clientArgsBuilder.getSetupBuilder()
        .setClientType(Control.ClientType.ASYNC_CLIENT)
        .setClientChannels(2)
        .setRpcType(Control.RpcType.UNARY)
        .setOutstandingRpcsPerChannel(1)
        .setAsyncClientThreads(4)
        .addServerTargets(serverAddress);
    clientArgsBuilder.getSetupBuilder().getPayloadConfigBuilder().getSimpleParamsBuilder()
        .setReqSize(1000)
        .setRespSize(1000);
    clientArgsBuilder.getSetupBuilder().getHistogramParamsBuilder()
        .setResolution(0.01)
        .setMaxPossible(60000000000.0);
    StreamObserver<Control.ClientArgs> clientObserver = startClient(clientArgsBuilder.build());
    assertWorkOccurred(clientObserver);
  }

  @Test
  public void runPingPongAsyncClosedLoop() throws Exception {
    Control.ServerArgs.Builder serverArgsBuilder = Control.ServerArgs.newBuilder();
    serverArgsBuilder.getSetupBuilder()
        .setServerType(Control.ServerType.ASYNC_SERVER)
        .setAsyncServerThreads(4)
        .setPort(0)
        .getPayloadConfigBuilder().getSimpleParamsBuilder().setRespSize(1000);
    int serverPort = startServer(serverArgsBuilder.build());

    Control.ClientArgs.Builder clientArgsBuilder = Control.ClientArgs.newBuilder();
    String serverAddress = "localhost:" + serverPort;
    clientArgsBuilder.getSetupBuilder()
        .setClientType(Control.ClientType.ASYNC_CLIENT)
        .setClientChannels(2)
        .setRpcType(Control.RpcType.STREAMING)
        .setOutstandingRpcsPerChannel(1)
        .setAsyncClientThreads(4)
        .addServerTargets(serverAddress);
    clientArgsBuilder.getSetupBuilder().getPayloadConfigBuilder().getSimpleParamsBuilder()
        .setReqSize(1000)
        .setRespSize(1000);
    clientArgsBuilder.getSetupBuilder().getHistogramParamsBuilder()
        .setResolution(0.01)
        .setMaxPossible(60000000000.0);
    StreamObserver<Control.ClientArgs> clientObserver = startClient(clientArgsBuilder.build());
    assertWorkOccurred(clientObserver);
  }

  @Test
  public void runGenericPingPongAsyncClosedLoop() throws Exception {
    Control.ServerArgs.Builder serverArgsBuilder = Control.ServerArgs.newBuilder();
    serverArgsBuilder.getSetupBuilder()
        .setServerType(Control.ServerType.ASYNC_GENERIC_SERVER)
        .setAsyncServerThreads(4)
        .setPort(0)
        .getPayloadConfigBuilder().getBytebufParamsBuilder().setReqSize(1000).setRespSize(1000);
    int serverPort = startServer(serverArgsBuilder.build());

    Control.ClientArgs.Builder clientArgsBuilder = Control.ClientArgs.newBuilder();
    String serverAddress = "localhost:" + serverPort;
    clientArgsBuilder.getSetupBuilder()
        .setClientType(Control.ClientType.ASYNC_CLIENT)
        .setClientChannels(2)
        .setRpcType(Control.RpcType.STREAMING)
        .setOutstandingRpcsPerChannel(1)
        .setAsyncClientThreads(4)
        .addServerTargets(serverAddress);
    clientArgsBuilder.getSetupBuilder().getPayloadConfigBuilder().getBytebufParamsBuilder()
        .setReqSize(1000)
        .setRespSize(1000);
    clientArgsBuilder.getSetupBuilder().getHistogramParamsBuilder()
        .setResolution(0.01)
        .setMaxPossible(60000000000.0);
    StreamObserver<Control.ClientArgs> clientObserver = startClient(clientArgsBuilder.build());
    assertWorkOccurred(clientObserver);
  }

  private void assertWorkOccurred(StreamObserver<Control.ClientArgs> clientObserver)
      throws InterruptedException {

    Stats.ClientStats stat = null;
    for (int i = 0; i < 30; i++) {
      // Poll until we get some stats
      Thread.sleep(300);
      clientObserver.onNext(MARK);
      stat = marksQueue.poll(TIMEOUT, TimeUnit.SECONDS);
      if (stat == null) {
        fail("Did not receive stats");
      }
      if (stat.getLatencies().getCount() > 10) {
        break;
      }
    }
    clientObserver.onCompleted();
    assertThat(stat.hasLatencies()).isTrue();
    assertThat(stat.getLatencies().getCount()).isLessThan(stat.getLatencies().getSum());
    double mean = stat.getLatencies().getSum() / stat.getLatencies().getCount();
    System.out.println("Mean " + mean + " ns");
    assertThat(stat.getLatencies().getMinSeen()).isLessThan(mean);
    assertThat(stat.getLatencies().getMaxSeen()).isGreaterThan(mean);
  }

  private StreamObserver<Control.ClientArgs> startClient(Control.ClientArgs clientArgs)
      throws Exception {
    final SettableFuture<Void> clientReady = SettableFuture.create();
    StreamObserver<Control.ClientArgs> clientObserver = workerServiceStub.runClient(
        new StreamObserver<Control.ClientStatus>() {
          @Override
          public void onNext(Control.ClientStatus value) {
            clientReady.set(null);
            if (value.hasStats()) {
              marksQueue.add(value.getStats());
            }
          }

          @Override
          public void onError(Throwable t) {
            clientReady.setException(t);
          }

          @Override
          public void onCompleted() {
            clientReady.setException(
                new RuntimeException("onCompleted() before receiving response"));
          }
        });

    // Start the client
    clientObserver.onNext(clientArgs);
    clientReady.get(TIMEOUT, TimeUnit.SECONDS);
    return clientObserver;
  }

  private int startServer(Control.ServerArgs serverArgs) throws Exception {
    final SettableFuture<Integer> port = SettableFuture.create();
    serverLifetime =
        workerServiceStub.runServer(new StreamObserver<Control.ServerStatus>() {
          @Override
          public void onNext(Control.ServerStatus value) {
            port.set(value.getPort());
          }

          @Override
          public void onError(Throwable t) {
            port.setException(t);
          }

          @Override
          public void onCompleted() {
            port.setException(new RuntimeException("onCompleted() before receiving response"));
          }
        });
    // trigger server startup
    serverLifetime.onNext(serverArgs);
    return port.get(TIMEOUT, TimeUnit.SECONDS);
  }
}
