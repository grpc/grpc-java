/*
 * Copyright 2019 The gRPC Authors
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Status;
import io.grpc.netty.InternalProtocolNegotiators;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.sds.SdsProtocolNegotiators.ServerSdsProtocolNegotiator;
import io.grpc.xds.internal.sds.ServerWrapperForXds;
import io.grpc.xds.internal.sds.XdsServerBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link XdsServerBuilder}.
 */
@RunWith(JUnit4.class)
public class XdsServerBuilderTest {

  @Rule public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();
  private XdsClient mockXdsClient;
  private ServerWrapperForXds xdsServer;
  private XdsClient.ListenerWatcher listenerWatcher;
  private int port;
  private XdsClientWrapperForServerSds xdsClientWrapperForServerSds;

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

  private void buildServer(XdsServerBuilder.ErrorNotifier errorNotifier) throws IOException {
    port = findFreePort();
    XdsServerBuilder builder = XdsServerBuilder.forPort(port);
    if (errorNotifier != null) {
      builder = builder.withErrorNotifier(errorNotifier);
    }
    mockXdsClient = mock(XdsClient.class);
    xdsClientWrapperForServerSds = new XdsClientWrapperForServerSds(port);
    listenerWatcher = startAndGetWatcher(xdsClientWrapperForServerSds, mockXdsClient, port);
    ServerSdsProtocolNegotiator serverSdsProtocolNegotiator =
        new ServerSdsProtocolNegotiator(
            xdsClientWrapperForServerSds, InternalProtocolNegotiators.serverPlaintext());
    xdsServer = cleanupRule.register(builder.buildServer(serverSdsProtocolNegotiator));
  }

  private void verifyServer(
      Future<Throwable> future, XdsServerBuilder.ErrorNotifier mockErrorNotifier)
      throws InterruptedException, ExecutionException, TimeoutException {
    if (future != null) {
      Throwable exception = future.get(5, TimeUnit.SECONDS);
      assertThat(exception).isNull();
    }
    List<? extends SocketAddress> list = xdsServer.getListenSockets();
    assertThat(list).hasSize(1);
    InetSocketAddress socketAddress = (InetSocketAddress) list.get(0);
    assertThat(socketAddress.getAddress().isAnyLocalAddress()).isTrue();
    assertThat(socketAddress.getPort()).isEqualTo(port);
    if (mockErrorNotifier != null) {
      verify(mockErrorNotifier, never()).onError(any(Status.class));
    }
    assertThat(xdsClientWrapperForServerSds.serverWatchers).isEmpty();
  }

  private void verifyShutdown() throws InterruptedException {
    xdsServer.shutdown();
    xdsServer.awaitTermination(500L, TimeUnit.MILLISECONDS);
    verify(mockXdsClient, times(1)).shutdown();
  }

  private Future<Throwable> startServerAsync() {
    final SettableFuture<Throwable> settableFuture = SettableFuture.create();
    Executors.newSingleThreadExecutor().execute(new Runnable() {
      @Override
      public void run() {
        try {
          xdsServer.start();
          settableFuture.set(null);
        } catch (Throwable e) {
          settableFuture.set(e);
        }
      }
    });
    return settableFuture;
  }

  @Test
  public void xdsServerStartAndShutdown()
      throws IOException, InterruptedException, TimeoutException, ExecutionException {
    buildServer(null);
    Future<Throwable> future = startServerAsync();
    generateListenerUpdate(
        listenerWatcher,
        port,
        port,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1"),
        null);
    verifyServer(future, null);
    verifyShutdown();
  }

  @Test
  public void xdsServerStartAfterListenerUpdate()
          throws IOException, InterruptedException, TimeoutException, ExecutionException {
    buildServer(null);
    generateListenerUpdate(
            listenerWatcher,
            port,
            port,
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1"),
            null);
    xdsServer.start();
    verifyServer(null,null);
    verifyShutdown();
  }

  @Test
  public void xdsServerStartAndShutdownWithErrorNotifier()
      throws IOException, InterruptedException, TimeoutException, ExecutionException {
    XdsServerBuilder.ErrorNotifier mockErrorNotifier = mock(XdsServerBuilder.ErrorNotifier.class);
    buildServer(mockErrorNotifier);
    Future<Throwable> future = startServerAsync();
    generateListenerUpdate(
        listenerWatcher,
        port,
        port,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1"),
        null);
    verifyServer(future, mockErrorNotifier);
    verifyShutdown();
  }

  @Test
  public void xdsServer_serverWatcher()
      throws IOException, InterruptedException, TimeoutException, ExecutionException {
    XdsServerBuilder.ErrorNotifier mockErrorNotifier = mock(XdsServerBuilder.ErrorNotifier.class);
    buildServer(mockErrorNotifier);
    Future<Throwable> future = startServerAsync();
    listenerWatcher.onError(Status.ABORTED);
    verify(mockErrorNotifier).onError(Status.ABORTED);
    assertThat(xdsClientWrapperForServerSds.serverWatchers).hasSize(1);
    assertThat(future.isDone()).isFalse();
    reset(mockErrorNotifier);
    listenerWatcher.onResourceDoesNotExist("not found error");
    ArgumentCaptor<Status> argCaptor = ArgumentCaptor.forClass(null);
    verify(mockErrorNotifier).onError(argCaptor.capture());
    Status captured = argCaptor.getValue();
    assertThat(captured.getCode()).isEqualTo(Status.Code.NOT_FOUND);
    assertThat(captured.getDescription()).isEqualTo("not found error");
    assertThat(xdsClientWrapperForServerSds.serverWatchers).hasSize(1);
    assertThat(future.isDone()).isFalse();
    reset(mockErrorNotifier);
    generateListenerUpdate(
        listenerWatcher,
        port,
        port,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1"),
        null);
    verifyServer(future, mockErrorNotifier);
    verifyShutdown();
  }

  @Test
  public void xdsServer_startError()
      throws IOException, InterruptedException, TimeoutException, ExecutionException {
    XdsServerBuilder.ErrorNotifier mockErrorNotifier = mock(XdsServerBuilder.ErrorNotifier.class);
    buildServer(mockErrorNotifier);
    Future<Throwable> future = startServerAsync();
    // create port conflict for start to fail
    ServerSocket serverSocket = new ServerSocket(port);
    generateListenerUpdate(
        listenerWatcher,
        port,
        port,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1"),
        null);
    Throwable exception = future.get(5, TimeUnit.SECONDS);
    assertThat(exception).isInstanceOf(IOException.class);
    assertThat(exception).hasMessageThat().contains("Failed to bind");
    verify(mockErrorNotifier, never()).onError(any(Status.class));
    serverSocket.close();
  }

  @Test
  public void xdsServerStartSecondUpdateAndError()
      throws IOException, InterruptedException, TimeoutException, ExecutionException {
    XdsServerBuilder.ErrorNotifier mockErrorNotifier = mock(XdsServerBuilder.ErrorNotifier.class);
    buildServer(mockErrorNotifier);
    Future<Throwable> future = startServerAsync();
    generateListenerUpdate(
        listenerWatcher,
        port,
        port,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1"),
        null);
    generateListenerUpdate(
        listenerWatcher,
        port,
        port,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1"),
        null);
    verify(mockErrorNotifier, never()).onError(any(Status.class));
    verifyServer(future, mockErrorNotifier);
    listenerWatcher.onError(Status.ABORTED);
    verify(mockErrorNotifier, never()).onError(any(Status.class));
    verifyServer(null, mockErrorNotifier);
    verifyShutdown();
  }

  private static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }
}
