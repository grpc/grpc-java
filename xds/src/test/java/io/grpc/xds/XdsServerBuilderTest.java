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
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.SettableFuture;
import io.grpc.BindableService;
import io.grpc.InsecureServerCredentials;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.sds.ServerWrapperForXds;
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
  private XdsServerBuilder builder;
  private ServerWrapperForXds xdsServer;
  private XdsClient.LdsResourceWatcher listenerWatcher;
  private int port;
  private XdsClientWrapperForServerSds xdsClientWrapperForServerSds;

  private void buildServer(
      XdsServerBuilder.XdsServingStatusListener xdsServingStatusListener,
      boolean injectMockXdsClient)
      throws IOException {
    buildBuilder(xdsServingStatusListener, injectMockXdsClient);
    xdsServer = cleanupRule.register(builder.buildServer(xdsClientWrapperForServerSds));
  }

  private void buildBuilder(XdsServerBuilder.XdsServingStatusListener xdsServingStatusListener,
      boolean injectMockXdsClient) throws IOException {
    port = XdsServerTestHelper.findFreePort();
    builder =
        XdsServerBuilder.forPort(
            port, XdsServerCredentials.create(InsecureServerCredentials.create()));
    if (xdsServingStatusListener != null) {
      builder = builder.xdsServingStatusListener(xdsServingStatusListener);
    }
    xdsClientWrapperForServerSds = new XdsClientWrapperForServerSds(port);
    if (injectMockXdsClient) {
      mockXdsClient = mock(XdsClient.class);
      listenerWatcher =
          XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds, mockXdsClient, port);
    }
  }

  private void verifyServer(
      Future<Throwable> future,
      XdsServerBuilder.XdsServingStatusListener mockXdsServingStatusListener,
      Status notServingStatus)
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
    if (mockXdsServingStatusListener != null) {
      if (notServingStatus != null) {
        ArgumentCaptor<Throwable> argCaptor = ArgumentCaptor.forClass(null);
        verify(mockXdsServingStatusListener, times(1)).onNotServing(argCaptor.capture());
        Throwable throwable = argCaptor.getValue();
        assertThat(throwable).isInstanceOf(StatusException.class);
        assertThat(((StatusException) throwable).getStatus()).isEqualTo(notServingStatus);
      } else {
        verify(mockXdsServingStatusListener, never()).onNotServing(any(Throwable.class));
        verify(mockXdsServingStatusListener, times(1)).onServing();
      }
    }
  }

  private void verifyShutdown() throws InterruptedException {
    xdsServer.shutdown();
    xdsServer.awaitTermination(500L, TimeUnit.MILLISECONDS);
    verify(mockXdsClient, times(1)).shutdown();
  }

  private Future<Throwable> startServerAsync() throws InterruptedException {
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
    // wait until xdsClientWrapperForServerSds.serverWatchers populated
    for (int i = 0; i < 10 && xdsClientWrapperForServerSds.serverWatchers.isEmpty(); i++) {
      Thread.sleep(100L);
    }
    return settableFuture;
  }

  @Test
  public void xdsServerStartAndShutdown()
      throws IOException, InterruptedException, TimeoutException, ExecutionException {
    buildServer(null, true);
    Future<Throwable> future = startServerAsync();
    XdsServerTestHelper.generateListenerUpdate(
        listenerWatcher,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1")
    );
    verifyServer(future, null, null);
    verifyShutdown();
  }

  @Test
  public void xdsServerStartAfterListenerUpdate()
          throws IOException, InterruptedException, TimeoutException, ExecutionException {
    buildServer(null, true);
    XdsServerTestHelper.generateListenerUpdate(
            listenerWatcher,
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1")
    );
    xdsServer.start();
    try {
      xdsServer.start();
      fail("expected exception");
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessageThat().contains("Already started");
    }
    verifyServer(null,null, null);
    verifyShutdown();
  }

  @Test
  public void xdsServerStartAndShutdownWithXdsServingStatusListener()
      throws IOException, InterruptedException, TimeoutException, ExecutionException {
    XdsServerBuilder.XdsServingStatusListener mockXdsServingStatusListener =
        mock(XdsServerBuilder.XdsServingStatusListener.class);
    buildServer(mockXdsServingStatusListener, true);
    Future<Throwable> future = startServerAsync();
    XdsServerTestHelper.generateListenerUpdate(
        listenerWatcher,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1")
    );
    verifyServer(future, mockXdsServingStatusListener, null);
    verifyShutdown();
  }

  @Test
  public void xdsServer_serverWatcher()
      throws IOException, InterruptedException, TimeoutException, ExecutionException {
    XdsServerBuilder.XdsServingStatusListener mockXdsServingStatusListener =
        mock(XdsServerBuilder.XdsServingStatusListener.class);
    buildServer(mockXdsServingStatusListener, true);
    Future<Throwable> future = startServerAsync();
    listenerWatcher.onError(Status.ABORTED);
    ArgumentCaptor<Throwable> argCaptor = ArgumentCaptor.forClass(null);
    verify(mockXdsServingStatusListener).onNotServing(argCaptor.capture());
    Throwable throwable = argCaptor.getValue();
    assertThat(throwable).isInstanceOf(StatusException.class);
    Status captured = ((StatusException) throwable).getStatus();
    assertThat(captured.getCode()).isEqualTo(Status.Code.ABORTED);
    assertThat(xdsClientWrapperForServerSds.serverWatchers).hasSize(1);
    assertThat(future.isDone()).isFalse();
    reset(mockXdsServingStatusListener);
    listenerWatcher.onError(Status.NOT_FOUND);
    argCaptor = ArgumentCaptor.forClass(null);
    verify(mockXdsServingStatusListener).onNotServing(argCaptor.capture());
    throwable = argCaptor.getValue();
    assertThat(throwable).isInstanceOf(StatusException.class);
    captured = ((StatusException) throwable).getStatus();
    assertThat(captured.getCode()).isEqualTo(Status.Code.NOT_FOUND);
    reset(mockXdsServingStatusListener);
    listenerWatcher.onResourceDoesNotExist("not found error");
    argCaptor = ArgumentCaptor.forClass(null);
    verify(mockXdsServingStatusListener).onNotServing(argCaptor.capture());
    throwable = argCaptor.getValue();
    assertThat(throwable).isInstanceOf(StatusException.class);
    captured = ((StatusException) throwable).getStatus();
    assertThat(captured.getCode()).isEqualTo(Status.Code.NOT_FOUND);
    assertThat(future.isDone()).isFalse();
    reset(mockXdsServingStatusListener);
    XdsServerTestHelper.generateListenerUpdate(
        listenerWatcher,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1")
    );
    verifyServer(future, mockXdsServingStatusListener, null);
    verifyShutdown();
  }

  @Test
  public void xdsServer_startError()
      throws IOException, InterruptedException, TimeoutException, ExecutionException {
    XdsServerBuilder.XdsServingStatusListener mockXdsServingStatusListener =
        mock(XdsServerBuilder.XdsServingStatusListener.class);
    buildServer(mockXdsServingStatusListener, true);
    Future<Throwable> future = startServerAsync();
    // create port conflict for start to fail
    ServerSocket serverSocket = new ServerSocket(port);
    XdsServerTestHelper.generateListenerUpdate(
        listenerWatcher,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1")
    );
    Throwable exception = future.get(5, TimeUnit.SECONDS);
    assertThat(exception).isInstanceOf(IOException.class);
    assertThat(exception).hasMessageThat().contains("Failed to bind");
    verify(mockXdsServingStatusListener, never()).onNotServing(any(Throwable.class));
    serverSocket.close();
  }

  @Test
  public void xdsServerWithoutMockXdsClient_startError()
      throws IOException, InterruptedException, TimeoutException, ExecutionException {
    XdsServerBuilder.XdsServingStatusListener mockXdsServingStatusListener =
        mock(XdsServerBuilder.XdsServingStatusListener.class);
    buildServer(mockXdsServingStatusListener, false);
    try {
      xdsServer.start();
      fail("exception expected");
    } catch (IOException expected) {
      assertThat(expected).hasMessageThat().contains("Cannot find bootstrap configuration");
    }
    verify(mockXdsServingStatusListener, never()).onNotServing(any(Throwable.class));
  }

  @Test
  public void xdsServerStartSecondUpdateAndError()
      throws IOException, InterruptedException, TimeoutException, ExecutionException {
    XdsServerBuilder.XdsServingStatusListener mockXdsServingStatusListener =
        mock(XdsServerBuilder.XdsServingStatusListener.class);
    buildServer(mockXdsServingStatusListener, true);
    Future<Throwable> future = startServerAsync();
    XdsServerTestHelper.generateListenerUpdate(
        listenerWatcher,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1")
    );
    XdsServerTestHelper.generateListenerUpdate(
        listenerWatcher,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1")
    );
    verify(mockXdsServingStatusListener, never()).onNotServing(any(Throwable.class));
    verifyServer(future, mockXdsServingStatusListener, null);
    listenerWatcher.onError(Status.ABORTED);
    verifyServer(null, mockXdsServingStatusListener, null);
    verifyShutdown();
  }

  @Test
  public void xdsServer_2ndBuild_expectException() throws IOException {
    XdsServerBuilder.XdsServingStatusListener mockXdsServingStatusListener =
        mock(XdsServerBuilder.XdsServingStatusListener.class);
    buildServer(mockXdsServingStatusListener, true);
    try {
      builder.build();
      fail("exception expected");
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessageThat().contains("Server already built!");
    }
  }

  @Test
  public void xdsServer_2ndSetter_expectException() throws IOException {
    XdsServerBuilder.XdsServingStatusListener mockXdsServingStatusListener =
        mock(XdsServerBuilder.XdsServingStatusListener.class);
    buildBuilder(mockXdsServingStatusListener, true);
    BindableService mockBindableService = mock(BindableService.class);
    ServerServiceDefinition serverServiceDefinition = io.grpc.ServerServiceDefinition
        .builder("mock").build();
    when(mockBindableService.bindService()).thenReturn(serverServiceDefinition);
    builder.addService(mockBindableService);
    xdsServer = cleanupRule.register(builder.buildServer(xdsClientWrapperForServerSds));
    try {
      builder.addService(mock(BindableService.class));
      fail("exception expected");
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessageThat().contains("Server already built!");
    }
  }
}
