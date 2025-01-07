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

package io.grpc.internal;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ChannelLogger;
import io.grpc.ClientStreamTracer;
import io.grpc.Grpc;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalChannelz.TransportStats;
import io.grpc.InternalInstrumented;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.internal.testing.TestClientStreamTracer;
import io.grpc.internal.testing.TestServerStreamTracer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;

/** Standard unit tests for {@link ClientTransport}s and {@link ServerTransport}s. */
@RunWith(JUnit4.class)
public abstract class AbstractTransportTest {
  /**
   * Use a small flow control to help detect flow control bugs. Don't use 64KiB to test
   * SETTINGS/WINDOW_UPDATE exchange.
   */
  public static final int TEST_FLOW_CONTROL_WINDOW = 65 * 1024;

  private static final int TIMEOUT_MS = 5000;

  protected static final String GRPC_EXPERIMENTAL_SUPPORT_TRACING_MESSAGE_SIZES =
      "GRPC_EXPERIMENTAL_SUPPORT_TRACING_MESSAGE_SIZES";

  private static final Attributes.Key<String> ADDITIONAL_TRANSPORT_ATTR_KEY =
      Attributes.Key.create("additional-attr");

  private static final Attributes.Key<String> EAG_ATTR_KEY =
      Attributes.Key.create("eag-attr");

  private static final Attributes EAG_ATTRS =
      Attributes.newBuilder().set(EAG_ATTR_KEY, "value").build();

  protected final TransportTracer.Factory fakeClockTransportTracer = new TransportTracer.Factory(
      new TimeProvider() {
        @Override
        public long currentTimeNanos() {
          return fakeCurrentTimeNanos();
        }
      });

  /**
   * Returns a new server that when started will be able to be connected to from the client. Each
   * returned instance should be new and yet be accessible by new client transports.
   */
  protected abstract InternalServer newServer(
      List<ServerStreamTracer.Factory> streamTracerFactories);

  /**
   * Builds a new server that is listening on the same port as the given server instance does.
   */
  protected abstract InternalServer newServer(
      int port, List<ServerStreamTracer.Factory> streamTracerFactories);

  /**
   * Returns a new transport that when started will be able to connect to {@code server}.
   */
  protected abstract ManagedClientTransport newClientTransport(InternalServer server);

  /**
   * Returns the authority string used by a client to connect to {@code server}.
   */
  protected abstract String testAuthority(InternalServer server);

  protected final Attributes eagAttrs() {
    return EAG_ATTRS;
  }

  protected final ChannelLogger transportLogger() {
    return new ChannelLogger() {
      @Override
      public void log(ChannelLogLevel level, String message) {}

      @Override
      public void log(ChannelLogLevel level, String messageFormat, Object... args) {}
    };
  }

