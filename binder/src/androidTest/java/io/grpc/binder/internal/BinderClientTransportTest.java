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

package io.grpc.binder.internal;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.os.DeadObjectException;
import android.os.Parcel;
import android.os.RemoteException;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.Empty;
import io.grpc.CallOptions;
import io.grpc.ClientStreamTracer;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.binder.AndroidComponentAddress;
import io.grpc.binder.AsyncSecurityPolicy;
import io.grpc.binder.BinderServerBuilder;
import io.grpc.binder.HostServices;
import io.grpc.binder.SecurityPolicy;
import io.grpc.binder.internal.OneWayBinderProxies.BlackHoleOneWayBinderProxy;
import io.grpc.binder.internal.OneWayBinderProxies.BlockingBinderDecorator;
import io.grpc.binder.internal.OneWayBinderProxies.ThrowingOneWayBinderProxy;
import io.grpc.internal.ClientStream;
import io.grpc.internal.ClientStreamListener;
import io.grpc.internal.ClientTransportFactory.ClientTransportOptions;
import io.grpc.internal.FixedObjectPool;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.StreamListener;
import io.grpc.protobuf.lite.ProtoLiteUtils;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Client-side transport tests for binder channel. Like BinderChannelSmokeTest, this covers edge
 * cases not exercised by AbstractTransportTest, but in this case we're dealing with rare ordering
 * issues at the transport level, so we use a BinderTransport.BinderClientTransport directly, rather
 * than a channel.
 */
@RunWith(AndroidJUnit4.class)
public final class BinderClientTransportTest {
  private static final long TIMEOUT_SECONDS = 5;

  private static final ClientStreamTracer[] tracers =
      new ClientStreamTracer[] {new ClientStreamTracer() {}};

  private final Context appContext = ApplicationProvider.getApplicationContext();

  MethodDescriptor.Marshaller<Empty> marshaller =
      ProtoLiteUtils.marshaller(Empty.getDefaultInstance());

  MethodDescriptor<Empty, Empty> methodDesc =
      MethodDescriptor.newBuilder(marshaller, marshaller)
          .setFullMethodName("test/method")
          .setType(MethodDescriptor.MethodType.UNARY)
          .build();

  MethodDescriptor<Empty, Empty> streamingMethodDesc =
      MethodDescriptor.newBuilder(marshaller, marshaller)
          .setFullMethodName("test/methodServerStreaming")
          .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
          .build();

  AndroidComponentAddress serverAddress;
  BinderTransport.BinderClientTransport transport;
  BlockingSecurityPolicy blockingSecurityPolicy = new BlockingSecurityPolicy();

  private final ObjectPool<ScheduledExecutorService> executorServicePool =
      new FixedObjectPool<>(Executors.newScheduledThreadPool(1));
  private final ObjectPool<ScheduledExecutorService> offloadServicePool =
      new FixedObjectPool<>(Executors.newScheduledThreadPool(1));
  private final TestTransportListener transportListener = new TestTransportListener();
  private final TestStreamListener streamListener = new TestStreamListener();

  @Before
  public void setUp() throws Exception {
    ServerCallHandler<Empty, Empty> callHandler =
        ServerCalls.asyncUnaryCall(
            (req, respObserver) -> {
              respObserver.onNext(req);
              respObserver.onCompleted();
            });

    ServerCallHandler<Empty, Empty> streamingCallHandler =
        ServerCalls.asyncServerStreamingCall(
            (req, respObserver) -> {
              for (int i = 0; i < 100; i++) {
                respObserver.onNext(req);
              }
              respObserver.onCompleted();
            });

    ServerServiceDefinition serviceDef =
        ServerServiceDefinition.builder("test")
            .addMethod(methodDesc, callHandler)
            .addMethod(streamingMethodDesc, streamingCallHandler)
            .build();

    serverAddress = HostServices.allocateService(appContext);
    HostServices.configureService(
        serverAddress,
        HostServices.serviceParamsBuilder()
            .setServerFactory(
                (service, receiver) ->
                    BinderServerBuilder.forAddress(serverAddress, receiver)
                        .addService(serviceDef)
                        .build())
            .build());
  }

