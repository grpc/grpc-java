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
import io.grpc.xds.XdsServerTestHelper.FakeXdsClient;
import io.grpc.xds.XdsServerTestHelper.FakeXdsClientPoolFactory;
import io.grpc.xds.internal.security.CommonTlsContextTestsUtil;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

// TODO (zivy@): move certain tests down to XdsServerWrapperTest or to XdsSecurityClientServerTest
/**
 * Unit tests for {@link XdsServerBuilder}.
 */
@RunWith(JUnit4.class)
public class XdsServerBuilderTest {

  @Rule public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();
  private XdsServerBuilder builder;
  private XdsServerWrapper xdsServer;
  private int port;
  private TlsContextManager tlsContextManager;
  private FakeXdsClient xdsClient = new FakeXdsClient();
  private FakeXdsClientPoolFactory xdsClientPoolFactory = new FakeXdsClientPoolFactory(xdsClient);

  private void buildServer(XdsServerBuilder.XdsServingStatusListener xdsServingStatusListener)
      throws IOException {
    buildBuilder(xdsServingStatusListener);
    xdsServer = cleanupRule.register((XdsServerWrapper) builder.build());
  }

  private void buildBuilder(XdsServerBuilder.XdsServingStatusListener xdsServingStatusListener)
      throws IOException {
    builder =
        XdsServerBuilder.forPort(
            port, XdsServerCredentials.create(InsecureServerCredentials.create()));
    builder.xdsClientPoolFactory(xdsClientPoolFactory);
    if (xdsServingStatusListener != null) {
      builder.xdsServingStatusListener(xdsServingStatusListener);
    }
    tlsContextManager = mock(TlsContextManager.class);
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
    assertThat(socketAddress.getPort()).isGreaterThan(-1);
    if (mockXdsServingStatusListener != null) {
      if (notServingStatus != null) {
        ArgumentCaptor<Throwable> argCaptor = ArgumentCaptor.forClass(Throwable.class);
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
    assertThat(xdsClient.isShutDown()).isTrue();
  }

  private Future<Throwable> startServerAsync() throws
          InterruptedException, TimeoutException, ExecutionException {
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
    xdsClient.ldsResource.get(5000, TimeUnit.MILLISECONDS);
    return settableFuture;
  }

  @Test
  public void xdsServerStartAndShutdown()
      throws IOException, InterruptedException, TimeoutException, ExecutionException {
    buildServer(null);
    Future<Throwable> future = startServerAsync();
    XdsServerTestHelper.generateListenerUpdate(
        xdsClient,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1"),
        tlsContextManager);
    verifyServer(future, null, null);
    verifyShutdown();
  }

  @Test
  public void xdsServerRestartAfterListenerUpdate()
          throws IOException, InterruptedException, TimeoutException, ExecutionException {
    buildServer(null);
    Future<Throwable> future = startServerAsync();
    XdsServerTestHelper.generateListenerUpdate(
            xdsClient,
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1"),
            tlsContextManager);
    try {
      xdsServer.start();
      fail("expected exception");
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessageThat().contains("Already started");
    }
    verifyServer(future,null, null);
  }

  @Test
  public void xdsServerStartAndShutdownWithXdsServingStatusListener()
      throws IOException, InterruptedException, TimeoutException, ExecutionException {
    XdsServerBuilder.XdsServingStatusListener mockXdsServingStatusListener =
        mock(XdsServerBuilder.XdsServingStatusListener.class);
    buildServer(mockXdsServingStatusListener);
    Future<Throwable> future = startServerAsync();
    XdsServerTestHelper.generateListenerUpdate(
        xdsClient,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1"),
            tlsContextManager);
    verifyServer(future, mockXdsServingStatusListener, null);
  }

  @Test
  public void xdsServer_discoverState() throws Exception {
    XdsServerBuilder.XdsServingStatusListener mockXdsServingStatusListener =
        mock(XdsServerBuilder.XdsServingStatusListener.class);
    buildServer(mockXdsServingStatusListener);
    Future<Throwable> future = startServerAsync();
    XdsServerTestHelper.generateListenerUpdate(
            xdsClient,
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1"),
            tlsContextManager);
    future.get(5000, TimeUnit.MILLISECONDS);
    xdsClient.ldsWatcher.onError(Status.ABORTED);
    verify(mockXdsServingStatusListener, never()).onNotServing(any(StatusException.class));
    reset(mockXdsServingStatusListener);
    xdsClient.ldsWatcher.onError(Status.CANCELLED);
    verify(mockXdsServingStatusListener, never()).onNotServing(any(StatusException.class));
    reset(mockXdsServingStatusListener);
    xdsClient.ldsWatcher.onResourceDoesNotExist("not found error");
    verify(mockXdsServingStatusListener).onNotServing(any(StatusException.class));
    reset(mockXdsServingStatusListener);
    XdsServerTestHelper.generateListenerUpdate(
        xdsClient,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1"),
            tlsContextManager);
    verifyServer(null, mockXdsServingStatusListener, null);
  }

  @Test
  public void xdsServer_startError()
      throws IOException, InterruptedException, TimeoutException, ExecutionException {
    XdsServerBuilder.XdsServingStatusListener mockXdsServingStatusListener =
        mock(XdsServerBuilder.XdsServingStatusListener.class);
    ServerSocket serverSocket = new ServerSocket(0);
    port = serverSocket.getLocalPort();
    buildServer(mockXdsServingStatusListener);
    Future<Throwable> future = startServerAsync();
    // create port conflict for start to fail
    XdsServerTestHelper.generateListenerUpdate(
        xdsClient,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1"),
            tlsContextManager);
    Throwable exception = future.get(5, TimeUnit.SECONDS);
    assertThat(exception).isInstanceOf(IOException.class);
    assertThat(exception).hasMessageThat().contains("Failed to bind");
    verify(mockXdsServingStatusListener, never()).onNotServing(any(Throwable.class));
    serverSocket.close();
  }

  @Test
  public void xdsServerStartSecondUpdateAndError()
      throws IOException, InterruptedException, TimeoutException, ExecutionException {
    XdsServerBuilder.XdsServingStatusListener mockXdsServingStatusListener =
        mock(XdsServerBuilder.XdsServingStatusListener.class);
    buildServer(mockXdsServingStatusListener);
    Future<Throwable> future = startServerAsync();
    XdsServerTestHelper.generateListenerUpdate(
        xdsClient,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1"),
            tlsContextManager);
    XdsServerTestHelper.generateListenerUpdate(
        xdsClient,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1"),
            tlsContextManager);
    verify(mockXdsServingStatusListener, never()).onNotServing(any(Throwable.class));
    verifyServer(future, mockXdsServingStatusListener, null);
    xdsClient.ldsWatcher.onError(Status.ABORTED);
    verifyServer(null, mockXdsServingStatusListener, null);
  }

  @Test
  public void xdsServer_2ndBuild_expectException() throws IOException {
    XdsServerBuilder.XdsServingStatusListener mockXdsServingStatusListener =
        mock(XdsServerBuilder.XdsServingStatusListener.class);
    buildServer(mockXdsServingStatusListener);
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
    buildBuilder(mockXdsServingStatusListener);
    BindableService mockBindableService = mock(BindableService.class);
    ServerServiceDefinition serverServiceDefinition = io.grpc.ServerServiceDefinition
        .builder("mock").build();
    when(mockBindableService.bindService()).thenReturn(serverServiceDefinition);
    builder.addService(mockBindableService);
    xdsServer = cleanupRule.register((XdsServerWrapper) builder.build());
    try {
      builder.addService(mock(BindableService.class));
      fail("exception expected");
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessageThat().contains("Server already built!");
    }
  }

  @Test
  public void drainGraceTime_negativeThrows() throws IOException {
    buildBuilder(null);
    try {
      builder.drainGraceTime(-1, TimeUnit.SECONDS);
      fail("exception expected");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().contains("drain grace time");
    }
  }

  @Test
  public void testOverrideBootstrap() throws Exception {
    Map<String, Object> b = new HashMap<>();
    buildBuilder(null);
    builder.overrideBootstrapForTest(b);
    assertThat(xdsClientPoolFactory.savedBootstrap).isEqualTo(b);
  }
}