  /**
   * When non-null, will be shut down during tearDown(). However, it _must_ have been started with
   * {@code serverListener}, otherwise tearDown() can't wait for shutdown which can put following
   * tests in an indeterminate state.
   */
  protected InternalServer server;
  protected ServerTransport serverTransport;
  protected ManagedClientTransport client;
  protected MethodDescriptor<String, String> methodDescriptor =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodDescriptor.MethodType.UNKNOWN)
          .setFullMethodName("service/method")
          .setRequestMarshaller(StringMarshaller.INSTANCE)
          .setResponseMarshaller(StringMarshaller.INSTANCE)
          .build();
  private final CallOptions callOptions = CallOptions.DEFAULT;

  private Metadata.Key<String> asciiKey = Metadata.Key.of(
      "ascii-key", Metadata.ASCII_STRING_MARSHALLER);
  private Metadata.Key<String> binaryKey = Metadata.Key.of(
      "key-bin", StringBinaryMarshaller.INSTANCE);
  private final Metadata.Key<String> tracerHeaderKey = Metadata.Key.of(
      "tracer-key", Metadata.ASCII_STRING_MARSHALLER);
  private final String tracerKeyValue = "tracer-key-value";

  protected ManagedClientTransport.Listener mockClientTransportListener
      = mock(ManagedClientTransport.Listener.class);
  protected MockServerListener serverListener = new MockServerListener();
  private ArgumentCaptor<Throwable> throwableCaptor = ArgumentCaptor.forClass(Throwable.class);
  protected final TestClientStreamTracer clientStreamTracer1 = new TestHeaderClientStreamTracer();
  private final TestClientStreamTracer clientStreamTracer2 = new TestHeaderClientStreamTracer();
  protected final ClientStreamTracer[] tracers = new ClientStreamTracer[] {
      clientStreamTracer1, clientStreamTracer2
  };
  private final ClientStreamTracer[] noopTracers = new ClientStreamTracer[] {
    new ClientStreamTracer() {}
  };

  protected final TestServerStreamTracer serverStreamTracer1 = new TestServerStreamTracer();
  private final TestServerStreamTracer serverStreamTracer2 = new TestServerStreamTracer();
  protected final ServerStreamTracer.Factory serverStreamTracerFactory = mock(
      ServerStreamTracer.Factory.class,
      delegatesTo(new ServerStreamTracer.Factory() {
          final ArrayDeque<TestServerStreamTracer> tracers =
              new ArrayDeque<>(Arrays.asList(serverStreamTracer1, serverStreamTracer2));

          @Override
          public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
            TestServerStreamTracer tracer = tracers.poll();
            if (tracer != null) {
              return tracer;
            }
            return new TestServerStreamTracer();
          }
        }));

  @SuppressWarnings("deprecation") // https://github.com/grpc/grpc-java/issues/7467
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() {
    server = newServer(Arrays.asList(serverStreamTracerFactory));
    when(mockClientTransportListener.filterTransport(any())).thenAnswer(i -> i.getArguments()[0]);
  }

  @After
  public void tearDown() throws InterruptedException {
    if (client != null) {
      client.shutdownNow(Status.UNKNOWN.withDescription("teardown"));
    }
    if (serverTransport != null) {
      serverTransport.shutdownNow(Status.UNKNOWN.withDescription("teardown"));
    }
    if (server != null) {
      server.shutdown();
      assertTrue(serverListener.waitForShutdown(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }
  }

  /**
   * Moves the clock forward, for tests that require moving the clock forward. It is the transport
   * subclass's responsibility to implement this method.
   */
  protected void advanceClock(long offset, TimeUnit unit) {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns true if env var is set.
   */
  protected static boolean isEnabledSupportTracingMessageSizes() {
    return GrpcUtil.getFlag(GRPC_EXPERIMENTAL_SUPPORT_TRACING_MESSAGE_SIZES, false);
  }

  /**
   * Returns the current time, for tests that rely on the clock.
   */
  protected long fakeCurrentTimeNanos() {
    throw new UnsupportedOperationException();
  }

  // TODO(ejona):
  //   multiple streams on same transport
  //   multiple client transports to same server
  //   halfClose to trigger flush (client and server)
  //   flow control pushes back (client and server)
  //   flow control provides precisely number of messages requested (client and server)
  //   onReady called when buffer drained (on server and client)
  //   test no start reentrancy (esp. during failure) (transport and call)
  //   multiple requests/responses (verifying contents received)
  //   server transport shutdown triggers client shutdown (via GOAWAY)
  //   queued message InputStreams are closed on stream cancel
  //     (and maybe exceptions handled)

  /**
   * Test for issue https://github.com/grpc/grpc-java/issues/1682 .
   */
  @Test
  public void frameAfterRstStreamShouldNotBreakClientChannel() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    // Try to create a sequence of frames so that the client receives a HEADERS or DATA frame
    // after having sent a RST_STREAM to the server. Previously, this would have broken the
    // Netty channel.

    ClientStream stream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, noopTracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    stream.start(clientStreamListener);
    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    stream.flush();
    stream.writeMessage(methodDescriptor.streamRequest("foo"));
    stream.flush();
    stream.cancel(Status.CANCELLED);
    stream.flush();
    serverStreamCreation.stream.writeHeaders(new Metadata(), true);
    serverStreamCreation.stream.flush();
    serverStreamCreation.stream.writeMessage(methodDescriptor.streamResponse("bar"));
    serverStreamCreation.stream.flush();

    assertEquals(
        Status.CANCELLED, clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertNotNull(clientStreamListener.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));

    ClientStreamListener mockClientStreamListener2 = mock(ClientStreamListener.class);

    // Test that the channel is still usable i.e. we can receive headers from the server on a
    // new stream.
    stream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, noopTracers);
    stream.start(mockClientStreamListener2);
    serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverStreamCreation.stream.writeHeaders(new Metadata(), true);
    serverStreamCreation.stream.flush();

    verify(mockClientStreamListener2, timeout(TIMEOUT_MS)).headersRead(any(Metadata.class));
  }

  @Test
  public void serverNotListening() throws Exception {
    // Start server to just acquire a port.
    server.start(serverListener);
    client = newClientTransport(server);
    server.shutdown();
    assertTrue(serverListener.waitForShutdown(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    server = null;

    InOrder inOrder = inOrder(mockClientTransportListener);
    runIfNotNull(client.start(mockClientTransportListener));
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportTerminated();
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    inOrder.verify(mockClientTransportListener).transportShutdown(statusCaptor.capture());
    assertCodeEquals(Status.UNAVAILABLE, statusCaptor.getValue());
    inOrder.verify(mockClientTransportListener).transportTerminated();
    verify(mockClientTransportListener, never()).transportReady();
    verify(mockClientTransportListener, never()).transportInUse(anyBoolean());
  }

  @Test
  public void clientStartStop() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    InOrder inOrder = inOrder(mockClientTransportListener);
    startTransport(client, mockClientTransportListener);
    Status shutdownReason = Status.UNAVAILABLE.withDescription("shutdown called");
    client.shutdown(shutdownReason);
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportTerminated();
    inOrder.verify(mockClientTransportListener).transportShutdown(same(shutdownReason));
    inOrder.verify(mockClientTransportListener).transportTerminated();
    verify(mockClientTransportListener, never()).transportInUse(anyBoolean());
  }

  @Test
  public void clientStartAndStopOnceConnected() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    InOrder inOrder = inOrder(mockClientTransportListener);
    startTransport(client, mockClientTransportListener);
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportReady();
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    client.shutdown(Status.UNAVAILABLE);
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportTerminated();
    inOrder.verify(mockClientTransportListener).transportShutdown(any(Status.class));
    inOrder.verify(mockClientTransportListener).transportTerminated();
    assertTrue(serverTransportListener.waitForTermination(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    server.shutdown();
    assertTrue(serverListener.waitForShutdown(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    server = null;
    verify(mockClientTransportListener, never()).transportInUse(anyBoolean());
  }

  @Test
  public void checkClientAttributes() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    assumeTrue(client instanceof ConnectionClientTransport);
    ConnectionClientTransport connectionClient = (ConnectionClientTransport) client;
    startTransport(connectionClient, mockClientTransportListener);
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportReady();

    assertNotNull("security level should be set in client attributes",
        connectionClient.getAttributes().get(GrpcAttributes.ATTR_SECURITY_LEVEL));
  }

  @Test
  public void serverAlreadyListening() throws Exception {
    client = null;
    server.start(serverListener);
    int port = -1;
    SocketAddress addr = server.getListenSocketAddress();
    if (addr instanceof InetSocketAddress) {
      port = ((InetSocketAddress) addr).getPort();
    }
    InternalServer server2 = newServer(port, Arrays.asList(serverStreamTracerFactory));
    thrown.expect(IOException.class);
    server2.start(new MockServerListener());
  }

  @Test
  public void serverStartInterrupted() throws Exception {
    client = null;

    // Just get free port
    server.start(serverListener);
    int port = -1;
    SocketAddress addr = server.getListenSocketAddress();
    if (addr instanceof InetSocketAddress) {
      port = ((InetSocketAddress) addr).getPort();
    }
    assumeTrue("transport is not using InetSocketAddress", port != -1);
    server.shutdown();
    assertTrue(serverListener.waitForShutdown(TIMEOUT_MS, TimeUnit.MILLISECONDS));

    server = newServer(port, Arrays.asList(serverStreamTracerFactory));
    boolean success;
    Thread.currentThread().interrupt();
    try {
      server.start(serverListener = new MockServerListener());
      success = true;
    } catch (Exception ex) {
      success = false;
    } finally {
      Thread.interrupted(); // clear interruption
    }
    assumeTrue("apparently start is not impacted by interruption, so nothing to test", !success);
    // second time should not throw, as the first time should not have bound to the port
    server.start(serverListener);
  }

  @Test
  public void openStreamPreventsTermination() throws Exception {
    server.start(serverListener);
    int port = -1;
    SocketAddress addr = server.getListenSocketAddress();
    if (addr instanceof InetSocketAddress) {
      port = ((InetSocketAddress) addr).getPort();
    }
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    ClientStream clientStream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportInUse(true);
    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    ServerStream serverStream = serverStreamCreation.stream;
    ServerStreamListenerBase serverStreamListener = serverStreamCreation.listener;

    client.shutdown(Status.UNAVAILABLE);
    client = null;
    server.shutdown();
    serverTransport.shutdown();
    serverTransport = null;

    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportShutdown(any(Status.class));
    assertTrue(serverListener.waitForShutdown(TIMEOUT_MS, TimeUnit.MILLISECONDS));

    // A new server should be able to start listening, since the current server has given up
    // resources. There may be cases this is impossible in the future, but for now it is a useful
    // property.
    serverListener = new MockServerListener();
    server = newServer(port, Arrays.asList(serverStreamTracerFactory));
    server.start(serverListener);

    // Try to "flush" out any listener notifications on client and server. This also ensures that
    // the stream still functions.
    serverStream.writeHeaders(new Metadata(), true);
    clientStream.halfClose();
    assertNotNull(clientStreamListener.headers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertTrue(serverStreamListener.awaitHalfClosed(TIMEOUT_MS, TimeUnit.MILLISECONDS));

    verify(mockClientTransportListener, never()).transportTerminated();
    verify(mockClientTransportListener, never()).transportInUse(false);
    assertFalse(serverTransportListener.isTerminated());

    clientStream.cancel(Status.CANCELLED);

    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportTerminated();
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportInUse(false);
    assertTrue(serverTransportListener.waitForTermination(TIMEOUT_MS, TimeUnit.MILLISECONDS));
  }

  @Test
  public void shutdownNowKillsClientStream() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    ClientStream clientStream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportInUse(true);
    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    ServerStreamListenerBase serverStreamListener = serverStreamCreation.listener;

    Status status = Status.UNKNOWN.withDescription("test shutdownNow");
    client.shutdownNow(status);
    client = null;

    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportShutdown(any(Status.class));
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportTerminated();
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportInUse(false);
    assertTrue(serverTransportListener.waitForTermination(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertTrue(serverTransportListener.isTerminated());

    assertEquals(status, clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertNotNull(clientStreamListener.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    Status serverStatus = serverStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertFalse(serverStatus.isOk());
    assertTrue(clientStreamTracer1.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertNull(clientStreamTracer1.getInboundTrailers());
    assertStatusEquals(status, clientStreamTracer1.getStatus());
    assertTrue(serverStreamTracer1.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertStatusEquals(serverStatus, serverStreamTracer1.getStatus());
  }

  @Test
  public void shutdownNowKillsServerStream() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    ClientStream clientStream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportInUse(true);
    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    ServerStreamListenerBase serverStreamListener = serverStreamCreation.listener;

    Status shutdownStatus = Status.UNKNOWN.withDescription("test shutdownNow");
    serverTransport.shutdownNow(shutdownStatus);
    serverTransport = null;

    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportShutdown(any(Status.class));
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportTerminated();
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportInUse(false);
    assertTrue(serverTransportListener.waitForTermination(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertTrue(serverTransportListener.isTerminated());

    Status clientStreamStatus = clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertFalse(clientStreamStatus.isOk());
    assertNotNull(clientStreamListener.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertTrue(clientStreamTracer1.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertNull(clientStreamTracer1.getInboundTrailers());
    assertStatusEquals(clientStreamStatus, clientStreamTracer1.getStatus());
    assertTrue(serverStreamTracer1.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertStatusEquals(shutdownStatus, serverStreamTracer1.getStatus());

    // Generally will be same status provided to shutdownNow, but InProcessTransport can't
    // differentiate between client and server shutdownNow. The status is not really used on
    // server-side, so we don't care much.
    assertNotNull(serverStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
  }

  @Test
  public void ping() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    ClientTransport.PingCallback mockPingCallback = mock(ClientTransport.PingCallback.class);
    try {
      client.ping(mockPingCallback, MoreExecutors.directExecutor());
    } catch (UnsupportedOperationException ex) {
      // Transport doesn't support ping, so this neither passes nor fails.
      assumeTrue(false);
    }
    verify(mockPingCallback, timeout(TIMEOUT_MS)).onSuccess(ArgumentMatchers.anyLong());
    verify(mockClientTransportListener, never()).transportInUse(anyBoolean());
  }

  @Test
  public void ping_duringShutdown() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    // Stream prevents termination
    ClientStream stream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    stream.start(clientStreamListener);
    client.shutdown(Status.UNAVAILABLE);
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportShutdown(any(Status.class));
    ClientTransport.PingCallback mockPingCallback = mock(ClientTransport.PingCallback.class);
    try {
      client.ping(mockPingCallback, MoreExecutors.directExecutor());
    } catch (UnsupportedOperationException ex) {
      // Transport doesn't support ping, so this neither passes nor fails.
      assumeTrue(false);
    }
    verify(mockPingCallback, timeout(TIMEOUT_MS)).onSuccess(ArgumentMatchers.anyLong());
    stream.cancel(Status.CANCELLED);
  }

  @Test
  public void ping_afterTermination() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportReady();
    Status shutdownReason = Status.UNAVAILABLE.withDescription("shutdown called");
    client.shutdown(shutdownReason);
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportTerminated();
    ClientTransport.PingCallback mockPingCallback = mock(ClientTransport.PingCallback.class);
    try {
      client.ping(mockPingCallback, MoreExecutors.directExecutor());
    } catch (UnsupportedOperationException ex) {
      // Transport doesn't support ping, so this neither passes nor fails.
      assumeTrue(false);
    }
    verify(mockPingCallback, timeout(TIMEOUT_MS)).onFailure(throwableCaptor.capture());
    Status status = Status.fromThrowable(throwableCaptor.getValue());
    assertSame(shutdownReason, status);
  }

  @Test
  public void newStream_duringShutdown() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    // Stream prevents termination
    ClientStream stream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, noopTracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    stream.start(clientStreamListener);
    client.shutdown(Status.UNAVAILABLE);
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportShutdown(any(Status.class));

    ClientStream stream2 = client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener2 = new ClientStreamListenerBase();
    stream2.start(clientStreamListener2);
    Status clientStreamStatus2 =
        clientStreamListener2.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertNotNull(clientStreamListener2.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertCodeEquals(Status.UNAVAILABLE, clientStreamStatus2);
    assertNull(clientStreamTracer2.getInboundTrailers());
    assertSame(clientStreamStatus2, clientStreamTracer2.getStatus());

    // Make sure earlier stream works.
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;
    // TODO(zdapeng): Increased timeout to 20 seconds to see if flakiness of #2328 persists. Take
    // further action after sufficient observation.
    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(20 * TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverStreamCreation.stream.close(Status.OK, new Metadata());
    assertCodeEquals(Status.OK, clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertNotNull(clientStreamListener.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
  }

  @Test
  public void newStream_afterTermination() throws Exception {
    // We expect the same general behavior as duringShutdown, but for some transports (e.g., Netty)
    // dealing with afterTermination is harder than duringShutdown.
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportReady();
    Status shutdownReason = Status.UNAVAILABLE.withDescription("shutdown called");
    client.shutdown(shutdownReason);
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportTerminated();
    Thread.sleep(100);
    ClientStream stream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    stream.start(clientStreamListener);
    assertEquals(
        shutdownReason, clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertNotNull(clientStreamListener.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    verify(mockClientTransportListener, never()).transportInUse(anyBoolean());
    assertNull(clientStreamTracer1.getInboundTrailers());
    assertSame(shutdownReason, clientStreamTracer1.getStatus());
    // Assert no interactions
    assertNull(serverStreamTracer1.getServerCallInfo());
  }

  @Test
  public void transportInUse_balancerRpcsNotCounted() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);

    // stream1 is created by balancer through Subchannel.asChannel(), which is marked by
    // CALL_OPTIONS_RPC_OWNED_BY_BALANCER in CallOptions.  It won't be counted for in-use signal.
    ClientStream stream1 = client.newStream(
        methodDescriptor, new Metadata(),
        callOptions.withOption(GrpcUtil.CALL_OPTIONS_RPC_OWNED_BY_BALANCER, Boolean.TRUE),
        noopTracers);
    ClientStreamListenerBase clientStreamListener1 = new ClientStreamListenerBase();
    stream1.start(clientStreamListener1);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    StreamCreation serverStreamCreation1
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);

    // stream2 is the normal RPC, and will be counted for in-use
    ClientStream stream2 = client.newStream(
        methodDescriptor, new Metadata(), callOptions, noopTracers);
    ClientStreamListenerBase clientStreamListener2 = new ClientStreamListenerBase();
    stream2.start(clientStreamListener2);
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportInUse(true);
    StreamCreation serverStreamCreation2
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);

    stream2.halfClose();
    verify(mockClientTransportListener, never()).transportInUse(false);
    serverStreamCreation2.stream.close(Status.OK, new Metadata());
    // As soon as stream2 is closed, even though stream1 is still open, the transport will report
    // in-use == false.
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportInUse(false);

    stream1.halfClose();
    serverStreamCreation1.stream.close(Status.OK, new Metadata());
    // Verify that the callback has been called only once for true and false respectively
    verify(mockClientTransportListener).transportInUse(true);
    verify(mockClientTransportListener).transportInUse(false);
  }

  @Test
  public void transportInUse_normalClose() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    ClientStream stream1 = client.newStream(
        methodDescriptor, new Metadata(), callOptions, noopTracers);
    ClientStreamListenerBase clientStreamListener1 = new ClientStreamListenerBase();
    stream1.start(clientStreamListener1);
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportInUse(true);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    StreamCreation serverStreamCreation1
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    ClientStream stream2 = client.newStream(
        methodDescriptor, new Metadata(), callOptions, noopTracers);
    ClientStreamListenerBase clientStreamListener2 = new ClientStreamListenerBase();
    stream2.start(clientStreamListener2);
    StreamCreation serverStreamCreation2
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);

    stream1.halfClose();
    serverStreamCreation1.stream.close(Status.OK, new Metadata());
    stream2.halfClose();
    verify(mockClientTransportListener, never()).transportInUse(false);
    serverStreamCreation2.stream.close(Status.OK, new Metadata());
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportInUse(false);
    // Verify that the callback has been called only once for true and false respectively
    verify(mockClientTransportListener).transportInUse(true);
    verify(mockClientTransportListener).transportInUse(false);
  }

  @Test
  public void transportInUse_clientCancel() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    ClientStream stream1 = client.newStream(
        methodDescriptor, new Metadata(), callOptions, noopTracers);
    ClientStreamListenerBase clientStreamListener1 = new ClientStreamListenerBase();
    stream1.start(clientStreamListener1);
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportInUse(true);
    ClientStream stream2 = client.newStream(
        methodDescriptor, new Metadata(), callOptions, noopTracers);
    ClientStreamListenerBase clientStreamListener2 = new ClientStreamListenerBase();
    stream2.start(clientStreamListener2);

    stream1.cancel(Status.CANCELLED);
    verify(mockClientTransportListener, never()).transportInUse(false);
    stream2.cancel(Status.CANCELLED);
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportInUse(false);
    // Verify that the callback has been called only once for true and false respectively
    verify(mockClientTransportListener).transportInUse(true);
    verify(mockClientTransportListener).transportInUse(false);
  }

  @Test
  public void basicStream() throws Exception {
    InOrder serverInOrder = inOrder(serverStreamTracerFactory);
    server.start(serverListener);
    client = newClientTransport(server);

    startTransport(client, mockClientTransportListener);

    // This attribute is available right after transport is started
    assertThat(((ConnectionClientTransport) client).getAttributes()
        .get(GrpcAttributes.ATTR_CLIENT_EAG_ATTRS)).isSameInstanceAs(EAG_ATTRS);

    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    Metadata clientHeaders = new Metadata();
    clientHeaders.put(asciiKey, "client");
    clientHeaders.put(asciiKey, "dupvalue");
    clientHeaders.put(asciiKey, "dupvalue");
    clientHeaders.put(binaryKey, "äbinaryclient");
    clientHeaders.put(binaryKey, "dup,value");
    Metadata clientHeadersCopy = new Metadata();

    clientHeadersCopy.merge(clientHeaders);
    ClientStream clientStream = client.newStream(
        methodDescriptor, clientHeaders, callOptions, tracers);
    assertThat(((TestHeaderClientStreamTracer) clientStreamTracer1).transportAttrs)
        .isSameInstanceAs(((ConnectionClientTransport) client).getAttributes());

    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertTrue(clientStreamTracer1.awaitOutboundHeaders(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertEquals(methodDescriptor.getFullMethodName(), serverStreamCreation.method);
    assertEquals(Lists.newArrayList(clientHeadersCopy.getAll(asciiKey)),
        Lists.newArrayList(serverStreamCreation.headers.getAll(asciiKey)));
    assertEquals(Lists.newArrayList(clientHeadersCopy.getAll(binaryKey)),
        Lists.newArrayList(serverStreamCreation.headers.getAll(binaryKey)));
    assertEquals(tracerKeyValue, serverStreamCreation.headers.get(tracerHeaderKey));
    ServerStream serverStream = serverStreamCreation.stream;
    ServerStreamListenerBase serverStreamListener = serverStreamCreation.listener;

    serverInOrder.verify(serverStreamTracerFactory).newServerStreamTracer(
        eq(methodDescriptor.getFullMethodName()), any(Metadata.class));

    assertEquals("additional attribute value",
        serverStream.getAttributes().get(ADDITIONAL_TRANSPORT_ATTR_KEY));
    assertNotNull(serverStream.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR));
    assertNotNull(serverStream.getAttributes().get(Grpc.TRANSPORT_ATTR_LOCAL_ADDR));

    // This attribute is still available when the transport is connected
    assertThat(((ConnectionClientTransport) client).getAttributes()
        .get(GrpcAttributes.ATTR_CLIENT_EAG_ATTRS)).isSameInstanceAs(EAG_ATTRS);

    serverStream.request(1);
    assertTrue(clientStreamListener.awaitOnReadyAndDrain(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertTrue(clientStream.isReady());
    clientStream.writeMessage(methodDescriptor.streamRequest("Hello!"));
    assertThat(clientStreamTracer1.nextOutboundEvent()).isEqualTo("outboundMessage(0)");

    clientStream.flush();
    InputStream message = serverStreamListener.messageQueue.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertEquals("Hello!", methodDescriptor.parseRequest(message));
    message.close();
    assertThat(clientStreamTracer1.nextOutboundEvent())
        .matches("outboundMessageSent\\(0, -?[0-9]+, -?[0-9]+\\)");
    if (isEnabledSupportTracingMessageSizes()) {
      assertThat(clientStreamTracer1.getOutboundWireSize()).isGreaterThan(0L);
      assertThat(clientStreamTracer1.getOutboundUncompressedSize()).isGreaterThan(0L);
    }

    assertThat(serverStreamTracer1.nextInboundEvent()).isEqualTo("inboundMessage(0)");
    assertNull("no additional message expected", serverStreamListener.messageQueue.poll());

    clientStream.halfClose();
    assertTrue(serverStreamListener.awaitHalfClosed(TIMEOUT_MS, TimeUnit.MILLISECONDS));

    if (isEnabledSupportTracingMessageSizes()) {
      assertThat(serverStreamTracer1.getInboundWireSize()).isGreaterThan(0L);
      assertThat(serverStreamTracer1.getInboundUncompressedSize()).isGreaterThan(0L);
    }
    assertThat(serverStreamTracer1.nextInboundEvent())
        .matches("inboundMessageRead\\(0, -?[0-9]+, -?[0-9]+\\)");

    Metadata serverHeaders = new Metadata();
    serverHeaders.put(asciiKey, "server");
    serverHeaders.put(asciiKey, "dupvalue");
    serverHeaders.put(asciiKey, "dupvalue");
    serverHeaders.put(binaryKey, "äbinaryserver");
    serverHeaders.put(binaryKey, "dup,value");
    Metadata serverHeadersCopy = new Metadata();
    serverHeadersCopy.merge(serverHeaders);
    serverStream.writeHeaders(serverHeaders, true);
    Metadata headers = clientStreamListener.headers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertNotNull(headers);
    assertAsciiMetadataValuesEqual(serverHeadersCopy.getAll(asciiKey), headers.getAll(asciiKey));
    assertEquals(
        Lists.newArrayList(serverHeadersCopy.getAll(binaryKey)),
        Lists.newArrayList(headers.getAll(binaryKey)));

    clientStream.request(1);
    assertTrue(serverStreamListener.awaitOnReadyAndDrain(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertTrue(serverStream.isReady());
    serverStream.writeMessage(methodDescriptor.streamResponse("Hi. Who are you?"));
    assertThat(serverStreamTracer1.nextOutboundEvent()).isEqualTo("outboundMessage(0)");

    serverStream.flush();
    message = clientStreamListener.messageQueue.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertNotNull("message expected", message);
    assertThat(serverStreamTracer1.nextOutboundEvent())
        .matches("outboundMessageSent\\(0, -?[0-9]+, -?[0-9]+\\)");
    if (isEnabledSupportTracingMessageSizes()) {
      assertThat(serverStreamTracer1.getOutboundWireSize()).isGreaterThan(0L);
      assertThat(serverStreamTracer1.getOutboundUncompressedSize()).isGreaterThan(0L);
    }
    assertTrue(clientStreamTracer1.getInboundHeaders());
    assertThat(clientStreamTracer1.nextInboundEvent()).isEqualTo("inboundMessage(0)");
    assertEquals("Hi. Who are you?", methodDescriptor.parseResponse(message));
    assertThat(clientStreamTracer1.nextInboundEvent())
        .matches("inboundMessageRead\\(0, -?[0-9]+, -?[0-9]+\\)");
    if (isEnabledSupportTracingMessageSizes()) {
      assertThat(clientStreamTracer1.getInboundWireSize()).isGreaterThan(0L);
      assertThat(clientStreamTracer1.getInboundUncompressedSize()).isGreaterThan(0L);
    }

    message.close();
    assertNull("no additional message expected", clientStreamListener.messageQueue.poll());

    Status status = Status.OK.withDescription("That was normal");
    Metadata trailers = new Metadata();
    trailers.put(asciiKey, "trailers");
    trailers.put(asciiKey, "dupvalue");
    trailers.put(asciiKey, "dupvalue");
    trailers.put(binaryKey, "äbinarytrailers");
    trailers.put(binaryKey, "dup,value");
    serverStream.close(status, trailers);
    assertNull(serverStreamTracer1.nextInboundEvent());
    assertNull(serverStreamTracer1.nextOutboundEvent());
    assertCodeEquals(Status.OK, serverStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertSame(status, serverStreamTracer1.getStatus());
    Status clientStreamStatus = clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    Metadata clientStreamTrailers =
        clientStreamListener.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertSame(clientStreamTrailers, clientStreamTracer1.getInboundTrailers());
    assertSame(clientStreamStatus, clientStreamTracer1.getStatus());
    assertNull(clientStreamTracer1.nextInboundEvent());
    assertNull(clientStreamTracer1.nextOutboundEvent());
    assertEquals(status.getCode(), clientStreamStatus.getCode());
    assertEquals(status.getDescription(), clientStreamStatus.getDescription());
    assertAsciiMetadataValuesEqual(
        trailers.getAll(asciiKey), clientStreamTrailers.getAll(asciiKey));
    assertEquals(
        Lists.newArrayList(trailers.getAll(binaryKey)),
        Lists.newArrayList(clientStreamTrailers.getAll(binaryKey)));
  }

  @Test
  @SuppressWarnings("deprecation")
  public void authorityPropagation() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener
            = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);

    Metadata clientHeaders = new Metadata();
    ClientStream clientStream = client.newStream(
        methodDescriptor, clientHeaders, callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    StreamCreation serverStreamCreation
            = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    ServerStream serverStream = serverStreamCreation.stream;

    assertEquals(testAuthority(server), serverStream.getAuthority());
  }

  private void assertAsciiMetadataValuesEqual(Iterable<String> expected, Iterable<String> actural) {
    StringBuilder sbExpected = new StringBuilder();
    for (String str : expected) {
      sbExpected.append(str).append(",");
    }
    StringBuilder sbActual = new StringBuilder();
    for (String str : actural) {
      sbActual.append(str).append(",");
    }
    assertEquals(sbExpected.toString(), sbActual.toString());
  }

  @Test
  public void zeroMessageStream() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    ClientStream clientStream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    ServerStream serverStream = serverStreamCreation.stream;
    ServerStreamListenerBase serverStreamListener = serverStreamCreation.listener;

    clientStream.halfClose();
    assertTrue(serverStreamListener.awaitHalfClosed(TIMEOUT_MS, TimeUnit.MILLISECONDS));

    serverStream.writeHeaders(new Metadata(), true);
    assertNotNull(clientStreamListener.headers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));

    Status status = Status.OK.withDescription("Nice talking to you");
    serverStream.close(status, new Metadata());
    assertCodeEquals(Status.OK, serverStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    Status clientStreamStatus = clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    Metadata clientStreamTrailers =
        clientStreamListener.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertNotNull(clientStreamTrailers);
    assertEquals(status.getCode(), clientStreamStatus.getCode());
    assertEquals(status.getDescription(), clientStreamStatus.getDescription());
    assertTrue(clientStreamTracer1.getOutboundHeaders());
    assertTrue(clientStreamTracer1.getInboundHeaders());
    assertSame(clientStreamTrailers, clientStreamTracer1.getInboundTrailers());
    assertSame(clientStreamStatus, clientStreamTracer1.getStatus());
    assertSame(status, serverStreamTracer1.getStatus());
  }

  @Test
  public void earlyServerClose_withServerHeaders() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    ClientStream clientStream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    ServerStream serverStream = serverStreamCreation.stream;
    ServerStreamListenerBase serverStreamListener = serverStreamCreation.listener;

    serverStream.writeHeaders(new Metadata(), true);
    assertNotNull(clientStreamListener.headers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));

    Status strippedStatus = Status.OK.withDescription("Hello. Goodbye.");
    Status status = strippedStatus.withCause(new Exception());
    serverStream.close(status, new Metadata());
    assertCodeEquals(Status.OK, serverStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    Status clientStreamStatus = clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    Metadata clientStreamTrailers =
        clientStreamListener.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertNotNull(clientStreamTrailers);
    checkClientStatus(status, clientStreamStatus);
    assertTrue(clientStreamTracer1.getOutboundHeaders());
    assertTrue(clientStreamTracer1.getInboundHeaders());
    assertSame(clientStreamTrailers, clientStreamTracer1.getInboundTrailers());
    assertSame(clientStreamStatus, clientStreamTracer1.getStatus());
    assertSame(status, serverStreamTracer1.getStatus());
  }

  @Test
  public void earlyServerClose_noServerHeaders() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    ClientStream clientStream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    ServerStream serverStream = serverStreamCreation.stream;
    ServerStreamListenerBase serverStreamListener = serverStreamCreation.listener;

    Status strippedStatus = Status.OK.withDescription("Hellogoodbye");
    Status status = strippedStatus.withCause(new Exception());
    Metadata trailers = new Metadata();
    trailers.put(asciiKey, "trailers");
    trailers.put(asciiKey, "dupvalue");
    trailers.put(asciiKey, "dupvalue");
    trailers.put(binaryKey, "äbinarytrailers");
    serverStream.close(status, trailers);
    assertCodeEquals(Status.OK, serverStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    Status clientStreamStatus = clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    Metadata clientStreamTrailers =
        clientStreamListener.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    checkClientStatus(status, clientStreamStatus);
    assertEquals(
        Lists.newArrayList(trailers.getAll(asciiKey)),
        Lists.newArrayList(clientStreamTrailers.getAll(asciiKey)));
    assertEquals(
        Lists.newArrayList(trailers.getAll(binaryKey)),
        Lists.newArrayList(clientStreamTrailers.getAll(binaryKey)));
    assertTrue(clientStreamTracer1.getOutboundHeaders());
    assertSame(clientStreamTrailers, clientStreamTracer1.getInboundTrailers());
    assertSame(clientStreamStatus, clientStreamTracer1.getStatus());
    assertSame(status, serverStreamTracer1.getStatus());
  }

  @Test
  public void earlyServerClose_serverFailure() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    ClientStream clientStream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    ServerStream serverStream = serverStreamCreation.stream;
    ServerStreamListenerBase serverStreamListener = serverStreamCreation.listener;

    Status strippedStatus = Status.INTERNAL.withDescription("I'm not listening");
    Status status = strippedStatus.withCause(new Exception());
    serverStream.close(status, new Metadata());
    assertCodeEquals(Status.OK, serverStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    Status clientStreamStatus = clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    Metadata clientStreamTrailers =
        clientStreamListener.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertNotNull(clientStreamTrailers);
    checkClientStatus(status, clientStreamStatus);
    assertTrue(clientStreamTracer1.getOutboundHeaders());
    assertSame(clientStreamTrailers, clientStreamTracer1.getInboundTrailers());
    assertSame(clientStreamStatus, clientStreamTracer1.getStatus());
    assertSame(status, serverStreamTracer1.getStatus());
  }

  @Test
  public void earlyServerClose_serverFailure_withClientCancelOnListenerClosed() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    final ClientStream clientStream =
        client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase() {

      @Override
      public void closed(
          Status status, RpcProgress rpcProgress, Metadata trailers) {
        super.closed(status, rpcProgress, trailers);
        // This simulates the blocking calls which can trigger clientStream.cancel().
        clientStream.cancel(Status.CANCELLED.withCause(status.asRuntimeException()));
      }
    };
    clientStream.start(clientStreamListener);
    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    ServerStream serverStream = serverStreamCreation.stream;
    ServerStreamListenerBase serverStreamListener = serverStreamCreation.listener;

    Status strippedStatus = Status.INTERNAL.withDescription("I'm not listening");
    Status status = strippedStatus.withCause(new Exception());
    serverStream.close(status, new Metadata());
    assertCodeEquals(Status.OK, serverStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    Status clientStreamStatus = clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    Metadata clientStreamTrailers =
        clientStreamListener.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertNotNull(clientStreamTrailers);
    checkClientStatus(status, clientStreamStatus);
    assertTrue(clientStreamTracer1.getOutboundHeaders());
    assertSame(clientStreamTrailers, clientStreamTracer1.getInboundTrailers());
    assertSame(clientStreamStatus, clientStreamTracer1.getStatus());
    assertSame(status, serverStreamTracer1.getStatus());
  }

  @Test
  public void clientCancel() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    ClientStream clientStream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    ServerStreamListenerBase serverStreamListener = serverStreamCreation.listener;

    Status status = Status.CANCELLED.withDescription("Nevermind").withCause(new Exception());
    clientStream.cancel(status);
    assertEquals(status, clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertNotNull(clientStreamListener.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    Status serverStatus = serverStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertNotEquals(Status.Code.OK, serverStatus.getCode());
    // Cause should not be transmitted between client and server by default
    assertNull(serverStatus.getCause());

    clientStream.cancel(status);
    assertTrue(clientStreamTracer1.getOutboundHeaders());
    assertNull(clientStreamTracer1.getInboundTrailers());
    assertSame(status, clientStreamTracer1.getStatus());
    assertSame(serverStatus, serverStreamTracer1.getStatus());
  }

  @Test
  public void clientCancelFromWithinMessageRead() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    final SettableFuture<Boolean> closedCalled = SettableFuture.create();
    final ClientStream clientStream =
        client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    final Status status = Status.CANCELLED.withDescription("nevermind");
    clientStream.start(new ClientStreamListener() {
      private boolean messageReceived = false;

      @Override
      public void headersRead(Metadata headers) {
      }

      @Override
      public void closed(
          Status status, RpcProgress rpcProgress, Metadata trailers) {
        assertEquals(Status.CANCELLED.getCode(), status.getCode());
        assertEquals("nevermind", status.getDescription());
        closedCalled.set(true);
      }

      @Override
      public void messagesAvailable(MessageProducer producer) {
        InputStream message;
        while ((message = producer.next()) != null) {
          assertFalse("too many messages received", messageReceived);
          messageReceived = true;
          assertEquals("foo", methodDescriptor.parseResponse(message));
          clientStream.cancel(status);
        }
      }

      @Override
      public void onReady() {
      }
    });
    clientStream.halfClose();
    clientStream.request(1);

    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertEquals(methodDescriptor.getFullMethodName(), serverStreamCreation.method);
    ServerStream serverStream = serverStreamCreation.stream;
    ServerStreamListenerBase serverStreamListener = serverStreamCreation.listener;
    assertTrue(serverStreamListener.awaitOnReadyAndDrain(TIMEOUT_MS, TimeUnit.MILLISECONDS));

    assertTrue(serverStream.isReady());
    serverStream.writeHeaders(new Metadata(), true);
    serverStream.writeMessage(methodDescriptor.streamRequest("foo"));
    serverStream.flush();

    // Block until closedCalled was set.
    closedCalled.get(5, TimeUnit.SECONDS);

    serverStream.close(Status.OK, new Metadata());
    assertTrue(clientStreamTracer1.getOutboundHeaders());
    assertTrue(clientStreamTracer1.getInboundHeaders());
    if (isEnabledSupportTracingMessageSizes()) {
      assertThat(clientStreamTracer1.getInboundWireSize()).isGreaterThan(0L);
      assertThat(clientStreamTracer1.getInboundUncompressedSize()).isGreaterThan(0L);
      assertThat(serverStreamTracer1.getOutboundWireSize()).isGreaterThan(0L);
      assertThat(serverStreamTracer1.getOutboundUncompressedSize()).isGreaterThan(0L);
    }
    assertNull(clientStreamTracer1.getInboundTrailers());
    assertSame(status, clientStreamTracer1.getStatus());
    // There is a race between client cancelling and server closing.  The final status seen by the
    // server is non-deterministic.
    assertTrue(serverStreamTracer1.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertNotNull(serverStreamTracer1.getStatus());
  }

  @Test
  public void serverCancel() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    ClientStream clientStream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    ServerStream serverStream = serverStreamCreation.stream;
    ServerStreamListenerBase serverStreamListener = serverStreamCreation.listener;

    Status status = Status.DEADLINE_EXCEEDED.withDescription("It was bound to happen")
        .withCause(new Exception());
    serverStream.cancel(status);
    assertEquals(status, serverStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    Status clientStreamStatus = clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertNotNull(clientStreamListener.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    // Presently we can't sent much back to the client in this case. Verify that is the current
    // behavior for consistency between transports.
    assertCodeEquals(Status.CANCELLED, clientStreamStatus);
    // Cause should not be transmitted between server and client
    assertNull(clientStreamStatus.getCause());

    assertTrue(clientStreamTracer1.getOutboundHeaders());
    assertNull(clientStreamTracer1.getInboundTrailers());
    assertSame(clientStreamStatus, clientStreamTracer1.getStatus());
    verify(serverStreamTracerFactory).newServerStreamTracer(anyString(), any(Metadata.class));
    assertSame(status, serverStreamTracer1.getStatus());

    // Second cancellation shouldn't trigger additional callbacks
    serverStream.cancel(status);
    doPingPong(serverListener);
  }

  @Test
  public void flowControlPushBack() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener =
        serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    ClientStream clientStream =
        client.newStream(methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    StreamCreation serverStreamCreation =
        serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertEquals(methodDescriptor.getFullMethodName(), serverStreamCreation.method);
    ServerStream serverStream = serverStreamCreation.stream;
    ServerStreamListenerBase serverStreamListener = serverStreamCreation.listener;

    serverStream.writeHeaders(new Metadata(), true);

    String largeMessage = newString(1024);

    serverStream.request(1);
    assertTrue(clientStreamListener.awaitOnReadyAndDrain(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertTrue(clientStream.isReady());
    final int maxToSend = 10 * 1024;
    int clientSent;
    // Verify that flow control will push back on client.
    for (clientSent = 0; clientStream.isReady(); clientSent++) {
      if (clientSent > maxToSend) {
        // It seems like flow control isn't working. _Surely_ flow control would have pushed-back
        // already. If this is normal, please configure the transport to buffer less.
        fail("Too many messages sent before isReady() returned false");
      }
      clientStream.writeMessage(methodDescriptor.streamRequest(largeMessage));
      clientStream.flush();
    }
    assertTrue(clientSent > 0);
    // Make sure there are at least a few messages buffered.
    for (; clientSent < 5; clientSent++) {
      clientStream.writeMessage(methodDescriptor.streamResponse(largeMessage));
      clientStream.flush();
    }
    doPingPong(serverListener);

    int serverReceived = verifyMessageCountAndClose(serverStreamListener.messageQueue, 1);

    clientStream.request(1);
    assertTrue(serverStreamListener.awaitOnReadyAndDrain(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertTrue(serverStream.isReady());
    int serverSent;
    // Verify that flow control will push back on server.
    for (serverSent = 0; serverStream.isReady(); serverSent++) {
      if (serverSent > maxToSend) {
        // It seems like flow control isn't working. _Surely_ flow control would have pushed-back
        // already. If this is normal, please configure the transport to buffer less.
        fail("Too many messages sent before isReady() returned false");
      }
      serverStream.writeMessage(methodDescriptor.streamResponse(largeMessage));
      serverStream.flush();
    }
    assertTrue(serverSent > 0);
    // Make sure there are at least a few messages buffered.
    for (; serverSent < 5; serverSent++) {
      serverStream.writeMessage(methodDescriptor.streamResponse(largeMessage));
      serverStream.flush();
    }
    doPingPong(serverListener);

    int clientReceived = verifyMessageCountAndClose(clientStreamListener.messageQueue, 1);

    serverStream.request(3);
    clientStream.request(3);
    doPingPong(serverListener);
    clientReceived += verifyMessageCountAndClose(clientStreamListener.messageQueue, 3);
    serverReceived += verifyMessageCountAndClose(serverStreamListener.messageQueue, 3);

    // Request the rest
    serverStream.request(clientSent);
    clientStream.request(serverSent);
    clientReceived +=
        verifyMessageCountAndClose(clientStreamListener.messageQueue, serverSent - clientReceived);
    serverReceived +=
        verifyMessageCountAndClose(serverStreamListener.messageQueue, clientSent - serverReceived);

    assertTrue(clientStreamListener.awaitOnReadyAndDrain(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertTrue(clientStream.isReady());
    assertTrue(serverStreamListener.awaitOnReadyAndDrain(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertTrue(serverStream.isReady());

    // Request four more
    for (int i = 0; i < 5; i++) {
      clientStream.writeMessage(methodDescriptor.streamRequest(largeMessage));
      clientStream.flush();
      serverStream.writeMessage(methodDescriptor.streamResponse(largeMessage));
      serverStream.flush();
    }
    doPingPong(serverListener);
    clientReceived += verifyMessageCountAndClose(clientStreamListener.messageQueue, 4);
    serverReceived += verifyMessageCountAndClose(serverStreamListener.messageQueue, 4);

    // Drain exactly how many messages are left
    serverStream.request(1);
    clientStream.request(1);
    clientReceived += verifyMessageCountAndClose(clientStreamListener.messageQueue, 1);
    serverReceived += verifyMessageCountAndClose(serverStreamListener.messageQueue, 1);

    // And now check that the streams can still complete gracefully
    clientStream.writeMessage(methodDescriptor.streamRequest(largeMessage));
    clientStream.flush();
    clientStream.halfClose();
    doPingPong(serverListener);
    assertFalse(serverStreamListener.awaitHalfClosed(TIMEOUT_MS, TimeUnit.MILLISECONDS));

    serverStream.request(1);
    serverReceived += verifyMessageCountAndClose(serverStreamListener.messageQueue, 1);
    assertEquals(clientSent + 6, serverReceived);
    assertTrue(serverStreamListener.awaitHalfClosed(TIMEOUT_MS, TimeUnit.MILLISECONDS));

    serverStream.writeMessage(methodDescriptor.streamResponse(largeMessage));
    serverStream.flush();
    Status status = Status.OK.withDescription("... quite a lengthy discussion");
    serverStream.close(status, new Metadata());
    doPingPong(serverListener);
    try {
      clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
      fail("Expected TimeoutException");
    } catch (TimeoutException expectedException) {
    }

    clientStream.request(1);
    clientReceived += verifyMessageCountAndClose(clientStreamListener.messageQueue, 1);
    assertEquals(serverSent + 6, clientReceived);
    assertCodeEquals(Status.OK, serverStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    Status clientStreamStatus = clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertNotNull(clientStreamListener.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertEquals(status.getCode(), clientStreamStatus.getCode());
    assertEquals(status.getDescription(), clientStreamStatus.getDescription());
  }

  @Test
  public void flowControlDoesNotDeadlockLargeMessage() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener =
        serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    ClientStream clientStream =
        client.newStream(methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    StreamCreation serverStreamCreation =
        serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertEquals(methodDescriptor.getFullMethodName(), serverStreamCreation.method);
    ServerStream serverStream = serverStreamCreation.stream;
    ServerStreamListenerBase serverStreamListener = serverStreamCreation.listener;

    serverStream.writeHeaders(new Metadata(), true);

    String largeMessage = newString(TEST_FLOW_CONTROL_WINDOW + 1);

    serverStream.request(1);
    assertTrue(clientStreamListener.awaitOnReadyAndDrain(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertTrue(clientStream.isReady());
    clientStream.writeMessage(methodDescriptor.streamRequest(largeMessage));
    clientStream.flush();
    doPingPong(serverListener);

    verifyMessageCountAndClose(serverStreamListener.messageQueue, 1);

    clientStream.request(1);
    assertTrue(serverStreamListener.awaitOnReadyAndDrain(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertTrue(serverStream.isReady());
    serverStream.writeMessage(methodDescriptor.streamResponse(largeMessage));
    serverStream.flush();
    doPingPong(serverListener);

    verifyMessageCountAndClose(clientStreamListener.messageQueue, 1);

    // And now check that the streams can still complete normally.
    clientStream.halfClose();
    doPingPong(serverListener);
    serverStream.request(1);
    assertTrue(serverStreamListener.awaitHalfClosed(TIMEOUT_MS, TimeUnit.MILLISECONDS));

    Status status = Status.OK.withDescription("... quite a lengthy discussion");
    serverStream.close(status, new Metadata());
    doPingPong(serverListener);
    clientStream.request(1);
    assertCodeEquals(Status.OK, serverStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    Status clientStreamStatus = clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertNotNull(clientStreamListener.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertEquals(status.getCode(), clientStreamStatus.getCode());
    assertEquals(status.getDescription(), clientStreamStatus.getDescription());
  }

  private int verifyMessageCountAndClose(BlockingQueue<InputStream> messageQueue, int count)
      throws Exception {
    InputStream message;
    for (int i = 0; i < count; i++) {
      message = messageQueue.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
      assertNotNull(message);
      message.close();
    }
    assertNull("no additional message expected", messageQueue.poll());
    return count;
  }

  @Test
  public void messageProducerOnlyProducesRequestedMessages() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener =
        serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    // Start an RPC.
    ClientStream clientStream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    StreamCreation serverStreamCreation =
        serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertEquals(methodDescriptor.getFullMethodName(), serverStreamCreation.method);

    // Have the client send two messages.
    clientStream.writeMessage(methodDescriptor.streamRequest("MESSAGE"));
    clientStream.writeMessage(methodDescriptor.streamRequest("MESSAGE"));
    clientStream.flush();

    doPingPong(serverListener);

    // Verify server only receives one message if that's all it requests.
    serverStreamCreation.stream.request(1);
    verifyMessageCountAndClose(serverStreamCreation.listener.messageQueue, 1);
  }

  @Test
  public void interactionsAfterServerStreamCloseAreNoops() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    // boilerplate
    ClientStream clientStream =
        client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    StreamCreation server
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);

    // setup
    clientStream.request(1);
    server.stream.close(Status.INTERNAL, new Metadata());
    assertNotNull(clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertNotNull(clientStreamListener.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));

    // Ensure that for a closed ServerStream, interactions are noops
    server.stream.writeHeaders(new Metadata(), true);
    server.stream.writeMessage(methodDescriptor.streamResponse("response"));
    server.stream.close(Status.INTERNAL, new Metadata());

    // Make sure new streams still work properly
    doPingPong(serverListener);
  }

  @Test
  public void interactionsAfterClientStreamCancelAreNoops() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    // boilerplate
    ClientStream clientStream =
        client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListener clientListener = mock(ClientStreamListener.class);
    clientStream.start(clientListener);
    StreamCreation server
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);

    // setup
    server.stream.request(1);
    clientStream.cancel(Status.UNKNOWN);
    assertNotNull(server.listener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));

    // Ensure that for a cancelled ClientStream, interactions are noops
    clientStream.writeMessage(methodDescriptor.streamRequest("request"));
    clientStream.halfClose();
    clientStream.cancel(Status.UNKNOWN);

    // Make sure new streams still work properly
    doPingPong(serverListener);
  }

  // Not all transports support the tracer yet
  protected boolean haveTransportTracer() {
    return false;
  }

  @Test
  public void transportTracer_streamStarted() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    if (!haveTransportTracer()) {
      return;
    }

    // start first stream
    long serverFirstTimestampNanos;
    long clientFirstTimestampNanos;
    {
      TransportStats serverBefore = getTransportStats(serverTransportListener.transport);
      assertEquals(0, serverBefore.streamsStarted);
      assertEquals(0, serverBefore.lastRemoteStreamCreatedTimeNanos);
      TransportStats clientBefore = getTransportStats(client);
      assertEquals(0, clientBefore.streamsStarted);
      assertEquals(0, clientBefore.lastRemoteStreamCreatedTimeNanos);

      ClientStream clientStream = client.newStream(
          methodDescriptor, new Metadata(), callOptions, tracers);
      ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
      clientStream.start(clientStreamListener);
      StreamCreation serverStreamCreation = serverTransportListener
          .takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);

      TransportStats serverAfter = getTransportStats(serverTransportListener.transport);
      assertEquals(1, serverAfter.streamsStarted);
      serverFirstTimestampNanos = serverAfter.lastRemoteStreamCreatedTimeNanos;
      assertEquals(fakeCurrentTimeNanos(), serverAfter.lastRemoteStreamCreatedTimeNanos);

      TransportStats clientAfter = getTransportStats(client);
      assertEquals(1, clientAfter.streamsStarted);
      clientFirstTimestampNanos = clientAfter.lastLocalStreamCreatedTimeNanos;
      assertEquals(fakeCurrentTimeNanos(), clientFirstTimestampNanos);

      ServerStream serverStream = serverStreamCreation.stream;
      serverStream.close(Status.OK, new Metadata());
    }

    final long elapsedMillis = 100;
    advanceClock(100, TimeUnit.MILLISECONDS);

    // start second stream
    {
      TransportStats serverBefore = getTransportStats(serverTransportListener.transport);
      assertEquals(1, serverBefore.streamsStarted);
      TransportStats clientBefore = getTransportStats(client);
      assertEquals(1, clientBefore.streamsStarted);

      ClientStream clientStream = client.newStream(
          methodDescriptor, new Metadata(), callOptions, noopTracers);
      ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
      clientStream.start(clientStreamListener);
      StreamCreation serverStreamCreation = serverTransportListener
          .takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);

      TransportStats serverAfter = getTransportStats(serverTransportListener.transport);
      assertEquals(2, serverAfter.streamsStarted);
      assertEquals(
          TimeUnit.MILLISECONDS.toNanos(elapsedMillis),
          serverAfter.lastRemoteStreamCreatedTimeNanos - serverFirstTimestampNanos);
      assertEquals(fakeCurrentTimeNanos(), serverAfter.lastRemoteStreamCreatedTimeNanos);

      TransportStats clientAfter = getTransportStats(client);
      assertEquals(2, clientAfter.streamsStarted);
      assertEquals(
          TimeUnit.MILLISECONDS.toNanos(elapsedMillis),
          clientAfter.lastLocalStreamCreatedTimeNanos - clientFirstTimestampNanos);
      assertEquals(fakeCurrentTimeNanos(), clientAfter.lastLocalStreamCreatedTimeNanos);

      ServerStream serverStream = serverStreamCreation.stream;
      serverStream.close(Status.OK, new Metadata());
    }
  }

  @Test
  public void transportTracer_server_streamEnded_ok() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    ClientStream clientStream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    ServerStream serverStream = serverStreamCreation.stream;
    if (!haveTransportTracer()) {
      return;
    }

    TransportStats serverBefore = getTransportStats(serverTransportListener.transport);
    assertEquals(0, serverBefore.streamsSucceeded);
    assertEquals(0, serverBefore.streamsFailed);
    TransportStats clientBefore = getTransportStats(client);
    assertEquals(0, clientBefore.streamsSucceeded);
    assertEquals(0, clientBefore.streamsFailed);

    clientStream.halfClose();
    serverStream.close(Status.OK, new Metadata());
    // do not validate stats until close() has been called on client
    assertNotNull(clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertNotNull(clientStreamListener.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));


    TransportStats serverAfter = getTransportStats(serverTransportListener.transport);
    assertEquals(1, serverAfter.streamsSucceeded);
    assertEquals(0, serverAfter.streamsFailed);
    TransportStats clientAfter = getTransportStats(client);
    assertEquals(1, clientAfter.streamsSucceeded);
    assertEquals(0, clientAfter.streamsFailed);
  }

  @Test
  public void transportTracer_server_streamEnded_nonOk() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    ClientStream clientStream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    ServerStream serverStream = serverStreamCreation.stream;
    if (!haveTransportTracer()) {
      return;
    }

    TransportStats serverBefore = getTransportStats(serverTransportListener.transport);
    assertEquals(0, serverBefore.streamsFailed);
    assertEquals(0, serverBefore.streamsSucceeded);
    TransportStats clientBefore = getTransportStats(client);
    assertEquals(0, clientBefore.streamsFailed);
    assertEquals(0, clientBefore.streamsSucceeded);

    serverStream.close(Status.UNKNOWN, new Metadata());
    // do not validate stats until close() has been called on client
    assertNotNull(clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertNotNull(clientStreamListener.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));


    TransportStats serverAfter = getTransportStats(serverTransportListener.transport);
    assertEquals(1, serverAfter.streamsFailed);
    assertEquals(0, serverAfter.streamsSucceeded);
    TransportStats clientAfter = getTransportStats(client);
    assertEquals(1, clientAfter.streamsFailed);
    assertEquals(0, clientAfter.streamsSucceeded);

    client.shutdown(Status.UNAVAILABLE);
  }

  @Test
  public void transportTracer_client_streamEnded_nonOk() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    ClientStream clientStream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    MockServerTransportListener serverTransportListener =
        serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    StreamCreation serverStreamCreation =
        serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    if (!haveTransportTracer()) {
      return;
    }

    TransportStats serverBefore = getTransportStats(serverTransportListener.transport);
    assertEquals(0, serverBefore.streamsFailed);
    assertEquals(0, serverBefore.streamsSucceeded);
    TransportStats clientBefore = getTransportStats(client);
    assertEquals(0, clientBefore.streamsFailed);
    assertEquals(0, clientBefore.streamsSucceeded);

    clientStream.cancel(Status.UNKNOWN);
    // do not validate stats until close() has been called on server
    assertNotNull(serverStreamCreation.listener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));

    TransportStats serverAfter = getTransportStats(serverTransportListener.transport);
    assertEquals(1, serverAfter.streamsFailed);
    assertEquals(0, serverAfter.streamsSucceeded);
    TransportStats clientAfter = getTransportStats(client);
    assertEquals(1, clientAfter.streamsFailed);
    assertEquals(0, clientAfter.streamsSucceeded);
  }

  @Test
  public void transportTracer_server_receive_msg() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    ClientStream clientStream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    ServerStream serverStream = serverStreamCreation.stream;
    ServerStreamListenerBase serverStreamListener = serverStreamCreation.listener;
    if (!haveTransportTracer()) {
      return;
    }

    TransportStats serverBefore = getTransportStats(serverTransportListener.transport);
    assertEquals(0, serverBefore.messagesReceived);
    assertEquals(0, serverBefore.lastMessageReceivedTimeNanos);
    TransportStats clientBefore = getTransportStats(client);
    assertEquals(0, clientBefore.messagesSent);
    assertEquals(0, clientBefore.lastMessageSentTimeNanos);

    serverStream.request(1);
    clientStream.writeMessage(methodDescriptor.streamRequest("request"));
    clientStream.flush();
    clientStream.halfClose();
    verifyMessageCountAndClose(serverStreamListener.messageQueue, 1);

    TransportStats serverAfter = getTransportStats(serverTransportListener.transport);
    assertEquals(1, serverAfter.messagesReceived);
    assertEquals(fakeCurrentTimeNanos(), serverAfter.lastMessageReceivedTimeNanos);
    TransportStats clientAfter = getTransportStats(client);
    assertEquals(1, clientAfter.messagesSent);
    assertEquals(fakeCurrentTimeNanos(), clientAfter.lastMessageSentTimeNanos);

    serverStream.close(Status.OK, new Metadata());
  }

  @Test
  public void transportTracer_server_send_msg() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    ClientStream clientStream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    ServerStream serverStream = serverStreamCreation.stream;
    if (!haveTransportTracer()) {
      return;
    }

    TransportStats serverBefore = getTransportStats(serverTransportListener.transport);
    assertEquals(0, serverBefore.messagesSent);
    assertEquals(0, serverBefore.lastMessageSentTimeNanos);
    TransportStats clientBefore = getTransportStats(client);
    assertEquals(0, clientBefore.messagesReceived);
    assertEquals(0, clientBefore.lastMessageReceivedTimeNanos);

    clientStream.request(1);
    serverStream.writeHeaders(new Metadata(), true);
    serverStream.writeMessage(methodDescriptor.streamResponse("response"));
    serverStream.flush();
    verifyMessageCountAndClose(clientStreamListener.messageQueue, 1);

    TransportStats serverAfter = getTransportStats(serverTransportListener.transport);
    assertEquals(1, serverAfter.messagesSent);
    assertEquals(fakeCurrentTimeNanos(), serverAfter.lastMessageSentTimeNanos);
    TransportStats clientAfter = getTransportStats(client);
    assertEquals(1, clientAfter.messagesReceived);
    assertEquals(fakeCurrentTimeNanos(), clientAfter.lastMessageReceivedTimeNanos);

    serverStream.close(Status.OK, new Metadata());
  }

  @Test
  public void socketStats() throws Exception {
    server.start(serverListener);
    ManagedClientTransport client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    ClientStream clientStream = client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);

    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    ServerStream serverStream = serverStreamCreation.stream;

    SocketAddress serverAddress = clientStream.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
    SocketAddress clientAddress = serverStream.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);

    SocketStats clientSocketStats = client.getStats().get();
    assertEquals(
        "clientLocal " + clientStream.getAttributes(), clientAddress, clientSocketStats.local);
    assertEquals(
        "clientRemote " + clientStream.getAttributes(), serverAddress, clientSocketStats.remote);
    // very basic sanity check that socket options are populated
    assertNotNull(clientSocketStats.socketOptions.lingerSeconds);
    assertTrue(clientSocketStats.socketOptions.others.containsKey("SO_SNDBUF"));

    SocketStats serverSocketStats = serverTransportListener.transport.getStats().get();
    assertEquals(
        "serverLocal " + serverStream.getAttributes(), serverAddress, serverSocketStats.local);
    assertEquals(
        "serverRemote " + serverStream.getAttributes(), clientAddress, serverSocketStats.remote);
    // very basic sanity check that socket options are populated
    assertNotNull(serverSocketStats.socketOptions.lingerSeconds);
    assertTrue(serverSocketStats.socketOptions.others.containsKey("SO_SNDBUF"));
  }

  /** This assumes the server limits metadata size to GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE. */
  @Test
  public void serverChecksInboundMetadataSize() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    Metadata tooLargeMetadata = new Metadata();
    tooLargeMetadata.put(
        Metadata.Key.of("foo-bin", Metadata.BINARY_BYTE_MARSHALLER),
        new byte[GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE]);

    ClientStream clientStream = client.newStream(
        methodDescriptor, tooLargeMetadata, callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);

    clientStream.writeMessage(methodDescriptor.streamRequest("foo"));
    clientStream.halfClose();
    clientStream.request(1);
    // Server shouldn't have created a stream, so nothing to clean up on server-side

    // If this times out, the server probably isn't noticing the metadata size
    Status status = clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    List<Status.Code> codeOptions = Arrays.asList(
        Status.Code.UNKNOWN, Status.Code.RESOURCE_EXHAUSTED, Status.Code.INTERNAL);
    if (!codeOptions.contains(status.getCode())) {
      fail("Status code was not expected: " + status);
    }
  }

  /** This assumes the client limits metadata size to GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE. */
  @Test
  public void clientChecksInboundMetadataSize_header() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    Metadata tooLargeMetadata = new Metadata();
    tooLargeMetadata.put(
        Metadata.Key.of("foo-bin", Metadata.BINARY_BYTE_MARSHALLER),
        new byte[GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE]);

    ClientStream clientStream =
        client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);

    clientStream.writeMessage(methodDescriptor.streamRequest("foo"));
    clientStream.halfClose();
    clientStream.request(1);

    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);

    serverStreamCreation.stream.request(1);
    serverStreamCreation.stream.writeHeaders(tooLargeMetadata, true);
    serverStreamCreation.stream.writeMessage(methodDescriptor.streamResponse("response"));
    serverStreamCreation.stream.close(Status.OK, new Metadata());

    Status status = clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    List<Status.Code> codeOptions = Arrays.asList(
        Status.Code.UNKNOWN, Status.Code.RESOURCE_EXHAUSTED, Status.Code.INTERNAL);
    if (!codeOptions.contains(status.getCode())) {
      fail("Status code was not expected: " + status);
    }
    assertFalse(clientStreamListener.headers.isDone());
  }

  /** This assumes the client limits metadata size to GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE. */
  @Test
  public void clientChecksInboundMetadataSize_trailer() throws Exception {
    server.start(serverListener);
    client = newClientTransport(server);
    startTransport(client, mockClientTransportListener);
    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    serverTransport = serverTransportListener.transport;

    Metadata.Key<String> tellTaleKey
        = Metadata.Key.of("tell-tale", Metadata.ASCII_STRING_MARSHALLER);
    Metadata tooLargeMetadata = new Metadata();
    tooLargeMetadata.put(tellTaleKey, "true");
    tooLargeMetadata.put(
        Metadata.Key.of("foo-bin", Metadata.BINARY_BYTE_MARSHALLER),
        new byte[GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE]);

    ClientStream clientStream =
        client.newStream(
        methodDescriptor, new Metadata(), callOptions, tracers);
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);

    clientStream.writeMessage(methodDescriptor.streamRequest("foo"));
    clientStream.halfClose();
    clientStream.request(1);

    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);

    serverStreamCreation.stream.request(1);
    serverStreamCreation.stream.writeHeaders(new Metadata(), true);
    serverStreamCreation.stream.writeMessage(methodDescriptor.streamResponse("response"));
    serverStreamCreation.stream.close(Status.OK, tooLargeMetadata);

    Status status = clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    List<Status.Code> codeOptions = Arrays.asList(
        Status.Code.UNKNOWN, Status.Code.RESOURCE_EXHAUSTED, Status.Code.INTERNAL);
    if (!codeOptions.contains(status.getCode())) {
      fail("Status code was not expected: " + status);
    }
    Metadata metadata = clientStreamListener.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertNull(metadata.get(tellTaleKey));
  }

  /**
   * Helper that simply does an RPC. It can be used similar to a sleep for negative testing: to give
   * time for actions _not_ to happen. Since it is based on doing an actual RPC with actual
   * callbacks, it generally provides plenty of time for Runnables to execute. But it is also faster
   * on faster machines and more reliable on slower machines.
   */
  private void doPingPong(MockServerListener serverListener) throws Exception {
    ManagedClientTransport client = newClientTransport(server);
    ManagedClientTransport.Listener listener = mock(ManagedClientTransport.Listener.class);
    startTransport(client, listener);
    ClientStream clientStream = client.newStream(
        methodDescriptor, new Metadata(), callOptions,
        new ClientStreamTracer[] { new ClientStreamTracer() {} });
    ClientStreamListenerBase clientStreamListener = new ClientStreamListenerBase();
    clientStream.start(clientStreamListener);

    MockServerTransportListener serverTransportListener
        = serverListener.takeListenerOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    StreamCreation serverStreamCreation
        = serverTransportListener.takeStreamOrFail(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    ServerStream serverStream = serverStreamCreation.stream;
    ServerStreamListenerBase serverStreamListener = serverStreamCreation.listener;

    serverStream.close(Status.OK, new Metadata());
    assertNotNull(clientStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertNotNull(clientStreamListener.trailers.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    assertNotNull(serverStreamListener.status.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    client.shutdown(Status.UNAVAILABLE);
  }

  /**
   * Only assert that the Status.Code matches, but provide the entire actual result in case the
   * assertion fails.
   */
  private static void assertCodeEquals(String message, Status expected, Status actual) {
    if (expected == null) {
      fail("expected should not be null");
    }
    if (actual == null || !expected.getCode().equals(actual.getCode())) {
      assertEquals(message, expected, actual);
    }
  }

  private static void assertCodeEquals(Status expected, Status actual) {
    assertCodeEquals(null, expected, actual);
  }

  private static void assertStatusEquals(Status expected, Status actual) {
    if (expected == null) {
      fail("expected should not be null");
    }
    if (actual == null || !expected.getCode().equals(actual.getCode())
        || !Objects.equal(expected.getDescription(), actual.getDescription())
        || !Objects.equal(expected.getCause(), actual.getCause())) {
      assertEquals(expected, actual);
    }
  }

  /**
   * Verifies that the client status is as expected. By default, the code and description should
   * be present, and the cause should be stripped away.
   */
  private static void checkClientStatus(Status expectedStatus, Status clientStreamStatus) {
    if (!clientStreamStatus.isOk() && clientStreamStatus.getCode() != expectedStatus.getCode()) {
      System.out.println("Full Status:  " + clientStreamStatus);
    }
    assertEquals(expectedStatus.getCode(), clientStreamStatus.getCode());
    assertEquals(expectedStatus.getDescription(), clientStreamStatus.getDescription());
    assertNull(clientStreamStatus.getCause());
  }

  private static boolean waitForFuture(Future<?> future, long timeout, TimeUnit unit)
      throws InterruptedException {
    try {
      future.get(timeout, unit);
    } catch (ExecutionException ex) {
      throw new AssertionError(ex);
    } catch (TimeoutException ex) {
      return false;
    }
    return true;
  }

  private static void runIfNotNull(Runnable runnable) {
    if (runnable != null) {
      runnable.run();
    }
  }

  protected static void startTransport(
      ManagedClientTransport clientTransport,
      ManagedClientTransport.Listener listener) {
    runIfNotNull(clientTransport.start(listener));
    verify(listener, timeout(TIMEOUT_MS)).filterTransport(any());
    verify(listener, timeout(TIMEOUT_MS)).transportReady();
  }

  private final class TestHeaderClientStreamTracer extends TestClientStreamTracer {
    Attributes transportAttrs;

    @Override
    public void streamCreated(Attributes transportAttrs, Metadata metadata) {
      this.transportAttrs = transportAttrs;
      metadata.put(tracerHeaderKey, tracerKeyValue);
    }
  }

  public static class MockServerListener implements ServerListener {
    public final BlockingQueue<MockServerTransportListener> listeners
        = new LinkedBlockingQueue<>();
    private final SettableFuture<?> shutdown = SettableFuture.create();

    @Override
    public ServerTransportListener transportCreated(ServerTransport transport) {
      MockServerTransportListener listener = new MockServerTransportListener(transport);
      listeners.add(listener);
      return listener;
    }

    @Override
    public void serverShutdown() {
      assertTrue(shutdown.set(null));
    }

    public boolean waitForShutdown(long timeout, TimeUnit unit) throws InterruptedException {
      return waitForFuture(shutdown, timeout, unit);
    }

    public MockServerTransportListener takeListenerOrFail(long timeout, TimeUnit unit)
        throws InterruptedException {
      MockServerTransportListener listener = listeners.poll(timeout, unit);
      if (listener == null) {
        fail("Timed out waiting for server transport");
      }
      return listener;
    }
  }

  public static class MockServerTransportListener implements ServerTransportListener {
    public final ServerTransport transport;
    public final BlockingQueue<StreamCreation> streams = new LinkedBlockingQueue<>();
    private final SettableFuture<?> terminated = SettableFuture.create();

    public MockServerTransportListener(ServerTransport transport) {
      this.transport = transport;
    }

    @Override
    public void streamCreated(ServerStream stream, String method, Metadata headers) {
      ServerStreamListenerBase listener = new ServerStreamListenerBase();
      streams.add(new StreamCreation(stream, method, headers, listener));
      stream.setListener(listener);
    }

    @Override
    public Attributes transportReady(Attributes attributes) {
      assertFalse(terminated.isDone());
      return Attributes.newBuilder()
          .setAll(attributes)
          .set(ADDITIONAL_TRANSPORT_ATTR_KEY, "additional attribute value")
          .build();
    }

    @Override
    public void transportTerminated() {
      assertTrue(terminated.set(null));
    }

    public boolean waitForTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return waitForFuture(terminated, timeout, unit);
    }

    public boolean isTerminated() {
      return terminated.isDone();
    }

    public StreamCreation takeStreamOrFail(long timeout, TimeUnit unit)
        throws InterruptedException {
      StreamCreation stream = streams.poll(timeout, unit);
      if (stream == null) {
        fail("Timed out waiting for server stream");
      }
      return stream;
    }
  }

  public static class ServerStreamListenerBase implements ServerStreamListener {
    public final BlockingQueue<InputStream> messageQueue = new LinkedBlockingQueue<>();
    // Would have used Void instead of Object, but null elements are not allowed
    private final BlockingQueue<Object> readyQueue = new LinkedBlockingQueue<>();
    private final CountDownLatch halfClosedLatch = new CountDownLatch(1);
    private final SettableFuture<Status> status = SettableFuture.create();

    private boolean awaitOnReady(int timeout, TimeUnit unit) throws Exception {
      return readyQueue.poll(timeout, unit) != null;
    }

    private boolean awaitOnReadyAndDrain(int timeout, TimeUnit unit) throws Exception {
      if (!awaitOnReady(timeout, unit)) {
        return false;
      }
      // Throw the rest away
      readyQueue.drainTo(Lists.newArrayList());
      return true;
    }

    private boolean awaitHalfClosed(int timeout, TimeUnit unit) throws Exception {
      return halfClosedLatch.await(timeout, unit);
    }

    @Override
    public void messagesAvailable(MessageProducer producer) {
      if (status.isDone()) {
        fail("messagesAvailable invoked after closed");
      }
      InputStream message;
      while ((message = producer.next()) != null) {
        messageQueue.add(message);
      }
    }

    @Override
    public void onReady() {
      if (status.isDone()) {
        fail("onReady invoked after closed");
      }
      readyQueue.add(new Object());
    }

    @Override
    public void halfClosed() {
      if (status.isDone()) {
        fail("halfClosed invoked after closed");
      }
      halfClosedLatch.countDown();
    }

    @Override
    public void closed(Status status) {
      if (this.status.isDone()) {
        fail("closed invoked more than once");
      }
      this.status.set(status);
    }
  }

  public static class ClientStreamListenerBase implements ClientStreamListener {
    public final BlockingQueue<InputStream> messageQueue = new LinkedBlockingQueue<>();
    // Would have used Void instead of Object, but null elements are not allowed
    private final BlockingQueue<Object> readyQueue = new LinkedBlockingQueue<>();
    private final SettableFuture<Metadata> headers = SettableFuture.create();
    private final SettableFuture<Metadata> trailers = SettableFuture.create();
    private final SettableFuture<Status> status = SettableFuture.create();

    private boolean awaitOnReady(int timeout, TimeUnit unit) throws Exception {
      return readyQueue.poll(timeout, unit) != null;
    }

    private boolean awaitOnReadyAndDrain(int timeout, TimeUnit unit) throws Exception {
      if (!awaitOnReady(timeout, unit)) {
        return false;
      }
      // Throw the rest away
      readyQueue.drainTo(Lists.newArrayList());
      return true;
    }

    @Override
    public void messagesAvailable(MessageProducer producer) {
      if (status.isDone()) {
        fail("messagesAvailable invoked after closed");
      }
      InputStream message;
      while ((message = producer.next()) != null) {
        messageQueue.add(message);
      }
    }

    @Override
    public void onReady() {
      if (status.isDone()) {
        fail("onReady invoked after closed");
      }
      readyQueue.add(new Object());
    }

    @Override
    public void headersRead(Metadata headers) {
      if (status.isDone()) {
        fail("headersRead invoked after closed");
      }
      this.headers.set(headers);
    }

    @Override
    public void closed(Status status, RpcProgress rpcProgress, Metadata trailers) {
      if (this.status.isDone()) {
        fail("headersRead invoked after closed");
      }
      this.status.set(status);
      this.trailers.set(trailers);
    }
  }

  public static class StreamCreation {
    public final ServerStream stream;
    public final String method;
    public final Metadata headers;
    public final ServerStreamListenerBase listener;

    public StreamCreation(
        ServerStream stream, String method, Metadata headers, ServerStreamListenerBase listener) {
      this.stream = stream;
      this.method = method;
      this.headers = headers;
      this.listener = listener;
    }
  }

  private static class StringMarshaller implements MethodDescriptor.Marshaller<String> {
    public static final StringMarshaller INSTANCE = new StringMarshaller();

    @Override
    public InputStream stream(String value) {
      return new ByteArrayInputStream(value.getBytes(UTF_8));
    }

    @Override
    public String parse(InputStream stream) {
      try {
        return new String(ByteStreams.toByteArray(stream), UTF_8);
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }
  }

  private static class StringBinaryMarshaller implements Metadata.BinaryMarshaller<String> {
    public static final StringBinaryMarshaller INSTANCE = new StringBinaryMarshaller();

    @Override
    public byte[] toBytes(String value) {
      return value.getBytes(UTF_8);
    }

    @Override
    public String parseBytes(byte[] serialized) {
      return new String(serialized, UTF_8);
    }
  }

  private static TransportStats getTransportStats(InternalInstrumented<SocketStats> socket)
      throws ExecutionException, InterruptedException {
    return socket.getStats().get().data;
  }

  private static String newString(int size) {
    StringBuilder sb = new StringBuilder(size);
    for (int i = 0; i < size; i++) {
      sb.append('a');
    }
    return sb.toString();
  }
}
