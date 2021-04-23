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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.sds.ServerWrapperForXds;
import java.io.IOException;
import java.net.BindException;
import java.net.NoRouteToHostException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link ServerWrapperForXds}.
 */
@RunWith(JUnit4.class)
public class ServerWrapperForXdsTest {

  private ServerWrapperForXds serverWrapperForXds;
  private ServerBuilder<?> mockDelegateBuilder;
  private int port;
  private XdsClientWrapperForServerSds xdsClientWrapperForServerSds;
  private XdsServerBuilder.XdsServingStatusListener mockXdsServingStatusListener;
  private XdsClient mockXdsClient;
  private XdsClient.LdsResourceWatcher listenerWatcher;
  private Server mockServer;

  @Before
  public void setUp() throws IOException {
    port = XdsServerTestHelper.findFreePort();
    mockDelegateBuilder = mock(ServerBuilder.class);
    xdsClientWrapperForServerSds = new XdsClientWrapperForServerSds(port);
    mockXdsServingStatusListener = mock(XdsServerBuilder.XdsServingStatusListener.class);
    mockXdsClient = mock(XdsClient.class);
    listenerWatcher =
        XdsServerTestHelper.startAndGetWatcher(xdsClientWrapperForServerSds, mockXdsClient, port);
    mockServer = mock(Server.class);
    when(mockDelegateBuilder.build()).thenReturn(mockServer);
    serverWrapperForXds = new ServerWrapperForXds(mockDelegateBuilder,
        xdsClientWrapperForServerSds,
        mockXdsServingStatusListener,
        100, TimeUnit.MILLISECONDS);
  }

  private Future<Throwable> startServerAsync() throws InterruptedException {
    final SettableFuture<Throwable> settableFuture = SettableFuture.create();
    Executors.newSingleThreadExecutor().execute(new Runnable() {
      @Override
      public void run() {
        try {
          serverWrapperForXds.start();
          settableFuture.set(null);
        } catch (Throwable e) {
          settableFuture.set(e);
        }
      }
    });
    // wait until xdsClientWrapperForServerSds.serverWatchers populated
    for (int i = 0; i < 10; i++) {
      synchronized (xdsClientWrapperForServerSds.serverWatchers) {
        if (!xdsClientWrapperForServerSds.serverWatchers.isEmpty()) {
          break;
        }
      }
      Thread.sleep(100L);
    }
    return settableFuture;
  }

