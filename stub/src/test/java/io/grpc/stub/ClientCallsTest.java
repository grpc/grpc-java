/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc.stub;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.NoopClientCall;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls.StubType;
import io.grpc.stub.ServerCalls.NoopStreamObserver;
import io.grpc.stub.ServerCalls.ServerStreamingMethod;
import io.grpc.stub.ServerCalls.UnaryMethod;
import io.grpc.stub.ServerCallsTest.IntegerMarshaller;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit tests for {@link ClientCalls}.
 */
@RunWith(JUnit4.class)
public class ClientCallsTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private static final MethodDescriptor<Integer, Integer> UNARY_METHOD =
      MethodDescriptor.<Integer, Integer>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName("some/method")
          .setRequestMarshaller(new IntegerMarshaller())
          .setResponseMarshaller(new IntegerMarshaller())
          .build();
  private static final MethodDescriptor<Integer, Integer> SERVER_STREAMING_METHOD =
      UNARY_METHOD.toBuilder().setType(MethodDescriptor.MethodType.SERVER_STREAMING).build();
  private static final MethodDescriptor<Integer, Integer> BIDI_STREAMING_METHOD =
      UNARY_METHOD.toBuilder().setType(MethodDescriptor.MethodType.BIDI_STREAMING).build();

  private Server server;
  private ManagedChannel channel;
  @Mock
  private ManagedChannel mockChannel;
  @Captor
  private ArgumentCaptor<MethodDescriptor<?, ?>> methodDescriptorCaptor;
  @Captor
  private ArgumentCaptor<CallOptions> callOptionsCaptor;
  private boolean originalRejectRunnableOnExecutor;

  @Before
  public void setUp() {
    originalRejectRunnableOnExecutor = ClientCalls.rejectRunnableOnExecutor;
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.shutdownNow();
    }
    if (channel != null) {
      channel.shutdownNow();
    }
    ClientCalls.rejectRunnableOnExecutor = originalRejectRunnableOnExecutor;
  }

  @Test
  public void unaryBlockingCallSuccess() throws Exception {
    Integer req = 2;
    final String resp = "bar";
    final Status status = Status.OK;
    final Metadata trailers = new Metadata();

    NoopClientCall<Integer, String> call = new NoopClientCall<Integer, String>() {
      @Override
      public void start(ClientCall.Listener<String> listener, Metadata headers) {
        listener.onMessage(resp);
        listener.onClose(status, trailers);
      }
    };

    String actualResponse = ClientCalls.blockingUnaryCall(call, req);
    assertEquals(resp, actualResponse);
  }

  @Test
  public void unaryBlockingCallFailed() throws Exception {
    Integer req = 2;
    final Status status = Status.INTERNAL.withDescription("Unique status");
    final Metadata trailers = new Metadata();

    NoopClientCall<Integer, String> call = new NoopClientCall<Integer, String>() {
      @Override
      public void start(io.grpc.ClientCall.Listener<String> listener, Metadata headers) {
        listener.onClose(status, trailers);
      }
    };

    try {
      ClientCalls.blockingUnaryCall(call, req);
      fail("Should fail");
    } catch (StatusRuntimeException e) {
      assertSame(status, e.getStatus());
      assertSame(trailers, e.getTrailers());
    }
  }

  @Test
  public void blockingUnaryCall2_success() throws Exception {
    Integer req = 2;
    final Integer resp = 3;

    class BasicUnaryResponse implements UnaryMethod<Integer, Integer> {
      Integer request;

      @Override public void invoke(Integer request, StreamObserver<Integer> responseObserver) {
        this.request = request;
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
      }
    }

    BasicUnaryResponse service = new BasicUnaryResponse();
    server = InProcessServerBuilder.forName("simple-reply").directExecutor()
        .addService(ServerServiceDefinition.builder("some")
            .addMethod(UNARY_METHOD, ServerCalls.asyncUnaryCall(service))
            .build())
        .build().start();
    channel = InProcessChannelBuilder.forName("simple-reply").directExecutor().build();
    Integer actualResponse =
        ClientCalls.blockingUnaryCall(channel, UNARY_METHOD, CallOptions.DEFAULT, req);
    assertEquals(resp, actualResponse);
    assertEquals(req, service.request);
  }

  @Test
  public void blockingUnaryCall2_interruptedWaitsForOnClose() throws Exception {
    Integer req = 2;

    class NoopUnaryMethod implements UnaryMethod<Integer, Integer> {
      ServerCallStreamObserver<Integer> observer;

      @Override public void invoke(Integer request, StreamObserver<Integer> responseObserver) {
        observer = (ServerCallStreamObserver<Integer>) responseObserver;
      }
    }

    NoopUnaryMethod methodImpl = new NoopUnaryMethod();
    server = InProcessServerBuilder.forName("noop").directExecutor()
        .addService(ServerServiceDefinition.builder("some")
            .addMethod(UNARY_METHOD, ServerCalls.asyncUnaryCall(methodImpl))
            .build())
        .build().start();

    InterruptInterceptor interceptor = new InterruptInterceptor();
    channel = InProcessChannelBuilder.forName("noop")
        .directExecutor()
        .intercept(interceptor)
        .build();
    try {
      ClientCalls.blockingUnaryCall(channel, UNARY_METHOD, CallOptions.DEFAULT, req);
      fail();
    } catch (StatusRuntimeException ex) {
      assertTrue(Thread.interrupted());
      assertTrue("interrupted", ex.getCause() instanceof InterruptedException);
    }
    assertTrue("onCloseCalled", interceptor.onCloseCalled);
    assertTrue("context not cancelled", methodImpl.observer.isCancelled());
  }

  @Test
  public void blockingUnaryCall2_rejectExecutionOnClose() throws Exception {
    Integer req = 2;

    class NoopUnaryMethod implements UnaryMethod<Integer, Integer> {
      ServerCallStreamObserver<Integer> observer;

      @Override public void invoke(Integer request, StreamObserver<Integer> responseObserver) {
        observer = (ServerCallStreamObserver<Integer>) responseObserver;
      }
    }

    NoopUnaryMethod methodImpl = new NoopUnaryMethod();
    server = InProcessServerBuilder.forName("noop").directExecutor()
        .addService(ServerServiceDefinition.builder("some")
            .addMethod(UNARY_METHOD, ServerCalls.asyncUnaryCall(methodImpl))
            .build())
        .build().start();

    InterruptInterceptor interceptor = new InterruptInterceptor();
    channel = InProcessChannelBuilder.forName("noop")
        .directExecutor()
        .intercept(interceptor)
        .build();
    try {
      ClientCalls.blockingUnaryCall(channel, UNARY_METHOD, CallOptions.DEFAULT, req);
      fail();
    } catch (StatusRuntimeException ex) {
      assertTrue(Thread.interrupted());
      assertTrue("interrupted", ex.getCause() instanceof InterruptedException);
    }
    assertTrue("onCloseCalled", interceptor.onCloseCalled);
    assertTrue("context not cancelled", methodImpl.observer.isCancelled());
    assertNotNull("callOptionsExecutor should not be null", interceptor.savedExecutor);
    ClientCalls.rejectRunnableOnExecutor = true;
    try {
      interceptor.savedExecutor.execute(() -> { });
      fail();
    } catch (Exception ex) {
      assertTrue(ex instanceof RejectedExecutionException);
    }
  }

  @Test
  public void blockingUnaryCall_HasBlockingStubType() {
    NoopClientCall<Integer, Integer> call = new NoopClientCall<Integer, Integer>() {
      @Override
      public void start(io.grpc.ClientCall.Listener<Integer> listener, Metadata headers) {
        listener.onMessage(1);
        listener.onClose(Status.OK, new Metadata());
      }
    };
    when(mockChannel.newCall(
        ArgumentMatchers.<MethodDescriptor<Integer, Integer>>any(), any(CallOptions.class)))
        .thenReturn(call);

    Integer unused =
        ClientCalls.blockingUnaryCall(mockChannel, UNARY_METHOD, CallOptions.DEFAULT, 1);

    verify(mockChannel).newCall(methodDescriptorCaptor.capture(), callOptionsCaptor.capture());
    CallOptions capturedCallOption = callOptionsCaptor.getValue();
    assertThat(capturedCallOption.getOption(ClientCalls.STUB_TYPE_OPTION))
        .isEquivalentAccordingToCompareTo(StubType.BLOCKING);
  }

  @Test
  public void blockingServerStreamingCall_HasBlockingStubType() {
    NoopClientCall<Integer, Integer> call = new NoopClientCall<Integer, Integer>() {
      @Override
      public void start(io.grpc.ClientCall.Listener<Integer> listener, Metadata headers) {
        listener.onMessage(1);
        listener.onClose(Status.OK, new Metadata());
      }
    };
    when(mockChannel.newCall(
        ArgumentMatchers.<MethodDescriptor<Integer, Integer>>any(), any(CallOptions.class)))
        .thenReturn(call);

    Iterator<Integer> unused =
        ClientCalls.blockingServerStreamingCall(mockChannel, UNARY_METHOD, CallOptions.DEFAULT, 1);

    verify(mockChannel).newCall(methodDescriptorCaptor.capture(), callOptionsCaptor.capture());
    CallOptions capturedCallOption = callOptionsCaptor.getValue();
    assertThat(capturedCallOption.getOption(ClientCalls.STUB_TYPE_OPTION))
        .isEquivalentAccordingToCompareTo(StubType.BLOCKING);
  }

  @Test
  public void unaryFutureCallSuccess() throws Exception {
    final AtomicReference<ClientCall.Listener<String>> listener =
        new AtomicReference<>();
    final AtomicReference<Integer> message = new AtomicReference<>();
    final AtomicReference<Boolean> halfClosed = new AtomicReference<>();
    NoopClientCall<Integer, String> call = new NoopClientCall<Integer, String>() {
      @Override
      public void start(io.grpc.ClientCall.Listener<String> responseListener, Metadata headers) {
        listener.set(responseListener);
      }

      @Override
      public void sendMessage(Integer msg) {
        message.set(msg);
      }

      @Override
      public void halfClose() {
        halfClosed.set(true);
      }
    };
    Integer req = 2;
    ListenableFuture<String> future = ClientCalls.futureUnaryCall(call, req);

    assertEquals(req, message.get());
    assertTrue(halfClosed.get());
    listener.get().onMessage("bar");
    listener.get().onClose(Status.OK, new Metadata());
    assertEquals("bar", future.get());
  }

  @Test
  public void unaryFutureCallFailed() throws Exception {
    final AtomicReference<ClientCall.Listener<String>> listener =
        new AtomicReference<>();
    NoopClientCall<Integer, String> call = new NoopClientCall<Integer, String>() {
      @Override
      public void start(io.grpc.ClientCall.Listener<String> responseListener, Metadata headers) {
        listener.set(responseListener);
      }
    };
    Integer req = 2;
    ListenableFuture<String> future = ClientCalls.futureUnaryCall(call, req);
    Metadata trailers = new Metadata();
    listener.get().onClose(Status.INTERNAL, trailers);
    try {
      future.get();
      fail("Should fail");
    } catch (ExecutionException e) {
      Status status = Status.fromThrowable(e);
      assertEquals(Status.INTERNAL, status);
      Metadata metadata = Status.trailersFromThrowable(e);
      assertSame(trailers, metadata);
    }
  }

  @Test
  public void unaryFutureCallCancelled() throws Exception {
    final AtomicReference<ClientCall.Listener<String>> listener =
        new AtomicReference<>();
    final AtomicReference<String> cancelMessage = new AtomicReference<>();
    final AtomicReference<Throwable> cancelCause = new AtomicReference<>();
    NoopClientCall<Integer, String> call = new NoopClientCall<Integer, String>() {
      @Override
      public void start(io.grpc.ClientCall.Listener<String> responseListener, Metadata headers) {
        listener.set(responseListener);
      }

      @Override
      public void cancel(String message, Throwable cause) {
        cancelMessage.set(message);
        cancelCause.set(cause);
      }
    };
    Integer req = 2;
    ListenableFuture<String> future = ClientCalls.futureUnaryCall(call, req);
    future.cancel(true);
    assertEquals("GrpcFuture was cancelled", cancelMessage.get());
    assertNull(cancelCause.get());
    listener.get().onMessage("bar");
    listener.get().onClose(Status.OK, new Metadata());
    try {
      future.get();
      fail("Should fail");
    } catch (CancellationException e) {
      // Expected
    }
  }

  @Test
  public void cannotSetOnReadyAfterCallStarted() throws Exception {
    NoopClientCall<Integer, String> call = new NoopClientCall<>();
    CallStreamObserver<Integer> callStreamObserver =
        (CallStreamObserver<Integer>) ClientCalls.asyncClientStreamingCall(call,
            new NoopStreamObserver<String>());
    Runnable noOpRunnable = new Runnable() {
      @Override
      public void run() {
      }
    };
    try {
      callStreamObserver.setOnReadyHandler(noOpRunnable);
      fail("Should not be able to set handler after call started");
    } catch (IllegalStateException ise) {
      // expected
    }
  }

  @Test
  public void disablingInboundAutoFlowControlSuppressesRequestsForMoreMessages()
      throws Exception {
    final AtomicReference<ClientCall.Listener<String>> listener =
        new AtomicReference<>();
    final List<Integer> requests = new ArrayList<>();
    NoopClientCall<Integer, String> call = new NoopClientCall<Integer, String>() {
      @Override
      public void start(io.grpc.ClientCall.Listener<String> responseListener, Metadata headers) {
        listener.set(responseListener);
      }

      @Override
      public void request(int numMessages) {
        requests.add(numMessages);
      }
    };
    ClientCalls.asyncBidiStreamingCall(call, new ClientResponseObserver<Integer, String>() {
      @Override
      public void beforeStart(ClientCallStreamObserver<Integer> requestStream) {
        requestStream.disableAutoRequestWithInitial(1);
      }

      @Override
      public void onNext(String value) {

      }

      @Override
      public void onError(Throwable t) {

      }

      @Override
      public void onCompleted() {

      }
    });
    listener.get().onMessage("message");
    assertThat(requests).containsExactly(1);
  }

  @Test
  public void disablingAutoRequestSuppressesRequests()
      throws Exception {
    final AtomicReference<ClientCall.Listener<String>> listener =
        new AtomicReference<>();
    final List<Integer> requests = new ArrayList<>();
    NoopClientCall<Integer, String> call = new NoopClientCall<Integer, String>() {
      @Override
      public void start(io.grpc.ClientCall.Listener<String> responseListener, Metadata headers) {
        listener.set(responseListener);
      }

      @Override
      public void request(int numMessages) {
        requests.add(numMessages);
      }
    };
    ClientCalls.asyncBidiStreamingCall(call, new ClientResponseObserver<Integer, String>() {
      @Override
      public void beforeStart(ClientCallStreamObserver<Integer> requestStream) {
        requestStream.disableAutoRequestWithInitial(0);
      }

      @Override
      public void onNext(String value) {

      }

      @Override
      public void onError(Throwable t) {

      }

      @Override
      public void onCompleted() {

      }
    });
    listener.get().onMessage("message");
    assertThat(requests).isEmpty();
  }

  @Test
  public void checkForNullInAsyncUnaryCall()  {
    NoopClientCall<Integer, String> call = new NoopClientCall<>();
    try {
      ClientCalls.asyncUnaryCall(call, Integer.valueOf(1), null);
      fail("Should have gotten an exception for the null responseObserver");
    } catch (NullPointerException e) {
      assertEquals("responseObserver", e.getMessage());
    }
  }

  @Test
  public void checkForNullInBidiCall()  {
    NoopClientCall<Integer, String> call = new NoopClientCall<>();
    try {
      ClientCalls.asyncBidiStreamingCall(call, null);
      fail("Should have gotten an exception for the null responseObserver");
    } catch (NullPointerException e) {
      assertEquals("responseObserver", e.getMessage());
    }
  }

  @Test
  public void checkForNullInClientStreamCall()  {
    NoopClientCall<Integer, String> call = new NoopClientCall<>();
    try {
      ClientCalls.asyncClientStreamingCall(call, null);
      fail("Should have gotten an exception for the null responseObserver");
    } catch (NullPointerException e) {
      assertEquals("responseObserver", e.getMessage());
    }
  }

  @Test
  public void checkForNullInServerStreamCall()  {
    NoopClientCall<Integer, String> call = new NoopClientCall<>();
    try {
      ClientCalls.asyncServerStreamingCall(call, Integer.valueOf(1), null);
      fail("Should have gotten an exception for the null responseObserver");
    } catch (NullPointerException e) {
      assertEquals("responseObserver", e.getMessage());
    }
  }

  @Test
  public void callStreamObserverPropagatesFlowControlRequestsToCall()
      throws Exception {
    ClientResponseObserver<Integer, String> responseObserver =
        new ClientResponseObserver<Integer, String>() {
          @Override
          public void beforeStart(ClientCallStreamObserver<Integer> requestStream) {
            requestStream.disableAutoRequestWithInitial(0);
          }

          @Override
          public void onNext(String value) {
          }

          @Override
          public void onError(Throwable t) {
          }

          @Override
          public void onCompleted() {
          }
        };
    final AtomicReference<ClientCall.Listener<String>> listener =
        new AtomicReference<>();
    final List<Integer> requests = new ArrayList<>();
    NoopClientCall<Integer, String> call = new NoopClientCall<Integer, String>() {
      @Override
      public void start(io.grpc.ClientCall.Listener<String> responseListener, Metadata headers) {
        listener.set(responseListener);
      }

      @Override
      public void request(int numMessages) {
        requests.add(numMessages);
      }
    };
    CallStreamObserver<Integer> requestObserver =
        (CallStreamObserver<Integer>)
            ClientCalls.asyncBidiStreamingCall(call, responseObserver);
    listener.get().onMessage("message");
    requestObserver.request(5);
    assertThat(requests).contains(5);
  }

  @Test
  public void canCaptureInboundFlowControlForServerStreamingObserver()
      throws Exception {

    class ResponseObserver implements ClientResponseObserver<Integer, String> {

      private ClientCallStreamObserver<Integer> requestStream;

      @Override
      public void beforeStart(ClientCallStreamObserver<Integer> requestStream) {
        this.requestStream = requestStream;
        requestStream.disableAutoRequestWithInitial(0);
      }

      @Override
      public void onNext(String value) {
      }

      @Override
      public void onError(Throwable t) {
      }

      @Override
      public void onCompleted() {
      }

      void request(int numMessages) {
        requestStream.request(numMessages);
      }
    }

    ResponseObserver responseObserver = new ResponseObserver();
    final AtomicReference<ClientCall.Listener<String>> listener =
        new AtomicReference<>();
    final List<Integer> requests = new ArrayList<>();
    NoopClientCall<Integer, String> call = new NoopClientCall<Integer, String>() {
      @Override
      public void start(io.grpc.ClientCall.Listener<String> responseListener, Metadata headers) {
        listener.set(responseListener);
      }

      @Override
      public void request(int numMessages) {
        requests.add(numMessages);
      }
    };
    ClientCalls.asyncServerStreamingCall(call, 1, responseObserver);
    responseObserver.request(5);
    listener.get().onMessage("message");
    assertThat(requests).containsExactly(5).inOrder();
  }

  @Test
  public void inprocessTransportInboundFlowControl() throws Exception {
    final Semaphore semaphore = new Semaphore(0);
    ServerServiceDefinition service = ServerServiceDefinition.builder(
        new ServiceDescriptor("some", BIDI_STREAMING_METHOD))
        .addMethod(BIDI_STREAMING_METHOD, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<Integer, Integer>() {
              int iteration;

              @Override
              public StreamObserver<Integer> invoke(StreamObserver<Integer> responseObserver) {
                final ServerCallStreamObserver<Integer> serverCallObserver =
                    (ServerCallStreamObserver<Integer>) responseObserver;
                serverCallObserver.setOnReadyHandler(new Runnable() {
                  @Override
                  public void run() {
                    while (serverCallObserver.isReady()) {
                      serverCallObserver.onNext(iteration);
                    }
                    iteration++;
                    semaphore.release();
                  }
                });
                return new ServerCalls.NoopStreamObserver<Integer>() {
                  @Override
                  public void onCompleted() {
                    serverCallObserver.onCompleted();
                  }
                };
              }
            }))
        .build();
    long tag = System.nanoTime();
    server = InProcessServerBuilder.forName("go-with-the-flow" + tag).directExecutor()
        .addService(service).build().start();
    channel = InProcessChannelBuilder.forName("go-with-the-flow" + tag).directExecutor().build();
    final ClientCall<Integer, Integer> clientCall = channel.newCall(BIDI_STREAMING_METHOD,
        CallOptions.DEFAULT);
    final CountDownLatch latch = new CountDownLatch(1);
    final List<Object> receivedMessages = new ArrayList<>(6);

    ClientResponseObserver<Integer, Integer> responseObserver =
        new ClientResponseObserver<Integer, Integer>() {
          @Override
          public void beforeStart(final ClientCallStreamObserver<Integer> requestStream) {
            requestStream.disableAutoRequestWithInitial(0);
          }

          @Override
          public void onNext(Integer value) {
            receivedMessages.add(value);
          }

          @Override
          public void onError(Throwable t) {
            receivedMessages.add(t);
            latch.countDown();
          }

          @Override
          public void onCompleted() {
            latch.countDown();
          }
        };

    CallStreamObserver<Integer> integerStreamObserver = (CallStreamObserver<Integer>)
        ClientCalls.asyncBidiStreamingCall(clientCall, responseObserver);
    integerStreamObserver.request(1);
    assertTrue(semaphore.tryAcquire(5, TimeUnit.SECONDS));
    integerStreamObserver.request(2);
    assertTrue(semaphore.tryAcquire(5, TimeUnit.SECONDS));
    integerStreamObserver.request(3);
    integerStreamObserver.onCompleted();
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    // Verify that number of messages produced in each onReady handler call matches the number
    // requested by the client.
    assertEquals(Arrays.asList(0, 1, 1, 2, 2, 2), receivedMessages);
  }

  @Test
  public void inprocessTransportOutboundFlowControl() throws Exception {
    final Semaphore semaphore = new Semaphore(0);
    final List<Object> receivedMessages = new ArrayList<>(6);
    final SettableFuture<ServerCallStreamObserver<Integer>> observerFuture
        = SettableFuture.create();
    ServerServiceDefinition service = ServerServiceDefinition.builder(
        new ServiceDescriptor("some", BIDI_STREAMING_METHOD))
        .addMethod(BIDI_STREAMING_METHOD, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<Integer, Integer>() {
              @Override
              public StreamObserver<Integer> invoke(StreamObserver<Integer> responseObserver) {
                final ServerCallStreamObserver<Integer> serverCallObserver =
                    (ServerCallStreamObserver<Integer>) responseObserver;
                serverCallObserver.disableAutoRequest();
                observerFuture.set(serverCallObserver);
                return new StreamObserver<Integer>() {
                  @Override
                  public void onNext(Integer value) {
                    receivedMessages.add(value);
                  }

                  @Override
                  public void onError(Throwable t) {
                    receivedMessages.add(t);
                  }

                  @Override
                  public void onCompleted() {
                    serverCallObserver.onCompleted();
                  }
                };
              }
            }))
        .build();
    long tag = System.nanoTime();
    server = InProcessServerBuilder.forName("go-with-the-flow" + tag).directExecutor()
        .addService(service).build().start();
    channel = InProcessChannelBuilder.forName("go-with-the-flow" + tag).directExecutor().build();
    final ClientCall<Integer, Integer> clientCall = channel.newCall(BIDI_STREAMING_METHOD,
        CallOptions.DEFAULT);

    final SettableFuture<Void> future = SettableFuture.create();
    ClientResponseObserver<Integer, Integer> responseObserver =
        new ClientResponseObserver<Integer, Integer>() {
          @Override
          public void beforeStart(final ClientCallStreamObserver<Integer> requestStream) {
            requestStream.setOnReadyHandler(new Runnable() {
              int iteration;

              @Override
              public void run() {
                while (requestStream.isReady()) {
                  requestStream.onNext(iteration);
                }
                iteration++;
                if (iteration == 3) {
                  requestStream.onCompleted();
                }
                semaphore.release();
              }
            });
          }

          @Override
          public void onNext(Integer value) {
          }

          @Override
          public void onError(Throwable t) {
            future.setException(t);
          }

          @Override
          public void onCompleted() {
            future.set(null);
          }
        };

    ClientCalls.asyncBidiStreamingCall(clientCall, responseObserver);
    ServerCallStreamObserver<Integer> serverCallObserver = observerFuture.get(5, TimeUnit.SECONDS);
    serverCallObserver.request(1);
    assertTrue(semaphore.tryAcquire(5, TimeUnit.SECONDS));
    serverCallObserver.request(2);
    assertTrue(semaphore.tryAcquire(5, TimeUnit.SECONDS));
    serverCallObserver.request(3);
    future.get(5, TimeUnit.SECONDS);
    // Verify that number of messages produced in each onReady handler call matches the number
    // requested by the client.
    assertEquals(Arrays.asList(0, 1, 1, 2, 2, 2), receivedMessages);
  }

  @Test
  public void blockingResponseStreamFailed() throws Exception {
    final AtomicReference<ClientCall.Listener<String>> listener =
        new AtomicReference<>();
    NoopClientCall<Integer, String> call = new NoopClientCall<Integer, String>() {
      @Override
      public void start(io.grpc.ClientCall.Listener<String> responseListener, Metadata headers) {
        listener.set(responseListener);
      }
    };

    Integer req = 2;
    Iterator<String> iter = ClientCalls.blockingServerStreamingCall(call, req);

    Metadata trailers = new Metadata();
    listener.get().onClose(Status.INTERNAL, trailers);
    try {
      iter.next();
      fail("Should fail");
    } catch (Exception e) {
      Status status = Status.fromThrowable(e);
      assertEquals(Status.INTERNAL, status);
      Metadata metadata = Status.trailersFromThrowable(e);
      assertSame(trailers, metadata);
    }
  }

  @Test
  public void blockingServerStreamingCall_interruptedWaitsForOnClose() throws Exception {
    Integer req = 2;

    class NoopServerStreamingMethod implements ServerStreamingMethod<Integer, Integer> {
      ServerCallStreamObserver<Integer> observer;

      @Override public void invoke(Integer request, StreamObserver<Integer> responseObserver) {
        observer = (ServerCallStreamObserver<Integer>) responseObserver;
      }
    }

    NoopServerStreamingMethod methodImpl = new NoopServerStreamingMethod();
    server = InProcessServerBuilder.forName("noop").directExecutor()
        .addService(ServerServiceDefinition.builder("some")
            .addMethod(SERVER_STREAMING_METHOD, ServerCalls.asyncServerStreamingCall(methodImpl))
            .build())
        .build().start();

    InterruptInterceptor interceptor = new InterruptInterceptor();
    channel = InProcessChannelBuilder.forName("noop")
        .directExecutor()
        .intercept(interceptor)
        .build();
    Iterator<Integer> iter = ClientCalls.blockingServerStreamingCall(
        channel.newCall(SERVER_STREAMING_METHOD, CallOptions.DEFAULT), req);
    try {
      iter.next();
      fail();
    } catch (StatusRuntimeException ex) {
      assertTrue(Thread.interrupted());
      assertTrue("interrupted", ex.getCause() instanceof InterruptedException);
    }
    assertTrue("onCloseCalled", interceptor.onCloseCalled);
    assertTrue("context not cancelled", methodImpl.observer.isCancelled());
  }

  @Test
  public void blockingServerStreamingCall2_success() throws Exception {
    Integer req = 2;
    final Integer resp1 = 3;
    final Integer resp2 = 4;

    class BasicServerStreamingResponse implements ServerStreamingMethod<Integer, Integer> {
      Integer request;

      @Override public void invoke(Integer request, StreamObserver<Integer> responseObserver) {
        this.request = request;
        responseObserver.onNext(resp1);
        responseObserver.onNext(resp2);
        responseObserver.onCompleted();
      }
    }

    BasicServerStreamingResponse service = new BasicServerStreamingResponse();
    server = InProcessServerBuilder.forName("simple-reply").directExecutor()
        .addService(ServerServiceDefinition.builder("some")
            .addMethod(SERVER_STREAMING_METHOD, ServerCalls.asyncServerStreamingCall(service))
            .build())
        .build().start();
    channel = InProcessChannelBuilder.forName("simple-reply").directExecutor().build();
    Iterator<Integer> iter = ClientCalls.blockingServerStreamingCall(
            channel, SERVER_STREAMING_METHOD, CallOptions.DEFAULT, req);
    assertEquals(resp1, iter.next());
    assertTrue(iter.hasNext());
    assertEquals(resp2, iter.next());
    assertFalse(iter.hasNext());
    assertEquals(req, service.request);
  }

  @Test
  public void blockingServerStreamingCall2_interruptedWaitsForOnClose() throws Exception {
    Integer req = 2;

    class NoopServerStreamingMethod implements ServerStreamingMethod<Integer, Integer> {
      ServerCallStreamObserver<Integer> observer;

      @Override public void invoke(Integer request, StreamObserver<Integer> responseObserver) {
        observer = (ServerCallStreamObserver<Integer>) responseObserver;
      }
    }

    NoopServerStreamingMethod methodImpl = new NoopServerStreamingMethod();
    server = InProcessServerBuilder.forName("noop").directExecutor()
        .addService(ServerServiceDefinition.builder("some")
            .addMethod(SERVER_STREAMING_METHOD, ServerCalls.asyncServerStreamingCall(methodImpl))
            .build())
        .build().start();

    InterruptInterceptor interceptor = new InterruptInterceptor();
    channel = InProcessChannelBuilder.forName("noop")
        .directExecutor()
        .intercept(interceptor)
        .build();
    Iterator<Integer> iter = ClientCalls.blockingServerStreamingCall(
        channel, SERVER_STREAMING_METHOD, CallOptions.DEFAULT, req);
    try {
      iter.next();
      fail();
    } catch (StatusRuntimeException ex) {
      assertTrue(Thread.interrupted());
      assertTrue("interrupted", ex.getCause() instanceof InterruptedException);
    }
    assertTrue("onCloseCalled", interceptor.onCloseCalled);
    assertTrue("context not cancelled", methodImpl.observer.isCancelled());
  }

  // Used for blocking tests to check interrupt behavior and make sure onClose is still called.
  class InterruptInterceptor implements ClientInterceptor {
    boolean onCloseCalled;
    Executor savedExecutor;

    @Override
    public <ReqT,RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
        @Override public void start(ClientCall.Listener<RespT> listener, Metadata headers) {
          super.start(new SimpleForwardingClientCallListener<RespT>(listener) {
            @Override public void onClose(Status status, Metadata trailers) {
              onCloseCalled = true;
              savedExecutor = callOptions.getExecutor();
              super.onClose(status, trailers);
            }
          }, headers);
        }

        @Override public void halfClose() {
          super.halfClose();
          Thread.currentThread().interrupt();
        }
      };
    }
  }
}