  private class BinderClientTransportBuilder {
    final BinderClientTransportFactory.Builder factoryBuilder =
        new BinderClientTransportFactory.Builder()
            .setSourceContext(appContext)
            .setScheduledExecutorPool(executorServicePool)
            .setOffloadExecutorPool(offloadServicePool);

    public BinderClientTransportBuilder setSecurityPolicy(SecurityPolicy securityPolicy) {
      factoryBuilder.setSecurityPolicy(securityPolicy);
      return this;
    }

    public BinderClientTransportBuilder setBinderDecorator(
        OneWayBinderProxy.Decorator binderDecorator) {
      factoryBuilder.setBinderDecorator(binderDecorator);
      return this;
    }

    public BinderClientTransportBuilder setReadyTimeoutMillis(int timeoutMillis) {
      factoryBuilder.setReadyTimeoutMillis(timeoutMillis);
      return this;
    }

    public BinderTransport.BinderClientTransport build() {
      return factoryBuilder
          .buildClientTransportFactory()
          .newClientTransport(serverAddress, new ClientTransportOptions(), null);
    }
  }

  @After
  public void tearDown() throws Exception {
    blockingSecurityPolicy.provideNextCheckAuthorizationResult(Status.ABORTED);
    transport.shutdownNow(Status.OK);
    HostServices.awaitServiceShutdown();
    shutdownAndTerminate(executorServicePool.getObject());
    shutdownAndTerminate(offloadServicePool.getObject());
  }

  private static void shutdownAndTerminate(ExecutorService executorService)
      throws InterruptedException {
    executorService.shutdownNow();
    if (!executorService.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      throw new AssertionError("executor failed to terminate promptly");
    }
  }

  @Test
  public void testShutdownBeforeStreamStart_b153326034() throws Exception {
    transport = new BinderClientTransportBuilder().build();
    startAndAwaitReady(transport, transportListener);
    ClientStream stream =
        transport.newStream(methodDesc, new Metadata(), CallOptions.DEFAULT, tracers);
    transport.shutdownNow(Status.UNKNOWN.withDescription("reasons"));

    // This shouldn't throw an exception.
    stream.start(streamListener);
  }

  @Test
  public void testRequestWhileStreamIsWaitingOnCall_b154088869() throws Exception {
    transport = new BinderClientTransportBuilder().build();
    startAndAwaitReady(transport, transportListener);
    ClientStream stream =
        transport.newStream(streamingMethodDesc, new Metadata(), CallOptions.DEFAULT, tracers);

    stream.start(streamListener);
    stream.writeMessage(marshaller.stream(Empty.getDefaultInstance()));
    stream.halfClose();
    stream.request(3);

    streamListener.readAndDiscardMessages(2);

    // Without the fix, this loops forever.
    stream.request(2);
  }

  @Test
  public void testTransactionForDiscardedCall_b155244043() throws Exception {
    transport = new BinderClientTransportBuilder().build();
    startAndAwaitReady(transport, transportListener);
    ClientStream stream =
        transport.newStream(streamingMethodDesc, new Metadata(), CallOptions.DEFAULT, tracers);

    stream.start(streamListener);
    stream.writeMessage(marshaller.stream(Empty.getDefaultInstance()));

    assertThat(transport.getOngoingCalls()).hasSize(1);
    int callId = transport.getOngoingCalls().keySet().iterator().next();
    stream.cancel(Status.UNKNOWN);

    // Send a transaction to the no-longer present call ID. It should be silently ignored.
    Parcel p = Parcel.obtain();
    transport.handleTransaction(callId, p);
    p.recycle();
  }