  @Test
  public void start()
      throws InterruptedException, TimeoutException, ExecutionException, IOException {
    Future<Throwable> future = startServerAsync();
    listenerWatcher.onError(Status.ABORTED);
    verifyCapturedCodeAndNotServing(Status.Code.ABORTED, ServerWrapperForXds.ServingState.STARTING);
    XdsServerTestHelper.generateListenerUpdate(
        listenerWatcher,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT1", "VA1")
    );
    Throwable exception = future.get(2, TimeUnit.SECONDS);
    assertThat(exception).isNull();
    assertThat(serverWrapperForXds.getCurrentServingState())
        .isEqualTo(ServerWrapperForXds.ServingState.STARTED);
    listenerWatcher.onResourceDoesNotExist("name");
    verifyCapturedCodeAndNotServing(Status.Code.NOT_FOUND,
        ServerWrapperForXds.ServingState.NOT_SERVING);
    listenerWatcher.onError(Status.NOT_FOUND);
    verifyCapturedCodeAndNotServing(Status.Code.NOT_FOUND,
        ServerWrapperForXds.ServingState.NOT_SERVING);
    listenerWatcher.onError(Status.INVALID_ARGUMENT);
    verifyCapturedCodeAndNotServing(Status.Code.INVALID_ARGUMENT,
        ServerWrapperForXds.ServingState.NOT_SERVING);
    listenerWatcher.onError(Status.PERMISSION_DENIED);
    verifyCapturedCodeAndNotServing(Status.Code.PERMISSION_DENIED,
        ServerWrapperForXds.ServingState.NOT_SERVING);
    listenerWatcher.onError(Status.UNIMPLEMENTED);
    verifyCapturedCodeAndNotServing(Status.Code.UNIMPLEMENTED,
        ServerWrapperForXds.ServingState.NOT_SERVING);
    listenerWatcher.onError(Status.UNAUTHENTICATED);
    verifyCapturedCodeAndNotServing(Status.Code.UNAUTHENTICATED,
        ServerWrapperForXds.ServingState.NOT_SERVING);
    listenerWatcher.onError(Status.ABORTED);
    verifyCapturedCodeAndNotServing(null, ServerWrapperForXds.ServingState.NOT_SERVING);
    Server mockServer1 = mock(Server.class);
    Server mockServer2 = mock(Server.class);
    Server mockServer3 = mock(Server.class);
    final SettableFuture<Throwable> settableFutureForThrow = SettableFuture.create();
    final SettableFuture<Object> settableFutureToSignalStart = SettableFuture.create();
    doAnswer(new Answer<Server>() {
      @Override
      public Server answer(InvocationOnMock invocation) throws Throwable {
        settableFutureToSignalStart.set(null);
        throw settableFutureForThrow.get();
      }
    }).when(mockServer1).start();
    doThrow(new BindException()).when(mockServer2).start();
    doReturn(mockServer3).when(mockServer3).start();
    when(mockDelegateBuilder.build()).thenReturn(mockServer1, mockServer2, mockServer3);
    new Thread(new Runnable() {
      @Override
      public void run() {
        XdsServerTestHelper.generateListenerUpdate(
            listenerWatcher,
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2")
        );
      }
    }).start();
    assertThat(settableFutureToSignalStart.get()).isNull();
    assertThat(serverWrapperForXds.getCurrentServingState())
        .isEqualTo(ServerWrapperForXds.ServingState.ENTER_SERVING);
    settableFutureForThrow.set(new IOException(new BindException()));
    Thread.sleep(1000L);
    ArgumentCaptor<Throwable> argCaptor = ArgumentCaptor.forClass(null);
    InOrder inOrder = inOrder(mockXdsServingStatusListener);
    inOrder.verify(mockXdsServingStatusListener, times(2)).onNotServing(argCaptor.capture());
    List<Throwable> throwableList = argCaptor.getAllValues();
    assertThat(throwableList.size()).isEqualTo(2);
    Throwable throwable = throwableList.remove(0);
    assertThat(throwable).isInstanceOf(IOException.class);
    assertThat(throwable.getCause()).isInstanceOf(BindException.class);
    throwable = throwableList.remove(0);
    assertThat(throwable).isInstanceOf(BindException.class);
    inOrder.verify(mockXdsServingStatusListener).onServing();
    assertThat(serverWrapperForXds.getCurrentServingState())
        .isEqualTo(ServerWrapperForXds.ServingState.STARTED);
    serverWrapperForXds.shutdown();
  }

  @Test
  public void delegateInitialStartError()
          throws InterruptedException, TimeoutException, ExecutionException, IOException {
    Future<Throwable> future = startServerAsync();
    doThrow(new IOException("test exception")).when(mockServer).start();
    new Thread(new Runnable() {
      @Override
      public void run() {
        XdsServerTestHelper.generateListenerUpdate(
                listenerWatcher,
                CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2")
        );
      }
    }).start();
    Throwable exception = future.get(2, TimeUnit.SECONDS);
    assertThat(exception).isInstanceOf(IOException.class);
    assertThat(exception).hasMessageThat().isEqualTo("test exception");
  }

  private void verifyCapturedCodeAndNotServing(Status.Code expected,
      ServerWrapperForXds.ServingState servingState) {
    ArgumentCaptor<Throwable> argCaptor = ArgumentCaptor.forClass(null);
    verify(mockXdsServingStatusListener, times(expected != null ? 1 : 0))
        .onNotServing(argCaptor.capture());
    if (expected != null) {
      Throwable throwable = argCaptor.getValue();
      assertThat(throwable).isInstanceOf(StatusException.class);
      Status captured = ((StatusException) throwable).getStatus();
      assertThat(captured.getCode()).isEqualTo(expected);
    }
    assertThat(serverWrapperForXds.getCurrentServingState()).isEqualTo(servingState);
    reset(mockXdsServingStatusListener);
  }