  @Test
  public void testBadTransactionStreamThroughput_b163053382() throws Exception {
    transport = new BinderClientTransportBuilder().build();
    startAndAwaitReady(transport, transportListener);
    ClientStream stream =
        transport.newStream(streamingMethodDesc, new Metadata(), CallOptions.DEFAULT, tracers);

    stream.start(streamListener);
    stream.writeMessage(marshaller.stream(Empty.getDefaultInstance()));
    stream.halfClose();
    stream.request(1000);

    // We should eventually see all messages despite receiving no more transactions from the server.
    streamListener.readAndDiscardMessages(100);
  }

  @Test
  public void testMessageProducerClosedAfterStream_b169313545() throws Exception {
    transport = new BinderClientTransportBuilder().build();
    startAndAwaitReady(transport, transportListener);
    ClientStream stream =
        transport.newStream(methodDesc, new Metadata(), CallOptions.DEFAULT, tracers);

    stream.start(streamListener);
    stream.writeMessage(marshaller.stream(Empty.getDefaultInstance()));
    stream.halfClose();
    stream.request(2);

    // Wait until we receive the first message.
    streamListener.awaitMessages();

    // Now cancel the stream, forcing it to close.
    stream.cancel(Status.CANCELLED);

    // The message producer shouldn't throw an exception if we drain it now.
    streamListener.drainMessages();
  }

  @Test
  public void testNewStreamBeforeTransportReadyFails() throws Exception {
    // Use a special SecurityPolicy that lets us act before the transport is setup/ready.
    transport =
        new BinderClientTransportBuilder().setSecurityPolicy(blockingSecurityPolicy).build();
    transport.start(transportListener).run();
    ClientStream stream =
        transport.newStream(streamingMethodDesc, new Metadata(), CallOptions.DEFAULT, tracers);
    stream.start(streamListener);
    assertThat(streamListener.awaitClose().getCode()).isEqualTo(Code.INTERNAL);

    // Unblock the SETUP_TRANSPORT handshake and make sure it becomes ready in the usual way.
    blockingSecurityPolicy.provideNextCheckAuthorizationResult(Status.OK);
    transportListener.awaitReady();
  }