  @Test
  public void start_internalError()
      throws InterruptedException, TimeoutException, ExecutionException {
    Future<Throwable> future = startServerAsync();
    listenerWatcher.onError(Status.INTERNAL);
    Throwable exception = future.get(2, TimeUnit.SECONDS);
    assertThat(exception).isInstanceOf(IOException.class);
    Throwable cause = exception.getCause();
    assertThat(cause).isInstanceOf(StatusException.class);
    assertThat(((StatusException) cause).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(serverWrapperForXds.getCurrentServingState())
        .isEqualTo(ServerWrapperForXds.ServingState.SHUTDOWN);
  }

  @Test
  public void delegateStartError_shutdown()
      throws InterruptedException, TimeoutException, ExecutionException, IOException {
    Future<Throwable> future = startServerAsync();
    XdsServerTestHelper.generateListenerUpdate(
        listenerWatcher,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2")
    );
    Throwable exception = future.get(2, TimeUnit.SECONDS);
    assertThat(exception).isNull();
    assertThat(serverWrapperForXds.getCurrentServingState())
        .isEqualTo(ServerWrapperForXds.ServingState.STARTED);
    listenerWatcher.onResourceDoesNotExist("name");
    verifyCapturedCodeAndNotServing(Status.Code.NOT_FOUND,
        ServerWrapperForXds.ServingState.NOT_SERVING);
    Server mockServer = mock(Server.class);
    doThrow(new IOException(new NoRouteToHostException())).when(mockServer).start();
    when(mockDelegateBuilder.build()).thenReturn(mockServer);
    XdsServerTestHelper.generateListenerUpdate(
        listenerWatcher,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT3", "VA3")
    );
    Thread.sleep(100L);
    assertThat(serverWrapperForXds.getCurrentServingState())
        .isEqualTo(ServerWrapperForXds.ServingState.SHUTDOWN);
  }

  @Test
  public void shutdownDuringRestart()
      throws InterruptedException, TimeoutException, ExecutionException, IOException {
    Future<Throwable> future = startServerAsync();
    XdsServerTestHelper.generateListenerUpdate(
        listenerWatcher,
        CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2")
    );
    Throwable exception = future.get(2, TimeUnit.SECONDS);
    assertThat(exception).isNull();
    assertThat(serverWrapperForXds.getCurrentServingState())
        .isEqualTo(ServerWrapperForXds.ServingState.STARTED);
    listenerWatcher.onResourceDoesNotExist("name");
    verifyCapturedCodeAndNotServing(Status.Code.NOT_FOUND,
        ServerWrapperForXds.ServingState.NOT_SERVING);
    Server mockServer = mock(Server.class);
    final SettableFuture<Object> settableFutureForStart = SettableFuture.create();
    final SettableFuture<Object> settableFutureToSignalStart = SettableFuture.create();
    final SettableFuture<Throwable> settableFutureForInterrupt = SettableFuture.create();
    doAnswer(new Answer<Server>() {
      @Override
      public Server answer(InvocationOnMock invocation)
          throws InterruptedException, ExecutionException {
        settableFutureToSignalStart.set(null);
        try {
          settableFutureForStart.get();
        } catch (InterruptedException | CancellationException e) {
          settableFutureForInterrupt.set(e);
          throw e;
        }
        return null;  // never reach here
      }
    }).when(mockServer).start();
    when(mockDelegateBuilder.build()).thenReturn(mockServer);
    new Thread(new Runnable() {
      @Override
      public void run() {
        XdsServerTestHelper.generateListenerUpdate(
            listenerWatcher,
            CommonTlsContextTestsUtil.buildTestInternalDownstreamTlsContext("CERT2", "VA2")
        );
      }
    }).start();
    assertThat(settableFutureToSignalStart.get()).isNull();
    assertThat(serverWrapperForXds.getCurrentServingState())
        .isEqualTo(ServerWrapperForXds.ServingState.ENTER_SERVING);
    serverWrapperForXds.shutdown();
    assertThat(serverWrapperForXds.getCurrentServingState())
        .isEqualTo(ServerWrapperForXds.ServingState.SHUTDOWN);
    Throwable interruptedException = settableFutureForInterrupt.get(1L, TimeUnit.SECONDS);
    assertThat(interruptedException).isInstanceOf(InterruptedException.class);
  }
}