  @Test
  public void testTxnFailureDuringSetup() throws Exception {
    BlockingBinderDecorator<ThrowingOneWayBinderProxy> decorator = new BlockingBinderDecorator<>();
    transport = new BinderClientTransportBuilder().setBinderDecorator(decorator).build();
    transport.start(transportListener).run();
    ThrowingOneWayBinderProxy endpointBinder =
        new ThrowingOneWayBinderProxy(decorator.takeNextRequest());
    DeadObjectException doe = new DeadObjectException("ouch");
    endpointBinder.setRemoteException(doe);
    decorator.putNextResult(endpointBinder);

    Status shutdownStatus = transportListener.awaitShutdown();
    assertThat(shutdownStatus.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(shutdownStatus.getCause()).isInstanceOf(RemoteException.class);
    transportListener.awaitTermination();

    ClientStream stream =
        transport.newStream(streamingMethodDesc, new Metadata(), CallOptions.DEFAULT, tracers);
    stream.start(streamListener);

    Status streamStatus = streamListener.awaitClose();
    assertThat(streamStatus.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(streamStatus.getCause()).isSameInstanceAs(doe);
  }

  @Test
  public void testTxnFailurePostSetup() throws Exception {
    BlockingBinderDecorator<ThrowingOneWayBinderProxy> decorator = new BlockingBinderDecorator<>();
    transport = new BinderClientTransportBuilder().setBinderDecorator(decorator).build();
    transport.start(transportListener).run();
    ThrowingOneWayBinderProxy endpointBinder =
        new ThrowingOneWayBinderProxy(decorator.takeNextRequest());
    decorator.putNextResult(endpointBinder);
    ThrowingOneWayBinderProxy serverBinder =
        new ThrowingOneWayBinderProxy(decorator.takeNextRequest());
    DeadObjectException doe = new DeadObjectException("ouch");
    serverBinder.setRemoteException(doe);
    decorator.putNextResult(serverBinder);
    transportListener.awaitReady();

    ClientStream stream =
        transport.newStream(streamingMethodDesc, new Metadata(), CallOptions.DEFAULT, tracers);
    stream.start(streamListener);
    stream.writeMessage(marshaller.stream(Empty.getDefaultInstance()));
    stream.halfClose();
    stream.request(1);

    Status streamStatus = streamListener.awaitClose();
    assertThat(streamStatus.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(streamStatus.getCause()).isSameInstanceAs(doe);
  }

  @Test
  public void testBlackHoleEndpointConnectTimeout() throws Exception {
    BlockingBinderDecorator<BlackHoleOneWayBinderProxy> decorator = new BlockingBinderDecorator<>();
    transport =
        new BinderClientTransportBuilder()
            .setBinderDecorator(decorator)
            .setReadyTimeoutMillis(1_234)
            .build();
    transport.start(transportListener).run();
    BlackHoleOneWayBinderProxy endpointBinder =
        new BlackHoleOneWayBinderProxy(decorator.takeNextRequest());
    endpointBinder.dropAllTransactions(true);
    decorator.putNextResult(endpointBinder);
    Status transportStatus = transportListener.awaitShutdown();
    assertThat(transportStatus.getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
    assertThat(transportStatus.getDescription()).contains("1234");
    transportListener.awaitTermination();
  }

  @Test
  public void testBlackHoleSecurityPolicyConnectTimeout() throws Exception {
    transport =
        new BinderClientTransportBuilder()
            .setSecurityPolicy(blockingSecurityPolicy)
            .setReadyTimeoutMillis(1_234)
            .build();
    transport.start(transportListener).run();
    Status transportStatus = transportListener.awaitShutdown();
    assertThat(transportStatus.getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
    assertThat(transportStatus.getDescription()).contains("1234");
    transportListener.awaitTermination();
    blockingSecurityPolicy.provideNextCheckAuthorizationResult(Status.OK);
  }

  @Test
  public void testAsyncSecurityPolicyFailure() throws Exception {
    SettableAsyncSecurityPolicy securityPolicy = new SettableAsyncSecurityPolicy();
    transport = new BinderClientTransportBuilder().setSecurityPolicy(securityPolicy).build();
    RuntimeException exception = new NullPointerException();
    securityPolicy.setAuthorizationException(exception);
    transport.start(transportListener).run();
    Status transportStatus = transportListener.awaitShutdown();
    assertThat(transportStatus.getCode()).isEqualTo(Code.INTERNAL);
    assertThat(transportStatus.getCause()).isEqualTo(exception);
    transportListener.awaitTermination();
  }

  @Test
  public void testAsyncSecurityPolicySuccess() throws Exception {
    SettableAsyncSecurityPolicy securityPolicy = new SettableAsyncSecurityPolicy();
    transport = new BinderClientTransportBuilder().setSecurityPolicy(securityPolicy).build();
    securityPolicy.setAuthorizationResult(Status.PERMISSION_DENIED);
    transport.start(transportListener).run();
    Status transportStatus = transportListener.awaitShutdown();
    assertThat(transportStatus.getCode()).isEqualTo(Code.PERMISSION_DENIED);
    transportListener.awaitTermination();
  }

  private static void startAndAwaitReady(
      BinderTransport.BinderClientTransport transport, TestTransportListener transportListener)
      throws Exception {
    transport.start(transportListener).run();
    transportListener.awaitReady();
  }

  private static final class TestTransportListener implements ManagedClientTransport.Listener {
    public boolean inUse;
    private final SettableFuture<Boolean> isReady = SettableFuture.create();
    private final SettableFuture<Status> shutdownStatus = SettableFuture.create();
    private final SettableFuture<Boolean> isTerminated = SettableFuture.create();

    @Override
    public void transportShutdown(Status shutdownStatus) {
      if (!this.shutdownStatus.set(shutdownStatus)) {
        throw new IllegalStateException("transportShutdown() already called");
      }
    }

    public Status awaitShutdown() throws Exception {
      return shutdownStatus.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void transportTerminated() {
      if (!isTerminated.set(true)) {
        throw new IllegalStateException("isTerminated() already called");
      }
    }

    public void awaitTermination() throws Exception {
      isTerminated.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void transportReady() {
      if (!isReady.set(true)) {
        throw new IllegalStateException("isTerminated() already called");
      }
    }

    public void awaitReady() throws Exception {
      isReady.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void transportInUse(boolean inUse) {
      this.inUse = inUse;
    }
  }

  private static final class TestStreamListener implements ClientStreamListener {

    public boolean ready;
    public Metadata headers;

    @GuardedBy("this")
    private final Deque<MessageProducer> messageProducers = new ArrayDeque<>();

    @GuardedBy("this")
    @Nullable
    private Status closedStatus;

    @Override
    public synchronized void messagesAvailable(StreamListener.MessageProducer messageProducer) {
      messageProducers.add(messageProducer);
      notifyAll();
    }

    /** Blocks until at least one MessageProducer has been provided for reading. */
    public synchronized void awaitMessages() throws InterruptedException {
      while (messageProducers.isEmpty()) {
        wait();
      }
    }

    /** Blocks until {@code n} messages can be produced (and discarded). */
    public synchronized void readAndDiscardMessages(int n)
        throws InterruptedException, IOException {
      while (n > 0) {
        while (closedStatus == null && messageProducers.isEmpty()) {
          wait();
        }
        if (closedStatus != null) {
          throw closedStatus.withDescription("premature close").asRuntimeException();
        }
        try (InputStream message = messageProducers.peek().next()) {
          if (message == null) {
            messageProducers.remove();
            continue;
          }
          n -= 1;
        }
      }
    }

    public synchronized Status awaitClose() {
      while (closedStatus == null) {
        try {
          wait();
        } catch (InterruptedException inte) {
          throw new AssertionError("Interrupted waiting for close");
        }
      }
      return closedStatus;
    }

    /** Discards any messages available on the stream without reading them. Does not block. */
    public synchronized int drainMessages() throws IOException {
      int n = 0;
      while (!messageProducers.isEmpty()) {
        try (InputStream message = messageProducers.peek().next()) {
          if (message == null) {
            messageProducers.remove();
            continue;
          }
          n += 1;
        }
      }
      return n;
    }

    @Override
    public void onReady() {
      ready = true;
    }

    @Override
    public void headersRead(Metadata headers) {
      this.headers = headers;
    }

    @Override
    public synchronized void closed(Status status, RpcProgress rpcProgress, Metadata trailers) {
      this.closedStatus = status;
      notifyAll();
    }
  }

  /**
   * A SecurityPolicy that blocks the transport authorization check until a test sets the outcome.
   */
  static class BlockingSecurityPolicy extends SecurityPolicy {
    private final BlockingQueue<Status> results = new LinkedBlockingQueue<>();

    public void provideNextCheckAuthorizationResult(Status status) {
      results.add(status);
    }

    @Override
    public Status checkAuthorization(int uid) {
      try {
        return results.take();
      } catch (InterruptedException e) {
        return Status.fromThrowable(e);
      }
    }
  }

  /** An AsyncSecurityPolicy that lets a test specify the outcome of checkAuthorizationAsync(). */
  static class SettableAsyncSecurityPolicy extends AsyncSecurityPolicy {
    private SettableFuture<Status> result = SettableFuture.create();

    public void clearAuthorizationResult() {
      result = SettableFuture.create();
    }

    public boolean setAuthorizationResult(Status status) {
      return result.set(status);
    }

    public boolean setAuthorizationException(Throwable t) {
      return result.setException(t);
    }

    public ListenableFuture<Status> checkAuthorizationAsync(int uid) {
      return Futures.nonCancellationPropagating(result);
    }
  }
}
